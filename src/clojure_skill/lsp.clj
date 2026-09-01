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
            [clojure.string :as str]
            [clojure-skill.tmp :as tmp])
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

(defn log-file
  "Where the bridge writes its own output.

  Kept outside the project: a log inside .lsp/ shows up as an untracked file in
  every repo whose .gitignore predates it, and nothing about a debug log needs to
  live in the user's working tree."
  [root]
  (str (fs/path (or (System/getenv "XDG_CACHE_HOME") (fs/path (fs/home) ".cache"))
                "clj-skill" "lsp-bridge"
                (str (tmp/sha1 (str (fs/absolutize root))) ".log"))))

(defn- read-number [path]
  (try
    (when (fs/exists? path)
      (parse-long (str/trim (slurp path))))
    (catch Exception _ nil)))

(defn read-port [root] (read-number (port-file root)))
(defn read-pid [root] (read-number (pid-file root)))

(defn running?
  "Whether a bridge is usable for root.

  Both the recorded pid and the port file must be there: after a reboot the pid
  can be reused by an unrelated process, and treating that as a live bridge makes
  every query fail at connect time instead of restarting the bridge."
  [root]
  (boolean
   (when (fs/exists? (port-file root))
     (when-let [pid (read-pid root)]
       (try
         (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) .isAlive)
         (catch Exception _ false))))))

(def ^:private start-timeout-ms 180000)
(def ^:private start-poll-ms 500)

(def ^:private progress-every-ms 15000)

(defn start!
  "Start the bridge for root in the background and wait until it is listening.

  Indexing a cold monorepo can take minutes, so progress is reported while
  waiting — silence here is indistinguishable from a hang."
  [root]
  (println (format ";; clojure-lsp is indexing %s — the first query waits for this." root))
  (println ";; run `clj-skill lsp-bridge warm ROOT` ahead of time to avoid the wait.")
  (flush)
  ;; The bridge outlives this process, so it must not inherit our stdout: holding
  ;; the write end of the pipe open keeps a `… | head` from ever seeing EOF, and
  ;; the caller hangs long after the query itself finished. Its output goes to a
  ;; log beside the pid and port files instead.
  (let [log (fs/file (log-file root))]
    (fs/create-dirs (fs/parent log))
    (process/process ["clj-skill" "lsp-bridge" "start" root]
                     {:dir root :out log :err log}))
  (loop [waited 0]
    (cond
      (and (fs/exists? (port-file root)) (running? root))
      (do (println (format ";; bridge listening on port %s after %ds"
                           (read-port root) (quot waited 1000)))
          0)

      (>= waited start-timeout-ms)
      (do (binding [*out* *err*]
            (println (format "clojure-lsp did not finish indexing within %ds — see %s"
                             (quot start-timeout-ms 1000) (log-file root))))
          1)

      :else
      (do (when (and (pos? waited) (zero? (mod waited progress-every-ms)))
            (println (format ";; still indexing (%ds)…" (quot waited 1000)))
            (flush))
          (Thread/sleep start-poll-ms)
          (recur (+ waited start-poll-ms))))))

(defn- ensure!
  "Start the bridge if it is not up, and abort when it cannot be started, so a
  query is never run against a bridge that failed to come up."
  [root]
  (when-not (running? root)
    (when-not (zero? (start! root))
      (throw (ex-info (str "could not start the clojure-lsp bridge for " root) {:root root})))))

(defn send-command
  "Send one JSON command to the bridge and return its parsed response.

  Throws on every failure rather than returning nil. A query that never reached
  clojure-lsp must not print the same thing as a query that ran and found
  nothing — an agent reads \"no references\" as permission to delete the var."
  [root command-map]
  (let [port (or (read-port root)
                 (throw (ex-info (format "no bridge is running for %s" root) {:root root})))
        resp (try
               (with-open [sock (Socket. "127.0.0.1" (int port))
                           out (io/writer (.getOutputStream sock))
                           in (io/reader (.getInputStream sock))]
                 (.setSoTimeout sock 60000)
                 (.write out (json/generate-string command-map))
                 (.write out "\n")
                 (.flush out)
                 (some-> (.readLine in) (json/parse-string true)))
               (catch java.net.ConnectException _
                 (throw (ex-info (str "bridge is not accepting connections on port " port
                                      "; stop it with `clj-skill lsp stop` and retry")
                                 {:root root})))
               (catch Exception e
                 (throw (ex-info (str "bridge error: " (ex-message e)) {:root root}))))]
    (when-not resp
      (throw (ex-info "bridge closed the connection without answering" {:root root})))
    (when-let [error (:error resp)]
      (throw (ex-info (str error) {:root root})))
    resp))

