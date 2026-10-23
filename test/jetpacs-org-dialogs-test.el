;;; jetpacs-org-dialogs-test.el --- JA-5d: footnote + header sheet -*- lexical-binding: t; -*-

;;; Commentary:

;; JA-5d's slice of the JA-5 exit gate: every dialog spec passes the
;; DIALOG profile and carries only drop-mode remote descriptors (SPEC
;; 18.1); dialog ids are fresh per press; the sheet's submitted value is
;; re-validated against a freshly built candidate list (23.2); the
;; mutations ride the JA-4 engine (cycle flushes the log note, duplicate
;; strips IDs, narrow/widen round-trips); Archive is a token-addressed
;; remote descriptor WITH the 14.1 confirm whose replace-set sweep
;; answers a stale sheet honestly; tags write through the charset
;; filter; and the one bridged flow (Refile) is can-bridge gated.
;;
;; Harness: a recorder replaces `ebp-client-dialog-show', capturing
;; (ID SPEC STYLE CALLBACK) so tests inspect specs and drive callbacks
;; directly — no wire, no Companion.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'ebp)
(require 'jetpacs-org-render)   ; pulls jetpacs-org-dialogs

;;;; Harness

(defvar jetpacs-org-dialogs-test--shown nil
  "Recorded dialogs, newest first: (:id ID :spec S :style ST :callback CB).")

(defmacro jetpacs-org-dialogs-test--with-env (&rest body)
  "Stub client (ready, dialog grant, rich profile) + dialog recorder."
  (declare (indent 0))
  `(let ((jetpacs-org-dialogs-test--shown nil)
         (client (ebp-client-create
                  :receipt-file (make-temp-file "ja5d-receipts"))))
     (setf (ebp-client-state client) 'ready
           (ebp-client-granted client) ["surfaces.dialog"]
           (ebp-client-limits client) '(:max_rich_spans 4096
                                        :max_frame_bytes 4194304)
           (ebp-client-profiles client)
           '(:app (:node_types ["text" "rich_text" "image" "divider"
                                "table" "button" "row" "column"]
                   :features [])
             :dialog (:node_types ["text" "rich_text" "button" "row"
                                   "column" "text_input" "enum_list"
                                   "date_button" "time_button" "divider"]
                      :features [])))
     (cl-letf (((symbol-function 'ebp-client-dialog-show)
                (cl-function
                 (lambda (_client id spec &key style callback)
                   (push (list :id id :spec spec :style style
                               :callback callback)
                         jetpacs-org-dialogs-test--shown)
                   (format "req-%d" (length jetpacs-org-dialogs-test--shown))))))
       (unwind-protect
           (progn (jetpacs-attach client) ,@body)
         (jetpacs-detach)
         (jetpacs-org-dialogs-reset)))))

(defmacro jetpacs-org-dialogs-test--with-file (var content &rest body)
  "The render-test fixture shape: temp .org under a fresh root."
  (declare (indent 2))
  `(let* ((dir (file-name-as-directory
                (file-truename (make-temp-file "ja5d" t))))
          (,var (expand-file-name "fixture.org" dir))
          (ebp-org-roots (list dir)))
     (with-temp-file ,var (insert ,content))
     (unwind-protect
         (progn ,@body)
       (dolist (name (list ,var (concat ,var "_archive")))
         (when-let* ((buf (find-buffer-visiting name)))
           (with-current-buffer buf (set-buffer-modified-p nil))
           (kill-buffer buf)))
       (delete-directory dir t)
       (jetpacs-buffer-forget-exposed)
       (ebp-org-reset))))

(defun jetpacs-org-dialogs-test--buffer (file)
  (let ((buf (find-file-noselect file)))
    (with-current-buffer buf
      (unless (derived-mode-p 'org-mode) (org-mode))
      (font-lock-ensure (point-min) (point-max)))
    buf))

(defun jetpacs-org-dialogs-test--ref (buf headline)
  (with-current-buffer buf
    (org-with-wide-buffer
     (goto-char (point-min))
     (search-forward headline)
     (ebp-org-ref-at-point))))

(defconst jetpacs-org-dialogs-test--params '(:surface "app:ja5d")
  "Minimal event params for driving handlers and dispatches.")

(defun jetpacs-org-dialogs-test--last ()
  (car jetpacs-org-dialogs-test--shown))

(defun jetpacs-org-dialogs-test--descriptors (node)
  "Every ActionDescriptor reachable in NODE (on_tap + on_pick, deep)."
  (let (out)
    (cl-labels
        ((walk (n)
           (when (consp n)
             (dolist (hook '(:on_tap :on_pick))
               (when-let* ((d (plist-get n hook))) (push d out)))
             (dolist (s (append (plist-get n :spans) nil))
               (when-let* ((d (plist-get s :on_tap))) (push d out)))
             (dolist (c (append (plist-get n :children) nil))
               (walk c)))))
      (walk node))
    out))

(defconst jetpacs-org-dialogs-test--sheet-fixture
  "* TODO Parent :old:\n:PROPERTIES:\n:ID: dup-me\n:END:\nBody.\n** Child\n* Other\n")

(defun jetpacs-org-dialogs-test--show-sheet (buf &optional headline)
  "Drive the sheet for HEADLINE (default Parent); return its ref."
  (let ((ref (jetpacs-org-dialogs-test--ref buf (or headline "Parent"))))
    (jetpacs-org-dialogs--show-sheet buf (plist-get ref :pos)
                                     jetpacs-org-dialogs-test--params)
    ref))

;;;; Profile + descriptor discipline

(ert-deftest jetpacs-org-dialogs-specs-pass-dialog-profile ()
  "EVERY spec this module can show passes the reference dialog profile.
The runtime send is ungated (the sections/files precedent calls ebp
directly), so this pin is the conformance guarantee."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        (concat jetpacs-org-dialogs-test--sheet-fixture
                "Text with a note[fn:1] here.\n\n[fn:1] The definition.\n")
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--show-sheet buf)))
        (jetpacs-org-dialogs--show-set-todo
         ref buf jetpacs-org-dialogs-test--params)
        (jetpacs-org-dialogs--show-priority
         ref buf jetpacs-org-dialogs-test--params)
        (jetpacs-org-dialogs--show-tags
         ref buf jetpacs-org-dialogs-test--params)
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (search-forward "note[fn:1]")
           (jetpacs-org-dialogs--show-footnote
            buf (- (point) 6) jetpacs-org-dialogs-test--params)))
        (should (= 5 (length jetpacs-org-dialogs-test--shown)))
        (dolist (shown jetpacs-org-dialogs-test--shown)
          (should (jetpacs-check-profile (plist-get shown :spec) 'dialog)))))))

(ert-deftest jetpacs-org-dialogs-remote-descriptors-are-drop ()
  "SPEC 18.1: every remote descriptor inside a dialog is drop-mode."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let ((buf (jetpacs-org-dialogs-test--buffer f)))
        (jetpacs-org-dialogs-test--show-sheet buf)
        (dolist (d (jetpacs-org-dialogs-test--descriptors
                    (plist-get (jetpacs-org-dialogs-test--last) :spec)))
          (should-not (member (plist-get d :when_offline)
                              '("queue" "wake"))))))))

(ert-deftest jetpacs-org-dialogs-fresh-ids-per-press ()
  "Two presses on the same heading get DIFFERENT dialog ids —
18.1 answers a reused outstanding id with 1201."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let ((buf (jetpacs-org-dialogs-test--buffer f)))
        (jetpacs-org-dialogs-test--show-sheet buf)
        (jetpacs-org-dialogs-test--show-sheet buf)
        (pcase-let ((`(,a ,b) jetpacs-org-dialogs-test--shown))
          (should-not (equal (plist-get a :id) (plist-get b :id))))))))

(ert-deftest jetpacs-org-dialogs-sheet-style-and-title ()
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let ((buf (jetpacs-org-dialogs-test--buffer f)))
        (jetpacs-org-dialogs-test--show-sheet buf)
        (let ((shown (jetpacs-org-dialogs-test--last)))
          (should (equal (plist-get shown :style) "sheet"))
          (should (seq-find
                   (lambda (c) (equal (plist-get c :text) "Parent"))
                   (append (plist-get (plist-get shown :spec) :children)
                           nil))))))))

;;;; Sheet dispatch

