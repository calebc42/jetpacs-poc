;;; jetpacs-org-heading-verbs.el --- Token-addressed Org heading verb handlers -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The handlers behind an app's heading verbs: set or cycle TODO,
;; schedule, priority, tags, refile, logbook note, delete, duplicate,
;; set or add a property, clock in.  Each one resolves the tapped
;; `:token', runs the vanilla Org core from jetpacs-org-dialogs.el at
;; that heading, saves, notifies and refreshes, and answers a SPEC 14.4
;; status.
;;
;; The app keeps the wire names.  It registers each verb itself with
;; `jetpacs-defaction' under its own owner and passes the handler this
;; file builds, so names a device already queued offline keep resolving
;; and the static applet validator still finds a literal provider for
;; every name the app emits:
;;
;;   (jetpacs-defaction "heading.todo-set"
;;                      (jetpacs-org-heading-verb
;;                       'todo-set :token-owner "myapp" :save #'myapp-save)
;;                      :doc "Set a heading's TODO state; empty clears")
;;
;; What stays the app's: the token scope its builders mint in, its save
;; funnel (a function of the buffer, or t for `jetpacs-org-save-policy'),
;; and what happens to its screen when a heading leaves it (delete,
;; refile).

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'org)
(require 'org-clock)
(require 'ebp-org)
(require 'jetpacs-surfaces)
(require 'jetpacs-shell)
(require 'jetpacs-apps)
(require 'jetpacs-dialog)
(require 'jetpacs-org-dialogs)
(require 'jetpacs-org-save)

(defconst jetpacs-org-heading-verbs
  '(todo-set todo-cycle schedule priority tags refile add-note delete
    duplicate prop-set prop-add clock-in)
  "The heading verbs `jetpacs-org-heading-verb' builds handlers for.")

(defconst jetpacs-org-heading-verbs--refusal
  "This needs the Companion dialog bridge"
  "What a prompting verb says when no dialog can reach the device.")

;;;; Shared steps

(defun jetpacs-org-heading-verbs--ref (config args)
  "ARGS' `:token' resolved in CONFIG's token scope, or nil."
  (ebp-org-token-ref (plist-get args :token)
                     :owner (plist-get config :token-owner)))

(defun jetpacs-org-heading-verbs--at (config args fn &optional no-save)
  "Run FN at ARGS' heading; save through CONFIG unless NO-SAVE.
Answers `jetpacs-org-heading-at-ref''s status."
  (jetpacs-org-heading-at-ref
   (jetpacs-org-heading-verbs--ref config args)
   fn
   (unless no-save (plist-get config :save))))

(defun jetpacs-org-heading-verbs--save-function (config)
  "CONFIG's save as a function of the buffer, for multi-buffer saves."
  (let ((save (plist-get config :save)))
    (if (functionp save) save #'jetpacs-org-save-policy)))

(defun jetpacs-org-heading-verbs--done (status text params)
  "On an `accepted' STATUS notify TEXT and refresh PARAMS' surface.
Return STATUS."
  (when (eq status 'accepted)
    (jetpacs-shell-notify text (plist-get params :surface))
    (jetpacs-app-defer-refresh params))
  status)

(defun jetpacs-org-heading-verbs--leave (config params)
  "Leave PARAMS' screen after its heading stopped existing there.
CONFIG's `:leave' function decides; without one the surface repushes.
Runs outside the dispatch extent."
  (if-let* ((leave (plist-get config :leave)))
      (funcall leave params)
    (ignore-errors (jetpacs-shell-push (plist-get params :surface)))))

(defun jetpacs-org-heading-verbs--flow (config args params fn)
  "Answer for a prompting verb and run FN behind the dialog guard.
ARGS' `:token' resolves in CONFIG's scope: missing is `rejected', swept
is `stale'.  Otherwise FN runs in a flow continuation, only when a prompt
can reach PARAMS' device surface."
  (cond
   ((not (stringp (plist-get args :token))) 'rejected)
   ((null (jetpacs-org-heading-verbs--ref config args)) 'stale)
   (t
    (jetpacs-flow-continue
     (lambda ()
       (jetpacs-dialog-with-prompting
        fn (plist-get params :surface)
        jetpacs-org-heading-verbs--refusal)))
    'accepted)))

;;;; The verbs

(defun jetpacs-org-heading-verbs--todo-set (config args params)
  "Set the TODO state to `:state'; an empty state clears it.
CONFIG, ARGS and PARAMS as for every verb."
  (let ((state (plist-get args :state)))
    (if (not (stringp state))
        'rejected
      (jetpacs-org-heading-verbs--done
       (jetpacs-org-heading-verbs--at
        config args (lambda () (jetpacs-org-heading-set-todo state)))
       (if (string-empty-p state) "State cleared"
         (format "State → %s" state))
       params))))

(defun jetpacs-org-heading-verbs--todo-cycle (config args params)
  "Cycle the heading through its TODO sequence.
CONFIG, ARGS and PARAMS as for every verb."
  (let* ((state nil)
         (status (jetpacs-org-heading-verbs--at
                  config args
                  (lambda () (setq state (jetpacs-org-heading-cycle-todo))))))
    (jetpacs-org-heading-verbs--done
     status (if state (format "State → %s" state) "State cleared") params)))

(defun jetpacs-org-heading-verbs--schedule (config args params)
  "Move the SCHEDULED stamp, or the DEADLINE with `:type' \"DEADLINE\".
`:when' is relative to today (\"+0d\", \"+1d\", \"+1w\"), `:value' an ISO
date, and a true `:clear' removes the stamp.  A moved stamp keeps its
time of day, repeater and delay cookie.  CONFIG, ARGS and PARAMS as for
every verb."
  (let* ((deadline (equal (plist-get args :type) "DEADLINE"))
         (prop (if deadline "DEADLINE" "SCHEDULED"))
         (clear (let ((c (plist-get args :clear)))
                  (and c (not (eq c :json-false)))))
         (when (plist-get args :when))
         (value (plist-get args :value))
         (date (or (and (stringp when) (not (string-empty-p when))
                        (or (jetpacs-org-relative-date
                             (format-time-string "%Y-%m-%d") when)
                            when))
                   (and (stringp value) (not (string-empty-p value)) value))))
    (cond
     (clear
      (jetpacs-org-heading-verbs--done
       (jetpacs-org-heading-verbs--at
        config args (lambda () (jetpacs-org-heading-clear-planning prop)))
       (if deadline "Deadline cleared" "Schedule cleared")
       params))
     (date
      (jetpacs-org-heading-verbs--done
       (jetpacs-org-heading-verbs--at
        config args (lambda () (jetpacs-org-heading-set-planning prop date)))
       (format "%s %s" (if deadline "Deadline" "Scheduled") date)
       params))
     (t 'rejected))))

(defun jetpacs-org-heading-verbs--priority (config args params)
  "Set the priority to `:value'; an empty value removes it.
CONFIG, ARGS and PARAMS as for every verb."
  (let ((value (plist-get args :value)))
    (if (not (stringp value))
        'rejected
      (jetpacs-org-heading-verbs--done
       (jetpacs-org-heading-verbs--at
        config args (lambda () (jetpacs-org-heading-set-priority value)))
       (if (string-empty-p value) "Priority cleared"
         (format "Priority %s" (upcase value)))
       params))))

(defun jetpacs-org-heading-verbs--tags (config args params)
  "Replace the heading's local tags with `:value', a vector or list.
Device-injected strings are 23.1 input: each member must be in Org's own
tag charset or the whole write refuses.  CONFIG, ARGS and PARAMS as for
every verb."
  (let* ((value (plist-get args :value))
         (tags (and (or (vectorp value) (proper-list-p value))
                    (append value nil))))
    (if (not (and (or (vectorp value) (proper-list-p value))
                  (jetpacs-org-tags-valid-p tags)))
        'rejected
      (jetpacs-org-heading-verbs--done
       (jetpacs-org-heading-verbs--at
        config args (lambda () (jetpacs-org-heading-set-tags tags)))
       (if tags (format "Tags: %s" (string-join tags " ")) "Tags cleared")
       params))))

(defun jetpacs-org-heading-verbs--refile (config args params)
  "Refile the whole subtree through a bridged target picker.
Source and target buffers both go through CONFIG's save; the screen is
then left.  CONFIG, ARGS and PARAMS as for every verb."
  (let ((ref (jetpacs-org-heading-verbs--ref config args))
        (surface (plist-get params :surface)))
    (jetpacs-org-heading-verbs--flow
     config args params
     (lambda ()
       (let ((marker (ebp-org-resolve-ref ref)))
         (unwind-protect
             (with-current-buffer (marker-buffer marker)
               (org-with-wide-buffer
                (goto-char marker)
                (let* ((targets (jetpacs-org-heading-refile-targets))
                       (choice (condition-case nil
                                   (completing-read "Refile to: "
                                                    (mapcar #'car targets)
                                                    nil t)
                                 (quit nil)))
                       (target (and choice (assoc choice targets))))
                  (if (not target)
                      (jetpacs-shell-notify "Refile cancelled" surface)
                    (jetpacs-org-heading-refile
                     target (jetpacs-org-heading-verbs--save-function config))
                    (jetpacs-shell-notify (format "Refiled to %s" choice)
                                          surface)))))
           (set-marker marker nil)))
       (jetpacs-org-heading-verbs--leave config params)))))

(defun jetpacs-org-heading-verbs--add-note (config args params)
  "Add a logbook note through a bridged prompt.
CONFIG, ARGS and PARAMS as for every verb."
  (let ((surface (plist-get params :surface)))
    (jetpacs-org-heading-verbs--flow
     config args params
     (lambda ()
       (let ((note (string-trim
                    (condition-case nil (read-string "Note: ")
                      (quit "")))))
         (if (string-empty-p note)
             (jetpacs-shell-notify "Note cancelled" surface)
           (when (eq (jetpacs-org-heading-verbs--at
                      config args
                      (lambda ()
                        (jetpacs-org-heading-insert-note
                         (jetpacs-scalar-text note))))
                     'accepted)
             (jetpacs-shell-notify "Note added" surface)))
         (ignore-errors (jetpacs-shell-push surface)))))))

(defun jetpacs-org-heading-verbs--delete (config args params)
  "Delete the subtree outright, then leave the screen.
The emitting descriptor's `:confirm' already asked on the device.
CONFIG, ARGS and PARAMS as for every verb."
  (let ((status (jetpacs-org-heading-verbs--at
                 config args #'jetpacs-org-heading-delete-subtree)))
    (when (eq status 'accepted)
      (jetpacs-shell-notify "Deleted" (plist-get params :surface))
      (jetpacs-flow-continue
       (lambda () (jetpacs-org-heading-verbs--leave config params))))
    status))

(defun jetpacs-org-heading-verbs--duplicate (config args params)
  "Copy the subtree and insert it right after itself.
CONFIG, ARGS and PARAMS as for every verb."
  (jetpacs-org-heading-verbs--done
   (jetpacs-org-heading-verbs--at
    config args #'jetpacs-org-heading-duplicate-subtree)
   "Duplicated" params))

(defun jetpacs-org-heading-verbs--property-value (raw)
  "The property string a row input's injected RAW value stands for."
  (cond
   ((eq raw t) "t")
   ((memq raw '(nil :json-false)) "nil")
   ((vectorp raw) (if (> (length raw) 0) (format "%s" (aref raw 0)) ""))
   ((proper-list-p raw) (if raw (format "%s" (car raw)) ""))
   ((stringp raw) (string-trim raw))
   (t (format "%s" raw))))

(defun jetpacs-org-heading-verbs--prop-set (config args params)
  "Set property `:name' to the row input's injected `:value'.
An empty value deletes the property.  CONFIG, ARGS and PARAMS as for
every verb."
  (let ((name (plist-get args :name))
        (value (jetpacs-org-heading-verbs--property-value
                (plist-get args :value))))
    (if (not (and (stringp name) (not (string-empty-p name))))
        'rejected
      (jetpacs-org-heading-verbs--done
       (jetpacs-org-heading-verbs--at
        config args (lambda () (jetpacs-org-heading-set-property name value)))
       (if (string-empty-p value) (format "Removed %s" name)
         (format "%s → %s" name value))
       params))))

(defun jetpacs-org-heading-verbs--prop-add (config args params)
  "Ask for a property name through the bridge and add it empty.
The new property then shows as a row whose value is ready to fill in.
CONFIG, ARGS and PARAMS as for every verb."
  (let ((surface (plist-get params :surface)))
    (jetpacs-org-heading-verbs--flow
     config args params
     (lambda ()
       (let ((name (string-trim
                    (condition-case nil
                        (read-string "New property name: ")
                      (quit "")))))
         (cond
          ((string-empty-p name) nil)
          ((not (jetpacs-org-property-name-valid-p name))
           (jetpacs-shell-notify
            "Property names can't contain colons or spaces" surface))
          ((eq (jetpacs-org-heading-verbs--at
                config args (lambda () (org-set-property (upcase name) "")))
               'accepted)
           (jetpacs-shell-notify
            (format "Added %s — fill in its value" (upcase name)) surface)))
         (ignore-errors (jetpacs-shell-push surface)))))))

(defun jetpacs-org-heading-verbs--clock-in (config args params)
  "Clock in at the heading.  Nothing is written, so nothing is saved.
CONFIG, ARGS and PARAMS as for every verb."
  (jetpacs-org-heading-verbs--done
   (jetpacs-org-heading-verbs--at config args #'org-clock-in t)
   "Clocked in" params))

;;;; The constructor

(cl-defun jetpacs-org-heading-verb (verb &key token-owner (save t) leave)
  "Return the action handler for heading VERB.
VERB is a member of `jetpacs-org-heading-verbs'.

TOKEN-OWNER is the owner scope the app's builders mint heading tokens
in; the handler resolves ARGS' `:token' there.  SAVE is how a mutated
buffer is saved: t (the default) for `jetpacs-org-save-policy', or a
function of the buffer, the app's funnel.  LEAVE, a function of the
event PARAMS, runs after `delete' or `refile' took the heading off the
screen; without it the surface repushes.

The handler takes (ARGS PARAMS) and answers a SPEC 14.4 status.
Register it with `jetpacs-defaction' under the app's own owner and wire
name."
  (unless (memq verb jetpacs-org-heading-verbs)
    (error "Unknown heading verb %S" verb))
  (unless (stringp token-owner)
    (error "Heading verb TOKEN-OWNER must be a string, got %S" token-owner))
  (let ((config (list :token-owner token-owner :save save :leave leave))
        (fn (intern (format "jetpacs-org-heading-verbs--%s" verb))))
    (lambda (args params) (funcall fn config args params))))

(provide 'jetpacs-org-heading-verbs)
;;; jetpacs-org-heading-verbs.el ends here
