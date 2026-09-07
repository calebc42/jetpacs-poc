;;; jetpacs-applet-tooling-test.el --- Tests for static Jetpacs Applet Tooling -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)
(require 'json)

(let* ((test-dir (file-name-directory (or load-file-name buffer-file-name)))
       (root (expand-file-name "../.." test-dir)))
  (setq jetpacs-applet-tooling-workspace-root root)
  (load (expand-file-name
         "jetpacs-applet-mcp/jetpacs-applet-tooling.el" root)
        nil 'nomessage))

(ert-deftest jetpacs-applet-tooling-test-summary-is-static-and-public-by-default ()
  (let ((summary
         (jetpacs-applet-tooling-summarize-elisp-file
          "jetpacs-applet-mcp/examples/hello/hello.el")))
    (should (string-match-p "hello-register" summary))
    (should (string-match-p "hello-unregister" summary))
    (should-not (string-match-p "hello--view" summary))))

(ert-deftest jetpacs-applet-tooling-test-tooling-entrypoints-are-complete-elisp ()
  (dolist (path '("jetpacs-applet-mcp/jetpacs-applet-tooling.el"
                  "jetpacs-applet-mcp/jetpacs-applet-mcp.el"
                  "jetpacs-applet-mcp/jetpacs-applet-mcp-runtime.el"))
    (let* ((file (jetpacs-applet-tooling-resolve-file path))
           (records (jetpacs-applet-tooling--read-file file)))
      (should-not (seq-some (lambda (record)
                              (plist-get record :parse-error))
                            records)))))

(ert-deftest jetpacs-applet-tooling-test-rejects-workspace-traversal ()
  (should-error (jetpacs-applet-tooling-resolve-file "../outside.el")
                :type 'error))

(ert-deftest jetpacs-applet-tooling-test-checkout-namespaces-stay-separate-from-references ()
  (let* ((workspace (make-temp-file "jetpacs-applet-tooling-layout-" t))
         (repositories (make-temp-file "jetpacs-applet-tooling-repositories-" t))
         (reference (make-temp-file "jetpacs-applet-tooling-reference-" t))
         (prior-reference-env (getenv "JETPACS_REFERENCE_ROOTS"))
         (jetpacs-applet-tooling-workspace-root workspace)
         (jetpacs-applet-tooling-repositories-root repositories)
         (jetpacs-applet-tooling-app-source-roots nil)
         (jetpacs-applet-tooling-reference-roots nil))
    (unwind-protect
        (progn
          (dolist (namespace jetpacs-applet-tooling-app-source-names)
            (make-directory (expand-file-name namespace repositories)))
          (with-temp-file (expand-file-name "glasspane.el" repositories)
            (insert ""))
          (with-temp-file (expand-file-name "glasspane/glasspane.el" repositories)
            (insert ""))
          (with-temp-file (expand-file-name "package.el" reference)
            (insert ""))
          (setenv "JETPACS_REFERENCE_ROOTS"
                  (json-serialize (let ((object (make-hash-table :test #'equal)))
                                    (puthash "fixture" reference object)
                                    object)))
          (should (equal (jetpacs-applet-tooling-app-source-namespaces)
                         (sort (copy-sequence jetpacs-applet-tooling-app-source-names)
                               #'string<)))
          (should (file-regular-p
                   (jetpacs-applet-tooling-resolve-file "glasspane/glasspane.el")))
          (should (jetpacs-applet-tooling-reference-path-p "fixture/package.el"))
          (should (jetpacs-applet-tooling-trusted-source-root
                   (jetpacs-applet-tooling-resolve-file "glasspane/glasspane.el")))
          (should-not
           (jetpacs-applet-tooling-trusted-source-root
            (jetpacs-applet-tooling-resolve-file "fixture/package.el"))))
      (setenv "JETPACS_REFERENCE_ROOTS" prior-reference-env)
      (delete-directory workspace t)
      (delete-directory repositories t)
      (delete-directory reference t))))

(ert-deftest jetpacs-applet-tooling-test-checkout-symlink-and-escape-are-rejected ()
  (let* ((workspace (make-temp-file "jetpacs-applet-tooling-escape-workspace-" t))
         (repositories (make-temp-file "jetpacs-applet-tooling-escape-repositories-" t))
         (outside (make-temp-file "jetpacs-applet-tooling-escape-outside-" t))
         (jetpacs-applet-tooling-workspace-root workspace)
         (jetpacs-applet-tooling-repositories-root repositories)
         (jetpacs-applet-tooling-app-source-roots nil)
         (jetpacs-applet-tooling-reference-roots nil))
    (unwind-protect
        (progn
          (dolist (namespace jetpacs-applet-tooling-app-source-names)
            (make-directory (expand-file-name namespace repositories)))
          (delete-directory (expand-file-name "glasspane" repositories))
          (make-symbolic-link outside (expand-file-name "glasspane" repositories))
          (should-error (jetpacs-applet-tooling-resolved-app-source-roots)
                        :type 'error)
          (should (jetpacs-applet-tooling-path-has-parent-segment-p
                   "glasspane/../outside.el"))
          (should-error
           (jetpacs-applet-tooling-resolve-file
            (expand-file-name "outside.el" outside))
           :type 'error))
      (delete-directory workspace t)
      (delete-directory repositories t)
      (delete-directory outside t))))

(ert-deftest jetpacs-applet-tooling-test-rejects-overlapping-org-root ()
  (let* ((root (make-temp-file "jetpacs-applet-tooling-overlap-" t))
         (org-root (expand-file-name "org-source" root))
         (jetpacs-applet-tooling-workspace-root root)
         (jetpacs-applet-tooling-org-source-root org-root))
    (unwind-protect
        (progn
          (make-directory org-root)
          (should-error (jetpacs-applet-tooling-org-source-root) :type 'error))
      (delete-directory root t))))

(ert-deftest jetpacs-applet-tooling-test-rejects-overlapping-reference-roots ()
  (let* ((workspace (make-temp-file "jetpacs-applet-tooling-ref-workspace-" t))
         (first (make-temp-file "jetpacs-applet-tooling-ref-first-" t))
         (second (expand-file-name "nested" first))
         (jetpacs-applet-tooling-workspace-root workspace)
         (jetpacs-applet-tooling-reference-roots
          `(("first" . ,first) ("second" . ,second)))
         (jetpacs-applet-tooling-org-source-root nil))
    (unwind-protect
        (progn
          (make-directory second)
          (should-error
           (jetpacs-applet-tooling-resolved-reference-roots)
           :type 'error))
      (delete-directory workspace t)
      (delete-directory first t))))

(ert-deftest jetpacs-applet-tooling-test-applet-directory-does-not-follow-outside-links ()
  (let ((root (make-temp-file "jetpacs-applet-tooling-root-" t))
        (outside (make-temp-file "jetpacs-applet-tooling-outside-" nil ".el")))
    (unwind-protect
        (let* ((jetpacs-applet-tooling-workspace-root root)
               (applet (expand-file-name "applet" root))
               (link (expand-file-name "escape.el" applet)))
          (make-directory applet)
          (make-symbolic-link outside link)
          (should-error (jetpacs-applet-tooling--applet-files "applet")
                        :type 'error))
      (delete-directory root t)
      (delete-file outside))))

(ert-deftest jetpacs-applet-tooling-test-node-joins-contract-and-builder ()
  (let ((description (jetpacs-applet-tooling-describe-node "button")))
    (should (string-match-p "Required fields: label, on_tap" description))
    (should (string-match-p "Builder: jetpacs-button" description))
    (should (string-match-p "button.variant" description))))

(ert-deftest jetpacs-applet-tooling-test-org-source-is-a-separate-readable-root ()
  (let ((description (jetpacs-applet-tooling-describe-symbol "org-entry-get")))
    (should (string-match-p "org/org.el:" description))
    (should (string-match-p "(defun org-entry-get" description))))

(ert-deftest jetpacs-applet-tooling-test-org-exact-lookup-does-not-assume-org-prefix ()
  (let ((description
         (jetpacs-applet-tooling-describe-symbol "ob-clojure-inf-clojure-output")))
    (should (string-match-p "org/ob-clojure.el:" description))
    (should (string-match-p "(defun ob-clojure-inf-clojure-output"
                            description))))

(ert-deftest jetpacs-applet-tooling-test-org-light-index-excludes-syntax-decoys ()
  (let* ((root (make-temp-file "jetpacs-applet-tooling-org-index-" t))
         (file (expand-file-name "org-fixture.el" root))
         (jetpacs-applet-tooling-org-source-root root))
    (unwind-protect
        (progn
          (with-temp-file file
            (insert ";; (defun decoy-comment () nil)\n"
                    "(defconst fixture-text \"a multiline string\n"
                    "(defun decoy-string () nil)\n"
                    "still inside the string\")\n"
                    "(defun actual-top-level () \"Documented fixture.\" t)\n"
                    "(when t\n"
                    "(defun decoy-nested () nil))\n"))
          (let ((names (mapcar (lambda (record) (plist-get record :name))
                               (jetpacs-applet-tooling--light-index-org-file file))))
            (should (member "actual-top-level" names))
            (should (member "fixture-text" names))
            (should-not (member "decoy-comment" names))
            (should-not (member "decoy-string" names))
            (should-not (member "decoy-nested" names))))
      (delete-directory root t))))

(ert-deftest jetpacs-applet-tooling-test-arbitrary-package-source-is-namespaced ()
  (let* ((workspace (make-temp-file "jetpacs-applet-tooling-workspace-" t))
         (package-root (make-temp-file "jetpacs-applet-tooling-package-" t))
         (file (expand-file-name "fixture-api.el" package-root))
         (jetpacs-applet-tooling-workspace-root workspace)
         (jetpacs-applet-tooling-reference-roots
          `(("fixture" . ,package-root)))
         (jetpacs-applet-tooling-org-source-root nil))
    (unwind-protect
        (progn
          (with-temp-file file
            (insert ";;; fixture-api.el --- Reference fixture -*- lexical-binding: t; -*-\n"
                    "(defun fixture-package-run (value)\n"
                    "  \"Run the package operation for VALUE.\"\n"
                    "  value)\n"))
          (should (member "fixture"
                          (jetpacs-applet-tooling-reference-namespaces)))
          (should (string-match-p
                   "fixture/fixture-api.el:2"
                   (jetpacs-applet-tooling-describe-symbol
                    "fixture-package-run")))
          (should (string-match-p
                   "fixture-package-run"
                   (jetpacs-applet-tooling-list-api
                    "fixture" "package-run" 10 nil)))
          (should (string-match-p
                   "Elisp API summary: fixture/fixture-api.el"
                   (jetpacs-applet-tooling-summarize-elisp-file
                    "fixture/fixture-api.el"))))
      (delete-directory workspace t)
      (delete-directory package-root t))))

(ert-deftest jetpacs-applet-tooling-test-reference-roots-environment-is-json-object ()
  (let* ((workspace (make-temp-file "jetpacs-applet-tooling-env-workspace-" t))
         (package-root (make-temp-file "jetpacs-applet-tooling-env-package-" t))
         (prior (getenv "JETPACS_REFERENCE_ROOTS"))
         (jetpacs-applet-tooling-workspace-root workspace)
         (jetpacs-applet-tooling-reference-roots nil)
         (jetpacs-applet-tooling-org-source-root nil))
    (unwind-protect
        (progn
          (let ((object (make-hash-table :test #'equal)))
            (puthash "fixture" package-root object)
            (setenv "JETPACS_REFERENCE_ROOTS" (json-serialize object)))
          (should (equal
                   (cdr (assoc
                         "fixture"
                         (jetpacs-applet-tooling-resolved-reference-roots)))
                   (file-name-as-directory (file-truename package-root)))))
      (setenv "JETPACS_REFERENCE_ROOTS" prior)
      (delete-directory workspace t)
      (delete-directory package-root t))))

(ert-deftest jetpacs-applet-tooling-test-reference-applet-validates ()
  (let ((report
         (jetpacs-applet-tooling-validate-applet
          "jetpacs-applet-mcp/examples/hello/hello.el")))
    (should (string-match-p "Status: PASS" report))
    (should (string-match-p "1 registered action" report))
    (should (string-match-p "0 unresolved names" report))))

(ert-deftest jetpacs-applet-tooling-test-glasspane-documentation-contract-is-complete ()
  (let ((validation (jetpacs-applet-tooling-validate-applet "glasspane"))
        (inventory (jetpacs-applet-tooling-inspect-applet "glasspane")))
    (should (string-match-p "Status: PASS" validation))
    (should (string-match-p "0 unresolved names" validation))
    (should (string-match-p "No static issues found" validation))
    ;; This is a semantic contract, not an incidental Glasspane action total.
    (should (string-match-p "Registered remote actions" inventory))
    (should (string-match-p "glasspane.document.open" inventory))
    (should (string-match-p "glasspane.files.return" inventory))
    (should (string-match-p "unresolved literal names (0): none" inventory))
    (should (string-match-p "Jetpacs-platform-provided in source" inventory))))

(ert-deftest jetpacs-applet-tooling-test-action-emissions-reconcile-semantically ()
  (let* ((root (make-temp-file "jetpacs-applet-tooling-actions-" t))
         (file (expand-file-name "fixture.el" root))
         (jetpacs-applet-tooling-workspace-root root)
         (jetpacs-applet-tooling-platform-source-directory nil))
    (unwind-protect
        (progn
          (with-temp-file file
            (insert ";;; fixture.el --- Action fixture -*- lexical-binding: t; -*-\n"
                    ";; Package-Requires: ((emacs \"30.1\"))\n"
                    "(defconst fixture-owner \"fixture\" \"Stable owner.\")\n"
                    "(defun fixture--run (_args _params) \"Run.\" 'accepted)\n"
                    "(defun fixture-view (dynamic)\n"
                    "  \"Build action fixtures.\"\n"
                    "  (list (jetpacs-action \"fixture.run\")\n"
                    "        (jetpacs-action \"missing.run\")\n"
                    "        (jetpacs-action dynamic)\n"
                    "        '(jetpacs-action \"quoted.decoy\")))\n"
                    "(defun fixture-register () \"Register.\"\n"
                    "  (with-jetpacs-owner fixture-owner\n"
                    "    (jetpacs-chrome-define-root fixture-owner \"home\" #'fixture-view)\n"
                    "    (jetpacs-defaction \"fixture.run\" #'fixture--run :doc \"Run.\"))\n"
                    "  (jetpacs-defapp fixture-owner :label \"Fixture\" :surfaces '(\"fixture\")))\n"
                    "(provide 'fixture)\n"))
          (let* ((scan (jetpacs-applet-tooling--scan-applet-file file))
                 (result (jetpacs-applet-tooling--action-reconciliation
                          (list scan) nil)))
            (should (equal (plist-get result :local-names) '("fixture.run")))
            (should (equal (plist-get result :unresolved-names)
                           '("missing.run")))
            (should (= (length (plist-get result :dynamic-emissions)) 1))
            (should-not (member "quoted.decoy"
                                (plist-get result :emitted-names))))
          (let ((report (jetpacs-applet-tooling-validate-applet "fixture.el")))
            (should (string-match-p "Status: FAIL" report))
            (should (string-match-p "action.unresolved-provider" report))
            (should (string-match-p "missing.run" report))))
      (delete-directory root t))))

(ert-deftest jetpacs-applet-tooling-test-action-reconciliation-finds-platform-provider ()
  (let* ((root (make-temp-file "jetpacs-applet-tooling-provider-" t))
         (platform-directory (expand-file-name "emacs" root))
         (platform-file (expand-file-name "jetpacs-fixture.el"
                                          platform-directory))
         (applet-file (expand-file-name "skin.el" root))
         (jetpacs-applet-tooling-workspace-root root))
    (unwind-protect
        (progn
          (make-directory platform-directory)
          (with-temp-file platform-file
            (insert ";;; jetpacs-fixture.el --- Provider -*- lexical-binding: t; -*-\n"
                    ";; Package-Requires: ((emacs \"30.1\"))\n"
                    "(defconst jetpacs-fixture-owner \"jetpacs.fixture\" \"Owner.\")\n"
                    "(defun jetpacs-fixture--run (_args _params) \"Run.\" 'accepted)\n"
                    "(with-jetpacs-owner jetpacs-fixture-owner\n"
                    "  (jetpacs-defaction \"jetpacs.fixture.run\" #'jetpacs-fixture--run :doc \"Run.\"))\n"
                    "(provide 'jetpacs-fixture)\n"))
          (with-temp-file applet-file
            (insert ";;; skin.el --- Skin -*- lexical-binding: t; -*-\n"
                    ";; Package-Requires: ((emacs \"30.1\"))\n"
                    "(defun skin-view () \"Build.\"\n"
                    "  (jetpacs-action \"jetpacs.fixture.run\"))\n"
                    "(provide 'skin)\n"))
          (let* ((applet-scan
                  (jetpacs-applet-tooling--scan-applet-file applet-file))
                 (platform-scan
                  (jetpacs-applet-tooling--scan-applet-file platform-file))
                 (result
                  (jetpacs-applet-tooling--action-reconciliation
                   (list applet-scan) (list platform-scan))))
            (should (equal (plist-get result :platform-names)
                           '("jetpacs.fixture.run")))
            (should-not (plist-get result :unresolved-names))))
      (delete-directory root t))))

(ert-deftest jetpacs-applet-tooling-test-action-vocabulary-comes-from-contract-and-elisp ()
  (let ((report (jetpacs-applet-tooling-action-vocabulary)))
    (should (string-match-p "Remote semantic actions are open-world" report))
    (should (string-match-p "surface.open" report))
    (should (string-match-p "jetpacs-surface-open" report))
    (should (string-match-p "dialog.submit" report))))

(ert-deftest jetpacs-applet-tooling-test-applet-manifest-is-canonical-and-semantic ()
  (let* ((path "jetpacs-applet-mcp/examples/hello/hello.el")
         (first (jetpacs-applet-tooling-applet-manifest path))
         (second (jetpacs-applet-tooling-applet-manifest (concat "./" path)))
         (configured
          (let ((print-length 1)
                (print-level 1)
                (pp-default-function #'pp-emacs-lisp-code))
            (jetpacs-applet-tooling-applet-manifest path))))
    (should (equal first second))
    (should (equal first configured))
    (should (string-match-p "jetpacs.applet-manifest/2" first))
    (should (string-match-p
             ":name[[:space:]\n]+\"jetpacs-applet-tooling\"" first))
    (should (string-match-p ":emacs-version" first))
    (should (string-match-p ":analysis-inputs" first))
    (should (string-match-p "ebp/contract.json" first))
    (should (string-match-p ":owner \"hello\"" first))
    (should (string-match-p ":name \"hello.refresh\"" first))
    (should (string-match-p ":action-emissions" first))
    (should (string-match-p ":constructor \"jetpacs-action\"" first))
    (should (string-match-p ":builtin-emissions" first))
    (should (string-match-p
             ":signature[[:space:]\n]+\"(_args params)\"" first))
    (should (string-match-p
             "Queue a refresh using PARAMS" first))
    (should (string-match-p "sha256: [[:xdigit:]]\\{64\\}" first))))

(ert-deftest jetpacs-applet-tooling-test-manifest-provenance-points-to-registration ()
  (let* ((manifest
          (jetpacs-applet-tooling--applet-manifest-data
         "jetpacs-applet-mcp/examples/hello/hello.el"))
         (action (aref (plist-get manifest :actions) 0))
         (emission (aref (plist-get manifest :action-emissions) 0))
         (root (aref (plist-get manifest :roots) 0)))
    (should (= (plist-get action :line) 43))
    (should (= (plist-get emission :line) 30))
    (should (equal (plist-get emission :name) "hello.refresh"))
    (should (= (plist-get root :line) 41))
    (should (equal (plist-get action :doc)
                   "Refresh the applet's root surface"))))

(ert-deftest jetpacs-applet-tooling-test-provenance-distinguishes-identical-quoted-form ()
  (let* ((root (make-temp-file "jetpacs-applet-tooling-positioned-reader-" t))
         (file (expand-file-name "fixture.el" root))
         (jetpacs-applet-tooling-workspace-root root))
    (unwind-protect
        (progn
          (with-temp-file file
            (insert ";;; fixture.el --- Positioned reader fixture -*- lexical-binding: t; -*-\n"
                    ";; Package-Requires: ((emacs \"30.1\"))\n\n"
                    "(defconst fixture-owner \"fixture\" \"Stable owner.\")\n"
                    "(defun fixture--run (_args _params) \"Run fixture.\" 'accepted)\n"
                    "'(jetpacs-defaction \"fixture.run\" #'fixture--run :doc \"Run fixture.\")\n"
                    "(with-jetpacs-owner fixture-owner\n"
                    "  (jetpacs-defaction \"fixture.run\" #'fixture--run :doc \"Run fixture.\"))\n"
                    "(provide 'fixture)\n"))
          (let ((actions (plist-get (jetpacs-applet-tooling--scan-applet-file file)
                                    :actions)))
            (should (= (length actions) 1))
            (should (= (plist-get (car actions) :line) 8))))
      (delete-directory root t))))

(ert-deftest jetpacs-applet-tooling-test-large-manifest-is-pageable-not-truncated ()
  (let ((jetpacs-applet-tooling-max-result-chars 2500)
        (path "jetpacs-applet-mcp/examples/hello/hello.el"))
    (let ((index (jetpacs-applet-tooling-applet-manifest path)))
      (should (string-match-p "canonical applet manifest index" index))
      (should (string-match-p ":manifest-sha256" index))
      (should (string-match-p ":name \"actions\"" index))
      (should (string-match-p ":name \"action-emissions\"" index))
      (should-not (string-match-p "output truncated" index)))
    (let ((page (jetpacs-applet-tooling-applet-manifest path "actions" 0 10)))
      (should (string-match-p "canonical applet manifest page" page))
      (should (string-match-p ":name \"hello.refresh\"" page))
      (should (string-match-p ":next-offset[[:space:]\n]+nil" page))
      (should-not (string-match-p "output truncated" page)))))

(ert-deftest jetpacs-applet-tooling-test-template-is-readable-and-lifecycle-complete ()
  (let ((template (jetpacs-applet-tooling-applet-template "weather" "Weather")))
    (should (string-match-p "weather-register" template))
    (should (string-match-p "weather-unregister" template))
    (should (string-match-p "with-jetpacs-owner" template))
    (should (string-match-p "Queue a refresh using PARAMS" template))
    (with-temp-buffer
      (insert template)
      (goto-char (point-min))
      (condition-case err
          (while t (read (current-buffer)))
        (end-of-file nil)
        (error (ert-fail (error-message-string err)))))))

(ert-deftest jetpacs-applet-tooling-test-reference-applet-is-scaffold-form-for-form ()
  (let ((reference
         (with-temp-buffer
           (insert-file-contents
            (jetpacs-applet-tooling-resolve-file
             "jetpacs-applet-mcp/examples/hello/hello.el"))
           (buffer-string))))
    (should
     (equal (jetpacs-applet-tooling--read-forms-from-string reference)
            (jetpacs-applet-tooling--read-forms-from-string
             (jetpacs-applet-tooling-applet-template "hello" "Hello"))))))

(ert-deftest jetpacs-applet-tooling-test-generated-template-passes-its-validator ()
  (let* ((root (make-temp-file "jetpacs-applet-tooling-template-" t))
         (file (expand-file-name "weather.el" root))
         (jetpacs-applet-tooling-workspace-root root))
    (unwind-protect
        (progn
          (with-temp-file file
            (insert (jetpacs-applet-tooling-applet-template "weather" "Weather")))
          (should (string-match-p "Status: PASS"
                                  (jetpacs-applet-tooling-validate-applet "weather.el"))))
      (delete-directory root t))))

(ert-deftest jetpacs-applet-tooling-test-walker-tolerates-dotted-fixture-data ()
  (let (visited)
    (jetpacs-applet-tooling--walk-form
     '(defconst fixture `(("name" . "contents")))
     (lambda (form _ancestors) (push (car-safe form) visited)))
    (should (memq 'defconst visited))))

(ert-deftest jetpacs-applet-tooling-test-prompts-in-flow-continuations-are-not-d2-errors ()
  (let ((form '(defun app--handler (_args _params)
                 (jetpacs-flow-continue
                  (lambda () (completing-read "Pick: " '("a" "b"))))
                 'accepted)))
    (should-not
     (jetpacs-applet-tooling--calls-in-form
      form jetpacs-applet-tooling--prompting-functions '(jetpacs-flow-continue)))))

(ert-deftest jetpacs-applet-tooling-test-direct-prompts-are-d2-errors ()
  (let ((form '(defun app--handler (_args _params)
                 (read-string "Value: ")
                 'accepted)))
    (should
     (equal '(read-string)
            (jetpacs-applet-tooling--calls-in-form
             form jetpacs-applet-tooling--prompting-functions
             '(jetpacs-flow-continue))))))

(provide 'jetpacs-applet-tooling-test)
;;; jetpacs-applet-tooling-test.el ends here