(ert-deftest jetpacs-org-dialogs-sheet-dispatch-validates-value ()
  "A submitted value the sheet would not offer NOW mutates nothing."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent"))
             (before (with-current-buffer buf (buffer-string))))
        (jetpacs-org-dialogs--sheet-dispatch
         ref buf "evil-value" jetpacs-org-dialogs-test--params)
        ;; "widen" is not offered on a wide buffer either.
        (jetpacs-org-dialogs--sheet-dispatch
         ref buf "widen" jetpacs-org-dialogs-test--params)
        (should (equal before
                       (with-current-buffer buf (buffer-string))))))))

(ert-deftest jetpacs-org-dialogs-cycle-todo-through-the-engine ()
  "The todo arm rides `ebp-org-toggle-todo' (never raw `org-todo')."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent")))
        (jetpacs-org-dialogs--sheet-dispatch
         ref buf "todo" jetpacs-org-dialogs-test--params)
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (should (search-forward "* DONE Parent" nil t))))))))

(ert-deftest jetpacs-org-dialogs-duplicate-strips-ids ()
  "Duplicate copies the subtree; the copy never shares org-id identity."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent")))
        (jetpacs-org-dialogs--sheet-dispatch
         ref buf "duplicate" jetpacs-org-dialogs-test--params)
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (should (= 2 (count-matches "^\\* TODO Parent")))
           (should (= 1 (count-matches ":ID: dup-me")))))))))

(ert-deftest jetpacs-org-dialogs-narrow-widen-roundtrip ()
  "Narrow narrows (never inside `org-with-wide-buffer' — the poc
lesson), the candidate list flips to Widen, and widen widens."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent")))
        (should (assoc "narrow"
                       (jetpacs-org-dialogs--sheet-candidates ref buf)))
        (jetpacs-org-dialogs--sheet-dispatch
         ref buf "narrow" jetpacs-org-dialogs-test--params)
        (with-current-buffer buf
          (should (buffer-narrowed-p))
          (should-not (save-excursion (goto-char (point-min))
                                      (search-forward "* Other" nil t))))
        (should (assoc "widen"
                       (jetpacs-org-dialogs--sheet-candidates ref buf)))
        (jetpacs-org-dialogs--sheet-dispatch
         ref buf "widen" jetpacs-org-dialogs-test--params)
        (with-current-buffer buf
          (should-not (buffer-narrowed-p)))))))

;;;; Archive

(ert-deftest jetpacs-org-dialogs-archive-confirm-present ()
  "Archive rides a remote descriptor with the 14.1 confirm and a token
— never a position, never confirm-less (the poc archived bare)."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let ((buf (jetpacs-org-dialogs-test--buffer f)))
        (jetpacs-org-dialogs-test--show-sheet buf)
        (let* ((spec (plist-get (jetpacs-org-dialogs-test--last) :spec))
               (archive (seq-find
                         (lambda (d) (equal (plist-get d :action)
                                            "jetpacs.org.archive"))
                         (jetpacs-org-dialogs-test--descriptors spec))))
          (should archive)
          (should (stringp (plist-get archive :confirm)))
          (should (stringp (plist-get (plist-get archive :args) :token)))
          (should-not (plist-get (plist-get archive :args) :pos)))))))

(ert-deftest jetpacs-org-dialogs-archive-effect-and-file ()
  "An armed token archives the subtree into the archive file, saves it
(the poc ignored `org-archive-subtree-save-file-p'), and answers
`accepted'; the spent sheet is abandoned."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (abandoned nil))
        (cl-letf (((symbol-function 'ebp-client-abandon)
                   (lambda (_c id) (push id abandoned))))
          (jetpacs-org-dialogs-test--show-sheet buf)
          (let ((token (plist-get jetpacs-org-dialogs--sheet :token)))
            (should (eq 'accepted
                        (jetpacs-org-dialogs--archive
                         (list :token token)
                         jetpacs-org-dialogs-test--params)))
            (should (equal abandoned '("req-1")))
            (should-not jetpacs-org-dialogs--sheet)))
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (should-not (search-forward "* TODO Parent" nil t))))
        (should (file-exists-p (concat f "_archive")))
        (with-temp-buffer
          (insert-file-contents (concat f "_archive"))
          (goto-char (point-min))
          (should (search-forward "Parent" nil t)))))))

