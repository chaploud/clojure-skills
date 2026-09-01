(ns clojure-skill.roundtrip-test
  "Guards the assumption every structural command rests on: rewrite-clj must
  reproduce a source file byte for byte, or an edit would silently reformat the
  parts it did not touch."
  (:require [clojure.test :refer [deftest is]]
            [clojure-skill.files :as files]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(deftest every-file-in-this-project-round-trips-unchanged
  (doseq [path (files/source-files ["src" "test" "deps.edn" "bb.edn"])]
    (is (= (slurp path) (n/string (p/parse-file-all path))) path)))
