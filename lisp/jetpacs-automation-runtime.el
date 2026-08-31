;;; jetpacs-automation-runtime.el --- Durable automation lifecycle -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Durable admission, frozen-revision replay, hybrid execution, activation,
;; reconciliation, and redacted run history for Jetpacs Automations.  Recipe
;; policy lives here rather than in EBP: the protocol layer remains entirely
;; Jetpacs-neutral.

;;; Code:

(require 'cl-lib)
(require 'json)
(require 'seq)
(require 'subr-x)
(require 'ebp)
(require 'ebp-store)
(require 'ebp-sqlite)
(require 'jetpacs-automation-model)
(require 'jetpacs-settings)
(require 'jetpacs-surfaces)
(require 'jetpacs-shell)

(defgroup jetpacs-automations nil
  "Safe GUI-over-Lisp device and Emacs automations."
  :group 'jetpacs)

(defcustom jetpacs-automation-recipes nil
  "Saved canonical automation recipes.
This is the sole persisted recipe authority; GUI views edit projections of
these inert Lisp data forms."
  :type '(repeat sexp)
  :group 'jetpacs-automations)

(defcustom jetpacs-automation-enabled-recipes nil
  "Recipe ids whose triggers should be active on the paired Companion.
Activation is intentionally separate from `jetpacs-automation-recipes': Save
never arms a trigger."
  :type '(repeat string)
  :group 'jetpacs-automations)

(defvar jetpacs-automation-data-directory
  (expand-file-name "jetpacs/automations/" user-emacs-directory)
  "Directory for the independent automation inbox, revisions, and history.")

(defconst jetpacs-automation-history-retention-ms (* 30 24 60 60 1000)
  "Run-history retention in milliseconds.")
(defconst jetpacs-automation-history-per-recipe 100
  "Maximum retained run-history rows per recipe.")
(defconst jetpacs-automation-work-lease-ms 120000
  "Automation worker lease duration in milliseconds.")

(defvar jetpacs-automation--store nil
  "Open automation work store, separate from endpoint receipts.")
(defvar jetpacs-automation--archive (make-hash-table :test #'equal)
  "Trigger id to frozen revision plist.")
(defvar jetpacs-automation--history nil
  "Newest-first redacted run history.")
(defvar jetpacs-automation--storage-loaded-p nil)
(defvar jetpacs-automation--dry-run-digests (make-hash-table :test #'equal)
  "Recipe id to the last successful current draft digest.")
(defvar jetpacs-automation--statuses (make-hash-table :test #'equal)
  "Recipe id to runtime status plist.")
(defvar jetpacs-automation--active-recipes (make-hash-table :test #'equal)
  "Recipe ids with an in-flight leased work item.")
(defvar jetpacs-automation--pump-timer nil)
(defvar jetpacs-automation--pump-running-p nil)
(defvar jetpacs-automation--notification-builders
  (make-hash-table :test #'equal)
  "Notification surface to immutable builder closure.")

;;;; Time, files, and frozen revision storage

(defun jetpacs-automation--now-ms ()
  "Return the current Unix time in integer milliseconds."
  (truncate (* 1000 (float-time))))

(defun jetpacs-automation--file (name)
  "Return NAME inside `jetpacs-automation-data-directory'."
  (expand-file-name name jetpacs-automation-data-directory))

(defun jetpacs-automation--json-read-file (file max-bytes)
  "Read bounded JSON FILE into plist/vector data, or nil when absent."
  (when (file-readable-p file)
    (when (> (file-attribute-size (file-attributes file)) max-bytes)
      (error "automation data file exceeds its bound"))
    (json-parse-string
     (with-temp-buffer
       (insert-file-contents file)
       (buffer-string))
     :object-type 'plist :array-type 'array
     :null-object nil :false-object :json-false)))

(defun jetpacs-automation--json-write-file (file value)
  "Atomically and durably replace FILE with JSON VALUE."
  (let* ((directory (file-name-directory file))
         (temporary (make-temp-file
                     (expand-file-name ".automation-write-" directory))))
    (unwind-protect
        (let ((write-region-inhibit-fsync nil)
              (text (json-serialize value :null-object nil
                                    :false-object :json-false)))
          (write-region text nil temporary nil 'silent)
          (rename-file temporary file t)
          (setq temporary nil))
      (when (and temporary (file-exists-p temporary))
        (delete-file temporary)))))

(defun jetpacs-automation--load-archive ()
  "Load and validate the frozen revision archive."
  (clrhash jetpacs-automation--archive)
  (seq-doseq
      (entry (or (jetpacs-automation--json-read-file
                  (jetpacs-automation--file "revisions.json") (* 8 1024 1024))
                 []))
    (let ((trigger-id (plist-get entry :trigger_id))
          (source (plist-get entry :recipe_source))
          (prefix (plist-get entry :device_prefix_count)))
      (when (and (stringp trigger-id) (stringp source)
                 (integerp prefix) (>= prefix 0))
        ;; Refuse corrupt revisions at load; never let a work item discover a
        ;; malformed frozen source only after it has been accepted.
        (jetpacs-automation-read-recipe source)
        (puthash trigger-id entry jetpacs-automation--archive)))))

(defun jetpacs-automation--save-archive ()
  "Durably save the frozen revision archive."
  (jetpacs-automation--json-write-file
   (jetpacs-automation--file "revisions.json")
   (vconcat (sort (hash-table-values jetpacs-automation--archive)
                  (lambda (a b)
                    (string< (plist-get a :trigger_id)
                             (plist-get b :trigger_id)))))))

(defun jetpacs-automation--archive-plan (plan)
  "Durably archive compiled PLAN before its trigger may be installed."
  (let* ((trigger-id (plist-get plan :trigger-id))
         (entry (list :trigger_id trigger-id
                      :recipe_id (plist-get plan :recipe-id)
                      :recipe_digest (plist-get plan :recipe-digest)
                      :execution_digest (plist-get plan :execution-digest)
                      :device_prefix_count
                      (plist-get plan :device-prefix-count)
                      :recipe_source
                      (jetpacs-automation-print-recipe
                       (plist-get plan :recipe)))))
    (unless (equal entry (gethash trigger-id jetpacs-automation--archive))
      (puthash trigger-id entry jetpacs-automation--archive)
      (jetpacs-automation--save-archive))
    entry))

(defun jetpacs-automation--load-history ()
  "Load bounded redacted run history."
  (setq jetpacs-automation--history
        (append (or (jetpacs-automation--json-read-file
                     (jetpacs-automation--file "history.json") (* 2 1024 1024))
                    []) nil)))

(defun jetpacs-automation--prune-history (now-ms)
  "Prune history by age and per-recipe count at NOW-MS."
  (let ((cutoff (- now-ms jetpacs-automation-history-retention-ms))
        (counts (make-hash-table :test #'equal))
        kept)
    (dolist (entry jetpacs-automation--history)
      (let* ((recipe-id (plist-get entry :recipe_id))
             (finished (or (plist-get entry :finished_at_ms) 0))
             (count (gethash recipe-id counts 0)))
        (when (and (>= finished cutoff)
                   (< count jetpacs-automation-history-per-recipe))
          (puthash recipe-id (1+ count) counts)
          (push entry kept))))
    (setq jetpacs-automation--history (nreverse kept))))

(defun jetpacs-automation--record-history
    (state status &optional error-kind path)
  "Record a redacted terminal STATUS for work STATE."
  (let ((now (jetpacs-automation--now-ms)))
    (push (append
           (list :recipe_id (plist-get state :recipe_id)
                 :run_id (plist-get state :run_id)
                 :started_at_ms (plist-get state :started_at_ms)
                 :finished_at_ms now :status status)
           (when path (list :path (truncate-string-to-width path 160)))
           (when error-kind
             (list :error_kind (truncate-string-to-width error-kind 96))))
          jetpacs-automation--history)
    (jetpacs-automation--prune-history now)
    (jetpacs-automation--json-write-file
     (jetpacs-automation--file "history.json")
     (vconcat jetpacs-automation--history))))

(defun jetpacs-automation--ensure-storage ()
  "Open the independent work store and load auxiliary durable state."
  (unless jetpacs-automation--storage-loaded-p
    (make-directory jetpacs-automation-data-directory t)
    (setq jetpacs-automation--store
          (ebp-sqlite-open (jetpacs-automation--file "work.sqlite")))
    (jetpacs-automation--load-archive)
    (jetpacs-automation--load-history)
    (setq jetpacs-automation--storage-loaded-p t))
  jetpacs-automation--store)

(defun jetpacs-automation-runtime-reset ()
  "Close runtime resources and clear process-local automation state.
This is primarily useful to tests and live reloading; durable files remain."
  (when jetpacs-automation--store
    (ignore-errors (ebp-store-close jetpacs-automation--store)))
  (when (timerp jetpacs-automation--pump-timer)
    (cancel-timer jetpacs-automation--pump-timer))
  (setq jetpacs-automation--store nil
        jetpacs-automation--storage-loaded-p nil
        jetpacs-automation--pump-timer nil
        jetpacs-automation--pump-running-p nil
        jetpacs-automation--history nil)
  (clrhash jetpacs-automation--archive)
  (clrhash jetpacs-automation--active-recipes)
  (clrhash jetpacs-automation--statuses)
  t)

;;;; Recipe lifecycle

(defun jetpacs-automation-recipe (recipe-id)
  "Return saved normalized RECIPE-ID, or nil."
  (cl-find recipe-id jetpacs-automation-recipes
           :key (lambda (recipe)
                  (condition-case nil
                      (plist-get (jetpacs-automation-normalize-recipe recipe) :id)
                    (error nil)))
           :test #'equal))

(defun jetpacs-automation-recipes-normalized ()
  "Return all saved recipes normalized and sorted by display name."
  (sort (mapcar #'jetpacs-automation-normalize-recipe
                jetpacs-automation-recipes)
        (lambda (a b) (string< (plist-get a :name) (plist-get b :name)))))

(defun jetpacs-automation-run-dry-run (recipe &optional trigger-data)
  "Dry-run RECIPE and remember proof for its exact current digest."
  (let ((result (jetpacs-automation-dry-run recipe trigger-data
                                             '(:source "dry-run"))))
    (when (plist-get result :ok)
      (let ((normalized (jetpacs-automation-normalize-recipe recipe)))
        (puthash (plist-get normalized :id) (plist-get result :digest)
                 jetpacs-automation--dry-run-digests)))
    result))

(defun jetpacs-automation--save-setting (symbol value)
  "Persist SYMBOL as VALUE through the Customize settings seam."
  (jetpacs-settings-save-variable symbol value)
  value)

(defun jetpacs-automation-save-recipe (recipe dry-run-digest)
  "Persist normalized RECIPE after proof by exact DRY-RUN-DIGEST.
An execution-relevant replacement is disabled before the new revision is
saved.  Return the saved canonical recipe."
  (let* ((normalized (jetpacs-automation-normalize-recipe recipe))
         (id (plist-get normalized :id))
         (digest (jetpacs-automation-recipe-digest normalized))
         (proved (gethash id jetpacs-automation--dry-run-digests))
         (old (jetpacs-automation-recipe id)))
    (unless (and (stringp dry-run-digest)
                 (equal dry-run-digest digest)
                 (equal proved digest))
      (error "automation save requires a Dry Run of the current draft"))
    (when (and old (member id jetpacs-automation-enabled-recipes)
               (not (equal (jetpacs-automation-execution-digest old)
                           (jetpacs-automation-execution-digest normalized))))
      (jetpacs-automation-disable id))
    (let ((next (cons normalized
                      (cl-remove id jetpacs-automation-recipes
                                 :key (lambda (item)
                                        (ignore-errors
                                          (plist-get
                                           (jetpacs-automation-normalize-recipe item)
                                           :id)))
                                 :test #'equal))))
      (jetpacs-automation--save-setting 'jetpacs-automation-recipes next))
    (puthash id (list :state (if (member id jetpacs-automation-enabled-recipes)
                                 "enabled" "disabled"))
             jetpacs-automation--statuses)
    normalized))

(defun jetpacs-automation-delete-recipe (recipe-id)
  "Disable and delete saved RECIPE-ID."
  (when (member recipe-id jetpacs-automation-enabled-recipes)
    (jetpacs-automation-disable recipe-id))
  (jetpacs-automation--save-setting
   'jetpacs-automation-recipes
   (cl-remove recipe-id jetpacs-automation-recipes
              :key (lambda (recipe)
                     (ignore-errors
                       (plist-get (jetpacs-automation-normalize-recipe recipe) :id)))
              :test #'equal))
  (remhash recipe-id jetpacs-automation--dry-run-digests)
  (puthash recipe-id '(:state "deleted") jetpacs-automation--statuses)
  (jetpacs-automation-reconcile)
  t)

(defun jetpacs-automation-enable (recipe-id)
  "Enable saved RECIPE-ID after a current-session Dry Run.
When disconnected, activation is persisted as pending and reconciled at the
next READY session."
  (let* ((recipe (or (jetpacs-automation-recipe recipe-id)
                     (error "unknown automation recipe %s" recipe-id)))
         (normalized (jetpacs-automation-normalize-recipe recipe))
         (digest (jetpacs-automation-recipe-digest normalized)))
    (unless (equal digest (gethash recipe-id
                                   jetpacs-automation--dry-run-digests))
      (error "automation enable requires a Dry Run of the saved revision"))
    (when (jetpacs-connected-p)
      (let ((plan (jetpacs-automation-compile
                   normalized (jetpacs-automation-device-profile
                               (jetpacs-client)))))
        (unless (plist-get plan :supported)
          (signal 'jetpacs-automation-unsupported
                  (list (mapconcat #'identity (plist-get plan :missing) ", "))))
        ;; The revision becomes durable before an install can race a fire.
        (jetpacs-automation--ensure-storage)
        (jetpacs-automation--archive-plan plan)))
    (jetpacs-automation--save-setting
     'jetpacs-automation-enabled-recipes
     (delete-dups (append jetpacs-automation-enabled-recipes (list recipe-id))))
    (puthash recipe-id '(:state "pending") jetpacs-automation--statuses)
    (jetpacs-automation-reconcile)
    t))

(defun jetpacs-automation-disable (recipe-id)
  "Persistently disable RECIPE-ID and reconcile the device set when READY."
  (jetpacs-automation--save-setting
   'jetpacs-automation-enabled-recipes
   (delete recipe-id (copy-sequence jetpacs-automation-enabled-recipes)))
  (puthash recipe-id (list :state (if (jetpacs-connected-p)
                                     "disabling" "pending-disable"))
           jetpacs-automation--statuses)
  (jetpacs-automation-reconcile)
  t)

(defun jetpacs-automation-status (recipe-id)
  "Return runtime status plist for RECIPE-ID."
  (or (copy-tree (gethash recipe-id jetpacs-automation--statuses))
      (list :state (if (member recipe-id jetpacs-automation-enabled-recipes)
                       "pending" "disabled"))))

(defun jetpacs-automation-history (&optional recipe-id)
  "Return redacted history, optionally limited to RECIPE-ID."
  (jetpacs-automation--ensure-storage)
  (copy-tree
   (if recipe-id
       (cl-remove recipe-id jetpacs-automation--history
                  :key (lambda (entry) (plist-get entry :recipe_id))
                  :test-not #'equal)
     jetpacs-automation--history)))

;;;; Capability profile and trigger reconciliation

(defun jetpacs-automation-device-profile (&optional client)
  "Return automation compiler profile for CLIENT or the live client."
  (let* ((client (or client (jetpacs-client)))
         (device (and client (ebp-client-device client))))
    (list :trigger-types (and client (ebp-client-device-trigger-types client))
          :state-types (and client (ebp-client-device-state-types client))
          :trackable-state-types
          (append (plist-get device :trackable_state_types) nil)
          :caps (and client (ebp-client-device-caps client))
          :trigger-caps (append (plist-get device :trigger_caps) nil)
          :max-trigger-responses
          (plist-get (and client (ebp-client-limits client))
                     :max_trigger_responses))))

(defun jetpacs-automation-reconcile (&optional client)
  "Atomically reconcile all enabled recipes to CLIENT's trigger set.
Unavailable portable recipes are omitted from the replacement set and marked
unsupported; their conditions and actions are never weakened."
  (let ((client (or client (and (jetpacs-connected-p) (jetpacs-client)))))
    (when (and client (eq (ebp-client-state client) 'ready))
      (condition-case err
          (let ((profile (jetpacs-automation-device-profile client))
                plans wires)
            (jetpacs-automation--ensure-storage)
            (dolist (id jetpacs-automation-enabled-recipes)
              (if-let* ((recipe (jetpacs-automation-recipe id)))
                  (let ((plan (jetpacs-automation-compile recipe profile)))
                    (if (plist-get plan :supported)
                        (progn
                          (jetpacs-automation--archive-plan plan)
                          (push plan plans)
                          (push (plist-get plan :wire-trigger) wires)
                          (puthash id '(:state "installing")
                                   jetpacs-automation--statuses))
                      (puthash id (list :state "unsupported"
                                        :missing (plist-get plan :missing))
                               jetpacs-automation--statuses)))
                (puthash id '(:state "missing") jetpacs-automation--statuses)))
            (ebp-client-triggers-set
             client (vconcat (nreverse wires))
             :omitted-function
             (lambda (omitted)
               (dolist (wire omitted)
                 (when-let* ((plan
                              (cl-find (plist-get wire :id) plans
                                       :key (lambda (item)
                                              (plist-get item :trigger-id))
                                       :test #'equal)))
                   (puthash (plist-get plan :recipe-id)
                            '(:state "unsupported" :missing ("state-gate"))
                            jetpacs-automation--statuses))))
             :callback
             (lambda (_count error)
               (dolist (plan plans)
                 (puthash (plist-get plan :recipe-id)
                          (if error
                              '(:state "pending" :error-kind "install-failed")
                            (list :state "enabled"
                                  :trigger-id (plist-get plan :trigger-id)
                                  :device-prefix-count
                                  (plist-get plan :device-prefix-count)))
                          jetpacs-automation--statuses))
               (unless error
                 (maphash
                  (lambda (id status)
                    (when (and (not (member id
                                           jetpacs-automation-enabled-recipes))
                               (member (plist-get status :state)
                                       '("disabling" "pending-disable")))
                      (puthash id '(:state "disabled")
                               jetpacs-automation--statuses)))
                  jetpacs-automation--statuses))))
            t)
        (error
         (dolist (id jetpacs-automation-enabled-recipes)
           (puthash id (list :state "pending" :error-kind
                             (symbol-name (car err)))
                    jetpacs-automation--statuses))
         nil)))))

;;;; Durable event admission

(defun jetpacs-automation--safe-source (value path)
  "Return inert wire VALUE at PATH as bounded canonical Lisp source."
  (jetpacs-authoring-print
   (jetpacs-automation--literal value path)
   jetpacs-automation-max-recipe-bytes))

(defun jetpacs-automation--work-state-encode (state)
  "Serialize scalar/source-string work STATE to JSON."
  (json-serialize state :null-object nil :false-object :json-false))

(defun jetpacs-automation--work-state-decode (json)
  "Decode and minimally validate automation work JSON."
  (let ((state (json-parse-string json :object-type 'plist :array-type 'array
                                  :null-object nil :false-object :json-false)))
    (unless (and (= (plist-get state :version) 1)
                 (stringp (plist-get state :recipe_id))
                 (stringp (plist-get state :recipe_source))
                 (stringp (plist-get state :pending_source))
                 (stringp (plist-get state :trigger_source))
                 (stringp (plist-get state :run_source))
                 (stringp (plist-get state :outputs_source)))
      (error "invalid automation work payload"))
    state))

(defun jetpacs-automation--trigger-fired (args params)
  "Durably admit one trigger.fired ARGS/PARAMS event."
  (let* ((trigger-id (plist-get args :id))
         (revision (and (stringp trigger-id)
                        (gethash trigger-id jetpacs-automation--archive)))
         (client (jetpacs-client))
         (pairing-id (and client
                          (plist-get (ebp-client-config client) :pairing-id)))
         (event-id (plist-get params :event_id)))
    (unless revision
      (cl-return-from jetpacs-automation--trigger-fired 'stale))
    (condition-case _err
        (let* ((store (jetpacs-automation--ensure-storage))
               (recipe (jetpacs-automation-read-recipe
                        (plist-get revision :recipe_source)))
               (prefix (plist-get revision :device_prefix_count))
               (steps (plist-get recipe :steps))
               (pending (cl-subseq steps (min prefix (length steps))))
               (now (jetpacs-automation--now-ms))
               (state
                (list :version 1 :recipe_id (plist-get recipe :id)
                      :run_id event-id :started_at_ms now
                      :recipe_source (plist-get revision :recipe_source)
                      :pending_source
                      (jetpacs-authoring-print pending
                                               jetpacs-automation-max-recipe-bytes)
                      :trigger_source
                      (jetpacs-automation--safe-source
                       (or (plist-get args :data) nil) "trigger.data")
                      :run_source
                      (jetpacs-automation--safe-source
                       (list :id event-id
                             :occurred-at-ms (plist-get params :occurred_at_ms)
                             :trigger-id trigger-id)
                       "run")
                      :outputs_source "[]" :path "steps")))
          (unless (and (stringp pairing-id) (stringp event-id))
            (error "missing automation durable identity"))
          (ebp-store-admit store pairing-id event-id "automation.run"
                           (jetpacs-automation--work-state-encode state) now)
          (jetpacs-automation--schedule-pump)
          'accepted)
      (error 'rejected))))

;;;; Recoverable worker

(defun jetpacs-automation--read-work-source (source max-bytes)
  "Read one inert work-state SOURCE bounded by MAX-BYTES."
  (jetpacs-authoring-read-one source max-bytes))

(defun jetpacs-automation--state-environment (state recipe)
  "Build evaluator environment from frozen work STATE and RECIPE."
  (let* ((trigger-data
          (jetpacs-automation--read-work-source
           (plist-get state :trigger_source) jetpacs-automation-max-recipe-bytes))
         (run
          (jetpacs-automation--read-work-source
           (plist-get state :run_source) jetpacs-automation-max-recipe-bytes))
         (rows
          (jetpacs-automation--read-work-source
           (plist-get state :outputs_source) jetpacs-automation-max-recipe-bytes))
         outputs)
    (unless (vectorp rows) (error "invalid automation output cursor"))
    (seq-doseq (row rows)
      (push (cons (jetpacs-automation--plist-fetch row :id)
                  (jetpacs-automation--plist-fetch row :value))
            outputs))
    (jetpacs-automation-build-environment recipe trigger-data run outputs)))

(defun jetpacs-automation--state-pending (state)
  "Return STATE's pending step vector."
  (let ((pending (jetpacs-automation--read-work-source
                  (plist-get state :pending_source)
                  jetpacs-automation-max-recipe-bytes)))
    (unless (vectorp pending) (error "invalid automation step cursor"))
    pending))

(defun jetpacs-automation--state-outputs (state)
  "Return STATE's output-row vector."
  (let ((outputs (jetpacs-automation--read-work-source
                  (plist-get state :outputs_source)
                  jetpacs-automation-max-recipe-bytes)))
    (unless (vectorp outputs) (error "invalid automation outputs"))
    outputs))

(defun jetpacs-automation--checkpoint
    (work state pending outputs &optional path)
  "Checkpoint WORK with STATE, PENDING steps, OUTPUTS, and PATH."
  (setq state (plist-put state :pending_source
                         (jetpacs-authoring-print
                          pending jetpacs-automation-max-recipe-bytes)))
  (setq state (plist-put state :outputs_source
                         (jetpacs-authoring-print
                          outputs jetpacs-automation-max-recipe-bytes)))
  (when path (setq state (plist-put state :path path)))
  (cons (ebp-store-checkpoint
         jetpacs-automation--store work
         (jetpacs-automation--work-state-encode state)
         (jetpacs-automation--now-ms))
        state))

(defun jetpacs-automation--release-active (recipe-id)
  "Release RECIPE-ID's per-recipe FIFO lock and resume pumping."
  (remhash recipe-id jetpacs-automation--active-recipes)
  (jetpacs-automation--schedule-pump))

(defun jetpacs-automation--block-work (work state kind &optional path)
  "Durably block WORK with redacted KIND/PATH and release its recipe."
  (condition-case nil
      (progn
        (ebp-store-block jetpacs-automation--store work
                         (jetpacs-automation--now-ms) kind)
        (jetpacs-automation--record-history state "failed" kind path))
    (error nil))
  (puthash (plist-get state :recipe_id)
           (list :state "error" :error-kind kind)
           jetpacs-automation--statuses)
  (jetpacs-automation--release-active (plist-get state :recipe_id)))

(defun jetpacs-automation--complete-work (work state)
  "Complete WORK, erase its operational payload, and record history."
  (ebp-store-complete jetpacs-automation--store work
                      (jetpacs-automation--now-ms))
  (jetpacs-automation--record-history state "success" nil
                                       (plist-get state :path))
  (jetpacs-automation--release-active (plist-get state :recipe_id)))

(defun jetpacs-automation--notification-root (surface)
  "Build the notification node registered for SURFACE."
  (funcall (or (gethash surface jetpacs-automation--notification-builders)
               (lambda ()
                 (jetpacs-notification-surface
                  (jetpacs-text "Automation notification" :style "body")
                  :meta '(:channel "automations"))))))

(defun jetpacs-automation--post-notification (args recipe-id)
  "Publish connected notification ARGS on RECIPE-ID's stable surface."
  (unless (and (jetpacs-connected-p)
               (jetpacs-granted-p "surfaces.notification"))
    (error "notification surface is unavailable"))
  (let* ((text (jetpacs-automation--plist-fetch args :text))
         (title (jetpacs-automation--plist-fetch args :title))
         (surface (format "notification:automation-%s"
                          (substring (secure-hash 'sha256 recipe-id) 0 16))))
    (unless (stringp text) (error "device.notify requires text"))
    (puthash
     surface
     (lambda ()
       (jetpacs-notification-surface
        (if (stringp title)
            (jetpacs-column (jetpacs-text title :style "title")
                            (jetpacs-text text :style "body") :spacing 4)
          (jetpacs-text text :style "body"))
        :meta '(:channel "automations")))
     jetpacs-automation--notification-builders)
    (jetpacs-shell-define-root
     surface (lambda () (jetpacs-automation--notification-root surface)))
    (jetpacs-shell-push surface)
    nil))

(defun jetpacs-automation--host-execute (descriptor args state)
  "Synchronously execute host action DESCRIPTOR with ARGS and STATE."
  (pcase (plist-get descriptor :host-executor)
    ('message
     (let ((text (jetpacs-automation--plist-fetch args :text)))
       (unless (stringp text) (error "emacs.message requires text"))
       (message "%s" text)
       nil))
    ('theme-load
     (let* ((name (jetpacs-automation--plist-fetch args :theme))
            (theme (and (stringp name)
                        (cl-find name (custom-available-themes)
                                 :key #'symbol-name :test #'equal))))
       (unless theme (error "requested theme is unavailable"))
       (load-theme theme t)
       nil))
    (_ (error "host executor is unavailable for %s"
              (plist-get state :path)))))

(defun jetpacs-automation--continue-after-action
    (work state pending outputs step-id output path)
  "Checkpoint action OUTPUT and continue WORK after STEP-ID at PATH."
  (let* ((row (list :id step-id :value
                    (jetpacs-automation--literal output "step.output")))
         (next-outputs (vconcat outputs (vector row)))
         (checkpoint (jetpacs-automation--checkpoint
                      work state pending next-outputs path)))
    (jetpacs-automation--process-work (car checkpoint) (cdr checkpoint))))

(defun jetpacs-automation--process-action
    (work state recipe pending outputs step path)
  "Execute one action STEP and continue durable WORK."
  (let* ((action (jetpacs-automation--plist-fetch step :action))
         (descriptor (jetpacs-automation-action-descriptor action))
         (environment (jetpacs-automation--state-environment state recipe))
         (args (jetpacs-automation-resolve-args
                (jetpacs-automation--plist-fetch step :args) environment))
         (step-id (jetpacs-automation--plist-fetch step :id)))
    (unless descriptor (error "automation action is no longer registered"))
    (jetpacs-automation-validate-action-args
     action args nil (concat path ".args"))
    (pcase (plist-get descriptor :executor)
      ('host
       (jetpacs-automation--continue-after-action
        work state pending outputs step-id
        (jetpacs-automation--host-execute descriptor args state) path))
      ('device
       (if (plist-get descriptor :notify)
           (jetpacs-automation--continue-after-action
            work state pending outputs step-id
            (jetpacs-automation--post-notification
             args (plist-get state :recipe_id)) path)
         (if (not (jetpacs-connected-p))
             (progn
               (ebp-store-retry jetpacs-automation--store work
                                (jetpacs-automation--now-ms)
                                (+ (jetpacs-automation--now-ms) 1000)
                                "waiting-session")
               (jetpacs-automation--release-active
                (plist-get state :recipe_id)))
           ;; Capability calls are session-scoped.  Once sent, any error or
           ;; indeterminate disconnect stops the run and is never auto-retried.
           (ebp-client-capability-invoke
            (jetpacs-client) (plist-get descriptor :cap)
            :args (jetpacs-automation--wire-data args)
            :callback
            (lambda (result error)
              (condition-case callback-error
                  (if error
                      (jetpacs-automation--block-work
                       work state "capability-failed" path)
                    (jetpacs-automation--continue-after-action
                     work state pending outputs step-id result path))
                (error
                 (jetpacs-automation--block-work
                  work state (symbol-name (car callback-error)) path))))))))
      (_ (error "unknown automation executor")))))

(defun jetpacs-automation--process-work (work state)
  "Run leased WORK from decoded checkpoint STATE until async or terminal."
  (condition-case err
      (let* ((recipe (jetpacs-automation-read-recipe
                      (plist-get state :recipe_source)))
             (pending (jetpacs-automation--state-pending state))
             (outputs (jetpacs-automation--state-outputs state)))
        (if (= (length pending) 0)
            (jetpacs-automation--complete-work work state)
          (let* ((step (aref pending 0))
                 (rest (cl-subseq pending 1))
                 (kind (jetpacs-automation--plist-fetch step :kind))
                 (step-id (jetpacs-automation--plist-fetch step :id))
                 (path (format "%s/%s" (plist-get state :path) step-id)))
            (if (equal (jetpacs-automation--symbol-name kind) ":if")
                (let* ((environment
                        (jetpacs-automation--state-environment state recipe))
                       (choice
                        (jetpacs-automation-eval-expression
                         (jetpacs-automation--plist-fetch step :condition)
                         environment))
                       (branch (jetpacs-automation--plist-fetch
                                step (if choice :then :else)))
                       (next (vconcat branch rest))
                       (checkpoint
                        (jetpacs-automation--checkpoint
                         work state next outputs
                         (concat path (if choice "/then" "/else")))))
                  (jetpacs-automation--process-work
                   (car checkpoint) (cdr checkpoint)))
              (unless (equal (jetpacs-automation--symbol-name kind) ":action")
                (error "unknown durable step kind"))
              (jetpacs-automation--process-action
               work state recipe rest outputs step path)))))
    (error
     (jetpacs-automation--block-work
      work state (symbol-name (car err)) (plist-get state :path)))))

(defun jetpacs-automation--schedule-pump ()
  "Schedule a near-term durable inbox pump."
  (unless (timerp jetpacs-automation--pump-timer)
    (setq jetpacs-automation--pump-timer
          (run-at-time 0.05 nil #'jetpacs-automation-pump))))

(defun jetpacs-automation-pump ()
  "Claim and progress runnable work, preserving per-recipe FIFO execution."
  (setq jetpacs-automation--pump-timer nil)
  (unless jetpacs-automation--pump-running-p
    (let ((jetpacs-automation--pump-running-p t)
          (store (condition-case nil (jetpacs-automation--ensure-storage)
                   (error nil)))
          (remaining 32)
          stop)
      (while (and store (> remaining 0) (not stop))
        (cl-decf remaining)
        (let ((work (condition-case nil
                        (ebp-store-claim-next
                         store "jetpacs-automations"
                         (jetpacs-automation--now-ms)
                         jetpacs-automation-work-lease-ms)
                      (error nil))))
          (if (not work)
              (setq stop t)
            (condition-case err
                (let* ((state (jetpacs-automation--work-state-decode
                               (ebp-store-work-payload-json work)))
                       (recipe-id (plist-get state :recipe_id)))
                  (if (gethash recipe-id jetpacs-automation--active-recipes)
                      (ebp-store-retry
                       store work (jetpacs-automation--now-ms)
                       (+ (jetpacs-automation--now-ms) 100) nil)
                    (puthash recipe-id t jetpacs-automation--active-recipes)
                    (jetpacs-automation--process-work work state)))
              (error
               (ignore-errors
                 (ebp-store-block store work (jetpacs-automation--now-ms)
                                  (symbol-name (car err)))))))))))
  t)

;;;; Session wiring

(defun jetpacs-automation--on-ready (client)
  "Reconcile recipes and resume accepted work for READY CLIENT."
  (jetpacs-automation--ensure-storage)
  (jetpacs-automation-reconcile client)
  (jetpacs-automation--schedule-pump))

(defun jetpacs-automation-runtime-start ()
  "Install the durable trigger handler and READY lifecycle hook."
  (jetpacs-defaction "trigger.fired" #'jetpacs-automation--trigger-fired)
  (add-hook 'jetpacs-ready-functions #'jetpacs-automation--on-ready 20)
  t)

(jetpacs-automation-runtime-start)

(provide 'jetpacs-automation-runtime)
;;; jetpacs-automation-runtime.el ends here
