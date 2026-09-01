(ns clojure-skill.doctor
  "Report which parts of the skill work in this environment, and how to enable
  the rest.

  Everything optional degrades rather than fails, so an agent needs to be told
  what it can rely on before it plans a search or a query."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure-skill.repl :as repl]))

(defn- on-path?
  "Whether cmd actually runs. Invoking it beats looking it up on PATH: a broken
  symlink or a wrapper that cannot start is reported as unusable, not present."
  [cmd]
  (try
    (zero? (:exit (process/sh [cmd "--version"])))
    (catch Exception _ false)))

(defn- line
  "One status row. Absent optional tooling reads as `--`, not as a failure."
  ([ok? label detail] (line ok? label detail :required))
  ([ok? label detail requirement]
   (println (format "%-4s %-30s %s"
                    (cond ok? "ok"
                          (= requirement :optional) "--"
                          :else "MISS")
                    label detail))))

(def ^:private cljfmt-clj-configs
  "Config files cljfmt stopped reading by default in 0.16.0."
  [".cljfmt.clj" "cljfmt.clj"])

(defn- check-cljfmt-config []
  (let [stale (filter fs/exists? cljfmt-clj-configs)]
    (when (seq stale)
      (println)
      (println (format "warning: %s is ignored since cljfmt 0.16 — rename it to .cljfmt.edn"
                       (str/join ", " stale))))))

(defn- check-hooks []
  (let [settings (fs/path (fs/home) ".claude" "settings.json")]
    (and (fs/exists? settings)
         (str/includes? (slurp (str settings)) "clj-skill hook"))))

(defn- report-servers []
  (println)
  (println "nREPL servers")
  (let [servers (filter :valid (repl/discover))]
    (if (empty? servers)
      (println "  none found — start one, then `clj-skill repl ports` to confirm")
      (doseq [{:keys [host port env-type cider? project-dir matches-cwd]} servers]
        (println (format "  %s:%s  %s  %s  %s" host port
                         (name (or env-type :unknown))
                         (if cider? "cider-nrepl" "no cider-nrepl")
                         (cond matches-cwd "(this project)"
                               project-dir project-dir
                               :else "")))))))

(defn -main [& _]
  (let [rg? (on-path? "rg")
        lsp? (on-path? "clojure-lsp")
        kondo? (on-path? "clj-kondo")]
    (println "clj-skill doctor")
    (println)
    (println "Always available (needs only babashka)")
    (line true "repair / outline / find" "structural read, search and edit")
    (line rg? "  find pre-filter (rg)"
          (if rg? "large searches stay fast"
              "searches scan every file — brew install ripgrep")
          :optional)
    (println)
    (println "Static analysis")
    (line lsp? "lsp (clojure-lsp)"
          (if lsp? "diagnostics / references / definition / hover"
              "brew install clojure-lsp/brew/clojure-lsp-native"))
    (line kondo? "  clj-kondo"
          (if kondo? "backs clojure-lsp's analysis" "installed with clojure-lsp")
          :optional)
    (println)
    (println "Claude Code integration")
    (let [hooks? (check-hooks)]
      (line hooks? "auto paren-repair hooks"
            (if hooks? "registered in ~/.claude/settings.json" "opt-in: bb install-hooks")
            :optional))
    (check-cljfmt-config)
    (report-servers)
    (println)
    (println "Runtime queries (`clj-skill cider …`) need a server marked cider-nrepl above.")
    0))
