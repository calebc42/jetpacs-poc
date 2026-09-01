;;; jetpacs-org-clock.el --- Native Org clock chronometer -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Mirrors the running native Org clock to the Companion as an ongoing
;; chronometer notification.  Clock state, persistence, notification
;; lifecycle, and the durable org.clock.* verbs belong to the Org Mode app;
;; downstream applications may choose where to place clock-in affordances.
;;
;; The notification action names and `notification:org-clock' surface are
;; durable wire identities.  In particular, every action remains global:
;; an hour-lived PendingIntent may replay during SYNCING, before READY can
;; re-claim the notification root after an Emacs restart.

;;; Code:

(require 'org-clock)
(require 'org-crypt)
(require 'ebp-org)
(require 'jetpacs-surfaces)
(require 'jetpacs-widgets)
(require 'jetpacs-shell)

(defconst jetpacs-org-clock-owner "org-mode"
  "Owner of native Org clocking and its chronometer notification.")

(defconst jetpacs-org-clock-surface "notification:org-clock"
  "The chronometer's durable surface id.")

(defconst jetpacs-org-clock--ttl-s 3600
  "Offline ttl for the notification's clock actions.
A non-drop policy requires one; an hour bounds a late delivery.  The
running entry keeps accumulating until the occurrence lands, so an
older clock-out records a duration the user never meant.")

(defvar jetpacs-org-clock-enabled t
  "Non-nil when native Org clock notification integration is enabled.
This rollout flag remains through the downstream owner's rollback window.")

(defvar jetpacs-org-clock--live nil
  "Non-nil while this Emacs has the notification root registered.
This prevents a same-owner re-claim from every file buffer whose clock-in
hook runs, and prevents retire from tombstoning a grant-degraded surface
that this session never asserted.")

(defconst jetpacs-org-clock--verbs
  '("org.clock.out" "org.clock.switch" "org.clock.in-last")
  "Durable clock verbs retained across the owner cutover.")

;;;; The SurfaceSpec

(defun jetpacs-org-clock--remote (name)
  "Return the chronometer ActionDescriptor for verb NAME.
Use `wake' only when the session holds `offline.wake'.  An ungranted wake
descriptor voids the whole surface at the push gate, so other sessions
degrade to `queue' instead of losing the chronometer."
  (jetpacs-action name
                  :when-offline (jetpacs-durable-offline-policy)
                  :ttl-s jetpacs-org-clock--ttl-s))

(defun jetpacs-org-clock-notification-spec ()
  "Return the ongoing chronometer SurfaceSpec for the running clock."
  (if (not (and (org-clock-is-active) org-clock-current-task))
      ;; A debounced repush may build once after retire.  A quiet zero state
      ;; is preferable to degrading the notification to an error view.
      (jetpacs-notification-surface
       (jetpacs-text "No running clock" :style "body")
       :meta (list :channel "clocking"))
    (jetpacs-notification-surface
     (jetpacs-text (format "Clocked in: %s" org-clock-current-task)
                   :style "title")
     :meta (list :channel "clocking"
                 :ongoing t
                 :category "stopwatch"
                 :chronometer
                 (list :base_ms (truncate
                                 (* 1000 (float-time org-clock-start-time))))
                 ;; Most important first: the platform may show fewer.
                 :actions
                 (vector
                  (list :label "Clock out"
                        :on_tap
                        (jetpacs-org-clock--remote "org.clock.out"))
                  (list :label "Switch task"
                        :on_tap
                        (jetpacs-org-clock--remote "org.clock.switch")))))))

;;;; Assert / retire

(defun jetpacs-org-clock--soon (function)
  "Run FUNCTION now, or after the current action dispatch extent.
Clock hooks fired inside a handler must not send from that extent; the same
hooks fired by an interactive native Org command need no deferral."
  (if (jetpacs-in-action-p)
      (jetpacs-flow-continue function)
    (funcall function)))

(defun jetpacs-org-clock--push ()
  "Re-render the live chronometer, keeping a gate failure local."
  (when jetpacs-org-clock--live
    (condition-case err
        (jetpacs-shell-push jetpacs-org-clock-surface)
      (error
       (message "jetpacs-org-clock: push failed: %s"
                (jetpacs-error-label err))))))

(defun jetpacs-org-clock--assert ()
  "Mirror the running native Org clock when the session may.
An ungranted notification surface degrades silently; READY re-asserts it
after disconnected or restarting sessions acquire their grants."
  (when (and jetpacs-org-clock-enabled
             (org-clock-is-active)
             (jetpacs-granted-p "surfaces.notification"))
    (unless jetpacs-org-clock--live
      (setq jetpacs-org-clock--live t)
      (with-jetpacs-owner jetpacs-org-clock-owner
        (jetpacs-shell-define-root jetpacs-org-clock-surface
                                   #'jetpacs-org-clock-notification-spec)))
    (jetpacs-org-clock--soon #'jetpacs-org-clock--push)))

(defun jetpacs-org-clock--retire ()
  "Take the chronometer down; remember a disconnected removal.
This is deliberately never grant-gated: a mid-session revocation must not
leave a dead timer ticking in the device shade."
  (when jetpacs-org-clock--live
    (setq jetpacs-org-clock--live nil)
    (jetpacs-org-clock--soon
     (lambda ()
       (jetpacs-shell-remove-root jetpacs-org-clock-surface)))))

(defun jetpacs-org-clock--on-ready (_client)
  "Settle both directions of the device cache at READY.
A running clock re-asserts its root; a stopped clock unconditionally
tombstones any cached ongoing notification.  This hook runs in the late
depth-90 READY phase, after durable replay has settled."
  (if (and jetpacs-org-clock-enabled (org-clock-is-active))
      (jetpacs-org-clock--assert)
    (setq jetpacs-org-clock--live nil)
    (jetpacs-shell-remove-root jetpacs-org-clock-surface)))

;;;; Durable handlers

(defun jetpacs-org-clock--defer-save (buffer)
  "Safely defer saving Org clock mutations in BUFFER.
The buffer-local crypt hook rides `ebp-org-defer-save''s later plain
`save-buffer', closing the path that would otherwise persist a decrypted
Org Crypt entry in cleartext."
  (when (buffer-live-p buffer)
    (with-current-buffer buffer
      (add-hook 'before-save-hook #'org-encrypt-entries nil t)
      (ebp-org-defer-save))))

(defun jetpacs-org-clock--on-out (_args _params)
  "Stop the running clock and return its durable dispatch status."
  (if (not (org-clock-is-active))
      'stale
    (let ((buffer (and (markerp org-clock-marker)
                       (marker-buffer org-clock-marker))))
      (org-clock-out)
      (jetpacs-org-clock--defer-save buffer)
      'accepted)))

(defun jetpacs-org-clock--on-switch (_args _params)
  "Refuse task switching until a device-visible picker exists."
  'rejected)

(defun jetpacs-org-clock--on-in-last (_args _params)
  "Resume the last clocked task, mapping failure to `rejected'."
  (condition-case err
      (progn
        (org-clock-in-last)
        (jetpacs-org-clock--defer-save
         (and (markerp org-clock-marker)
              (marker-buffer org-clock-marker)))
        'accepted)
    (error
     ;; Label only: the datum may quote heading text.
     (message "jetpacs-org-clock: clock-in-last failed: %s"
              (jetpacs-error-label err))
     'rejected)))

;;;; Enable / disable

(defun jetpacs-org-clock--undef-if-handler (name handler)
  "Undefine NAME only when HANDLER is this native implementation."
  (when (eq (gethash name jetpacs-action-handlers) handler)
    (jetpacs-undefaction name)))

(defun jetpacs-org-clock--on-teardown (owner)
  "Detach the native clock integration with its OWNER."
  (when (equal owner jetpacs-org-clock-owner)
    ;; The owner sweep already tombstoned the claimed surface.
    (setq jetpacs-org-clock--live nil)
    (jetpacs-org-clock-unregister)))

(defun jetpacs-org-clock-unregister ()
  "Detach native clock hooks and verbs, retiring a live chronometer."
  (jetpacs-org-clock--retire)
  (jetpacs-org-clock--undef-if-handler
   "org.clock.out" #'jetpacs-org-clock--on-out)
  (jetpacs-org-clock--undef-if-handler
   "org.clock.switch" #'jetpacs-org-clock--on-switch)
  (jetpacs-org-clock--undef-if-handler
   "org.clock.in-last" #'jetpacs-org-clock--on-in-last)
  (remove-hook 'org-clock-in-hook #'jetpacs-org-clock--assert)
  (remove-hook 'org-clock-out-hook #'jetpacs-org-clock--retire)
  (remove-hook 'jetpacs-ready-functions #'jetpacs-org-clock--on-ready)
  (remove-hook 'jetpacs-teardown-functions #'jetpacs-org-clock--on-teardown)
  t)

(defun jetpacs-org-clock-register ()
  "Register or disable native Org clock integration by rollout flag.
Hooks attach at app enable, never merely by loading this library."
  (if (not jetpacs-org-clock-enabled)
      (jetpacs-org-clock-unregister)
    ;; These are global by design.  A durable tap can replay during SYNCING,
    ;; before READY re-claims the notification root; surface scope would turn
    ;; that replay into a permanent rejection and delete its receipt.
    (with-jetpacs-owner jetpacs-org-clock-owner
      (jetpacs-defaction "org.clock.out" #'jetpacs-org-clock--on-out
                         :any-surface t
                         :doc "Stop the running Org clock.")
      (jetpacs-defaction "org.clock.switch" #'jetpacs-org-clock--on-switch
                         :any-surface t
                         :doc "Switch the running clock (no picker yet).")
      (jetpacs-defaction "org.clock.in-last" #'jetpacs-org-clock--on-in-last
                         :any-surface t
                         :doc "Resume the last clocked task."))
    (add-hook 'org-clock-in-hook #'jetpacs-org-clock--assert)
    (add-hook 'org-clock-out-hook #'jetpacs-org-clock--retire)
    (add-hook 'jetpacs-ready-functions #'jetpacs-org-clock--on-ready 90)
    (add-hook 'jetpacs-teardown-functions #'jetpacs-org-clock--on-teardown)
    ;; The Org Mode app may be enabled with a clock already running.
    (jetpacs-org-clock--assert))
  t)

(provide 'jetpacs-org-clock)
;;; jetpacs-org-clock.el ends here
