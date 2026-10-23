;;; jetpacs-applet-mcp-runtime-test.el --- Trusted runtime tests -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)

(let* ((test-dir (file-name-directory (or load-file-name buffer-file-name)))
       (root (expand-file-name "../.." test-dir)))
  (setq jetpacs-applet-tooling-workspace-root root
        jetpacs-applet-mcp-suppress-auto-start t)
  (add-to-list 'load-path
               (expand-file-name "../ebp.el/lisp" root))
  (add-to-list 'load-path (expand-file-name "emacs" root))
  (load "jetpacs-devtools" nil 'nomessage)
  (load (expand-file-name "jetpacs-applet-mcp/jetpacs-applet-mcp.el" root) nil 'nomessage)
  (defvar jetpacs-applet-mcp-runtime-test--root root)
  ;; As the trusted launcher does: load while recording, then register.
  (let ((hello (expand-file-name "jetpacs-applet-mcp/examples/hello/hello.el"
                                 root)))
    (defvar jetpacs-applet-mcp-runtime-test--hello-at-load
      (jetpacs-applet-tooling-load-recording-registrations hello))
    (jetpacs-apps-call-register
     (car (jetpacs-applet-tooling-lifecycle-functions "hello.el")) hello)))

(ert-deftest jetpacs-applet-mcp-runtime-test-live-registry-has-source-provenance ()
  (let ((inventory (jetpacs-applet-tooling-runtime-inventory)))
    (should (string-match-p ":surface \"app:hello\"" inventory))
    (should (string-match-p ":name \"hello.refresh\"" inventory))
    (should (string-match-p
             "jetpacs-applet-mcp/examples/hello/hello.el" inventory))))

(ert-deftest jetpacs-applet-mcp-runtime-test-two-builds-have-one-canonical-hash ()
  (let ((report (jetpacs-applet-tooling-check-render-determinism "hello" nil)))
    (should (string-match-p "Status: STABLE" report))
    (should (string-match-p "Canonical outputs equal: yes" report))
    (should (string-match-p "Fixed structural bounds: pass" report))
    (should (string-match-p
             (regexp-quote
              "Real Jetpacs gates (offline variant + identity): pass")
             report))
    (should (string-match-p "Builder failures captured: 0" report))))

(ert-deftest jetpacs-applet-mcp-runtime-test-hello-is-side-effect-free-at-load ()
  "Loading hello registered nothing from its own file; its register did."
  (should-not jetpacs-applet-mcp-runtime-test--hello-at-load)
  (should (gethash "hello.refresh" jetpacs-action-handlers)))

(ert-deftest jetpacs-applet-mcp-runtime-test-registration-at-load-is-recorded ()
  "An applet whose file registers at load is caught, naming the source."
  (let* ((fixture (expand-file-name
                   "jetpacs-applet-mcp/test/fixtures/lifecycle/at-load/atload.el"
                   jetpacs-applet-mcp-runtime-test--root))
         (at-load (unwind-protect
                      (jetpacs-applet-tooling-load-recording-registrations fixture)
                    (when (fboundp 'atload-unregister)
                      (funcall 'atload-unregister)))))
    (should (member "action atload.refresh (atload.el)" at-load))
    (should (member "app atload (atload.el)" at-load))))

(ert-deftest jetpacs-applet-mcp-runtime-test-lifecycle-symmetry-tool ()
  "Hello's unregister restores every registry its register touched, and
the tool leaves hello registered as it found it."
  (let ((report (jetpacs-applet-tooling-check-lifecycle-symmetry "hello")))
    (should (string-match-p "Status: SYMMETRIC" report))
    (should (string-match-p "Registered on entry: yes" report)))
  (should (gethash "hello.refresh" jetpacs-action-handlers))
  ;; A leak is reported: an extra global hook the unregister never removes.
  (let ((leaky (lambda () (add-hook 'jetpacs-reset-functions #'ignore))))
    (unwind-protect
        (progn
          (advice-add 'hello-register :after leaky)
          (let ((report (jetpacs-applet-tooling-check-lifecycle-symmetry
                         "hello")))
            (should (string-match-p "Status: ASYMMETRIC" report))
            (should (string-match-p "\\+ hook jetpacs-reset-functions ignore"
                                    report))))
      (advice-remove 'hello-register leaky)
      (remove-hook 'jetpacs-reset-functions #'ignore)))
  (should-error (jetpacs-applet-tooling-check-lifecycle-symmetry "no-such-app")))

(ert-deftest jetpacs-applet-mcp-runtime-test-marks-builder-invocation-non-read-only ()
  (let* ((tools (append (jetpacs-applet-mcp--tools) nil))
         (render (seq-find
                  (lambda (tool)
                    (equal (gethash "name" tool)
                           "jetpacs_check_render_determinism"))
                  tools)))
    (should render)
    (should (eq (gethash "readOnlyHint" (gethash "annotations" render))
                :json-false))
    (should (seq-some
             (lambda (tool)
               (equal (gethash "name" tool) "jetpacs_runtime_inventory"))
             tools))
    (let ((symmetry (seq-find
                     (lambda (tool)
                       (equal (gethash "name" tool)
                              "jetpacs_check_lifecycle_symmetry"))
                     tools)))
      (should symmetry)
      (should (eq (gethash "readOnlyHint" (gethash "annotations" symmetry))
                  :json-false)))))

(provide 'jetpacs-applet-mcp-runtime-test)
;;; jetpacs-applet-mcp-runtime-test.el ends here
