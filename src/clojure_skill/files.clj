(ns clojure-skill.files
  "Clojure source file identification and traversal.

  Kept free of cljfmt and timbre so that outline and find start without paying
  their load cost."
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
  "Whether the file's first line is a Babashka shebang.

  A read error propagates rather than reading as \"not Clojure\": treating an
  unreadable script as out of scope would make the repair hook skip the very file
  it exists to protect."
  [file-path]
  (when (fs/regular-file? file-path)
    (with-open [r (io/reader (fs/file file-path))]
      (some->> (first (line-seq r))
               (re-matches #"^#!/[^\s]+/(bb|env\s{1,3}bb)(\s.*)?$")
               boolean))))

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

  A path given explicitly is used even when its extension is unknown, so an
  extensionless Babashka script can be named directly; only files found by
  walking a directory are filtered.

  Throws on a path that does not exist, because an empty result would otherwise
  read as \"nothing matched\" when the real answer is \"you named the wrong path\"."
  [paths]
  (->> paths
       (mapcat (fn [p]
                 (cond
                   (fs/directory? p)
                   (->> (file-seq (fs/file p))
                        (filter #(.isFile ^java.io.File %))
                        (map fs/path)
                        (remove ignored?)
                        (filter #(contains? extensions (extension %))))

                   (fs/exists? p) [(fs/path p)]

                   :else (throw (ex-info (str "no such file or directory: " p) {:path p})))))
       (map str)
       distinct))
