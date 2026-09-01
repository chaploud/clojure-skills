(ns clojure-skill.main-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-skill.main :as main]
            [clojure-skill.repl :as repl]))

(def ^:private resolve-port #'main/resolve-port)

(deftest a-port-given-explicitly-wins
  (is (= 1234 (resolve-port {:port 1234}))))

(deftest the-single-server-in-this-directory-is-inferred
  (with-redefs [repl/discover (fn [] [{:port 7888 :matches-cwd true}
                                      {:port 9999 :matches-cwd false}])]
    (is (= 7888 (resolve-port {})))))

(deftest no-server-here-is-an-error-not-a-guess
  (with-redefs [repl/discover (fn [] [{:port 9999 :matches-cwd false}])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--port" (resolve-port {})))))

(deftest several-servers-here-is-an-error-not-a-guess
  (testing "silently picking one would evaluate against the wrong project"
    (with-redefs [repl/discover (fn [] [{:port 1 :matches-cwd true}
                                        {:port 2 :matches-cwd true}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--port" (resolve-port {}))))))

(deftest a-remote-host-must-name-its-port
  (testing "discovery only probes this machine, so inferring a port for a remote
            host would connect somewhere the caller did not ask for"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--port"
                          (resolve-port {:host "elsewhere"})))))

(deftest an-unknown-command-is-reported-and-not-silently-ignored
  (let [code (atom nil)]
    (with-out-str
      (binding [*err* (java.io.StringWriter.)]
        (reset! code (main/dispatch ["nope"] {}))))
    (is (= 1 @code))))
