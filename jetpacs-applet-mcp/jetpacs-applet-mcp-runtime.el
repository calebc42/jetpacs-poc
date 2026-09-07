;;; jetpacs-applet-mcp-runtime.el --- Explicit trusted applet MCP launcher -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; This is the deliberate code-execution boundary for Jetpacs applet
;; development.  The ordinary jetpacs-applet-mcp.el server reads source but never
;; loads it.  This launcher requires JETPACS_TRUSTED_APPLET, loads the real
;; Jetpacs runtime and that applet, then exposes live registry and render
;; determinism tools in addition to the static surface.
;;
;; The chosen applet is arbitrary Elisp.  Use this only for code you trust.

;;; Code:

(require 'subr-x)
(require 'seq)

(declare-function jetpacs-applet-mcp-start-server "jetpacs-applet-mcp" ())
(declare-function jetpacs-applet-tooling--relative "jetpacs-applet-tooling" (path))
(declare-function jetpacs-applet-tooling-reference-path-p "jetpacs-applet-tooling" (path))
(declare-function jetpacs-applet-tooling-resolve-file "jetpacs-applet-tooling" (path))
(declare-function jetpacs-applet-tooling-trusted-source-root "jetpacs-applet-tooling" (path))
(declare-function jetpacs-applet-tooling-path-has-parent-segment-p "jetpacs-applet-tooling" (path))
(declare-function jetpacs-applet-tooling-resolved-app-source-roots "jetpacs-applet-tooling" ())
(declare-function jetpacs-applet-tooling-runtime-available-p "jetpacs-applet-tooling" ())
(declare-function jetpacs-applet-tooling-workspace-root "jetpacs-applet-tooling" ())

(defvar jetpacs-applet-mcp-suppress-auto-start)

(let* ((launcher-file (or load-file-name
                          (bound-and-true-p byte-compile-current-file)))
       (mcp-directory (file-name-directory launcher-file))
       (tooling-file (expand-file-name "jetpacs-applet-tooling.el"
                                       mcp-directory)))
  (load tooling-file nil 'nomessage)
  (let* ((trusted (getenv "JETPACS_TRUSTED_APPLET"))
         (workspace (jetpacs-applet-tooling-workspace-root))
         (applet
          (progn
            (unless (and trusted (not (string-empty-p trusted)))
              (error (concat
                      "Jetpacs Applet MCP runtime: JETPACS_TRUSTED_APPLET is required; "
                      "runtime mode executes that Elisp")))
            (unless (string-match-p (rx ".el" eos) trusted)
              (error "Jetpacs Applet MCP runtime: trusted applet must be an .el file"))
            (when (jetpacs-applet-tooling-path-has-parent-segment-p trusted)
              (error "Jetpacs Applet MCP runtime: trusted applet path may not contain parent segments"))
            (when (jetpacs-applet-tooling-reference-path-p trusted)
              (error (concat
                      "Jetpacs Applet MCP runtime: trusted applet must be inside the "
                      "Jetpacs workspace, not a read-only reference namespace")))
            (let ((resolved (jetpacs-applet-tooling-resolve-file trusted)))
              (unless (jetpacs-applet-tooling-trusted-source-root resolved)
                (error (concat
                        "Jetpacs Applet MCP runtime: trusted applet is outside an "
                        "allowlisted Jetpacs checkout")))
              resolved)))
         (platform
          (seq-find #'file-directory-p
                    (mapcar (lambda (relative)
                              (expand-file-name relative workspace))
                            '("emacs")))))
    (unless platform
      (error "Jetpacs Applet MCP runtime: no Jetpacs Emacs source directory found"))
    ;; Jetpacs platform source and applet siblings win over stale neighboring
    ;; byte-code.  Read-only package reference roots deliberately do NOT enter
    ;; `load-path': they are inspection evidence and may not match the running
    ;; Emacs.  The applet uses dependencies already present in its trusted
    ;; runtime graph; source inspection never authorizes dependency execution.
    ;; Prefer a newer .el over a stale neighboring .elc: this is a development
    ;; process, and its live inventory/render evidence must match current
    ;; source rather than an opaque prior build artifact.
    (setq load-prefer-newer t)
    (add-to-list 'load-path platform)
    ;; Make optional local authoring available without requiring it: the
    ;; explicitly trusted applet still chooses its dependencies.
    (let ((material3 (expand-file-name "apps/jetpacs-material3" platform)))
      (when (file-directory-p material3)
        (add-to-list 'load-path material3)))
    ;; Optional in-repository modules remain dependency choices of the applet.
    (dolist (relative '("jetpacs-components/lisp/jetpacs-components"
                        "jetpacs-automations/lisp"
                        "jetpacs-component-catalog/lisp"))
      (let ((directory (expand-file-name relative workspace)))
        (when (file-directory-p directory)
          (unless (file-in-directory-p (file-truename directory) workspace)
            (error "Jetpacs runtime module leaves workspace: %s" relative))
          (add-to-list 'load-path directory))))
    (add-to-list 'load-path (file-name-directory applet))
    ;; Active sibling checkouts are an explicit runtime dependency boundary,
    ;; distinct from inspection-only reference roots.  Add only each named
    ;; checkout and its conventional authoring directories; never add arbitrary
    ;; directories below the repositories root.
    (dolist (entry (jetpacs-applet-tooling-resolved-app-source-roots))
      (let ((checkout (cdr entry)))
        (dolist (directory
                 (list checkout (expand-file-name "lisp" checkout)))
          (when (file-directory-p directory)
            (add-to-list 'load-path directory)))))
    (require 'jetpacs-devtools)
    (load applet nil 'nomessage)
    (setq jetpacs-applet-mcp-suppress-auto-start t)
    (load (expand-file-name "jetpacs-applet-mcp.el" mcp-directory) nil 'nomessage)
    (unless (jetpacs-applet-tooling-runtime-available-p)
      (error "Jetpacs Applet MCP runtime: runtime introspection failed to initialize"))
    (message "Jetpacs Applet MCP runtime: trusted applet loaded: %s"
             (jetpacs-applet-tooling--relative applet))
    (jetpacs-applet-mcp-start-server)))

(provide 'jetpacs-applet-mcp-runtime)
;;; jetpacs-applet-mcp-runtime.el ends here
