;;; jetpacs-org-structure.el --- Reusable Org outline sessions -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Jetpacs session policy over the prompt-free `ebp-org' structural engine.
;; This module owns applet-scoped private copy/cut clipboards, one safe undo
;; receipt, and presentation focus.  Org text, refs, buffers, and file names
;; remain in Emacs; downstream applets render only their own opaque ids and
;; action descriptors.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'org)
(require 'ebp-org)

(defgroup jetpacs-org-structure nil
  "Reusable Jetpacs sessions for Org outline manipulation."
  :group 'org
  :prefix "jetpacs-org-structure-")

(defvar jetpacs-org-structure--clipboards (make-hash-table :test #'equal)
  "Applet owner to private copy/cut receipt.")

(defvar jetpacs-org-structure--undos (make-hash-table :test #'equal)
  "Applet owner to its one live structural undo receipt.")

(defvar jetpacs-org-structure--focus (make-hash-table :test #'equal)
  "(OWNER . SCOPE) to private presentation-focus receipt.")

(defun jetpacs-org-structure--owner (owner)
  "Return OWNER when it is a non-empty string, or signal."
  (unless (and (stringp owner) (not (string-empty-p owner)))
    (user-error "Org structure owner must be a non-empty string"))
  owner)

(defun jetpacs-org-structure--buffer-for-ref (ref)
  "Return REF's live Org buffer without retaining its temporary marker."
  (let ((marker (ebp-org-resolve-ref ref)))
    (unwind-protect (marker-buffer marker)
      (set-marker marker nil))))

(defun jetpacs-org-structure--unique-buffers (buffers)
  "Return live BUFFERS once each, retaining their input order."
  (let (result)
    (dolist (buffer buffers (nreverse result))
      (when (and (buffer-live-p buffer) (not (memq buffer result)))
        (push buffer result)))))

(defun jetpacs-org-structure--undo-capable-p (buffers)
  "Return non-nil when every member of BUFFERS has ordinary undo enabled."
  (and buffers
       (cl-every (lambda (buffer)
                   (with-current-buffer buffer
                     (listp buffer-undo-list)))
                 buffers)))

(defun jetpacs-org-structure--start-undo-unit (buffers)
  "Put an undo boundary in each member of BUFFERS."
  (dolist (buffer buffers)
    (with-current-buffer buffer (undo-boundary))))

(defun jetpacs-org-structure--remember-undo
    (owner label buffers save-order)
  "Remember LABEL's completed BUFFERS mutation for OWNER.
SAVE-ORDER is the safety-preserving buffer order used if the user undoes it."
  (let ((entries
         (mapcar (lambda (buffer)
                   (list :buffer buffer
                         :tick (buffer-chars-modified-tick buffer)))
                 buffers)))
    (puthash owner
             (list :label label :entries entries
                   :save-order (jetpacs-org-structure--unique-buffers
                                save-order))
             jetpacs-org-structure--undos)))

(defun jetpacs-org-structure--perform
    (owner label buffers save-order function)
  "For OWNER, run structural FUNCTION and retain one undo named LABEL.
BUFFERS are exactly the buffers FUNCTION changes.  SAVE-ORDER specifies the
safe inverse persistence order.  A successful mutation replaces any older
receipt; when undo is disabled, it explicitly leaves no undo affordance."
  (setq owner (jetpacs-org-structure--owner owner)
        buffers (jetpacs-org-structure--unique-buffers buffers)
        save-order (jetpacs-org-structure--unique-buffers save-order))
  (let ((undo-capable (jetpacs-org-structure--undo-capable-p buffers)))
    (when undo-capable
      (jetpacs-org-structure--start-undo-unit buffers))
    (let ((outcome (funcall function)))
      (if undo-capable
          (progn
            (jetpacs-org-structure--start-undo-unit buffers)
            (jetpacs-org-structure--remember-undo
             owner label buffers save-order))
        (remhash owner jetpacs-org-structure--undos))
      outcome)))

(defun jetpacs-org-structure-insert
    (owner ref namespace relation title)
  "For OWNER, insert TITLE at RELATION to REF in NAMESPACE.
Return the explicit `ebp-org-insert-heading' outcome and retain one undo."
  (let ((buffer (jetpacs-org-structure--buffer-for-ref ref)))
    (jetpacs-org-structure--perform
     owner "Heading inserted" (list buffer) (list buffer)
     (lambda ()
       (ebp-org-insert-heading ref namespace relation title)))))

