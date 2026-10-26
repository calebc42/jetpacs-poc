;;; jetpacs-org-heading-verbs-test.el --- Token-addressed heading verbs -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; Each handler `jetpacs-org-heading-verb' builds resolves its token in
;; the configured scope, mutates through the vanilla Org core, saves
;; through the configured funnel, notifies, refreshes, and answers a
;; SPEC 14.4 status.  The fixture is a temp Org file inside the Org
;; roots; continuations run immediately so effects are observable.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-org-heading-verbs)

(defvar jetpacs-org-heading-verbs-test--notes nil "Notifications, newest first.")
(defvar jetpacs-org-heading-verbs-test--refreshes 0 "Refresh count.")
(defvar jetpacs-org-heading-verbs-test--saved nil "Buffers saved, newest first.")
(defvar jetpacs-org-heading-verbs-test--left nil "Leave calls, newest first.")

(defmacro jetpacs-org-heading-verbs-test--with-file (content &rest body)
  "Run BODY with `file' holding CONTENT and `tokens' naming its headings.
Tokens are minted in owner \"testapp\", one per heading in order."
  (declare (indent 1))
  `(let* ((dir (make-temp-file "jetpacs-heading-verbs" t))
          (file (expand-file-name "tasks.org" dir))
          (ebp-org-roots (list dir))
          (org-todo-keywords '((sequence "TODO" "NEXT" "|" "DONE")))
          (org-log-done nil)
          (org-log-reschedule nil)
          (jetpacs-org-heading-verbs-test--notes nil)
          (jetpacs-org-heading-verbs-test--refreshes 0)
          (jetpacs-org-heading-verbs-test--saved nil)
          (jetpacs-org-heading-verbs-test--left nil)
          tokens)
     (unwind-protect
         (progn
           (write-region ,content nil file nil 'silent)
           (with-current-buffer (find-file-noselect file)
             (org-mode)
             (let (refs)
               (org-map-entries (lambda () (push (ebp-org-ref-at-point) refs)))
               (setq tokens (ebp-org-ref-tokens (nreverse refs)
                                                :set "t" :owner "testapp"))))
           (cl-letf (((symbol-function 'jetpacs-flow-continue)
                      (lambda (fn) (funcall fn)))
                     ((symbol-function 'jetpacs-app-defer-refresh)
                      (lambda (&rest _)
                        (cl-incf jetpacs-org-heading-verbs-test--refreshes)))
                     ((symbol-function 'jetpacs-shell-notify)
                      (lambda (text &rest _)
                        (push text jetpacs-org-heading-verbs-test--notes)))
                     ((symbol-function 'jetpacs-shell-push) #'ignore))
             ,@body))
       (when-let* ((buffer (find-buffer-visiting file)))
         (with-current-buffer buffer (set-buffer-modified-p nil))
         (kill-buffer buffer))
       (ebp-org-reset)
       (delete-directory dir t))))

(defun jetpacs-org-heading-verbs-test--verb (verb)
  "VERB's handler in scope \"testapp\" with a recording save and leave."
  (jetpacs-org-heading-verb
   verb :token-owner "testapp"
   :save (lambda (buffer)
           (with-current-buffer buffer (save-buffer))
           (push buffer jetpacs-org-heading-verbs-test--saved))
   :leave (lambda (params)
            (push params jetpacs-org-heading-verbs-test--left))))

(defun jetpacs-org-heading-verbs-test--call (verb args)
  "Call VERB's handler with ARGS on surface \"app:test\"."
  (funcall (jetpacs-org-heading-verbs-test--verb verb)
           args '(:surface "app:test")))

(defun jetpacs-org-heading-verbs-test--disk (file)
  "FILE's text on disk."
  (with-temp-buffer (insert-file-contents file) (buffer-string)))

(ert-deftest jetpacs-org-heading-verbs-todo-saves-notifies-and-refreshes ()
  "Set and cycle write through the save funnel and report the new state."
  (jetpacs-org-heading-verbs-test--with-file "* TODO Water\n* Call\n"
    (should (eq (jetpacs-org-heading-verbs-test--call
                 'todo-set (list :token (nth 0 tokens) :state "DONE"))
                'accepted))
    (should (string-prefix-p "* DONE Water"
                             (jetpacs-org-heading-verbs-test--disk file)))
    (should (equal (car jetpacs-org-heading-verbs-test--notes) "State → DONE"))
    (should (= jetpacs-org-heading-verbs-test--refreshes 1))
    (should (= (length jetpacs-org-heading-verbs-test--saved) 1))
    (should (eq (jetpacs-org-heading-verbs-test--call
                 'todo-cycle (list :token (nth 1 tokens)))
                'accepted))
    (should (equal (car jetpacs-org-heading-verbs-test--notes) "State → TODO"))
    (should (eq (jetpacs-org-heading-verbs-test--call
                 'todo-set (list :token (nth 0 tokens) :state 7))
                'rejected))
    (should (eq (jetpacs-org-heading-verbs-test--call
                 'todo-set (list :token "swept" :state "DONE"))
                'stale))
    (should (= jetpacs-org-heading-verbs-test--refreshes 2))))

(ert-deftest jetpacs-org-heading-verbs-tokens-resolve-only-in-their-scope ()
  "A token minted for another owner is a plain miss."
  (jetpacs-org-heading-verbs-test--with-file "* TODO Water\n"
    (should (eq (funcall (jetpacs-org-heading-verb
                          'todo-set :token-owner "otherapp")
                         (list :token (car tokens) :state "DONE")
                         '(:surface "app:test"))
                'stale))))

(ert-deftest jetpacs-org-heading-verbs-schedule-moves-clears-and-refuses ()
  "Relative and absolute dates move the stamp; :clear removes it."
  (jetpacs-org-heading-verbs-test--with-file
      "* TODO Water\nSCHEDULED: <2026-08-25 Tue 09:00 +1w>\n"
    (should (eq (jetpacs-org-heading-verbs-test--call
                 'schedule (list :token (car tokens) :value "2026-09-01"))
                'accepted))
    (should (string-search "SCHEDULED: <2026-09-01 Tue 09:00 +1w>"
                           (jetpacs-org-heading-verbs-test--disk file)))
    (should (equal (car jetpacs-org-heading-verbs-test--notes)
                   "Scheduled 2026-09-01"))
    (should (eq (jetpacs-org-heading-verbs-test--call
                 'schedule (list :token (car tokens) :type "DEADLINE"
                                 :when "+0d"))
                'accepted))
    (should (string-search (format-time-string "DEADLINE: <%Y-%m-%d")
                           (jetpacs-org-heading-verbs-test--disk file)))
    (should (eq (jetpacs-org-heading-verbs-test--call
                 'schedule (list :token (car tokens) :clear t))
                'accepted))
    (should-not (string-search "SCHEDULED"
                               (jetpacs-org-heading-verbs-test--disk file)))
    (should (equal (car jetpacs-org-heading-verbs-test--notes)
                   "Schedule cleared"))
    (should (eq (jetpacs-org-heading-verbs-test--call
                 'schedule (list :token (car tokens) :clear :json-false))
                'rejected))))

(ert-deftest jetpacs-org-heading-verbs-priority-tags-and-properties ()
  "Priority, tags and properties write; bad tags refuse the whole write."
  (jetpacs-org-heading-verbs-test--with-file "* TODO Water\n"
    (let ((token (car tokens)))
      (should (eq (jetpacs-org-heading-verbs-test--call
                   'priority (list :token token :value "a"))
                  'accepted))
      (should (equal (car jetpacs-org-heading-verbs-test--notes) "Priority A"))
      (should (eq (jetpacs-org-heading-verbs-test--call
                   'tags (list :token token :value ["home" "work"]))
                  'accepted))
      (should (equal (car jetpacs-org-heading-verbs-test--notes)
                     "Tags: home work"))
      (should (eq (jetpacs-org-heading-verbs-test--call
                   'tags (list :token token :value ["ok" "no spaces"]))
                  'rejected))
      (should (eq (jetpacs-org-heading-verbs-test--call
                   'prop-set (list :token token :name "EFFORT" :value ["1:00"]))
                  'accepted))
      (should (equal (car jetpacs-org-heading-verbs-test--notes)
                     "EFFORT → 1:00"))
      (should (eq (jetpacs-org-heading-verbs-test--call
                   'prop-set (list :token token :value "x"))
                  'rejected))
      (let ((disk (jetpacs-org-heading-verbs-test--disk file)))
        (should (string-prefix-p "* TODO [#A] Water" disk))
        (should (string-search ":home:work:" disk))
        (should (string-search ":EFFORT:   1:00" disk))))))

(ert-deftest jetpacs-org-heading-verbs-delete-and-duplicate ()
  "Duplicate refreshes in place; delete saves and then leaves the screen."
  (jetpacs-org-heading-verbs-test--with-file "* TODO Water\n* Call\n"
    (should (eq (jetpacs-org-heading-verbs-test--call
                 'duplicate (list :token (nth 1 tokens)))
                'accepted))
    (should (string-suffix-p "* Call\n* Call\n"
                             (jetpacs-org-heading-verbs-test--disk file)))
    (should (eq (jetpacs-org-heading-verbs-test--call
                 'delete (list :token (nth 0 tokens)))
                'accepted))
    (should (string-prefix-p "* Call" (jetpacs-org-heading-verbs-test--disk file)))
    (should (equal jetpacs-org-heading-verbs-test--left
                   '((:surface "app:test"))))
    (should (equal (car jetpacs-org-heading-verbs-test--notes) "Deleted"))))

(ert-deftest jetpacs-org-heading-verbs-clock-in-saves-nothing ()
  "Clock-in runs at the heading and does not save the buffer."
  (jetpacs-org-heading-verbs-test--with-file "* TODO Water\n"
    (let (clocked)
      (cl-letf (((symbol-function 'org-clock-in)
                 (lambda (&rest _) (setq clocked (org-get-heading t t t t)))))
        (should (eq (jetpacs-org-heading-verbs-test--call
                     'clock-in (list :token (car tokens)))
                    'accepted)))
      (should (equal clocked "Water"))
      (should-not jetpacs-org-heading-verbs-test--saved)
      (should (equal (car jetpacs-org-heading-verbs-test--notes)
                     "Clocked in")))))

(ert-deftest jetpacs-org-heading-verbs-prompting-verbs-gate-on-the-bridge ()
  "Note, property-add and refile answer first, then prompt only when a
dialog can reach the device; missing and swept tokens answer at once."
  (jetpacs-org-heading-verbs-test--with-file "* TODO Water\n"
    (dolist (verb '(add-note prop-add refile))
      (should (eq (jetpacs-org-heading-verbs-test--call verb nil) 'rejected))
      (should (eq (jetpacs-org-heading-verbs-test--call
                   verb (list :token "swept"))
                  'stale)))
    (cl-letf (((symbol-function 'jetpacs-dialog-can-bridge-p) (lambda () nil)))
      (should (eq (jetpacs-org-heading-verbs-test--call
                   'add-note (list :token (car tokens)))
                  'accepted))
      (should (equal (car jetpacs-org-heading-verbs-test--notes)
                     "Dialogs are not available in this session")))
    (cl-letf (((symbol-function 'jetpacs-dialog-can-bridge-p) (lambda () t))
              ((symbol-function 'read-string)
               (lambda (prompt &rest _)
                 (if (string-prefix-p "Note" prompt) "Rinsed" "estimate"))))
      (jetpacs-org-heading-verbs-test--call
       'add-note (list :token (car tokens)))
      (should (equal (car jetpacs-org-heading-verbs-test--notes) "Note added"))
      (jetpacs-org-heading-verbs-test--call
       'prop-add (list :token (car tokens)))
      (should (equal (car jetpacs-org-heading-verbs-test--notes)
                     "Added ESTIMATE — fill in its value")))
    (let ((disk (jetpacs-org-heading-verbs-test--disk file)))
      (should (string-search "Rinsed" disk))
      (should (string-search ":ESTIMATE:" disk)))))

(ert-deftest jetpacs-org-heading-verbs-constructor-validates ()
  "Unknown verbs and a missing token scope are programming errors."
  (should-error (jetpacs-org-heading-verb 'explode :token-owner "a"))
  (should-error (jetpacs-org-heading-verb 'todo-set))
  (dolist (verb jetpacs-org-heading-verbs)
    (should (functionp (jetpacs-org-heading-verb verb :token-owner "a")))))

(provide 'jetpacs-org-heading-verbs-test)
;;; jetpacs-org-heading-verbs-test.el ends here
