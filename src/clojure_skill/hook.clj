(ns clojure-skill.hook
  "Claude Code hook that keeps delimiters balanced across the agent's edits.

  On PreToolUse it rewrites a Write whose content is unbalanced; on PostToolUse
  it repairs an Edit in place, restoring the pre-edit backup when the damage
  cannot be repaired, so the agent never continues from a file it broke."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure-skill.delimiter-repair
             :refer [delimiter-error? fix-delimiters actual-delimiter-error?]]
            [clojure-skill.files :refer [clojure-file?]]
            [clojure-skill.repair :as repair]
            [clojure-skill.stats :as stats]
            [clojure-skill.tmp :as tmp]
            [taoensso.timbre :as timbre]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def ^:dynamic *enable-cljfmt* false)
(def ^:dynamic *enable-revert* true)

;; ============================================================================
;; Claude Code Hook Functions
;; ============================================================================

(defn backup-file
  "Backup file to temp location, returns backup path"
  [file-path session-id]
  (let [ctx {:session-id session-id}
        backup (tmp/backup-path ctx file-path)
        content (slurp file-path :encoding "UTF-8")]
    ;; Ensure parent directories exist
    (when-let [parent (fs/parent backup)]
      (fs/create-dirs parent))
    (spit backup content :encoding "UTF-8")
    backup))

(defn restore-file
  "Restore file from its backup, returning whether it worked.

  The backup is deleted only after a successful write: a failed restore that
  removed the backup anyway would destroy the only copy of the file's pre-edit
  content."
  [file-path backup-path]
  (boolean
   (when (fs/exists? backup-path)
     (try
       (spit file-path (slurp backup-path :encoding "UTF-8") :encoding "UTF-8")
       (fs/delete-if-exists backup-path)
       true
       (catch Exception e
         (timbre/error "could not restore" file-path "from" backup-path ":" (ex-message e))
         false)))))

(defn delete-backup
  "Delete backup file if it exists"
  [backup-path]
  (fs/delete-if-exists backup-path))

(defn fix-and-format-file!
  "Repair and format a file, recording what happened in the stats log."
  [file-path stats-event-prefix]
  (let [before (try (slurp file-path :encoding "UTF-8") (catch Exception _ nil))
        broken? (and before (actual-delimiter-error? before))]
    (when broken?
      (stats/log-event! :delimiter-error stats-event-prefix file-path))
    (let [result (repair/repair-file! file-path {:format? *enable-cljfmt*})]
      (stats/log-event! (cond (not (:success result)) :delimiter-fix-failed
                              (:delimiter-fixed result) :delimiter-fixed
                              :else :delimiter-ok)
                        stats-event-prefix file-path)
      (timbre/debug " " file-path (:message result))
      result)))

(defmulti process-hook
  (fn [hook-input]
    [(:hook_event_name hook-input) (:tool_name hook-input)]))

(defmethod process-hook :default [_] nil)

(defmethod process-hook ["PreToolUse" "Write"]
  [{:keys [tool_input]}]
  (let [{:keys [file_path content]} tool_input]
    (when (clojure-file? file_path)
      (timbre/debug "PreWrite: clojure" file_path)
      (if (delimiter-error? content)
        (let [actual-error? (actual-delimiter-error? content)]
          (when actual-error?
            (stats/log-event! :delimiter-error "PreToolUse:Write" file_path))
          (timbre/debug "  Delimiter error detected, attempting fix")
          (if-let [fixed-content (fix-delimiters content)]
            (do
              (when actual-error?
                (stats/log-event! :delimiter-fixed "PreToolUse:Write" file_path))
              (timbre/debug "  Fix successful, allowing write with updated content")
              {:hookSpecificOutput
               {:hookEventName "PreToolUse"
                :permissionDecision "allow"
                :updatedInput {:file_path file_path
                               :content fixed-content}}})
            (do
              (when actual-error?
                (stats/log-event! :delimiter-fix-failed "PreToolUse:Write" file_path))
              (timbre/debug "  Fix failed, denying write")
              {:hookSpecificOutput
               {:hookEventName "PreToolUse"
                :permissionDecision "deny"
                :permissionDecisionReason "Delimiter errors found and could not be auto-fixed"}})))
        (do
          (stats/log-event! :delimiter-ok "PreToolUse:Write" file_path)
          (timbre/debug "  No delimiter errors, allowing write")
          nil)))))

(defmethod process-hook ["PreToolUse" "Edit"]
  [{:keys [tool_input session_id]}]
  (let [{:keys [file_path]} tool_input]
    (when (clojure-file? file_path)
      (timbre/debug "PreEdit: clojure" file_path)

      ;; Only create backup if revert is enabled
      (when *enable-revert*
        (try
          (backup-file file_path session_id)
          nil
          (catch Exception e
            ;; Say so now: without a backup, a PostToolUse repair failure cannot
            ;; roll the file back, and the agent needs to know that before it edits.
            (timbre/error "could not back up" file_path ":" (ex-message e))
            {:hookSpecificOutput
             {:hookEventName "PreToolUse"
              :permissionDecision "allow"
              :permissionDecisionReason
              (str "Could not back up " file_path
                   ", so an unfixable delimiter error cannot be rolled back: "
                   (ex-message e))}}))))))

