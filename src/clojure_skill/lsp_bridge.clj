(ns clojure-skill.lsp-bridge
  "TCP bridge server wrapping clojure-lsp in LSP stdio mode.

  Architecture:
  - Starts clojure-lsp as a subprocess communicating via stdio (JSON-RPC)
  - Listens on a localhost TCP port for client queries
  - Caches textDocument/publishDiagnostics notifications
  - Translates simple client commands into LSP requests

  Files written:
  - .lsp/.clj-lsp-bridge.port  — TCP port number
  - .lsp/.clj-lsp-bridge.pid   — bridge process PID"
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader OutputStream]
           [java.net ServerSocket Socket InetAddress]
           [java.nio.charset StandardCharsets]))

;; ============================================================================
;; LSP JSON-RPC Framing
;; ============================================================================

(defn write-lsp-message
  "Write a JSON-RPC message with Content-Length header to an OutputStream."
  [^OutputStream out msg-map]
  (let [json-str (json/generate-string msg-map)
        bytes (.getBytes json-str StandardCharsets/UTF_8)
        header (str "Content-Length: " (alength bytes) "\r\n\r\n")]
    (.write out (.getBytes header StandardCharsets/UTF_8))
    (.write out bytes)
    (.flush out)))

(defn read-lsp-message
  "Read a JSON-RPC message from a BufferedReader.
  Parses Content-Length header, reads body, returns parsed JSON map.
  Returns nil on EOF."
  [^BufferedReader reader]
  (try
    (loop [content-length nil]
      (let [line (.readLine reader)]
        (cond
          (nil? line) nil ;; EOF

          (str/blank? line)
          ;; End of headers — read body
          (if content-length
            (let [buf (char-array content-length)]
              (loop [offset 0]
                (when (< offset content-length)
                  (let [n (.read reader buf offset (- content-length offset))]
                    (when (pos? n)
                      (recur (+ offset n))))))
              (json/parse-string (String. buf) true))
            (recur nil))

          :else
          (let [[_ len-str] (re-matches #"Content-Length:\s*(\d+)" line)]
            (recur (if len-str (parse-long len-str) content-length))))))
    (catch Exception _
      nil)))

;; ============================================================================
;; LSP Request/Response Management
;; ============================================================================

(def ^:private request-id (atom 0))

(defn next-request-id []
  (swap! request-id inc))

(def ^:private pending-requests
  "Map of request-id -> promise for pending LSP responses"
  (atom {}))

(def ^:private diagnostics-cache
  "Map of file-uri -> diagnostics vector"
  (atom {}))

(defn make-lsp-request
  "Create an LSP JSON-RPC request map."
  [method params]
  (let [id (next-request-id)]
    {:jsonrpc "2.0"
     :id id
     :method method
     :params params}))

(defn make-lsp-notification
  "Create an LSP JSON-RPC notification map (no id)."
  [method params]
  {:jsonrpc "2.0"
   :method method
   :params params})

;; ============================================================================
;; LSP Process Management
;; ============================================================================

(defn file->uri [path]
  (str (.toURI (io/file path))))

(defn uri->file [uri]
  (str/replace-first uri #"^file://" ""))

(defn start-lsp-process
  "Start clojure-lsp as subprocess in stdio mode.
  Returns process object."
  [project-root]
  (let [pb (ProcessBuilder. ["clojure-lsp" "--stdio"])
        _ (.directory pb (io/file project-root))
        env (.environment pb)]
    ;; Don't inherit parent env's CLASSPATH to avoid conflicts
    (.remove env "CLASSPATH")
    (.start pb)))

(defn initialize-lsp
  "Send initialize and initialized to clojure-lsp.
  Returns the initialize response."
  [lsp-out lsp-reader project-root]
  (let [init-req (make-lsp-request
                  "initialize"
                  {:processId (.pid (java.lang.ProcessHandle/current))
                   :rootUri (file->uri project-root)
                   :capabilities
                   {:textDocument
                    {:publishDiagnostics {:relatedInformation true}
                     :hover {:contentFormat ["markdown" "plaintext"]}
                     :definition {:linkSupport true}
                     :references {}}}})
        id (:id init-req)]
    (write-lsp-message lsp-out init-req)
    ;; Read messages until we get the initialize response
    (loop []
      (let [msg (read-lsp-message lsp-reader)]
        (cond
          (nil? msg) (throw (ex-info "LSP process terminated during initialize" {}))

          ;; Got our response
          (= (:id msg) id)
          (do
            ;; Send initialized notification
            (write-lsp-message lsp-out (make-lsp-notification "initialized" {}))
            msg)

          ;; Cache diagnostics that arrive during init
          (= (:method msg) "textDocument/publishDiagnostics")
          (let [uri (get-in msg [:params :uri])
                diags (get-in msg [:params :diagnostics])]
            (swap! diagnostics-cache assoc uri diags)
            (recur))

          :else (recur))))))

;; ============================================================================
;; LSP Message Router (reads from LSP process)
;; ============================================================================

(defn start-lsp-reader-loop
  "Background thread that reads messages from clojure-lsp and routes them:
  - Responses (with :id) -> deliver to pending-requests promise
  - Diagnostics notifications -> cache
  - Other notifications -> ignore"
  [lsp-reader]
  (future
    (try
      (loop []
        (when-let [msg (read-lsp-message lsp-reader)]
          ;; Route message
          (cond
            ;; Response to a request
            (:id msg)
            (when-let [p (get @pending-requests (:id msg))]
              (deliver p msg)
              (swap! pending-requests dissoc (:id msg)))

            ;; Diagnostics notification
            (= (:method msg) "textDocument/publishDiagnostics")
            (let [uri (get-in msg [:params :uri])
                  diags (get-in msg [:params :diagnostics])]
              (swap! diagnostics-cache assoc uri diags))

            ;; Other notifications — ignore
            :else nil)
          (recur)))
      (catch Exception _
        ;; Reader closed, LSP process likely terminated
        nil))))

(defn send-lsp-request!
  "Send a request to clojure-lsp and wait for response.
  Returns the response map or nil on timeout."
  [lsp-out method params & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [req (make-lsp-request method params)
        id (:id req)
        p (promise)]
    (swap! pending-requests assoc id p)
    (write-lsp-message lsp-out req)
    (let [result (deref p timeout-ms ::timeout)]
      (swap! pending-requests dissoc id)
      (when (not= result ::timeout)
        result))))

;; ============================================================================
;; Client Command Handlers
;; ============================================================================

(defn handle-diagnostics
  "Return cached diagnostics, optionally filtered by file."
  [{:keys [file]}]
  (if file
    (let [uri (file->uri file)]
      {:diagnostics (get @diagnostics-cache uri [])
       :file file
       :uri uri})
    {:diagnostics @diagnostics-cache}))

(defn handle-references
  "Find references for symbol at position."
  [lsp-out {:keys [file line col]}]
  (let [uri (file->uri file)
        resp (send-lsp-request! lsp-out "textDocument/references"
                                {:textDocument {:uri uri}
                                 :position {:line (dec line) :character col}
                                 :context {:includeDeclaration true}})]
    {:references
     (mapv (fn [loc]
             {:file (uri->file (:uri loc))
              :line (inc (get-in loc [:range :start :line]))
              :col (get-in loc [:range :start :character])
              :end-line (inc (get-in loc [:range :end :line]))
              :end-col (get-in loc [:range :end :character])})
           (:result resp))}))

(defn handle-definition
  "Go to definition for symbol at position."
  [lsp-out {:keys [file line col]}]
  (let [uri (file->uri file)
        resp (send-lsp-request! lsp-out "textDocument/definition"
                                {:textDocument {:uri uri}
                                 :position {:line (dec line) :character col}})]
    (let [result (:result resp)
          ;; clojure-lsp may return a single location or array
          locs (if (map? result) [result] result)]
      {:definitions
       (mapv (fn [loc]
               {:file (uri->file (:uri loc))
                :line (inc (get-in loc [:range :start :line]))
                :col (get-in loc [:range :start :character])})
             locs)})))

(defn handle-hover
  "Get hover information for symbol at position."
  [lsp-out {:keys [file line col]}]
  (let [uri (file->uri file)
        resp (send-lsp-request! lsp-out "textDocument/hover"
                                {:textDocument {:uri uri}
                                 :position {:line (dec line) :character col}})]
    {:hover (get-in resp [:result :contents])}))

(defn handle-client-command
  "Dispatch client command and return response map."
  [lsp-out {:keys [command] :as request}]
  (case command
    "status" {:status "running"
              :diagnostics-count (count @diagnostics-cache)}
    "diagnostics" (handle-diagnostics request)
    "references" (handle-references lsp-out request)
    "definition" (handle-definition lsp-out request)
    "hover" (handle-hover lsp-out request)
    "stop" {:status "stopping"}
    {:error (str "Unknown command: " command)}))

;; ============================================================================
;; TCP Server for Client Connections
;; ============================================================================

(defn handle-client-connection
  "Handle a single client TCP connection.
  Reads JSON command, executes, writes JSON response."
  [lsp-out ^Socket client-socket]
  (try
    (with-open [in (io/reader (.getInputStream client-socket))
                out (io/writer (.getOutputStream client-socket))]
      (let [line (.readLine in)]
        (when line
          (let [request (json/parse-string line true)
                response (handle-client-command lsp-out request)]
            (.write out (json/generate-string response))
            (.write out "\n")
            (.flush out)))))
    (catch Exception e
      (try
        (with-open [out (io/writer (.getOutputStream client-socket))]
          (.write out (json/generate-string {:error (.getMessage e)}))
          (.write out "\n")
          (.flush out))
        (catch Exception _ nil)))
    (finally
      (.close client-socket))))

(defn start-tcp-server
  "Start TCP server listening on localhost with random port.
  Returns [server-socket port]."
  []
  (let [server (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort server)]
    [server port]))

;; ============================================================================
;; PID/Port File Management
;; ============================================================================

(defn lsp-dir [project-root]
  (str (fs/path project-root ".lsp")))

(defn pid-file [project-root]
  (str (fs/path (lsp-dir project-root) ".clj-lsp-bridge.pid")))

(defn port-file [project-root]
  (str (fs/path (lsp-dir project-root) ".clj-lsp-bridge.port")))

(defn write-pid-port-files!
  "Write PID and port files to .lsp/ directory."
  [project-root port]
  (let [dir (lsp-dir project-root)]
    (fs/create-dirs dir)
    (spit (pid-file project-root) (str (.pid (java.lang.ProcessHandle/current))))
    (spit (port-file project-root) (str port))))

(defn cleanup-pid-port-files!
  "Remove PID and port files."
  [project-root]
  (fs/delete-if-exists (pid-file project-root))
  (fs/delete-if-exists (port-file project-root)))

(defn read-bridge-pid
  "Read bridge PID from file. Returns nil if not found."
  [project-root]
  (try
    (when (fs/exists? (pid-file project-root))
      (parse-long (str/trim (slurp (pid-file project-root)))))
    (catch Exception _ nil)))

(defn read-bridge-port
  "Read bridge port from file. Returns nil if not found."
  [project-root]
  (try
    (when (fs/exists? (port-file project-root))
      (parse-long (str/trim (slurp (port-file project-root)))))
    (catch Exception _ nil)))

;; ============================================================================
;; Bridge Stop
;; ============================================================================

(defn stop-bridge
  "Stop a running bridge by PID."
  [project-root]
  (when-let [pid (read-bridge-pid project-root)]
    (try
      (when-let [ph (java.lang.ProcessHandle/of pid)]
        (when-let [handle (.orElse ph nil)]
          (.destroy handle)
          (println (str "Stopped bridge (PID " pid ")"))))
      (catch Exception e
        (println (str "Warning: could not stop PID " pid ": " (.getMessage e)))))
    (cleanup-pid-port-files! project-root)))

;; ============================================================================
;; Main Bridge Loop
;; ============================================================================

(defn run-bridge
  "Main bridge entry point. Starts clojure-lsp, TCP server, and serves clients."
  [project-root]
  (let [project-root (or project-root (System/getProperty "user.dir"))
        _ (println (str "Starting clj-lsp-bridge for " project-root))

        ;; Stop any existing bridge
        _ (stop-bridge project-root)

        ;; Start clojure-lsp subprocess
        lsp-process (start-lsp-process project-root)
        lsp-out (.getOutputStream lsp-process)
        lsp-reader (BufferedReader. (InputStreamReader. (.getInputStream lsp-process) StandardCharsets/UTF_8))

        ;; Initialize LSP protocol
        _ (println "Initializing clojure-lsp...")
        _init-resp (initialize-lsp lsp-out lsp-reader project-root)
        _ (println "clojure-lsp initialized.")

        ;; Start background reader loop
        _reader-future (start-lsp-reader-loop lsp-reader)

        ;; Start TCP server
        [^ServerSocket server-socket tcp-port] (start-tcp-server)
        _ (write-pid-port-files! project-root tcp-port)
        _ (println (str "Bridge listening on 127.0.0.1:" tcp-port))

        ;; Shutdown hook
        running (atom true)]

    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (reset! running false)
                                 (try (.close server-socket) (catch Exception _ nil))
                                 (try (.destroy lsp-process) (catch Exception _ nil))
                                 (cleanup-pid-port-files! project-root))))

    ;; Accept client connections
    (try
      (while @running
        (try
          (let [client (.accept server-socket)]
            (.setSoTimeout client 10000)
            (future (handle-client-connection lsp-out client)))
          (catch java.net.SocketException _
            ;; Server socket closed during shutdown
            (reset! running false))))
      (finally
        (try (.close server-socket) (catch Exception _ nil))
        (try (.destroy lsp-process) (catch Exception _ nil))
        (cleanup-pid-port-files! project-root)))))

;; ============================================================================
;; CLI
;; ============================================================================

(defn show-help []
  (println "clj-lsp-bridge - TCP bridge wrapping clojure-lsp")
  (println)
  (println "Usage:")
  (println "  clj-lsp-bridge start [PROJECT-ROOT]   Start bridge (default: cwd)")
  (println "  clj-lsp-bridge stop [PROJECT-ROOT]    Stop running bridge")
  (println)
  (println "The bridge starts clojure-lsp as a subprocess, communicates via")
  (println "LSP stdio protocol, and listens on a localhost TCP port for")
  (println "client queries from clj-lsp-client.")
  (println)
  (println "Files created in PROJECT-ROOT/.lsp/:")
  (println "  .clj-lsp-bridge.port  — TCP port number")
  (println "  .clj-lsp-bridge.pid   — bridge process PID"))

(defn -main [& args]
  (let [command (first args)]
    (case command
      ("--help" "-h") (show-help)
      "start" (let [project-root (or (second args) (System/getProperty "user.dir"))]
                (run-bridge project-root))
      "stop"  (let [project-root (or (second args) (System/getProperty "user.dir"))]
                (stop-bridge project-root))
      (nil) (run-bridge (System/getProperty "user.dir"))
      ;; Unknown arg — show help
      (do
        (println (str "Unknown command: " command))
        (println)
        (show-help)
        (System/exit 1)))))
