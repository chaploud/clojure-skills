(ns clojure-skill.cider-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure-skill.cider :as cider]))

(def ^:private url->path #'cider/url->path)
(def ^:private render-inspect #'cider/render-inspect)
(def ^:private one-line #'cider/one-line)
(def ^:private project-frame? #'cider/project-frame?)

(deftest file-urls-become-plain-paths
  (is (= "/a/b/core.clj" (url->path "file:/a/b/core.clj")))
  (is (nil? (url->path nil))))

(deftest jar-locations-keep-the-archive-and-the-entry-distinguishable
  (is (= "/deps/foo.jar:inner/ns.clj"
         (url->path "jar:file:/deps/foo.jar!/inner/ns.clj"))))

(deftest an-unknown-scheme-is-passed-through
  (is (= "weird://x" (url->path "weird://x"))))

(deftest several-arglists-collapse-to-one-line
  (is (= "[f] [f coll]" (one-line "[f]\n[f coll]")))
  (is (nil? (one-line nil))))

(deftest only-frames-flagged-project-are-the-user-s-own
  (is (project-frame? {"flags" ["project" "clj"]}))
  (is (not (project-frame? {"flags" ["java" "dup"]})))
  (is (not (project-frame? {}))))

(deftest inspector-rendering-becomes-readable-text
  (testing "value indices stay visible because inspect-push navigates by them"
    (is (= "Count: 2\n:a<1>"
           (render-inspect ["Count: " "2" '(:newline) '(:value ":a" 1)])))))

(deftest the-middleware-hint-names-a-usable-coordinate
  (is (str/includes? cider/middleware-hint "cider/cider-nrepl")))
