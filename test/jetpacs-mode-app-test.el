;;; jetpacs-mode-app-test.el --- ERT for reader/editor mode apps -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)
(require 'cl-lib)
(require 'org)
(require 'jetpacs-org-mode)

(defmacro jetpacs-mode-app-test--with-org-file (binding content &rest body)
  "Bind BINDING to a temporary Org file containing CONTENT during BODY."
  (declare (indent 2) (debug (symbolp form body)))
  `(let ((,binding (make-temp-file "jetpacs-mode-app-" nil ".org"
                                    ,content)))
     (unwind-protect
         (progn ,@body)
       (when-let* ((buffer (get-file-buffer ,binding)))
         (with-current-buffer buffer (set-buffer-modified-p nil))
         (kill-buffer buffer))
       (delete-file ,binding))))

(defmacro jetpacs-mode-app-test--with-variant-client (binding &rest body)
  "Attach a READY client advertising retained app variants during BODY."
  (declare (indent 1) (debug (symbolp body)))
  `(let* ((receipt (make-temp-file "jetpacs-mode-app-receipts-"))
          (,binding (ebp-client-create :receipt-file receipt)))
     (setf (ebp-client-state ,binding) 'ready
           (ebp-client-profiles ,binding)
           (list :app
                 (list :node_types (vconcat jetpacs-app-node-types)
                       :builtins ["variant.switch"] :features []))
           (ebp-client-limits ,binding)
           '(:max_frame_bytes 4194304 :max_rich_spans 10000
             :max_table_cells 10000 :max_canvas_ops 10000))
     (unwind-protect
         (progn (jetpacs-attach ,binding) ,@body)
       (jetpacs-reader-org-reset-cache)
       (jetpacs-detach)
       (ignore-errors (delete-file receipt)))))

(defun jetpacs-mode-app-test--toolbar-commands (items)
  "Return every command string nested one level below toolbar ITEMS."
  (cl-loop for item in items
           append
           (append
            (when-let* ((command (plist-get item :command)))
              (list command))
            (when-let* ((menu (plist-get item :menu)))
              (jetpacs-mode-app-test--toolbar-commands
               (append menu nil))))))

(defun jetpacs-mode-app-test--toolbar-snippets (items)
  "Return every snippet string nested below toolbar ITEMS."
  (cl-loop for item in items
           append
           (append
            (when-let* ((snippet (plist-get item :snippet)))
              (list snippet))
            (when-let* ((menu (plist-get item :menu)))
              (jetpacs-mode-app-test--toolbar-snippets
               (append menu nil))))))

(defun jetpacs-mode-app-test--exposed-position (buffer action)
  "Return one exposed BUFFER position for ACTION, or nil."
  (let ((table (gethash (buffer-name buffer) jetpacs-buffer-exposed))
        found)
    (when table
      (maphash (lambda (position actions)
                 (when (and (null found) (member action actions))
                   (setq found position)))
               table))
    found))

(ert-deftest jetpacs-reader-org-path-p-includes-native-archives ()
  "Org's sibling archive convention selects the same native adapter."
  (dolist (path '("/tmp/note.org" "/tmp/note.ORG"
                  "/tmp/note.org_archive" "/tmp/note.ORG_ARCHIVE"))
    (should (jetpacs-reader-org-path-p path)))
  (dolist (path '("/tmp/note.org_archive.bak" "/tmp/note_archive"
                  "/tmp/note.orgx_archive" nil))
    (should-not (jetpacs-reader-org-path-p path)))
  (should (jetpacs-reader-adapter-for "/tmp/note.org_archive")))