(ert-deftest jetpacs-org-dialogs-archive-token-stale-after-sweep ()
  "A second sheet re-mints the set; the first sheet's Archive token
answers `stale' — the replace-set sweep keeping old dialogs honest."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let ((buf (jetpacs-org-dialogs-test--buffer f)))
        (jetpacs-org-dialogs-test--show-sheet buf)
        (let ((old-token (plist-get jetpacs-org-dialogs--sheet :token)))
          (jetpacs-org-dialogs-test--show-sheet buf "Other")
          (should (eq 'stale
                      (jetpacs-org-dialogs--archive
                       (list :token old-token)
                       jetpacs-org-dialogs-test--params))))
        ;; Shape gate.
        (should (eq 'rejected
                    (jetpacs-org-dialogs--archive
                     '(:token 42) jetpacs-org-dialogs-test--params)))))))

;;;; Chained dialogs

(defun jetpacs-org-dialogs-test--submit (shown value &optional fields)
  "Drive SHOWN's callback as a submit of VALUE (+FIELDS plist)."
  (funcall (plist-get shown :callback)
           "submitted"
           (append (list :status "submitted" :value value)
                   (when fields (list :fields fields)))
           nil))

(ert-deftest jetpacs-org-dialogs-set-todo-validates-keyword ()
  "A keyword the buffer defines writes; an invented one writes nothing;
Clear clears."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent")))
        (jetpacs-org-dialogs--show-set-todo
         ref buf jetpacs-org-dialogs-test--params)
        (let ((shown (jetpacs-org-dialogs-test--last)))
          (jetpacs-org-dialogs-test--submit shown "NOSUCHSTATE")
          (with-current-buffer buf
            (org-with-wide-buffer
             (goto-char (point-min))
             (should (search-forward "* TODO Parent" nil t))))
          (jetpacs-org-dialogs-test--submit shown "DONE")
          (with-current-buffer buf
            (org-with-wide-buffer
             (goto-char (point-min))
             (should (search-forward "* DONE Parent" nil t))))
          (jetpacs-org-dialogs-test--submit shown "__none__")
          (with-current-buffer buf
            (org-with-wide-buffer
             (goto-char (point-min))
             (should (search-forward "* Parent" nil t)))))))))

(ert-deftest jetpacs-org-dialogs-priority-set-and-clear ()
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent")))
        (jetpacs-org-dialogs--show-priority
         ref buf jetpacs-org-dialogs-test--params)
        (let ((shown (jetpacs-org-dialogs-test--last)))
          (jetpacs-org-dialogs-test--submit shown "B")
          (with-current-buffer buf
            (org-with-wide-buffer
             (goto-char (point-min))
             (should (search-forward "[#B]" nil t))))
          ;; Out-of-range char: no write.
          (jetpacs-org-dialogs-test--submit shown "Z")
          (with-current-buffer buf
            (org-with-wide-buffer
             (goto-char (point-min))
             (should (search-forward "[#B]" nil t))))
          (jetpacs-org-dialogs-test--submit shown "__remove__")
          (with-current-buffer buf
            (org-with-wide-buffer
             (goto-char (point-min))
             (should-not (search-forward "[#" nil t)))))))))

(defconst jetpacs-org-dialogs-test--tag-group-fixture
  (concat "#+TAGS: { state : hot cold } [ proj : alpha beta ]\n"
          "#+FILETAGS: :alpha:\n"
          "* Parent :hot:\n"
          "** Child :beta:extra:\n")
  "An exclusive group, a plain group, a file tag, and an inherited member.")

(defun jetpacs-org-dialogs-test--tags-dialog (buf headline)
  "Show the tags dialog for HEADLINE in BUF; return (REF . CHILDREN)."
  (let ((ref (jetpacs-org-dialogs-test--ref buf headline)))
    (jetpacs-org-dialogs--show-tags ref buf jetpacs-org-dialogs-test--params)
    (cons ref (append (plist-get (plist-get (jetpacs-org-dialogs-test--last)
                                            :spec)
                                 :children)
                      nil))))

(defun jetpacs-org-dialogs-test--child-by-id (children id)
  "The node in CHILDREN whose :id is ID."
  (seq-find (lambda (c) (equal (plist-get c :id) id)) children))

(defun jetpacs-org-dialogs-test--local-tags (buf headline)
  "HEADLINE's local tags in BUF, sorted."
  (with-current-buffer buf
    (org-with-wide-buffer
     (goto-char (point-min))
     (search-forward headline)
     (sort (copy-sequence (org-get-tags nil t)) #'string<))))

(ert-deftest jetpacs-org-dialogs-tags-picker-seeds-group-lists ()
  "One chip list per buffer group, seeded with the heading's local
members; inherited tags read-only; the spec passes the dialog profile."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--tag-group-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (children (cdr (jetpacs-org-dialogs-test--tags-dialog buf "Child")))
             (state (jetpacs-org-dialogs-test--child-by-id
                     children "org-tags-group-0"))
             (proj (jetpacs-org-dialogs-test--child-by-id
                    children "org-tags-group-1"))
             (loose (jetpacs-org-dialogs-test--child-by-id
                     children "org-tags-loose"))
             (texts (mapcar (lambda (c) (plist-get c :text))
                            (seq-filter (lambda (c) (equal (plist-get c :t) "text"))
                                        children))))
        (should (jetpacs-check-profile
                 (plist-get (jetpacs-org-dialogs-test--last) :spec) 'dialog))
        (should (equal (mapcar (lambda (o) (plist-get o :value))
                               (append (plist-get state :options) nil))
                       '("hot" "cold")))
        (should-not (append (plist-get state :value) nil))
        (should (equal (append (plist-get proj :value) nil) '("beta")))
        (should (equal (append (plist-get loose :value) nil) '("extra")))
        (should (eq (plist-get loose :allow_add) t))
        (should (seq-find (lambda (text) (string-search "(one of)" text)) texts))
        (let ((inherited (seq-find (lambda (text) (string-prefix-p "Inherited" text))
                                   texts)))
          (should inherited)
          (should (string-search "alpha" inherited))
          (should (string-search "hot" inherited)))))))

(ert-deftest jetpacs-org-dialogs-tags-picker-save-unions-fields ()
  "Save unions every chip list with the text field; junk is filtered and
the parent's own tags are untouched."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--tag-group-fixture
      (let ((buf (jetpacs-org-dialogs-test--buffer f)))
        (jetpacs-org-dialogs-test--tags-dialog buf "Child")
        (jetpacs-org-dialogs-test--submit
         (jetpacs-org-dialogs-test--last) "save"
         '(:org-tags-group-0 ["cold"] :org-tags-group-1 ["beta"]
           :org-tags-loose ["extra"] :org-tags ":next:bad tag:"))
        (should (equal (jetpacs-org-dialogs-test--local-tags buf "* Child")
                       '("beta" "cold" "extra" "next")))
        (should (equal (jetpacs-org-dialogs-test--local-tags buf "* Parent")
                       '("hot")))))))

(ert-deftest jetpacs-org-dialogs-tags-picker-empty-save-clears ()
  "An all-empty save clears the local tags; all-junk notifies and writes
nothing."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--tag-group-fixture
      (let ((buf (jetpacs-org-dialogs-test--buffer f))
            (notified nil))
        (cl-letf (((symbol-function 'jetpacs-shell-notify)
                   (lambda (text &rest _) (push text notified))))
          (jetpacs-org-dialogs-test--tags-dialog buf "Child")
          (jetpacs-org-dialogs-test--submit
           (jetpacs-org-dialogs-test--last) "save"
           '(:org-tags-group-0 [] :org-tags-group-1 [] :org-tags-loose []
             :org-tags ""))
          (should-not (jetpacs-org-dialogs-test--local-tags buf "* Child"))
          (should-not notified)
          (jetpacs-org-dialogs-test--tags-dialog buf "Child")
          (jetpacs-org-dialogs-test--submit
           (jetpacs-org-dialogs-test--last) "save" '(:org-tags "bad tag"))
          (should-not (jetpacs-org-dialogs-test--local-tags buf "* Child"))
          (should (member "No valid tags in that" notified)))))))

(ert-deftest jetpacs-org-dialogs-tags-picker-reports-partial-save ()
  "A partial save reports skipped tags; valid-only saves stay quiet."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--tag-group-fixture
      (let ((buf (jetpacs-org-dialogs-test--buffer f)) notified)
        (cl-letf (((symbol-function 'jetpacs-shell-notify)
                   (lambda (text &rest _) (push text notified))))
          (dolist (case '((":next:bad tag:"
                           "Saved tags; skipped 1 invalid tag.")
                          (":next:bad tag:bad!tag:"
                           "Saved tags; skipped 2 invalid tags.")
                          (":next:" nil)))
            (setq notified nil)
            (jetpacs-org-dialogs-test--tags-dialog buf "Child")
            (jetpacs-org-dialogs-test--submit
             (jetpacs-org-dialogs-test--last) "save"
             (list :org-tags-group-1 ["beta"] :org-tags (car case)))
            (should (equal (jetpacs-org-dialogs-test--local-tags buf "* Child")
                           '("beta" "next")))
            (should (equal notified (and (cadr case) (list (cadr case)))))))))))

(ert-deftest jetpacs-org-dialogs-tags-picker-degrades-without-groups ()
  "A buffer with no tag groups still gets the loose list and text field."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (children (cdr (jetpacs-org-dialogs-test--tags-dialog
                             buf "Parent"))))
        (should-not (seq-find (lambda (c)
                                (string-prefix-p "org-tags-group-"
                                                 (or (plist-get c :id) "")))
                              children))
        (should (jetpacs-org-dialogs-test--child-by-id children "org-tags-loose"))
        (should (jetpacs-org-dialogs-test--child-by-id children "org-tags"))))))

(ert-deftest jetpacs-org-dialogs-tags-write-through-charset-filter ()
  "The captured field writes org-charset tags; junk is filtered."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent")))
        (jetpacs-org-dialogs--show-tags
         ref buf jetpacs-org-dialogs-test--params)
        (let* ((shown (jetpacs-org-dialogs-test--last))
               (children (append (plist-get (plist-get shown :spec)
                                            :children)
                                 nil)))
          ;; The loose chip list carries the current local tag; the
          ;; text field starts empty so an unchecked chip cannot survive.
          (should (seq-find (lambda (c)
                              (and (equal (plist-get c :t) "enum_list")
                                   (equal (plist-get c :id) "org-tags-loose")
                                   (member "old" (append (plist-get c :value)
                                                         nil))))
                            children))
          (should (seq-find (lambda (c)
                              (and (equal (plist-get c :t) "text_input")
                                   (equal (plist-get c :id) "org-tags")
                                   (equal (plist-get c :value) "")))
                            children))
          ;; No list field re-submits "old", so the union drops it.
          (jetpacs-org-dialogs-test--submit
           shown "save" '(:org-tags ":work:bad tag:next:"))
          (with-current-buffer buf
            (org-with-wide-buffer
             (goto-char (point-min))
             (search-forward "* TODO Parent")
             (let ((tags (org-get-tags nil t)))
               (should (member "work" tags))
               (should (member "next" tags))
               (should-not (member "old" tags))
               (should-not (seq-find (lambda (tag)
                                       (string-match-p " " tag))
                                     tags))))))))))

;;;; Refile gating

(ert-deftest jetpacs-org-dialogs-refile-is-can-bridge-gated ()
  "With no bridge, the refile flow refuses LOUDLY (snackbar) instead of
raising a prompt nobody can answer (JA-6 audit P2 :284)."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent"))
             (notified nil)
             (prompted nil))
        (cl-letf (((symbol-function 'jetpacs-flow-begin)
                   (lambda (_surface fn) (funcall fn)))
                  ((symbol-function 'jetpacs-dialog-can-bridge-p)
                   (lambda () nil))
                  ((symbol-function 'jetpacs-shell-notify)
                   (lambda (text &optional _s) (push text notified)))
                  ((symbol-function 'org-refile)
                   (lambda (&rest _) (setq prompted t))))
          (jetpacs-org-dialogs--sheet-dispatch
           ref buf "refile" jetpacs-org-dialogs-test--params)
          (should-not prompted)
          (should notified))))))

(ert-deftest jetpacs-org-dialogs-refile-runs-under-flow ()
  "With a bridge, refile runs at the resolved heading and the caches
and buffers are settled afterward."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent"))
             (refiled-at nil))
        (cl-letf (((symbol-function 'jetpacs-flow-begin)
                   (lambda (_surface fn) (funcall fn)))
                  ((symbol-function 'jetpacs-dialog-can-bridge-p)
                   (lambda () t))
                  ((symbol-function 'org-refile)
                   (lambda (&rest _)
                     (interactive)
                     (setq refiled-at (cons (buffer-name)
                                            (org-get-heading t t t t))))))
          (jetpacs-org-dialogs--sheet-dispatch
           ref buf "refile" jetpacs-org-dialogs-test--params)
          (should (equal (cdr refiled-at) "Parent")))))))

(ert-deftest jetpacs-org-dialogs-refile-saves-through-the-policy ()
  "Every buffer refile dirtied is saved by `ebp-org-file-save-function',
never by Org's save-all."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent"))
             (saved nil)
             (ebp-org-file-save-function
              (lambda (b)
                (push b saved)
                (with-current-buffer b (set-buffer-modified-p nil)))))
        (cl-letf (((symbol-function 'jetpacs-flow-begin)
                   (lambda (_surface fn) (funcall fn)))
                  ((symbol-function 'jetpacs-dialog-can-bridge-p)
                   (lambda () t))
                  ((symbol-function 'org-refile)
                   (lambda (&rest _)
                     (interactive)
                     (insert "moved")))
                  ((symbol-function 'org-save-all-org-buffers)
                   (lambda (&rest _) (error "Bypassed the save policy"))))
          (jetpacs-org-dialogs--sheet-dispatch
           ref buf "refile" jetpacs-org-dialogs-test--params)
          (should (equal saved (list buf))))))))

