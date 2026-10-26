;;; jetpacs-reminders.el --- Owner reminder projections -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; An owner that keeps a durable reminder set on the device (SPEC 18.6)
;; supplies only its projector: a function that returns the set it
;; wants now.  This module owns the rest, the same for every owner:
;;
;; - Coalescing.  `jetpacs-reminders-schedule-sync' collapses a burst of
;;   triggers (every surface push, an Org refresh, a settings change)
;;   into one projection, deferred out of any dispatch extent (D2).
;; - The confirmed-set cache.  A set equal to the last one the Companion
;;   accepted is not resent; the cache moves only on the callback's
;;   acceptance, so a lost send is retried by the next trigger.
;; - Failure is not emptiness.  A projector that signals sends nothing,
;;   so a broken scan never clears the device's durable set.  A disabled
;;   projection, by contrast, sends the empty set: that is the explicit
;;   clear a user who turns reminders off is asking for.
;; - The trigger every owner wants, `jetpacs-shell-after-push-hook', and
;;   teardown: a projection is a ledger claim, so
;;   `jetpacs-teardown-owner' removes it with the owner's hooks.
;;
;; Nothing here knows about Org.  The canonical Org reminder set
;; (jetpacs-org-reminders.el) is one projection; an app such as Bloom is
;; another.

;;; Code:

(require 'cl-lib)
(require 'jetpacs-surfaces)
(require 'jetpacs-device)

(defvar jetpacs-shell-after-push-hook)

(defvar jetpacs-reminders--projections (make-hash-table :test #'equal)
  "Map of OWNER -> projection plist.
Members: :projector and :enabled-p as given to
`jetpacs-reminders-define-projection'; :last, the set the Companion last
confirmed, or `unset'; :timer, the pending coalesced sync, or nil.")

(cl-defun jetpacs-reminders-define-projection (owner projector &key enabled-p)
  "Keep OWNER's device reminder set equal to what PROJECTOR returns.
PROJECTOR takes no arguments and returns a list of SPEC 18.6 reminder
plists (see `jetpacs-reminders-set'); it signals when it cannot tell, and
then nothing is sent.  ENABLED-P, when given, is called before each sync:
nil sends the empty set, clearing OWNER's reminders on the device.

The projection syncs after every surface push and whenever
`jetpacs-reminders-schedule-sync' is called for OWNER.  It is a ledger
claim of OWNER, so `jetpacs-teardown-owner' removes it; so does
`jetpacs-reminders-remove-projection'.  Defining it again replaces it
and forgets the confirmed set, so the next sync sends.  Return OWNER."
  (jetpacs-reminders-remove-projection owner)
  (with-jetpacs-owner owner
    (puthash owner (list :projector projector :enabled-p enabled-p
                         :last 'unset :timer nil)
             jetpacs-reminders--projections)
    (jetpacs--claim "reminder-projection" owner))
  (add-hook 'jetpacs-shell-after-push-hook #'jetpacs-reminders--after-push)
  owner)

(defun jetpacs-reminders-remove-projection (owner)
  "Stop syncing OWNER's reminders and forget its confirmed set.
The device keeps the set it holds: unloading is not a request to clear
reminders, which a disabled projection's empty set is."
  (when-let* ((projection (gethash owner jetpacs-reminders--projections)))
    (when (timerp (plist-get projection :timer))
      (cancel-timer (plist-get projection :timer)))
    (remhash owner jetpacs-reminders--projections))
  (when (equal (jetpacs--owner-of "reminder-projection" owner) owner)
    (jetpacs--unclaim "reminder-projection" owner))
  (when (zerop (hash-table-count jetpacs-reminders--projections))
    (remove-hook 'jetpacs-shell-after-push-hook
                 #'jetpacs-reminders--after-push))
  owner)

(jetpacs-define-claim-disposer
 "reminder-projection" #'jetpacs-reminders-remove-projection)

(defun jetpacs-reminders-projection-p (owner)
  "Non-nil when OWNER has a reminder projection."
  (and (gethash owner jetpacs-reminders--projections) t))

(defun jetpacs-reminders--after-push ()
  "Schedule a sync of every projection after a surface push."
  (maphash (lambda (owner _) (jetpacs-reminders-schedule-sync owner))
           jetpacs-reminders--projections))

(defconst jetpacs-reminders--coalesce-idle-seconds 0.1
  "Idle delay before a scheduled sync runs, outside an action handler.
Long enough to fold a burst of pushes or saves into one projection,
short enough that the device's set follows an edit at once; the delay
the Org projection used before the module took over its sync.")

(defun jetpacs-reminders-schedule-sync (owner)
  "Coalesce a sync of OWNER's projection; return t.
Inside an action handler the sync runs as the handler's continuation,
elsewhere from an idle timer.  Nothing happens without a projection."
  (when-let* ((projection (gethash owner jetpacs-reminders--projections)))
    (unless (timerp (plist-get projection :timer))
      (let ((run (lambda () (jetpacs-reminders--run-scheduled owner))))
        (plist-put projection :timer
                   (if (jetpacs-in-action-p)
                       (jetpacs-flow-continue run)
                     (run-with-idle-timer
                      jetpacs-reminders--coalesce-idle-seconds nil run))))))
  t)

(defun jetpacs-reminders--run-scheduled (owner)
  "Clear OWNER's pending timer and sync it."
  (when-let* ((projection (gethash owner jetpacs-reminders--projections)))
    (plist-put projection :timer nil)
    (jetpacs-reminders-sync-now owner)))

(defun jetpacs-reminders-sync-now (owner)
  "Send OWNER's projected set now, when connected, granted and changed."
  (when-let* ((projection (gethash owner jetpacs-reminders--projections))
              ((jetpacs-connected-p))
              ((jetpacs-granted-p "reminders.owner"))
              (scan
               (condition-case err
                   (let ((enabled-p (plist-get projection :enabled-p)))
                     (cons t (and (or (null enabled-p) (funcall enabled-p))
                                  (funcall (plist-get projection :projector)))))
                 (error
                  (message "jetpacs-reminders: %s scan failed (%s)"
                           owner (jetpacs-error-label err))
                  nil))))
    (let ((reminders (cdr scan)))
      (unless (equal reminders (plist-get projection :last))
        (condition-case err
            (jetpacs-reminders-set
             reminders :owner owner
             :callback
             (lambda (_count error)
               ;; The projection may have been replaced meanwhile; only the
               ;; one that sent records the confirmation.
               (when (and (null error)
                          (eq projection
                              (gethash owner jetpacs-reminders--projections)))
                 (plist-put projection :last reminders))))
          (error
           (message "jetpacs-reminders: %s sync failed (%s)"
                    owner (jetpacs-error-label err))))))))

(provide 'jetpacs-reminders)
;;; jetpacs-reminders.el ends here
