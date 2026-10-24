;;; jetpacs-reminders-test.el --- Owner reminder projections -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; `jetpacs-reminders-define-projection': coalescing, the confirmed-set
;; cache, failure versus a disabled projection, and teardown.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-reminders)
(require 'jetpacs-shell)

(defmacro jetpacs-reminders-test--with-device (calls &rest body)
  "Run BODY against a connected, granted fake device.
CALLS names a variable that collects (OWNER . REMINDERS) per send; each
send is accepted at once.  Projections and the push hook start empty."
  (declare (indent 1))
  `(let ((jetpacs-reminders--projections (make-hash-table :test #'equal))
         (jetpacs-shell-after-push-hook nil)
         (,calls nil))
     (cl-letf (((symbol-function 'jetpacs-connected-p) (lambda () t))
               ((symbol-function 'jetpacs-granted-p) (lambda (&rest _) t))
               ((symbol-function 'jetpacs-reminders-set)
                (cl-function
                 (lambda (reminders &key owner callback)
                   (push (cons owner reminders) ,calls)
                   (funcall callback (length reminders) nil)))))
       ,@body)))

(ert-deftest jetpacs-reminders-projection-diffs-and-clears-when-disabled ()
  "Equal sets are sent once; a disabled projection sends the empty set."
  (jetpacs-reminders-test--with-device calls
    (let ((enabled t)
          (set '((:id "app.r1" :at_ms 1 :title "One"))))
      (jetpacs-reminders-define-projection
       "app" (lambda () set) :enabled-p (lambda () enabled))
      (jetpacs-reminders-sync-now "app")
      (jetpacs-reminders-sync-now "app")
      (should (equal calls `(("app" . ,set))))
      (setq enabled nil)
      (jetpacs-reminders-sync-now "app")
      (should (equal (car calls) '("app")))
      (jetpacs-reminders-sync-now "app")
      (should (= (length calls) 2)))))

(ert-deftest jetpacs-reminders-failed-scan-and-lost-send-keep-the-cache ()
  "A signalling projector sends nothing; an unconfirmed send is retried."
  (jetpacs-reminders-test--with-device calls
    (let ((fail t)
          (accept nil)
          (set '((:id "app.r1" :at_ms 1 :title "One"))))
      (cl-letf (((symbol-function 'jetpacs-error-label) (lambda (_) "e")))
        (jetpacs-reminders-define-projection
         "app" (lambda () (if fail (error "scan") set)))
        (jetpacs-reminders-sync-now "app")
        (should-not calls)
        (setq fail nil)
        (cl-letf (((symbol-function 'jetpacs-reminders-set)
                   (cl-function
                    (lambda (reminders &key owner callback)
                      (push (cons owner reminders) calls)
                      (funcall callback 0 (and (not accept) 'refused))))))
          (jetpacs-reminders-sync-now "app")
          (setq accept t)
          (jetpacs-reminders-sync-now "app")
          (jetpacs-reminders-sync-now "app"))
        (should (= (length calls) 2))))))

(ert-deftest jetpacs-reminders-schedule-coalesces-and-push-triggers ()
  "A burst of triggers is one sync; every push schedules every owner."
  (jetpacs-reminders-test--with-device calls
    (let ((scans 0))
      (jetpacs-reminders-define-projection
       "app" (lambda () (cl-incf scans) nil))
      (should (memq #'jetpacs-reminders--after-push
                    jetpacs-shell-after-push-hook))
      (should (jetpacs-reminders-schedule-sync "app"))
      (jetpacs-reminders-schedule-sync "app")
      (run-hooks 'jetpacs-shell-after-push-hook)
      (let ((timer (plist-get (gethash "app" jetpacs-reminders--projections)
                              :timer)))
        (should (timerp timer))
        (timer-event-handler timer))
      (should (= scans 1))
      (should-not (plist-get (gethash "app" jetpacs-reminders--projections)
                             :timer))
      ;; No projection, no timer and no error.
      (should (jetpacs-reminders-schedule-sync "nobody")))))

(ert-deftest jetpacs-reminders-teardown-removes-the-projection ()
  "Teardown and removal cancel the timer, keep the device set, drop the hook."
  (jetpacs-reminders-test--with-device calls
    (jetpacs-reminders-define-projection "app" (lambda () nil))
    (jetpacs-reminders-define-projection "other" (lambda () nil))
    (jetpacs-reminders-schedule-sync "app")
    (let ((timer (plist-get (gethash "app" jetpacs-reminders--projections)
                            :timer)))
      (jetpacs--dispose-owner-claims "app")
      (should-not (memq timer timer-idle-list)))
    (should-not (jetpacs-reminders-projection-p "app"))
    (should-not (jetpacs--owned-names "reminder-projection" "app"))
    (should (memq #'jetpacs-reminders--after-push
                  jetpacs-shell-after-push-hook))
    (jetpacs-reminders-remove-projection "other")
    (should-not (memq #'jetpacs-reminders--after-push
                      jetpacs-shell-after-push-hook))
    ;; Unloading never clears what the device holds.
    (should-not calls)))

(ert-deftest jetpacs-reminders-replaced-projection-ignores-a-late-confirm ()
  "A confirmation for a replaced projection does not seed the new cache."
  (jetpacs-reminders-test--with-device calls
    (let (pending)
      (cl-letf (((symbol-function 'jetpacs-reminders-set)
                 (cl-function
                  (lambda (reminders &key owner callback)
                    (push (cons owner reminders) calls)
                    (setq pending callback)))))
        (jetpacs-reminders-define-projection "app" (lambda () nil))
        (jetpacs-reminders-sync-now "app")
        (jetpacs-reminders-define-projection "app" (lambda () nil))
        (funcall pending 0 nil)
        (should (eq (plist-get (gethash "app" jetpacs-reminders--projections)
                               :last)
                    'unset))))))

(provide 'jetpacs-reminders-test)
;;; jetpacs-reminders-test.el ends here
