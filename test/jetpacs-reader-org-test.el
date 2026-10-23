;;; jetpacs-reader-org-test.el --- The reader's inline editors -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; The exposure-gated editing sessions beside the Org reader, ported with
;; them from the downstream app that first shipped them: the inline source
;; draft (open, deterministic editor identities, save body + language
;; without losing Org metadata, cancel, invalid fields, source/disk drift
;; and failed-write rollback), the Babel call argument draft (save
;; preserves syntax and never evaluates; execute saves separate results
;; and goes stale), and the footnote dialog (read, switch to edit on a
;; fresh identity, save exactly one definition, refuse invalid Org and
;; drift).  Handlers are driven directly with the args a render would
;; emit; exposures are recorded by hand.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'ebp)
(require 'jetpacs-reader-org)
(require 'jetpacs-org-save)             ; the save policy the tests stub

(defmacro jetpacs-reader-org-test--with-file (file content &rest body)
  "Bind FILE to a fresh root's code.org holding CONTENT, visited and current."
  (declare (indent 2))
  `(let* ((root (file-truename (make-temp-file "jetpacs-reader-org" t)))
          (,file (expand-file-name "code.org" root))
          (ebp-org-roots (list root))
          (org-babel-load-languages '((emacs-lisp . t) (python . t) (shell . nil)))
          (jetpacs-reader-org--source-edit nil)
          (jetpacs-reader-org--footnote-dialog nil)
          (jetpacs-buffer-exposed (make-hash-table :test #'equal))
          (jetpacs-node-id-claims (make-hash-table :test #'equal))
          buffer)
     (unwind-protect
         (progn
           (with-temp-file ,file (insert ,content))
           (setq buffer (find-file-noselect ,file))
           (with-current-buffer buffer
             (org-mode)
             (cl-letf (((symbol-function 'jetpacs-buffer-defer-refresh) #'ignore)
                       ((symbol-function 'jetpacs-shell-notify) #'ignore))
               ,@body)))
       (when (buffer-live-p buffer)
         (with-current-buffer buffer (set-buffer-modified-p nil))
         (kill-buffer buffer))
       (delete-directory root t))))

(defun jetpacs-reader-org-test--disk (file)
  "FILE's current on-disk content."
  (with-temp-buffer (insert-file-contents file) (buffer-string)))

(defun jetpacs-reader-org-test--element-pos (needle)
  "The `:post-affiliated' position of the element whose line holds NEEDLE."
  (save-excursion
    (goto-char (point-min))
    (search-forward needle)
    (org-element-property :post-affiliated (org-element-at-point))))

(defun jetpacs-reader-org-test--nodes-of-type (tree type)
  "Every node of TYPE in TREE, in document order."
  (let (nodes)
    (cl-labels ((walk (value)
                  (cond
                   ((vectorp value) (mapc #'walk value))
                   ((and (consp value) (keywordp (car value)))
                    (when (equal (plist-get value :t) type) (push value nodes))
                    (cl-loop for (_k v) on value by #'cddr do (walk v)))
                   ((consp value) (mapc #'walk value)))))
      (walk tree))
    (nreverse nodes)))

(defun jetpacs-reader-org-test--action (tree name)
  "The tap descriptor naming action NAME in TREE."
  (cl-loop for type in '("button" "icon_button")
           thereis (cl-loop for node in (jetpacs-reader-org-test--nodes-of-type
                                         tree type)
                            for tap = (plist-get node :on_tap)
                            when (equal name (plist-get tap :action))
                            return tap)))

(defconst jetpacs-reader-org-test--source
  "* Lab\nBefore.\n#+NAME: sample\n#+begin_src emacs-lisp -n :results value\n(+ 2 2)\n#+end_src\n\n#+RESULTS: sample\n: 4\nAfter.\n")

(defun jetpacs-reader-org-test--source-open ()
  "Expose and open the fixture's source block; the handler's status."
  (let ((pos (jetpacs-reader-org-test--element-pos "#+begin_src")))
    (jetpacs-buffer-expose (buffer-name) pos "jetpacs.org.source.edit")
    (jetpacs-reader-org--on-source-edit
     (list :buffer (buffer-name) :pos pos :tick (buffer-chars-modified-tick)
           :scope "test")
     '(:surface "app:test"))))

(defun jetpacs-reader-org-test--source-save (text language)
  "Submit TEXT and LANGUAGE through the live draft's editor fields."
  (let* ((editor (jetpacs-reader-org-source-editor
                  (jetpacs-reader-org-source-draft)))
         (action (jetpacs-reader-org-test--action editor "jetpacs.org.source.save"))
         (args (plist-get action :args)))
    (jetpacs-reader-org--on-source-save
     args (list :surface "app:test"
                :fields (list (intern (concat ":" (plist-get args :field))) text
                              (intern (concat ":" (plist-get args :language-field)))
                              language)))))

(ert-deftest jetpacs-reader-org-editor-verbs-register-ownerless ()
  "Every editor verb registers with the adapter, ownerless, and leaves
with it — taking the live draft along."
  (jetpacs-reader-org-register)
  (dolist (verb jetpacs-reader-org--editor-verbs)
    (should (gethash verb jetpacs-action-handlers))
    (should-not (jetpacs--owner-of "action" verb))
    (should (stringp (plist-get (jetpacs-action-schema verb) :doc))))
  (unwind-protect
      (progn
        (setq jetpacs-reader-org--source-edit '(:id "draft"))
        (jetpacs-reader-org-unregister)
        (dolist (verb jetpacs-reader-org--editor-verbs)
          (should-not (gethash verb jetpacs-action-handlers)))
        (should-not (jetpacs-reader-org-source-draft)))
    (jetpacs-reader-org-register)))

(ert-deftest jetpacs-reader-org-source-draft-saves-body-and-language ()
  "One draft at a time; the editor is deterministic; Save rewrites body
and language without touching the block's name, headers or results."
  (jetpacs-reader-org-test--with-file file jetpacs-reader-org-test--source
    (let ((original (buffer-string)))
      (should (eq (jetpacs-reader-org-test--source-open) 'accepted))
      (should (eq (jetpacs-reader-org-test--source-open) 'rejected))
      (let ((draft (jetpacs-reader-org-source-draft)))
        (should (jetpacs-reader-org-source-editing-p
                 (buffer-name) (plist-get draft :pos) "test"))
        (should-not (jetpacs-reader-org-source-editing-p
                     (buffer-name) (plist-get draft :pos) "other"))
        (should-not (jetpacs-reader-org-source-editing-p
                     (buffer-name) (plist-get draft :pos) "test" 'call))
        (let* ((editor (jetpacs-reader-org-source-editor draft))
               (dropdown (car (jetpacs-reader-org-test--nodes-of-type
                               editor "dropdown")))
               (input (car (jetpacs-reader-org-test--nodes-of-type
                            editor "text_input"))))
          (should (equal (plist-get dropdown :value) "emacs-lisp"))
          (should (string-search "python" (jetpacs-node->canonical-json dropdown)))
          (should (equal (plist-get input :value) "(+ 2 2)\n"))
          (should (equal original (buffer-string)))))
      (should (eq (jetpacs-reader-org-test--source-save "print(4)" "python")
                  'accepted))
      (should-not (jetpacs-reader-org-source-draft))
      (should-not (buffer-modified-p))
      (should (equal (buffer-string)
                     "* Lab\nBefore.\n#+NAME: sample\n#+begin_src python -n :results value\nprint(4)\n#+end_src\n\n#+RESULTS: sample\n: 4\nAfter.\n"))
      (should (equal (buffer-string) (jetpacs-reader-org-test--disk file))))))

(ert-deftest jetpacs-reader-org-source-draft-cancel-and-invalid ()
  "Cancel resets the input identity; invalid fields change nothing."
  (jetpacs-reader-org-test--with-file file jetpacs-reader-org-test--source
    (let ((original (buffer-string)))
      (should (eq (jetpacs-reader-org-test--source-open) 'accepted))
      (let* ((id (plist-get (jetpacs-reader-org-source-draft) :id))
             (editor (jetpacs-reader-org-source-editor
                      (jetpacs-reader-org-source-draft)))
             (cancel (jetpacs-reader-org-test--action
                      editor "jetpacs.org.source.cancel")))
        (should (eq (jetpacs-reader-org--on-source-cancel
                     (plist-get cancel :args) '(:surface "app:other"))
                    'stale))
        (should (eq (jetpacs-reader-org--on-source-cancel
                     (plist-get cancel :args) '(:surface "app:test"))
                    'accepted))
        (should-not (jetpacs-reader-org-source-draft))
        (should (eq (jetpacs-reader-org-test--source-open) 'accepted))
        (should-not (equal id (plist-get (jetpacs-reader-org-source-draft) :id))))
      (should (eq (jetpacs-reader-org-test--source-save
                   "#+END_SRC\n* Injected" "python")
                  'rejected))
      (should (eq (jetpacs-reader-org-test--source-save
                   "print(4)" "python :var evil=1")
                  'rejected))
      (should-not (jetpacs-reader-org--source-field '(:invalid . "bad") "invalid"))
      (should (equal original (buffer-string)))
      (should (equal original (jetpacs-reader-org-test--disk file))))))

(ert-deftest jetpacs-reader-org-source-draft-rejects-drift-and-failed-writes ()
  "Source and disk freshness guards reject lost updates; a refused write
rolls back and keeps the draft."
  (dolist (failure '(buffer disk write))
    (jetpacs-reader-org-test--with-file file jetpacs-reader-org-test--source
      (should (eq (jetpacs-reader-org-test--source-open) 'accepted))
      (pcase failure
        ('buffer (goto-char (point-max)) (insert "New local text.\n"))
        ('disk (with-temp-file file (insert "* External replacement\n"))))
      (let ((before (buffer-string))
            (disk (jetpacs-reader-org-test--disk file)))
        (if (eq failure 'write)
            (cl-letf (((symbol-function 'jetpacs-org-save-policy)
                       (lambda (&rest _) (error "Write refused"))))
              (should (eq (jetpacs-reader-org-test--source-save "print(4)" "python")
                          'rejected)))
          (should (eq (jetpacs-reader-org-test--source-save "print(4)" "python")
                      'stale)))
        (should (equal before (buffer-string)))
        (should (equal disk (jetpacs-reader-org-test--disk file)))
        (should (jetpacs-reader-org-source-draft))))))

(defconst jetpacs-reader-org-test--call
  "* Calls\n#+NAME: double\n#+begin_src emacs-lisp :var n=4 :results value\n(* 2 n)\n#+end_src\n\n#+RESULTS: double\n: 8\n\n#+call: double(n=5)\n")

(defun jetpacs-reader-org-test--call-args (verb)
  "Expose VERB at the fixture's call line and return a render's args."
  (let ((pos (jetpacs-reader-org-test--element-pos "#+call:")))
    (jetpacs-buffer-expose (buffer-name) pos verb)
    (list :buffer (buffer-name) :pos pos :tick (buffer-chars-modified-tick)
          :scope "test")))

(ert-deftest jetpacs-reader-org-call-executes-and-saves-results ()
  "Execute honors the literal argument override, saves separate results
durably, and a spent snapshot answers stale."
  (jetpacs-reader-org-test--with-file file jetpacs-reader-org-test--call
    (let ((args (jetpacs-reader-org-test--call-args "jetpacs.org.call.execute")))
      (should (eq (jetpacs-reader-org--on-call-execute args '(:surface "app:test"))
                  'accepted))
      (should (string-search "#+RESULTS: double\n: 8" (buffer-string)))
      (should (string-search "#+call: double(n=5)\n\n#+RESULTS:\n: 10"
                             (buffer-string)))
      (should-not (buffer-modified-p))
      (should (equal (buffer-string) (jetpacs-reader-org-test--disk file)))
      (should (eq (jetpacs-reader-org--on-call-execute args '(:surface "app:test"))
                  'stale)))))

(ert-deftest jetpacs-reader-org-call-execute-refuses-drift-and-failed-writes ()
  "Stale calls never execute; an unexposed or unresolvable call rejects;
a failed save restores the buffer."
  (dolist (failure '(buffer disk exposure write missing))
    (jetpacs-reader-org-test--with-file file jetpacs-reader-org-test--call
      (when (eq failure 'missing)
        (goto-char (point-max))
        (search-backward "double(n=5)")
        (replace-match "missing(n=5)" t t)
        (save-buffer))
      (let ((args (jetpacs-reader-org-test--call-args "jetpacs.org.call.execute")))
        (pcase failure
          ('buffer (goto-char (point-max)) (insert "Changed.\n"))
          ('disk (with-temp-file file (insert "* External edit\n")))
          ('exposure (clrhash jetpacs-buffer-exposed)))
        (let ((before (buffer-string))
              (disk (jetpacs-reader-org-test--disk file)))
          (pcase failure
            ('write
             (cl-letf (((symbol-function 'jetpacs-org-save-policy)
                        (lambda (&rest _) (error "Write refused"))))
               (should (eq (jetpacs-reader-org--on-call-execute
                            args '(:surface "app:test"))
                           'rejected))))
            ('missing
             (should (eq (jetpacs-reader-org--on-call-execute
                          args '(:surface "app:test"))
                         'rejected)))
            (_
             (cl-letf (((symbol-function 'org-babel-lob-execute-maybe)
                        (lambda () (ert-fail "Stale call executed"))))
               (should (eq (jetpacs-reader-org--on-call-execute
                            args '(:surface "app:test"))
                           (if (eq failure 'exposure) 'rejected 'stale))))))
          (should (equal before (buffer-string)))
          (should (equal disk (jetpacs-reader-org-test--disk file))))))))

(defun jetpacs-reader-org-test--call-save (value)
  "Save VALUE through the live argument draft's editor field."
  (let* ((editor (jetpacs-reader-org-call-editor (jetpacs-reader-org-source-draft)))
         (action (jetpacs-reader-org-test--action editor "jetpacs.org.call.save"))
         (args (plist-get action :args)))
    (jetpacs-buffer-expose (buffer-name) (plist-get (jetpacs-reader-org-source-draft) :pos)
                           "jetpacs.org.call.save")
    (jetpacs-reader-org--on-call-save
     args (list :surface "app:test"
                :fields (list (intern (concat ":" (plist-get args :field))) value)))))

(ert-deftest jetpacs-reader-org-call-draft-saves-arguments-without-evaluating ()
  "Argument edits preserve the surrounding syntax, metadata and results;
Save never resolves the call; malformed values stay inside the field."
  (dolist (value '("n=7" "n=(+ 2 3), text=\"a,b\"" "" "  "))
    (jetpacs-reader-org-test--with-file file
        "* Calls\n#+NAME: saved-call\n  #+CaLl: double[:var offset=1](n=5)   :results value\n\n#+RESULTS: saved-call\n: 10\nAfter.\n"
      (let ((before (buffer-string))
            (args (jetpacs-reader-org-test--call-args "jetpacs.org.call.edit")))
        (should (eq (jetpacs-reader-org--on-call-edit args '(:surface "app:test"))
                    'accepted))
        (let ((draft (jetpacs-reader-org-source-draft)))
          (should (eq (plist-get draft :kind) 'call))
          (should (jetpacs-reader-org-source-editing-p
                   (buffer-name) (plist-get draft :pos) "test" 'call))
          (should (equal (plist-get draft :text) "n=5"))
          (should (equal (plist-get
                          (car (jetpacs-reader-org-test--nodes-of-type
                                (jetpacs-reader-org-call-editor draft) "text_input"))
                          :value)
                         "n=5")))
        (cl-letf (((symbol-function 'org-babel-lob-get-info)
                   (lambda (&rest _) (ert-fail "Save evaluated arguments")))
                  ((symbol-function 'org-babel-lob-execute-maybe)
                   (lambda () (ert-fail "Save executed call"))))
          (should (eq (jetpacs-reader-org-test--call-save value) 'accepted)))
        (should (equal (buffer-string)
                       (string-replace "(n=5)" (concat "(" value ")") before)))
        (should (equal (buffer-string) (jetpacs-reader-org-test--disk file)))
        (should-not (jetpacs-reader-org-source-draft)))))
  (jetpacs-reader-org-test--with-file file jetpacs-reader-org-test--call
    (let ((before (buffer-string))
          (args (jetpacs-reader-org-test--call-args "jetpacs.org.call.edit")))
      (should (eq (jetpacs-reader-org--on-call-edit args '(:surface "app:test"))
                  'accepted))
      ;; A second draft of any kind must wait for the first.
      (should (eq (jetpacs-reader-org-test--source-open) 'rejected))
      (dolist (value '("n=5) :results silent (" "n=5\n* Heading" "n=(+ 2 3"))
        (should (eq (jetpacs-reader-org-test--call-save value) 'rejected)))
      (should (equal before (buffer-string)))
      (should (jetpacs-reader-org-source-draft)))))

(defconst jetpacs-reader-org-test--footnote-sources
  '("* Lab\nRead[fn:a].\n* Footnotes\n[fn:a] Old *note*\n\n[fn:b] Other.\n"
    "* Lab\nRead[fn:a:Old *note*] and[fn:a].\n"
    "* Lab\nRead[fn:a], defined here[fn:a:Old *note*].\n"
    "* Lab\nRead[fn::Old *note*].\n")
  "Normal, named inline (first and later reference) and anonymous notes.")

(defmacro jetpacs-reader-org-test--with-dialogs (shown callback &rest body)
  "Stub the client, grant and dialog transport; record SHOWN and CALLBACK."
  (declare (indent 2))
  `(cl-letf (((symbol-function 'jetpacs-client) (lambda () 'client))
             ((symbol-function 'jetpacs-granted-p) (lambda (&rest _) t))
             ((symbol-function 'jetpacs-flow-continue) #'funcall)
             ((symbol-function 'ebp-client-dialog-show)
              (lambda (_client _id spec &rest opts)
                (setq ,shown spec ,callback (plist-get opts :callback))
                "request"))
             ((symbol-function 'ebp-client-abandon) (lambda (&rest _) nil)))
     ,@body))

(defun jetpacs-reader-org-test--footnote-args ()
  "Expose the first reference's open verb and return a render's args."
  (let ((pos (save-excursion (goto-char (point-min))
                             (search-forward "[fn:")
                             (match-beginning 0))))
    (jetpacs-buffer-expose (buffer-name) pos "jetpacs.org.footnote.open")
    (list :buffer (buffer-name) :pos pos :tick (buffer-chars-modified-tick))))

(ert-deftest jetpacs-reader-org-footnote-dialog-reads-edits-and-saves ()
  "Open shows a read dialog (through the installed prose seam); Edit moves
to a fresh editing identity; Save rewrites exactly one definition durably;
dismissal never writes."
  (dolist (source jetpacs-reader-org-test--footnote-sources)
    (jetpacs-reader-org-test--with-file file source
      (let ((jetpacs-reader-org-footnote-prose-function
             (lambda (text) (jetpacs-text (concat "PROSE:" text))))
            shown callback)
        (jetpacs-reader-org-test--with-dialogs shown callback
          (let ((args (jetpacs-reader-org-test--footnote-args)))
            (should (eq (jetpacs-reader-org--on-footnote-open
                         args '(:surface "app:test"))
                        'accepted))
            (should-not (jetpacs-reader-org-test--nodes-of-type shown "text_input"))
            (should (string-search "PROSE:Old *note*"
                                   (jetpacs-node->canonical-json shown)))
            (funcall callback "dismissed" nil nil)
            (should-not jetpacs-reader-org--footnote-dialog)
            (should (equal source (buffer-string)))
            (should (eq (jetpacs-reader-org--on-footnote-open
                         args '(:surface "app:test"))
                        'accepted))
            (let ((id (plist-get jetpacs-reader-org--footnote-dialog :id))
                  (old-callback callback))
              (should (eq (jetpacs-reader-org--on-footnote-edit
                           (list :session id) (list :dialog_id "other"))
                          'stale))
              (should (eq (jetpacs-reader-org--on-footnote-edit
                           (list :session id) (list :dialog_id id))
                          'accepted))
              (should-not (equal id (plist-get jetpacs-reader-org--footnote-dialog :id)))
              ;; The retired preview's conclusion cannot retire the editor.
              (funcall old-callback "dismissed" nil nil)
              (should jetpacs-reader-org--footnote-dialog))
            (should (equal (plist-get (car (jetpacs-reader-org-test--nodes-of-type
                                            shown "text_input"))
                                      :value)
                           "Old *note*"))
            (let ((id (plist-get jetpacs-reader-org--footnote-dialog :id)))
              (should (eq (jetpacs-reader-org--on-footnote-save
                           (list :session id)
                           (list :dialog_id id :fields '(:footnote-text "New /note/")))
                          'accepted)))
            (should-not jetpacs-reader-org--footnote-dialog)
            (let ((expected (replace-regexp-in-string
                             "Old \\*note\\*" "New /note/" source t t)))
              (should (equal expected (buffer-string)))
              (should (equal expected (jetpacs-reader-org-test--disk file))))))))))

(ert-deftest jetpacs-reader-org-footnote-save-refuses-drift-invalid-and-failed-write ()
  "The wrong dialog, invalid Org, source or disk drift and write errors
cannot clobber a note; the dialog stays up for the user to retry."
  (dolist (failure '(wrong-dialog invalid source disk write))
    (jetpacs-reader-org-test--with-file file
        "* Lab\nRead[fn:a].\n* Footnotes\n[fn:a] Original.\n"
      (let* ((before (buffer-string))
             (args (jetpacs-reader-org-test--footnote-args))
             (record (jetpacs-reader-org-footnote-record (plist-get args :pos)))
             (jetpacs-reader-org--footnote-dialog
              (append record '(:id "fn-test" :editing t :field "footnote-text"
                               :request-id "req" :surface "app:test"))))
        (should (equal (plist-get record :text) "Original."))
        (pcase failure
          ('source (goto-char (point-max)) (insert "Changed elsewhere."))
          ('disk (with-temp-file file (insert "Changed on disk."))))
        (let ((after-drift (buffer-string)))
          (cl-letf (((symbol-function 'jetpacs-org-save-policy)
                     (lambda (&rest _) (error "Simulated failed write"))))
            (should
             (eq (jetpacs-reader-org--on-footnote-save
                  '(:session "fn-test")
                  (list :dialog_id (if (eq failure 'wrong-dialog) "other" "fn-test")
                        :fields (list :footnote-text
                                      (if (eq failure 'invalid)
                                          "Escape.\n* New heading\n"
                                        "New note."))))
                 (if (memq failure '(wrong-dialog source disk)) 'stale 'rejected))))
          (should (equal after-drift (buffer-string)))
          (should jetpacs-reader-org--footnote-dialog)
          (unless (eq failure 'disk)
            (should (equal before (jetpacs-reader-org-test--disk file)))))))))

(ert-deftest jetpacs-reader-org-footnote-record-and-open-guards ()
  "The record resolves only real references in bounds; open rejects the
unexposed and the ungranted, and goes stale on a moved snapshot."
  (jetpacs-reader-org-test--with-file file
      "* Lab\nRead[fn:a] and [fn:missing].\n* Footnotes\n[fn:a] Note.\n"
    (should-not (jetpacs-reader-org-footnote-record (point-min)))
    (should-not (jetpacs-reader-org-footnote-record
                 (save-excursion (goto-char (point-min))
                                 (search-forward "[fn:missing")
                                 (match-beginning 0))))
    (let ((args (jetpacs-reader-org-test--footnote-args)))
      ;; No client/grant: rejected before any dialog.
      (cl-letf (((symbol-function 'jetpacs-client) (lambda () nil)))
        (should (eq (jetpacs-reader-org--on-footnote-open args '(:surface "app:test"))
                    'rejected)))
      (should (eq (jetpacs-reader-org--on-footnote-open
                   (plist-put (copy-sequence args) :pos (point-min))
                   '(:surface "app:test"))
                  'rejected))
      (let (shown callback)
        (jetpacs-reader-org-test--with-dialogs shown callback
          (goto-char (point-max))
          (insert "drift\n")
          (should (eq (jetpacs-reader-org--on-footnote-open args '(:surface "app:test"))
                      'stale))
          (should-not shown))))))

(ert-deftest jetpacs-reader-org-path-p-includes-native-archives ()
  "The reader claims Org's archive filenames, in any case."
  (dolist (path '("/tmp/note.org" "/tmp/note.ORG"
                  "/tmp/note.org_archive" "/tmp/note.ORG_ARCHIVE"))
    (should (jetpacs-reader-org-path-p path)))
  (dolist (path '("/tmp/note.org_archive.bak" "/tmp/note_archive"
                  "/tmp/note.orgx_archive" nil))
    (should-not (jetpacs-reader-org-path-p path))))

(provide 'jetpacs-reader-org-test)
;;; jetpacs-reader-org-test.el ends here