;;;; The tap handlers

(ert-deftest jetpacs-org-dialogs-tap-gates ()
  "The two dialog taps run the sections gate order: shape, exposure,
grant — and only then schedule the dialog."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        (concat jetpacs-org-dialogs-test--sheet-fixture
                "A note[fn:1] here.\n\n[fn:1] Def.\n")
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (name (buffer-name buf))
             ;; Exposure lands at span-RUN starts; the heading line's
             ;; first run starts at its bol (the stars), so that is the
             ;; position a real tap carries — the ref's :pos records
             ;; wherever point stood and may sit mid-run.
             (hpos (with-current-buffer buf
                     (org-with-wide-buffer
                      (goto-char (point-min))
                      (search-forward "* TODO Parent")
                      (line-beginning-position)))))
        ;; Unexposed: rejected.
        (jetpacs-buffer-forget-exposed)
        (should (eq 'rejected
                    (jetpacs-org-dialogs--heading-action
                     (list :buffer name :pos hpos)
                     jetpacs-org-dialogs-test--params)))
        ;; Rendered: the heading line is exposed for the sheet verb and
        ;; the footnote ref for the footnote verb.
        (jetpacs-org-render buf)
        (should (jetpacs-buffer-exposed-p name hpos "jetpacs.org.heading"))
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (search-forward "note[fn:1]")
           (should (jetpacs-buffer-exposed-p
                    name (- (point) 6) "jetpacs.org.footnote"))))
        (should (eq 'accepted
                    (jetpacs-org-dialogs--heading-action
                     (list :buffer name :pos hpos)
                     jetpacs-org-dialogs-test--params)))
        ;; Float pos: rejected on shape (the sections lesson).
        (should (eq 'rejected
                    (jetpacs-org-dialogs--heading-action
                     (list :buffer name :pos (float hpos))
                     jetpacs-org-dialogs-test--params)))))))

;;;; The timestamp editor (JA-5e)

(ert-deftest jetpacs-org-dialogs-ts-seed-shapes ()
  "Seeding decodes date/time/repeater NODE-LEGALLY (times zero-padded,
AUDIT-ja5); a habit's /max tail and delay cookies ride the session so
a save preserves them; an absent stamp seeds today with no repeat."
  (let ((s (jetpacs-org-dialogs--ts-seed "<2026-07-05 Sun 8:34 +1w>")))
    (should (equal (plist-get s :date) "2026-07-05"))
    (should (equal (plist-get s :time) "08:34"))
    (should (equal (plist-get s :rep-type) "+"))
    (should (equal (plist-get s :rep-n) "1"))
    (should (equal (plist-get s :rep-unit) "w")))
  (let ((s (jetpacs-org-dialogs--ts-seed "<2026-07-05 Sun .+2d/4d>")))
    (should (equal (plist-get s :rep-type) ".+"))
    (should (equal (plist-get s :rep-n) "2"))
    (should (equal (plist-get s :rep-unit) "d"))
    (should (equal (plist-get s :rep-tail) "/4d")))
  ;; A delay cookie alone never seeds a repeater (the extractor's
  ;; deliberate exclusion) — but it is CARRIED, not dropped.
  (let ((s (jetpacs-org-dialogs--ts-seed "<2026-07-05 Sun -1d>")))
    (should (equal (plist-get s :rep-type) "none"))
    (should (equal (plist-get s :delay) "-1d")))
  (let ((s (jetpacs-org-dialogs--ts-seed nil)))
    (should (stringp (plist-get s :date)))
    (should-not (plist-get s :time))
    (should (equal (plist-get s :rep-type) "none"))))

(ert-deftest jetpacs-org-dialogs-ts-spec-passes-dialog-profile ()
  "The ts dialog is in-profile, its picks are drop-mode remote
descriptors carrying :sid/:field, and the repeater fields are the
capturable trio."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent")))
        (jetpacs-org-dialogs--ts-open
         (list :kind 'planning :ref ref :which "SCHEDULED")
         "<2026-07-05 Sun +1w>" jetpacs-org-dialogs-test--params)
        (let* ((shown (jetpacs-org-dialogs-test--last))
               (spec (plist-get shown :spec)))
          (should (jetpacs-check-profile spec 'dialog))
          (let ((picks (seq-filter
                        (lambda (d) (equal (plist-get d :action)
                                           "jetpacs.org.ts-pick"))
                        (jetpacs-org-dialogs-test--descriptors spec))))
            (should (= 2 (length picks)))
            (dolist (p picks)
              (should (stringp (plist-get (plist-get p :args) :sid)))
              (should (member (plist-get (plist-get p :args) :field)
                              '("date" "time")))
              (should-not (member (plist-get p :when_offline)
                                  '("queue" "wake"))))))))))

(ert-deftest jetpacs-org-dialogs-ts-pick-gates-and-represents ()
  "A pick must name a live session AND its current dialog; a good pick
stores the value, abandons the old dialog, and re-presents fresh."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent")))
        (jetpacs-org-dialogs--ts-open
         (list :kind 'planning :ref ref :which "SCHEDULED")
         nil jetpacs-org-dialogs-test--params)
        (let* ((sid (cl-loop for k being the hash-keys of
                             jetpacs-org-dialogs--ts-sessions
                             return k))
               (session (gethash sid jetpacs-org-dialogs--ts-sessions))
               (dialog-id (plist-get session :dialog-id))
               (abandoned nil))
          ;; Unknown session / wrong dialog / bad value shape.
          (should (eq 'stale (jetpacs-org-dialogs--ts-pick
                              '(:sid "ts-nope" :field "date"
                                :value "2026-01-01")
                              '(:dialog_id "x"))))
          (should (eq 'stale (jetpacs-org-dialogs--ts-pick
                              (list :sid sid :field "date"
                                    :value "2026-01-01")
                              '(:dialog_id "not-the-current-one"))))
          (should (eq 'rejected (jetpacs-org-dialogs--ts-pick
                                 (list :sid sid :field "date"
                                       :value "not-a-date")
                                 (list :dialog_id dialog-id))))
          ;; The good pick: immediate-fire the deferred re-present.
          (cl-letf (((symbol-function 'run-at-time)
                     (lambda (_time _repeat fn &rest args)
                       (apply fn args)))
                    ((symbol-function 'ebp-client-abandon)
                     (lambda (_c id) (push id abandoned))))
            (should (eq 'accepted (jetpacs-org-dialogs--ts-pick
                                   (list :sid sid :field "date"
                                         :value "2026-08-01")
                                   (list :dialog_id dialog-id)))))
          (should (equal abandoned
                         (list (plist-get session :request-id))))
          (let ((fresh (gethash sid jetpacs-org-dialogs--ts-sessions)))
            (should (equal (plist-get fresh :date) "2026-08-01"))
            (should-not (equal (plist-get fresh :dialog-id) dialog-id)))
          ;; Two dialogs shown: the original and the re-present.
          (should (= 2 (length jetpacs-org-dialogs-test--shown))))))))

(ert-deftest jetpacs-org-dialogs-ts-save-planning-and-repeater ()
  "Save writes the planning line through the engine's two-step cookie
surgery; the captured repeater fields override the seed; Clear removes."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent")))
        (jetpacs-org-dialogs--ts-open
         (list :kind 'planning :ref ref :which "SCHEDULED")
         nil jetpacs-org-dialogs-test--params)
        (let ((session (cl-loop for s being the hash-values of
                                jetpacs-org-dialogs--ts-sessions
                                return s)))
          (jetpacs-org-dialogs--ts-conclude
           (plist-put (copy-sequence session) :date "2026-08-02")
           "save"
           '(:ts-rep-type "+" :ts-rep-n "2" :ts-rep-unit "w")))
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (should (re-search-forward
                    "SCHEDULED: <2026-08-02 [A-Za-z]\\{3\\} \\+2w>"
                    nil t))))
        ;; Clear.
        (jetpacs-org-dialogs--ts-open
         (list :kind 'planning :ref ref :which "SCHEDULED")
         nil jetpacs-org-dialogs-test--params)
        (let ((session (cl-loop for s being the hash-values of
                                jetpacs-org-dialogs--ts-sessions
                                return s)))
          (jetpacs-org-dialogs--ts-conclude session "clear" nil))
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (should-not (search-forward "SCHEDULED:" nil t))))))))

(ert-deftest jetpacs-org-dialogs-ts-body-rewrite-guarded ()
  "A body stamp rewrites literally in place (bracket kept, day name
recomputed); a stamp that MOVED is a no-op plus a snackbar."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        "* H\nSee [2026-07-04 Sat] for details.\n"
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (name (buffer-name buf))
             (pos (with-current-buffer buf
                    (org-with-wide-buffer
                     (goto-char (point-min))
                     (search-forward "[2026")
                     (match-beginning 0)))))
        (let ((session (list :target (list :kind 'body :buffer name
                                           :pos pos :bracket ?\[)
                             :params jetpacs-org-dialogs-test--params
                             :date "2026-12-25" :time "09:30"
                             :rep-type "none" :rep-n "1" :rep-unit "w")))
          (jetpacs-org-dialogs--ts-conclude session "save" nil)
          (with-current-buffer buf
            (org-with-wide-buffer
             (goto-char (point-min))
             (should (search-forward "[2026-12-25 Fri 09:30]" nil t))))
          ;; Shift the buffer: the recorded position no longer holds a
          ;; stamp — nothing is struck.
          (with-current-buffer buf
            (goto-char (point-min))
            (insert "shift\n"))
          (let ((before (with-current-buffer buf (buffer-string))))
            (jetpacs-org-dialogs--ts-conclude session "save" nil)
            (should (equal before
                           (with-current-buffer buf (buffer-string))))))))))

