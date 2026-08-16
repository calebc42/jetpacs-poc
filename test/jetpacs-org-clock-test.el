;;; jetpacs-org-clock-test.el --- ERT for native Org clocking -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)
(require 'cl-lib)
(require 'seq)
(require 'jetpacs-org-clock)

(defun jetpacs-org-clock-test--register ()
  "Restore the canonical clock owner for an independent test arm."
  (let ((jetpacs-org-clock-enabled t))
    (jetpacs-org-clock-register)))

(ert-deftest jetpacs-org-clock-owner-defaults-and-durable-registration ()
  "The native owner registers every durable identity and lifecycle hook."
  (should (equal jetpacs-org-clock-owner "org-mode"))
  (should (equal jetpacs-org-clock-surface "notification:org-clock"))
  (should (default-value 'jetpacs-org-clock-enabled))
  (jetpacs-org-clock-test--register)
  (dolist (name jetpacs-org-clock--verbs)
    (should (gethash name jetpacs-action-handlers))
    (should (equal (jetpacs--owner-of "action" name) "org-mode"))
    ;; Durable PendingIntents can replay during SYNCING before the root claim.
    (should (gethash name jetpacs--any-surface-actions)))
  (should (memq #'jetpacs-org-clock--assert org-clock-in-hook))
  (should (memq #'jetpacs-org-clock--retire org-clock-out-hook))
  (should (memq #'jetpacs-org-clock--on-ready jetpacs-ready-functions))
  (should (memq #'jetpacs-org-clock--on-teardown
                jetpacs-teardown-functions)))

(ert-deftest jetpacs-org-clock-rollout-flag-round-trips-native-lifecycle ()
  "The rollout flag removes only native identities and restores them."
  (unwind-protect
      (progn
        (jetpacs-org-clock-test--register)
        (let ((jetpacs-org-clock-enabled nil))
          (jetpacs-org-clock-register))
        (dolist (name jetpacs-org-clock--verbs)
          (should-not (gethash name jetpacs-action-handlers)))
        (should-not (memq #'jetpacs-org-clock--assert org-clock-in-hook))
        (should-not (memq #'jetpacs-org-clock--retire org-clock-out-hook))
        (should-not (memq #'jetpacs-org-clock--on-ready
                          jetpacs-ready-functions))
        (jetpacs-org-clock-test--register)
        (should (eq (gethash "org.clock.out" jetpacs-action-handlers)
                    #'jetpacs-org-clock--on-out)))
    (jetpacs-org-clock-test--register)))

(ert-deftest jetpacs-org-clock-notification-shape ()
  "The chronometer spec carries an epoch base and two durable meta actions."
  (cl-letf (((symbol-function 'org-clock-is-active) (lambda () t)))
    (let* ((org-clock-current-task "Water the garden")
           (org-clock-start-time (time-subtract nil 90))
           (spec (jetpacs-org-clock-notification-spec))
           (meta (plist-get spec :meta))
           (chronometer (plist-get meta :chronometer))
           (actions (plist-get meta :actions)))
      (should (equal (plist-get (plist-get spec :body) :t) "text"))
      (should (eq (plist-get meta :ongoing) t))
      (should (equal (plist-get meta :category) "stopwatch"))
      (should (integerp (plist-get chronometer :base_ms)))
      (should (= (plist-get chronometer :base_ms)
                 (truncate (* 1000 (float-time org-clock-start-time)))))
      (should (vectorp actions))
      (should (= (length actions) 2))
      (should (equal (mapcar (lambda (action) (plist-get action :label))
                             (append actions nil))
                     '("Clock out" "Switch task")))
      (seq-doseq (entry actions)
        (let ((tap (plist-get entry :on_tap)))
          (should (member (plist-get tap :action)
                          '("org.clock.out" "org.clock.switch")))
          ;; This harness has no client, so ungranted wake degrades to queue.
          (should (equal (plist-get tap :when_offline) "queue"))
          (should (= (plist-get tap :ttl_s) 3600))))
      (let ((json (jetpacs-node->canonical-json spec)))
        (should (string-search "\"base_ms\"" json))
        (should (string-search "\"actions\"" json))
        (should (string-search "Water the garden" json))))))

(ert-deftest jetpacs-org-clock-handler-matrix-and-crypt-save-guard ()
  "Clock statuses are honest and every deferred save installs Org Crypt."
  (cl-letf (((symbol-function 'org-clock-is-active) (lambda () nil)))
    (should (eq (jetpacs-org-clock--on-out nil nil) 'stale)))
  ;; Clock-out captures the live buffer before Org clears its marker.
  (with-temp-buffer
    (let ((marker (point-marker)) saved outed)
      (cl-letf (((symbol-function 'org-clock-is-active) (lambda () marker))
                ((symbol-function 'org-clock-out)
                 (lambda (&rest _) (setq outed t)))
                ((symbol-function 'ebp-org-defer-save)
                 (lambda () (push (current-buffer) saved))))
        (let ((org-clock-marker marker))
          (should (eq (jetpacs-org-clock--on-out nil nil) 'accepted))
          (should outed)
          (should (equal saved (list (current-buffer))))
          (should (local-variable-p 'before-save-hook))
          (should (memq #'org-encrypt-entries before-save-hook))))))
  (should (eq (jetpacs-org-clock--on-switch nil nil) 'rejected))
  ;; Clock-in-last installs the same save guard in the newly clocked buffer.
  (with-temp-buffer
    (let ((marker (point-marker)) saved)
      (cl-letf (((symbol-function 'org-clock-in-last) (lambda (&rest _) t))
                ((symbol-function 'ebp-org-defer-save)
                 (lambda () (push (current-buffer) saved))))
        (let ((org-clock-marker marker))
          (should (eq (jetpacs-org-clock--on-in-last nil nil) 'accepted))
          (should (equal saved (list (current-buffer))))
          (should (local-variable-p 'before-save-hook))
          (should (memq #'org-encrypt-entries before-save-hook))))))
  (cl-letf (((symbol-function 'org-clock-in-last)
             (lambda (&rest _) (user-error "No last clock"))))
    (should (eq (jetpacs-org-clock--on-in-last nil nil) 'rejected))))

(ert-deftest jetpacs-org-clock-replayed-tap-dispatches-before-root-claim ()
  "A durable SYNCING replay reaches its global handler without a root."
  (jetpacs-org-clock-test--register)
  (should-not
   (jetpacs-owned-surface-p jetpacs-org-clock-surface
                            jetpacs-org-clock-owner))
  (let ((handler (gethash "org.clock.out" jetpacs-action-handlers)))
    (should handler)
    (cl-letf (((symbol-function 'org-clock-is-active) (lambda () nil)))
      (should
       (eq (jetpacs--dispatch
            nil (list :action "org.clock.out"
                      :surface jetpacs-org-clock-surface :args nil)
            handler)
           'stale)))
    (with-temp-buffer
      (let ((marker (point-marker)) outed)
        (cl-letf (((symbol-function 'org-clock-is-active) (lambda () marker))
                  ((symbol-function 'org-clock-out)
                   (lambda (&rest _) (setq outed t)))
                  ((symbol-function 'ebp-org-defer-save) #'ignore))
          (let ((org-clock-marker marker))
            (should
             (eq (jetpacs--dispatch
                  nil (list :action "org.clock.out"
                            :surface jetpacs-org-clock-surface :args nil)
                  handler)
                 'accepted))
            (should outed)))))))

(ert-deftest jetpacs-org-clock-grant-denial-degrades-whole-surface ()
  "No notification grant means no root, push, removal, or signal."
  (let ((pushes 0) (roots 0) (removals 0))
    (cl-letf (((symbol-function 'jetpacs-granted-p) (lambda (&rest _) nil))
              ((symbol-function 'jetpacs-shell-push)
               (lambda (&rest _) (cl-incf pushes)))
              ((symbol-function 'jetpacs-shell-define-root)
               (lambda (&rest _) (cl-incf roots)))
              ((symbol-function 'jetpacs-shell-remove-root)
               (lambda (&rest _) (cl-incf removals)))
              ((symbol-function 'org-clock-is-active) (lambda () t)))
      (let ((jetpacs-org-clock-enabled t)
            (jetpacs-org-clock--live nil)
            (org-clock-current-task "Task")
            (org-clock-start-time (current-time)))
        (jetpacs-org-clock--assert)
        (jetpacs-org-clock--on-ready nil)
        (should-not jetpacs-org-clock--live)
        (jetpacs-org-clock--retire)
        (should (zerop roots))
        (should (zerop pushes))
        (should (zerop removals))))))

(ert-deftest jetpacs-org-clock-ready-tombstones-stopped-cache ()
  "READY with no clock removes a cached chronometer in both live shapes."
  (let ((pushes 0) (roots 0) (removals 0))
    (cl-letf (((symbol-function 'jetpacs-granted-p) (lambda (&rest _) nil))
              ((symbol-function 'jetpacs-shell-push)
               (lambda (&rest _) (cl-incf pushes)))
              ((symbol-function 'jetpacs-shell-define-root)
               (lambda (&rest _) (cl-incf roots)))
              ((symbol-function 'jetpacs-shell-remove-root)
               (lambda (&rest _) (cl-incf removals)))
              ((symbol-function 'org-clock-is-active) (lambda () nil)))
      (let ((jetpacs-org-clock-enabled t)
            (jetpacs-org-clock--live nil))
        (jetpacs-org-clock--on-ready nil)
        (should (= removals 1))
        (should (zerop roots))
        (should (zerop pushes)))
      ;; A cancelled clock can leave the live flag set until READY settles it.
      (let ((jetpacs-org-clock-enabled t)
            (jetpacs-org-clock--live t))
        (jetpacs-org-clock--on-ready nil)
        (should (= removals 2))
        (should-not jetpacs-org-clock--live)
        (should (zerop roots))
        (should (zerop pushes))))))

(provide 'jetpacs-org-clock-test)
;;; jetpacs-org-clock-test.el ends here
