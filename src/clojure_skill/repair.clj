(ns clojure-skill.repair
  "Delimiter repair and formatting for Clojure source, in place or as a filter.

  Formatting goes through cljfmt.core with the project's own configuration
  loaded, so a file repaired here and a file formatted by the project's cljfmt
  come out the same."
  (:require [babashka.fs :as fs]
            [cljfmt.config :as cljfmt-config]
            [cljfmt.core :as cljfmt]
            [clojure.string :as str]
            [clojure-skill.delimiter-repair :refer [fix-delimiters]]
            [clojure-skill.files :as files]))

(def ^:private config-cache
  "Loaded cljfmt config per directory. Reading .cljfmt.edn once per directory
  matters for `repair` over many files; the hook only ever sees one."
  (atom {}))

(defn config-for
  "cljfmt configuration for source living in dir."
  [dir]
  (let [dir (str (or dir "."))]
    (or (@config-cache dir)
        (let [cfg (cljfmt-config/load-config dir)]
          (swap! config-cache assoc dir cfg)
          cfg))))

(defn repair-string
  "Repair one piece of source, and format it unless format? is false.

  Returns {:text ... :delimiter-fixed ... :formatted ...}, or nil when the
  delimiters cannot be balanced. :dir is where to look for .cljfmt.edn."
  ([source] (repair-string source {}))
  ([source {:keys [dir format?] :or {format? true}}]
   (when-let [balanced (fix-delimiters source)]
     (let [text (if format?
                  (cljfmt/reformat-string balanced (config-for dir))
                  balanced)]
       {:text text
        :delimiter-fixed (not= balanced source)
        :formatted (not= text balanced)}))))

(defn repair-file!
  "Repair and format path in place.

  Returns {:success :delimiter-fixed :formatted :message :fault}. :fault is
  :delimiters when the file's delimiters could not be balanced and :unexpected
  for anything else — the caller reverts only for the former, since reverting a
  file because the formatter crashed would discard a good edit."
  ([path] (repair-file! path {}))
  ([path opts]
   (try
     (let [source (slurp path :encoding "UTF-8")]
       (if-let [{:keys [text delimiter-fixed formatted]}
                (repair-string source (assoc opts :dir (fs/parent path)))]
         (do
           (when (not= text source)
             (spit path text :encoding "UTF-8"))
           {:success true
            :delimiter-fixed delimiter-fixed
            :formatted formatted
            :message (cond delimiter-fixed "delimiters repaired"
                           formatted "formatted"
                           :else "no changes needed")})
         {:success false
          :fault :delimiters
          :delimiter-fixed false
          :formatted false
          :message "delimiters could not be balanced"}))
     (catch Exception e
       {:success false
        :fault :unexpected
        :delimiter-fixed false
        :formatted false
        :message (str "could not repair: " (ex-message e))}))))

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
    (let [{:keys [success delimiter-fixed formatted message]} (repair-file! path)
          tags (remove nil? [(when delimiter-fixed "delimiter-fixed")
                             (when formatted "formatted")])]
      (println (format "%s: %s%s" path message
                       (if (seq tags) (str " [" (str/join ", " tags) "]") "")))
      (if success 0 1))))

(defn run
  "Repair the given files in place, or filter stdin to stdout when none are given.

  Exits 1 if any file failed, so the code stays meaningful however many files
  were named."
  [paths]
  (if (empty? paths)
    (run-stdin)
    (if (some pos? (mapv run-file paths)) 1 0)))
