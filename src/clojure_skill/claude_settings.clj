(ns clojure-skill.claude-settings
  "Install/uninstall hooks in ~/.claude/settings.json"
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as string]))

(def settings-path
  (str (fs/path (fs/home) ".claude" "settings.json")))

(def hook-command "clj-paren-repair-claude-hook --cljfmt")

(def hook-entries
  {"PreToolUse"  [{"matcher" "Write|Edit"
                   "hooks"   [{"type" "command" "command" hook-command}]}]
   "PostToolUse" [{"matcher" "Edit|Write"
                   "hooks"   [{"type" "command" "command" hook-command}]}]
   "Stop"        [{"hooks"   [{"type" "command" "command" hook-command}]}]})

(defn- read-settings []
  (if (fs/exists? settings-path)
    (json/parse-string (slurp settings-path))
    {}))

(defn- write-settings! [settings]
  (fs/create-dirs (fs/parent settings-path))
  (spit settings-path (json/generate-string settings {:pretty true})))

(defn- our-hook? [hook-group]
  (some (fn [h]
          (when-let [cmd (get h "command")]
            (string/starts-with? cmd "clj-paren-repair-claude-hook")))
        (get hook-group "hooks")))

(defn install-hooks! []
  (let [settings (read-settings)
        hooks    (get settings "hooks" {})
        updated  (reduce-kv
                  (fn [acc event-name new-groups]
                    (let [existing (get acc event-name [])]
                      (if (some our-hook? existing)
                        acc
                        (assoc acc event-name (into existing new-groups)))))
                  hooks
                  hook-entries)]
    (write-settings! (assoc settings "hooks" updated))
    (println "Hooks registered in" settings-path)))

(defn uninstall-hooks! []
  (when (fs/exists? settings-path)
    (let [settings (read-settings)
          hooks    (get settings "hooks" {})
          updated  (reduce-kv
                    (fn [acc event-name groups]
                      (let [filtered (vec (remove our-hook? groups))]
                        (if (seq filtered)
                          (assoc acc event-name filtered)
                          acc)))
                    {}
                    hooks)]
      (write-settings!
       (if (seq updated)
         (assoc settings "hooks" updated)
         (dissoc settings "hooks")))
      (println "Hooks removed from" settings-path))))
