(ns clojure-skill.files-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [clojure-skill.files :as files]))

(deftest recognises-every-clojure-dialect
  (doseq [ext ["clj" "cljs" "cljc" "cljd" "bb" "lpy" "edn"]]
    (is (files/clojure-file? (str "a/b." ext)) ext))
  (is (not (files/clojure-file? "a/b.md")))
  (is (not (files/clojure-file? "a/b.java"))))

(deftest extension-check-is-case-insensitive
  (is (files/clojure-file? "A.CLJ")))

(deftest recognises-a-babashka-script-without-an-extension
  (let [file (fs/create-temp-file)]
    (try
      (spit (str file) "#!/usr/bin/env bb\n(println 1)\n")
      (is (files/clojure-file? (str file)))
      (finally (fs/delete-if-exists file)))))

(deftest a-file-given-explicitly-is-kept-whatever-its-name
  (testing "so that `outline some-script` works on an extensionless file"
    (let [file (fs/create-temp-file)]
      (try
        (is (= [(str file)] (files/source-files [(str file)])))
        (finally (fs/delete-if-exists file))))))

(deftest walking-a-directory-skips-build-and-vendor-output
  (let [dir (fs/create-temp-dir)]
    (try
      (fs/create-dirs (fs/path dir "src"))
      (fs/create-dirs (fs/path dir "node_modules"))
      (fs/create-dirs (fs/path dir "target"))
      (spit (str (fs/path dir "src" "a.clj")) "(ns a)")
      (spit (str (fs/path dir "node_modules" "b.clj")) "(ns b)")
      (spit (str (fs/path dir "target" "c.clj")) "(ns c)")
      (spit (str (fs/path dir "src" "d.md")) "not clojure")
      (is (= 1 (count (files/source-files [(str dir)]))))
      (finally (fs/delete-tree dir)))))
