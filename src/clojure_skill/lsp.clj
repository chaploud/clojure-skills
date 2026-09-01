(ns clojure-skill.lsp
  "Static code intelligence, answered by clojure-lsp through clj-lsp-bridge.

  The bridge is started per project and reused, because clojure-lsp's startup
  analysis is what makes the first query expensive; the project root is found by
  walking up from the queried file, so several projects added with /add-dir each
  get their own."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net Socket]))

(def project-markers
  "Files that mark a Clojure project root, in priority order."
  ["deps.edn" "project.clj" "bb.edn" "shadow-cljs.edn"])

(defn detect-project-root
  "Nearest ancestor of file-path holding a project marker.

  Symlinks are resolved first so that a file reached through a link and the same
  file reached directly do not start two bridges for one project."
  [file-path]
  (when file-path
    (let [real (fs/real-path file-path)
          start (if (fs/directory? real) real (fs/parent real))]
      (loop [dir start]
        (when (and dir (not= (str dir) "/"))
          (if (some #(fs/exists? (fs/path dir %)) project-markers)
            (str dir)
            (recur (fs/parent dir))))))))

(defn resolve-root
  "Project root for a query: an explicit root, else the queried file's project,
  else the working directory."
  [{:keys [project-root file]}]
  (or project-root
      (when file (detect-project-root file))
      (System/getProperty "user.dir")))

;; ============================================================================
;; Bridge lifecycle
;; ============================================================================

(defn pid-file [root] (str (fs/path root ".lsp" ".clj-lsp-bridge.pid")))
(defn port-file [root] (str (fs/path root ".lsp" ".clj-lsp-bridge.port")))

(defn- read-number [path]
  (try
    (when (fs/exists? path)
      (parse-long (str/trim (slurp path))))
    (catch Exception _ nil)))

(defn read-port [root] (read-number (port-file root)))
(defn read-pid [root] (read-number (pid-file root)))

(defn running?
  "Whether the bridge process recorded for root is still alive."
  [root]
  (boolean
   (when-let [pid (read-pid root)]
     (try
       (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) .isAlive)
       (catch Exception _ false)))))

(def ^:private start-timeout-ms 180000)
(def ^:private start-poll-ms 500)

(defn start!
  "Start the bridge for root in the background and wait until it is listening.

  A cold clojure-lsp indexing a monorepo can take minutes, so the wait is long;
  `clj-skill lsp-bridge warm ROOT` populates the cache ahead of time."
  [root]
  (println (format ";; starting clj-lsp-bridge for %s …" root))
  (process/process ["clj-skill" "lsp-bridge" "start" root]
                   {:dir root :out :inherit :err :inherit})
  (loop [waited 0]
    (cond
      (and (fs/exists? (port-file root)) (running? root))
      (do (println (format ";; bridge listening on port %s" (read-port root))) 0)

      (>= waited start-timeout-ms)
      (do (binding [*out* *err*]
            (println (format "bridge did not start within %ds" (quot start-timeout-ms 1000))))
          1)

      :else
      (do (Thread/sleep start-poll-ms) (recur (+ waited start-poll-ms))))))

(defn- ensure! [root]
  (when-not (running? root) (start! root)))

(defn send-command
  "Send one JSON command to the bridge and return its parsed response."
  [root command-map]
  (if-let [port (read-port root)]
    (try
      (with-open [sock (Socket. "127.0.0.1" (int port))
                  out (io/writer (.getOutputStream sock))
                  in (io/reader (.getInputStream sock))]
        (.setSoTimeout sock 30000)
        (.write out (json/generate-string command-map))
        (.write out "\n")
        (.flush out)
        (some-> (.readLine in) (json/parse-string true)))
      (catch java.net.ConnectException _
        (binding [*out* *err*] (println "bridge is not accepting connections; it may have stopped."))
        nil)
      (catch Exception e
        (binding [*out* *err*] (println (str "bridge error: " (ex-message e))))
        nil))
    (do (binding [*out* *err*] (println (format "no bridge port file for %s" root)))
        nil)))

;; ============================================================================
;; Queries
;; ============================================================================

(def ^:private severities {1 "error" 2 "warning" 3 "info" 4 "hint"})

(defn- print-diagnostic [path {:keys [range severity message]}]
  (let [{:keys [line character]} (:start range)]
    (println (format "%s:%d:%d: %s: %s"
                     path (inc line) (inc character)
                     (get severities severity "unknown") message))))

(defn diagnostics [root {:keys [file]}]
  (ensure! root)
  (when-let [resp (send-command root (cond-> {:command "diagnostics"}
                                       file (assoc :file (str (fs/absolutize file)))))]
    (let [diags (:diagnostics resp)]
      (if file
        (if (seq diags)
          (run! #(print-diagnostic file %) diags)
          (println (format ";; no diagnostics for %s" file)))
        (let [any? (atom false)]
          (doseq [[uri file-diags] diags
                  :when (seq file-diags)
                  :let [path (str/replace-first (name uri) #"^file://" "")]
                  d file-diags]
            (reset! any? true)
            (print-diagnostic path d))
          (when-not @any? (println ";; no diagnostics"))))))
  0)

(defn- print-locations [locations empty-msg]
  (if (seq locations)
    (doseq [{:keys [file line col]} locations]
      (println (format "%s:%d:%d" file line col)))
    (println (str ";; " empty-msg)))
  0)

(defn- position-query [root command {:keys [file line col]} result-key empty-msg]
  (ensure! root)
  (let [resp (send-command root {:command command
                                 :file (str (fs/absolutize file))
                                 :line (parse-long (str line))
                                 :col (parse-long (str col))})]
    (print-locations (get resp result-key) empty-msg)))

(defn references [root opts]
  (position-query root "references" opts :references "no references"))

(defn definition [root opts]
  (position-query root "definition" opts :definitions "definition not found"))

(defn hover [root {:keys [file line col]}]
  (ensure! root)
  (let [resp (send-command root {:command "hover"
                                 :file (str (fs/absolutize file))
                                 :line (parse-long (str line))
                                 :col (parse-long (str col))})
        contents (:hover resp)]
    (println (cond
               (nil? contents) ";; no hover info"
               (string? contents) contents
               (:value contents) (:value contents)
               :else (json/generate-string contents {:pretty true}))))
  0)

;; ============================================================================
;; Lifecycle commands
;; ============================================================================

(defn start [root]
  (if (running? root)
    (do (println (format ";; bridge already running for %s (pid %s, port %s)"
                         root (read-pid root) (read-port root)))
        0)
    (start! root)))

(defn stop [root]
  (if-let [pid (read-pid root)]
    (do
      (send-command root {:command "stop"})
      (Thread/sleep 500)
      (when (running? root)
        (try
          (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) .destroyForcibly)
          (catch Exception _ nil)))
      (fs/delete-if-exists (pid-file root))
      (fs/delete-if-exists (port-file root))
      (println (format ";; bridge stopped for %s" root)))
    (println (format ";; no bridge running for %s" root)))
  0)

(defn status [root]
  (if (running? root)
    (let [resp (send-command root {:command "status"})]
      (println (format ";; bridge running for %s (pid %s, port %s), %s file(s) with diagnostics"
                       root (read-pid root) (read-port root)
                       (:diagnostics-count resp 0))))
    (println (format ";; no bridge running for %s" root)))
  0)
