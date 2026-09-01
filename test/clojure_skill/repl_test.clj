(ns clojure-skill.repl-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-skill.repl :as repl]))

(deftest listening-ports-are-read-from-lsof-output
  (is (= [7888 7889]
         (repl/parse-lsof-ports
          (str "java 1 me 5u IPv6 0t0 TCP *:7888 (LISTEN)\n"
               "bb   2 me 6u IPv4 0t0 TCP 127.0.0.1:7889 (LISTEN)\n")))))

(deftest established-connections-are-not-mistaken-for-servers
  (is (empty? (repl/parse-lsof-ports "java 1 me 7u IPv4 0t0 TCP 127.0.0.1:5000->127.0.0.1:6000 (ESTABLISHED)"))))

(deftest a-port-listed-twice-is-reported-once
  (testing "lsof prints one row per address family for a dual-stack listener"
    (is (= [7888] (repl/parse-lsof-ports
                   (str "java 1 me 5u IPv6 0t0 TCP *:7888 (LISTEN)\n"
                        "java 1 me 6u IPv4 0t0 TCP *:7888 (LISTEN)\n"))))))

(deftest a-bracketed-ipv6-address-is-a-listening-port
  (testing "a server bound only to ::1 would otherwise be invisible to discovery"
    (is (= [7888] (repl/parse-lsof-ports "java 1 me 5u IPv6 0t0 TCP [::1]:7888 (LISTEN)")))
    (is (= [7888] (repl/parse-lsof-ports "java 1 me 5u IPv6 0t0 TCP [::]:7888 (LISTEN)")))))

(deftest no-output-yields-no-ports
  (is (nil? (repl/parse-lsof-ports nil)))
  (is (empty? (repl/parse-lsof-ports ""))))
