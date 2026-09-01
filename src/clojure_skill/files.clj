(ns clojure-skill.files
  "Clojure source file identification and traversal.

  Kept free of cljfmt/timbre so that lightweight tools (clj-sexp) can require
  it without paying their startup cost."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def extensions
  "File extensions this skill treats as Clojure source."
  #{"clj" "cljs" "cljc" "cljd" "bb" "lpy" "edn"})

(def ^:private ignored-dirs
  #{"node_modules" "target" ".git" ".cpcache" ".shadow-cljs" ".lsp" ".clj-kondo"
    "build" ".dart_tool" ".cljd" "out"})

(defn- babashka-shebang?
  "Whether the file's first line is a Babashka shebang."
  [file-path]
  (when (fs/exists? file-path)
    (try
      (with-open [r (io/reader (fs/file file-path))]
        (some->> (first (line-seq r))
                 (re-matches #"^#!/[^\s]+/(bb|env\s{1,3}bb)(\s.*)?$")
                 boolean))
      (catch Exception _ false))))

(defn extension
  "Lower-cased extension of path, or nil when it has none."
  [path]
  (some-> (fs/extension path) str/lower-case))

(defn clojure-file?
  "Whether path is Clojure source, by extension or Babashka shebang."
  [path]
  (boolean
   (when path
     (or (contains? extensions (extension path))
         (babashka-shebang? path)))))

(defn- ignored? [path]
  (some ignored-dirs (map str (fs/components path))))

(defn source-files
  "Clojure source files under paths, skipping build and vendor directories.

  A path given explicitly is used even when its extension is unknown; only
  files found by walking a directory are filtered."
  [paths]
  (->> paths
       (mapcat (fn [p]
                 (if (fs/directory? p)
                   (->> (file-seq (fs/file p))
                        (filter #(.isFile ^java.io.File %))
                        (map fs/path)
                        (remove ignored?)
                        (filter #(contains? extensions (extension %))))
                   [(fs/path p)])))
       (map str)
       distinct))
