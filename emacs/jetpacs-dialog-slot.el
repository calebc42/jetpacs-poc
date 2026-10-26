;;; jetpacs-dialog-slot.el --- One live dialog per owner -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The single-writer dialog slot, keyed by owner.  An app that shows its
;; own editor dialogs (properties, saved searches, pickers) needs the same
;; three guarantees every time, and used to rebuild them by hand:
;;
;; - one live dialog per owner: a second show abandons the first, whose
;;   device dialog would otherwise linger with no way back to it;
;; - the abandoned dialog's late conclusion (the 1301 the abandon causes)
;;   must not clear its successor's slot, or the successor's Save handler
;;   finds no request to close and loses the params it was opened from;
;; - teardown of the owner abandons whatever is still live.
;;
;; A dialog-context event carries no `:surface' (SPEC 14.4), so the slot
;; also keeps the OPENING event's params for the refresh a Save needs.

;;; Code:

(require 'cl-lib)
(require 'ebp)
(require 'jetpacs-surfaces)

(defvar jetpacs-dialog--slots (make-hash-table :test #'equal)
  "Map of OWNER -> (:request-id ID :params PARAMS) for its live dialog.")

(defun jetpacs-dialog--slot-owner (owner)
  "OWNER, else `jetpacs-current-owner'; signal when neither names one."
  (or owner jetpacs-current-owner
      (error "jetpacs-dialog-slot: no owner (pass :owner or bind one)")))

(defun jetpacs-dialog-slot-params (&optional owner)
  "The opening event's params of OWNER's live dialog, or nil.
OWNER defaults to `jetpacs-current-owner'."
  (plist-get (gethash (jetpacs-dialog--slot-owner owner)
                      jetpacs-dialog--slots)
             :params))

(defun jetpacs-dialog-slot-live-p (&optional owner)
  "Non-nil while OWNER has a live dialog in its slot."
  (and (gethash (jetpacs-dialog--slot-owner owner) jetpacs-dialog--slots) t))

(defun jetpacs-dialog-slot-close (&optional owner)
  "Retire OWNER's live dialog, if any (the S3 handler-side dismissal).
The slot is cleared BEFORE `ebp-client-abandon' sends rpc.cancel, so the
Companion's resulting 1301 conclusion finds nothing to clear."
  (let* ((owner (jetpacs-dialog--slot-owner owner))
         (slot (gethash owner jetpacs-dialog--slots)))
    (remhash owner jetpacs-dialog--slots)
    (when-let* ((client (jetpacs-client))
                (request-id (plist-get slot :request-id)))
      (condition-case err
          (ebp-client-abandon client request-id)
        (error
         ;; The slot is already cleared; a request the client could not
         ;; abandon (it is gone, or the request already concluded) is
         ;; worth a line, not a signal.
         (message "jetpacs-dialog-slot: abandon for %s failed: %s"
                  owner (jetpacs-error-label err)))))))

(cl-defun jetpacs-dialog-slot-show (id spec &key owner params on-submit
                                       on-conclude style)
  "Show SPEC as dialog ID in OWNER's slot; return the request id or nil.
OWNER defaults to `jetpacs-current-owner'.  PARAMS are the opening
event's params, read back with `jetpacs-dialog-slot-params'.  ON-SUBMIT,
when given, receives the conclusion's `:fields' plist (the
`jetpacs-dialog-submit' route); a dialog whose Save is a remote action
concludes through `jetpacs-dialog-slot-close' in that action's handler.
ON-CONCLUDE, when given, receives every conclusion as (STATUS RESULT
ERROR), after the slot is cleared, for a dialog that reads a submitted
`:value' or reacts to dismissal.  A still-live earlier dialog of the
same owner is abandoned first.  STYLE is passed through to
`ebp-client-dialog-show' (for example \"sheet\")."
  (let ((owner (jetpacs-dialog--slot-owner owner)))
    (when-let* ((client (jetpacs-client)))
      (jetpacs-dialog-slot-close owner)
      ;; CELL carries the id into the callback, which cannot close over
      ;; REQUEST-ID: the show that returns it is that binding's init.
      (let* ((cell (list nil))
             (request-id
              (apply
               #'ebp-client-dialog-show
               client id spec
               (append
                (and style (list :style style))
                (list
                 :callback
               (lambda (status result error)
                 ;; Dismissal and the abandon's 1301 both land here; each
                 ;; clears only its OWN request.
                 (when (equal (plist-get (gethash owner jetpacs-dialog--slots)
                                         :request-id)
                              (car cell))
                   (remhash owner jetpacs-dialog--slots))
                 ;; Outside the identity guard: a submitted conclusion runs
                 ;; its handler whoever owns the slot by then.
                 (when (and on-submit (equal status "submitted"))
                   (funcall on-submit (plist-get result :fields)))
                 (when on-conclude
                   (funcall on-conclude status result error))))))))
        (when request-id
          (setcar cell request-id)
          (puthash owner (list :request-id request-id :params params)
                   jetpacs-dialog--slots))
        request-id))))

(defun jetpacs-dialog-slot--on-teardown (owner)
  "Abandon OWNER's live dialog when OWNER is torn down."
  (jetpacs-dialog-slot-close owner))

(defun jetpacs-dialog-slot-reset ()
  "Forget every slot without abandoning (the test and reset seam)."
  (clrhash jetpacs-dialog--slots))

(add-hook 'jetpacs-teardown-functions #'jetpacs-dialog-slot--on-teardown)
(add-hook 'jetpacs-reset-functions #'jetpacs-dialog-slot-reset)

(defun jetpacs-dialog-slot-unload-function ()
  "Unload hygiene: detach the teardown and reset members."
  (remove-hook 'jetpacs-teardown-functions #'jetpacs-dialog-slot--on-teardown)
  (remove-hook 'jetpacs-reset-functions #'jetpacs-dialog-slot-reset)
  nil)

(provide 'jetpacs-dialog-slot)
;;; jetpacs-dialog-slot.el ends here