(ert-deftest jetpacs-org-dialogs-timestamp-tap-and-bare-keyword ()
  "A rendered stamp is exposed for the timestamp verb and its handler
accepts; a bare `SCHEDULED:' with no stamp exposes nothing (the
malformed-planning trap from org_parser)."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        "* H\nSCHEDULED: <2026-07-05 Sun>\nSCHEDULED: \nbody\n"
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (name (buffer-name buf)))
        (jetpacs-org-render buf)
        (let ((pos (with-current-buffer buf
                     (org-with-wide-buffer
                      (goto-char (point-min))
                      (search-forward "<2026")
                      (match-beginning 0)))))
          (should (jetpacs-buffer-exposed-p
                   name pos "jetpacs.org.timestamp"))
          (should (eq 'accepted
                      (jetpacs-org-dialogs--timestamp-action
                       (list :buffer name :pos pos)
                       jetpacs-org-dialogs-test--params))))
        ;; The bare keyword line: no timestamp exposure anywhere on it.
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (search-forward "SCHEDULED: \n")
           (let ((bol (match-beginning 0)))
             (cl-loop for p from bol below (+ bol 11)
                      do (should-not
                          (jetpacs-buffer-exposed-p
                           name p "jetpacs.org.timestamp"))))))))))

;;;; The log-note dialog (JA-5e)

(ert-deftest jetpacs-org-dialogs-log-note-follows-a-cancelled-note ()
  "A toggle that cancels a free-text note is followed by the note
dialog; Save writes a LOGBOOK line `ebp-org-parse-logbook' reads
back; dismissing keeps today's cancel."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent"))
             (org-log-done 'note)
             ;; The write lands wherever `org-log-beginning' says — the
             ;; user's own config.  Configure the drawer so the engine
             ;; parser assertion below applies (default nil = body).
             (org-log-into-drawer t))
        ;; Immediate-fire only the note dialog's deferral (no ts-open
        ;; in this path, so no sweep-timer recursion).
        (cl-letf (((symbol-function 'run-at-time)
                   (lambda (_time _repeat fn &rest args) (apply fn args))))
          (jetpacs-org-dialogs--sheet-dispatch
           ref buf "todo" jetpacs-org-dialogs-test--params))
        (let ((shown (jetpacs-org-dialogs-test--last)))
          (should shown)
          (should (jetpacs-check-profile (plist-get shown :spec) 'dialog))
          (jetpacs-org-dialogs-test--submit
           shown "save" '(:org-note "Finished on the tablet"))
          (with-current-buffer buf
            (org-with-wide-buffer
             (goto-char (point-min))
             (should (search-forward "- Note taken on [" nil t))
             (should (search-forward "Finished on the tablet" nil t))
             ;; The engine's parser reads the line back as a note.
             (goto-char (point-min))
             (search-forward "* DONE Parent")
             (let ((entries (ebp-org-logbook-entries (point))))
               (should (seq-find
                        (lambda (e)
                          (and (eq (plist-get e :type) 'note)
                               (string-match-p "Finished on the tablet"
                                               (plist-get e :content))))
                        entries))))))))))

;;;; add-heading (JA-5f)

(ert-deftest jetpacs-org-dialogs-add-heading-gates ()
  "Unexposed, non-file and outside-roots buffers are rejected; the
armed case defers to the flow and answers accepted."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f "* Existing\n"
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (name (buffer-name buf))
             (flowed nil))
        (cl-letf (((symbol-function 'jetpacs-flow-continue)
                   (lambda (fn) (setq flowed fn))))
          ;; Unexposed.
          (jetpacs-buffer-forget-exposed)
          (should (eq 'rejected
                      (jetpacs-org-dialogs--add-heading
                       (list :buffer name)
                       jetpacs-org-dialogs-test--params)))
          ;; A file outside the org roots.
          (jetpacs-org-add-heading-descriptor name)
          (let ((ebp-org-roots
                 (list (file-name-as-directory
                        (make-temp-file "ja5f-other" t)))))
            (should (eq 'rejected
                        (jetpacs-org-dialogs--add-heading
                         (list :buffer name)
                         jetpacs-org-dialogs-test--params))))
          ;; Armed and in-root.
          (jetpacs-org-add-heading-descriptor name)
          (should (eq 'accepted
                      (jetpacs-org-dialogs--add-heading
                       (list :buffer name)
                       jetpacs-org-dialogs-test--params)))
          (should (functionp flowed)))))))

(ert-deftest jetpacs-org-dialogs-add-heading-flow-inserts-and-saves ()
  "The bridged prompt appends `* TITLE' at wide point-max (newlines
flattened) and runs the engine's file-save seam; a blank or quit
answer is a loud no-op."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f "* Existing\nbody"
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (saved nil)
             (ebp-org-file-save-function
              (lambda (b) (setq saved (buffer-name b)))))
        (cl-letf (((symbol-function 'jetpacs-dialog-can-bridge-p)
                   (lambda () t))
                  ((symbol-function 'read-string)
                   (lambda (&rest _) "  Captured\non the go  ")))
          (jetpacs-org-dialogs--add-heading-flow
           buf jetpacs-org-dialogs-test--params))
        (should (equal saved (buffer-name buf)))
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (should (search-forward "* Captured on the go\n" nil t))))
        ;; Cancelled: nothing inserted, nothing saved.
        (setq saved nil)
        (let ((before (with-current-buffer buf (buffer-string))))
          (cl-letf (((symbol-function 'jetpacs-dialog-can-bridge-p)
                     (lambda () t))
                    ((symbol-function 'read-string)
                     (lambda (&rest _) (keyboard-quit))))
            (jetpacs-org-dialogs--add-heading-flow
             buf jetpacs-org-dialogs-test--params))
          (should-not saved)
          (should (equal before
                         (with-current-buffer buf (buffer-string)))))))))

(ert-deftest jetpacs-org-dialogs-add-heading-is-can-bridge-gated ()
  "With no bridge the flow refuses loudly — never a desktop prompt."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f "* Existing\n"
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (notified nil)
             (prompted nil))
        (cl-letf (((symbol-function 'jetpacs-dialog-can-bridge-p)
                   (lambda () nil))
                  ((symbol-function 'jetpacs-shell-notify)
                   (lambda (text &optional _s) (push text notified)))
                  ((symbol-function 'read-string)
                   (lambda (&rest _) (setq prompted t) "X")))
          (jetpacs-org-dialogs--add-heading-flow
           buf jetpacs-org-dialogs-test--params)
          (should-not prompted)
          (should notified))))))

;;;; AUDIT-ja5 regressions (each pins a confirmed finding)

