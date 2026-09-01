(ns clojure-skill.repl
  "Evaluating code in a running nREPL server, and finding one to talk to.

  Sessions persist per host:port, so vars and requires survive across
  invocations, and every subcommand shares the same session."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure-skill.delimiter-repair :refer [fix-delimiters]]
            [clojure-skill.nrepl-client :as nc]
            [clojure-skill.tmp :as tmp]))

;; ============================================================================
;; Stored connections
;; ============================================================================

(defn validate-stored-connection
  "Check that a stored connection still answers and still knows its session.

  Stale session files are left alone: a server that is merely down should not
  cost the user their session id when it comes back."
  [{:keys [host port session-id env-type]}]
  (try
    (if-let [active (nc/ls-sessions host port)]
      (if (some #{session-id} active)
        {:status :active :host host :port port :session-id session-id :env-type env-type}
        {:status :invalid :host host :port port :reason "session expired"})
      {:status :invalid :host host :port port :reason "server unreachable"})
    (catch Exception e
      {:status :invalid :host host :port port :reason (ex-message e)})))

(defn list-connected
  "Stored connections that are still reachable with a live session."
  []
  (->> (tmp/list-nrepl-session-files {})
       (map validate-stored-connection)
       (filter #(= :active (:status %)))
       vec))

;; ============================================================================
;; Port discovery
;; ============================================================================

(def ^:private project-dir-expr
  "Expression that yields the server's working directory, by environment."
  {:basilisp "(import os)\n(os/getcwd)"
   :default "(System/getProperty \"user.dir\")"})

(defn- read-nrepl-port-file []
  (try
    (when (fs/exists? ".nrepl-port")
      (parse-long (str/trim (slurp ".nrepl-port" :encoding "UTF-8"))))
    (catch Exception _ nil)))

(defn parse-lsof-ports
  "Listening TCP ports in lsof output, e.g. `TCP *:7888 (LISTEN)`."
  [lsof-output]
  (when lsof-output
    (->> (str/split-lines lsof-output)
         (keep (fn [line]
                 (when-let [[_ port] (re-find #"TCP\s+(?:\*|[\d.]+):(\d+)\s+\(LISTEN\)" line)]
                   (parse-long port))))
         distinct
         vec)))

(defn- listening-jvm-ports []
  (try
    (let [proc (.. (ProcessBuilder. ["sh" "-c" "lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null | grep -Ei 'java|clojure|babashka|bb|nrepl'"])
                   (redirectErrorStream true)
                   start)
          _ (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)]
      (or (parse-lsof-ports (slurp (.getInputStream proc))) []))
    (catch Exception _ [])))

(defn- probe-port
  "Describe the nREPL server on port, or mark it invalid when it does not answer."
  [host port source current-dir]
  (try
    (nc/with-socket host port 250
      (fn [socket out in]
        (let [conn (nc/make-connection socket out in host port)]
          (if (:sessions (nc/ls-sessions* conn))
            (let [{:keys [env-type ops]} (nc/describe-session conn)
                  expr (get project-dir-expr env-type (:default project-dir-expr))
                  project-dir (some-> (last (:value (nc/eval-nrepl* conn expr)))
                                      (str/replace #"^\"|\"$" ""))]
              {:host host :port port :source source :valid true
               :env-type env-type
               :cider? (contains? ops "fn-refs")
               :project-dir project-dir
               :matches-cwd (= project-dir current-dir)})
            {:host host :port port :source source :valid false}))))
    (catch Exception e
      {:host host :port port :source source :valid false :error (ex-message e)})))

(defn discover
  "Probe every port that looks like it might host an nREPL server.

  Candidates come from a .nrepl-port file and from JVM/Babashka processes that
  lsof reports as listening."
  []
  (let [port-file-port (read-nrepl-port-file)
        current-dir (System/getProperty "user.dir")
        candidates (distinct (concat (when port-file-port [port-file-port])
                                     (listening-jvm-ports)))]
    (vec (pmap #(probe-port "localhost" %
                            (if (= % port-file-port) :nrepl-port-file :lsof)
                            current-dir)
               candidates))))

;; ============================================================================
;; Evaluation
;; ============================================================================

(defn- cljs-mode?
  "Whether a shadow-cljs session is currently jacked into a CLJS build."
  [conn]
  (try
    (boolean (seq (:value (nc/eval-nrepl* conn "cljs.user/*clojurescript-version*"))))
    (catch Exception _ false)))

(defn- print-messages
  "Print stdout, stderr and values from an eval, each value followed by a divider
  naming the namespace and environment it came from."
  [messages env-type shadow-cljs-mode?]
  (when (= env-type :shadow)
    (println (if shadow-cljs-mode?
               ";; shadow-cljs repl is in CLJS mode"
               (str ";; shadow-cljs repl is NOT in CLJS mode\n"
                    ";; (shadow/active-builds) lists builds; (shadow/repl <build-id>) jacks in"))))
  (doseq [msg messages]
    (when-let [s (:out msg)] (print s) (flush))
    (when-let [s (:err msg)] (binding [*out* *err*] (print s) (flush)))
    (when-let [value (:value msg)]
      (println (str "=> " value))
      (println (format "*======== %s | %s ========*" (:ns msg) (name env-type)))
      (flush))))

(defn eval-code
  "Evaluate code on host:port, repairing unbalanced delimiters first.

  On timeout an nREPL :interrupt is sent so a runaway evaluation does not keep
  the server busy after this process gives up."
  [{:keys [host port code timeout-ms] :or {timeout-ms 120000}}]
  (let [code (or (fix-delimiters code) code)]
    (try
      (nc/with-socket host (nc/coerce-long port) timeout-ms
        (fn [socket out in]
          (let [conn (nc/make-connection socket out in host port)
                {:keys [session-id env-type]} (nc/ensure-session conn)
                conn (assoc conn :session-id session-id)
                env-type (or env-type :unknown)
                shadow-mode? (when (= env-type :shadow) (cljs-mode? conn))
                eval-id (nc/next-id)]
            (try
              (-> (nc/messages-for-id conn {"op" "eval" "code" code "id" eval-id})
                  (print-messages env-type shadow-mode?))
              0
              (catch java.net.SocketTimeoutException _
                (println "\n⚠️  timed out, sending nREPL :interrupt …")
                (nc/write-bencode-msg out {"op" "interrupt"
                                           "session" session-id
                                           "interrupt-id" eval-id})
                (println "✋ evaluation interrupted.")
                124)))))
      (catch java.net.ConnectException _
        (binding [*out* *err*]
          (println (format "connection refused: %s:%s" host port))
          (println "run `clj-skill repl ports` to find a running server."))
        1)
      (catch java.io.EOFException e
        (binding [*out* *err*]
          (println (format "nREPL protocol error: %s" (ex-message e)))
          (println (format "the server at %s:%s may not speak bencode, or the session went stale." host port))
          (println (format "try: clj-skill repl reset -p %s" port)))
        1)
      (catch Exception e
        (binding [*out* *err*]
          (println (format "nREPL error talking to %s:%s: %s" host port (ex-message e))))
        1))))

(defn reset-session
  "Forget the stored session for host:port so the next call clones a fresh one."
  [host port]
  (nc/delete-nrepl-session host port)
  (println (format "session reset for %s:%s" host port))
  0)

;; ============================================================================
;; Reporting
;; ============================================================================

(defn print-connected []
  (let [connections (list-connected)]
    (if (empty? connections)
      (println ";; no active nREPL connections")
      (doseq [{:keys [host port env-type session-id]} connections]
        (println (format "%s:%s (%s) session=%s" host port
                         (name (or env-type :unknown)) session-id)))))
  0)

(defn print-ports []
  (let [discovered (discover)
        {valid true invalid false} (group-by (comp boolean :valid) discovered)]
    (if (empty? valid)
      (println ";; no nREPL servers found")
      (doseq [{:keys [host port env-type cider? project-dir matches-cwd]} valid]
        (println (format "%s:%s (%s%s)%s" host port
                         (name (or env-type :unknown))
                         (if cider? ", cider-nrepl" "")
                         (cond
                           matches-cwd "  <- this project"
                           project-dir (str "  " project-dir)
                           :else "")))))
    (doseq [{:keys [host port error]} invalid]
      (binding [*out* *err*]
        (println (format ";; %s:%s did not answer (%s)" host port (or error "no response"))))))
  0)
