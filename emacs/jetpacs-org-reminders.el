;;; jetpacs-org-reminders.el --- Org agenda, search, and reminder owner -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The one Org agenda/search extraction and device-reminder pipeline owned by
;; the Org Mode app, plus the canonical built-in heading extractions beside
;; it: the TODO sweep, the heading item shape, the tag vocabulary, and the
;; `[ :startgroup ... :endgroup ]' tag-group index.  Downstream screens may
;; consume the neutral projections, but extraction, search, and reminder
;; delivery remain native Org machinery.  Registration is explicit from
;; `jetpacs-org-mode'.

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

(defvar jetpacs-org-reminders-enabled t
  "Non-nil when the canonical Org reminder pipeline is enabled.")

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

;;;; Canonical heading extraction (built-in Org, memoised under org-mode)

(defun jetpacs-org-mode-heading-item-at ()
  "Build a heading item alist for the org entry at point.
The one item shape every foundation extraction answers
\(headline/todo/priority/tags/scheduled/deadline/level/file/pos/ref).
Public: downstream search and scan builders mint their items here so
every card shares one shape."
  (let* ((components (org-heading-components))
         (todo (nth 2 components))
         (priority (nth 3 components))
         (headline (nth 4 components))
         (tags (org-get-tags))
         (scheduled (org-entry-get (point) "SCHEDULED"))
         (deadline  (org-entry-get (point) "DEADLINE")))
    `((headline . ,headline)
      (todo . ,todo)
      (priority . ,(if priority (char-to-string priority) nil))
      (tags . ,(vconcat tags))
      (scheduled . ,scheduled)
      (deadline  . ,deadline)
      (level . ,(nth 0 components))
      (file . ,(buffer-file-name))
      (pos . ,(point))
      (ref . ,(ebp-org-ref-at-point)))))

(defun jetpacs-org-mode--todo-items-1 (files)
  "Uncached worker for `jetpacs-org-mode-todo-items'."
  (let ((scope (or files (jetpacs-org-mode--agenda-scope)))
        items)
    (when scope
      (ebp-org--with-clamped-io
        (org-map-entries
         (lambda ()
           (when (nth 2 (org-heading-components))
             (push (jetpacs-org-mode-heading-item-at) items)))
         "TODO<>\"\"" scope)))
    (nreverse items)))

(defun jetpacs-org-mode-todo-items (&optional files)
  "Extract every heading carrying a TODO keyword from FILES.
FILES defaults to the canonical local agenda scope.  The sweep is
`org-map-entries' over the files themselves and runs under the D2 IO
clamp; results are memoised in the Org Mode namespace, so
`ebp-org-cache-invalidate' with no namespace (or with `org-mode')
evicts them.  Downstream apps with a note index of their own may answer
the whole-vault case from it and fall through here otherwise."
  (ebp-org-with-cache 'org-mode (list 'todos files)
    (jetpacs-org-mode--todo-items-1 files)))

