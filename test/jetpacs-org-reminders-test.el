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

(ert-deftest jetpacs-org-search-projection-uses-native-text-and-match-modes ()
  "The public projection delegates to Org and returns stable bounded refs."
  (let* ((dir (make-temp-file "jetpacs-org-search" t))
         (first (expand-file-name "a.org" dir))
         (second (expand-file-name "b.org" dir))
         (outside (make-temp-file "jetpacs-org-search-outside" nil ".org"))
         (org-directory dir)
         (org-agenda-files (list second first))
         (org-agenda-text-search-extra-files (list outside))
         (org-todo-keywords '((sequence "TODO" "NEXT" "|" "DONE")))
         (ebp-org-roots (list dir)))
    (unwind-protect
        (progn
          (write-region "* TODO Alpha rocket :work:\nBody needle.\n" nil first
                        nil 'silent)
          (write-region "* NEXT Beta garden :home:\nAnother needle.\n" nil second
                        nil 'silent)
          (write-region "* Outside secret\nneedle\n" nil outside nil 'silent)
          (ebp-org-cache-invalidate)
          (let ((text (jetpacs-org-mode-search-items "needle" 'text 10))
                (match (jetpacs-org-mode-search-items "+work" 'match 10)))
            (should (= (length text) 2))
            (should (equal (mapcar (lambda (row) (alist-get 'headline row))
                                   text)
                           '("Alpha rocket" "Beta garden")))
            (should (= (length match) 1))
            (should (equal (alist-get 'headline (car match)) "Alpha rocket"))
            (should (plist-get (alist-get 'ref (car match)) :file)))
          (should (= (length (jetpacs-org-mode-search-items
                              "needle" 'text 1))
                     1))
          (should-error (jetpacs-org-mode-search-items "" 'text 10)
                        :type 'user-error)
          (should-error (jetpacs-org-mode-search-items "x" 'unknown 10)
                        :type 'user-error))
      (dolist (file (list first second outside))
        (when-let* ((buffer (find-buffer-visiting file)))
          (with-current-buffer buffer (set-buffer-modified-p nil))
          (kill-buffer buffer)))
      (when (file-exists-p outside) (delete-file outside))
      (delete-directory dir t)
      (ebp-org-cache-invalidate))))

(ert-deftest jetpacs-org-custom-views-advertise-only-compatible-commands ()
  "Only bounded, scope-safe, context-free built-in commands are projected."
  (let ((org-agenda-custom-commands
         '(("s" "Needles" search "private matcher")
           ("t" "Work TODOs" tags-todo "+work")
           ("x" "Other scope" search "x"
            ((org-agenda-files '("/tmp/outside.org"))))
           ("f" "Function" ignore "")
           ("b" "Composite" ((agenda "") (alltodo "")))
           ("p" . "Prefix")))
        (org-agenda-custom-commands-contexts
         '(("t" ((in-mode . "org-mode"))))))
    (let ((views (jetpacs-org-mode-custom-views)))
      (should (= (length views) 1))
      (should (equal (plist-get (car views) :label) "Needles"))
      (should (equal (plist-get (car views) :kind) "search"))
      (should (string-prefix-p "org-custom-view-"
                               (plist-get (car views) :id)))
      (should-not (string-search "private matcher"
                                 (prin1-to-string views))))))

(ert-deftest jetpacs-org-custom-view-runs-native-command-in-canonical-scope ()
  "An opaque view preserves Org ordering and cannot search extra files."
  (let* ((dir (make-temp-file "jetpacs-org-custom-view" t))
         (inside (expand-file-name "inside.org" dir))
         (outside (make-temp-file "jetpacs-org-custom-outside" nil ".org"))
         (org-directory dir)
         (org-agenda-files (list inside))
         (org-agenda-text-search-extra-files (list outside))
         (org-agenda-custom-commands
          '(("s" "Needle view" search "needle"
             ((org-agenda-sorting-strategy '(alpha-up))))))
         (org-agenda-custom-commands-contexts nil)
         (ebp-org-roots (list dir)))
    (unwind-protect
        (progn
          (write-region "* Beta\nneedle\n* Alpha\nneedle\n" nil inside
                        nil 'silent)
          (write-region "* Outside secret\nneedle\n" nil outside
                        nil 'silent)
          (let* ((view (car (jetpacs-org-mode-custom-views)))
                 (id (plist-get view :id))
                 (items (jetpacs-org-mode-custom-view-items id 10)))
            (should (equal (mapcar (lambda (row)
                                     (alist-get 'headline row))
                                   items)
                           '("Alpha" "Beta")))
            (should-not (string-search outside (prin1-to-string items)))
            (should (= (length
                        (jetpacs-org-mode-custom-view-items id 1))
                       1))
            (setq org-agenda-custom-commands nil)
            (should-error (jetpacs-org-mode-custom-view-items id 10)
                          :type 'ebp-org-unresolved)))
      (dolist (file (list inside outside))
        (when-let* ((buffer (find-buffer-visiting file)))
          (with-current-buffer buffer (set-buffer-modified-p nil))
          (kill-buffer buffer)))
      (when (file-exists-p outside) (delete-file outside))
      (delete-directory dir t))))

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

(ert-deftest jetpacs-org-reminder-candidates-preserve-ref-and-lead-time ()
  "The neutral seam shifts presentation without changing event eligibility."
  (let* ((now (encode-time 0 0 12 16 8 2026))
         (ref '(:file "/v/private.org" :pos 42 :headline "Water"))
         (items `(((headline . "Water") (time . "12:10")
                   (date . "2026-08-16") (type . "scheduled")
                   (file . "/v/private.org") (pos . 42) (ref . ,ref)))))
    (cl-letf (((symbol-function 'jetpacs-org-mode--agenda-items)
               (lambda (&rest _) items)))
      (let ((candidate
             (car (jetpacs-org-mode-reminder-candidates 1 now 15))))
        (should (equal (plist-get candidate :ref) ref))
        (should (equal (plist-get candidate :headline) "Water"))
        (should (= (- (plist-get candidate :event_at_ms)
                      (plist-get candidate :at_ms))
                   (* 15 60 1000)))
        ;; The lead timestamp is five minutes in the past, but the future
        ;; event remains eligible and Android may present it immediately.
        (should (< (plist-get candidate :at_ms)
                   (truncate (* 1000 (float-time now)))))))))

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
