(ns clojure-skill.main
  "Single entry point for the skill's commands.

  Namespaces are resolved on use rather than required up front so that a cheap
  command does not pay for the dependencies of an expensive one — the hook in
  particular runs on every file the agent writes."
  (:require [babashka.cli :as cli]
            [clojure.string :as str]))

(def ^:private cli-spec
  {:alias {:p :port :H :host :t :timeout :f :file :h :help}
   :coerce {:port :long :timeout :long :line :long :col :long
            :limit :long :width :long
            :dry-run :boolean :no-prefilter :boolean :all :boolean
            :cljfmt :boolean :no-revert :boolean :stats :boolean
            :log-level :keyword}})

(def help-text
  "clj-skill — Clojure tooling for coding agents

Structure (needs nothing but babashka)
  outline FILE...                    top-level forms as `path:start-end: form`
  find PATTERN [PATH...]             every node matching PATTERN, as `path:line:col: form`
  replace FILE PATTERN               replace the one matching top-level form; reads it from stdin
  repair [FILE...]                   balance delimiters and format; filters stdin when given no files

  PATTERN is a Clojure form where `_` matches any one form and `&` matches the
  rest of a sequence, e.g. '(defmethod _ &)' or '(assoc _ :id &)'.

REPL (needs a running nREPL server)
  repl ports                         nREPL servers on this machine
  repl connected                     servers this session already holds a session on
  repl eval [CODE]                   evaluate CODE, or stdin; state persists between calls
  repl reset                         drop the stored session for --port

Runtime queries (needs the cider-nrepl middleware)
  cider ops                          ops this server supports
  cider info SYM                     where SYM is defined, with arglists and doc
  cider refs SYM                     loaded functions that call SYM
  cider deps SYM                     loaded functions that SYM calls
  cider apropos PATTERN              loaded vars whose name matches
  cider ns-vars NS                   public vars of a loaded namespace
  cider test [NS...]                 run tests, report only failures
  cider retest                       re-run what failed last time
  cider stacktrace                   last exception, project frames only (--all for every frame)
  cider inspect CODE                 evaluate and page the value instead of printing all of it

Static analysis (needs clojure-lsp)
  lsp diagnostics                    project diagnostics, or one file with --file
  lsp references --file F --line N --col N
  lsp definition --file F --line N --col N
  lsp hover      --file F --line N --col N
  lsp start | stop | status          bridge lifecycle
  lsp-bridge warm [ROOT]             index the project ahead of a first query

  doctor                             what works here, and how to enable the rest

Options
  -p, --port N       nREPL port; inferred when exactly one server matches this directory
  -H, --host HOST    nREPL host (default 127.0.0.1)
  -t, --timeout MS   evaluation timeout (default 120000)
      --ns NS        namespace to resolve a symbol in (cider)
      --limit N      maximum matches to print (find, default 50)
      --no-prefilter scan every file instead of narrowing with ripgrep (find)
      --dry-run      report the edit without writing it (replace)
      --all          every stack frame, not only project ones (cider stacktrace)
      --file, --line, --col, --project-root   position for lsp queries

Line and column numbers are 1-based everywhere, matching editors and ripgrep.")

;; ============================================================================
;; Port resolution
;; ============================================================================

(defn- resolve-port
  "Port to talk to: the one given, or the single discovered server whose working
  directory is this one.

  Guessing only when the choice is unambiguous keeps an agent from silently
  evaluating against another project's REPL."
  [opts]
  (or (:port opts)
      (when (:host opts)
        (throw (ex-info "--port is required with --host: discovery only probes this machine" {})))
      (let [here (filter :matches-cwd ((requiring-resolve 'clojure-skill.repl/discover)))]
        (case (count here)
          1 (:port (first here))
          0 (throw (ex-info (str "no nREPL server found for this directory — pass --port, "
                                 "or run `clj-skill repl ports`") {}))
          (throw (ex-info (str "several nREPL servers match this directory ("
                               (str/join ", " (map :port here))
                               ") — pass --port") {}))))))

(defn- connection [opts]
  {:host (or (:host opts) "127.0.0.1")
   :port (resolve-port opts)
   :timeout-ms (or (:timeout opts) 120000)})

(defn- stdin-redirected?
  "Whether stdin is a pipe, heredoc or file rather than the terminal.

  Asking the console, not `.ready`, because a pipe whose producer has not written
  yet is not ready but will be — testing readiness makes `echo code | …`
  intermittently report that no code was given."
  []
  (nil? (System/console)))

(defn- code-arg [args]
  (or (first args) (when (stdin-redirected?) (slurp *in*))))

;; ============================================================================
;; Dispatch
;; ============================================================================

(defn- call [sym & args]
  (apply (requiring-resolve sym) args))

(defn- required
  "Value, or an error naming what the command expected.

  Checked before anything connects or reads, so a forgotten argument fails with a
  usage message rather than a query for the symbol `null`."
  [value what usage]
  (when (or (nil? value) (and (string? value) (str/blank? value)))
    (throw (ex-info (format "%s is required — usage: %s" what usage) {})))
  value)

(defn- run-sexp [cmd args opts]
  (case cmd
    "outline" (do (required (first args) "FILE" "clj-skill outline FILE...")
                  (call 'clojure-skill.sexp/outline args opts))
    "find" (do (required (first args) "PATTERN" "clj-skill find PATTERN [PATH...]")
               (call 'clojure-skill.sexp/find-forms (first args) (rest args) opts))
    "replace" (do (required (first args) "FILE" "clj-skill replace FILE PATTERN")
                  (required (second args) "PATTERN" "clj-skill replace FILE PATTERN")
                  (call 'clojure-skill.sexp/replace-form (first args) (second args) opts))))

(defn- run-repl [args opts]
  (case (first args)
    "ports" (call 'clojure-skill.repl/print-ports)
    "connected" (call 'clojure-skill.repl/print-connected)
    "reset" (let [{:keys [host port]} (connection opts)]
              (call 'clojure-skill.repl/reset-session host port))
    "eval" (let [code (code-arg (rest args))]
             (if (str/blank? code)
               (do (binding [*out* *err*] (println "no code given; pass it as an argument or on stdin"))
                   1)
               (call 'clojure-skill.repl/eval-code (assoc (connection opts) :code code))))
    (do (binding [*out* *err*] (println (str "unknown repl command: " (first args)))) 1)))

(defn- run-cider [args opts]
  (let [[cmd & rest-args] args
        arg #(required (first rest-args) % (str "clj-skill cider " cmd " " %))
        flags (select-keys opts [:ns :all])
        ;; the argument is validated before `connection` runs, so a missing one
        ;; does not cost a round trip to a REPL
        conn #(connection opts)]
    (case cmd
      "ops" (call 'clojure-skill.cider/ops (conn))
      "info" (call 'clojure-skill.cider/info (conn) (arg "SYM") flags)
      "refs" (call 'clojure-skill.cider/refs (conn) (arg "SYM") flags)
      "deps" (call 'clojure-skill.cider/deps (conn) (arg "SYM") flags)
      "apropos" (call 'clojure-skill.cider/apropos (conn) (arg "PATTERN") flags)
      "ns-vars" (call 'clojure-skill.cider/ns-vars (conn) (arg "NS") flags)
      "test" (call 'clojure-skill.cider/run-tests (conn) rest-args flags)
      "retest" (call 'clojure-skill.cider/retest (conn) flags)
      "stacktrace" (call 'clojure-skill.cider/stacktrace (conn) flags)
      "inspect" (call 'clojure-skill.cider/inspect (conn)
                      (required (code-arg rest-args) "CODE" "clj-skill cider inspect CODE")
                      flags)
      (do (binding [*out* *err*] (println (str "unknown cider command: " cmd))) 1))))

(defn- run-lsp [args opts]
  (let [root (call 'clojure-skill.lsp/resolve-root opts)
        cmd (first args)
        position #(do (required (:file opts) "--file" (str "clj-skill lsp " cmd " --file F --line N --col N"))
                      (required (:line opts) "--line" (str "clj-skill lsp " cmd " --file F --line N --col N"))
                      (required (:col opts) "--col" (str "clj-skill lsp " cmd " --file F --line N --col N"))
                      opts)]
    (case cmd
      "diagnostics" (call 'clojure-skill.lsp/diagnostics root opts)
      "references" (call 'clojure-skill.lsp/references root (position))
      "definition" (call 'clojure-skill.lsp/definition root (position))
      "hover" (call 'clojure-skill.lsp/hover root (position))
      "start" (call 'clojure-skill.lsp/start root)
      "stop" (call 'clojure-skill.lsp/stop root)
      "status" (call 'clojure-skill.lsp/status root)
      (do (binding [*out* *err*] (println (str "unknown lsp command: " (first args)))) 1))))

(defn dispatch
  "Run one command and return its exit code."
  [[cmd & args] opts]
  (case cmd
    ("outline" "find" "replace") (run-sexp cmd args opts)
    "repair" (call 'clojure-skill.repair/run args)
    "repl" (run-repl args opts)
    "cider" (run-cider args opts)
    "lsp" (run-lsp args opts)
    "lsp-bridge" (call 'clojure-skill.lsp-bridge/run args)
    "hook" (call 'clojure-skill.hook/run opts)
    "doctor" (call 'clojure-skill.doctor/-main)
    (do (binding [*out* *err*] (println (str "unknown command: " cmd)))
        (println help-text)
        1)))

(defn -main [& argv]
  (System/exit
   (try
     (let [{:keys [args opts]} (cli/parse-args argv cli-spec)]
       (if (or (:help opts) (empty? args))
         (do (println help-text) 0)
         (dispatch args opts)))
     ;; Every failure gets one line on stderr. Option parsing is inside the try
     ;; too, so a malformed flag reads like the rest of the CLI rather than
     ;; dumping a stack trace at the agent.
     (catch Exception e
       (binding [*out* *err*] (println (or (ex-message e) (str e))))
       1))))
