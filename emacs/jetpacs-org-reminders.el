;;; jetpacs-org-reminders.el --- Org agenda, search, and reminder owner -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The one Org agenda/search extraction and device-reminder pipeline owned by
;; the Org Mode app.  Downstream screens may consume the neutral projections,
;; but extraction, search, and reminder delivery remain native Org machinery.
;; Registration is explicit from `jetpacs-org-mode', and the rollout flag is
;; deliberately independent of the inert legacy inline pipeline's flag.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'calendar)
(require 'org)
(require 'org-agenda)
(require 'ebp-org)

(defvar jetpacs-shell-after-push-hook)
(declare-function jetpacs-connected-p "jetpacs-surfaces" ())
(declare-function jetpacs-granted-p "jetpacs-surfaces"
                  (capability &optional client))
(declare-function jetpacs-error-label "jetpacs-surfaces" (err))
(declare-function jetpacs-reminders-set "jetpacs-device"
                  (reminders &rest args))

(defconst jetpacs-org-reminders-owner "org-mode"
  "Owner of the canonical Org agenda reminder set.")

(defcustom jetpacs-org-reminders-horizon-hours 24
  "How far ahead timed Org Agenda items become device reminders.
A date-only scheduled item is not an alarm.  Repeating timestamps are
expanded by Org Agenda before reminders are built."
  :type 'natnum :group 'jetpacs-org)

(defcustom jetpacs-org-mode-search-result-limit 100
  "Maximum rows returned by the canonical Org search projection.
The search itself is performed by Org's agenda search commands; this bound
limits the neutral records retained for downstream applets."
  :type '(integer 1 512) :group 'jetpacs-org)

(defvar jetpacs-org-reminders-enabled nil
  "Non-nil when the canonical Org reminder pipeline is enabled.
The default stays nil so the device owner can be changed only by the
ordered, durable-set cutover ceremony.")

(defvar jetpacs-org-reminders--last-reminders 'unset
  "Last reminder set confirmed by the Companion.")

(defconst jetpacs-org-mode--agenda-buffer "*Jetpacs Org Mode Agenda*"
  "Private Org Agenda buffer used by the canonical extraction.")

(defconst jetpacs-org-mode--search-buffer "*Jetpacs Org Mode Search*"
  "Private Org Agenda buffer used by the canonical search projection.")

(defconst jetpacs-org-mode-custom-view-limit 64
  "Maximum compatible Org custom agenda commands exposed as saved views.")

(defconst jetpacs-org-mode-custom-view-scan-limit 256
  "Maximum custom agenda definitions inspected for compatible saved views.")

(defconst jetpacs-org-mode--custom-view-types
  '(agenda agenda* alltodo stuck todo search tags tags-todo)
  "Built-in single-command agenda types representable as a flat saved view.")

(defconst jetpacs-org-mode--custom-view-forbidden-settings
  '(org-agenda-files org-agenda-text-search-extra-files
    org-agenda-buffer-name org-agenda-buffer-tmp-name org-agenda-sticky)
  "Custom-command settings that could escape or hide the canonical projection.")

;;;; Canonical agenda extraction

(defun jetpacs-org-mode--agenda-scope ()
  "Return existing local Org agenda files, expanding directories.
The safe scope comes from `ebp-org-agenda-files', which has already
dropped remote entries.  A nil result means no items, never the
current buffer."
  (cl-mapcan
   (lambda (entry)
     (cond
      ((file-directory-p entry)
       (directory-files entry t org-agenda-file-regexp))
      ((file-exists-p entry) (list entry))))
   (ebp-org-agenda-files)))

(defalias 'jetpacs-org-mode-agenda-scope
  #'jetpacs-org-mode--agenda-scope
  "Public access to Org Mode's canonical local agenda-file scope.")