(ert-deftest jetpacs-org-dialogs-schedule-arm-maps-scheduled ()
  "P1: (upcase \"schedule\") is \"SCHEDULE\" — the arm shipped unable to
write.  The mapping is explicit now; both arms name real engine keys."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent"))
             (opened nil))
        (cl-letf (((symbol-function 'jetpacs-org-dialogs--ts-open)
                   (lambda (target _stamp _params) (push target opened)))
                  ((symbol-function 'run-at-time)
                   (lambda (_t _r fn &rest args) (apply fn args))))
          (jetpacs-org-dialogs--sheet-dispatch
           ref buf "schedule" jetpacs-org-dialogs-test--params)
          (jetpacs-org-dialogs--sheet-dispatch
           ref buf "deadline" jetpacs-org-dialogs-test--params))
        (should (equal (mapcar (lambda (tgt) (plist-get tgt :which))
                               (nreverse opened))
                       '("SCHEDULED" "DEADLINE")))))))

(ert-deftest jetpacs-org-dialogs-copy-button-needs-dialog-profile ()
  "P1: the Copy builtin ships inside a DIALOG spec, so the DIALOG
profile's advertisement governs — an app-profile gate emitted a
builtin the reference Companion's dialog validator 1201s."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        "A note[fn:1] here.\n\n[fn:1] The definition.\n"
      ;; App advertises clipboard.copy; the dialog profile does NOT —
      ;; the reference Companion's exact shape.
      (setf (ebp-client-profiles (jetpacs-client))
            '(:app (:node_types ["text" "rich_text" "button" "column"]
                    :builtins ["clipboard.copy" "dialog.submit"
                               "dialog.dismiss"])
              :dialog (:node_types ["text" "rich_text" "button" "row"
                                    "column" "text_input" "enum_list"
                                    "date_button" "time_button"]
                       :builtins ["dialog.submit" "dialog.dismiss"])))
      (let ((buf (jetpacs-org-dialogs-test--buffer f)))
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (search-forward "note[fn:1]")
           (jetpacs-org-dialogs--show-footnote
            buf (- (point) 6) jetpacs-org-dialogs-test--params)))
        (let* ((spec (plist-get (jetpacs-org-dialogs-test--last) :spec))
               (builtins (mapcar (lambda (d) (plist-get d :builtin))
                                 (jetpacs-org-dialogs-test--descriptors
                                  spec))))
          (should-not (member "clipboard.copy" builtins)))))))

(ert-deftest jetpacs-org-dialogs-rep-unit-injection-blocked ()
  "P1 (23.1): a crafted repeater UNIT reached the org file verbatim —
a device could smuggle a heading.  Every captured field is now
shape-checked; a failing member is dropped, never written."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent")))
        (jetpacs-org-dialogs--ts-open
         (list :kind 'planning :ref ref :which "SCHEDULED")
         nil jetpacs-org-dialogs-test--params)
        (let ((session (cl-loop for s being the hash-values of
                                jetpacs-org-dialogs--ts-sessions
                                return s)))
          (jetpacs-org-dialogs--ts-conclude
           (plist-put (copy-sequence session) :date "2026-08-02")
           "save"
           '(:ts-rep-type "+" :ts-rep-n "1"
             :ts-rep-unit "w>\n* EVIL :tag:")))
        (with-current-buffer buf
          (org-with-wide-buffer
           (should-not (save-excursion (goto-char (point-min))
                                       (search-forward "EVIL" nil t)))
           ;; The invalid unit fell back to the seed; the legal cookie
           ;; still wrote.
           (goto-char (point-min))
           (should (re-search-forward
                    "SCHEDULED: <2026-08-02 [A-Za-z]\\{3\\} \\+1w>"
                    nil t))))))))

(ert-deftest jetpacs-org-dialogs-ts-seed-legal-and-preserving ()
  "P2: a 1-digit hour and an hourly repeater are legal org that the
dialog nodes reject — the spec build signaled inside the timer and the
tap was silently dead.  Seeds are node-legal now, and the cookies the
editor cannot represent (raw hourly repeater, /max tail, delay) ride
the session and survive a body save."
  (let ((s (jetpacs-org-dialogs--ts-seed "<2026-07-30 Thu 9:30 +2h>")))
    (should (equal (plist-get s :time) "09:30"))
    (should (equal (plist-get s :rep-type) "none"))
    (should (equal (plist-get s :rep-raw) "+2h")))
  (let ((s (jetpacs-org-dialogs--ts-seed "<2026-07-20 Mon .+2d/4d -1d>")))
    (should (equal (plist-get s :rep-type) ".+"))
    (should (equal (plist-get s :rep-tail) "/4d"))
    (should (equal (plist-get s :delay) "-1d")))
  ;; The build itself: a legal-org stamp yields a dialog, not a signal.
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        "* H\nSee <2026-07-30 Thu 9:30 +2h> then.\n"
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (name (buffer-name buf))
             (pos (with-current-buffer buf
                    (org-with-wide-buffer
                     (goto-char (point-min))
                     (search-forward "<2026")
                     (match-beginning 0)))))
        (jetpacs-org-dialogs--ts-open
         (list :kind 'body :buffer name :pos pos :bracket ?<)
         "<2026-07-30 Thu 9:30 +2h>" jetpacs-org-dialogs-test--params)
        (let ((shown (jetpacs-org-dialogs-test--last)))
          (should shown)
          (should (jetpacs-check-profile (plist-get shown :spec) 'dialog)))
        ;; Saving preserves the unrepresentable hourly cookie.
        (let ((session (cl-loop for s being the hash-values of
                                jetpacs-org-dialogs--ts-sessions
                                return s)))
          (jetpacs-org-dialogs--ts-body-write
           (plist-get session :target)
           (plist-put (copy-sequence session) :date "2026-08-01")
           "save"))
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (should (search-forward "+2h>" nil t))))))))

(ert-deftest jetpacs-org-dialogs-inline-footnote-shows-definition ()
  "P2: nth 3 of `org-footnote-at-reference-p' IS the inline definition
string; reading it as a boolean showed the [fn:...] wrapper instead."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        "Body text[fn:note:the actual definition].\n"
      (let ((buf (jetpacs-org-dialogs-test--buffer f)))
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (search-forward "[fn:")
           (let ((info (jetpacs-org-dialogs--footnote-info
                        buf (match-beginning 0))))
             (should (plist-get info :inline))
             (should (equal (plist-get info :definition)
                            "the actual definition")))))))))

(ert-deftest jetpacs-org-dialogs-labelled-footnote-records-definition-position ()
  "The definition button carries an actual scroll target, not a line hint."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        "Body[fn:note].\n\n[fn:note] Definition.\n"
      (let ((buf (jetpacs-org-dialogs-test--buffer f)))
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (search-forward "[fn:note]")
           (let ((info (jetpacs-org-dialogs--footnote-info
                        buf (match-beginning 0))))
             (should (integerp (plist-get info :def-pos)))
             (goto-char (plist-get info :def-pos))
             (should (org-footnote-at-definition-p)))))))))

(ert-deftest jetpacs-org-dialogs-sheet-refuses-rotted-pos ()
  "P2 (14.5): a rotted pos must answer \"gone\", never resolve to
whatever heading now encloses it — every sheet arm and the Archive
token would target the WRONG subtree."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (body-pos (with-current-buffer buf
                         (org-with-wide-buffer
                          (goto-char (point-min))
                          (search-forward "Body.")
                          (match-beginning 0))))
             (notified nil))
        (should-not (jetpacs-org-dialogs--ref-at buf body-pos))
        (cl-letf (((symbol-function 'jetpacs-shell-notify)
                   (lambda (text &optional _s) (push text notified))))
          (jetpacs-org-dialogs--show-sheet
           buf body-pos jetpacs-org-dialogs-test--params))
        (should (equal notified '("No heading there")))
        (should-not jetpacs-org-dialogs-test--shown)))))

