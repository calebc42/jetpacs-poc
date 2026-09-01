;;; jetpacs-org-reminders-test.el --- ERT for Org reminders -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-org-mode)
(require 'glasspane)

(ert-deftest jetpacs-org-reminders-owner-and-rollout-defaults ()
  "The canonical pipeline owns org-mode and remains opt-in at merge."
  (should (equal jetpacs-org-reminders-owner "org-mode"))
  (should (custom-variable-p 'jetpacs-org-reminders-horizon-hours))
  (should (= (default-value 'jetpacs-org-reminders-horizon-hours) 24))
  (should-not (default-value 'jetpacs-org-reminders-enabled)))

(ert-deftest jetpacs-org-reminders-agenda-extractor-is-shared-and-memoised ()
  "Glasspane delegates to the one extractor cached under org-mode."
  (should (eq (symbol-function 'jetpacs-org-mode-agenda-items)
              'jetpacs-org-mode--agenda-items))
  (should (eq (symbol-function 'jetpacs-org-mode-agenda-scope)
              'jetpacs-org-mode--agenda-scope))
  (should (eq (symbol-function 'glasspane-org--agenda-scope)
              'jetpacs-org-mode--agenda-scope))
  (should (eq (symbol-function 'glasspane-org-agenda-scope)
              'jetpacs-org-mode-agenda-scope))
  (should (eq (symbol-function 'glasspane-org-agenda-items)
              'jetpacs-org-mode--agenda-items))
  (should (eq (symbol-function 'glasspane-org--agenda-items-1)
              'jetpacs-org-mode--agenda-items-1))
  (let ((org-agenda-files nil)
        (calls 0)
        (fixture '(((headline . "Rich")
                    (todo . "TODO")
                    (priority . "A")
                    (tags . ["work"])
                    (extra . "Scheduled:")
                    (ts-date . 740000)
                    (date . "2026-08-16")
                    (ref . (:file "/v/a.org" :pos 4 :headline "Rich"))))))
    (unwind-protect
        (progn
          (ebp-org-cache-invalidate)
          (cl-letf (((symbol-function 'jetpacs-org-mode--agenda-items-1)
                     (lambda (&rest _)
                       (cl-incf calls)
                       fixture)))
            (should (equal (jetpacs-org-mode--agenda-items 'day) fixture))
            (should (equal (glasspane-org-agenda-items 'day) fixture))
            (should (= calls 1))
            ;; Clearing Glasspane's namespace cannot evict the canonical
            ;; projection; clearing Org Mode's namespace must do so.
            (ebp-org-cache-invalidate 'glasspane)
            (glasspane-org-agenda-items 'day)
            (should (= calls 1))
            (ebp-org-cache-invalidate 'org-mode)
            (glasspane-org-agenda-items 'day)
            (should (= calls 2))))
      (ebp-org-cache-invalidate))))

(ert-deftest jetpacs-org-reminders-horizon-dedupe-and-id-shape ()
  "Only in-horizon timed rows arm, with one owner-shaped id per instant."
  (let* ((now (encode-time 0 0 12 16 8 2026))
         (items
          '(((headline . "Water the garden")
             (time . "13:30......") (date . "2026-08-16")
             (type . "scheduled") (file . "/v/my tasks.org") (pos . 42))
            ((headline . "Water the garden")
             (time . "13:30......") (date . "2026-08-16")
             (type . "deadline") (file . "/v/my tasks.org") (pos . 42))
            ((headline . "Date only") (date . "2026-08-16")
             (type . "scheduled") (file . "/v/my tasks.org") (pos . 90))
            ((headline . "Far")
             (time . "15:00") (date . "2026-08-16")
             (type . "scheduled") (file . "/v/far.org") (pos . 7)))))
    (cl-letf (((symbol-function 'jetpacs-org-mode--agenda-items)
               (lambda (&rest _) items)))
      (let* ((reminders
              (jetpacs-org-reminders--upcoming-reminders 2 now))
             (reminder (car reminders)))
        (should (= (length reminders) 1))
        (should (equal (plist-get reminder :title) "Water the garden"))
        (should (equal (plist-get reminder :body) "13:30 · scheduled"))
        (should (jetpacs-identifier-p (plist-get reminder :id)))
        (should
         (string-match-p
          "\\`org-mode\\.rem-[A-Za-z0-9._:/-]+-[[:xdigit:]]\\{8\\}\\'"
          (plist-get reminder :id))))
      ;; The absorbed defcustom controls the default horizon.
      (let ((jetpacs-org-reminders-horizon-hours 4))
        (should (= (length
                    (jetpacs-org-reminders--upcoming-reminders nil now))
                   2))))))

(ert-deftest jetpacs-org-reminders-sync-gates-and-diffs-confirmed-set ()
  "The active owner sends only when connected/granted and suppresses repeats."
  (let ((jetpacs-org-reminders-enabled t)
        (jetpacs-org-reminders--last-reminders 'unset)
        (connected t)
        (granted t)
        (calls nil)
        (fixture '((:id "org-mode.rem-a-12345678"
                    :at_ms 42 :title "A"))))
    (cl-letf (((symbol-function 'jetpacs-connected-p)
               (lambda () connected))
              ((symbol-function 'jetpacs-granted-p)
               (lambda (&rest _) granted))
              ((symbol-function 'jetpacs-org-reminders--upcoming-reminders)
               (lambda (&rest _) fixture))
              ((symbol-function 'jetpacs-reminders-set)
               (cl-function
                (lambda (reminders &key owner callback)
                  (push (cons owner reminders) calls)
                  (funcall callback (length reminders) nil)))))
      (jetpacs-org-reminders--sync-reminders)
      (jetpacs-org-reminders--sync-reminders)
      (should (equal calls `(("org-mode" . ,fixture))))
      (setq jetpacs-org-reminders--last-reminders 'unset
            connected nil)
      (jetpacs-org-reminders--sync-reminders)
      (setq connected t
            granted nil)
      (jetpacs-org-reminders--sync-reminders)
      (should (= (length calls) 1)))))

(ert-deftest jetpacs-org-reminders-scan-error-preserves-durable-set ()
  "A failed agenda scan must not be mistaken for an authoritative empty set."
  (let ((jetpacs-org-reminders-enabled t)
        (jetpacs-org-reminders--last-reminders
         '((:id "org-mode.rem-existing-12345678"
            :at_ms 42 :title "Existing")))
        (calls 0))
    (cl-letf (((symbol-function 'jetpacs-connected-p) (lambda () t))
              ((symbol-function 'jetpacs-granted-p) (lambda (&rest _) t))
              ((symbol-function 'jetpacs-error-label) (lambda (_) "scan"))
              ((symbol-function 'jetpacs-org-reminders--upcoming-reminders)
               (lambda (&rest _) (error "fixture failure")))
              ((symbol-function 'jetpacs-reminders-set)
               (lambda (&rest _) (cl-incf calls))))
      (jetpacs-org-reminders--sync-reminders)
      (should (= calls 0))
      (should
       (equal jetpacs-org-reminders--last-reminders
              '((:id "org-mode.rem-existing-12345678"
                 :at_ms 42 :title "Existing")))))))

(ert-deftest jetpacs-org-reminders-hook-singleton-across-all-flags ()
  "Every three-pipeline flag combination selects at most one live hook."
  (let ((hooks (list #'jetpacs-org-reminders--sync-reminders
                     #'jetpacs-org-mode--sync-reminders
                     #'glasspane-agenda--sync-reminders)))
    (cl-labels
        ((live-hooks ()
           (cl-remove-if-not
            (lambda (hook) (memq hook jetpacs-shell-after-push-hook))
            hooks))
         (clear-reminder-hooks ()
           (jetpacs-org-reminders-unregister)
           (glasspane-agenda-unregister)
           (remove-hook 'jetpacs-shell-after-push-hook
                        #'jetpacs-org-mode--sync-reminders)))
      (unwind-protect
          (dolist (new '(nil t))
            (dolist (legacy '(nil t))
              (dolist (glass '(nil t))
                (clear-reminder-hooks)
                (let ((jetpacs-org-reminders-enabled new)
                      (jetpacs-org-mode-reminders-enabled legacy)
                      (glasspane-agenda-reminders-enabled glass))
                  ;; Exercise the actual composition-root registration order.
                  (jetpacs-org-mode-register)
                  (glasspane-agenda-register)
                  (let ((live (live-hooks)))
                    (should-not
                     (memq #'jetpacs-org-mode--sync-reminders live))
                    (should (<= (length live) 1))
                    (should
                     (equal live
                            (cond
                             (new
                              (list
                               #'jetpacs-org-reminders--sync-reminders))
                             (glass
                              (list #'glasspane-agenda--sync-reminders))))))))))
        (clear-reminder-hooks)
        (let ((jetpacs-org-reminders-enabled nil)
              (jetpacs-org-mode-reminders-enabled nil)
              (glasspane-agenda-reminders-enabled t))
          (jetpacs-org-mode-register)
          (glasspane-agenda-register))))))

(provide 'jetpacs-org-reminders-test)
;;; jetpacs-org-reminders-test.el ends here