(defun jetpacs-org-mode-all-tags ()
  "Sorted tag vocabulary for a query builder.
Combines `org-tag-alist' (the configured vocabulary) with every tag
actually used in the canonical agenda scope.  Memoised in the Org Mode
namespace."
  (ebp-org-with-cache 'org-mode (list 'all-tags)
    (let ((tags nil))
      (dolist (entry org-tag-alist)
        (let ((tg (if (consp entry) (car entry) entry)))
          (when (stringp tg) (push tg tags))))
      ;; Explicit scope: passed nil, the table falls back to the
      ;; `org-agenda-files' FUNCTION (P1-5/P1-7); the sweep visits
      ;; every file, so it runs clamped.
      (let ((files (jetpacs-org-mode--agenda-scope)))
        (when files
          (ebp-org--with-clamped-io
            (dolist (entry (org-global-tags-completion-table files))
              (when (stringp (car entry)) (push (car entry) tags))))))
      (sort (delete-dups tags) #'string-lessp))))

;;;; Tag groups (built-in `[ :startgroup ... :endgroup ]' vocabulary)

(defun jetpacs-org-mode-tag-strings (tags)
  "Return TAGS as distinct, non-empty strings without text properties.
Item `tags' vectors still carry Org's propertized `org-get-tags' output;
the foundation's tag-group readers already answer clean strings."
  (let ((values (cond
                 ((vectorp tags) (append tags nil))
                 ((listp tags) (copy-sequence tags))
                 ((stringp tags) (list tags)))))
    (delete-dups
     (delq nil
           (mapcar
            (lambda (tag)
              (when (stringp tag)
                (let ((clean (string-trim
                              (substring-no-properties tag))))
                  (unless (string-empty-p clean) clean))))
            values)))))

(defun jetpacs-org-mode-tag-group (groups name)
  "Return the group named NAME in GROUPS, ignoring case.
GROUPS is vocabulary from `ebp-org-tag-groups' or the `:groups' field of
`ebp-org-tag-groups-at'.  Return the first matching declaration, preserving
Org's persistent-before-file precedence, or nil when NAME is absent."
  (and (stringp name)
       (cl-find-if
        (lambda (group)
          (let ((candidate (plist-get group :name)))
            (and (stringp candidate)
                 (string-equal-ignore-case candidate name))))
        groups)))

(defun jetpacs-org-mode--tag-settings-here ()
  "Snapshot the current buffer's tag inputs missing from the EBP cache stamp.
Org owns `org-current-tag-alist': its normal settings refresh rebuilds that
vocabulary.  Include both that vocabulary and its configuration inputs, so
settings refreshes and buffer-local inheritance exclusions invalidate cached
membership without needing a text edit or manual cache invalidation."
  (copy-tree
   (list org-tag-alist org-tag-persistent-alist org-current-tag-alist
         org-file-tags org-tags-exclude-from-inheritance
         org-use-tag-inheritance)))

(defun jetpacs-org-mode-tag-settings-stamp (files)
  "Snapshot global and visited-buffer tag settings for FILES.
The EBP cache already stamps file contents and buffer edits; tag vocabulary
and inheritance exclusions may change without either.  Copy these inputs
so later in-place customization cannot mutate an existing cache key."
  (list (jetpacs-org-mode--tag-settings-here)
        (mapcar
         (lambda (file)
           (cons file
                 (when-let* ((buffer (find-buffer-visiting file)))
                   (with-current-buffer buffer
                     (and (derived-mode-p 'org-mode)
                          (jetpacs-org-mode--tag-settings-here))))))
         files)))

(defun jetpacs-org-mode-file-tag-group-index (file group &optional inherit)
  "Return FILE's effective tag GROUP members and heading memberships.
The per-file memo every group consumer shares, so a first paint pays one
walk per file per generation.  The memoised result is a plist with
`:members' in declaration order, `:file-tags' as the members carried by
the file's own `#+FILETAGS' (so a file with no headings still belongs),
and `:positions' as an alist from heading point to the members inherited
there.  With INHERIT non-nil, file tags and parent tags count even when
`org-use-tag-inheritance' narrows agenda display
\(`org-tags-exclude-from-inheritance' still applies); nil follows Org's
configured inheritance.  Cache identity includes the buffer's tag
vocabulary, exclusions and INHERIT because the EBP cache's content stamp
does not cover settings changes.  A refused or vanished FILE answers nil."
  (when (and (stringp file) (stringp group)
             (not (string-empty-p group)))
    (condition-case nil
        (let ((true (ebp-org--check-file file)))
          (ebp-org--with-clamped-io
            (with-current-buffer (find-file-noselect true t)
              (unless (derived-mode-p 'org-mode) (org-mode))
              (org-with-wide-buffer
               (let ((org-use-tag-inheritance
                      (or inherit org-use-tag-inheritance)))
                 (ebp-org-with-cache 'org-mode
                     (list 'tag-group-index true group (and inherit t)
                           (buffer-chars-modified-tick)
                           (jetpacs-org-mode--tag-settings-here))
                   (let* ((members
                           (plist-get
                            (jetpacs-org-mode-tag-group
                             (ebp-org-tag-groups) group)
                            :tags))
                          (file-tags (jetpacs-org-mode-tag-strings
                                      org-file-tags))
                          positions)
                     (org-map-entries
                      (lambda ()
                        (let ((membership (ebp-org-tag-groups-at (point))))
                          (push
                           (cons (point)
                                 (plist-get
                                  (jetpacs-org-mode-tag-group
                                   (plist-get membership :groups) group)
                                  :members))
                           positions)))
                      nil 'file)
                     (list :members members
                           :file-tags (cl-remove-if-not
                                       (lambda (member)
                                         (member member file-tags))
                                       members)
                           :positions (nreverse positions)))))))))
      (error nil))))

;;;; Reminder projection and ownership

(defun jetpacs-org-reminders--item-hm (time)
  "Normalize an agenda TIME property to HH:MM, or return nil."
  (when (stringp time)
    (let ((text (string-trim time)))
      (when (string-match
             "\\`\\([0-9]\\{1,2\\}\\):\\([0-9]\\{2\\}\\)" text)
        (format "%02d:%s" (string-to-number (match-string 1 text))
                (match-string 2 text))))))

(defalias 'jetpacs-org-reminders-item-hm
  #'jetpacs-org-reminders--item-hm
  "Normalize an agenda item's raw `time' property to HH:MM, or nil.
Public: downstream cards render the same clock text the reminder
projection alarms on.")

(defun jetpacs-org-reminders--reminder-id (name)
  "Return a stable SPEC identifier under the Org Mode owner for NAME."
  (let* ((safe (replace-regexp-in-string "[^A-Za-z0-9._:/-]" "-" name))
         (stem (substring safe 0 (min (length safe) 80))))
    (format "%s.rem-%s-%s" jetpacs-org-reminders-owner stem
            (substring (sha1 name) 0 8))))

(defun jetpacs-org-mode-reminder-candidates
    (&optional horizon-hours now lead-minutes)
  "Return neutral timed Org candidates within HORIZON-HOURS of NOW.
HORIZON-HOURS defaults to `jetpacs-org-reminders-horizon-hours'; NOW is an
Emacs time value and defaults to `current-time'.  LEAD-MINUTES defaults to
zero and shifts only the presentation timestamp, not eligibility: an event
must still be future and within the horizon, while its lead-time reminder may
already be due.  Date-only agenda rows are intentionally absent.

Each returned plist carries :ref, :headline, :type, :date, :time,
:event_at_ms, :at_ms, and a stable private :identity.  It is neutral
application data, not an EBP reminder descriptor, so downstream applets can
add their own opaque routing without duplicating Org extraction."
  (let* ((hours (or horizon-hours jetpacs-org-reminders-horizon-hours))
         (lead (or lead-minutes 0))
         (horizon (* hours 3600))
         (now-seconds (float-time (or now (current-time))))
         (days (max 1 (1+ (ceiling (/ hours 24.0)))))
         (items (jetpacs-org-mode--agenda-items days nil))
         candidates
         seen)
    (unless (and (numberp hours) (>= hours 0))
      (error "jetpacs-org: reminder horizon must be non-negative"))
    (unless (and (integerp lead) (>= lead 0))
      (error "jetpacs-org: reminder lead minutes must be a non-negative integer"))
    (dolist (item items)
      (let ((date (alist-get 'date item))
            (hm (jetpacs-org-reminders--item-hm (alist-get 'time item)))
            (headline (alist-get 'headline item))
            (type (alist-get 'type item))
            (file (alist-get 'file item))
            (pos (alist-get 'pos item))
            (ref (alist-get 'ref item)))
        (when (and (stringp date) hm)
          (let ((event-at (float-time
                           (org-time-string-to-time (concat date " " hm)))))
            (when (and (> event-at now-seconds)
                       (< (- event-at now-seconds) horizon))
              (let ((identity
                     (format "%sT%s %S" date hm
                             (or ref (cons file pos)))))
                ;; A heading scheduled and deadlined for the same instant
                ;; appears twice in Org Agenda but must create one alarm.
                (unless (member identity seen)
                  (push identity seen)
                  (push (list :ref ref
                              :headline (or headline "Org reminder")
                              :type type
                              :date date
                              :time hm
                              :event_at_ms (truncate (* event-at 1000))
                              :at_ms (max 0 (truncate
                                             (* (- event-at (* lead 60)) 1000)))
                              :identity identity)
                        candidates))))))))
    (nreverse candidates)))

(defun jetpacs-org-reminders--upcoming-reminders
    (&optional horizon-hours now)
  "Return basic EBP reminders projected from neutral Org candidates."
  (mapcar
   (lambda (candidate)
     (list :id (jetpacs-org-reminders--reminder-id
                (plist-get candidate :identity))
           :at_ms (plist-get candidate :at_ms)
           :title (plist-get candidate :headline)
           :body (concat (plist-get candidate :time)
                         (when-let* ((type (plist-get candidate :type))
                                     ((stringp type)))
                           (concat " · " type)))))
   (jetpacs-org-mode-reminder-candidates horizon-hours now 0)))

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
