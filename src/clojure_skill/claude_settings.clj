(ns clojure-skill.claude-settings
  "Registering and removing the paren-repair hooks in Claude Code's settings.

  The hooks are opt-in: editing a user's settings.json is not something
  installing a CLI should do on its own."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as string]))

(def ^:dynamic *settings-path*
  "Claude Code's settings file. Rebindable so the install and uninstall paths can
  be exercised without writing to the user's real settings."
  (str (fs/path (fs/home) ".claude" "settings.json")))

(def hook-command "clj-skill hook --cljfmt")

(def ^:private legacy-hook-command
  "The pre-consolidation binary, still recognised so that uninstalling after an
  upgrade removes the hook an older install left behind."
  "clj-paren-repair-claude-hook")

(defn hook-entries []
  {"PreToolUse"  [{"matcher" "Write|Edit"
                   "hooks"   [{"type" "command" "command" hook-command}]}]
   "PostToolUse" [{"matcher" "Edit|Write"
                   "hooks"   [{"type" "command" "command" hook-command}]}]
   "Stop"        [{"hooks"   [{"type" "command" "command" hook-command}]}]})

(defn- read-settings []
  (if (fs/exists? *settings-path*)
    (json/parse-string (slurp *settings-path*))
    {}))

(defn- write-settings! [settings]
  (fs/create-dirs (fs/parent *settings-path*))
  (spit *settings-path* (json/generate-string settings {:pretty true})))

(defn- ours?
  "Whether a hook group contains one of our commands."
  [hook-group]
  (boolean
   (some (fn [h]
           (when-let [cmd (get h "command")]
             (or (string/starts-with? cmd hook-command)
                 (string/starts-with? cmd legacy-hook-command))))
         (get hook-group "hooks"))))

(defn hooks-installed?
  "Whether settings.json already registers our hook.

  Reads the JSON rather than matching on the raw text, so a mention inside an
  unrelated setting is not counted, and an older install's command name still is."
  []
  (boolean
   (when (fs/exists? *settings-path*)
     (try
       (some ours? (mapcat val (get (read-settings) "hooks")))
       (catch Exception _ false)))))

(defn install-hooks!
  "Add our hook groups to settings.json, leaving any already there alone."
  []
  (let [settings (read-settings)
        updated (reduce-kv
                 (fn [acc event groups]
                   (if (some ours? (get acc event))
                     acc
                     (update acc event (fnil into []) groups)))
                 (get settings "hooks" {})
                 (hook-entries))]
    (write-settings! (assoc settings "hooks" updated))
    (println "hooks registered in" *settings-path*)))

(defn uninstall-hooks!
  "Remove our hook groups from settings.json, and the \"hooks\" key itself when
  nothing else is left under it."
  []
  (when (fs/exists? *settings-path*)
    (let [settings (read-settings)
          updated (reduce-kv
                   (fn [acc event groups]
                     (let [kept (vec (remove ours? groups))]
                       (cond-> acc (seq kept) (assoc event kept))))
                   {}
                   (get settings "hooks" {}))]
      (write-settings!
       (if (seq updated)
         (assoc settings "hooks" updated)
         (dissoc settings "hooks")))
      (println "hooks removed from" *settings-path*))))
