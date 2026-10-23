;;; jetpacs-editor-org-test.el --- GR-6b Org save-policy gate -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; Pins the native Org save-policy seam and its downstream adoption:
;; encryption abort rollback, isolated after-save hooks, whole-cache
;; invalidation, EBP save-function ownership, Glasspane's additive editor
;; adapter, and SRS durability inside the engine failure boundary.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'org)
(require 'ebp-org)
(require 'jetpacs-editor-org)

(defmacro jetpacs-editor-org-test--with-file (binding content &rest body)
  "Bind BINDING to a temporary Org file containing CONTENT during BODY."
  (declare (indent 2) (debug (symbolp form body)))
  `(let ((,binding (make-temp-file "jetpacs-editor-org-" nil ".org"
                                    ,content)))
     (unwind-protect
         (progn ,@body)
       (when-let* ((buffer (get-file-buffer ,binding)))
         (with-current-buffer buffer (set-buffer-modified-p nil))
         (kill-buffer buffer))
       (delete-file ,binding))))

(defun jetpacs-editor-org-test--file-string (file)
  "Return FILE's literal bytes as an Emacs string."
  (with-temp-buffer
    (insert-file-contents-literally file)
    (buffer-string)))

(ert-deftest jetpacs-editor-org-save-policy-rolls-back-encryption-abort ()
  "A signaling encryption transform writes nothing and restores the buffer."
  (jetpacs-editor-org-test--with-file file "* Secret\nold cipher\n"
    (let ((buffer (find-file-noselect file)))
      (with-current-buffer buffer
        (goto-char (point-max))
        (insert "pending cleartext\n")
        (let ((before (buffer-string))
              (before-point (point))
              (jetpacs-editor--adapters nil)
              (jetpacs-files-before-buffer-save-hook
               '(jetpacs-editor--files-before-save)))
          (jetpacs-editor-register
           'org :predicate (lambda (_path) t)
           :before-save #'jetpacs-editor-org--before-save)
          (cl-letf (((symbol-function 'org-encrypt-entries)
                     (lambda ()
                       (goto-char (point-max))
                       (insert "partial encryption output\n")
                       (error "encryption failed"))))
            (should-error (jetpacs-editor-org-save-policy buffer)
                          :type 'error))
          (should (equal (buffer-string) before))
          (should (= (point) before-point))
          (should (buffer-modified-p))
          (should (equal (jetpacs-editor-org-test--file-string file)
                         "* Secret\nold cipher\n")))))))

(ert-deftest jetpacs-editor-org-save-policy-runs-after-save-hooks ()
  "A successful save runs the after-save hooks with the durable path, then
drops the whole cache.  Downstream indexers (such as a note index) attach
here; the foundation names none of them."
  (jetpacs-editor-org-test--with-file file "* Before\n"
    (let ((buffer (find-file-noselect file))
          saved invalidated)
      (with-current-buffer buffer
        (goto-char (point-max))
        (insert "changed\n"))
      (let ((jetpacs-files-before-buffer-save-hook nil)
            (jetpacs-files-after-save-hook
             (list (lambda (path) (setq saved path)))))
        (cl-letf (((symbol-function 'ebp-org-cache-invalidate)
                   (lambda (&optional namespace)
                     (push namespace invalidated))))
          (should (jetpacs-editor-org-save-policy buffer))))
      (should (equal saved (file-truename file)))
      (should (equal invalidated '(nil)))
      (should-not (buffer-modified-p buffer))
      (should (string-search "changed"
                             (jetpacs-editor-org-test--file-string file))))))

(ert-deftest jetpacs-editor-org-save-policy-isolates-after-save-hook-errors ()
  "A failing after-save hook is post-durability: the completed write keeps
its verdict, the cache still drops once, and the error stays local."
  (jetpacs-editor-org-test--with-file file "* Before\n"
    (let ((buffer (find-file-noselect file))
          (invalidations 0))
      (with-current-buffer buffer
        (goto-char (point-max))
        (insert "without indexer\n"))
      (let ((jetpacs-files-before-buffer-save-hook nil)
            (jetpacs-files-after-save-hook
             (list (lambda (_path) (error "indexer down")))))
        (cl-letf (((symbol-function 'ebp-org-cache-invalidate)
                   (lambda (&optional _namespace)
                     (cl-incf invalidations))))
          (should (jetpacs-editor-org-save-policy buffer))))
      (should (= invalidations 1))
      (should-not (buffer-modified-p buffer))
      (should (string-search
               "without indexer"
               (jetpacs-editor-org-test--file-string file))))))

(ert-deftest jetpacs-editor-org-save-policy-busts-org-mode-memo ()
  "A downstream funnel save evicts an `org-mode' memo before its next read."
  (require 'glasspane-org)
  (jetpacs-editor-org-test--with-file file "* Before\n"
    (let ((buffer (find-file-noselect file))
          (walks 0)
          (ebp-org--cache (make-hash-table :test #'equal))
          (ebp-org--cache-generation nil)
          (ebp-org--stamp-memo nil)
          (org-agenda-files nil))
      (cl-labels ((read-projection ()
                    (ebp-org-with-cache 'org-mode '(gr6b-projection)
                      (cl-incf walks))))
        (should (= (read-projection) 1))
        (should (= (read-projection) 1))
        (should (= walks 1))
        (should (> (hash-table-count ebp-org--cache) 0))
        (with-current-buffer buffer
          (goto-char (point-max))
          (insert "mutated through Glasspane\n"))
        (let ((jetpacs-files-before-buffer-save-hook nil))
          (glasspane-org-save-and-invalidate buffer))
        (should (= (hash-table-count ebp-org--cache) 0))
        (should (= (read-projection) 2))
        (should (= walks 2))))))

(ert-deftest jetpacs-editor-org-register-rebinds-and-restores-save-policy ()
  "Registration owns EBP's save seam and restores the previous holder."
  (let ((jetpacs-editor--adapters nil)
        (jetpacs-editor-org--registered nil)
        (jetpacs-editor-org--previous-file-save-function nil)
        (ebp-org-file-save-function #'ignore))
    (unwind-protect
        (progn
          (jetpacs-editor-org-register)
          (should (eq ebp-org-file-save-function
                      #'jetpacs-editor-org-save-policy))
          (jetpacs-editor-org-unregister)
          (should (eq ebp-org-file-save-function #'ignore)))
      (jetpacs-editor-org-unregister))))

(ert-deftest jetpacs-editor-org-glasspane-adapter-is-additive ()
  "Glasspane appends only its action and post-save contribution."
  (require 'glasspane-detail)
  (let ((jetpacs-editor--adapters nil)
        indexed)
    (unwind-protect
        (progn
          (jetpacs-editor-register
           'org
           :predicate (lambda (path) (string-suffix-p ".org" path))
           :body #'ignore :toolbar #'ignore :fab #'ignore
           :actions (lambda (_path)
                      (list (jetpacs-icon-button
                             "description"
                             (jetpacs-action "fixture.stock")))))
          (glasspane-detail-register)
          (let ((adapter (cl-find 'glasspane-org jetpacs-editor--adapters
                                  :key #'jetpacs-editor-adapter-id)))
            (should adapter)
            (should (jetpacs-editor-adapter-predicate adapter))
            (should (eq (jetpacs-editor-adapter-actions adapter)
                        #'glasspane-detail--editor-actions))
            (should (eq (jetpacs-editor-adapter-after-save adapter)
                        #'glasspane-org-vulpea-refresh-file))
            (should-not (jetpacs-editor-adapter-setup adapter))
            (should-not (jetpacs-editor-adapter-body adapter))
            (should-not (jetpacs-editor-adapter-toolbar adapter))
            (should-not (jetpacs-editor-adapter-fab adapter))
            (should-not (jetpacs-editor-adapter-before-save adapter)))
          (let ((json (jetpacs-node->canonical-json
                       (apply #'jetpacs-row
                              (jetpacs-editor--files-actions
                               "/tmp/notes.org")))))
            (should (string-search "fixture.stock" json))
            (should (string-search "files.properties.show" json)))
          (cl-letf (((symbol-function 'vulpea-db-update-file)
                     (lambda (path) (setq indexed path))))
            (jetpacs-editor--files-after-save "/tmp/notes.org"))
          (should (equal indexed "/tmp/notes.org"))
          (glasspane-detail-unregister)
          (should (equal (mapcar #'jetpacs-editor-adapter-id
                                 jetpacs-editor--adapters)
                         '(org))))
      (glasspane-detail-unregister))))

(ert-deftest jetpacs-editor-org-srs-durability-is-inside-engine-form ()
  "Rate, postpone, suspend, and undo treat a policy failure as rejection."
  (require 'glasspane-srs)
  (with-temp-buffer
    (org-mode)
    (insert "* Card\n:LOGBOOK:\nold row\n:END:\n")
    (goto-char (point-min))
    (let* ((marker (point-marker))
           (item (list '(card back) "1" (buffer-name)))
           (glasspane-srs--current item)
           (glasspane-srs--undo nil)
           (policy-fails t)
           (policy-calls 0)
           (advanced nil)
           (refreshed nil)
           events)
      (unwind-protect
          (cl-letf (((symbol-function 'org-srs-item-marker)
                     (lambda (&rest _) marker))
                    ((symbol-function 'org-srs-review-rate)
                     (lambda (&rest _) (push 'rate events)))
                    ((symbol-function 'org-srs-review-postpone)
                     (lambda (&rest _) (push 'postpone events)))
                    ((symbol-function 'glasspane-srs--push-undo) #'ignore)
                    ((symbol-function 'org-srs-log-beginning-of-drawer)
                     (lambda () (goto-char (point-min)) (forward-line 1)))
                    ((symbol-function 'org-srs-log-end-of-drawer)
                     (lambda () (goto-char (point-max))))
                    ((symbol-function 'org-srs-log-hide-drawer) #'ignore)
                    ((symbol-function 'jetpacs-editor-org-save-policy)
                     (lambda (&optional _buffer)
                       (cl-incf policy-calls)
                       (push 'policy events)
                       (when policy-fails (error "durability failed"))
                       t))
                    ((symbol-function 'glasspane-srs--advance)
                     (lambda () (setq advanced t) (push 'advance events)))
                    ((symbol-function 'jetpacs-app-defer-refresh)
                     (lambda (&rest _) (setq refreshed t)
                       (push 'refresh events)))
                    ((symbol-function 'jetpacs-shell-notify)
                     (lambda (&rest _) nil)))
            (should (eq (glasspane-srs--on-rate
                         '(:rating "good") '(:surface "app:glasspane"))
                        'rejected))
            (should (eq (glasspane-srs--on-postpone
                         nil '(:surface "app:glasspane"))
                        'rejected))
            (should (eq (glasspane-srs--on-suspend
                         nil '(:surface "app:glasspane"))
                        'rejected))
            (setq glasspane-srs--undo (list (cons item "restored row\n")))
            (should (eq (glasspane-srs--on-undo
                         nil '(:surface "app:glasspane"))
                        'rejected))
            (should (= policy-calls 4))
            (should-not advanced)
            (should-not refreshed)

            ;; On success the policy completes before the queue advances and
            ;; the UI refresh is deferred; this is the durable verdict order.
            (setq policy-fails nil policy-calls 0 events nil advanced nil
                  refreshed nil glasspane-srs--current item
                  glasspane-srs--undo nil)
            (should (eq (glasspane-srs--on-rate
                         '(:rating "good") '(:surface "app:glasspane"))
                        'accepted))
            (should (= policy-calls 1))
            (should advanced)
            (should refreshed)
            (should (equal (nreverse events)
                           '(rate policy advance refresh))))
        (set-marker marker nil)))))

(defmacro jetpacs-editor-org-test--with-splice-vault (file buffer &rest body)
  "Bind FILE (a two-heading fixture under its own org root) and BUFFER."
  (declare (indent 2))
  `(let* ((vault (file-truename (make-temp-file "jetpacs-splice" t)))
          (,file (expand-file-name "tasks.org" vault))
          (ebp-org-roots (list vault))
          (jetpacs-files-before-buffer-save-hook nil)
          (jetpacs-files-after-save-hook nil)
          ,buffer)
     (unwind-protect
         (progn
           (with-temp-file ,file
             (insert "* Stable\n:PROPERTIES:\n:ID: stable-id\n:END:\nold\n"
                     "* Neighbor\nkeep\n"))
           (setq ,buffer (find-file-noselect ,file))
           ,@body)
       (when (buffer-live-p ,buffer)
         (with-current-buffer ,buffer (set-buffer-modified-p nil))
         (kill-buffer ,buffer))
       (delete-directory vault t))))

(ert-deftest jetpacs-editor-org-fresh-splice-commits-then-refuses-stale ()
  "A matching snapshot splices the subtree durably and returns a fresh
ref; the spent snapshot, a starless value and a foreign owner's file
refuse with the splice condition and leave the file alone."
  (jetpacs-editor-org-test--with-splice-vault file buffer
    (let (ref beg end tick stamp)
      (with-current-buffer buffer
        (org-with-wide-buffer
         (goto-char (point-min))
         (setq ref (ebp-org-ref-at-point)
               beg (point)
               end (save-excursion (org-end-of-subtree t t) (point))
               tick (buffer-chars-modified-tick)
               stamp (jetpacs-editor-org-mtime-stamp file))))
      (should (stringp stamp))
      (should-error (jetpacs-editor-org-fresh-splice
                     ref "no stars" stamp beg end tick)
                    :type 'jetpacs-editor-org-splice-refused)
      (let ((new-ref (jetpacs-editor-org-fresh-splice
                      ref
                      (concat "* Changed\n:PROPERTIES:\n:ID: stable-id\n"
                              ":END:\nnew")
                      stamp beg end tick)))
        (should (equal (plist-get new-ref :headline) "Changed"))
        (should (equal (plist-get new-ref :id) "stable-id")))
      (should-not (buffer-modified-p buffer))
      (with-temp-buffer
        (insert-file-contents file)
        (should (string-search "* Changed" (buffer-string)))
        ;; The omitted final newline never glued the neighbor on.
        (should (string-search "new\n* Neighbor\nkeep" (buffer-string)))
        (should-not (string-search "* Stable" (buffer-string))))
      ;; The snapshot is spent: disk and buffer moved on.
      (should-error (jetpacs-editor-org-fresh-splice
                     ref "* Again\n" stamp beg end tick)
                    :type 'jetpacs-editor-org-splice-refused)
      (with-temp-buffer
        (insert-file-contents file)
        (should-not (string-search "* Again" (buffer-string)))))))

(ert-deftest jetpacs-editor-org-fresh-splice-restores-before-durability ()
  "A failed pre-write hook leaves neither speculative bytes nor dirt."
  (jetpacs-editor-org-test--with-splice-vault file buffer
    (with-current-buffer buffer
      (org-with-wide-buffer
       (goto-char (point-min))
       (let* ((ref (ebp-org-ref-at-point))
              (beg (point))
              (end (save-excursion (org-end-of-subtree t t) (point)))
              (tick (buffer-chars-modified-tick))
              (stamp (jetpacs-editor-org-mtime-stamp file))
              (original (buffer-string))
              (jetpacs-files-before-buffer-save-hook
               (list (lambda (&rest _) (error "encryption failed")))))
         (should-error
          (jetpacs-editor-org-fresh-splice
           ref
           (concat "* Changed\n:PROPERTIES:\n:ID: stable-id\n:END:\nnew\n")
           stamp beg end tick))
         (should (equal (buffer-string) original))
         (should-not (buffer-modified-p)))))
    (with-temp-buffer
      (insert-file-contents file)
      (should (string-search "* Stable" (buffer-string)))
      (should-not (string-search "* Changed" (buffer-string))))))

(provide 'jetpacs-editor-org-test)
;;; jetpacs-editor-org-test.el ends here
