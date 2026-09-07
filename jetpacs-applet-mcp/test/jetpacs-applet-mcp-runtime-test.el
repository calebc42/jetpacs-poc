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
  (load (expand-file-name "jetpacs-applet-mcp/examples/hello/hello.el" root)
        nil 'nomessage)
  (load (expand-file-name "jetpacs-applet-mcp/jetpacs-applet-mcp.el" root) nil 'nomessage))

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
             tools))))

(provide 'jetpacs-applet-mcp-runtime-test)
;;; jetpacs-applet-mcp-runtime-test.el ends here
