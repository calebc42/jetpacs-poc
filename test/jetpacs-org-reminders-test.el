;;; jetpacs-org-reminders-test.el --- ERT for Org reminders -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-org-mode)
(require 'glasspane)

(ert-deftest jetpacs-org-reminders-owner-and-rollout-defaults ()
  "The canonical pipeline owns org-mode and is enabled by default."
  (should (equal jetpacs-org-reminders-owner "org-mode"))
  (should (custom-variable-p 'jetpacs-org-reminders-horizon-hours))
  (should (= (default-value 'jetpacs-org-reminders-horizon-hours) 24))
  (should (default-value 'jetpacs-org-reminders-enabled)))

(ert-deftest jetpacs-org-reminders-agenda-extractor-is-shared-and-memoised ()
  "Apps share the one extractor cached under org-mode."
  (should (eq (symbol-function 'jetpacs-org-mode-agenda-items)
              'jetpacs-org-mode--agenda-items))
  (should (eq (symbol-function 'jetpacs-org-mode-agenda-scope)
              'jetpacs-org-mode--agenda-scope))
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
            (should (equal (jetpacs-org-mode-agenda-items 'day) fixture))
            (should (= calls 1))
            ;; Invalidation is whole-table: any namespace named, or
            ;; none, evicts the canonical projection.
            (jetpacs-org-mode-agenda-items 'day)
            (should (= calls 1))
            (ebp-org-cache-invalidate 'glasspane)
            (jetpacs-org-mode-agenda-items 'day)
            (should (= calls 2))
            (ebp-org-cache-invalidate)
            (jetpacs-org-mode-agenda-items 'day)
            (should (= calls 3))))
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
  (let ((jetpacs-reminders--projections (make-hash-table :test #'equal))
        (jetpacs-org-reminders-enabled t)
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
      (unwind-protect
          (progn
            (jetpacs-org-reminders-register)
            (jetpacs-reminders-sync-now "org-mode")
            (jetpacs-reminders-sync-now "org-mode")
            (should (equal calls `(("org-mode" . ,fixture))))
            ;; Defining again forgets the confirmed set.
            (jetpacs-org-reminders-register)
            (setq connected nil)
            (jetpacs-reminders-sync-now "org-mode")
            (setq connected t
                  granted nil)
            (jetpacs-reminders-sync-now "org-mode")
            (should (= (length calls) 1))
            (setq granted t)
            (jetpacs-reminders-sync-now "org-mode")
            (should (= (length calls) 2)))
        (jetpacs-org-reminders-unregister)))))

(ert-deftest jetpacs-org-reminders-scan-error-preserves-durable-set ()
  "A failed agenda scan must not be mistaken for an authoritative empty set."
  (let ((jetpacs-reminders--projections (make-hash-table :test #'equal))
        (jetpacs-org-reminders-enabled t)
        (calls 0))
    (cl-letf (((symbol-function 'jetpacs-connected-p) (lambda () t))
              ((symbol-function 'jetpacs-granted-p) (lambda (&rest _) t))
              ((symbol-function 'jetpacs-error-label) (lambda (_) "scan"))
              ((symbol-function 'jetpacs-org-reminders--upcoming-reminders)
               (lambda (&rest _) (error "fixture failure")))
              ((symbol-function 'jetpacs-reminders-set)
               (lambda (&rest _) (cl-incf calls))))
      (unwind-protect
          (progn
            (jetpacs-org-reminders-register)
            (jetpacs-reminders-sync-now "org-mode")
            (should (= calls 0)))
        (jetpacs-org-reminders-unregister)))))

(ert-deftest jetpacs-org-reminders-hook-singleton-across-all-flags ()
  "The rollout flag alone decides whether the Org projection exists."
  (unwind-protect
      (progn
        (jetpacs-org-reminders-unregister)
        (let ((jetpacs-org-reminders-enabled nil))
          (jetpacs-org-mode-register)
          (glasspane-agenda-register)
          (should-not (jetpacs-reminders-projection-p "org-mode")))
        (let ((jetpacs-org-reminders-enabled t))
          (jetpacs-org-mode-register)
          (glasspane-agenda-register)
          (should (jetpacs-reminders-projection-p "org-mode"))
          (should (memq #'jetpacs-reminders--after-push
                        jetpacs-shell-after-push-hook))))
    (jetpacs-org-reminders-unregister)
    (let ((jetpacs-org-reminders-enabled t))
      (jetpacs-org-mode-register)
      (glasspane-agenda-register))))

(defmacro jetpacs-org-reminders-test--with-vault (file content &rest body)
  "Bind FILE to a fresh vault's tasks.org holding CONTENT around BODY.
The vault is the only org root and the only agenda file."
  (declare (indent 2))
  `(let* ((vault (file-truename (make-temp-file "jetpacs-extract" t)))
          (,file (expand-file-name "tasks.org" vault))
          (org-agenda-files (list ,file))
          (ebp-org-roots (list vault)))
     (unwind-protect
         (progn
           (with-temp-file ,file (insert ,content))
           (ebp-org-cache-invalidate)
           ,@body)
       (ebp-org-cache-invalidate)
       (dolist (buffer (buffer-list))
         (when-let* ((name (buffer-file-name buffer))
                     ((string-prefix-p (file-name-as-directory vault)
                                       (file-truename name))))
           (with-current-buffer buffer (set-buffer-modified-p nil))
           (kill-buffer buffer)))
       (delete-directory vault t))))

(ert-deftest jetpacs-org-mode-todo-items-sweep-shape-and-memo ()
  "The built-in TODO sweep answers the one item shape over the given
files (or the agenda scope), and memoises it."
  (jetpacs-org-reminders-test--with-vault file
      "#+TITLE: Tasks\n\n* TODO Water the garden :home:\n** Sub\n* TODO Call the bank\nSCHEDULED: <2026-08-16 Sun>\n* Reference notes\n"
    (let ((items (jetpacs-org-mode-todo-items (list file))))
      (should (equal (mapcar (lambda (it) (alist-get 'headline it)) items)
                     '("Water the garden" "Call the bank")))
      (let ((first (car items)) (second (cadr items)))
        (should (equal (alist-get 'todo first) "TODO"))
        (should (equal (append (alist-get 'tags first) nil) '("home")))
        (should (= 1 (alist-get 'level first)))
        (should (equal (alist-get 'file first) file))
        (should (integerp (alist-get 'pos first)))
        (should (equal (plist-get (alist-get 'ref first) :headline)
                       "Water the garden"))
        (should (equal (alist-get 'scheduled second) "<2026-08-16 Sun>"))))
    ;; Nil FILES means the canonical agenda scope.
    (should (= 2 (length (jetpacs-org-mode-todo-items))))
    ;; The same shape at point.
    (with-current-buffer (find-file-noselect file)
      (org-with-wide-buffer
       (goto-char (point-min))
       (re-search-forward "Call the bank")
       (org-back-to-heading t)
       (let ((item (jetpacs-org-mode-heading-item-at)))
         (should (equal (alist-get 'headline item) "Call the bank"))
         (should (equal (alist-get 'todo item) "TODO"))
         (should (= (alist-get 'pos item) (point))))))
    ;; Memoised: a repeat call hits; any invalidation (whole-table, the
    ;; namespace argument is ignored) evicts it.
    (let ((calls 0))
      (cl-letf (((symbol-function 'jetpacs-org-mode--todo-items-1)
                 (lambda (&rest _) (cl-incf calls) '(fixture))))
        (ebp-org-cache-invalidate)
        (should (equal (jetpacs-org-mode-todo-items (list file)) '(fixture)))
        (jetpacs-org-mode-todo-items (list file))
        (should (= calls 1))
        (ebp-org-cache-invalidate 'other-app)
        (jetpacs-org-mode-todo-items (list file))
        (should (= calls 2))))))

(ert-deftest jetpacs-org-mode-all-tags-joins-vocabulary-and-files ()
  "The tag vocabulary is `org-tag-alist' plus every tag used in the
agenda scope, sorted and deduplicated."
  (jetpacs-org-reminders-test--with-vault file
      "* TODO Water :home:\n* Note :zulu:work:\n"
    (let ((org-tag-alist '(("alpha" . ?a) "zulu" (:startgroup) (:endgroup))))
      (should (equal (jetpacs-org-mode-all-tags)
                     '("alpha" "home" "work" "zulu"))))))

(ert-deftest jetpacs-org-mode-tag-strings-and-tag-group ()
  "Propertized tag vectors clean to distinct strings; group lookup is
case-insensitive and keeps declaration precedence."
  (should (equal (jetpacs-org-mode-tag-strings
                  (vector (propertize "home" 'inherited t) " work " "" "home"))
                 '("home" "work")))
  (should (equal (jetpacs-org-mode-tag-strings "solo") '("solo")))
  (should-not (jetpacs-org-mode-tag-strings nil))
  (let ((groups '((:name "Area" :tags ("home" "work"))
                  (:name "area" :tags ("other")))))
    (should (equal (plist-get (jetpacs-org-mode-tag-group groups "AREA") :tags)
                   '("home" "work")))
    (should-not (jetpacs-org-mode-tag-group groups "missing"))
    (should-not (jetpacs-org-mode-tag-group groups nil))))

(ert-deftest jetpacs-org-mode-file-tag-group-index-honors-inherit ()
  "The per-file group index walks the file once per generation, keys its
memo on the INHERIT policy and the tag settings, and answers nil for a
file outside the roots."
  (jetpacs-org-reminders-test--with-vault file
      "#+TAGS: [ area : home work ]\n#+FILETAGS: :home:\n* Parent :work:\n** Child\n* Loose :misc:\n"
    (let ((org-use-tag-inheritance nil))
      (let ((plain (jetpacs-org-mode-file-tag-group-index file "area"))
            (inherited (jetpacs-org-mode-file-tag-group-index file "area" t)))
        (should (equal (plist-get plain :members) '("home" "work")))
        (should (equal (plist-get plain :file-tags) '("home")))
        (should (equal (plist-get inherited :file-tags) '("home")))
        ;; Without inheritance only the local tag counts; with the PARA
        ;; rule the file tag and the parent's tag reach the child.
        (let ((positions (plist-get plain :positions))
              (positions+ (plist-get inherited :positions)))
          (should (= 3 (length positions)))
          (should (equal (cdr (nth 0 positions)) '("work")))
          (should-not (cdr (nth 1 positions)))
          (should (equal (cdr (nth 0 positions+)) '("home" "work")))
          (should (equal (cdr (nth 1 positions+)) '("home" "work")))
          (should (equal (cdr (nth 2 positions+)) '("home")))))
      ;; Memo: one walk per (file, group, policy) generation.
      (let ((walks 0))
        (cl-letf (((symbol-function 'org-map-entries)
                   (lambda (&rest _) (cl-incf walks) nil)))
          (jetpacs-org-mode-file-tag-group-index file "area")
          (jetpacs-org-mode-file-tag-group-index file "area" t)
          (should (= walks 0))
          (ebp-org-cache-invalidate 'org-mode)
          (jetpacs-org-mode-file-tag-group-index file "area")
          (jetpacs-org-mode-file-tag-group-index file "area")
          (should (= walks 1)))))
    (should-not (jetpacs-org-mode-file-tag-group-index "/nonexistent/x.org" "area"))
    (should-not (jetpacs-org-mode-file-tag-group-index file ""))))

(ert-deftest jetpacs-org-tokenize-filters-caps-and-attaches ()
  "Allowed refs get a tap token (and an archive token from OWNER-SET in
ARCHIVE-OWNER's scope); a ref outside the roots stays untappable; the
set is capped; an owner is required."
  (jetpacs-org-reminders-test--with-vault file "* TODO A\n* TODO B\n"
    (unwind-protect
        (let* ((inside (list (cons 'ref (list :file file :pos 1
                                              :headline "A"))))
               (outside (list (cons 'ref (list :file "/elsewhere/x.org"
                                               :pos 1 :headline "X"))))
               (out (jetpacs-org-tokenize (list inside outside) "t-set"
                                          :owner "alpha"
                                          :archive-owner "beta")))
          (should (stringp (alist-get 'token (car out))))
          (should (stringp (alist-get 'archive-token (car out))))
          (should (ebp-org-token-ref (alist-get 'token (car out))
                                     :owner "alpha"))
          (should (ebp-org-token-ref (alist-get 'archive-token (car out))
                                     :owner "beta"))
          (should (eq (cadr out) outside))
          ;; Tap only: no archive cell.
          (let ((tap (jetpacs-org-tokenize (list inside) "t-tap"
                                           :owner "alpha")))
            (should (alist-get 'token (car tap)))
            (should-not (assq 'archive-token (car tap))))
          ;; Capped at the per-set maximum.
          (let* ((ebp-org-token-set-max 1)
                 (both (jetpacs-org-tokenize (list inside (copy-tree inside))
                                             "t-cap" :owner "alpha")))
            (should (assq 'token (car both)))
            (should-not (assq 'token (cadr both))))
          (should-error (jetpacs-org-tokenize nil "t-none")))
      (ebp-org-reset))))

(ert-deftest jetpacs-org-agenda-formatters-are-the-glasspane-shapes ()
  "The lifted formatters answer what Glasspane's cards rendered."
  (should (equal (jetpacs-org-agenda-item-meta
                  '((extra . "Scheduled:") (file . "/v/work.org")) nil)
                 "work.org"))
  (should (equal (jetpacs-org-agenda-item-meta
                  '((extra . "Sched. 3x:") (file . "/v/work.org")) nil)
                 "Sched. 3x · work.org"))
  (should (equal (jetpacs-org-agenda-item-meta '((file . "/v/a.org")) "09:30")
                 "09:30 · a.org"))
  (should (equal (jetpacs-org-agenda-item-meta
                  '((extra . "In 3 d.") (file . "/v/t.org")) "09:15")
                 "09:15 · t.org"))
  (should (equal (jetpacs-org-agenda-item-meta '((extra . "Deadline")) nil)
                 ""))
  (should (equal (jetpacs-org-agenda-type-icon "past-scheduled")
                 '("history" . "error")))
  (should (equal (jetpacs-org-agenda-type-icon "upcoming-deadline")
                 '("flag")))
  (should-not (jetpacs-org-agenda-type-icon "timestamp"))
  (should (equal (jetpacs-org-agenda-type-label "past-scheduled")
                 "overdue"))
  (should (equal (jetpacs-org-agenda-type-label "upcoming-deadline")
                 "deadline soon"))
  (should-not (jetpacs-org-agenda-type-label "block")))

(provide 'jetpacs-org-reminders-test)
;;; jetpacs-org-reminders-test.el ends here
