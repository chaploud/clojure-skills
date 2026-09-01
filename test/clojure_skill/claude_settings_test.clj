(ns clojure-skill.claude-settings-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure-skill.claude-settings :as settings]))

(defn- with-settings
  "Run f against a throwaway settings file seeded with initial, returning what
  the file holds afterwards."
  [initial f]
  (let [file (fs/create-temp-file {:suffix ".json"})]
    (try
      (if initial
        (spit (str file) (json/generate-string initial))
        (fs/delete-if-exists file))
      (binding [settings/*settings-path* (str file)]
        (with-out-str (f)))
      (when (fs/exists? file)
        (json/parse-string (slurp (str file))))
      (finally (fs/delete-if-exists file)))))

(deftest install-creates-the-file-when-there-is-none
  (let [result (with-settings nil settings/install-hooks!)]
    (is (= #{"PreToolUse" "PostToolUse" "Stop"} (set (keys (get result "hooks")))))))

(deftest install-keeps-unrelated-settings-and-hooks
  (let [result (with-settings
                 {"model" "opus"
                  "hooks" {"PreToolUse" [{"matcher" "Bash"
                                          "hooks" [{"type" "command" "command" "my-linter"}]}]}}
                 settings/install-hooks!)]
    (is (= "opus" (get result "model")) "unrelated settings survive")
    (is (= 2 (count (get-in result ["hooks" "PreToolUse"]))) "the user's own hook is kept")))

(deftest installing-twice-adds-nothing
  (let [file (fs/create-temp-file {:suffix ".json"})]
    (try
      (fs/delete-if-exists file)
      (binding [settings/*settings-path* (str file)]
        (with-out-str (settings/install-hooks!) (settings/install-hooks!)))
      (let [result (json/parse-string (slurp (str file)))]
        (is (= 1 (count (get-in result ["hooks" "PreToolUse"])))))
      (finally (fs/delete-if-exists file)))))

(deftest uninstall-removes-only-our-hooks
  (let [file (fs/create-temp-file {:suffix ".json"})]
    (try
      (spit (str file) (json/generate-string
                        {"hooks" {"PreToolUse" [{"matcher" "Bash"
                                                 "hooks" [{"type" "command" "command" "my-linter"}]}]}}))
      (binding [settings/*settings-path* (str file)]
        (with-out-str (settings/install-hooks!) (settings/uninstall-hooks!)))
      (let [result (json/parse-string (slurp (str file)))]
        (is (= [{"matcher" "Bash" "hooks" [{"type" "command" "command" "my-linter"}]}]
               (get-in result ["hooks" "PreToolUse"])))
        (is (nil? (get-in result ["hooks" "Stop"])) "an event left empty is dropped"))
      (finally (fs/delete-if-exists file)))))

(deftest uninstall-drops-the-hooks-key-when-nothing-remains
  (let [result (with-settings nil #(do (settings/install-hooks!) (settings/uninstall-hooks!)))]
    (is (not (contains? result "hooks")))))

(deftest uninstall-removes-a-hook-left-by-an-older-install
  (testing "so upgrading does not strand a hook pointing at a deleted binary"
    (let [result (with-settings
                   {"hooks" {"PreToolUse" [{"matcher" "Write|Edit"
                                            "hooks" [{"type" "command"
                                                      "command" "clj-paren-repair-claude-hook --cljfmt"}]}]}}
                   settings/uninstall-hooks!)]
      (is (not (contains? result "hooks"))))))
