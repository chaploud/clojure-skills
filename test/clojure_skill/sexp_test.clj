(ns clojure-skill.sexp-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure-skill.sexp :as sexp]))

(defn- matches
  "Lines `find` prints for pattern over source written to a temp file."
  [pattern source]
  (let [file (fs/create-temp-file {:suffix ".clj"})]
    (try
      (spit (str file) source)
      (str/split-lines
       (str/trim (with-out-str (sexp/find-forms pattern [(str file)] {:no-prefilter true}))))
      (finally (fs/delete-if-exists file)))))

(deftest wildcard-matches-any-single-form
  (is (= 1 (count (matches "(defn _ &)" "(defn foo [x] x)"))))
  (is (= 1 (count (matches "(assoc _ :id &)" "(assoc m :id 1 :b 2)")))))

(deftest rest-marker-matches-remaining-forms
  (testing "& absorbs any number of trailing forms, including none"
    (is (= 1 (count (matches "(ns _ &)" "(ns a)"))))
    (is (= 1 (count (matches "(ns _ &)" "(ns a (:require [b])) "))))))

(deftest arity-is-exact-without-rest-marker
  (is (str/starts-with? (first (matches "(inc _)" "(inc 1)")) "/"))
  (is (str/includes? (first (matches "(inc _)" "(inc 1 2)")) "no match")))

(deftest collection-type-must-agree
  (testing "a list pattern does not match a vector with the same contents"
    (is (str/includes? (first (matches "(a b)" "[a b]")) "no match"))))

(deftest leaves-compare-as-written
  (testing "an auto-resolved keyword matches the text the caller typed"
    (is (= 1 (count (matches "(check ::authz/view)" "(check ::authz/view)")))))
  (testing "and does not match a different keyword"
    (is (str/includes? (first (matches "(check ::authz/view)" "(check ::authz/edit)"))
                       "no match"))))

(deftest reader-macros-are-matched-not-expanded
  (testing "a reader conditional inside a form does not stop the form matching"
    (is (= 1 (count (matches "(defn _ &)" "(defn f [x] #?(:clj 1 :cljs 2))")))))
  (testing "ClojureDart tagged literals likewise"
    (is (= 1 (count (matches "(defn _ &)" "(defn f [] #dart [1 2])"))))))

(deftest nested-forms-are-searched
  (is (= 2 (count (matches "(inc _)" "(defn f [] (inc 1) (do (inc 2)))")))))

(deftest outline-reports-the-line-range-of-each-top-level-form
  (let [file (fs/create-temp-file {:suffix ".clj"})]
    (try
      (spit (str file) "(ns a)\n\n;; comment\n(defn f\n  [x]\n  x)\n")
      (let [lines (str/split-lines (str/trim (with-out-str (sexp/outline [(str file)] {}))))]
        (is (= 2 (count lines)) "forms are listed; standalone comments are not")
        (is (str/includes? (last lines) ":4-6: (defn f")
            "a multi-line form reports the range an agent can read back"))
      (finally (fs/delete-if-exists file)))))

(defn- replace-in
  "Run replace-form over source, returning its exit code and the file afterwards."
  [source pattern replacement opts]
  (let [file (fs/create-temp-file {:suffix ".clj"})]
    (try
      (spit (str file) source)
      (let [code (atom nil)]
        (binding [*in* (java.io.StringReader. replacement)]
          (with-out-str (reset! code (sexp/replace-form (str file) pattern opts))))
        {:exit @code :content (slurp (str file))})
      (finally (fs/delete-if-exists file)))))

(deftest replace-preserves-surrounding-text
  (let [{:keys [exit content]}
        (replace-in "(ns a)\n\n;; keep me\n(defn f [x] x)\n\n(defn g [] 1)\n"
                    "(defn f &)" "(defn f [x] (inc x))" {})]
    (is (zero? exit))
    (is (str/includes? content ";; keep me"))
    (is (str/includes? content "(defn f [x] (inc x))"))
    (is (str/includes? content "(defn g [] 1)"))))

(deftest replace-refuses-an-ambiguous-pattern
  (let [source "(defn f [] 1)\n(defn g [] 2)\n"
        {:keys [exit content]} (replace-in source "(defn _ &)" "(defn h [] 3)" {})]
    (is (= 1 exit))
    (is (= source content) "an ambiguous pattern must not edit the file")))

(deftest replace-refuses-when-nothing-matches
  (let [source "(defn f [] 1)\n"
        {:keys [exit content]} (replace-in source "(defn nope &)" "(defn h [] 3)" {})]
    (is (= 1 exit))
    (is (= source content))))

(deftest replace-only-considers-top-level-forms
  (testing "a form nested inside another is not a replacement target"
    (let [source "(comment (defn f [] 1))\n"
          {:keys [exit content]} (replace-in source "(defn f &)" "(defn f [] 2)" {})]
      (is (= 1 exit))
      (is (= source content)))))

(deftest dry-run-leaves-the-file-alone
  (let [source "(defn f [] 1)\n"
        {:keys [exit content]} (replace-in source "(defn f &)" "(defn f [] 2)" {:dry-run true})]
    (is (zero? exit))
    (is (= source content))))

(deftest a-pattern-must-be-a-single-form
  (is (thrown? clojure.lang.ExceptionInfo (sexp/parse-one-form "(a) (b)")))
  (is (thrown? clojure.lang.ExceptionInfo (sexp/parse-one-form ""))))