(ert-deftest jetpacs-reader-registry-and-toggle-are-document-scoped ()
  "The generic host selects an adapter and refuses a replay for another file."
  (jetpacs-mode-app-test--with-org-file file "* One\n"
    (let ((jetpacs-reader--adapters nil)
          (jetpacs-reader--state (make-hash-table :test #'equal))
          (jetpacs-files--edit (list :path file))
          refreshed)
      (jetpacs-reader-register
       'fixture
       :predicate (lambda (path) (string-suffix-p ".org" path))
       :render (lambda (_path) (jetpacs-text "rendered")))
      (should (jetpacs-reader-active-p file))
      (cl-letf (((symbol-function 'jetpacs-buffer-defer-refresh)
                 (lambda (surface) (setq refreshed surface))))
        (should (eq (jetpacs-reader--toggle
                     (list :path file) '(:surface "app:files"))
                    'accepted))
        (should-not (jetpacs-reader-active-p file))
        (should (equal refreshed "app:files"))
        (should (eq (jetpacs-reader--toggle
                     '(:path "/tmp/not-the-open-file.org")
                     '(:surface "app:files"))
                    'stale))))))

(ert-deftest jetpacs-reader-org-register-reasserts-replaced-slot ()
  "An already-registered stock adapter can be restored after replacement."
  (let ((jetpacs-reader--adapters nil)
        ;; Model the real GR-2 lifecycle: stock actions remain installed
        ;; while another app temporarily owns adapter id `org'.
        (jetpacs-reader-org--registered t))
    (jetpacs-reader-register
     'org :predicate #'jetpacs-reader-org-path-p
     :render (lambda (_path) (jetpacs-text "replacement"))
     :actions #'ignore :transition #'ignore)
    (jetpacs-reader-org-register)
    (let ((adapter (jetpacs-reader-adapter-for "/tmp/restored.org")))
      (should (eq (jetpacs-reader-adapter-render adapter)
                  #'jetpacs-reader-org--render))
      (should (eq (jetpacs-reader-adapter-actions adapter)
                  #'jetpacs-reader-org--actions))
      (should (eq (jetpacs-reader-adapter-transition adapter)
                  #'jetpacs-reader-org--transition))
      (should (eq jetpacs-org-render-follow-destination-function
                  #'jetpacs-reader-org--present-followed-destination)))))

(ert-deftest jetpacs-reader-org-link-reuses-current-files-edit-screen ()
  "An internal Org link retargets Files without replacing its screen chrome."
  (jetpacs-mode-app-test--with-org-file
      file "* Target\nDestination body.\n\n[[*Target][Jump]]\n"
    (let* ((true (file-truename file))
           (buffer (jetpacs-reader-org--buffer true))
           (surface "app:org-link-files-test")
           (builder (lambda (_back) (jetpacs-text "edit")))
           (stack (list (cons "edit" builder)))
           (jetpacs-chrome--stacks (make-hash-table :test #'equal))
           (jetpacs-files--edit
            (list :path true :seed "seed" :mtime "stamp"
                  :mark-pos nil :document "doc:test.org"
                  :editor-id "body" :buffer buffer))
           (jetpacs-org-render-follow-destination-function
            #'jetpacs-reader-org--present-followed-destination)
           refreshed
           drilled)
      (puthash surface stack jetpacs-chrome--stacks)
      (jetpacs-org-render buffer)
      (let ((position (jetpacs-mode-app-test--exposed-position
                       buffer "jetpacs.org.follow"))
            (jetpacs-navigate-drill-function
             (lambda (&rest args) (setq drilled args) t)))
        (should (integerp position))
        (cl-letf (((symbol-function 'jetpacs-buffer-defer-view-refresh)
                   (lambda (target) (setq refreshed target))))
          (should
           (eq (jetpacs-org-render--follow
                (list :buffer (buffer-name buffer) :pos position)
                (list :surface surface))
               'accepted)))
        (should-not drilled)
        (should (equal refreshed surface))
        (should (eq (gethash surface jetpacs-chrome--stacks) stack))
        (should (equal (plist-get jetpacs-files--edit :document)
                       "doc:test.org"))
        (should (equal (plist-get jetpacs-files--edit :editor-id) "body"))
        (with-current-buffer buffer
          (save-excursion
            (goto-char (plist-get jetpacs-files--edit :mark-pos))
            (should (looking-at-p "\\* Target"))))))))

(ert-deftest jetpacs-reader-org-linked-file-routes-through-files-policy ()
  "A different linked file stays in Files, including after a refusal."
  (jetpacs-mode-app-test--with-org-file source-file "[[file:other.org]]\n"
    (jetpacs-mode-app-test--with-org-file destination-file "* Other\n"
      (let* ((source (jetpacs-reader-org--buffer source-file))
             (destination (jetpacs-reader-org--buffer destination-file))
             (jetpacs-files--edit (list :path (file-truename source-file)))
             opened)
        (cl-letf (((symbol-function 'jetpacs-files-open-path)
                   (lambda (path surface &optional mark-pos &rest _)
                     (setq opened (list path surface mark-pos))
                     'rejected)))
          (should
           (jetpacs-reader-org--present-followed-destination
            source destination 2 "app:jetpacs.files"))
          (should
           (equal opened
                  (list destination-file "app:jetpacs.files" 2))))))))

(ert-deftest jetpacs-reader-org-rents-built-in-search-and-visibility ()
  "Plain/regexp search uses org-occur; sparse filters use Org's matcher."
  (jetpacs-mode-app-test--with-org-file
      file "* TODO Alpha :work:\nNeedle one\n* Beta :home:\nNeedle two\n"
    (let ((jetpacs-reader--state (make-hash-table :test #'equal)))
      (should (= (jetpacs-reader-org--apply-search file "Needle") 2))
      (with-current-buffer (get-file-buffer file)
        (should (= (length org-occur-highlights) 2)))
      (jetpacs-reader-state-set file :org-search-mode 'regexp)
      (should (= (jetpacs-reader-org--apply-search file "Needle \\(one\\|two\\)")
                 2))
      (jetpacs-reader-state-set file :org-search-mode 'sparse)
      (should (eq (jetpacs-reader-org--apply-search file "+work") 'sparse))
      (should-error (progn
                      (jetpacs-reader-state-set file :org-search-mode 'regexp)
                      (jetpacs-reader-org--apply-search file "["))
                    :type 'user-error)
      (jetpacs-reader-state-set file :org-query "+work")
      (jetpacs-reader-state-set file :org-visibility 'contents)
      (jetpacs-reader-org--clear-search-in-buffer
       file (jetpacs-reader-org--buffer file))
      (should (eq (jetpacs-reader-org--visibility file) 'contents)))))

(ert-deftest jetpacs-reader-org-visibility-skips-search-reset-when-idle ()
  "An ordinary visibility cycle does not unfold solely to clear no search."
  (jetpacs-mode-app-test--with-org-file file "* One\nBody\n"
    (let ((jetpacs-reader--state (make-hash-table :test #'equal))
          (buffer (jetpacs-reader-org--buffer file))
          (show-all 0)
          (contents 0))
      (jetpacs-reader-state-set file :org-visibility 'contents)
      (cl-letf (((symbol-function 'org-remove-occur-highlights) #'ignore)
                ((symbol-function 'org-fold-show-all)
                 (lambda (&rest _) (cl-incf show-all)))
                ((symbol-function 'org-cycle-content)
                 (lambda (&rest _) (cl-incf contents))))
        (jetpacs-reader-org--clear-search-in-buffer
         file buffer)
        (should (= show-all 0))
        (should (= contents 1))

        (jetpacs-reader-state-set file :org-query "Body")
        (jetpacs-reader-org--clear-search-in-buffer
         file buffer)
        (should (= show-all 1))
        (should (= contents 2))))))

(ert-deftest jetpacs-reader-org-reuses-only-deterministic-visibility-snapshots ()
  "Global visibility cycles reuse Emacs output; live changes invalidate it."
  (jetpacs-mode-app-test--with-org-file file "* One\nBody\n"
    (let ((jetpacs-reader--state (make-hash-table :test #'equal))
          (jetpacs-reader-org--visibility-cache nil)
          (jetpacs-org-render--cache-generation 0)
          (renders 0))
      (cl-letf (((symbol-function 'jetpacs-client) (lambda () nil))
                ((symbol-function 'jetpacs-reader-org--apply-visibility)
                 #'ignore)
                ((symbol-function 'jetpacs-reader-org--apply-reader-mode)
                 #'ignore)
                ((symbol-function 'jetpacs-org-render)
                 (lambda (_buffer)
                   (cl-incf renders)
                   (list (jetpacs-text (format "render-%d" renders))))))
        ;; The deterministic first overview is retained.
        (let ((overview (jetpacs-reader-org--render file)))
          (should (= renders 1))

          ;; Populate the other two canonical global states.
          (dolist (visibility '(contents all))
            (jetpacs-reader-state-set file :org-visibility visibility)
            (jetpacs-reader-state-set
             file :org-visibility-cache-request t)
            (jetpacs-reader-org--render file))
          (should (= renders 3))

          ;; Cycling back consumes the exact Emacs-authored overview tree.
          (jetpacs-reader-state-set file :org-visibility 'overview)
          (jetpacs-reader-state-set file :org-visibility-cache-request t)
          (should (eq overview (jetpacs-reader-org--render file)))
          (should (= renders 3))

          ;; An ordinary refresh may reflect a local heading fold, so it builds
          ;; live but neither consumes nor overwrites canonical snapshots.
          (jetpacs-reader-org--render file)
          (should (= renders 4))
          (jetpacs-reader-state-set file :org-visibility-cache-request t)
          (should (eq overview (jetpacs-reader-org--render file)))
          (should (= renders 4))

          ;; The same state remains reusable until presentation input changes.
          (cl-incf jetpacs-org-render--cache-generation)
          (jetpacs-reader-state-set file :org-visibility-cache-request t)
          (jetpacs-reader-org--render file)
          (should (= renders 5))
          (with-current-buffer (get-file-buffer file)
            (goto-char (point-max))
            (insert "Changed\n"))
          (jetpacs-reader-state-set file :org-visibility-cache-request t)
          (jetpacs-reader-org--render file)
          (should (= renders 6)))))))

(ert-deftest jetpacs-reader-org-prewarms-next-visibility-off-interaction-path ()
  "The next canonical state is cached during idle and live Org is restored."
  (jetpacs-mode-app-test--with-org-file file "* One\nBody\n"
    (let ((jetpacs-reader--state (make-hash-table :test #'equal))
          (jetpacs-reader-org--visibility-cache nil)
          (jetpacs-reader-org--prewarm-timer nil)
          (jetpacs-org-render--cache-generation 0)
          (jetpacs-reader-org-prewarm-idle-seconds 0)
          scheduled
          (renders 0)
          applied)
      (unwind-protect
          (cl-letf (((symbol-function 'jetpacs-client) (lambda () nil))
                    ((symbol-function 'jetpacs-reader-org--apply-visibility)
                     (lambda (path _buffer)
                       (push (jetpacs-reader-org--visibility path) applied)))
                    ((symbol-function 'jetpacs-org-render)
                     (lambda (_buffer)
                       (cl-incf renders)
                       (list (jetpacs-text (format "render-%d" renders)))))
                    ((symbol-function 'run-with-idle-timer)
                     (lambda (_seconds _repeat function &rest args)
                       (setq scheduled (cons function args))
                       'fake-timer))
                    ((symbol-function 'timerp)
                     (lambda (value) (eq value 'fake-timer)))
                    ((symbol-function 'cancel-timer) #'ignore))
            (let ((overview (jetpacs-reader-org--render file)))
              (should (= renders 1))
              (should scheduled)
              (apply (car scheduled) (cdr scheduled))
              (should (= renders 2))
              ;; Idle work temporarily authored contents, then put the live
              ;; Emacs buffer back into the overview the user still sees.
              (should (equal (seq-take applied 2) '(overview contents)))
              (should (eq (jetpacs-reader-org--visibility file) 'overview))

              ;; The first user transition now consumes the prepared tree.
              (jetpacs-reader-state-set file :org-visibility 'contents)
              (jetpacs-reader-state-set
               file :org-visibility-cache-request t)
              (let ((contents (jetpacs-reader-org--render file)))
                (should (= renders 2))
                (should-not (eq contents overview)))))
        (jetpacs-reader-org--cancel-prewarm)))))

(ert-deftest jetpacs-reader-org-retains-authoritative-global-variants ()
  "All branches are Org-authored once; local selection performs no refresh."
  (jetpacs-mode-app-test--with-org-file
      file "* One\nBody\n** Two\nMore\n"
    (jetpacs-mode-app-test--with-variant-client client
      (let* ((jetpacs-reader--state (make-hash-table :test #'equal))
             (jetpacs-reader-org--visibility-cache nil)
             (jetpacs-reader-org--retained nil)
             (jetpacs-reader-org-prewarm-idle-seconds nil)
             (jetpacs-files--edit (list :path file))
             (jetpacs-buffer-budget (cons 10000 4000000))
             (jetpacs-buffer-extra-budget
              '((:max_table_cells . 10000) (:max_canvas_ops . 10000)))
             (jetpacs-node-id-claims (make-hash-table :test #'equal))
             (jetpacs-buffer--exposure-document
              (make-hash-table :test #'equal))
             (base-id (jetpacs-reader-org--variant-id-base file))
             (real-apply
              (symbol-function 'jetpacs-reader-org--apply-visibility))
             (renders 0)
             applied
             (refreshes 0))
        ;; Force a document-global collision so the test pins exact emitted-ID
        ;; propagation into both the toolbar and state subscription.
        (puthash base-id 1 jetpacs-node-id-claims)
        (cl-letf (((symbol-function 'jetpacs-reader-org--apply-visibility)
                   (lambda (path buffer)
                     (push (jetpacs-reader-org--visibility path) applied)
                     (funcall real-apply path buffer)))
                  ((symbol-function 'jetpacs-org-render)
                   (lambda (_buffer)
                     (cl-incf renders)
                     (list (jetpacs-text
                            (symbol-name
                             (jetpacs-reader-org--visibility file))))))
                  ((symbol-function 'jetpacs-buffer-defer-view-refresh)
                   (lambda (&rest _) (cl-incf refreshes))))
          (let* ((host (jetpacs-reader-org--render file))
                 (entries (append (plist-get host :variants) nil))
                 (id (plist-get host :id))
                 (visibility-button
                  (nth 1 (jetpacs-reader-org--actions file)))
                 (budget-after (copy-tree jetpacs-buffer-budget))
                 (extra-after (copy-tree jetpacs-buffer-extra-budget))
                 (claims-after
                  (jetpacs-reader-org--hash-snapshot
                   jetpacs-node-id-claims))
                 (exposure-after
                  (jetpacs-reader-org--hash-snapshot
                   jetpacs-buffer--exposure-document)))
            (should (equal (plist-get host :t) "variant_host"))
            (should (equal id (concat base-id "-1")))
            (should (equal (mapcar (lambda (entry)
                                    (plist-get entry :value))
                                  entries)
                           '("overview" "contents" "all")))
            (should (= renders 3))
            (should (eq (jetpacs-reader-org--visibility file) 'overview))
            (should (equal (plist-get visibility-button :on_tap)
                           (jetpacs-variant-switch id)))
            (should (gethash (cons (jetpacs-reader-org--surface) id)
                             jetpacs--state-handlers))

            ;; Receiver-local selection updates Emacs through its real Org
            ;; operation, without scheduling any surface replacement.
            (jetpacs--on-state-changed
             client (jetpacs-reader-org--surface) 1 id "contents")
            (should (eq (jetpacs-reader-org--visibility file) 'contents))
            (should (= refreshes 0))
            (should (eq (plist-get jetpacs-reader-org--retained :value)
                        'contents))

            ;; Model the same starting Chrome document context on a later
            ;; build.  The atomic cache restores every combined side effect;
            ;; no branch is re-rendered and authored value follows state.
            (setq jetpacs-buffer-budget (cons 10000 4000000)
                  jetpacs-buffer-extra-budget
                  '((:max_table_cells . 10000) (:max_canvas_ops . 10000)))
            (clrhash jetpacs-node-id-claims)
            (puthash base-id 1 jetpacs-node-id-claims)
            (clrhash jetpacs-buffer--exposure-document)
            (let ((cached (jetpacs-reader-org--render file)))
              (should (= renders 3))
              (should (equal (plist-get cached :id) id))
              (should (equal (plist-get cached :value) "contents"))
              (should (equal jetpacs-buffer-budget budget-after))
              (should (equal jetpacs-buffer-extra-budget extra-after))
              (should (equal (jetpacs-reader-org--hash-snapshot
                              jetpacs-node-id-claims)
                             claims-after))
              (should (equal (jetpacs-reader-org--hash-snapshot
                              jetpacs-buffer--exposure-document)
                             exposure-after)))))))))

(ert-deftest jetpacs-reader-org-retained-fallbacks-clear-local-routing ()
  "Custom folds/search/narrowing and old profiles keep the remote action."
  (jetpacs-mode-app-test--with-org-file file "* One\nBody\n** Two\nMore\n"
    (jetpacs-mode-app-test--with-variant-client _client
      (let ((jetpacs-reader--state (make-hash-table :test #'equal))
            (jetpacs-reader-org--visibility-cache nil)
            (jetpacs-reader-org--retained nil)
            (jetpacs-reader-org-prewarm-idle-seconds nil)
            (jetpacs-files--edit (list :path file)))
        (cl-letf (((symbol-function 'jetpacs-org-render)
                   (lambda (_buffer) (list (jetpacs-text "body"))))
                  ((symbol-function 'jetpacs-reader-refresh) #'ignore))
          (let* ((host (jetpacs-reader-org--render file))
                 (id (plist-get host :id))
                 (surface (jetpacs-reader-org--surface)))
            (should (equal (plist-get host :t) "variant_host"))
            (with-current-buffer (jetpacs-reader-org--buffer file)
              (jetpacs-reader-org--after-local-fold))
            (should-not jetpacs-reader-org--retained)
            (should-not (gethash (cons surface id) jetpacs--state-handlers))
            (should (equal (plist-get (jetpacs-reader-org--render file) :t)
                           "lazy_column"))
            (should (equal
                     (plist-get (nth 1 (jetpacs-reader-org--actions file))
                                :on_tap)
                     (jetpacs-action "jetpacs.reader.org.visibility"
                                     :args (list :path file))))

            ;; A fresh canonical state can retain again, but opening search
            ;; drops its subscription immediately, before the deferred push.
            (jetpacs-reader-org--apply-visibility
             file (jetpacs-reader-org--buffer file))
            (setq host (jetpacs-reader-org--render file)
                  id (plist-get host :id))
            (should (equal (plist-get host :t) "variant_host"))
            (should (eq (jetpacs-reader-org--search-toggle-action
                         (list :path file) (list :surface surface))
                        'accepted))
            (should-not jetpacs-reader-org--retained)
            (should-not (gethash (cons surface id) jetpacs--state-handlers))
            (should (equal (plist-get (jetpacs-reader-org--render file) :t)
                           "lazy_column"))

            ;; Whole-document canonicality is necessary independently of
            ;; search state.
            (jetpacs-reader-state-set file :org-search-open nil)
            (jetpacs-reader-org--apply-visibility
             file (jetpacs-reader-org--buffer file))
            (with-current-buffer (jetpacs-reader-org--buffer file)
              (save-restriction
                (narrow-to-region (point-min) (max (point-min) (1- (point-max))))
                (should (equal
                         (plist-get (jetpacs-reader-org--render file) :t)
                         "lazy_column"))))

            (setq host (jetpacs-reader-org--render file))
            (should (equal (plist-get host :t) "variant_host"))
            (jetpacs-reader-org--transition file 'editor)
            (should-not jetpacs-reader-org--retained)

            ;; Missing either advertisement is the compatibility path.
            (jetpacs-reader-org--apply-visibility
             file (jetpacs-reader-org--buffer file))
            (cl-letf (((symbol-function 'jetpacs-builtin-advertised-p)
                       (lambda (&rest _) nil)))
              (should (equal
                       (plist-get (jetpacs-reader-org--render file) :t)
                       "lazy_column")))))))))

(ert-deftest jetpacs-reader-org-retained-edit-revokes-before-refresh ()
  "A reader-side Org mutation cannot leave stale local variants selectable."
  (jetpacs-mode-app-test--with-org-file file "* One\n- [ ] Task\n"
    (jetpacs-mode-app-test--with-variant-client _client
      (let ((jetpacs-reader--state (make-hash-table :test #'equal))
            (jetpacs-reader-org--visibility-cache nil)
            (jetpacs-reader-org--retained nil)
            (jetpacs-reader-org-prewarm-idle-seconds nil)
            (jetpacs-files--edit (list :path file)))
        (cl-letf (((symbol-function 'jetpacs-org-render)
                   (lambda (_buffer) (list (jetpacs-text "body")))))
          (let* ((host (jetpacs-reader-org--render file))
                 (id (plist-get host :id))
                 (surface (jetpacs-reader-org--surface))
                 (buffer (jetpacs-reader-org--buffer file)))
            (should (equal (plist-get host :t) "variant_host"))
            (with-current-buffer buffer
              (goto-char (point-max))
              (insert "Edited\n"))
            (should-not jetpacs-reader-org--retained)
            (should-not (gethash (cons surface id) jetpacs--state-handlers))
            (should (equal (plist-get (jetpacs-reader-org--render file) :t)
                           "lazy_column"))))))))

(ert-deftest jetpacs-reader-org-retained-reconnect-adopts-input-state ()
  "A durable receiver selection is applied before the retained host is sent."
  (jetpacs-mode-app-test--with-org-file file "* One\nBody\n"
    (jetpacs-mode-app-test--with-variant-client client
      (let* ((jetpacs-reader--state (make-hash-table :test #'equal))
             (jetpacs-reader-org--visibility-cache nil)
             (jetpacs-reader-org--retained nil)
             (jetpacs-reader-org-prewarm-idle-seconds nil)
             (jetpacs-files--edit (list :path file))
             (id (jetpacs-reader-org--variant-id-base file)))
        (puthash (cons (jetpacs-reader-org--surface) id) "all"
                 (ebp-client-input-values client))
        (cl-letf (((symbol-function 'jetpacs-org-render)
                   (lambda (_buffer) (list (jetpacs-text "body")))))
          (let ((host (jetpacs-reader-org--render file)))
            (should (equal (plist-get host :value) "all"))
            (should (eq (jetpacs-reader-org--visibility file) 'all))
            (should (jetpacs-reader-org--canonical-p
                     file (jetpacs-reader-org--buffer file)))))))))

(ert-deftest jetpacs-reader-org-reader-mode-is-buffer-local ()
  "Reader mode hides markup and prettifies entities without global mutation."
  (jetpacs-mode-app-test--with-org-file file "* A *bold* \\alpha\n"
    (let ((jetpacs-reader--state (make-hash-table :test #'equal))
          (global-hide (default-value 'org-hide-emphasis-markers)))
      (let ((buffer (jetpacs-reader-org--buffer file)))
        (jetpacs-reader-org--apply-reader-mode file buffer)
        (with-current-buffer buffer
          (should org-hide-emphasis-markers)
          (should org-pretty-entities)
          (should org-hide-leading-stars))
        (should (eq (default-value 'org-hide-emphasis-markers) global-hide))))))

(ert-deftest jetpacs-reader-org-render-honors-files-mark-position ()
  "The Org reader turns Files' whole-buffer mark into a scroll target."
  (jetpacs-mode-app-test--with-org-file
      file "Intro.\n\n* Destination\nBody.\n"
    (let* ((jetpacs-reader--state (make-hash-table :test #'equal))
           (buffer (jetpacs-reader-org--buffer file))
           (mark-pos
            (with-current-buffer buffer
              (goto-char (point-min))
              (search-forward "* Destination")
              (line-beginning-position)))
           (jetpacs-files-editor-context
            (list :path (file-truename file) :mark-pos mark-pos))
           (root (jetpacs-reader-org--render file))
           (children (append (plist-get root :children) nil)))
      (should (equal (plist-get root :t) "lazy_column"))
      (should (= 1 (seq-count (lambda (node)
                               (plist-get node :scroll_here))
                             children))))))

(ert-deftest jetpacs-reader-org-render-scrolls-with-orgro-typography ()
  "The reader scrolls and reflows headings without prescribing a font."
  (jetpacs-mode-app-test--with-org-file
      file (concat "Intro paragraph.\n\n"
                   "* Attachments                                      :ATTACH:\n\n"
                   "* Next\n")
    (let* ((jetpacs-reader--state (make-hash-table :test #'equal))
           (root (jetpacs-reader-org--render file))
           (children (append (plist-get root :children) nil))
           (heading-index
            (seq-position
             children "Attachments"
             (lambda (node needle)
               (string-match-p needle
                               (jetpacs-node->canonical-json node)))))
           (heading (and heading-index (nth heading-index children)))
           (heading-row (car (append (plist-get heading :children) nil)))
           (heading-children
            (append (plist-get heading-row :children) nil))
           (headline (car heading-children))
           (overflow (cadr heading-children))
           (spans (append (plist-get headline :spans) nil))
           (text (mapconcat (lambda (span) (plist-get span :text)) spans ""))
           (body-span
            (seq-find
             (lambda (span)
               (equal (plist-get (plist-get span :on_tap) :action)
                      "jetpacs.buffer.fold"))
             spans)))
      (should (equal (plist-get root :t) "lazy_column"))
      (should heading-index)
      (should (equal (plist-get heading :t) "box"))
      (should (equal (plist-get heading-row :t) "row"))
      (should (equal (plist-get overflow :icon) "more_vert"))
      (should (equal (plist-get overflow :t) "menu"))
      (should
       (seq-some
        (lambda (item)
          (equal (plist-get (plist-get item :on_tap) :action)
                 "jetpacs.org.heading"))
        (append (plist-get overflow :items) nil)))
      (should (= (plist-get body-span :font_weight) 800))
      (should (string-match-p "Attachments :ATTACH:" text))
      (should-not (string-match-p "Attachments  +:ATTACH:" text))
      (should-not (string-match-p "[▸▾]" text))
      (let ((gap (nth (1+ heading-index) children)))
        (should (equal (plist-get gap :t) "spacer"))
        (should (= (plist-get gap :height) 8))))))

(ert-deftest jetpacs-editor-org-commands-require-sync-but-snippets-do-not ()
  "Plain editors get local helpers; synchronized editors also get Org commands."
  (let* ((jetpacs-files-editor-context '(:path "/tmp/a.org"))
         (plain (jetpacs-editor-org--toolbar "/tmp/a.org"))
         (plain-commands (jetpacs-mode-app-test--toolbar-commands plain))
         (snippets (jetpacs-mode-app-test--toolbar-snippets plain)))
    (should-not plain-commands)
    (should (member "_${selection}_" snippets))
    (should (member "_{${selection}}" snippets))
    (should (member "[cite:@${input:Key}]" snippets))
    (let* ((jetpacs-files-editor-context
            '(:path "/tmp/a.org" :document "doc:a.org" :editor-id "body"))
           (synced (jetpacs-editor-org--toolbar "/tmp/a.org"))
           (commands (jetpacs-mode-app-test--toolbar-commands synced)))
      (should (member "org-todo" commands))
      (should (member "org-refile" commands))
      (should (member "jetpacs-editor-org-encrypt-entry" commands)))))

(ert-deftest jetpacs-editor-org-installs-buffer-local-crypt-save-hook ()
  "A live Org editor re-encrypts decrypted crypt entries before save."
  (jetpacs-mode-app-test--with-org-file file "* Secret :crypt:\ntext\n"
    (let ((buffer (find-file-noselect file)))
      (jetpacs-editor-org--setup file)
      (with-current-buffer buffer
        (should (memq #'org-encrypt-entries before-save-hook))))))

(ert-deftest jetpacs-editor-org-prewrite-runs-org-crypt-explicitly ()
  "Files' write-region path invokes Org Crypt without relying on save-buffer."
  (with-temp-buffer
    (org-mode)
    (let (called)
      (cl-letf (((symbol-function 'org-encrypt-entries)
                 (lambda () (setq called (current-buffer)))))
        (jetpacs-editor-org--before-save "/tmp/a.org" (current-buffer))
        (should (eq called (current-buffer)))))))

(ert-deftest jetpacs-editor-org-narrowed-body-is-plain-section-editor ()
  "A narrowed Org buffer edits only its subtree with no sync commands."
  (jetpacs-mode-app-test--with-org-file
      file "* One\nfirst\n* Two\nsecond\n"
    (let ((jetpacs-reader--state (make-hash-table :test #'equal))
          (jetpacs-files-editor-context
           (list :path file :mtime "stamp"
                 :document "doc:test.org" :editor-id "body")))
      (let ((buffer (jetpacs-reader-org--buffer file)))
        (with-current-buffer buffer
          (goto-char (point-min))
          (org-narrow-to-subtree))
        (jetpacs-reader-state-set file :presentation 'editor)
        (let* ((node (jetpacs-editor-org--body file))
               (commands
                (jetpacs-mode-app-test--toolbar-commands
                 (append (plist-get node :toolbar) nil))))
          (should (equal (plist-get node :t) "editor"))
          (should-not (plist-get node :document))
          (should (string-search "* One\nfirst" (plist-get node :value)))
          (should-not (string-search "* Two" (plist-get node :value)))
          (should-not commands)
          (should (equal (plist-get (plist-get node :on_save) :action)
                         "jetpacs.editor.org.save-narrowed")))))))

(ert-deftest jetpacs-reader-org-narrow-transition-downgrades-sync ()
  "Switching a narrowed reader to edit removes whole-document sync identity."
  (jetpacs-mode-app-test--with-org-file file "* One\nbody\n* Two\nrest\n"
    (let* ((buffer (jetpacs-reader-org--buffer file))
           (jetpacs-files--edit
            (list :path (file-truename file) :seed "stale"
                  :mtime "stamp" :document "doc:test.org"
                  :editor-id "body" :buffer buffer)))
      (with-current-buffer buffer
        (goto-char (point-min))
        (org-narrow-to-subtree))
      (jetpacs-reader-org--transition file 'editor)
      (should-not (plist-get jetpacs-files--edit :document))
      (should-not (plist-get jetpacs-files--edit :buffer))
      (should (string-search "* Two\nrest"
                             (plist-get jetpacs-files--edit :seed))))))

(ert-deftest jetpacs-editor-org-narrowed-save-splices-only-section ()
  "A validated section save preserves the rest of the Org file."
  (jetpacs-mode-app-test--with-org-file
      file "* One\nfirst\n* Two\nsecond\n"
    (let* ((true (file-truename file))
           (root (file-name-directory true))
           (jetpacs-files-roots (list root))
           (jetpacs-reader--state (make-hash-table :test #'equal))
           (buffer (jetpacs-reader-org--buffer true))
           (jetpacs-files--edit
            (list :path true :seed "" :mtime (jetpacs-files--mtime-stamp true)
                  :coding nil)))
      (with-current-buffer buffer
        (goto-char (point-min))
        (org-narrow-to-subtree))
      (jetpacs-reader-state-set true :presentation 'editor)
      ;; The body records the Emacs-owned bounds and modification tick.
      (let ((jetpacs-files-editor-context jetpacs-files--edit))
        (jetpacs-editor-org--body true))
      (cl-letf (((symbol-function 'jetpacs-shell-notify) #'ignore)
                ((symbol-function 'jetpacs-buffer-defer-refresh) #'ignore))
        (should
         (eq (jetpacs-editor-org--save-narrowed
              (list :path true :mtime (jetpacs-files--mtime-stamp true)
                    :value "* One\nchanged")
              '(:surface "app:jetpacs.files"))
             'accepted)))
      (with-temp-buffer
        (insert-file-contents true)
        (should (equal (buffer-string)
                       "* One\nchanged\n* Two\nsecond\n")))
      (with-current-buffer buffer
        (should (buffer-narrowed-p))
        ;; `org-narrow-to-subtree' excludes the separator newline before
        ;; the next heading; the section editor preserves those exact
        ;; accessible bounds.
        (should (equal (buffer-string) "* One\nchanged"))))))

(ert-deftest jetpacs-editor-org-narrowed-save-rolls-back-before-write-error ()
  "A failed pre-write transform changes neither disk nor visiting buffer."
  (jetpacs-mode-app-test--with-org-file file "* One\nfirst\n* Two\nsecond\n"
    (let* ((true (file-truename file))
           (jetpacs-files-roots (list (file-name-directory true)))
           (jetpacs-reader--state (make-hash-table :test #'equal))
           (buffer (jetpacs-reader-org--buffer true))
           (jetpacs-files--edit
            (list :path true :seed "" :mtime (jetpacs-files--mtime-stamp true)
                  :coding nil)))
      (with-current-buffer buffer
        (goto-char (point-min))
        (org-narrow-to-subtree))
      (jetpacs-reader-state-set true :presentation 'editor)
      (let ((jetpacs-files-editor-context jetpacs-files--edit))
        (jetpacs-editor-org--body true))
      (cl-letf (((symbol-function 'jetpacs-editor--files-before-save)
                 (lambda (&rest _) (error "encryption failed")))
                ((symbol-function 'jetpacs-shell-notify) #'ignore))
        (should
         (eq (jetpacs-editor-org--save-narrowed
              (list :path true :mtime (jetpacs-files--mtime-stamp true)
                    :value "* One\nclear text\n")
              '(:surface "app:jetpacs.files"))
             'rejected)))
      (with-temp-buffer
        (insert-file-contents true)
        (should (equal (buffer-string)
                       "* One\nfirst\n* Two\nsecond\n")))
      (with-current-buffer buffer
        (should (equal (buffer-string) "* One\nfirst"))))))

(ert-deftest jetpacs-org-mode-reminders-filter-time-and-deduplicate ()
  "Timed Agenda rows become alarms; duplicate schedule/deadline rows do not."
  (let* ((now (encode-time 0 0 12 14 8 2026))
         (items
          (list
           '((headline . "Meeting") (file . "/vault/a.org") (pos . 42)
             (time . "13:30......") (date . "2026-08-14")
             (type . "scheduled"))
           '((headline . "Meeting") (file . "/vault/a.org") (pos . 42)
             (time . "13:30......") (date . "2026-08-14")
             (type . "deadline"))
           '((headline . "Date only") (file . "/vault/a.org") (pos . 90)
             (time) (date . "2026-08-14") (type . "scheduled"))
           '((headline . "Too late") (file . "/vault/b.org") (pos . 7)
             (time . "13:30") (date . "2026-08-15")
             (type . "scheduled")))))
    (cl-letf (((symbol-function 'jetpacs-org-mode--agenda-items)
               (lambda (_span &optional _start-day) items)))
      (let ((reminders (jetpacs-org-mode--upcoming-reminders 24 now)))
        (should (= 1 (length reminders)))
        (should (equal (plist-get (car reminders) :title) "Meeting"))
        (should (equal (plist-get (car reminders) :body)
                       "13:30 · scheduled"))
        (should (string-match-p "\\`org-rem-[[:xdigit:]]\\{20\\}\\'"
                                (plist-get (car reminders) :id)))))))

(ert-deftest jetpacs-org-mode-seeds-manual-and-inbox-without-overwrite ()
  "The complete manual bundle lands once and user edits always win."
  (let ((org-directory (make-temp-file "jetpacs-org-seed-" t)))
    (unwind-protect
        (let* ((paths (jetpacs-org-mode-seed))
               (inbox (plist-get paths :inbox))
               (manual (plist-get paths :manual))
               (attachment
                (expand-file-name
                 "data/C2/59CE94-D4C8-4C4F-9C9E-9ABE446E7DA3/hello-world.pdf"
                 (plist-get paths :manual-directory))))
          (should (file-readable-p inbox))
          (should (file-readable-p manual))
          (should (file-readable-p attachment))
          (should (file-readable-p
                   (expand-file-name "LICENSE"
                                     (plist-get paths :manual-directory))))
          (with-temp-file inbox (insert "user inbox\n"))
          (with-temp-file manual (insert "user manual note\n"))
          (jetpacs-org-mode-seed)
          (should (equal (with-temp-buffer
                           (insert-file-contents inbox)
                           (buffer-string))
                         "user inbox\n"))
          (should (equal (with-temp-buffer
                           (insert-file-contents manual)
                           (buffer-string))
                         "user manual note\n")))
      (delete-directory org-directory t))))

(ert-deftest jetpacs-org-mode-open-seed-uses-validated-files-route-and-surface ()
  "Seed navigation is exactly a validated Files open on the Files surface."
  (let ((file (make-temp-file "jetpacs-seed-open-" nil ".org" "* Manual\n"))
        captured)
    (unwind-protect
        (cl-letf (((symbol-function 'jetpacs-org-mode-seed)
                   (lambda () (list :manual file :inbox file)))
                  ((symbol-function 'jetpacs-org-mode--surface)
                   (lambda (owner) (concat "app:" owner)))
                  ((symbol-function 'jetpacs-files-open-path)
                   (lambda (path surface)
                     (setq captured (list path surface))
                     'accepted)))
          (should (eq 'accepted
                      (jetpacs-org-mode--on-open-seed
                       '(:document "manual")
                       '(:surface "app:org-mode"))))
          (should (equal captured (list file "app:jetpacs.files")))
          (should (eq 'rejected
                      (jetpacs-org-mode--on-open-seed
                       '(:document "forged")
                       '(:surface "app:org-mode")))))
      (when-let* ((buffer (get-file-buffer file)))
        (kill-buffer buffer))
      (delete-file file))))

(ert-deftest jetpacs-org-mode-seed-actions-cross-the-nav3-boundary ()
  "Seed rows select Files locally while Emacs performs the remote handoff."
  (cl-letf (((symbol-function 'jetpacs-feature-advertised-p)
             (lambda (feature &optional target)
               (and (equal feature "action.open_surface")
                    (eq target :app)))))
    (let ((tap (jetpacs-org-mode--open-seed-action "manual")))
      (should (equal (plist-get tap :action) "org-mode.open-seed"))
      (should (equal (plist-get (plist-get tap :args) :document) "manual"))
      (should (equal (plist-get tap :open_surface) "app:jetpacs.files"))))
  ;; Strict older receivers must never see the new optional member.
  (cl-letf (((symbol-function 'jetpacs-feature-advertised-p)
             (lambda (&rest _) nil)))
    (should-not
     (plist-member (jetpacs-org-mode--open-seed-action "inbox")
                   :open_surface))))

(ert-deftest jetpacs-org-mode-is-a-real-composed-app ()
  "The app claims home, Files, and Habits and installs both Org adapters."
  (let ((entry (assoc jetpacs-org-mode-owner jetpacs-apps--registry)))
    (should entry)
    (should (equal (plist-get (cdr entry) :label) "Org Mode"))
    (should (equal (plist-get (cdr entry) :surfaces)
                   (list jetpacs-org-mode-owner
                         jetpacs-files-owner
                         jetpacs-org-habits-owner)))
    (should (cl-find 'org jetpacs-reader--adapters
                     :key #'jetpacs-reader-adapter-id))
    (should (cl-find 'org jetpacs-editor--adapters
                     :key #'jetpacs-editor-adapter-id))
    (should (memq #'jetpacs-reader--files-body
                  jetpacs-files-editor-body-functions))
    (should (memq #'jetpacs-editor--files-toolbar
                  jetpacs-files-editor-toolbar-functions))
    (should (gethash "org-mode.capture" jetpacs-action-handlers))
    (should (gethash "org-mode.open-seed" jetpacs-action-handlers))
    ;; GR-0: the unverified inline pipeline defaults off.  A dedicated
    ;; owner takes over only after the device cutover ceremony.
    (should-not (memq #'jetpacs-org-mode--sync-reminders
                      jetpacs-shell-after-push-hook))))

(ert-deftest jetpacs-org-mode-legacy-reminder-hook-is-always-inert ()
  "GR-3 never installs the legacy reminder hook, even if its flag is true."
  (unwind-protect
      (progn
        (jetpacs-org-mode-unregister)
        (let ((jetpacs-org-mode-reminders-enabled nil))
          (jetpacs-org-mode-register)
          (should-not (memq #'jetpacs-org-mode--sync-reminders
                            jetpacs-shell-after-push-hook))
          (jetpacs-org-mode-unregister))
        (let ((jetpacs-org-mode-reminders-enabled t))
          (jetpacs-org-mode-register)
          (should-not (memq #'jetpacs-org-mode--sync-reminders
                            jetpacs-shell-after-push-hook))))
    (jetpacs-org-mode-unregister)
    (jetpacs-org-mode-register)))

(provide 'jetpacs-mode-app-test)
;;; jetpacs-mode-app-test.el ends here