(ert-deftest jetpacs-org-dialogs-archive-dialog-context-surface ()
  "P2: the Archive event arrives in DIALOG context (:dialog_id, no
:surface) — feedback and the re-push must target the surface the sheet
was opened FROM, not the shell default."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (notified-to nil) (refreshed-to nil))
        (jetpacs-org-dialogs-test--show-sheet buf)
        (let ((token (plist-get jetpacs-org-dialogs--sheet :token)))
          (cl-letf (((symbol-function 'jetpacs-shell-notify)
                     (lambda (_text &optional s) (push s notified-to)))
                    ((symbol-function 'jetpacs-buffer-defer-refresh)
                     (lambda (s) (push s refreshed-to)))
                    ((symbol-function 'ebp-client-abandon) #'ignore))
            (should (eq 'accepted
                        (jetpacs-org-dialogs--archive
                         (list :token token)
                         '(:dialog_id "sheet-dialog-1"))))))
        (should (equal notified-to '("app:ja5d")))
        (should (equal refreshed-to '("app:ja5d")))))))

(ert-deftest jetpacs-org-dialogs-footnote-drill-targets-tap-surface ()
  "P2: the Go-to-definition drill must land on the TAPPED surface — a
bare navigate from a timer resolves to the shell default."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        "A note[fn:1] here.\n\n[fn:1] Def.\n"
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (navigated-to 'unset)
             (navigated-pos nil))
        (with-current-buffer buf
          (org-with-wide-buffer
           (goto-char (point-min))
           (search-forward "note[fn:1]")
           (jetpacs-org-dialogs--show-footnote
            buf (- (point) 6) jetpacs-org-dialogs-test--params)))
        (cl-letf (((symbol-function 'run-at-time)
                   (lambda (_t _r fn &rest args) (apply fn args)))
                  ((symbol-function 'jetpacs-navigate-buffer)
                   (lambda (_buf &optional surface _label mark-pos)
                     (setq navigated-to surface
                           navigated-pos mark-pos))))
          (jetpacs-org-dialogs-test--submit
           (jetpacs-org-dialogs-test--last) "edit"))
        (should (equal navigated-to "app:ja5d"))
        (should (integerp navigated-pos))))))

(ert-deftest jetpacs-org-dialogs-pick-preserves-repeater-edits ()
  "P2 (14.1): the pick descriptors CAPTURE the repeater trio, and the
handler merges the captured values before re-presenting — a date pick
no longer wipes uncommitted repeater edits."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        jetpacs-org-dialogs-test--sheet-fixture
      (let* ((buf (jetpacs-org-dialogs-test--buffer f))
             (ref (jetpacs-org-dialogs-test--ref buf "Parent")))
        (jetpacs-org-dialogs--ts-open
         (list :kind 'planning :ref ref :which "SCHEDULED")
         nil jetpacs-org-dialogs-test--params)
        ;; The descriptors carry the capture set.
        (let* ((spec (plist-get (jetpacs-org-dialogs-test--last) :spec))
               (picks (seq-filter
                       (lambda (d) (equal (plist-get d :action)
                                          "jetpacs.org.ts-pick"))
                       (jetpacs-org-dialogs-test--descriptors spec))))
          (dolist (p picks)
            (should (equal (append (plist-get p :capture_fields) nil)
                           '("ts-rep-type" "ts-rep-n" "ts-rep-unit")))))
        (let* ((sid (cl-loop for k being the hash-keys of
                             jetpacs-org-dialogs--ts-sessions
                             return k))
               (dialog-id (plist-get (gethash
                                      sid jetpacs-org-dialogs--ts-sessions)
                                     :dialog-id)))
          (cl-letf (((symbol-function 'run-at-time)
                     (lambda (_t _r fn &rest args) (apply fn args)))
                    ((symbol-function 'ebp-client-abandon) #'ignore))
            (should (eq 'accepted
                        (jetpacs-org-dialogs--ts-pick
                         (list :sid sid :field "date"
                               :value "2026-09-01")
                         (list :dialog_id dialog-id
                               :fields '(:ts-rep-type "++"
                                         :ts-rep-n "2"
                                         :ts-rep-unit "m"))))))
          (let ((fresh (gethash sid jetpacs-org-dialogs--ts-sessions)))
            (should (equal (plist-get fresh :date) "2026-09-01"))
            (should (equal (plist-get fresh :rep-type) "++"))
            (should (equal (plist-get fresh :rep-n) "2"))
            (should (equal (plist-get fresh :rep-unit) "m"))))))))


;;;; Ref-addressed heading mutations (the token verbs' built-in cores)

(ert-deftest jetpacs-org-heading-at-ref-classifies-statuses ()
  "The funnel: no ref -> stale, refused -> rejected, unresolved -> stale,
success -> accepted with the whole memo dropped (or the save function
run), a signal from the body -> rejected, never accepted."
  ;; The funnel loads the save policy lazily; load it first so the stub
  ;; below is not clobbered by that load.
  (require 'jetpacs-org-save)
  (should (eq (jetpacs-org-heading-at-ref nil #'ignore) 'stale))
  (let ((ref '(:id nil :file "/vault/tasks.org" :pos 1 :headline "H")))
    (cl-letf (((symbol-function 'ebp-org-resolve-ref)
               (lambda (_) (signal 'ebp-org-refused nil))))
      (should (eq (jetpacs-org-heading-at-ref ref #'ignore) 'rejected)))
    (cl-letf (((symbol-function 'ebp-org-resolve-ref)
               (lambda (_) (signal 'ebp-org-unresolved nil))))
      (should (eq (jetpacs-org-heading-at-ref ref #'ignore) 'stale)))
    (with-temp-buffer
      (org-mode)
      (insert "* Heading\n")
      (let ((m (copy-marker (point-min)))
            (at nil) (invalidated nil) (saved nil))
        (cl-letf (((symbol-function 'ebp-org-resolve-ref)
                   (lambda (_) (copy-marker m)))
                  ((symbol-function 'ebp-org-cache-invalidate)
                   (lambda (&optional ns) (push ns invalidated)))
                  ((symbol-function 'jetpacs-org-save-policy)
                   (lambda (&optional buffer) (push (cons 'policy buffer) saved))))
          (should (eq (jetpacs-org-heading-at-ref
                       ref (lambda () (setq at (point))))
                      'accepted))
          (should (equal at (point-min)))
          (should (equal invalidated '(nil)))
          (should-not saved)
          (should (eq (jetpacs-org-heading-at-ref ref #'ignore t) 'accepted))
          (should (equal saved (list (cons 'policy (current-buffer)))))
          (should (eq (jetpacs-org-heading-at-ref
                       ref #'ignore
                       (lambda (buffer) (push (cons 'app buffer) saved)))
                      'accepted))
          (should (equal (car saved) (cons 'app (current-buffer))))
          (should (eq (jetpacs-org-heading-at-ref
                       ref (lambda () (error "boom")))
                      'rejected)))))))

(ert-deftest jetpacs-org-heading-mutation-cores ()
  "Each core is vanilla Org on the heading at point: planning that keeps
the time of day and repeater, TODO set/cycle, priority, tags, note,
properties, duplicate and delete."
  (with-temp-buffer
    (org-mode)
    (insert "* Task\nSCHEDULED: <2026-08-14 Fri 13:30-14:00 +1w>\nbody\n"
            "* Next\n")
    (let ((org-todo-keywords '((sequence "TODO" "NEXT" "|" "DONE")))
          (org-log-done nil)
          (org-log-into-drawer nil))
      (org-mode-restart)
      (goto-char (point-min))
      ;; Planning: the day moves, time of day and repeater survive.
      (jetpacs-org-heading-set-planning "SCHEDULED" "2026-08-20")
      (should (equal (org-entry-get nil "SCHEDULED")
                     "<2026-08-20 Thu 13:30-14:00 +1w>"))
      (jetpacs-org-heading-set-planning "DEADLINE" "2026-09-01")
      (should (equal (org-entry-get nil "DEADLINE") "<2026-09-01 Tue>"))
      ;; Clear the repeating stamp before cycling: a repeater would make
      ;; Org's auto-repeat reset DONE straight back to TODO.
      (jetpacs-org-heading-clear-planning "SCHEDULED")
      (should-not (org-entry-get nil "SCHEDULED"))
      (should (org-entry-get nil "DEADLINE"))
      ;; TODO: set, clear, cycle (skipping the empty state).
      (jetpacs-org-heading-set-todo "NEXT")
      (should (equal (org-get-todo-state) "NEXT"))
      (jetpacs-org-heading-set-todo "")
      (should-not (org-get-todo-state))
      (should (equal (jetpacs-org-heading-cycle-todo) "TODO"))
      (should (equal (jetpacs-org-heading-cycle-todo) "NEXT"))
      (should (equal (jetpacs-org-heading-cycle-todo) "DONE"))
      ;; Cycling past DONE lands on TODO again, never on the empty state.
      (should (equal (jetpacs-org-heading-cycle-todo) "TODO"))
      ;; Priority.
      (jetpacs-org-heading-set-priority "b")
      (should (string-search "[#B]" (org-get-heading)))
      (jetpacs-org-heading-set-priority "")
      (should-not (string-search "[#" (org-get-heading)))
      ;; Tags: validity then write.
      (should (jetpacs-org-tags-valid-p '("home" "work_1" "@ctx")))
      (should (jetpacs-org-tags-valid-p ["home"]))
      (should-not (jetpacs-org-tags-valid-p '("has space")))
      (should-not (jetpacs-org-tags-valid-p '(42)))
      (should-not (jetpacs-org-tags-valid-p "home"))
      (jetpacs-org-heading-set-tags '("home" "work"))
      (should (equal (org-get-tags nil t) '("home" "work")))
      ;; Properties.
      (should (jetpacs-org-property-name-valid-p "EFFORT"))
      (should-not (jetpacs-org-property-name-valid-p "HAS SPACE"))
      (should-not (jetpacs-org-property-name-valid-p "A:B"))
      (should-not (jetpacs-org-property-name-valid-p ""))
      (jetpacs-org-heading-set-property "EFFORT" "2h")
      (should (equal (org-entry-get nil "EFFORT") "2h"))
      (jetpacs-org-heading-set-property "EFFORT" "")
      (should-not (org-entry-get nil "EFFORT"))
      ;; A logbook note in Org's own format, inside a drawer.
      (jetpacs-org-heading-insert-note "two\nlines")
      (let ((text (buffer-string)))
        (should (string-search ":LOGBOOK:\n- Note taken on [" text))
        (should (string-search "] \\\\\n  two\n  lines\n:END:" text)))
      ;; Duplicate inserts the copy right after the subtree; delete removes it.
      (goto-char (point-min))
      (jetpacs-org-heading-duplicate-subtree)
      (goto-char (point-min))
      (should (= 2 (count-matches "^\\* TODO Task")))
      (should (= 1 (count-matches "^\\* Next")))
      (goto-char (point-min))
      (org-forward-heading-same-level 1)
      (should (looking-at "\\* TODO Task"))
      (jetpacs-org-heading-delete-subtree)
      (goto-char (point-min))
      (should (= 1 (count-matches "^\\* TODO Task")))
      (should (= 1 (count-matches "^\\* Next"))))))

(ert-deftest jetpacs-org-heading-date-helpers ()
  "The pure helpers behind planning edits."
  (should (equal (jetpacs-org-ts-time-range "<2026-08-14 Fri 13:30-14:00>")
                 "13:30-14:00"))
  (should (equal (jetpacs-org-ts-time-range "<2026-08-14 Fri 9:05 +1w>") "9:05"))
  (should-not (jetpacs-org-ts-time-range "<2026-08-14 Fri>"))
  (should-not (jetpacs-org-ts-time-range nil))
  (should (equal (jetpacs-org-relative-date "2026-08-14" "+0d") "2026-08-14"))
  (should (equal (jetpacs-org-relative-date "2026-08-14" "+3d") "2026-08-17"))
  (should (equal (jetpacs-org-relative-date "2026-08-14" "+1w") "2026-08-21"))
  (should-not (jetpacs-org-relative-date "2026-08-14" "tomorrow"))
  (should-not (jetpacs-org-relative-date "2026-08-14" nil)))

(ert-deftest jetpacs-org-heading-refile-saves-every-dirty-buffer ()
  "Refile moves the subtree to the chosen target and runs SAVE over each
modified Org file buffer — source and target alike."
  (jetpacs-org-dialogs-test--with-file source "* Move me\nbody\n* Stay\n"
    (let* ((target-file (expand-file-name "target.org" dir))
           (org-refile-targets nil)
           (org-agenda-files (list target-file))
           saved)
      (with-temp-file target-file (insert "* Inbox\n"))
      (let ((src (find-file-noselect source))
            (dst (find-file-noselect target-file)))
        (unwind-protect
            (with-current-buffer src
              (org-mode)
              (goto-char (point-min))
              (let ((targets (jetpacs-org-heading-refile-targets)))
                (should (cl-find "Inbox" targets :key #'car :test #'string-search))
                (jetpacs-org-heading-refile
                 (cl-find "Inbox" targets :key #'car :test #'string-search)
                 (lambda (buffer)
                   (push buffer saved)
                   (with-current-buffer buffer (save-buffer)))))
              (should (= 2 (length saved)))
              (should (memq src saved))
              (should (memq dst saved))
              (with-temp-buffer
                (insert-file-contents target-file)
                (should (string-search "** Move me\nbody" (buffer-string))))
              (with-temp-buffer
                (insert-file-contents source)
                (should-not (string-search "Move me" (buffer-string)))
                (should (string-search "* Stay" (buffer-string)))))
          (dolist (buffer (list src dst))
            (when (buffer-live-p buffer)
              (with-current-buffer buffer (set-buffer-modified-p nil))
              (kill-buffer buffer))))))))

;;;; File properties

(ert-deftest jetpacs-org-dialogs-update-keyword-inserts-after-title ()
  "An existing keyword is replaced in place; a new one lands after
#+TITLE; an empty value removes the line."
  (with-temp-buffer
    (insert "#+TITLE: Old\n#+AUTHOR: Me\n* Heading\n")
    (jetpacs-org-dialogs--update-keyword "TITLE" "New")
    (jetpacs-org-dialogs--update-keyword "CATEGORY" "cat")
    (jetpacs-org-dialogs--update-keyword "AUTHOR" "")
    (should (equal (buffer-string)
                   "#+TITLE: New\n#+CATEGORY: cat\n* Heading\n"))
    (jetpacs-org-dialogs--update-keyword "CATEGORY" "dog")
    (should (equal (buffer-string)
                   "#+TITLE: New\n#+CATEGORY: dog\n* Heading\n"))))

(ert-deftest jetpacs-org-dialogs-file-properties-spec-passes-dialog-profile ()
  "The show sends one spec through the foundation's slot, it passes the
dialog profile, and its Save is the ownerless strict save verb."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f
        "#+TITLE: Doc\n#+FILETAGS: :a:b:\n#+TODO: TODO NEXT | DONE\n* H\n"
      (jetpacs-org-dialogs--show-file-properties
       f jetpacs-org-dialogs-test--params)
      (should (= 1 (length jetpacs-org-dialogs-test--shown)))
      (let* ((spec (plist-get (car jetpacs-org-dialogs-test--shown) :spec))
             (json (jetpacs-node->canonical-json spec)))
        (should (jetpacs-check-profile spec 'dialog))
        (should (string-search "jetpacs.org.file-properties.save" json))
        (should (string-search "TODO, NEXT" json)))
      (should (jetpacs-dialog-slot-live-p jetpacs-org-dialogs-owner))
      (should (plist-get (jetpacs-action-schema
                          "jetpacs.org.file-properties.save")
                         :strict))
      (should-not (jetpacs--owner-of "action"
                                     "jetpacs.org.file-properties.save")))))

(ert-deftest jetpacs-org-dialogs-file-properties-save-replaces-keywords ()
  "Save rewrites the keywords in place (one #+TITLE line afterwards),
durably, then closes the slot; no fields or a path outside the roots
rejects."
  (jetpacs-org-dialogs-test--with-env
    (jetpacs-org-dialogs-test--with-file f "#+TITLE: Old\n* H\n"
      (let ((save (gethash "jetpacs.org.file-properties.save"
                           jetpacs-action-handlers))
            (disk (lambda () (with-temp-buffer (insert-file-contents f)
                                               (buffer-string)))))
        (cl-letf (((symbol-function 'jetpacs-buffer-defer-refresh) #'ignore))
          (jetpacs-org-dialogs--show-file-properties
           f jetpacs-org-dialogs-test--params)
          (should (eq (funcall save (list :path f)
                               '(:fields (:file-prop-title "Renamed"
                                          :file-prop-category "cat"
                                          :file-prop-tags ["x" "y"])))
                      'accepted))
          (should (equal (funcall disk)
                         "#+TITLE: Renamed\n#+CATEGORY: cat\n#+FILETAGS: :x:y:\n* H\n"))
          (should (= 1 (with-temp-buffer (insert (funcall disk))
                                         (how-many "^#\\+TITLE:"
                                                   (point-min) (point-max)))))
          (should-not (jetpacs-dialog-slot-live-p jetpacs-org-dialogs-owner))
          (should (eq (funcall save (list :path f) nil) 'rejected))
          (should (eq (funcall save '(:path "/elsewhere/x.org")
                               '(:fields (:file-prop-title "No")))
                      'rejected))
          (should (eq (funcall save (list :path (concat f ".txt"))
                               '(:fields (:file-prop-title "No")))
                      'rejected)))))))

(provide 'jetpacs-org-dialogs-test)
;;; jetpacs-org-dialogs-test.el ends here
