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
  (is (= 1 (count (matches "(inc _)" "(inc 1)"))))
  (is (str/includes? (first (matches "(inc _)" "(inc 1 2)")) "no match")))

(deftest a-rest-marker-that-is-not-last-is-rejected
  (testing "& swallows what follows it, so a pattern that looks specific and is
            not must fail rather than over-match"
    (is (thrown? clojure.lang.ExceptionInfo (sexp/parse-pattern "(assoc & :id)")))
    (is (thrown? clojure.lang.ExceptionInfo (sexp/parse-pattern "(defn & [x])")))))

(deftest a-variadic-arglist-is-a-valid-replacement
  (testing "& is a rest marker only in a pattern; in a replacement it is code"
    (is (some? (sexp/parse-replacement "(defn f [x & args] (apply + x args))")))))

(deftest a-name-carrying-metadata-still-matches-by-name
  (testing "otherwise looking a var up by name silently fails for every ^:private one"
    (is (= 1 (count (matches "(defn hidden &)" "(defn ^:private hidden [x] x)"))))
    (is (= 1 (count (matches "(def hidden &)" "(def ^{:doc \"d\"} hidden 1)"))))))

(deftest a-namespaced-map-is-not-a-plain-map
  (testing "its keys carry the prefix, so reporting a hit would name absent keys"
    (is (str/includes? (first (matches "{:a 1}" "(def x #:foo{:a 1})")) "no match"))
    (is (= 1 (count (matches "{:a 1}" "(def y {:a 1})"))))))

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
        ;; stderr is captured too: the refusal paths report there, and letting it
        ;; through makes a real failure hard to spot in the run output.
        (binding [*in* (java.io.StringReader. replacement)
                  *err* (java.io.StringWriter.)]
          (with-out-str (reset! code (sexp/replace-form (str file) pattern opts))))
        {:exit @code :content (slurp (str file))})
      (finally (fs/delete-if-exists file)))))

(deftest replace-changes-only-the-matched-form
  (let [{:keys [exit content]}
        (replace-in "(ns a)\n\n;; keep me\n(defn f [x] x)\n\n(defn g [] 1)\n"
                    "(defn f &)" "(defn f [x] (inc x))" {})]
    (is (zero? exit))
    (is (= "(ns a)\n\n;; keep me\n(defn f [x] (inc x))\n\n(defn g [] 1)\n" content)
        "blank lines, the comment and the trailing newline are all untouched")))

(deftest replace-keeps-comments-written-with-the-replacement
  (let [{:keys [content]} (replace-in "(defn f [] 1)\n" "(defn f &)"
                                      ";; why this is 2\n(defn f [] 2)" {})]
    (is (str/includes? content ";; why this is 2"))))

(deftest replace-does-not-rewrite-line-endings-it-did-not-touch
  (testing "rewrite-clj normalises CRLF, so without restoring them a one-form
            edit would show up as a whole-file diff"
    (let [{:keys [content]} (replace-in "(defn f [] 1)\r\n(defn g [] 2)\r\n"
                                        "(defn f &)" "(defn f [] 9)" {})]
      (is (= "(defn f [] 9)\r\n(defn g [] 2)\r\n" content)))))

(deftest replacing-in-a-file-that-does-not-exist-is-reported
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such file"
                        (sexp/replace-form "/nowhere/nope.clj" "(defn f &)" {}))))

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
  (is (thrown? clojure.lang.ExceptionInfo (sexp/parse-pattern "(a) (b)")))
  (is (thrown? clojure.lang.ExceptionInfo (sexp/parse-pattern ""))))

(deftest an-unreadable-file-is-reported-rather-than-counted-as-no-match
  (testing "an agent must not read \"no match\" as \"not defined anywhere\" when
            the search never managed to look at the file"
    (let [dir (fs/create-temp-dir)]
      (try
        (spit (str (fs/path dir "broken.clj")) "(defn foo [] (println 1)")
        (spit (str (fs/path dir "ok.clj")) "(defn bar [] 1)")
        (let [out (with-out-str
                    (binding [*err* (java.io.StringWriter.)]
                      (is (= 1 (sexp/find-forms "(defn foo &)" [(str dir)] {:no-prefilter true})))))]
          (is (str/includes? out "unreadable")))
        (finally (fs/delete-tree dir))))))

(deftest the-prefilter-finds-the-same-matches-as-a-full-scan
  (testing "rg's file set and ours must agree, or a real definition drops out
            of the search with no signal"
    (let [dir (fs/create-temp-dir)]
      (try
        (fs/create-dirs (fs/path dir "gen"))
        (spit (str (fs/path dir ".gitignore")) "gen/\n")
        (spit (str (fs/path dir "gen" "g.clj")) "(defn generated [] 1)")
        (spit (str (fs/path dir "src.clj")) "(defn generated [] 2)")
        (is (= (with-out-str (sexp/find-forms "(defn generated &)" [(str dir)] {:no-prefilter true}))
               (with-out-str (sexp/find-forms "(defn generated &)" [(str dir)] {}))))
        (finally (fs/delete-tree dir))))))

(deftest a-pattern-with-no-literal-to-filter-on-still-searches
  (let [dir (fs/create-temp-dir)]
    (try
      (spit (str (fs/path dir "a.clj")) "(alpha 1)")
      (is (str/includes? (with-out-str (sexp/find-forms "(_ 1)" [(str dir)] {})) "(alpha 1)"))
      (finally (fs/delete-tree dir)))))
