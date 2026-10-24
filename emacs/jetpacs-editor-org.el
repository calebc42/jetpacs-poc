;;; jetpacs-editor-org.el --- Org adapter for the Jetpacs editor -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Supplies the Org editing half of the Org Mode app: Orgro-compatible
;; formatting and insertion snippets on every editor rung, built-in Org
;; structural commands on synchronized editors, the add-heading FAB,
;; Org Crypt re-encryption before a real buffer save, and cache
;; invalidation after a durable Files save.  It installs the synchronous
;; Org save policy (`jetpacs-org-save') as the engine's
;; `ebp-org-file-save-function' and, on top of it, owns the
;; freshness-checked subtree splice (`jetpacs-editor-org-fresh-splice')
;; a heading editor commits through.
;;
;; Commands use the existing SPEC 17.7 `edit.command' path.  They run in
;; the attached Org buffer at the device caret and pass through
;; `jetpacs-emacs-ui-command-predicate'; a plain editor receives only
;; local snippet and line operations, so it remains valid offline.

;;; Code:

(require 'cl-lib)
(require 'org)
(require 'org-crypt)
(require 'ebp-org)
(require 'jetpacs-org-save)
(require 'jetpacs-editor)
(require 'jetpacs-reader-org)
(require 'jetpacs-org-toolbar)
(require 'jetpacs-org-dialogs)
(require 'jetpacs-widgets)

(defvar jetpacs-editor-org--registered nil
  "Non-nil while the Org editor adapter is registered.")

(defvar jetpacs-editor-org--previous-file-save-function nil
  "The `ebp-org-file-save-function' displaced by the Org adapter.")

(defun jetpacs-editor-org--synced-p ()
  "Whether the Files builder is authoring a synchronized editor."
  (and (plist-get jetpacs-files-editor-context :document) t))

(defun jetpacs-editor-org--format-items ()
  "Additional Orgro formatting and insertion toolbar items."
  (list
   (jetpacs-toolbar-item :icon "format_underlined" :label "U"
                         :snippet "_${selection}_")
   (jetpacs-toolbar-item :icon "code" :label "="
                         :snippet "=${selection}=")
   (jetpacs-toolbar-item :icon "subscript" :label "x₂"
                         :snippet "_{${selection}}")
   (jetpacs-toolbar-item :icon "superscript" :label "x²"
                         :snippet "^{${selection}}")
   (jetpacs-toolbar-item :icon "horizontal_rule" :label "Rule"
                         :snippet "-----" :placement "block")
   (jetpacs-toolbar-item
    :icon "format_quote" :label "Block"
    :menu
    (mapcar
     (lambda (kind)
       (jetpacs-toolbar-item
        :label kind
        :snippet (format "#+begin_%s\n${selection}${cursor}\n#+end_%s"
                         (downcase kind) (downcase kind))
        :placement "block"))
     '("Quote" "Example" "Verse" "Center" "Comment")))
   (jetpacs-toolbar-item :icon "format_list_bulleted" :label "Table"
                         :snippet "| ${cursor} |  |\n|---+---|\n|   |  |"
                         :placement "block")
   (jetpacs-toolbar-item :icon "format_quote" :label "Footnote"
                         :snippet "[fn:${input:Name}]")
   (jetpacs-toolbar-item :icon "library_books" :label "Cite"
                         :snippet "[cite:@${input:Key}]")
   (jetpacs-toolbar-item :icon "attachment" :label "Attach"
                         :snippet "[[attachment:${input:File}]]")))

(defun jetpacs-editor-org--command-items ()
  "Built-in Org command menu for a synchronized editor."
  (when (jetpacs-editor-org--synced-p)
    (list
     (jetpacs-toolbar-item
      :icon "more_vert" :label "Org"
      :menu
      (list
       (jetpacs-toolbar-item :label "Insert heading" :icon "post_add"
                             :command "org-insert-heading-respect-content")
       (jetpacs-toolbar-item :label "Cycle TODO" :icon "check_circle"
                             :command "org-todo")
       (jetpacs-toolbar-item :label "Schedule" :icon "schedule"
                             :command "org-schedule")
       (jetpacs-toolbar-item :label "Deadline" :icon "event_busy"
                             :command "org-deadline")
       (jetpacs-toolbar-item :label "Refile" :icon "drive_file_move"
                             :command "org-refile")
       (jetpacs-toolbar-item :label "Encrypt section" :icon "lock"
                             :command
                             "jetpacs-editor-org-encrypt-entry"))))))

(defun jetpacs-editor-org--toolbar (_path)
  "Return the complete Org toolbar for the current editor rung."
  (append (jetpacs-org-toolbar)
          (jetpacs-editor-org--format-items)
          (jetpacs-editor-org--command-items)))

(defun jetpacs-editor-org--body (path)
  "Return a plain section editor when PATH's Org buffer is narrowed.
SPEC 19 positions are whole-document offsets, so a narrowed document
must not pretend to be synchronized.  The reader transition has
already downgraded Files; this body presents exactly the accessible
subtree and records its Emacs-owned splice identity for the save gate."
  (when-let* ((buffer (get-file-buffer path)))
    (with-current-buffer buffer
      (when (and (not (jetpacs-reader-active-p path))
                 (buffer-narrowed-p))
        (let* ((beg (point-min))
               (end (point-max))
               (value (buffer-substring-no-properties beg end))
               (identity (format "%s:%d:%d" path beg end))
               (context (copy-sequence jetpacs-files-editor-context)))
          (jetpacs-reader-state-set path :org-narrow-edit-beg beg)
          (jetpacs-reader-state-set path :org-narrow-edit-end end)
          (jetpacs-reader-state-set path :org-narrow-edit-tick
                                    (buffer-chars-modified-tick))
          ;; Commands cannot use whole-document sync coordinates in this
          ;; local section editor, even if a stale context still carried
          ;; the old session during a hand-built test.
          (cl-remf context :document)
          (cl-remf context :editor-id)
          (cl-remf context :buffer)
          (let ((jetpacs-files-editor-context context))
            (jetpacs-editor
             (jetpacs-claim-node-id (jetpacs-wire-id "orgnedit" identity))
             :value value
             :syntax "org"
             :toolbar (jetpacs-editor-org--toolbar path)
             :on-save
             (jetpacs-action
              "jetpacs.editor.org.save-narrowed"
              :args (list :path path
                          :mtime (plist-get
                                  jetpacs-files-editor-context :mtime))))))))))

(defun jetpacs-editor-org--setup (path)
  "Prepare PATH's live Org buffer for encrypted-entry save safety."
  (when-let* ((buffer (get-file-buffer path)))
    (with-current-buffer buffer
      (unless (derived-mode-p 'org-mode) (org-mode))
      (add-hook 'before-save-hook #'org-encrypt-entries nil t)))
  nil)


(defun jetpacs-editor-org--fab (path)
  "Return the Org FAB menu for PATH."
  (let ((buf (buffer-name (find-file-noselect path t))))
    (jetpacs-icon-button
     "post_add"
     (jetpacs-org-add-heading-descriptor buf)
     :content-description "Add heading")))
(defun jetpacs-editor-org--actions (path)
  "The editor's top-bar actions for Org PATH: the file-properties dialog.
One emitter for every app: an app adapter composing over this one must
not emit its own, or the button doubles."
  (when (ebp-org-path-p path)
    (list (jetpacs-icon-button
           "tune"
           (jetpacs-action "jetpacs.org.file-properties.show"
                           :args (list :path path))
           :content-description "File properties"))))

(defun jetpacs-editor-org--before-save (_path buffer)
  "Re-encrypt decrypted Org Crypt entries in BUFFER before Files writes."
  (with-current-buffer buffer
    (when (derived-mode-p 'org-mode)
      (org-encrypt-entries))))

(defalias 'jetpacs-editor-org-save-policy #'jetpacs-org-save-policy
  "The policy's former home and name, kept live for app callers.
The policy lives in `jetpacs-org-save' now, a leaf every Org module
requires eagerly.  Stub `jetpacs-org-save-policy', which the
foundation calls; stubbing this alias intercepts only callers that
still use its name.")

(defun jetpacs-editor-org--commit-narrowed
    (true path value record buffer)
  "Splice VALUE into BUFFER's restriction and write all of TRUE.
RECORD is the current Files edit record.  Any failure before the disk
write restores the buffer byte-for-byte and then signals."
  (with-current-buffer buffer
    (let* ((original-beg (point-min))
           (original-end (point-max))
           (original-point (point))
           (original-modified (buffer-modified-p))
           (original-full
            (save-restriction
              (widen)
              (buffer-substring-no-properties (point-min) (point-max))))
           (changed nil)
           (durable nil))
      (unwind-protect
          (progn
            ;; Pre-write transforms must see the submitted section.  The
            ;; cleanup restores this speculative buffer mutation if one
            ;; of them (notably Org Crypt) cannot complete.
            (let ((inhibit-read-only t))
              (widen)
              (delete-region original-beg original-end)
              (goto-char original-beg)
              (insert value)
              (narrow-to-region original-beg (point)))
            (setq changed t)
            (jetpacs-editor--files-before-save true buffer)
            (let ((full
                   (save-restriction
                     (widen)
                     (buffer-substring-no-properties
                      (point-min) (point-max)))))
              (when (> (string-bytes full) jetpacs-files-max-bytes)
                (user-error "Edited file exceeds the save limit"))
              (let ((coding-system-for-write (plist-get record :coding)))
                (write-region full nil true nil 'silent))
              (setq durable t)
              (set-buffer-modified-p nil)
              (set-visited-file-modtime)
              (setq jetpacs-files--edit
                    (plist-put
                     (plist-put (copy-sequence record) :seed full)
                     :mtime (jetpacs-files--mtime-stamp true)))
              (jetpacs-reader-state-set
               path :org-narrow-edit-beg (point-min))
              (jetpacs-reader-state-set
               path :org-narrow-edit-end (point-max))
              (jetpacs-reader-state-set
               path :org-narrow-edit-tick (buffer-chars-modified-tick))
              full))
        (when (and changed (not durable))
          (let ((inhibit-read-only t))
            (widen)
            (delete-region (point-min) (point-max))
            (insert original-full)
            (narrow-to-region original-beg original-end)
            (goto-char (min (max original-point (point-min)) (point-max)))
            (set-buffer-modified-p original-modified))
          (jetpacs-reader-state-set path :org-narrow-edit-beg (point-min))
          (jetpacs-reader-state-set path :org-narrow-edit-end (point-max))
          (jetpacs-reader-state-set
           path :org-narrow-edit-tick (buffer-chars-modified-tick)))))))

(defun jetpacs-editor-org--save-narrowed (args params)
  "Validate, splice, and durably save one narrowed Org section."
  (let ((path (jetpacs-reader-org--action-path args params))
        (value (plist-get args :value))
        (stamp (plist-get args :mtime))
        (surface (plist-get params :surface)))
    (cond
     ((symbolp path) path)
     ((or (jetpacs-reader-active-p path) (not (stringp value))) 'rejected)
     ((> (string-bytes value) jetpacs-files-max-bytes)
      (jetpacs-shell-notify "Section is too large to save" surface)
      'rejected)
     (t
      (condition-case err
          (let* ((true (jetpacs-files--check path nil))
                 (record jetpacs-files--edit)
                 (buffer (get-file-buffer true))
                 (beg (jetpacs-reader-state-get
                       path :org-narrow-edit-beg nil))
                 (end (jetpacs-reader-state-get
                       path :org-narrow-edit-end nil))
                 (tick (jetpacs-reader-state-get
                        path :org-narrow-edit-tick nil)))
            (cond
             ((not (and (buffer-live-p buffer)
                        (equal (plist-get record :path) true)))
              'stale)
             ((not (equal stamp (jetpacs-files--mtime-stamp true)))
              (jetpacs-shell-notify "File changed on disk — not saved"
                                    surface)
              'stale)
             ((not (file-writable-p true))
              (jetpacs-shell-notify "File is not writable" surface)
              'rejected)
             ((not (with-current-buffer buffer
                     (and (buffer-narrowed-p)
                          (= (point-min) beg) (= (point-max) end)
                          (= (buffer-chars-modified-tick) tick))))
              (jetpacs-shell-notify
               "Section changed in Emacs — not saved" surface)
              'stale)
             (t
              (jetpacs-editor-org--commit-narrowed
               true path value record buffer)
              ;; Everything below is post-durability and cannot flip the
              ;; permanent action receipt to rejected.
              (jetpacs-run-isolated 'jetpacs-files-after-save-hook true)
              (condition-case post-error
                  (progn
                    (jetpacs-shell-notify
                     (format "Saved %s"
                             (jetpacs-scalar-text
                              (file-name-nondirectory true)))
                     surface)
                    (jetpacs-reader-refresh params))
                (error
                 (message "jetpacs-editor-org: post-save refresh failed: %s"
                          (jetpacs-error-label post-error))))
              'accepted)))
        (error
         (message "jetpacs-editor-org: narrowed save failed: %s"
                  (jetpacs-error-label err))
         (jetpacs-shell-notify "Section was not saved" surface)
         'rejected))))))

(defun jetpacs-editor-org--after-save (_path)
  "Invalidate memoized Org projections after a durable Files save."
  (ebp-org-cache-invalidate))

;;;; The fresh subtree splice (a heading editor's commit)

(define-error 'jetpacs-editor-org-splice-refused
  "Refused a stale or unsafe subtree splice" 'user-error)

(defun jetpacs-editor-org-mtime-stamp (path)
  "Return PATH's opaque microsecond modification stamp, or nil.
The value is compared only with another value from this function; it is
never parsed or exposed as a path-bearing identity."
  (when-let* ((mtime (file-attribute-modification-time
                      (file-attributes path))))
    (format-time-string "%s.%6N" mtime)))

(defun jetpacs-editor-org--splice-refuse (message)
  "Refuse a subtree splice with user-facing MESSAGE."
  (signal 'jetpacs-editor-org-splice-refused (list message)))

(defun jetpacs-editor-org--synced-edit-p (path)
  "Whether PATH is the live document in Files' synchronized editor."
  (let ((current (jetpacs-files-current-edit-path))
        (context (jetpacs-files-current-edit-context)))
    (and current
         (equal (file-truename current) (file-truename path))
         (plist-get context :document)
         (plist-get context :editor-id)
         (buffer-live-p (plist-get context :buffer)))))

(defun jetpacs-editor-org-fresh-splice (ref value stamp beg end tick)
  "Replace REF's subtree with VALUE after validating open-time facts.
STAMP, BEG, END, and TICK are the scalar snapshot minted when the editor
opened (`jetpacs-editor-org-mtime-stamp', the subtree bounds, and
`buffer-chars-modified-tick').  The target is re-resolved from REF,
including its Org ID, but the write proceeds only when disk mtime,
subtree bounds, and buffer tick still match that snapshot; a file open
in Files' synchronized editor is refused outright.  The save runs
through `jetpacs-org-save-policy'.  Any failure before durability
restores the visiting buffer's full text and modified state.  Signals
`jetpacs-editor-org-splice-refused' with a user-facing message, or the
engine's own `ebp-org-refused' / `ebp-org-unresolved'.  Return a freshly
anchored ref."
  (unless (and (stringp value)
               (string-match-p "\\`\\*+\\(?:[ \t]\\|$\\)" value))
    (jetpacs-editor-org--splice-refuse "Heading must start with Org stars"))
  (when (> (string-bytes value) jetpacs-files-max-bytes)
    (jetpacs-editor-org--splice-refuse "Heading is too large to save"))
  ;; Check disk identity before resolving the ref.  Resolution may visit or
  ;; consult the already-visiting buffer; doing it first would let Emacs ask
  ;; whether to edit a buffer whose file changed externally, violating D2.
  (let ((true (ebp-org--check-file (plist-get ref :file))))
    (when (jetpacs-editor-org--synced-edit-p true)
      (jetpacs-editor-org--splice-refuse "file is open in the synced editor"))
    (unless (equal stamp (jetpacs-editor-org-mtime-stamp true))
      (jetpacs-editor-org--splice-refuse "File changed on disk — not saved"))
    (unless (file-writable-p true)
      (jetpacs-editor-org--splice-refuse "File is not writable"))
    (let ((marker (ebp-org-resolve-ref ref)))
      (unwind-protect
          (with-current-buffer (marker-buffer marker)
            (org-with-wide-buffer
             (goto-char marker)
             (org-back-to-heading t)
             (let ((current-beg (point))
                   (current-end (save-excursion
                                  (org-end-of-subtree t t)
                                  (point))))
               (unless (and (integerp beg) (integerp end) (integerp tick)
                            (= current-beg beg) (= current-end end)
                            (= (buffer-chars-modified-tick) tick))
                 (jetpacs-editor-org--splice-refuse
                  "Heading changed in Emacs — not saved"))
               (let* ((original-beg (point-min))
                      (original-end (point-max))
                      (original-point (point))
                      (original-modified (buffer-modified-p))
                      (original-full
                       (buffer-substring-no-properties
                        (point-min) (point-max)))
                      (changed nil)
                      (durable nil)
                      new-ref)
                 (unwind-protect
                     (progn
                       (setq changed t)
                       (delete-region current-beg current-end)
                       (goto-char current-beg)
                       (insert value)
                       ;; Do not glue the following heading to a value
                       ;; whose final newline was omitted by the client.
                       (unless (or (bolp) (eobp)) (insert "\n"))
                       (goto-char current-beg)
                       (setq new-ref (ebp-org-ref-at-point))
                       (when (> (string-bytes
                                 (buffer-substring-no-properties
                                  (point-min) (point-max)))
                                jetpacs-files-max-bytes)
                         (jetpacs-editor-org--splice-refuse
                          "Edited file exceeds the save limit"))
                       (jetpacs-org-save-policy (current-buffer))
                       (setq durable t)
                       new-ref)
                   (when (and changed (not durable))
                     (let ((inhibit-read-only t))
                       (widen)
                       (delete-region (point-min) (point-max))
                       (insert original-full)
                       (narrow-to-region original-beg original-end)
                       (goto-char
                        (min (max original-point (point-min)) (point-max)))
                       (set-buffer-modified-p original-modified))))))))
        (set-marker marker nil)))))

;;;###autoload
(defun jetpacs-editor-org-encrypt-entry ()
  "Tag and symmetrically encrypt the Org entry at point.
This interactive wrapper is safe for the synchronized editor command
allowlist and makes the built-in before-save scan retain the entry."
  (interactive)
  (org-back-to-heading t)
  (org-toggle-tag "crypt" 'on)
  (org-encrypt-entry))

(defun jetpacs-editor-org-register ()
  "Register the Org editor adapter, idempotently."
  (unless jetpacs-editor-org--registered
    (setq jetpacs-editor-org--registered t)
    (jetpacs-editor-register
     'org :predicate #'ebp-org-path-p
     :setup #'jetpacs-editor-org--setup
     :body #'jetpacs-editor-org--body
     :toolbar #'jetpacs-editor-org--toolbar
     :fab #'jetpacs-editor-org--fab
     :actions #'jetpacs-editor-org--actions
     :before-save #'jetpacs-editor-org--before-save
     :after-save #'jetpacs-editor-org--after-save)
    (jetpacs-defaction "jetpacs.editor.org.save-narrowed"
                       #'jetpacs-editor-org--save-narrowed))
  (unless (eq ebp-org-file-save-function
              #'jetpacs-org-save-policy)
    (setq jetpacs-editor-org--previous-file-save-function
          ebp-org-file-save-function
          ebp-org-file-save-function #'jetpacs-org-save-policy))
  t)

(defun jetpacs-editor-org-unregister ()
  "Unregister the Org editor adapter."
  (when jetpacs-editor-org--registered
    (setq jetpacs-editor-org--registered nil)
    (jetpacs-editor-unregister 'org)
    (jetpacs-undefaction "jetpacs.editor.org.save-narrowed"))
  (when jetpacs-editor-org--previous-file-save-function
    (when (eq ebp-org-file-save-function
              #'jetpacs-org-save-policy)
      (setq ebp-org-file-save-function
            jetpacs-editor-org--previous-file-save-function))
    (setq jetpacs-editor-org--previous-file-save-function nil))
  t)

(defun jetpacs-editor-org-unload-function ()
  "Unload hygiene for the Org editor adapter."
  (jetpacs-editor-org-unregister)
  nil)

(provide 'jetpacs-editor-org)
;;; jetpacs-editor-org.el ends here
