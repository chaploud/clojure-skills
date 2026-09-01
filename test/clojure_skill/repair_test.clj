(ns clojure-skill.repair-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-skill.delimiter-repair :as dr]
            [clojure-skill.repair :as repair]))

(deftest balanced-source-is-only-formatted
  (let [{:keys [text delimiter-fixed formatted]} (repair/repair-string "(defn f [x]\n(+ x 1))")]
    (is (false? delimiter-fixed))
    (is (true? formatted))
    (is (= "(defn f [x]\n  (+ x 1))" text))))

(deftest already-formatted-source-comes-back-unchanged
  (let [source "(defn f [x]\n  (+ x 1))"
        {:keys [text delimiter-fixed formatted]} (repair/repair-string source)]
    (is (= source text))
    (is (false? delimiter-fixed))
    (is (false? formatted))))

(deftest a-missing-delimiter-is-closed
  (testing "the repaired source parses, which is the property that matters"
    (let [{:keys [text delimiter-fixed]} (repair/repair-string "(defn f [x]\n  (+ x 1")]
      (is (true? delimiter-fixed))
      (is (not (dr/actual-delimiter-error? text))))))

(deftest reader-conditionals-and-tagged-literals-are-not-errors
  (doseq [source ["(defn f [x] #?(:clj 1 :cljs 2))"
                  "(defn f [] #dart [1 2])"
                  "(def x #js {:a 1})"]]
    (is (not (dr/actual-delimiter-error? source)) source)))