(defmethod process-hook ["PostToolUse" "Write"]
  [{:keys [tool_input tool_response]}]
  (let [{:keys [file_path]} tool_input]
    (when (and (clojure-file? file_path) tool_response *enable-cljfmt*)
      (repair/repair-file! file_path {:format? true})
      nil)))

(defn- blocked
  "A PostToolUse block telling the agent what happened to its edit."
  [reason]
  {:decision "block"
   :reason reason
   :hookSpecificOutput {:hookEventName "PostToolUse" :additionalContext reason}})

(defmethod process-hook ["PostToolUse" "Edit"]
  [{:keys [tool_input tool_response session_id]}]
  (let [{:keys [file_path]} tool_input]
    (when (and (clojure-file? file_path) tool_response)
      (let [backup (tmp/backup-path {:session-id session_id} file_path)
            had-backup? (fs/exists? backup)
            {:keys [success fault message]} (fix-and-format-file! file_path "PostToolUse:Edit")]
        (try
          (cond
            success nil

            ;; An unexpected failure — an unreadable file, a formatter crash — is
            ;; not evidence the edit was bad, so the edit stands and the agent is
            ;; told what actually went wrong.
            (= :unexpected fault)
            (blocked (str "Could not process " file_path ": " message
                          ". The edit was left in place."))

            (not (and *enable-revert* had-backup?))
            (blocked (str "Delimiter errors in " file_path " could not be auto-fixed, and "
                          (if *enable-revert*
                            "no backup was available, so the file was not restored."
                            "revert is disabled, so the file was not restored.")))

            (restore-file file_path backup)
            (blocked (str "Delimiter errors could not be auto-fixed. " file_path
                          " was restored to its state before the edit."))

            :else
            (blocked (str "Delimiter errors in " file_path " could not be auto-fixed, and "
                          "restoring the backup failed. The file is still broken; "
                          "the backup is at " backup)))
          (finally
            (when (and success had-backup?)
              (delete-backup backup))))))))

(defmethod process-hook ["PreToolUse" "mcp__morph-mcp__edit_file"]
  [input]
  (let [path (get-in input [:tool_input :path])]
    (process-hook (-> input
                      (assoc :tool_name "Edit")
                      (assoc-in [:tool_input :file_path] path)))))

(defmethod process-hook ["PostToolUse" "mcp__morph-mcp__edit_file"]
  [input]
  (let [path (get-in input [:tool_input :path])]
    (process-hook (-> input
                      (assoc :tool_name "Edit")
                      (assoc-in [:tool_input :file_path] path)))))

(defmethod process-hook ["Stop" nil]
  [{:keys [session_id]}]
  (timbre/info "Stop: cleaning up session" session_id)
  (try
    (let [report (tmp/cleanup-session! {:session-id session_id})]
      (timbre/info "  Cleanup attempted for session IDs:" (:attempted report))
      (timbre/info "  Deleted directories:" (:deleted report))
      (timbre/info "  Skipped (non-existent):" (:skipped report))
      (when (seq (:errors report))
        (timbre/warn "  Errors during cleanup:")
        (doseq [{:keys [path error]} (:errors report)]
          (timbre/warn "    " path "-" error)))
      nil)
    (catch Exception e
      (timbre/error "  Unexpected error during cleanup:" (.getMessage e))
      nil)))

(defn run
  "Read one hook payload from stdin, act on it, and print any response.

  Returns 0 even when nothing matched: a hook that exits non-zero on an event it
  simply does not handle would surface as a tool failure to the agent."
  [{:keys [cljfmt no-revert stats stats-file log-level log-file]}]
  (let [log-file (or log-file "./.clojure-skill-hooks.log")
        stats-file (or stats-file (str (fs/path (fs/home) ".clojure-skill" "stats.log")))]
    (timbre/set-config!
     {:appenders {:spit (assoc (timbre/spit-appender {:fname log-file})
                               :enabled? (some? log-level)
                               :min-level (or log-level :report)
                               :ns-filter (if log-level {:allow "clojure-skill.*"} {:deny "*"}))}})
    (binding [*enable-cljfmt* (boolean cljfmt)
              *enable-revert* (not no-revert)
              stats/*enable-stats* (boolean stats)
              stats/*stats-file-path* (stats/normalize-stats-path stats-file)]
      (try
        (let [input (slurp *in*)
              _ (timbre/debug "INPUT:" input)
              response (process-hook (json/parse-string input true))]
          (timbre/debug "OUTPUT:" (json/generate-string response))
          (when response
            (println (json/generate-string response)))
          0)
        (catch Exception e
          (timbre/error "hook error:" (ex-message e))
          (binding [*out* *err*]
            (println "hook error:" (ex-message e)))
          2)))))
