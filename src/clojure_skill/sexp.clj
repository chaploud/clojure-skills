(ns clojure-skill.sexp
  "Structure-aware reading, searching and editing of Clojure source.

  Everything here goes through rewrite-clj, which round-trips source
  byte-for-byte, so edits cannot disturb surrounding whitespace, comments or
  reader macros the way a line-oriented tool does."
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [clojure-skill.files :as files]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]))

(def ^:private wildcard "_")
(def ^:private rest-marker "&")

;; ============================================================================
;; Pattern matching over nodes
;; ============================================================================
;;
;; Matching compares nodes rather than the values `sexpr` would produce:
;; `sexpr` rewrites reader conditionals and tagged literals into
;; `(read-string "...")`, so `#?(:clj a)` and `#dart [1]` would compare by an
;; incidental shape instead of by what was written. Leaves therefore compare by
;; their printed form, which is exactly what the caller typed in the pattern.

(defn- significant
  "Children of node with whitespace and comments dropped."
  [node]
  (remove n/whitespace-or-comment? (n/children node)))

(defn- leaf-string [node]
  (str/trim (n/string node)))

(declare match-children?)

(defn- match-node? [pattern node]
  (let [pat-str (when-not (n/inner? pattern) (leaf-string pattern))]
    (cond
      (= wildcard pat-str) true
      (n/inner? pattern) (and (n/inner? node)
                              (= (n/tag pattern) (n/tag node))
                              (match-children? (significant pattern) (significant node)))
      (n/inner? node) false
      :else (= pat-str (leaf-string node)))))

(defn- match-children? [pats nodes]
  (cond
    (and (empty? pats) (empty? nodes)) true
    (and (seq pats)
         (not (n/inner? (first pats)))
         (= rest-marker (leaf-string (first pats)))) true
    (or (empty? pats) (empty? nodes)) false
    (match-node? (first pats) (first nodes)) (recur (rest pats) (rest nodes))
    :else false))

(defn parse-one-form
  "Parse s into the single node it contains, or throw naming what was expected.

  Label is what to call s in the error, so a bad replacement is not reported as a
  bad pattern."
  ([s] (parse-one-form s "pattern"))
  ([s label]
   (let [forms (significant (p/parse-string-all s))]
     (when-not (= 1 (count forms))
       (throw (ex-info (format "%s must be exactly one form, got %d" label (count forms))
                       {:source s})))
     (first forms))))

;; ============================================================================
;; Scanning
;; ============================================================================

(defn- first-line [s]
  (first (str/split-lines s)))

(defn- truncate [s width]
  (if (> (count s) width) (str (subs s 0 width) " …") s))

(defn- zloc-of-file [path]
  (z/of-string (slurp path) {:track-position? true}))

(defn- scan
  "Zipper locations in path whose node matches pattern.

  With top-level-only? set, only the file's direct children are considered, which
  is what keeps `replace` from rewriting a form nested inside another."
  [pattern zloc top-level-only?]
  (let [step (if top-level-only? z/right z/next)]
    (loop [loc zloc acc []]
      (if (or (nil? loc) (z/end? loc))
        acc
        (recur (step loc)
               (if (and (not (z/whitespace-or-comment? loc))
                        (match-node? pattern (z/node loc)))
                 (conj acc loc)
                 acc))))))

(defn- describe
  "A match as `path:line:col: <first line of the form>`."
  [path loc width]
  (let [[line col] (z/position loc)]
    (format "%s:%d:%d: %s" path line col
            (truncate (first-line (n/string (z/node loc))) width))))

