(ns clojure-skill.roundtrip-test
  "Guards the assumption every structural command rests on: rewrite-clj must
  reproduce a file's bytes, or an edit to one form would silently reformat the
  rest of the file."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure-skill.files :as files]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(defn- round-trip [source]
  (n/string (p/parse-string-all source)))

(def ^:private tricky
  "Constructs that stress the parser, including ones this repo does not contain."
  {"a reader conditional" "#?(:clj 1 :cljs 2)\n"
   "a splicing reader conditional" "(concat #?@(:clj [1 2] :cljs [3]))\n"
   "a ClojureDart tagged literal" "(def x #dart [1 2])\n"
   "a ClojureDart type argument" "(def ^#/(List String) xs nil)\n"
   "a namespaced map" "#:foo{:a 1, :b 2}\n"
   "metadata" "(def ^{:doc \"d\"} ^:private x 1)\n"
   "a discarded form" "(a #_b c)\n"
   "an anonymous function" "(map #(inc %1) xs)\n"
   "a syntax quote" "`(a ~b ~@c)\n"
   "a regex" (str "(def re #\"[0-9]+\")" \newline)
   "a string with escapes" (str "(def s \"a\\nb\\\"c\")" \newline)
   "tabs for indentation" "(defn f []\n\t(inc 1))\n"
   "no trailing newline" "(def a 1)"
   "trailing whitespace" "(def a 1)   \n\n"
   "a form followed by a comment" "(def a 1) ;; why\n"
   "a shebang line" "#!/usr/bin/env bb\n(println 1)\n"
   "non-ASCII content" "(def msg \"日本語のテキスト\")\n"})

(deftest tricky-constructs-round-trip-unchanged
  (doseq [[what source] tricky]
    (is (= source (round-trip source)) what)))

(deftest every-file-in-this-project-round-trips-unchanged
  (doseq [path (files/source-files ["src" "test" "deps.edn" "bb.edn"])]
    (is (= (slurp path) (round-trip (slurp path))) path)))

(deftest windows-line-endings-are-the-one-thing-not-preserved
  (testing "rewrite-clj normalises CRLF to LF, which is why replace-form restores
            them before writing — see replace-does-not-rewrite-line-endings-it-did-not-touch"
    (let [crlf "(def a 1)\r\n(def b 2)\r\n"]
      (is (not= crlf (round-trip crlf)))
      (is (= (str/replace crlf "\r\n" "\n") (round-trip crlf))))))
