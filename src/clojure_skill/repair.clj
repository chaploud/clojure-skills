(ns clojure-skill.repair
  "Delimiter repair and formatting for Clojure files, in place or as a filter."
  (:require [babashka.fs :as fs]
            [cljfmt.core :as cljfmt]
            [clojure.string :as str]
            [clojure-skill.delimiter-repair :refer [fix-delimiters]]
            [clojure-skill.files :as files]
            [clojure-skill.hook :as hook]))

(defn repair-string
  "Repair and format one piece of source.
  Returns {:text ... :delimiter-fixed ... :formatted ...}, or nil when the
  delimiters cannot be balanced."
  [source]
  (when-let [balanced (fix-delimiters source)]
    (let [formatted (try (cljfmt/reformat-string balanced)
                         (catch Exception _ balanced))]
      {:text formatted
       :delimiter-fixed (not= balanced source)
       :formatted (not= formatted balanced)})))

(defn- run-stdin []
  (if-let [{:keys [text]} (repair-string (slurp *in*))]
    (do (print text) (flush) 0)
    (do (binding [*out* *err*]
          (println "could not balance delimiters; input left unchanged"))
        1)))

(defn- run-file [path]
  (cond
    (not (fs/exists? path))
    (do (binding [*out* *err*] (println (str path ": no such file"))) 1)

    (not (files/clojure-file? path))
    (do (binding [*out* *err*] (println (str path ": not a Clojure file"))) 1)

    :else
    (let [{:keys [success delimiter-fixed formatted message]}
          (binding [hook/*enable-cljfmt* true]
            (hook/fix-and-format-file! path true "repair"))
          tags (->> [(when delimiter-fixed "delimiter-fixed") (when formatted "formatted")]
                    (remove nil?))]
      (println (format "%s: %s%s" path message
                       (if (seq tags) (str " [" (str/join ", " tags) "]") "")))
      (if success 0 1))))

(defn run
  "Repair the given files in place, or filter stdin to stdout when none are given.

  Exit code is the number of files that could not be repaired, so a caller can
  branch on failure without parsing the output."
  [paths]
  (if (empty? paths)
    (run-stdin)
    (reduce + 0 (map run-file paths))))
