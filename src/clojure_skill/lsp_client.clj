(ns clojure-skill.lsp-client
  "Query client for clj-lsp-bridge.

  Connects to the bridge's TCP port and sends JSON commands.
  Auto-starts bridge if not running.

  Project root is detected from --file path (walks up to find
  deps.edn/project.clj/bb.edn/shadow-cljs.edn), falling back to CWD.
  This enables multi-project support with /add-dir."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net Socket]))

;; ============================================================================
;; Project Root Detection
;; ============================================================================

(def project-markers
  "Files that indicate a Clojure project root, in priority order."
  ["deps.edn" "project.clj" "bb.edn" "shadow-cljs.edn"])

(defn detect-project-root
  "Walk up from file-path to find the nearest project root.
  Looks for deps.edn, project.clj, bb.edn, or shadow-cljs.edn.
  Resolves symlinks to canonical path to avoid duplicate bridges."
  [file-path]
  (when file-path
    (let [real (fs/real-path file-path)
          start-dir (if (fs/directory? real) real (fs/parent real))]
      (loop [dir start-dir]
        (when (and dir (not= (str dir) "/"))
          (if (some #(fs/exists? (fs/path dir %)) project-markers)
            (str dir)
            (recur (fs/parent dir))))))))

(defn resolve-root
  "Resolve project root: detect from file path, or fall back to CWD."
  [{:keys [file]}]
  (or (when file (detect-project-root file))
      (System/getProperty "user.dir")))

;; ============================================================================
;; Bridge Discovery
;; ============================================================================

(defn lsp-dir [root]
  (str (fs/path root ".lsp")))

(defn pid-file [root]
  (str (fs/path (lsp-dir root) ".clj-lsp-bridge.pid")))

(defn port-file [root]
  (str (fs/path (lsp-dir root) ".clj-lsp-bridge.port")))

(defn read-port [root]
  (try
    (when (fs/exists? (port-file root))
      (parse-long (str/trim (slurp (port-file root)))))
    (catch Exception _ nil)))

(defn read-pid [root]
  (try
    (when (fs/exists? (pid-file root))
      (parse-long (str/trim (slurp (pid-file root)))))
    (catch Exception _ nil)))

(defn bridge-running?
  "Check if bridge process is alive by PID."
  [root]
  (when-let [pid (read-pid root)]
    (try
      (when-let [ph (java.lang.ProcessHandle/of pid)]
        (when-let [handle (.orElse ph nil)]
          (.isAlive handle)))
      (catch Exception _ false))))

;; ============================================================================
;; Bridge Auto-Start
;; ============================================================================

(defn start-bridge!
  "Start bridge in background. Waits up to 180s for port file to appear.
  Large projects (shadow-cljs, monorepos) can take over 60s to initialize."
  [root]
  (println (str "Starting clj-lsp-bridge for " root "..."))
  (let [script-name "clj-lsp-bridge"]
    (process/process [script-name "start" root]
                     {:dir root
                      :out :inherit
                      :err :inherit})
    ;; Wait for port file (360 attempts * 500ms = 180s)
    (loop [attempts 0]
      (cond
        (>= attempts 360)
        (do
          (println "Error: Bridge did not start within 180 seconds")
          (System/exit 1))

        (and (fs/exists? (port-file root))
             (bridge-running? root))
        (do
          (println (str "Bridge started on port " (read-port root)))
          true)

        :else
        (do
          (Thread/sleep 500)
          (recur (inc attempts)))))))

(defn ensure-bridge!
  "Ensure bridge is running, auto-start if not."
  [root]
  (when-not (bridge-running? root)
    (start-bridge! root)))

;; ============================================================================
;; TCP Client
;; ============================================================================

(defn send-command
  "Send a JSON command to bridge and return parsed response."
  [root command-map]
  (let [port (read-port root)]
    (when-not port
      (println "Error: No bridge port file found. Is the bridge running?")
      (System/exit 1))
    (try
      (with-open [sock (Socket. "127.0.0.1" (int port))
                  out (io/writer (.getOutputStream sock))
                  in (io/reader (.getInputStream sock))]
        (.setSoTimeout sock 30000)
        (.write out (json/generate-string command-map))
        (.write out "\n")
        (.flush out)
        (let [line (.readLine in)]
          (when line
            (json/parse-string line true))))
      (catch java.net.ConnectException _
        (println "Error: Cannot connect to bridge. It may have stopped.")
        nil)
      (catch Exception e
        (println (str "Error: " (.getMessage e)))
        nil))))

;; ============================================================================
;; Command Handlers
;; ============================================================================

(defn cmd-start [root]
  (if (bridge-running? root)
    (println (str "Bridge already running for " root " (PID " (read-pid root) ", port " (read-port root) ")"))
    (start-bridge! root)))

(defn cmd-stop [root]
  (if-let [pid (read-pid root)]
    (do
      (send-command root {:command "stop"})
      ;; Give it a moment to clean up
      (Thread/sleep 500)
      ;; Force kill if still running
      (when (bridge-running? root)
        (try
          (when-let [ph (java.lang.ProcessHandle/of pid)]
            (when-let [handle (.orElse ph nil)]
              (.destroyForcibly handle)))
          (catch Exception _ nil)))
      (fs/delete-if-exists (pid-file root))
      (fs/delete-if-exists (port-file root))
      (println (str "Bridge stopped for " root ".")))
    (println (str "No bridge running for " root "."))))

(defn cmd-status [root]
  (if (bridge-running? root)
    (let [resp (send-command root {:command "status"})]
      (println (str "Bridge: running for " root " (PID " (read-pid root) ", port " (read-port root) ")"))
      (when resp
        (println (str "Diagnostics cached: " (:diagnostics-count resp) " files"))))
    (println (str "Bridge: not running for " root))))

(defn cmd-diagnostics [root {:keys [file]}]
  (ensure-bridge! root)
  (let [resp (send-command root (cond-> {:command "diagnostics"}
                                  file (assoc :file (str (fs/absolutize file)))))]
    (when resp
      (if file
        ;; Single file diagnostics
        (let [diags (:diagnostics resp)]
          (if (seq diags)
            (doseq [d diags]
              (let [range (:range d)
                    start (:start range)]
                (println (format "%s:%d:%d: %s - %s"
                                 file
                                 (inc (:line start))
                                 (:character start)
                                 (get {1 "Error" 2 "Warning" 3 "Info" 4 "Hint"} (:severity d) "Unknown")
                                 (:message d)))))
            (println (str "No diagnostics for " file))))
        ;; All diagnostics
        (let [all-diags (:diagnostics resp)]
          (if (seq all-diags)
            (doseq [[uri diags] all-diags]
              (when (seq diags)
                (doseq [d diags]
                  (let [range (:range d)
                        start (:start range)
                        fpath (str/replace-first (name uri) #"^file://" "")]
                    (println (format "%s:%d:%d: %s - %s"
                                     fpath
                                     (inc (:line start))
                                     (:character start)
                                     (get {1 "Error" 2 "Warning" 3 "Info" 4 "Hint"} (:severity d) "Unknown")
                                     (:message d)))))))
            (println "No diagnostics.")))))))

(defn cmd-references [root {:keys [file line col]}]
  (ensure-bridge! root)
  (let [abs-file (str (fs/absolutize file))
        resp (send-command root {:command "references"
                                 :file abs-file
                                 :line (parse-long line)
                                 :col (parse-long col)})]
    (when resp
      (if-let [refs (seq (:references resp))]
        (doseq [r refs]
          (println (format "%s:%d:%d" (:file r) (:line r) (:col r))))
        (println "No references found.")))))

(defn cmd-definition [root {:keys [file line col]}]
  (ensure-bridge! root)
  (let [abs-file (str (fs/absolutize file))
        resp (send-command root {:command "definition"
                                 :file abs-file
                                 :line (parse-long line)
                                 :col (parse-long col)})]
    (when resp
      (if-let [defs (seq (:definitions resp))]
        (doseq [d defs]
          (println (format "%s:%d:%d" (:file d) (:line d) (:col d))))
        (println "Definition not found.")))))

(defn cmd-hover [root {:keys [file line col]}]
  (ensure-bridge! root)
  (let [abs-file (str (fs/absolutize file))
        resp (send-command root {:command "hover"
                                 :file abs-file
                                 :line (parse-long line)
                                 :col (parse-long col)})]
    (when resp
      (if-let [hover (:hover resp)]
        (cond
          (string? hover) (println hover)
          (:value hover) (println (:value hover))
          :else (println (json/generate-string hover {:pretty true})))
        (println "No hover info.")))))

;; ============================================================================
;; CLI
;; ============================================================================

(defn show-help []
  (println "clj-lsp-client - Query interface for clj-lsp-bridge")
  (println)
  (println "Usage:")
  (println "  clj-lsp-client start [--project-root PATH]     Start bridge (idempotent)")
  (println "  clj-lsp-client stop [--project-root PATH]      Stop bridge")
  (println "  clj-lsp-client status [--project-root PATH]    Check bridge status")
  (println "  clj-lsp-client diagnostics [--file PATH]       Get diagnostics")
  (println "  clj-lsp-client references --file PATH --line N --col N")
  (println "  clj-lsp-client definition --file PATH --line N --col N")
  (println "  clj-lsp-client hover --file PATH --line N --col N")
  (println)
  (println "Project root is auto-detected from --file path (walks up to find")
  (println "deps.edn/project.clj/bb.edn/shadow-cljs.edn). Falls back to CWD.")
  (println "Use --project-root to override explicitly.")
  (println)
  (println "The bridge is auto-started per project when needed."))

(defn parse-kv-args
  "Parse --key value pairs from args into a map."
  [args]
  (loop [args args
         result {}]
    (if (empty? args)
      result
      (let [arg (first args)]
        (if (str/starts-with? arg "--")
          (let [k (keyword (subs arg 2))
                v (second args)]
            (recur (drop 2 args) (assoc result k v)))
          (recur (rest args) result))))))

(defn -main [& args]
  (let [command (first args)
        rest-args (rest args)
        kv (parse-kv-args rest-args)
        ;; Resolve root: explicit --project-root > detect from --file > CWD
        root (or (:project-root kv)
                 (resolve-root kv))]
    (case command
      "start" (cmd-start root)
      "stop" (cmd-stop root)
      "status" (cmd-status root)
      "diagnostics" (cmd-diagnostics root kv)
      "references" (cmd-references root kv)
      "definition" (cmd-definition root kv)
      "hover" (cmd-hover root kv)
      (nil "--help" "-h") (show-help)
      (do
        (println (str "Unknown command: " command))
        (show-help)
        (System/exit 1)))))