(defun jetpacs-org-structure-move (owner ref namespace direction)
  "For OWNER, move REF one sibling in DIRECTION within NAMESPACE."
  (let ((buffer (jetpacs-org-structure--buffer-for-ref ref)))
    (jetpacs-org-structure--perform
     owner "Heading moved" (list buffer) (list buffer)
     (lambda ()
       (ebp-org-move-subtree ref namespace direction)))))

(defun jetpacs-org-structure-promote (owner ref namespace)
  "For OWNER, promote REF in NAMESPACE and retain one undo."
  (let ((buffer (jetpacs-org-structure--buffer-for-ref ref)))
    (jetpacs-org-structure--perform
     owner "Heading promoted" (list buffer) (list buffer)
     (lambda () (ebp-org-promote-subtree ref namespace)))))

(defun jetpacs-org-structure-demote (owner ref namespace)
  "For OWNER, demote REF in NAMESPACE and retain one undo."
  (let ((buffer (jetpacs-org-structure--buffer-for-ref ref)))
    (jetpacs-org-structure--perform
     owner "Heading demoted" (list buffer) (list buffer)
     (lambda () (ebp-org-demote-subtree ref namespace)))))

(defun jetpacs-org-structure-duplicate (owner ref namespace)
  "For OWNER, duplicate REF in NAMESPACE and retain one undo."
  (let ((buffer (jetpacs-org-structure--buffer-for-ref ref)))
    (jetpacs-org-structure--perform
     owner "Heading duplicated" (list buffer) (list buffer)
     (lambda () (ebp-org-duplicate-subtree ref namespace)))))

(defun jetpacs-org-structure-delete (owner ref namespace)
  "For OWNER, delete REF in NAMESPACE and retain one undo."
  (let ((buffer (jetpacs-org-structure--buffer-for-ref ref)))
    (jetpacs-org-structure--perform
     owner "Heading deleted" (list buffer) (list buffer)
     (lambda () (ebp-org-delete-subtree ref namespace)))))

(defun jetpacs-org-structure-archive (owner ref namespace)
  "For OWNER, archive REF in NAMESPACE through the Org engine.
Archive can update a dynamically selected second file, so this operation
invalidates the previous generic undo receipt instead of pretending a single
buffer undo can reverse Org's complete archive bookkeeping."
  (setq owner (jetpacs-org-structure--owner owner))
  (let ((outcome (ebp-org-archive-subtree ref namespace)))
    (remhash owner jetpacs-org-structure--undos)
    outcome))

