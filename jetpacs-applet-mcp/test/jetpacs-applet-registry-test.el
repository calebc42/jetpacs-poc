;;; jetpacs-applet-registry-test.el --- Build, validate, check, install -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Code:

(require 'ert)
(require 'cl-lib)

;; The suite runs from jetpacs-applet-mcp/ with `-l test/…'; the registry
;; foundation lives in the checkout's emacs/ directory.
(let ((here (file-name-directory (or load-file-name buffer-file-name))))
  (add-to-list 'load-path (expand-file-name ".." here))
  (add-to-list 'load-path (expand-file-name "../../emacs" here)))

(require 'jetpacs-applet-tooling)
(require 'jetpacs-applet-catalog)
(require 'jetpacs-registry)
(require 'jetpacs-applet-registry)

(defun jetpacs-applet-registry-test--fixture (name)
  "The absolute path of registry fixture NAME."
  (expand-file-name (concat "jetpacs-applet-mcp/test/fixtures/registry/" name)
                    (jetpacs-applet-tooling-workspace-root)))

(defmacro jetpacs-applet-registry-test--with-temp-dir (var &rest body)
  "Run BODY with VAR bound to a fresh temporary directory, deleted after."
  (declare (indent 1))
  `(let ((,var (make-temp-file "jetpacs-applet-registry" t)))
     (unwind-protect (progn ,@body)
       (delete-directory ,var t))))

(ert-deftest jetpacs-applet-registry-test-reads-source-with-include-and-refuses-absolute ()
  "Items and nested includes resolve relative to each index; absolute paths refuse."
  (let ((source (jetpacs-applet-registry-read-source
                 (jetpacs-applet-registry-test--fixture "registry.json"))))
    (should (equal (plist-get source :name) "fixture"))
    (should (equal (mapcar (lambda (i) (file-name-nondirectory (plist-get i :path)))
                           (plist-get source :items))
                   '("alpha" "beta"))))
  (jetpacs-applet-registry-test--with-temp-dir dir
    (with-temp-file (expand-file-name "registry.json" dir)
      (insert "{\"format\":1,\"name\":\"x\",\"include\":[\"/etc/registry.json\"]}"))
    (should-error (jetpacs-applet-registry-read-source (expand-file-name "registry.json" dir))
                  :type 'jetpacs-registry-error)
    (with-temp-file (expand-file-name "registry.json" dir)
      (insert "{\"format\":1,\"name\":\"x\",\"include\":[\"registry.json\"]}"))
    (should-error (jetpacs-applet-registry-read-source (expand-file-name "registry.json" dir))
                  :type 'jetpacs-registry-error)))

(ert-deftest jetpacs-applet-registry-test-builds-items-from-the-manifest ()
  "Derived keys come from the applet manifest; the defapp's extensions travel."
  (let ((alpha (jetpacs-applet-registry-build-item (jetpacs-applet-registry-test--fixture "alpha")))
        (beta (jetpacs-applet-registry-build-item (jetpacs-applet-registry-test--fixture "beta"))))
    (should (equal (gethash "name" alpha) "alpha"))
    (should (equal (gethash "version" alpha) "1.2.3"))
    (should (equal (append (gethash "registry_dependencies" alpha) nil) '("beta")))
    (should (equal (append (gethash "package_requires" alpha) nil) '(["emacs" "30.1"])))
    (should (member "jetpacs-apps" (append (gethash "requires_features" alpha) nil)))
    (should-not (member "alpha" (append (gethash "requires_features" alpha) nil)))
    (should-not (gethash "requires_extensions" alpha))
    (should (equal (append (gethash "requires_extensions" beta) nil) '("jetpacs.components")))
    (let ((file (aref (gethash "files" alpha) 0)))
      (should (equal (gethash "path" file) "alpha.el"))
      (should (equal (gethash "sha256" file) (jetpacs-registry-sha256 (gethash "content" file)))))
    (should (string-match-p "\\`[0-9a-f]\\{64\\}\\'" (gethash "applet_manifest_sha256" alpha)))
    (should (jetpacs-registry-verify-item alpha))))

(ert-deftest jetpacs-applet-registry-test-build-check-and-validate ()
  "A build writes every item and a stripped index; check pins it; validate reports."
  (jetpacs-applet-registry-test--with-temp-dir out
    (let ((source (jetpacs-applet-registry-test--fixture "registry.json")))
      (should (equal (mapcar #'file-name-nondirectory
                             (jetpacs-applet-registry-build source out))
                     '("alpha.json" "beta.json" "registry.json")))
      (should (jetpacs-applet-registry-check source out))
      (let ((index (jetpacs-registry-parse-json
                    (with-temp-buffer
                      (insert-file-contents (expand-file-name "registry.json" out))
                      (buffer-string)))))
        (should (equal (gethash "name" index) "fixture"))
        (should (= 2 (length (gethash "items" index))))
        (should-not (gethash "content" (aref (gethash "files" (aref (gethash "items" index) 0)) 0))))
      (with-temp-file (expand-file-name "alpha.json" out) (insert "{}"))
      (should-not (jetpacs-applet-registry-check source out))
      (with-temp-file (expand-file-name "stray.json" out) (insert "{}"))
      (jetpacs-applet-registry-build source out)
      (should-not (jetpacs-applet-registry-check source out))
      (should (string-match-p "^Status: PASS" (jetpacs-applet-registry-validate source)))
      (let ((report (jetpacs-applet-registry-validate
                     (jetpacs-applet-registry-test--fixture "broken-registry.json"))))
        (should (string-match-p "^Status: FAIL" report))
        (should (string-match-p "file.provide" report))))))

(ert-deftest jetpacs-applet-registry-test-resolves-dry-runs-and-installs-from-a-file-registry ()
  "The batch path: resolve order, requirement issues, dry run, install, lock."
  (jetpacs-applet-registry-test--with-temp-dir dir
    (let* ((out (expand-file-name "r/" dir))
           (apps (expand-file-name "apps/" dir))
           (jetpacs-registry-lock-file (expand-file-name "lock.json" dir)))
      (jetpacs-applet-registry-build (jetpacs-applet-registry-test--fixture "registry.json") out)
      (with-environment-variables
          (("JETPACS_REGISTRIES" (format "{\"@jetpacs\":\"file://%s{name}.json\"}" out)))
        (let ((report (jetpacs-applet-registry-resolve-report '("alpha") apps)))
          (should (equal (plist-get report :order) '("@jetpacs/beta" "@jetpacs/alpha")))
          (should-not (plist-get report :issues))
          (should (equal (mapcar (lambda (e) (plist-get e :action)) (plist-get report :plan))
                         '(create create))))
        (let ((text (jetpacs-applet-registry-batch-install '("alpha") apps)))
          (should (string-match-p "Order: @jetpacs/beta → @jetpacs/alpha" text))
          (should (file-exists-p (expand-file-name "beta.el" apps)))
          (should (file-exists-p (expand-file-name "alpha.el" apps)))
          (should (equal (gethash "version" (jetpacs-registry-lock-entry "alpha")) "1.2.3")))
        (should (equal (mapcar (lambda (e) (plist-get e :action))
                               (plist-get (jetpacs-applet-registry-resolve-report '("alpha") apps)
                                          :plan))
                       '(skip skip)))
        (with-temp-file (expand-file-name "beta.el" apps)
          (insert-file-contents (expand-file-name "beta.el" apps))
          (goto-char (point-max))
          (insert ";; local\n"))
        (should-error (jetpacs-applet-registry-batch-install '("alpha") apps)
                      :type 'jetpacs-registry-error)
        (should (jetpacs-applet-registry-batch-install '("alpha") apps :allow-overwrite t))))))

(ert-deftest jetpacs-applet-registry-test-fetched-items-are-validated-from-staging ()
  "A fetched item that fails the static validator is reported, never written."
  (let ((item (make-hash-table :test #'equal))
        (file (make-hash-table :test #'equal))
        (content ";;; nope.el --- no provide -*- lexical-binding: t; -*-\n(defun nope--x () 1)\n"))
    (puthash "path" "nope.el" file)
    (puthash "sha256" (jetpacs-registry-sha256 content) file)
    (puthash "content" content file)
    (puthash "format" 1 item)
    (puthash "name" "nope" item)
    (puthash "version" "0.0.1" item)
    (puthash "title" "Nope" item)
    (puthash "description" "Fails validation." item)
    (puthash "files" (vector file) item)
    (let ((report (jetpacs-applet-registry-validate-fetched (jetpacs-registry-check-item item))))
      (should (string-match-p "^Status: FAIL" report))
      (should (string-match-p "file.provide" report)))))

(provide 'jetpacs-applet-registry-test)
;;; jetpacs-applet-registry-test.el ends here
