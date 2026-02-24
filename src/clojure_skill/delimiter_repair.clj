(ns clojure-skill.delimiter-repair
  "Delimiter error detection and repair functions using edamame and parinferish"
  (:require [edamame.core :as e]
            [clojure-skill.stats :as stats]
            [parinferish.core :as parinferish]))

(def ^:dynamic *signal-on-bad-parse* true)

(defn delimiter-error?
  "Returns true if the string has a delimiter error specifically.
   Checks both that it's an :edamame/error and has delimiter info.
   Uses :all true to enable all standard Clojure reader features:
   function literals, regex, quotes, syntax-quote, deref, var, etc.
   Also enables :read-cond :allow to support reader conditionals.
   Handles unknown data readers gracefully with a default reader fn."
  [s]
  (try
    (e/parse-string-all s {:all true
                           :features #{:bb :clj :cljs :cljr :cljd :default}
                           :read-cond :allow
                           :readers (fn [_tag] (fn [data] data))
                           :auto-resolve name})
    false ; No error = no delimiter error
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)
            result (and (= :edamame/error (:type data))
                        (contains? data :edamame/opened-delimiter))]
        (when-not result
          (when *signal-on-bad-parse*
            (stats/log-stats! :delimiter-parse-error
                              {:ex-message (ex-message ex)
                               :ex-data (ex-data ex)})))
        result))
    (catch Exception e
      (when *signal-on-bad-parse*
        (stats/log-stats! :delimiter-parse-error
                          {:ex-message (ex-message e)}))
      *signal-on-bad-parse*)))

(defn actual-delimiter-error? [s]
  (binding [*signal-on-bad-parse* false]
    (delimiter-error? s)))

(defn repair-delimiters
  "Repairs delimiter errors using parinferish.
   Returns a map with:
   - :success - boolean indicating if repair was successful
   - :text - the repaired code (if successful)
   - :error - error message (if unsuccessful)"
  [s]
  (try
    (let [repaired (parinferish/flatten
                    (parinferish/parse s {:mode :indent}))]
      {:success true
       :text repaired
       :error nil})
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defn fix-delimiters
  "Takes a Clojure string and attempts to fix delimiter errors.
   Returns the repaired string if successful, false otherwise.
   If no delimiter errors exist, returns the original string."
  [s]
  (if (delimiter-error? s)
    (let [{:keys [text success]} (repair-delimiters s)]
      (when (and success text (not (delimiter-error? text)))
        text))
    s))