(defun jetpacs-org-mode--agenda-items (&optional span start-day)
  "Return rich agenda items for SPAN beginning at START-DAY.
SPAN accepts Org Agenda's day/week/month names or an integer day
count.  Results are memoised in the Org Mode namespace."
  (ebp-org-with-cache 'org-mode (list 'agenda (or span 'day) start-day)
    (jetpacs-org-mode--agenda-items-1 span start-day)))

(defalias 'jetpacs-org-mode-agenda-items
  #'jetpacs-org-mode--agenda-items
  "Return Org Mode's canonical rich agenda projection.
SPAN and START-DAY have the same meaning as in Org Agenda.  This public read
seam lets downstream applets present the one foundation-owned extraction
without depending on its private worker or introducing another agenda engine.")

(defun jetpacs-org-mode--canonical-search-scope ()
  "Return the canonical, contained files eligible for a search projection."
  (delete-dups
   (delq nil
         (mapcar #'ebp-org-file-allowed-p
                 (jetpacs-org-mode--agenda-scope)))))

(defun jetpacs-org-mode--record-in-scope-p (record files)
  "Return non-nil when neutral agenda RECORD belongs to canonical FILES."
  (when-let* ((file (alist-get 'file record))
              (allowed (ebp-org-file-allowed-p file)))
    (and (member allowed files) t)))

(defun jetpacs-org-mode--search-record-at-point ()
  "Return the neutral Org search record on the current agenda line.
Return nil when the line is an agenda heading rather than an Org hit."
  (when-let* ((marker (or (get-text-property (point) 'org-marker)
                          (get-text-property (point) 'org-hd-marker)))
              ((markerp marker))
              (buffer (marker-buffer marker))
              ((buffer-live-p buffer)))
    (let ((summary (string-trim
                    (buffer-substring-no-properties
                     (line-beginning-position) (line-end-position)))))
      (with-current-buffer buffer
        (org-with-wide-buffer
         (goto-char marker)
         (org-back-to-heading t)
         (let* ((components (org-heading-components))
                (tags (org-get-tags nil t)))
           `((headline . ,(or (nth 4 components) "Untitled"))
             (todo . ,(nth 2 components))
             (priority . ,(and (nth 3 components)
                               (char-to-string (nth 3 components))))
             (tags . ,(vconcat tags))
             (file . ,buffer-file-name)
             (pos . ,(point))
             (summary . ,summary)
             (ref . ,(ebp-org-ref-at-point)))))))))

(defun jetpacs-org-mode--search-items-1 (query mode limit)
  "Run Org's QUERY search in MODE and return at most LIMIT records.
MODE is `text', which delegates to `org-search-view' (including its native
phrase, Boolean, and brace-regexp syntax), or `match', which delegates to
`org-tags-view' and therefore accepts standard Org match syntax."
  (let ((files (sort (jetpacs-org-mode--canonical-search-scope)
                     #'string-lessp)))
    (when files
      (let ((org-agenda-files files)
            (org-agenda-buffer-name jetpacs-org-mode--search-buffer)
            (org-agenda-buffer-tmp-name jetpacs-org-mode--search-buffer)
            (org-agenda-text-search-extra-files nil)
            (org-agenda-sticky nil)
            (inhibit-redisplay t)
            records
            seen)
        (unwind-protect
            (save-window-excursion
              (let ((org-agenda-window-setup 'current-window))
                (pcase mode
                  ('text
                   (ebp-org-call-with-clamped-io
                    #'org-search-view nil query))
                  ('match
                   (ebp-org-call-with-clamped-io
                    #'org-tags-view nil query)))
                (with-current-buffer jetpacs-org-mode--search-buffer
                  (goto-char (point-min))
                  (while (not (eobp))
                    (when-let* ((record
                                 (jetpacs-org-mode--search-record-at-point))
                                ((jetpacs-org-mode--record-in-scope-p
                                  record files))
                                (key (cons (alist-get 'file record)
                                           (alist-get 'pos record)))
                                ((not (member key seen))))
                      (push key seen)
                      (push record records))
                    (forward-line 1)))))
          (when-let* ((buffer (get-buffer jetpacs-org-mode--search-buffer)))
            (kill-buffer buffer)))
        (seq-take
         (sort records
               (lambda (left right)
                 (let ((left-key
                        (format "%s:%012d:%s"
                                (or (alist-get 'file left) "")
                                (or (alist-get 'pos left) 0)
                                (or (alist-get 'headline left) "")))
                       (right-key
                        (format "%s:%012d:%s"
                                (or (alist-get 'file right) "")
                                (or (alist-get 'pos right) 0)
                                (or (alist-get 'headline right) ""))))
                   (string-lessp left-key right-key))))
         limit)))))

(defun jetpacs-org-mode-search-items (query &optional mode limit)
  "Return a bounded canonical Org search projection for QUERY.
MODE defaults to `text' and may be `text' or `match'.  Text mode is the
built-in Org agenda text search, including its native Boolean and
brace-regexp forms; match mode is standard Org tag/property/TODO match
syntax.  LIMIT defaults to `jetpacs-org-mode-search-result-limit'."
  (unless (and (stringp query) (not (string-blank-p query))
               (<= (length query) 512))
    (user-error "Search query must contain 1 to 512 characters"))
  (setq mode (or mode 'text)
        limit (or limit jetpacs-org-mode-search-result-limit))
  (unless (memq mode '(text match))
    (user-error "Unsupported Org search mode: %s" mode))
  (unless (and (integerp limit) (<= 1 limit 512))
    (user-error "Search limit must be between 1 and 512"))
  (ebp-org-with-cache 'org-mode (list 'scoped-search mode query limit)
    (jetpacs-org-mode--search-items-1 query mode limit)))

;;;; Compatible Org custom agenda views

(defun jetpacs-org-mode--custom-view-settings-p (settings)
  "Return non-nil when custom agenda SETTINGS preserve projection scope."
  (and (listp settings)
       (cl-every
        (lambda (binding)
          (and (consp binding)
               (symbolp (car binding))
               (not (memq (car binding)
                          jetpacs-org-mode--custom-view-forbidden-settings))))
        settings)))

(defun jetpacs-org-mode--contextual-custom-view-keys ()
  "Return keys whose Org custom-command meaning depends on desktop context."
  (let (keys)
    (dolist (rule org-agenda-custom-commands-contexts)
      (when (and (consp rule) (stringp (car rule)))
        (push (car rule) keys)
        (when (stringp (cadr rule)) (push (cadr rule) keys))))
    (delete-dups keys)))

(defun jetpacs-org-mode--custom-view-compatible-p (entry contextual-keys)
  "Return non-nil when normalized custom agenda ENTRY is context-free.
CONTEXTUAL-KEYS contains commands whose meaning depends on the desktop buffer."
  (and (proper-list-p entry)
       (<= 4 (length entry) 6)
       (stringp (car entry))
       (<= 1 (length (car entry)) 32)
       (not (member (car entry) contextual-keys))
       (or (null (nth 1 entry)) (stringp (nth 1 entry)))
       (memq (nth 2 entry) jetpacs-org-mode--custom-view-types)
       (stringp (nth 3 entry))
       (<= (length (nth 3 entry)) 512)
       (listp (nth 4 entry))
       (<= (length (or (nth 4 entry) nil)) 64)
       (jetpacs-org-mode--custom-view-settings-p (nth 4 entry))))

(defun jetpacs-org-mode--custom-view-id (entry)
  "Return a stable opaque saved-view id for custom agenda ENTRY."
  (let ((print-length nil)
        (print-level nil)
        (print-circle t))
    (concat "org-custom-view-"
            (substring (secure-hash 'sha256 (prin1-to-string entry)) 0 32))))

(defun jetpacs-org-mode--custom-view-label (entry)
  "Return a bounded display label for custom agenda ENTRY."
  (let ((description (nth 1 entry)))
    (truncate-string-to-width
     (if (and (stringp description) (not (string-blank-p description)))
         description
       (format "Org %s view" (symbol-name (nth 2 entry))))
     120 nil nil t)))

(defun jetpacs-org-mode--custom-view-entries ()
  "Return bounded compatible `org-agenda-custom-commands' entries."
  (condition-case err
      (let ((contextual
             (jetpacs-org-mode--contextual-custom-view-keys)))
        (seq-take
         (cl-remove-if-not
          (lambda (entry)
            (jetpacs-org-mode--custom-view-compatible-p
             entry contextual))
          (org-agenda-normalize-custom-commands
           (seq-take org-agenda-custom-commands
                     jetpacs-org-mode-custom-view-scan-limit)))
         jetpacs-org-mode-custom-view-limit))
    (error
     (message "jetpacs-org-mode: custom views unavailable (%s)" (car err))
     nil)))

(defun jetpacs-org-mode-custom-views ()
  "Return presentation-safe compatible Org custom agenda view descriptors.
Each descriptor contains an opaque `:id', a display `:label', and a built-in
`:kind'.  Agenda keys, match expressions, settings, functions, and file names
remain private to Emacs."
  (mapcar
   (lambda (entry)
     (list :id (jetpacs-org-mode--custom-view-id entry)
           :label (jetpacs-org-mode--custom-view-label entry)
           :kind (symbol-name (nth 2 entry))))
   (jetpacs-org-mode--custom-view-entries)))

(defun jetpacs-org-mode--resolve-custom-view (view-id)
  "Return the current compatible custom agenda entry for opaque VIEW-ID."
  (unless (stringp view-id) (signal 'ebp-org-refused (list 'invalid-view)))
  (or (cl-find view-id (jetpacs-org-mode--custom-view-entries)
               :key #'jetpacs-org-mode--custom-view-id :test #'equal)
      (signal 'ebp-org-unresolved nil)))

(defun jetpacs-org-mode-custom-view-items (view-id &optional limit)
  "Run opaque compatible Org custom VIEW-ID and return at most LIMIT records.
The command is resolved again at execution, so configuration drift makes a
queued action stale.  Native Org command settings and ordering are preserved,
while every returned row is rechecked against the canonical agenda scope."
  (setq limit (or limit jetpacs-org-mode-search-result-limit))
  (unless (and (integerp limit) (<= 1 limit 512))
    (user-error "Custom view limit must be between 1 and 512"))
  (let* ((entry (copy-tree
                 (jetpacs-org-mode--resolve-custom-view view-id)))
         (key (car entry))
         (files (sort (jetpacs-org-mode--canonical-search-scope)
                      #'string-lessp)))
    (when files
      (let ((org-agenda-files files)
            (org-agenda-text-search-extra-files nil)
            (org-agenda-custom-commands (list entry))
            (org-agenda-custom-commands-contexts nil)
            (org-agenda-buffer-name jetpacs-org-mode--search-buffer)
            (org-agenda-buffer-tmp-name jetpacs-org-mode--search-buffer)
            (org-agenda-sticky nil)
            (inhibit-redisplay t)
            records
            seen)
        (unwind-protect
            (save-window-excursion
              (let ((org-agenda-window-setup 'current-window))
                (ebp-org-call-with-clamped-io #'org-agenda nil key)
                (with-current-buffer jetpacs-org-mode--search-buffer
                  (goto-char (point-min))
                  (while (and (< (length records) limit) (not (eobp)))
                    (when-let* ((record
                                 (jetpacs-org-mode--search-record-at-point))
                                ((jetpacs-org-mode--record-in-scope-p
                                  record files))
                                (record-key
                                 (cons (alist-get 'file record)
                                       (alist-get 'pos record)))
                                ((not (member record-key seen))))
                      (push record-key seen)
                      (push record records))
                    (forward-line 1)))))
          (when-let* ((buffer (get-buffer jetpacs-org-mode--search-buffer)))
            (kill-buffer buffer)))
        (nreverse records)))))

(defun jetpacs-org-mode--agenda-items-1 (span start-day)
  "Uncached worker for `jetpacs-org-mode--agenda-items'."
  (let ((files (jetpacs-org-mode--agenda-scope)))
    (when files
      (let ((org-agenda-span (or span 'day))
            (org-agenda-start-day start-day)
            (org-agenda-files files)
            (org-agenda-buffer-tmp-name jetpacs-org-mode--agenda-buffer)
            (org-agenda-sticky nil)
            (inhibit-redisplay t)
            items)
        (unwind-protect
            (save-window-excursion
              (let ((org-agenda-window-setup 'current-window))
                (ebp-org--with-clamped-io
                  (org-agenda nil "a"))
                (with-current-buffer jetpacs-org-mode--agenda-buffer
                  (goto-char (point-min))
                  (while (not (eobp))
                    (let* ((marker (get-text-property (point) 'org-marker))
                           (tags (get-text-property (point) 'tags))
                           (time (get-text-property (point) 'time))
                           (type (get-text-property (point) 'type))
                           (extra (get-text-property (point) 'extra))
                           (ts-date (get-text-property (point) 'ts-date))
                           (date-abs (get-text-property (point) 'date))
                           (date-list
                            (cond
                             ((consp date-abs) date-abs)
                             ((numberp date-abs)
                              (calendar-gregorian-from-absolute date-abs))))
                           (date-str
                            (when date-list
                              (format "%04d-%02d-%02d"
                                      (nth 2 date-list)
                                      (nth 0 date-list)
                                      (nth 1 date-list)))))
                      (when marker
                        (with-current-buffer (marker-buffer marker)
                          (save-excursion
                            (goto-char marker)
                            (let* ((components (org-heading-components))
                                   (todo (nth 2 components))
                                   (priority (nth 3 components))
                                   (headline (nth 4 components)))
                              (push `((headline . ,headline)
                                      (todo . ,todo)
                                      (priority . ,(and priority
                                                        (char-to-string
                                                         priority)))
                                      (tags . ,(vconcat tags))
                                      (file . ,(buffer-file-name))
                                      (pos . ,(marker-position marker))
                                      (time . ,time)
                                      (date . ,date-str)
                                      (type . ,(and type (format "%s" type)))
                                      (extra . ,extra)
                                      (ts-date . ,ts-date)
                                      (ref . ,(ebp-org-ref-at-point)))
                                    items))))))
                    (forward-line 1)))))
          (when-let* ((buffer (get-buffer jetpacs-org-mode--agenda-buffer)))
            (kill-buffer buffer)))
        (nreverse items)))))

;;;; Reminder projection and ownership

(defun jetpacs-org-reminders--item-hm (time)
  "Normalize an agenda TIME property to HH:MM, or return nil."
  (when (stringp time)
    (let ((text (string-trim time)))
      (when (string-match
             "\\`\\([0-9]\\{1,2\\}\\):\\([0-9]\\{2\\}\\)" text)
        (format "%02d:%s" (string-to-number (match-string 1 text))
                (match-string 2 text))))))

(defun jetpacs-org-reminders--reminder-id (name)
  "Return a stable SPEC identifier under the Org Mode owner for NAME."
  (let* ((safe (replace-regexp-in-string "[^A-Za-z0-9._:/-]" "-" name))
         (stem (substring safe 0 (min (length safe) 80))))
    (format "%s.rem-%s-%s" jetpacs-org-reminders-owner stem
            (substring (sha1 name) 0 8))))

(defun jetpacs-org-reminders--upcoming-reminders
    (&optional horizon-hours now)
  "Return timed Org Agenda reminders within HORIZON-HOURS of NOW.
HORIZON-HOURS defaults to `jetpacs-org-reminders-horizon-hours'; NOW
is an Emacs time value and defaults to `current-time'."
  (let* ((hours (or horizon-hours jetpacs-org-reminders-horizon-hours))
         (horizon (* hours 3600))
         (now-seconds (float-time (or now (current-time))))
         (days (max 1 (1+ (ceiling (/ hours 24.0)))))
         (items (jetpacs-org-mode--agenda-items days nil))
         reminders
         seen)
    (dolist (item items)
      (let ((date (alist-get 'date item))
            (hm (jetpacs-org-reminders--item-hm (alist-get 'time item)))
            (headline (alist-get 'headline item))
            (type (alist-get 'type item))
            (file (alist-get 'file item))
            (pos (alist-get 'pos item)))
        (when (and (stringp date) hm)
          (let ((at (float-time
                     (org-time-string-to-time (concat date " " hm)))))
            (when (and (> at now-seconds) (< (- at now-seconds) horizon))
              (let ((id
                     (jetpacs-org-reminders--reminder-id
                      (format "%sT%s %s:%s" date hm
                              (or file "") (or pos 0)))))
                ;; A heading scheduled and deadlined for the same instant
                ;; appears twice in Org Agenda but must create one alarm.
                (unless (member id seen)
                  (push id seen)
                  (push (list :id id
                              :at_ms (truncate (* at 1000))
                              :title (or headline "Org reminder")
                              :body (concat hm
                                            (when (stringp type)
                                              (concat " · " type))))
                        reminders))))))))
    (nreverse reminders)))

(defun jetpacs-org-reminders--sync-reminders ()
  "Synchronize the canonical Org reminder set after a successful push."
  (when (and jetpacs-org-reminders-enabled
             (jetpacs-connected-p)
             (jetpacs-granted-p "reminders.owner"))
    ;; Keep scan failure distinct from a successful empty projection: only the
    ;; latter is authority to clear the owner's durable device set.
    (when-let* ((scan
                 (condition-case err
                     (cons t
                           (jetpacs-org-reminders--upcoming-reminders))
                   (error
                    (message "jetpacs-org-reminders: scan failed: %s"
                             (jetpacs-error-label err))
                    nil))))
      (let ((reminders (cdr scan)))
        (unless (equal reminders jetpacs-org-reminders--last-reminders)
          (condition-case err
              (jetpacs-reminders-set
               reminders :owner jetpacs-org-reminders-owner
               :callback
               (lambda (_count error)
                 (unless error
                   (setq jetpacs-org-reminders--last-reminders reminders))))
            (error
             (message "jetpacs-org-reminders: sync failed: %s"
                      (jetpacs-error-label err)))))))))

(defun jetpacs-org-reminders-register ()
  "Install the canonical reminder hook when its rollout flag is enabled."
  (remove-hook 'jetpacs-shell-after-push-hook
               #'jetpacs-org-reminders--sync-reminders)
  (when jetpacs-org-reminders-enabled
    (add-hook 'jetpacs-shell-after-push-hook
              #'jetpacs-org-reminders--sync-reminders))
  t)

(defun jetpacs-org-reminders-unregister ()
  "Remove the canonical reminder hook and forget its confirmed-set cache."
  (remove-hook 'jetpacs-shell-after-push-hook
               #'jetpacs-org-reminders--sync-reminders)
  (setq jetpacs-org-reminders--last-reminders 'unset)
  t)

(provide 'jetpacs-org-reminders)
;;; jetpacs-org-reminders.el ends here
