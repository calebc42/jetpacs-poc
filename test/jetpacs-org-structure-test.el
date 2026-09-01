;;; jetpacs-org-structure-test.el --- Private Org structure sessions -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'org)
(require 'jetpacs-org-structure)

(defmacro jetpacs-org-structure-test--with-files (bindings &rest body)
  "Create Org files from BINDINGS and run BODY inside their private root.
Each binding is (VARIABLE NAME CONTENT)."
  (declare (indent 1) (debug t))
  (let ((root (make-symbol "root")))
    `(let* ((,root (make-temp-file "jetpacs-org-structure" t))
            ,@(mapcar
               (lambda (binding)
                 `(,(nth 0 binding)
                   (expand-file-name ,(nth 1 binding) ,root)))
               bindings)
            (ebp-org-roots (list ,root))
            (ebp-org-file-save-function
             (lambda (buffer)
               (with-current-buffer buffer (save-buffer))
               t)))
       (unwind-protect
           (progn
             ,@(mapcar
                (lambda (binding)
                  `(write-region ,(nth 2 binding) nil ,(nth 0 binding)
                                 nil 'silent))
                bindings)
             (jetpacs-org-structure-reset)
             ,@body)
         (jetpacs-org-structure-reset)
         (dolist (file (list ,@(mapcar #'car bindings)))
           (when-let* ((buffer (find-buffer-visiting file)))
             (with-current-buffer buffer (set-buffer-modified-p nil))
             (kill-buffer buffer)))
         (delete-directory ,root t)))))

(defun jetpacs-org-structure-test--ref (file headline)
  "Return FILE's private Org ref for HEADLINE."
  (with-current-buffer (find-file-noselect file t)
    (unless (derived-mode-p 'org-mode) (org-mode))
    (org-with-wide-buffer
     (goto-char (point-min))
     (search-forward headline)
     (org-back-to-heading t)
     (ebp-org-ref-at-point))))

(ert-deftest jetpacs-org-structure-one-level-undo-is-drift-checked ()
  "A structural mutation has one undo, which later buffer drift invalidates."
  (jetpacs-org-structure-test--with-files
      ((file "notes.org" "* Alpha\n* Omega\n"))
    (let ((original "* Alpha\n* Omega\n")
          (ref (jetpacs-org-structure-test--ref file "Alpha")))
      (jetpacs-org-structure-insert
       "owner-a" ref "test" 'after "Beta")
      (should (equal (jetpacs-org-structure-undo-summary "owner-a")
                     '(:available t :label "Heading inserted")))
      (should (plist-get (jetpacs-org-structure-undo "owner-a" "test")
                         :changed))
      (with-current-buffer (find-file-noselect file t)
        (should (equal (buffer-string) original)))
      (should-not (jetpacs-org-structure-undo-summary "owner-a"))
      (jetpacs-org-structure-insert
       "owner-a" ref "test" 'after "Beta")
      (with-current-buffer (find-file-noselect file t)
        (goto-char (point-max))
        (insert "Local drift\n"))
      (should-error (jetpacs-org-structure-undo "owner-a" "test")
                    :type 'ebp-org-unresolved)
      (should-not (jetpacs-org-structure-undo-summary "owner-a")))))

(ert-deftest jetpacs-org-structure-clipboard-is-owner-private-and-stale-safe ()
  "Clipboard summaries expose no ref, and source edits consume stale clips."
  (jetpacs-org-structure-test--with-files
      ((file "notes.org" "* Alpha\nBody\n* Target\n"))
    (let ((ref (jetpacs-org-structure-test--ref file "Alpha")))
      (should (equal (jetpacs-org-structure-copy "owner-a" ref)
                     '(:available t :mode copy)))
      (let ((summary
             (jetpacs-org-structure-clipboard-summary "owner-a")))
        (should (equal summary '(:available t :mode copy)))
        (should-not (string-search file (prin1-to-string summary))))
      (should-not (jetpacs-org-structure-clipboard-summary "owner-b"))
      (ebp-org-set-property ref "test" "MOOD" "changed")
      (should-error
       (jetpacs-org-structure-paste
        "owner-a"
        (list :file file
              :parent (jetpacs-org-structure-test--ref file "Target"))
        "test")
       :type 'ebp-org-unresolved)
      (should-not (jetpacs-org-structure-clipboard-summary "owner-a")))))

(ert-deftest jetpacs-org-structure-cut-paste-undo-restores-both-files ()
  "Cut is delayed until paste, consumed once, and undone source-first."
  (jetpacs-org-structure-test--with-files
      ((source "source.org"
               "* Move\n:PROPERTIES:\n:ID: move-id\n:END:\nBody\n")
       (target "target.org" "* Target\n"))
    (let* ((source-before
            "* Move\n:PROPERTIES:\n:ID: move-id\n:END:\nBody\n")
           (target-before "* Target\n")
           (source-ref (jetpacs-org-structure-test--ref source "Move"))
           (target-ref (jetpacs-org-structure-test--ref target "Target"))
           (save-order nil)
           (ebp-org-file-save-function
            (lambda (buffer)
              (setq save-order
                    (append save-order
                            (list (file-name-nondirectory
                                   (buffer-file-name buffer)))))
              (with-current-buffer buffer (save-buffer))
              t)))
      (jetpacs-org-structure-cut "owner" source-ref)
      (should (equal (jetpacs-org-structure-clipboard-summary "owner")
                     '(:available t :mode cut)))
      (jetpacs-org-structure-paste
       "owner" (list :file target :parent target-ref) "test")
      (should-not (jetpacs-org-structure-clipboard-summary "owner"))
      (should (equal (jetpacs-org-structure-undo-summary "owner")
                     '(:available t :label "Heading moved")))
      (setq save-order nil)
      (let ((outcome (jetpacs-org-structure-undo "owner" "test")))
        (should (plist-get outcome :changed))
        (should-not (plist-get outcome :pending-save)))
      (should (equal save-order '("source.org" "target.org")))
      (with-current-buffer (find-file-noselect source t)
        (should (equal (buffer-string) source-before)))
      (with-current-buffer (find-file-noselect target t)
        (should (equal (buffer-string) target-before))))))

(ert-deftest jetpacs-org-structure-copy-paste-retains-clipboard ()
  "Copy can paste repeatedly while undo removes only the newest target copy."
  (jetpacs-org-structure-test--with-files
      ((source "source.org" "* Copy\n:PROPERTIES:\n:ID: copy-id\n:END:\n")
       (target "target.org" "* Target\n"))
    (let ((source-ref (jetpacs-org-structure-test--ref source "Copy"))
          (target-ref (jetpacs-org-structure-test--ref target "Target")))
      (jetpacs-org-structure-copy "owner" source-ref)
      (jetpacs-org-structure-paste
       "owner" (list :file target :parent target-ref) "test")
      (should (equal (jetpacs-org-structure-clipboard-summary "owner")
                     '(:available t :mode copy)))
      (with-current-buffer (find-file-noselect target t)
        (goto-char (point-min))
        (should (search-forward "Copy" nil t))
        (should-not (search-forward ":ID: copy-id" nil t)))
      (jetpacs-org-structure-undo "owner" "test")
      (with-current-buffer (find-file-noselect target t)
        (should (equal (buffer-string) "* Target\n"))))))

(ert-deftest jetpacs-org-structure-focus-is-session-only-and-revision-bound ()
  "Presentation focus returns only while its exact subtree is unchanged."
  (jetpacs-org-structure-test--with-files
      ((file "notes.org" "* Parent\n** Child\n"))
    (let ((ref (jetpacs-org-structure-test--ref file "Parent")))
      (should (jetpacs-org-structure-focus-set "owner" "outline" ref))
      (should (equal (plist-get
                      (jetpacs-org-structure-focus-current
                       "owner" "outline")
                      :headline)
                     "Parent"))
      (ebp-org-set-property ref "test" "MOOD" "changed")
      (should-not
       (jetpacs-org-structure-focus-current "owner" "outline"))
      (should-not
       (jetpacs-org-structure-focus-current "owner" "outline")))))

(provide 'jetpacs-org-structure-test)
;;; jetpacs-org-structure-test.el ends here