(defn- pattern-literals
  "Literal leaf strings in pattern, in depth-first order, excluding wildcards.
  Used to pre-filter candidate files with ripgrep."
  [pattern]
  (if (n/inner? pattern)
    (mapcat pattern-literals (significant pattern))
    (let [s (leaf-string pattern)]
      (when-not (#{wildcard rest-marker} s) [s]))))

(defn- ripgrep-files
  "Set of files under paths that ripgrep says contain literal.

  Returns nil when ripgrep cannot answer (missing, or exited with an error), so
  the caller falls back to scanning everything rather than reading rg's empty
  output as \"nothing matches\"."
  [literal paths]
  (when literal
    (try
      (let [globs (mapcat (fn [ext] ["--glob" (str "*." ext)]) files/extensions)
            {:keys [exit out]} (process/sh (concat ["rg" "-l" "--fixed-strings"]
                                                   globs
                                                   ["--" literal]
                                                   paths))]
        (case exit
          0 (set (str/split-lines (str/trim out)))
          1 #{}
          nil))
      (catch Exception _ nil))))

;; ============================================================================
;; Commands
;; ============================================================================

(defn outline
  "Print one line per top-level form: `path:START-END: <first line>`.

  Lets an agent map a large file for the cost of a few dozen lines, then read
  only the range it needs."
  [paths {:keys [width] :or {width 100}}]
  (doseq [path (files/source-files paths)]
    (try
      (loop [loc (zloc-of-file path)]
        (when (and loc (not (z/end? loc)))
          (when-not (z/whitespace-or-comment? loc)
            (let [[line _] (z/position loc)
                  text (n/string (z/node loc))
                  end (+ line (dec (count (str/split-lines text))))]
              (println (format "%s:%d-%d: %s" path line end
                               (truncate (first-line text) width)))))
          (recur (z/right loc))))
      (catch Exception e
        (binding [*out* *err*]
          (println (format "%s: cannot parse (%s)" path (ex-message e))))))))

(defn find-forms
  "Print `path:line:col: <first line>` for every node matching pattern."
  [pattern-str paths {:keys [limit width no-prefilter] :or {limit 50 width 120}}]
  (let [pattern (parse-one-form pattern-str)
        paths (or (seq paths) ["."])
        allowed (when-not no-prefilter
                  (ripgrep-files (first (pattern-literals pattern)) paths))
        candidates (cond->> (files/source-files paths)
                     allowed (filter (comp allowed str)))
        hits (for [path candidates
                   loc (try (scan pattern (zloc-of-file path) false)
                            (catch Exception _ nil))]
               (describe path loc width))
        shown (take limit hits)]
    (run! println shown)
    (when (seq (drop limit hits))
      (println (format ";; %d shown, more matches exist — raise --limit to see them"
                       (count shown))))
    (when (empty? shown)
      (println (format ";; no match for %s in %d file(s)" pattern-str (count candidates))))))

(defn replace-form
  "Replace the single top-level form matching pattern with a form read from stdin.

  Refuses unless exactly one form matches, so a pattern that is looser than the
  caller intended fails loudly instead of editing the wrong definition."
  [path pattern-str {:keys [dry-run]}]
  (let [pattern (parse-one-form pattern-str)
        matches (scan pattern (zloc-of-file path) true)
        replacement (str/trim (slurp *in*))]
    (cond
      (empty? matches)
      (do (binding [*out* *err*]
            (println (format "%s: no top-level form matches %s" path pattern-str)))
          1)

      (< 1 (count matches))
      (do (binding [*out* *err*]
            (println (format "%s: %d top-level forms match %s — narrow the pattern:"
                             path (count matches) pattern-str))
            (doseq [loc matches]
              (println (str "  " (describe path loc 100)))))
          1)

      :else
      (let [new-node (parse-one-form replacement "replacement")
            loc (first matches)
            [line col] (z/position loc)
            old-node (z/node loc)
            updated (z/root-string (z/replace loc new-node))]
        ;; Re-parse before writing: a replacement that reads on its own can still
        ;; leave the file unreadable, e.g. an unterminated string.
        (p/parse-string-all updated)
        (when-not dry-run
          (spit path updated))
        (println (format "%s:%d:%d: %s %d line(s) -> %d line(s)"
                         path line col
                         (if dry-run "would replace" "replaced")
                         (count (str/split-lines (n/string old-node)))
                         (count (str/split-lines (n/string new-node)))))
        0))))
