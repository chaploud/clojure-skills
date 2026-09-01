(ns clojure-skill.lsp-output-test
  "The shapes an agent parses out of `clj-skill lsp`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure-skill.lsp :as lsp]))

(def ^:private print-diagnostic #'lsp/print-diagnostic)
(def ^:private uri-key->path #'lsp/uri-key->path)

(defn- rendered [diagnostic]
  (str/trim (with-out-str (print-diagnostic "f.clj" diagnostic))))

(deftest diagnostics-report-1-based-coordinates
  (testing "LSP counts from zero; editors, ripgrep and the rest of this tool do not"
    (is (= "f.clj:1:1: error: x"
           (rendered {:range {:start {:line 0 :character 0}} :severity 1 :message "x"})))
    (is (= "f.clj:10:5: warning: y"
           (rendered {:range {:start {:line 9 :character 4}} :severity 2 :message "y"})))))

(deftest an-unknown-severity-is-labelled-rather-than-dropped
  (is (str/includes? (rendered {:range {:start {:line 0 :character 0}} :severity 9 :message "z"})
                     "unknown")))

(deftest a-file-uri-that-arrived-as-a-json-key-becomes-a-usable-path
  (testing "keywordizing file:///a/b.clj splits it, so reading the name alone
            loses the leading directory"
    (is (= "/a/b.clj" (uri-key->path (keyword "file:///a/b.clj"))))
    (is (= "/a/b.clj" (uri-key->path (keyword "file:/a/b.clj"))))))