;; ============================================================================
;; Queries
;; ============================================================================

(def ^:private severities {1 "error" 2 "warning" 3 "info" 4 "hint"})

(defn- uri-key->path
  "Path for a file URI that arrived as a JSON key.

  The URI is read back off the keyword rather than through `name`: keywordizing
  \"file:///a/b.clj\" splits it into the namespace \"file:\" and the name
  \"//a/b.clj\", so `name` alone yields a path with the leading directory gone."
  [uri-key]
  (-> (str uri-key)
      (subs 1)
      (str/replace-first #"^file:/*" "/")))

(defn- print-diagnostic [path {:keys [range severity message]}]
  (let [{:keys [line character]} (:start range)]
    (println (format "%s:%d:%d: %s: %s"
                     path (inc line) (inc character)
                     (get severities severity "unknown") message))))

(defn diagnostics
  "Print clojure-lsp's diagnostics for one file, or for every file it has
  analysed.

  Exits non-zero when anything was reported, so a caller can branch on it."
  [root {:keys [file]}]
  (ensure! root)
  (let [resp (send-command root (cond-> {:command "diagnostics"}
                                  file (assoc :file (str (fs/absolutize file)))))
        by-file (if file
                  {file (:diagnostics resp)}
                  (update-keys (:diagnostics resp) uri-key->path))
        reported (for [[path diags] by-file
                       d diags]
                   (do (print-diagnostic path d) d))]
    (if (seq reported)
      1
      (do (println (if file (format ";; no diagnostics for %s" file) ";; no diagnostics"))
          0))))

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

(defn- bridge-processes
  "Live bridge processes started for root, found by their command line.

  The pid file can be gone while the process lives — a bridge killed with
  SIGKILL never runs the shutdown hook that removes it — and such an orphan
  keeps clojure-lsp and its several hundred MB alive."
  [root]
  (let [marker (str "lsp-bridge start " root)]
    (->> (java.lang.ProcessHandle/allProcesses)
         .toList
         (filter #(some-> (.info ^java.lang.ProcessHandle %)
                          .commandLine (.orElse nil)
                          (str/includes? marker)))
         vec)))

(defn- exited?
  "Whether handle finished within timeout-ms."
  [^java.lang.ProcessHandle handle timeout-ms]
  (try
    (.get (.onExit handle) timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
    true
    (catch java.util.concurrent.TimeoutException _ false)
    (catch Exception _ (not (.isAlive handle)))))

(defn- terminate!
  "Ask a process and its children to exit, then force whatever is left.

  SIGTERM first so the bridge's shutdown hook gets to stop clojure-lsp; killing
  the parent outright would leave the language server running with its index
  still in memory. The children are collected before signalling, because a dead
  parent no longer reports them."
  [^java.lang.ProcessHandle handle]
  (let [children (vec (.toList (.descendants handle)))]
    (.destroy handle)
    (when-not (exited? handle 2000)
      (.destroyForcibly handle))
    (doseq [^java.lang.ProcessHandle child children
            :when (.isAlive child)]
      (.destroy child)
      (when-not (exited? child 1000)
        (.destroyForcibly child)))))

(defn stop
  "Stop the bridge for root.

  Reports which of the three states it found, because \"I stopped it\" and
  \"nothing was there\" call for different next steps when a query has just
  failed."
  [root]
  (let [recorded (read-pid root)
        running (bridge-processes root)]
    (when (and recorded (seq running))
      ;; Ask it to shut down cleanly first; fall back to signals below.
      (try (send-command root {:command "stop"}) (catch Exception _ nil))
      (Thread/sleep 300))
    (doseq [handle (bridge-processes root)]
      (try
        (terminate! handle)
        (catch Exception e
          (binding [*out* *err*]
            (println (format "could not stop pid %s: %s" (.pid handle) (ex-message e)))))))
    (fs/delete-if-exists (pid-file root))
    (fs/delete-if-exists (port-file root))
    (println
     (cond
       (seq running) (format ";; bridge stopped for %s (log: %s)" root (log-file root))
       recorded (format ";; no bridge was running for %s; removed its stale pid and port files" root)
       :else (format ";; no bridge running for %s" root)))
    0))

(defn status [root]
  (if-not (running? root)
    (do (println (format ";; no bridge running for %s" root)) 0)
    (let [resp (send-command root {:command "status"})]
      (println (format ";; bridge %s for %s (pid %s, port %s), %s file(s) with diagnostics"
                       (:status resp) root (read-pid root) (read-port root)
                       (:diagnostics-count resp 0)))
      (if (= "running" (:status resp)) 0 1))))
