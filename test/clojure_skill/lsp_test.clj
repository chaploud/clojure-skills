(ns clojure-skill.lsp-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [clojure-skill.lsp :as lsp]))

(deftest the-project-root-is-the-nearest-ancestor-holding-a-marker
  (let [dir (fs/create-temp-dir)]
    (try
      (fs/create-dirs (fs/path dir "sub" "deep"))
      (spit (str (fs/path dir "deps.edn")) "{}")
      (spit (str (fs/path dir "sub" "deep" "a.clj")) "(ns a)")
      (is (= (str (fs/real-path dir))
             (lsp/detect-project-root (str (fs/path dir "sub" "deep" "a.clj")))))
      (finally (fs/delete-tree dir)))))

(deftest a-nested-project-wins-over-the-one-containing-it
  (testing "so a monorepo sub-project gets its own bridge"
    (let [dir (fs/create-temp-dir)]
      (try
        (fs/create-dirs (fs/path dir "app"))
        (spit (str (fs/path dir "deps.edn")) "{}")
        (spit (str (fs/path dir "app" "bb.edn")) "{}")
        (spit (str (fs/path dir "app" "a.clj")) "(ns a)")
        (is (= (str (fs/real-path (fs/path dir "app")))
               (lsp/detect-project-root (str (fs/path dir "app" "a.clj")))))
        (finally (fs/delete-tree dir))))))

(deftest the-working-directory-is-the-fallback
  (is (= (System/getProperty "user.dir") (lsp/resolve-root {})))
  (is (= "/explicit" (lsp/resolve-root {:project-root "/explicit"}))))
