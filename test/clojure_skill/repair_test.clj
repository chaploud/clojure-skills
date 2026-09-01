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
  (let [{:keys [text delimiter-fixed]} (repair/repair-string "(defn f [x]\n  (+ x 1")]
    (is (true? delimiter-fixed))
    (is (= "(defn f [x]\n  (+ x 1))" text))
    (is (not (dr/actual-delimiter-error? text)))))

(deftest an-unbalanced-string-cannot-be-repaired
  (testing "so the stdin filter exits non-zero and leaves the input alone"
    (is (nil? (repair/repair-string "(def a \"oops)")))))

(deftest a-mismatched-closer-is-corrected-to-its-opener
  (is (= "(def a [1 2])" (:text (repair/repair-string "(def a [1 2)")))))

(deftest a-stray-closing-delimiter-is-removed
  (testing "pinned deliberately: this is lossy, and a change to it should be a
            decision rather than a surprise"
    (let [{:keys [text delimiter-fixed]} (repair/repair-string "(def a 1))")]
      (is (true? delimiter-fixed))
      (is (= "(def a 1)" text)))))

(deftest empty-input-is-not-a-repair-failure
  (is (= "" (:text (repair/repair-string "")))))

(deftest formatting-can-be-turned-off-without-losing-delimiter-repair
  (testing "which is what the hook's --cljfmt flag selects"
    (let [{:keys [text formatted]} (repair/repair-string "(defn f [x]\n(+ x 1))" {:format? false})]
      (is (false? formatted))
      (is (= "(defn f [x]\n(+ x 1))" text)))))
