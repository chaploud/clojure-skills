(ns clojure-skill.delimiter-repair-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-skill.delimiter-repair :as dr]))

(deftest reader-macros-are-not-delimiter-errors
  (testing "every dialect's own syntax must read cleanly, or the repair hook
            would rewrite valid files"
    (doseq [source ["(defn f [x] #?(:clj 1 :cljs 2))"
                    "(defn f [] #dart [1 2])"
                    "(def x #js {:a 1})"
                    "(def x #:foo{:a 1})"
                    "(defn f [] #?@(:clj [1 2]))"
                    "(defn f [] `(a ~b ~@c))"
                    "(def re #\"[a-z]+\")"]]
      (is (not (dr/actual-delimiter-error? source)) source))))

(deftest a-genuinely-unbalanced-form-is-an-error
  (is (dr/actual-delimiter-error? "(defn f [x] (+ x 1"))
  (is (dr/actual-delimiter-error? "(defn f [x")))
