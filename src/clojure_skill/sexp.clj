(ns clojure-skill.sexp
  "Structure-aware reading, searching and editing of Clojure source.

  Everything here goes through rewrite-clj, which reproduces a file's whitespace,
  comments and reader macros exactly, so an edit cannot disturb the parts of the
  file it did not target."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
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
;; Matching compares nodes rather than the values `sexpr` would produce: `sexpr`
;; rewrites reader conditionals and tagged literals into `(read-string "...")`,
;; so `#?(:clj a)` and `#dart [1]` would compare by an incidental shape instead
;; of by what was written. Leaves therefore compare by their printed form, which
;; is exactly what the caller typed in the pattern.
;;
;; Consequences worth knowing: collections match in written order, so
;; `{:a 1 :b 2}` does not match `{:b 2 :a 1}`; and `'x` does not match
;; `(quote x)`, because they are different nodes.

(defn- significant
  "Children of node with whitespace and comments dropped."
  [node]
  (remove n/whitespace-or-comment? (n/children node)))

(defn- leaf-string [node]
  (str/trim (n/string node)))

(defn- marker
  "The literal text of a leaf pattern node, or nil for a branch."
  [node]
  (when-not (n/inner? node) (leaf-string node)))

(defn- unwrap-meta
  "The value a `:meta` node annotates.

  `^:private foo` parses as a :meta node holding the metadata and the value, so
  a pattern naming `foo` has to look past the annotation — otherwise looking a
  var up by name silently fails for every ^:private definition."
  [node]
  (if (= :meta (n/tag node))
    (recur (last (significant node)))
    node))

(declare match-children?)

(defn- match-node? [pattern node]
  (let [pat (marker pattern)]
    (cond
      (= wildcard pat) true
      (n/inner? pattern) (let [node (if (= :meta (n/tag pattern)) node (unwrap-meta node))]
                           (and (n/inner? node)
                                (= (n/tag pattern) (n/tag node))
                                (match-children? (significant pattern) (significant node))))
      :else (let [node (unwrap-meta node)]
              (and (not (n/inner? node)) (= pat (leaf-string node)))))))

(defn- match-children? [pats nodes]
  (cond
    (and (empty? pats) (empty? nodes)) true
    (= rest-marker (some-> (first pats) marker)) true
    (or (empty? pats) (empty? nodes)) false
    (match-node? (first pats) (first nodes)) (recur (rest pats) (rest nodes))
    :else false))

(defn- check-rest-markers!
  "Reject `&` anywhere but at the end of a sequence.

  `&` swallows everything after it, so `(assoc & :id)` would quietly match every
  assoc rather than the ones ending in :id — a pattern that looks specific and is
  not. Only patterns are checked; a replacement is ordinary code, where `& args`
  is an arglist."
  [node]
  (when (n/inner? node)
    (let [children (vec (significant node))]
      (doseq [[i child] (map-indexed vector children)]
        (when (and (= rest-marker (marker child))
                   (not= i (dec (count children))))
          (throw (ex-info (format "& must be the last element of a sequence, but %s has %d form(s) after it"
                                  (n/string node) (- (count children) i 1))
                          {:form (n/string node)})))
        (check-rest-markers! child)))))

(defn- parse-forms
  "Parse s, returning [forms-node significant-forms] or throwing when s does not
  hold exactly one form. Label names s in the error."
  [s label]
  (let [root (p/parse-string-all s)
        forms (significant root)]
    (when-not (= 1 (count forms))
      (throw (ex-info (format "%s must be exactly one form, got %d" label (count forms))
                      {:source s})))
    [root forms]))

(defn parse-pattern
  "Parse a search pattern into a node."
  [s]
  (let [[_ forms] (parse-forms s "pattern")]
    (doto (first forms) check-rest-markers!)))

(defn parse-replacement
  "Parse a replacement into a node, keeping any comments written around the form.

  The whole parsed input is kept rather than just the form, so a leading `;;`
  line the caller wrote is inserted with it instead of being silently dropped."
  [s]
  (first (parse-forms s "replacement")))

;; ============================================================================
;; Scanning
;; ============================================================================

(defn- namespaced-map-body?
  "Whether loc is the map inside `#:foo{…}`.

  Its keys carry the prefix, so reporting it as a match for a plain map pattern
  would name keys the file does not contain."
  [loc]
  (some-> (z/up loc) z/node n/tag (= :namespaced-map)))

(defn- scan
  "Zipper locations whose node matches pattern.

  With top-level-only? set, only the file's direct children are considered, which
  is what keeps `replace` from rewriting a form nested inside another."
  [pattern zloc top-level-only?]
  (let [step (if top-level-only? z/right z/next)]
    (loop [loc zloc acc []]
      (if (or (nil? loc) (z/end? loc))
        acc
        (recur (step loc)
               (if (and (not (z/whitespace-or-comment? loc))
                        (not (namespaced-map-body? loc))
                        (match-node? pattern (z/node loc)))
                 (conj acc loc)
                 acc))))))

(defn- pattern-literals
  "Literal leaf strings in pattern, depth-first, excluding the wildcards.
  Used to pre-filter candidate files with ripgrep."
  [pattern]
  (if (n/inner? pattern)
    (mapcat pattern-literals (significant pattern))
    (let [s (leaf-string pattern)]
      (when-not (#{wildcard rest-marker} s) [s]))))

(defn- ripgrep-files
  "Set of files under paths that ripgrep says contain literal.

  Returns nil when ripgrep cannot answer (missing, or exited with an error), so
  the caller scans everything rather than reading rg's empty output as \"nothing
  matches\". `--no-ignore --hidden` keeps rg's file set the same as ours: rg
  otherwise skips gitignored paths, and a generated or vendored source would
  silently drop out of the search."
  [literal paths]
  (when literal
    (try
      (let [globs (mapcat (fn [ext] ["--glob" (str "*." ext)]) files/extensions)
            {:keys [exit out]} (process/sh (concat ["rg" "-l" "--no-ignore" "--hidden"
                                                    "--fixed-strings"]
                                                   globs
                                                   ["--" literal]
                                                   paths))]
        (case exit
          0 (set (str/split-lines (str/trim out)))
          1 #{}
          nil))
      (catch Exception _ nil))))

;; ============================================================================
;; Line endings
;; ============================================================================

(defn- crlf?
  "Whether source uses Windows line endings.

  rewrite-clj normalises CRLF to LF, so without restoring them an edit to one
  form would rewrite every line in the file."
  [source]
  (str/includes? source "\r\n"))

(defn- restore-line-endings [source updated]
  (if (crlf? source)
    (str/replace updated "\n" "\r\n")
    updated))

;; ============================================================================
;; Output helpers
;; ============================================================================

(defn- first-line [s]
  (first (str/split-lines s)))

(defn- truncate [s width]
  (if (> (count s) width) (str (subs s 0 width) " …") s))

(defn- describe
  "A match as `path:line:col: <first line of the form>`."
  [path loc width]
  (let [[line col] (z/position loc)]
    (format "%s:%d:%d: %s" path line col
            (truncate (first-line (n/string (z/node loc))) width))))

(defn- zloc-of-string [source]
  (z/of-string source {:track-position? true}))

;; ============================================================================
;; Commands
;; ============================================================================

(defn outline
  "Print one line per top-level form: `path:START-END: <first line>`.

  Returns 1 when any file could not be read, so an incomplete answer is not
  reported as a complete one."
  [paths {:keys [width] :or {width 100}}]
  (let [failures (atom 0)]
    (doseq [path (files/source-files paths)]
      (try
        (loop [loc (zloc-of-string (slurp path))]
          (when (and loc (not (z/end? loc)))
            (when-not (z/whitespace-or-comment? loc)
              (let [[line _] (z/position loc)
                    text (n/string (z/node loc))
                    end (+ line (dec (count (str/split-lines text))))]
                (println (format "%s:%d-%d: %s" path line end
                                 (truncate (first-line text) width)))))
            (recur (z/right loc))))
        (catch Exception e
          (swap! failures inc)
          (binding [*out* *err*]
            (println (format "%s: cannot read (%s)" path (ex-message e)))))))
    (if (pos? @failures) 1 0)))

(defn find-forms
  "Print `path:line:col: <first line>` for every node matching pattern.

  A file that cannot be read is reported on stderr and counted, and makes the
  command exit non-zero: an agent must not read \"no match\" as \"not defined
  anywhere\" when the search never looked at the file it broke."
  [pattern-str paths {:keys [limit width no-prefilter] :or {limit 50 width 120}}]
  (let [pattern (parse-pattern pattern-str)
        paths (or (seq paths) ["."])
        allowed (when-not no-prefilter
                  (ripgrep-files (first (pattern-literals pattern)) paths))
        candidates (cond->> (files/source-files paths)
                     allowed (filter (comp allowed str)))
        failures (atom 0)
        hits (for [path candidates
                   loc (try (scan pattern (zloc-of-string (slurp path)) false)
                            (catch Exception e
                              (swap! failures inc)
                              (binding [*out* *err*]
                                (println (format "%s: cannot read (%s)" path (ex-message e))))
                              nil))]
               (describe path loc width))
        shown (vec (take limit hits))]
    (run! println shown)
    (when (seq (drop limit hits))
      (println (format ";; %d shown, more matches exist — raise --limit to see them"
                       (count shown))))
    (when (empty? shown)
      (println (format ";; no match for %s in %d file(s)%s"
                       pattern-str (count candidates)
                       (if (pos? @failures) (format ", %d unreadable" @failures) ""))))
    (if (pos? @failures) 1 0)))

(defn replace-form
  "Replace the single top-level form matching pattern with a form read from stdin.

  Refuses unless exactly one form matches, so a pattern looser than the caller
  intended fails loudly instead of editing the wrong definition."
  [path pattern-str {:keys [dry-run]}]
  (when-not (fs/exists? path)
    (throw (ex-info (str "no such file: " path) {:path path})))
  (let [pattern (parse-pattern pattern-str)
        source (slurp path)
        matches (scan pattern (zloc-of-string source) true)]
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
      (let [new-node (parse-replacement (str/trim (slurp *in*)))
            loc (first matches)
            [line col] (z/position loc)
            old-node (z/node loc)
            updated (restore-line-endings source (z/root-string (z/replace loc new-node)))]
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
