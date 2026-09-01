(ns clojure-skill.test-runner
  "Runs every test namespace under test/ and exits non-zero on failure."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- test-namespaces []
  (->> (fs/glob "test" "**/*_test.clj")
       (map #(-> (str (fs/relativize (fs/path "test") %))
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn -main [& _]
  (let [nses (test-namespaces)]
    (apply require nses)
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (System/exit (if (pos? (+ fail error)) 1 0)))))