(defun jetpacs-org-structure--set-clipboard (owner ref mode)
  "Store REF privately for OWNER using copy/cut MODE."
  (setq owner (jetpacs-org-structure--owner owner))
  (unless (memq mode '(copy cut))
    (user-error "Clipboard mode must be copy or cut"))
  (let ((revision (ebp-org-subtree-revision ref)))
    (puthash owner
             (list :mode mode :ref (copy-tree ref) :revision revision)
             jetpacs-org-structure--clipboards)
    (list :available t :mode mode)))

(defun jetpacs-org-structure-copy (owner ref)
  "Copy REF into OWNER's private Emacs-only structural clipboard."
  (jetpacs-org-structure--set-clipboard owner ref 'copy))

(defun jetpacs-org-structure-cut (owner ref)
  "Arm REF for a move from OWNER's private structural clipboard.
No content is removed until a later successful paste."
  (jetpacs-org-structure--set-clipboard owner ref 'cut))

(defun jetpacs-org-structure-clipboard-summary (owner)
  "Return OWNER's presentation-safe clipboard summary, or nil.
The summary deliberately contains neither Org text, a ref, nor a file name."
  (when-let* ((entry (gethash (jetpacs-org-structure--owner owner)
                              jetpacs-org-structure--clipboards)))
    (list :available t :mode (plist-get entry :mode))))

(defun jetpacs-org-structure-clear-clipboard (owner)
  "Clear OWNER's private structural clipboard."
  (remhash (jetpacs-org-structure--owner owner)
           jetpacs-org-structure--clipboards))

(defun jetpacs-org-structure--live-clipboard (owner)
  "Return OWNER's clipboard when its source revision is still current.
A disappeared or edited source clears the stale receipt before signalling
`ebp-org-unresolved'.  Transient availability errors retain it."
  (let ((entry (gethash owner jetpacs-org-structure--clipboards)))
    (unless entry (signal 'ebp-org-unresolved nil))
    (condition-case err
        (if (equal (plist-get entry :revision)
                   (ebp-org-subtree-revision (plist-get entry :ref)))
            entry
          (remhash owner jetpacs-org-structure--clipboards)
          (signal 'ebp-org-unresolved nil))
      (ebp-org-unresolved
       (remhash owner jetpacs-org-structure--clipboards)
       (signal (car err) (cdr err))))))

(defun jetpacs-org-structure-transfer
    (owner ref destination namespace mode)
  "For OWNER, transfer REF to private DESTINATION in NAMESPACE using MODE.
MODE is `move' or `copy'.  This is the reusable direct refile/copy operation;
unlike `jetpacs-org-structure-paste', it neither reads nor changes a private
clipboard.  Existing contained destinations receive drift-checked one-level
undo.  A newly created destination remains safe and valid but cannot promise
generic buffer undo, so it replaces the prior receipt with none."
  (setq owner (jetpacs-org-structure--owner owner))
  (unless (memq mode '(move copy))
    (user-error "Transfer mode must be move or copy"))
  (let* ((source (jetpacs-org-structure--buffer-for-ref ref))
         (destination-file
          (and (plistp destination) (plist-get destination :file)))
         (allowed (and (stringp destination-file)
                       (ebp-org-file-allowed-p destination-file)))
         (target
          (and allowed
               (ebp-org-call-with-clamped-io
                #'find-file-noselect allowed t)))
         (changed (and target
                       (if (eq mode 'move)
                           (list source target)
                         (list target))))
         (undo-order (and target
                          (if (eq mode 'move)
                              (list source target)
                            (list target)))))
    (jetpacs-org-structure--perform
     owner (if (eq mode 'move) "Heading moved" "Heading copied")
     changed undo-order
     (lambda ()
       (ebp-org-transfer-subtree ref destination namespace mode)))))

(defun jetpacs-org-structure-paste (owner destination namespace)
  "Paste OWNER's private clipboard into DESTINATION in NAMESPACE.
DESTINATION has the private engine shape `(:file FILE :parent REF-OR-NIL)'.
A copy stays armed for another paste; a successful cut is consumed.  Raw Org
content never enters the returned outcome or a presentation model."
  (setq owner (jetpacs-org-structure--owner owner))
  (let* ((entry (jetpacs-org-structure--live-clipboard owner))
         (ref (plist-get entry :ref))
         (mode (if (eq (plist-get entry :mode) 'cut) 'move 'copy))
         (outcome
          (jetpacs-org-structure-transfer
           owner ref destination namespace mode)))
    (when (eq mode 'move)
      (remhash owner jetpacs-org-structure--clipboards))
    outcome))

(defun jetpacs-org-structure-undo-summary (owner)
  "Return OWNER's presentation-safe one-level undo summary, or nil."
  (when-let* ((receipt (gethash (jetpacs-org-structure--owner owner)
                                jetpacs-org-structure--undos)))
    (list :available t :label (plist-get receipt :label))))

(defun jetpacs-org-structure--undo-entry (receipt buffer)
  "Return BUFFER's private entry in undo RECEIPT."
  (cl-find buffer (plist-get receipt :entries)
           :key (lambda (entry) (plist-get entry :buffer)) :test #'eq))

(defun jetpacs-org-structure-undo (owner namespace)
  "Consume and reverse OWNER's one live structural mutation in NAMESPACE.
Every changed buffer must still match its post-mutation tick and disk version.
Cross-file undo restores the source first, so any later failure can duplicate
but never lose a subtree.  A post-undo save failure is reported as
`:pending-save' and scheduled locally; it is not exposed as a retryable action."
  (setq owner (jetpacs-org-structure--owner owner))
  (let ((receipt (gethash owner jetpacs-org-structure--undos)))
    (unless receipt (signal 'ebp-org-unresolved nil))
    (remhash owner jetpacs-org-structure--undos)
    (dolist (entry (plist-get receipt :entries))
      (let ((buffer (plist-get entry :buffer)))
        (unless (and (buffer-live-p buffer)
                     (= (plist-get entry :tick)
                        (buffer-chars-modified-tick buffer))
                     (with-current-buffer buffer
                       (verify-visited-file-modtime buffer)))
          (signal 'ebp-org-unresolved nil))))
    (let ((applied 0)
          pending)
      (catch 'stop
        (dolist (buffer (plist-get receipt :save-order))
          (unless (jetpacs-org-structure--undo-entry receipt buffer)
            (error "Undo save order names an unchanged buffer"))
          (condition-case nil
              (with-current-buffer buffer
                (org-with-wide-buffer
                 (let ((last-command nil)
                       (pending-undo-list nil))
                   (ebp-org-call-with-clamped-io #'undo-only 1))))
            (error
             (if (zerop applied)
                 (signal 'ebp-org-unresolved nil)
               (setq pending t)
               (throw 'stop nil))))
          (setq applied (1+ applied))
          (condition-case nil
              (with-current-buffer buffer
                (funcall ebp-org-file-save-function buffer))
            (error
             (setq pending t)
             (with-current-buffer buffer (ebp-org-defer-save))
             (throw 'stop nil)))))
      (ebp-org-cache-invalidate namespace)
      (append (list :changed (> applied 0)
                    :undone (plist-get receipt :label))
              (when pending (list :pending-save t))))))

(defun jetpacs-org-structure-focus-set (owner scope ref)
  "Set OWNER's private SCOPE focus to REF and its current revision."
  (setq owner (jetpacs-org-structure--owner owner))
  (puthash (cons owner scope)
           (list :ref (copy-tree ref)
                 :revision (ebp-org-subtree-revision ref))
           jetpacs-org-structure--focus)
  t)

(defun jetpacs-org-structure-focus-current (owner scope)
  "Return OWNER's live private focus REF for SCOPE, or nil when stale."
  (setq owner (jetpacs-org-structure--owner owner))
  (let* ((key (cons owner scope))
         (entry (gethash key jetpacs-org-structure--focus)))
    (when entry
      (condition-case nil
          (let ((ref (plist-get entry :ref)))
            (if (not (equal (plist-get entry :revision)
                            (ebp-org-subtree-revision ref)))
                (progn
                  (remhash key jetpacs-org-structure--focus)
                  nil)
              (let ((marker (ebp-org-resolve-ref ref)))
                (unwind-protect
                    (with-current-buffer (marker-buffer marker)
                      (org-with-wide-buffer
                       (goto-char marker)
                       (ebp-org-ref-at-point)))
                  (set-marker marker nil)))))
        ((ebp-org-refused ebp-org-unresolved)
         (remhash key jetpacs-org-structure--focus)
         nil)
        (ebp-org-unavailable nil)))))

(defun jetpacs-org-structure-focus-clear (owner scope)
  "Widen OWNER's presentation by clearing private SCOPE focus."
  (remhash (cons (jetpacs-org-structure--owner owner) scope)
           jetpacs-org-structure--focus))

(defun jetpacs-org-structure-reset (&optional owner)
  "Clear structural session state for OWNER, or every owner when nil."
  (if owner
      (progn
        (setq owner (jetpacs-org-structure--owner owner))
        (remhash owner jetpacs-org-structure--clipboards)
        (remhash owner jetpacs-org-structure--undos)
        (let (dead)
          (maphash (lambda (key _value)
                     (when (equal (car key) owner) (push key dead)))
                   jetpacs-org-structure--focus)
          (dolist (key dead) (remhash key jetpacs-org-structure--focus))))
    (clrhash jetpacs-org-structure--clipboards)
    (clrhash jetpacs-org-structure--undos)
    (clrhash jetpacs-org-structure--focus))
  t)

(defun jetpacs-org-structure-unload-function ()
  "Drop every private structural session receipt before unloading."
  (jetpacs-org-structure-reset)
  nil)

(provide 'jetpacs-org-structure)
;;; jetpacs-org-structure.el ends here
