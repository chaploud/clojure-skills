(ns clojure-skill.stats
  "Statistics tracking for delimiter repair events"
  (:require [babashka.fs :as fs]
            [taoensso.timbre :as timbre])
  (:import [java.time Instant]))

;; ============================================================================
;; Configuration
;; ============================================================================

(defn normalize-stats-path
  "Normalize a stats file path, handling tilde expansion and relative paths.

  Parameters:
  - path: string path (can be relative, absolute, or use ~)

  Returns: normalized absolute path as string"
  [path]
  (-> path
      fs/expand-home
      fs/absolutize
      fs/normalize
      str))

(def ^:dynamic *enable-stats* false)

(def ^:dynamic *stats-file-path*
  "Stats log file location - can be overridden via binding"
  (let [home (System/getProperty "user.home")]
    (str home "/.clojure-skill/stats.log")))

;; ============================================================================
;; Event Logging
;; ============================================================================

(defn log-stats!
  "Append one EDN entry to the stats file, tagged with the event type and time.

  Never throws: statistics are a side channel, and losing an entry must not fail
  the edit the caller was in the middle of."
  [event-type data]
  (when *enable-stats*
    (try
      (when-let [parent (fs/parent *stats-file-path*)]
        (fs/create-dirs parent))
      (spit *stats-file-path*
            (str (pr-str (merge {:event-type event-type
                                 :timestamp (str (Instant/now))}
                                data))
                 "\n")
            :append true)
      (catch Exception e
        (timbre/debug "could not write stats entry:" (ex-message e))))))

(defn log-event!
  "Log a delimiter event with the hook event and file it came from."
  [event-type hook-event file-path]
  (log-stats! event-type {:hook-event hook-event :file-path file-path}))
