;;; jetpacs-automations.el --- GUI-over-Lisp automation app -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A separate Jetpacs app presenting one automation draft as an Inspector,
;; ordered Tree, or canonical Lisp data.  Every structured mutation replaces
;; the same normalized recipe; a valid Lisp save replaces that recipe, while an
;; invalid source remains isolated in the editor and cannot overwrite it.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'jetpacs-apps)
(require 'jetpacs-automation-model)
(require 'jetpacs-automation-runtime)
(require 'jetpacs-chrome)
(require 'jetpacs-surfaces)
(require 'jetpacs-widgets)

(defconst jetpacs-automations-owner "jetpacs.automations"
  "Owner, app id, and primary surface of Automations.")
(defconst jetpacs-automations-title "Automations"
  "User-facing app title.")

(defvar jetpacs-automations--drafts (make-hash-table :test #'equal)
  "Editor key to draft state plist.")
(defvar jetpacs-automations--next-recipe 1
  "Monotonic suffix used for process-local new recipe ids.")

;;;; Drafts and immutable edits

(defun jetpacs-automations--plist-delete (plist key)
  "Return PLIST without KEY."
  (let (result)
    (cl-loop for (current value) on plist by #'cddr
             unless (eq current key)
             do (setq result (append result (list current value))))
    result))

(defun jetpacs-automations--default-trigger-params (type)
  "Return portable initial params for trigger TYPE."
  (pcase type
    ("time" '(:every-s 3600))
    ("battery.level" '(:below 20))
    ("power" '(:state "connected"))
    ("screen" '(:state "on"))
    ("state.edge" '(:when [(:type "battery.level" :below 20)]
                    :edge "rise"))
    (_ nil)))

(defun jetpacs-automations--new-id ()
  "Return an unused user recipe id."
  (let (candidate)
    (while
        (progn
          (setq candidate (format "user.automation-%d"
                                  jetpacs-automations--next-recipe)
                jetpacs-automations--next-recipe
                (1+ jetpacs-automations--next-recipe))
          (or (jetpacs-automation-recipe candidate)
              (gethash candidate jetpacs-automations--drafts))))
    candidate))

(defun jetpacs-automations--new-recipe ()
  "Return one valid disabled starter recipe."
  (let ((id (jetpacs-automations--new-id)))
    (jetpacs-automation-normalize-recipe
     (list :schema-version 1 :id id :name "Untitled automation" :inputs []
           :trigger (list :type "time" :params '(:every-s 3600)
                          :when [] :policy :queue :ttl-s 86400)
           :steps []))))

(defun jetpacs-automations--make-draft (recipe)
  "Return initial editor state for normalized RECIPE."
  (list :recipe (jetpacs-automation-normalize-recipe recipe)
        :mode "inspector" :dry-run nil :error nil :lisp-buffer nil))

(defun jetpacs-automations--draft (key)
  "Return draft KEY, seeding it from saved recipes when necessary."
  (or (gethash key jetpacs-automations--drafts)
      (when-let* ((recipe (jetpacs-automation-recipe key)))
        (let ((draft (jetpacs-automations--make-draft recipe)))
          (puthash key draft jetpacs-automations--drafts)
          draft))))

(defun jetpacs-automations--recipe (key)
  "Return KEY's current canonical draft recipe."
  (plist-get (or (jetpacs-automations--draft key)
                 (error "unknown automation draft %s" key))
             :recipe))

(defun jetpacs-automations--error-text (err)
  "Return bounded user-facing text for ERR without authored source."
  (truncate-string-to-width (error-message-string err) 240 nil nil t))

(defun jetpacs-automations--set-error (key err)
  "Retain draft KEY and attach bounded ERR."
  (let ((draft (jetpacs-automations--draft key)))
    (setq draft (plist-put draft :error (jetpacs-automations--error-text err)))
    (puthash key draft jetpacs-automations--drafts)))

(defun jetpacs-automations--replace-recipe (key recipe)
  "Replace KEY's recipe with normalized RECIPE and invalidate proof."
  (let* ((draft (jetpacs-automations--draft key))
         (normalized (jetpacs-automation-normalize-recipe recipe)))
    ;; Navigation actions address the stable editor key.  Letting raw Lisp
    ;; change :id would strand the open screen under a different identity.
    (unless (equal (plist-get normalized :id)
                   (plist-get (plist-get draft :recipe) :id))
      (error "recipe :id is immutable in an open editor"))
    (setq draft (plist-put draft :recipe normalized))
    (setq draft (plist-put draft :dry-run nil))
    (setq draft (plist-put draft :error nil))
    (setq draft (plist-put draft :lisp-buffer nil))
    (puthash key draft jetpacs-automations--drafts)
    normalized))

(defun jetpacs-automations--refresh (params)
  "Defer a safe refresh for action PARAMS."
  (jetpacs-app-defer-refresh params))

(defun jetpacs-automations--with-edit (key params function)
  "Apply FUNCTION to KEY's copied recipe and refresh PARAMS.
Errors remain on the draft and the last valid recipe is untouched."
  (condition-case err
      (jetpacs-automations--replace-recipe
       key (funcall function (copy-tree (jetpacs-automations--recipe key) t)))
    (error (jetpacs-automations--set-error key err)))
  (jetpacs-automations--refresh params)
  'accepted)

(defun jetpacs-automations--input-index (inputs name)
  "Return NAME's index in INPUTS, or nil."
  (cl-position name inputs :key (lambda (input) (plist-get input :name))
               :test #'equal))

(defun jetpacs-automations--step-ids (steps)
  "Return every id recursively contained in STEPS."
  (let (ids)
    (cl-labels ((walk (items)
                  (seq-doseq (step items)
                    (push (plist-get step :id) ids)
                    (when (eq (plist-get step :kind) :if)
                      (walk (plist-get step :then))
                      (walk (plist-get step :else))))))
      (walk steps))
    ids))

(defun jetpacs-automations--new-step-id (steps)
  "Return a unique id under STEPS."
  (let ((ids (jetpacs-automations--step-ids steps)) (n 1) candidate)
    (while (progn (setq candidate (format "step-%d" n) n (1+ n))
                  (member candidate ids)))
    candidate))

(defun jetpacs-automations--default-args (action)
  "Return useful starter args for ACTION."
  (pcase action
    ("device.notify" '(:text "Hello from Jetpacs"))
    ("device.vibrate" '(:ms 250))
    ("device.volume.set" '(:stream "music" :level 5))
    ("device.tts.speak" '(:text "Automation ran"))
    ("device.ringer.mode" '(:mode "normal"))
    ("device.flashlight" '(:on t))
    ("device.media.key" '(:key "play_pause"))
    ("device.screen.keep_on" '(:on t))
    ("device.brightness.set" '(:level 128))
    ("device.dnd.set" '(:mode "priority"))
    ("emacs.message" '(:text "Automation ran"))
    ("emacs.theme.load" '(:theme "modus-operandi"))
    (_ nil)))

(defun jetpacs-automations--map-step (steps id function)
  "Return STEPS with ID replaced by FUNCTION's result."
  (apply
   #'vector
   (cl-loop for step across steps
            collect
            (cond
             ((equal (plist-get step :id) id) (funcall function step))
             ((eq (plist-get step :kind) :if)
              (let ((copy (copy-tree step t)))
                (setq copy (plist-put copy :then
                                      (jetpacs-automations--map-step
                                       (plist-get copy :then) id function)))
                (setq copy (plist-put copy :else
                                      (jetpacs-automations--map-step
                                       (plist-get copy :else) id function)))
                copy))
             (t step)))))

(defun jetpacs-automations--delete-step (steps id)
  "Return STEPS without ID at any nesting level."
  (apply
   #'vector
   (cl-loop for step across steps
            unless (equal (plist-get step :id) id)
            collect
            (if (eq (plist-get step :kind) :if)
                (let ((copy (copy-tree step t)))
                  (setq copy (plist-put copy :then
                                        (jetpacs-automations--delete-step
                                         (plist-get copy :then) id)))
                  (setq copy (plist-put copy :else
                                        (jetpacs-automations--delete-step
                                         (plist-get copy :else) id)))
                  copy)
              step))))

(defun jetpacs-automations--move-sibling (steps id delta)
  "Move ID by DELTA within its sibling vector STEPS.
Return (CHANGED . VECTOR), recursively searching branches."
  (let* ((items (append steps nil))
         (index (cl-position id items :key (lambda (step) (plist-get step :id))
                             :test #'equal)))
    (if index
        (let ((target (max 0 (min (1- (length items)) (+ index delta)))))
          (when (/= index target)
            (let ((item (nth index items)))
              (setq items (delete item items))
              (setq items (append (cl-subseq items 0 target) (list item)
                                  (cl-subseq items target)))))
          (cons (/= index target) (vconcat items)))
      (let ((changed nil) result)
        (seq-doseq (step steps)
          (let ((copy step))
            (when (and (not changed) (eq (plist-get step :kind) :if))
              (let ((then (jetpacs-automations--move-sibling
                           (plist-get step :then) id delta)))
                (if (car then)
                    (progn (setq copy (copy-tree step t))
                           (setq copy (plist-put copy :then (cdr then)))
                           (setq changed t))
                  (let ((else (jetpacs-automations--move-sibling
                               (plist-get step :else) id delta)))
                    (when (car else)
                      (setq copy (copy-tree step t))
                      (setq copy (plist-put copy :else (cdr else)))
                      (setq changed t))))))
            (push copy result)))
        (cons changed (vconcat (nreverse result)))))))

;;;; Action handlers

(defun jetpacs-automations--open-detail (key surface)
  "Open draft KEY on SURFACE outside the current dispatch extent."
  (jetpacs-flow-continue
   (lambda ()
     (condition-case err
         (jetpacs-chrome-push-screen
          surface
          (format "automation-%s" (substring (secure-hash 'sha256 key) 0 12))
          (lambda (back) (jetpacs-automations--detail-screen key back)))
       (error
        (message "jetpacs-automations: open failed: %s"
                 (jetpacs-error-label err)))))))

(defun jetpacs-automations--on-open (args params)
  "Open saved recipe from ARGS."
  (let ((id (plist-get args :id)) (surface (plist-get params :surface)))
    (if (not (and (stringp id) (jetpacs-automation-recipe id)))
        'stale
      (jetpacs-automations--draft id)
      (jetpacs-automations--open-detail id surface)
      'accepted)))

(defun jetpacs-automations--on-new (_args params)
  "Create a process-local disabled draft and open it."
  (let* ((recipe (jetpacs-automations--new-recipe))
         (key (plist-get recipe :id)))
    (puthash key (jetpacs-automations--make-draft recipe)
             jetpacs-automations--drafts)
    (jetpacs-automations--open-detail key (plist-get params :surface))
    'accepted))

(defun jetpacs-automations--on-mode (args params)
  "Select Inspector, Tree, or Lisp mode from ARGS."
  (let ((key (plist-get args :id)) (mode (plist-get args :mode)))
    (if (not (and (jetpacs-automations--draft key)
                  (member mode '("inspector" "tree" "lisp"))))
        'stale
      (let ((draft (jetpacs-automations--draft key)))
        (setq draft (plist-put draft :mode mode))
        (puthash key draft jetpacs-automations--drafts))
      (jetpacs-automations--refresh params)
      'accepted)))

(defun jetpacs-automations--on-field (args params)
  "Apply one top-level Inspector field from ARGS."
  (let ((key (plist-get args :id)) (field (plist-get args :field))
        (value (plist-get args :value)))
    (if (not (and (stringp key) (stringp field) (stringp value)))
        'rejected
      (jetpacs-automations--with-edit
       key params
       (lambda (recipe)
         (pcase field
           ("name" (plist-put recipe :name value))
           ("trigger-type"
            (let ((trigger (copy-tree (plist-get recipe :trigger) t)))
              (setq trigger (plist-put trigger :type value))
              (setq trigger (plist-put trigger :params
                                       (jetpacs-automations--default-trigger-params
                                        value)))
              (plist-put recipe :trigger trigger)))
           ("policy"
            (let ((trigger (copy-tree (plist-get recipe :trigger) t)))
              (if (equal value "drop")
                  (progn
                    (setq trigger (plist-put trigger :policy :drop))
                    (setq trigger
                          (jetpacs-automations--plist-delete trigger :ttl-s)))
                (setq trigger (plist-put trigger :policy
                                         (if (equal value "wake") :wake :queue)))
                (unless (plist-get trigger :ttl-s)
                  (setq trigger (plist-put trigger :ttl-s 86400))))
              (plist-put recipe :trigger trigger)))
           ("ttl"
            (let ((number (string-to-number value))
                  (trigger (copy-tree (plist-get recipe :trigger) t)))
              (unless (and (string-match-p "\\`[0-9]+\\'" value)
                           (<= 1 number 604800))
                (error "TTL must be an integer from 1 to 604800"))
              (plist-put recipe :trigger (plist-put trigger :ttl-s number))))
           ((or "params" "when")
            (let* ((data (jetpacs-authoring-read-one
                          value jetpacs-automation-max-expression-bytes))
                   (trigger (copy-tree (plist-get recipe :trigger) t)))
              (plist-put recipe :trigger
                         (plist-put trigger (if (equal field "params")
                                                :params :when)
                                    data))))
           (_ (error "unknown Inspector field"))))))))

(defun jetpacs-automations--on-input-add (args params)
  "Append one text input to draft ARGS."
  (let ((key (plist-get args :id)))
    (jetpacs-automations--with-edit
     key params
     (lambda (recipe)
       (let* ((inputs (plist-get recipe :inputs))
              (names (mapcar (lambda (input) (plist-get input :name))
                             (append inputs nil)))
              (n 1) name)
         (while (progn (setq name (format "input-%d" n) n (1+ n))
                       (member name names)))
         (plist-put recipe :inputs
                    (vconcat inputs
                             (vector (list :name name :type "text" :value "")))))))))

(defun jetpacs-automations--on-input-delete (args params)
  "Delete an input named by ARGS."
  (let ((key (plist-get args :id)) (name (plist-get args :input)))
    (jetpacs-automations--with-edit
     key params
     (lambda (recipe)
       (plist-put recipe :inputs
                  (apply #'vector
                         (cl-remove name (append (plist-get recipe :inputs) nil)
                                    :key (lambda (input) (plist-get input :name))
                                    :test #'equal)))))))

(defun jetpacs-automations--on-input-set (args params)
  "Set one GUI-editable input property from ARGS."
  (let ((key (plist-get args :id)) (name (plist-get args :input))
        (field (plist-get args :field)) (value (plist-get args :value)))
    (if (not (and (stringp key) (stringp name) (stringp field)
                  (stringp value)))
        'rejected
      (jetpacs-automations--with-edit
       key params
       (lambda (recipe)
         (let* ((inputs (copy-sequence (plist-get recipe :inputs)))
                (index (jetpacs-automations--input-index inputs name))
                (input (and index (copy-tree (aref inputs index) t))))
           (unless input (error "input no longer exists"))
           (pcase field
             ("label"
              (setq input (if (string-empty-p value)
                              (jetpacs-automations--plist-delete input :label)
                            (plist-put input :label value))))
             ("type"
              (setq input (plist-put input :type value))
              (pcase value
                ("text" (setq input (plist-put input :value "")))
                ("number" (setq input (plist-put input :value 0)))
                ("bool" (setq input (plist-put input :value nil)))
                ("list" (setq input (plist-put input :value [])))
                ("enum"
                 (setq input (plist-put input :value "one"))
                 (setq input (plist-put input :options ["one" "two"])))
                (_ (error "unknown input type"))))
             ("value"
              (setq input
                    (plist-put input :value
                               (if (equal (plist-get input :type) "text")
                                   value
                                 (jetpacs-authoring-read-one
                                  value jetpacs-automation-max-expression-bytes)))))
             (_ (error "unknown input field")))
           (aset inputs index input)
           (plist-put recipe :inputs inputs)))))))

(defun jetpacs-automations--on-step-add (args params)
  "Append the action selected in ARGS to the root Tree."
  (let ((key (plist-get args :id)) (action (plist-get args :value)))
    (if (not (jetpacs-automation-action-descriptor action))
        'rejected
      (jetpacs-automations--with-edit
       key params
       (lambda (recipe)
         (let* ((steps (plist-get recipe :steps))
                (step (list :id (jetpacs-automations--new-step-id steps)
                            :kind :action :action action
                            :args (jetpacs-automations--default-args action))))
           (plist-put recipe :steps (vconcat steps (vector step)))))))))

(defun jetpacs-automations--on-step-add-if (args params)
  "Append an empty conditional to draft ARGS."
  (let ((key (plist-get args :id)))
    (jetpacs-automations--with-edit
     key params
     (lambda (recipe)
       (let* ((steps (plist-get recipe :steps))
              (step (list :id (jetpacs-automations--new-step-id steps)
                          :kind :if :condition t :then [] :else [])))
         (plist-put recipe :steps (vconcat steps (vector step))))))))

(defun jetpacs-automations--on-step-delete (args params)
  "Delete recursive step from ARGS."
  (let ((key (plist-get args :id)) (step-id (plist-get args :step)))
    (jetpacs-automations--with-edit
     key params
     (lambda (recipe)
       (plist-put recipe :steps
                  (jetpacs-automations--delete-step
                   (plist-get recipe :steps) step-id))))))

(defun jetpacs-automations--on-step-move (args params)
  "Move recursive step up or down within its sibling list."
  (let ((key (plist-get args :id)) (step-id (plist-get args :step))
        (direction (plist-get args :direction)))
    (jetpacs-automations--with-edit
     key params
     (lambda (recipe)
       (plist-put recipe :steps
                  (cdr (jetpacs-automations--move-sibling
                        (plist-get recipe :steps) step-id
                        (if (equal direction "up") -1 1))))))))

(defun jetpacs-automations--on-step-action (args params)
  "Change an action step to the action selected in ARGS."
  (let ((key (plist-get args :id)) (step-id (plist-get args :step))
        (action (plist-get args :value)))
    (if (not (jetpacs-automation-action-descriptor action))
        'rejected
      (jetpacs-automations--with-edit
       key params
       (lambda (recipe)
         (plist-put
          recipe :steps
          (jetpacs-automations--map-step
           (plist-get recipe :steps) step-id
           (lambda (step)
             (setq step (plist-put (copy-tree step t) :action action))
             (plist-put step :args (jetpacs-automations--default-args action))))))))))

(defun jetpacs-automations--on-step-source (args params)
  "Apply action args or conditional expression source from ARGS."
  (let ((key (plist-get args :id)) (step-id (plist-get args :step))
        (slot (plist-get args :slot)) (value (plist-get args :value)))
    (if (not (and (stringp value) (member slot '("args" "condition"))))
        'rejected
      (jetpacs-automations--with-edit
       key params
       (lambda (recipe)
         (let ((data (jetpacs-authoring-read-one
                      value jetpacs-automation-max-expression-bytes)))
           (plist-put
            recipe :steps
            (jetpacs-automations--map-step
             (plist-get recipe :steps) step-id
             (lambda (step)
               (plist-put (copy-tree step t)
                          (if (equal slot "args") :args :condition)
                          data))))))))))

(defun jetpacs-automations--on-lisp-apply (args params)
  "Parse and apply canonical recipe source in ARGS."
  (let ((key (plist-get args :id)) (value (plist-get args :value)))
    (if (not (and (stringp key) (stringp value)
                  (jetpacs-automations--draft key)))
        'rejected
      (condition-case err
          (jetpacs-automations--replace-recipe
           key (jetpacs-automation-read-recipe value))
        (error
         (let ((draft (jetpacs-automations--draft key)))
           (setq draft (plist-put draft :lisp-buffer value))
           (setq draft (plist-put draft :error
                                  (jetpacs-automations--error-text err)))
           (puthash key draft jetpacs-automations--drafts))))
      (jetpacs-automations--refresh params)
      'accepted)))

(defun jetpacs-automations--on-dry-run (args params)
  "Dry-run draft named by ARGS and retain its trace or error."
  (let* ((key (plist-get args :id))
         (draft (and (stringp key) (jetpacs-automations--draft key))))
    (if (not draft)
        'stale
      (let ((result (jetpacs-automation-run-dry-run
                     (plist-get draft :recipe))))
        (setq draft (plist-put draft :dry-run result))
        (setq draft (plist-put draft :error
                               (and (not (plist-get result :ok))
                                    (plist-get result :message))))
        (puthash key draft jetpacs-automations--drafts))
      (jetpacs-automations--refresh params)
      'accepted)))

(defun jetpacs-automations--on-save (args params)
  "Save the current proven draft named by ARGS."
  (let* ((key (plist-get args :id))
         (draft (and (stringp key) (jetpacs-automations--draft key)))
         (proof (and draft (plist-get draft :dry-run))))
    (if (not draft)
        'stale
      (condition-case err
          (progn
            (jetpacs-automation-save-recipe
             (plist-get draft :recipe) (plist-get proof :digest))
            (setq draft (plist-put draft :error nil)))
        (error (setq draft (plist-put draft :error
                                      (jetpacs-automations--error-text err)))))
      (puthash key draft jetpacs-automations--drafts)
      (jetpacs-automations--refresh params)
      'accepted)))

(defun jetpacs-automations--on-toggle (args params)
  "Enable or disable the saved draft named by ARGS."
  (let* ((key (plist-get args :id))
         (recipe (and (stringp key) (jetpacs-automation-recipe key)))
         (draft (and recipe (jetpacs-automations--draft key))))
    (if (not recipe)
        'stale
      (condition-case err
          (if (member key jetpacs-automation-enabled-recipes)
              (jetpacs-automation-disable key)
            ;; Make the currently shown successful proof visible to runtime.
            (let ((proof (plist-get draft :dry-run)))
              (unless (and (plist-get proof :ok)
                           (equal (plist-get proof :digest)
                                  (jetpacs-automation-recipe-digest recipe)))
                (error "run Dry Run on the saved revision before enabling"))
              (jetpacs-automation-enable key)))
        (error
         (setq draft (plist-put draft :error
                                (jetpacs-automations--error-text err)))
         (puthash key draft jetpacs-automations--drafts)))
      (jetpacs-automations--refresh params)
      'accepted)))

;;;; Node builders

(defun jetpacs-automations--action (verb key &rest args)
  "Return Automations action VERB for draft KEY plus ARGS."
  (jetpacs-action verb :args (append (list :id key) args)))

(defun jetpacs-automations--availability (kind name)
  "Return availability suffix for portable KIND/NAME on the live device."
  (if (not (jetpacs-connected-p))
      " — portable"
    (let ((profile (jetpacs-automation-device-profile)))
      (pcase kind
        ('trigger (unless (member name (plist-get profile :trigger-types))
                    " — unavailable"))
        ('action
         (let* ((descriptor (jetpacs-automation-action-descriptor name))
                (cap (plist-get descriptor :cap)))
           (unless (or (eq (plist-get descriptor :executor) 'host)
                       (plist-get descriptor :notify)
                       (member cap (plist-get profile :caps)))
             " — unavailable")))))))

(defun jetpacs-automations--trigger-options ()
  "Return dropdown options for the full portable trigger catalog."
  (mapcar
   (lambda (descriptor)
     (jetpacs-enum-option
      (concat (plist-get descriptor :label)
              (or (jetpacs-automations--availability
                   'trigger (plist-get descriptor :type)) ""))
      (plist-get descriptor :type)))
   (jetpacs-automation-trigger-descriptors)))

(defun jetpacs-automations--action-options ()
  "Return dropdown options for explicitly automation-safe actions."
  (mapcar
   (lambda (descriptor)
     (jetpacs-enum-option
      (concat (plist-get descriptor :label)
              (or (jetpacs-automations--availability
                   'action (plist-get descriptor :action)) ""))
      (plist-get descriptor :action)))
   (jetpacs-automation-action-descriptors)))

(defun jetpacs-automations--mode-row (key mode)
  "Return the three projection tabs for draft KEY and selected MODE."
  (jetpacs-row
   (jetpacs-button
    "Inspector" (jetpacs-automations--action
                 "automations.mode" key :mode "inspector")
    :variant (if (equal mode "inspector") "filled" "outlined"))
   (jetpacs-button
    "Tree" (jetpacs-automations--action
            "automations.mode" key :mode "tree")
    :variant (if (equal mode "tree") "filled" "outlined"))
   (jetpacs-button
    "Lisp" (jetpacs-automations--action
            "automations.mode" key :mode "lisp")
    :variant (if (equal mode "lisp") "filled" "outlined"))
   :spacing 8 :fill t))

(defun jetpacs-automations--input-card (key input index)
  "Return GUI controls for INPUT at INDEX in draft KEY."
  (let* ((name (plist-get input :name))
         (type (plist-get input :type))
         (value (plist-get input :value))
         (action-base (list :id key :input name)))
    (jetpacs-card
     (jetpacs-column
      (jetpacs-row
       (jetpacs-text (format "%d · %s" (1+ index) name) :style "title")
       (jetpacs-icon-button
        "delete"
        (apply #'jetpacs-action "automations.input.delete"
               :args action-base nil)
        :content-description "Delete input")
       :align "center" :arrange "space_between")
      (jetpacs-dropdown
       (format "automation-input-type-%d" index)
       (mapcar (lambda (name) (jetpacs-enum-option name name))
               '("text" "number" "bool" "enum" "list"))
       :label "Type" :value type
       :on-change (apply #'jetpacs-action "automations.input.set"
                         :args (append action-base (list :field "type")) nil))
      (jetpacs-text-input
       (format "automation-input-label-%d" index)
       :label "Label (optional)" :value (or (plist-get input :label) "")
       :on-submit (apply #'jetpacs-action "automations.input.set"
                         :args (append action-base (list :field "label")) nil)
       :single-line t)
      (jetpacs-text-input
       (format "automation-input-value-%d" index)
       :label (if (equal type "text") "Value" "Value (Lisp data)")
       :value (if (equal type "text") value
                (string-trim-right (jetpacs-authoring-print value 8192)))
       :on-submit (apply #'jetpacs-action "automations.input.set"
                         :args (append action-base (list :field "value")) nil)
       :single-line t)
      :spacing 8)
     :variant "outlined")))

(defun jetpacs-automations--inspector (key recipe)
  "Return Inspector projection for draft KEY/RECIPE."
  (let* ((trigger (plist-get recipe :trigger))
         (policy (substring (symbol-name (plist-get trigger :policy)) 1))
         nodes)
    (push (jetpacs-text-input
           "automation-name" :label "Name" :value (plist-get recipe :name)
           :on-submit (jetpacs-automations--action
                       "automations.field" key :field "name")
           :single-line t)
          nodes)
    (push (jetpacs-dropdown
           "automation-trigger" (jetpacs-automations--trigger-options)
           :label "Trigger" :value (plist-get trigger :type)
           :on-change (jetpacs-automations--action
                       "automations.field" key :field "trigger-type"))
          nodes)
    (push (jetpacs-dropdown
           "automation-policy"
           (mapcar (lambda (value) (jetpacs-enum-option (capitalize value) value))
                   '("drop" "queue" "wake"))
           :label "Offline policy" :value policy
           :on-change (jetpacs-automations--action
                       "automations.field" key :field "policy"))
          nodes)
    (unless (equal policy "drop")
      (push (jetpacs-text-input
             "automation-ttl" :label "Queue TTL (seconds)"
             :value (number-to-string (plist-get trigger :ttl-s))
             :keyboard "number" :single-line t
             :on-submit (jetpacs-automations--action
                         "automations.field" key :field "ttl"))
            nodes))
    (push (jetpacs-editor
           "automation-params" :value (jetpacs-authoring-print
                                        (plist-get trigger :params) 8192)
           :on-save (jetpacs-automations--action
                     "automations.field" key :field "params")
           :syntax "elisp" :line-numbers t :min-lines 3 :max-lines 7)
          nodes)
    (push (jetpacs-text "Trigger parameters — inert Lisp plist"
                        :style "caption") nodes)
    (push (jetpacs-editor
           "automation-when" :value (jetpacs-authoring-print
                                      (plist-get trigger :when) 8192)
           :on-save (jetpacs-automations--action
                     "automations.field" key :field "when")
           :syntax "elisp" :line-numbers t :min-lines 3 :max-lines 7)
          nodes)
    (push (jetpacs-text "State gates — inert Lisp vector"
                        :style "caption") nodes)
    (push (jetpacs-divider) nodes)
    (push (jetpacs-row
           (jetpacs-text "Inputs" :style "title")
           (jetpacs-button "Add input"
                           (jetpacs-automations--action
                            "automations.input.add" key)
                           :icon "add" :variant "outlined")
           :align "center" :arrange "space_between")
          nodes)
    (cl-loop for input across (plist-get recipe :inputs) for index from 0
             do (push (jetpacs-automations--input-card key input index) nodes))
    (apply #'jetpacs-column (append (nreverse nodes) '(:spacing 10)))))

(defun jetpacs-automations--step-target (plan top-index nested-p)
  "Return target label for PLAN's TOP-INDEX, respecting NESTED-P."
  (if (and (not nested-p)
           (< top-index (plist-get plan :device-prefix-count)))
      "On device · dispatched"
    "After reconnect"))

(defun jetpacs-automations--step-card
    (key step plan top-index &optional depth nested-p)
  "Return recursive Tree card for STEP in draft KEY."
  (let* ((depth (or depth 0))
         (id (plist-get step :id))
         (kind (plist-get step :kind))
         (target (jetpacs-automations--step-target plan top-index nested-p))
         (move-up (jetpacs-automations--action
                   "automations.step.move" key :step id :direction "up"))
         (move-down (jetpacs-automations--action
                     "automations.step.move" key :step id :direction "down"))
         nodes)
    (push (jetpacs-row
           (jetpacs-column
            (jetpacs-text (format "%s · %s" id
                                  (if (eq kind :action) "Action" "If"))
                          :style "title")
            (jetpacs-text target :style "caption") :spacing 2)
           (jetpacs-icon-button "arrow_upward" move-up
                                :content-description "Move up")
           (jetpacs-icon-button "arrow_downward" move-down
                                :content-description "Move down")
           (jetpacs-icon-button
            "delete"
            (jetpacs-automations--action
             "automations.step.delete" key :step id)
            :content-description "Delete step")
           :align "center" :spacing 4 :arrange "space_between")
          nodes)
    (if (eq kind :action)
        (progn
          (push (jetpacs-dropdown
                 (format "automation-step-action-%s" id)
                 (jetpacs-automations--action-options)
                 :label "Action" :value (plist-get step :action)
                 :on-change (jetpacs-automations--action
                             "automations.step.action" key :step id))
                nodes)
          (push (jetpacs-text "Arguments — literals or (:expr …)"
                              :style "caption") nodes)
          (push (jetpacs-editor
                 (format "automation-step-args-%s" id)
                 :value (jetpacs-authoring-print (plist-get step :args) 8192)
                 :on-save (jetpacs-automations--action
                           "automations.step.source" key :step id :slot "args")
                 :syntax "elisp" :line-numbers t :min-lines 3 :max-lines 8)
                nodes))
      (push (jetpacs-text "Condition" :style "caption") nodes)
      (push (jetpacs-editor
             (format "automation-step-condition-%s" id)
             :value (jetpacs-authoring-print (plist-get step :condition) 8192)
             :on-save (jetpacs-automations--action
                       "automations.step.source" key :step id :slot "condition")
             :syntax "elisp" :line-numbers t :min-lines 2 :max-lines 6)
            nodes)
      (dolist (branch '((:then . "THEN") (:else . "ELSE")))
        (push (jetpacs-text (cdr branch) :style "label") nodes)
        (let ((children (plist-get step (car branch))))
          (if (= (length children) 0)
              (push (jetpacs-text "No steps; use Lisp to seed this branch."
                                  :style "caption") nodes)
            (cl-loop for child across children
                     do (push (jetpacs-automations--step-card
                               key child plan top-index (1+ depth) t)
                              nodes))))))
    (jetpacs-with-attrs
     (jetpacs-card
     (apply #'jetpacs-column (append (nreverse nodes) '(:spacing 8)))
      :variant "outlined")
     :padding (* 12 depth))))

(defun jetpacs-automations--tree (key recipe)
  "Return ordered Tree projection for draft KEY/RECIPE."
  (let* ((profile (and (jetpacs-connected-p)
                       (jetpacs-automation-device-profile)))
         (plan (jetpacs-automation-compile recipe profile))
         (steps (plist-get recipe :steps))
         nodes)
    (push (jetpacs-text
           (format "%d on-device prefix step%s · %d after reconnect"
                   (plist-get plan :device-prefix-count)
                   (if (= (plist-get plan :device-prefix-count) 1) "" "s")
                   (length (plist-get plan :host-steps)))
           :style "caption") nodes)
    (when-let* ((missing (plist-get plan :missing)))
      (push (jetpacs-text (format "Unavailable: %s"
                                  (mapconcat #'identity missing ", "))
                          :style "caption" :color "error") nodes))
    (cl-loop for step across steps for index from 0
             do (push (jetpacs-automations--step-card
                       key step plan index 0 nil) nodes))
    (when (= (length steps) 0)
      (push (jetpacs-text "No steps yet. Add an action or conditional."
                          :style "body") nodes))
    (push (jetpacs-dropdown
           "automation-add-action" (jetpacs-automations--action-options)
           :label "Add action…"
           :on-change (jetpacs-automations--action
                       "automations.step.add" key))
          nodes)
    (push (jetpacs-button "Add conditional"
                           (jetpacs-automations--action
                            "automations.step.add-if" key)
                           :icon "account_tree" :variant "outlined")
          nodes)
    (apply #'jetpacs-column (append (nreverse nodes) '(:spacing 10)))))

(defun jetpacs-automations--lisp (key draft recipe)
  "Return canonical Lisp editor for KEY/DRAFT/RECIPE."
  (jetpacs-column
   (jetpacs-text
    "This is the complete persisted authority. It is read as one inert form; no Lisp is evaluated."
    :style "caption")
   (jetpacs-editor
    "automation-lisp"
    :value (or (plist-get draft :lisp-buffer)
               (jetpacs-automation-print-recipe recipe))
    :on-save (jetpacs-automations--action "automations.lisp.apply" key)
    :syntax "elisp" :line-numbers t :min-lines 18 :max-lines 28
    :autofocus t)
   :spacing 8))

(defun jetpacs-automations--dry-run-panel (draft)
  "Return status nodes for DRAFT's latest Dry Run."
  (let ((result (plist-get draft :dry-run)))
    (cond
     ((null result)
      (jetpacs-text "Dry Run required before Save or Enable."
                    :style "caption"))
     ((plist-get result :ok)
      (jetpacs-text
       (format "Dry Run passed · %d trace item%s · %s"
               (length (plist-get result :trace))
               (if (= (length (plist-get result :trace)) 1) "" "s")
               (substring (plist-get result :digest) 0 12))
       :style "caption" :color "primary"))
     (t (jetpacs-text (or (plist-get result :message) "Dry Run failed")
                      :style "caption" :color "error")))))

(defun jetpacs-automations--detail-screen (key back)
  "Build draft KEY's detail screen with BACK action."
  (let* ((draft (or (jetpacs-automations--draft key)
                    (error "Unknown automation draft %s" key)))
         (recipe (plist-get draft :recipe))
         (mode (plist-get draft :mode))
         (proof (plist-get draft :dry-run))
         (proved (and (plist-get proof :ok)
                      (equal (plist-get proof :digest)
                             (jetpacs-automation-recipe-digest recipe))))
         (saved (jetpacs-automation-recipe (plist-get recipe :id)))
         (enabled (member (plist-get recipe :id)
                          jetpacs-automation-enabled-recipes))
         (body
          (apply
           #'jetpacs-column
           (append
            (list
             (jetpacs-automations--mode-row key mode)
             (when-let* ((error (plist-get draft :error)))
               (jetpacs-card (jetpacs-text error :style "body" :color "error")
                             :variant "outlined"))
             (pcase mode
               ("tree" (jetpacs-automations--tree key recipe))
               ("lisp" (jetpacs-automations--lisp key draft recipe))
               (_ (jetpacs-automations--inspector key recipe)))
             (jetpacs-divider)
             (jetpacs-automations--dry-run-panel draft)
             (jetpacs-row
              (jetpacs-button "Dry Run"
                               (jetpacs-automations--action
                                "automations.dry-run" key)
                               :icon "play_arrow" :variant "outlined")
              (jetpacs-button "Save"
                               (jetpacs-automations--action
                                "automations.save" key)
                               :icon "save" :enabled (if proved t :json-false))
              (jetpacs-button
               (if enabled "Disable" "Enable")
               (jetpacs-automations--action "automations.toggle" key)
               :icon (if enabled "pause" "bolt")
               :variant (if enabled "outlined" "filled")
               :enabled (if (and saved proved) t :json-false))
              :spacing 8 :fill t))
            '(:spacing 12 :scroll t)))))
    (jetpacs-chrome-screen
     (plist-get recipe :name) body :back back
     :actions
     (list (jetpacs-icon-button
            "code" (jetpacs-automations--action
                    "automations.mode" key :mode
                    (if (equal mode "lisp") "inspector" "lisp"))
            :content-description "Toggle Lisp projection")))))

(defun jetpacs-automations--recipe-row (recipe)
  "Return a home row for saved RECIPE."
  (let* ((id (plist-get recipe :id))
         (status (jetpacs-automation-status id))
         (enabled (member id jetpacs-automation-enabled-recipes)))
    (jetpacs-chrome-row
     (plist-get recipe :name)
     :subtitle (format "%s · %s · %d step%s"
                       (if enabled "Enabled" "Disabled")
                       (plist-get status :state)
                       (length (plist-get recipe :steps))
                       (if (= (length (plist-get recipe :steps)) 1) "" "s"))
     :icon (if enabled "bolt" "account_tree")
     :on-tap (jetpacs-action "automations.open" :args (list :id id))
     :key id)))

(defun jetpacs-automations--home-screen (_back)
  "Build Automations home."
  (let* ((recipes (jetpacs-automation-recipes-normalized))
         (rows (mapcar #'jetpacs-automations--recipe-row recipes))
         (body (apply #'jetpacs-column
                      (append
                       (if rows rows
                         (list (jetpacs-card
                                (jetpacs-column
                                 (jetpacs-text "No automations yet" :style "title")
                                 (jetpacs-text
                                  "Create one, configure it in Inspector or Tree, inspect the same draft as Lisp, then Dry Run and Save."
                                  :style "body") :spacing 6)
                                :variant "outlined")))
                       '(:spacing 10 :scroll t)))))
    (jetpacs-chrome-screen
     jetpacs-automations-title body
     :actions
     (list (jetpacs-icon-button "add" (jetpacs-action "automations.new")
                                :content-description "New automation"))
     :fab (jetpacs-button "New automation" (jetpacs-action "automations.new")
                          :icon "add" :expanded "auto"))))

;;;; Registration

(defconst jetpacs-automations--verbs
  '(("automations.open" . jetpacs-automations--on-open)
    ("automations.new" . jetpacs-automations--on-new)
    ("automations.mode" . jetpacs-automations--on-mode)
    ("automations.field" . jetpacs-automations--on-field)
    ("automations.input.add" . jetpacs-automations--on-input-add)
    ("automations.input.delete" . jetpacs-automations--on-input-delete)
    ("automations.input.set" . jetpacs-automations--on-input-set)
    ("automations.step.add" . jetpacs-automations--on-step-add)
    ("automations.step.add-if" . jetpacs-automations--on-step-add-if)
    ("automations.step.delete" . jetpacs-automations--on-step-delete)
    ("automations.step.move" . jetpacs-automations--on-step-move)
    ("automations.step.action" . jetpacs-automations--on-step-action)
    ("automations.step.source" . jetpacs-automations--on-step-source)
    ("automations.lisp.apply" . jetpacs-automations--on-lisp-apply)
    ("automations.dry-run" . jetpacs-automations--on-dry-run)
    ("automations.save" . jetpacs-automations--on-save)
    ("automations.toggle" . jetpacs-automations--on-toggle))
  "Automations app verbs and handlers.")

(defun jetpacs-automations-register ()
  "Register the Automations app, screen, and actions idempotently."
  (with-jetpacs-owner jetpacs-automations-owner
    (dolist (entry jetpacs-automations--verbs)
      (jetpacs-defaction (car entry) (cdr entry)))
    (jetpacs-chrome-define-root jetpacs-automations-owner "home"
                                #'jetpacs-automations--home-screen))
  (jetpacs-defapp
   jetpacs-automations-owner :label jetpacs-automations-title
   :icon "account_tree" :surfaces (list jetpacs-automations-owner)
   :order 60))

(defun jetpacs-automations-unregister ()
  "Remove app-owned registrations while leaving the runtime active."
  (dolist (entry jetpacs-automations--verbs)
    (jetpacs-undefaction (car entry)))
  (jetpacs-apps-unregister jetpacs-automations-owner)
  (jetpacs-chrome-remove jetpacs-automations-owner))

;;;###autoload
(defun jetpacs-automations ()
  "Open the Automations app on the connected Companion."
  (interactive)
  (jetpacs-chrome-reset-screens jetpacs-automations-owner))

(jetpacs-automations-register)

(provide 'jetpacs-automations)
;;; jetpacs-automations.el ends here
