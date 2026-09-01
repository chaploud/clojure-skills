(ns clojure-skill.cider-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure-skill.cider :as cider]))

(def ^:private url->path #'cider/url->path)
(def ^:private render-inspect #'cider/render-inspect)
(def ^:private one-line #'cider/one-line)
(def ^:private project-frame? #'cider/project-frame?)
(def ^:private format-location #'cider/format-location)
(def ^:private print-test-failures #'cider/print-test-failures)
(def ^:private report-run #'cider/report-run)
(def ^:private static-alternatives @#'cider/static-alternatives)

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
  (testing "the snippet is pasted into a deps.edn, so it has to read as EDN"
    (let [alias-map (edn/read-string (subs cider/middleware-hint
                                           (str/index-of cider/middleware-hint "{:aliases")))]
      (is (contains? (:aliases alias-map) :nrepl))
      (is (contains? (get-in alias-map [:aliases :nrepl :extra-deps]) 'cider/cider-nrepl)))))

(deftest a-location-missing-its-file-still-prints-a-parsable-line
  (testing "cider-nrepl omits file/line for some vars; the shape must survive it"
    (is (= "?:?: my.ns/f" (format-location {"name" "my.ns/f"})))))

(deftest the-not-documented-placeholder-is-not-printed-as-a-doc
  (is (= "a.clj:3: my.ns/f" (format-location {"file" "a.clj" "line" 3 "name" "my.ns/f"
                                              "doc" "(not documented)"})))
  (is (str/includes? (format-location {"file" "a.clj" "line" 3 "name" "my.ns/f" "doc" "adds"})
                     ";; adds")))

(deftest only-failing-assertions-are-printed
  (testing "the whole point of `cider test` is to hand back what broke, not a log"
    (let [out (with-out-str
                (print-test-failures
                 {:my.ns {:passing-test [{"type" "pass" "var" "passing-test" "ns" "my.ns"}]
                          :broken-test [{"type" "fail" "var" "broken-test" "ns" "my.ns"
                                         "file" "my/ns.clj" "line" 12
                                         "expected" "(= 1 2)" "actual" "(not (= 1 2))"
                                         "context" [] "message" ""}]}}))]
      (is (str/includes? out "my/ns.clj:12: FAIL my.ns/broken-test"))
      (is (str/includes? out "expected: (= 1 2)"))
      (is (not (str/includes? out "passing-test"))))))

(deftest a-run-with-no-tests-is-a-failure-when-namespaces-were-named
  (testing "a misspelled or unloaded namespace must not read as a green run"
    (with-out-str
      (is (= 1 (report-run {:summary {}} true)))
      (is (= 0 (report-run {:summary {}} false)))
      (is (= 1 (report-run {:summary {"test" 1 "fail" 1}} false)))
      (is (= 0 (report-run {:summary {"test" 1 "pass" 1}} true))))))

(deftest an-unrecognised-inspector-item-is-still-shown
  (is (= "x{:a 1}" (render-inspect ["x" {:a 1}]))))

(deftest every-static-alternative-names-an-op-this-tool-actually-sends
  (testing "guards against offering a fallback for an op that was never wired up"
    (let [source (slurp "src/clojure_skill/cider.clj")]
      (doseq [op (keys static-alternatives)]
        (is (< 1 (count (re-seq (re-pattern (str "\"" op "\"")) source)))
            (str op " appears only in the fallback table, so nothing sends it"))))))
