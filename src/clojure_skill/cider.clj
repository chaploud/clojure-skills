(ns clojure-skill.cider
  "Queries that need the cider-nrepl middleware.

  They share the session `repl eval` uses, so `stacktrace` reports the exception
  the last evaluation raised."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure-skill.nrepl-client :as nc]))

(def middleware-hint
  (str "This op needs the cider-nrepl middleware. Add it to the alias you start "
       "the REPL with:\n"
       "  {:aliases {:nrepl {:extra-deps {nrepl/nrepl {:mvn/version \"1.4.0\"}\n"
       "                                  cider/cider-nrepl {:mvn/version \"0.62.2\"}}\n"
       "                     :main-opts [\"-m\" \"nrepl.cmdline\"\n"
       "                                 \"--middleware\" \"[cider.nrepl/cider-middleware]\"]}}}"))

(def ^:private static-alternatives
  "Static equivalents to suggest when the runtime op is unavailable."
  {"info" "clj-skill lsp definition --file FILE --line N --col N"
   "fn-refs" "clj-skill lsp references --file FILE --line N --col N"
   "fn-deps" "clj-skill find '(SYM &)' src"
   "apropos" "clj-skill find '(defn NAME &)' src"})

(defn- report-missing-op [op available]
  (binding [*out* *err*]
    (println (format "op %s is not available on this nREPL server." op))
    (println (format "Available ops: %s" (str/join ", " (sort available))))
    (when-let [alt (static-alternatives op)]
      (println (format "Static alternative: %s" alt)))
    (println)
    (println middleware-hint)))

(defn with-session
  "Connect to host:port, ensure the shared session, and call f with the
  session-bound connection and the session data."
  [{:keys [host port timeout-ms] :or {timeout-ms 60000}} f]
  (nc/with-socket host (nc/coerce-long port) timeout-ms
    (fn [socket out in]
      (let [conn (nc/make-connection socket out in host port)
            session (nc/ensure-session conn)]
        (f (assoc conn :session-id (:session-id session)) session)))))

(def ^:private error-statuses
  "nREPL statuses that mean the op did not do its job. Without checking these, an
  errored op is indistinguishable from an op that ran and found nothing — and a
  failing `cider test` would report a green run."
  #{"error" "unknown-op" "eval-error" "namespace-not-found" "interrupted"})

(defn- send-op!
  "Send op-map when the server supports its op, otherwise explain what is missing.

  Returns the response map, or nil when the op is unavailable or the server
  reported an error."
  [conn session op-map]
  (let [op (get op-map "op")]
    (if-not (contains? (:ops session) op)
      (report-missing-op op (:ops session))
      (let [r (nc/send-op conn op-map)
            failed (seq (filter error-statuses (:status r)))]
        (if failed
          (binding [*out* *err*]
            (println (format "op %s failed: %s" op (str/join ", " failed)))
            (when-let [detail (or (:err r) (:ex r))] (println (str/trim (str detail))))
            nil)
          r)))))

;; ============================================================================
;; Location formatting
;; ============================================================================

(defn- url->path
  "Strip the file: scheme cider-nrepl uses for locations. A location inside a jar
  keeps its jar:file:…!/inner form, rewritten to /abs/to.jar:inner so the archive
  and the entry inside it stay distinguishable."
  [url]
  (when url
    (cond
      (str/starts-with? url "jar:file:") (-> url
                                             (str/replace-first #"^jar:file:" "")
                                             (str/replace "!/" ":"))
      (str/starts-with? url "file:") (str/replace-first url #"^file:" "")
      :else url)))

(defn- one-line
  "Collapse a multi-line string to one line, so a var with several arglists still
  prints as a single grep-shaped result."
  [s]
  (when s (str/join " " (map str/trim (str/split-lines (str/trim s))))))

(defn- format-location [{:strs [file-url file line name doc]}]
  (format "%s:%s: %s%s"
          (or (url->path file-url) file "?")
          (or line "?")
          name
          (if (and doc (not= doc "(not documented)"))
            (str "  ;; " (first (str/split-lines doc)))
            "")))

;; ============================================================================
;; Commands
;; ============================================================================

(defn ops
  "List the ops this server supports, and say whether cider-nrepl is loaded."
  [opts]
  (with-session opts
    (fn [_ session]
      (println (format ";; env: %s" (name (:env-type session))))
      (println (format ";; cider-nrepl: %s"
                       (if (contains? (:ops session) "fn-refs") "yes" "no")))
      (run! println (sort (:ops session)))
      0)))

(defn info
  "Print where a symbol is defined, with its arglists and docstring."
  [opts sym {:keys [ns]}]
  (with-session opts
    (fn [conn session]
      (if-let [r (send-op! conn session {"op" "info" "sym" sym "ns" (or ns "user")})]
        (if (:name r)
          (do
            (println (format "%s:%s:%s: %s/%s %s"
                             (or (url->path (:file r)) "?") (or (:line r) "?") (or (:column r) "?")
                             (:ns r) (:name r) (or (one-line (:arglists-str r)) "")))
            (when (:doc r) (println (:doc r)))
            0)
          (do (println (format ";; %s not found — it may not be defined, or its namespace (%s) may not be loaded"
                               sym (or ns "user")))
              1))
        1))))

(defn- xref [op opts sym {:keys [ns]} result-key empty-msg]
  (with-session opts
    (fn [conn session]
      (if-let [r (send-op! conn session {"op" op "sym" sym "ns" (or ns "user")})]
        (do
          (if-let [entries (seq (get r result-key))]
            (run! (comp println format-location) entries)
            (println (format ";; %s for %s" empty-msg sym)))
          0)
        1))))

(defn refs
  "Print the loaded functions that call sym."
  [opts sym flags]
  (xref "fn-refs" opts sym flags :fn-refs "no callers"))

(defn deps
  "Print the loaded functions that sym calls."
  [opts sym flags]
  (xref "fn-deps" opts sym flags :fn-deps "no callees"))

(defn apropos
  "Print loaded vars whose name matches a pattern."
  [opts pattern _flags]
  (with-session opts
    (fn [conn session]
      (if-let [r (send-op! conn session {"op" "apropos" "query" pattern})]
        (do
          (if-let [entries (seq (:apropos-matches r))]
            (doseq [{:strs [name type doc]} entries]
              (println (format "%s  %s%s" name (or type "")
                               (if doc (str "  ;; " (first (str/split-lines doc))) ""))))
            (println (format ";; nothing matches %s" pattern)))
          0)
        1))))

(defn ns-vars
  "Print the public vars of a loaded namespace with their arglists."
  [opts ns-name _flags]
  (with-session opts
    (fn [conn session]
      (if-let [r (send-op! conn session {"op" "ns-vars-with-meta" "ns" ns-name})]
        (do
          (if-let [vars (seq (:ns-vars-with-meta r))]
            (doseq [[var-name meta-map] (sort-by key vars)]
              (println (format "%s/%s %s" ns-name (name var-name)
                               (or (one-line (get meta-map "arglists")) ""))))
            (println (format ";; %s has no public vars, or is not loaded" ns-name)))
          0)
        1))))

;; ============================================================================
;; Tests
;; ============================================================================

(defn- print-test-failures
  "Print one line per failing assertion, then the expected/actual pair."
  [results]
  (doseq [[test-ns vars] results
          [var-name assertions] vars
          {:strs [type file line expected actual message context]} assertions
          :when (#{"fail" "error"} type)]
    (println (format "%s:%s: %s %s/%s%s"
                     (or file "?") (or line "?") (str/upper-case type)
                     (name test-ns) (name var-name)
                     (if (seq context) (str " " (str/join " / " context)) "")))
    (when (seq message) (println (str "  " (str/trim message))))
    (when expected (println (str "  expected: " (str/trim expected))))
    (when actual (println (str "  actual:   " (str/trim actual))))))

(defn- failed? [{:strs [fail error]}]
  (pos? (+ (or fail 0) (or error 0))))

(defn- print-test-summary [{:strs [test pass fail error] :as summary}]
  (if (empty? summary)
    (println ";; no tests were run")
    (println (format ";; %d assertion(s): %d pass, %d fail, %d error"
                     (or test 0) (or pass 0) (or fail 0) (or error 0)))))

(defn- report-run
  "Print a test run and turn it into an exit code.

  A run that executed nothing is a failure when namespaces were named: the
  namespace may be misspelled, or simply never required, and reporting that as a
  green run is the one outcome an agent must not be given."
  [r named-namespaces?]
  (print-test-failures (:results r))
  (print-test-summary (:summary r))
  (cond
    (failed? (:summary r)) 1
    (and named-namespaces? (empty? (:summary r))) 1
    :else 0))

(defn run-tests
  "Run tests in the given namespaces, or in every loaded project namespace when
  none are given, and report only what failed.

  cider-nrepl only sees namespaces the REPL has already required; one that was
  never loaded is not found rather than run."
  [opts namespaces _flags]
  (with-session opts
    (fn [conn session]
      (let [query (if (seq namespaces)
                    {"ns-query" {"exactly" (vec namespaces)}}
                    {"ns-query" {"project?" "true"}})]
        (if-let [r (send-op! conn session {"op" "test-var-query" "var-query" query})]
          (report-run r (boolean (seq namespaces)))
          1)))))

(defn retest
  "Re-run only the tests that failed or errored on the previous run."
  [opts _flags]
  (with-session opts
    (fn [conn session]
      (if-let [r (send-op! conn session {"op" "retest"})]
        (report-run r false)
        1))))

;; ============================================================================
;; Stacktrace
;; ============================================================================

(defn- project-frame? [{:strs [flags]}]
  (contains? (set flags) "project"))

(defn- format-frame [{:strs [file-url file line name]}]
  (format "%s:%s: %s" (or (url->path file-url) file "?") (or line "?") name))

(defn stacktrace
  "Print the last exception raised in this session, showing only the frames in
  project code.

  A JVM stacktrace is mostly Clojure's own machinery; the `project` flag
  cider-nrepl puts on each frame is what separates the few lines worth reading."
  [opts {:keys [all]}]
  (with-session opts
    (fn [conn session]
      (if-let [r (send-op! conn session {"op" "analyze-last-stacktrace"})]
        (if-not (:class r)
          (do (println ";; no exception recorded in this session") 0)
          (let [frames (:stacktrace r)
                shown (if all frames (filter project-frame? frames))]
            (println (format "%s: %s" (:class r) (:message r)))
            (when (seq (:phase r)) (println (format ";; phase: %s" (:phase r))))
            (run! (comp println format-frame) shown)
            (when (and (not all) (empty? shown))
              (println (format ";; no project frames among %d — rerun with --all"
                               (count frames))))
            0))
        1))))

;; ============================================================================
;; Inspect
;; ============================================================================

(defn- render-inspect
  "Flatten cider-nrepl's inspector rendering into plain text.

  The rendering is a vector mixing plain strings with `(:value \"text\" idx)` and
  `(:newline)` markers; the index is what `inspect-push` navigates by, so it is
  kept visible."
  [rendered]
  (str/join
   (for [item rendered]
     (cond
       (string? item) item
       (and (seq? item) (= :newline (first item))) "\n"
       (and (seq? item) (= :value (first item))) (str (second item)
                                                      "<" (nth item 2) ">")
       :else (pr-str item)))))

(defn inspect
  "Evaluate code and print the inspector's paged rendering instead of the raw
  value, which keeps a large structure readable without printing all of it."
  [opts code _flags]
  (with-session opts
    (fn [conn session]
      (if-not (contains? (:ops session) "inspect-push")
        (do (report-missing-op "inspect-push" (:ops session)) 1)
        (let [r (nc/send-op conn {"op" "eval" "code" code "inspect" "true"})]
          (if-let [rendered (some-> (last (:value r)) edn/read-string)]
            (do (println (render-inspect rendered))
                (println ";; <N> marks an index for `inspect-push`")
                0)
            (do (binding [*out* *err*]
                  (println (str/trim (or (not-empty (str (:err r) (:out r)))
                                         "the expression produced no inspectable value"))))
                1)))))))
