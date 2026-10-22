;;; smoke-desktop.el --- D5 gate: a desktop Emacs drives the desktop Companion -*- lexical-binding: t; -*-

;; Run from the jetpacs-poc root against a running desktop Companion
;; (`cd companion && ./gradlew :desktop:run`, docs/PLAN-desktop-companion.md):
;;
;;   JETPACS_PORT=8766 emacs -Q --batch -l test/smoke-desktop.el
;;
;; Environment: JETPACS_HOST (127.0.0.1), JETPACS_PORT (8765),
;; JETPACS_PAIRING_ID and JETPACS_PAIRING_TOKEN (default: the SPEC 9.3
;; known-answer credential, which a Companion started with
;; JETPACS_DEV_PAIRING=public-known-answer accepts; paste the pairing
;; screen's values for a real pairing), JETPACS_REPOSITORIES_ROOT (the
;; parent of this checkout), and JETPACS_SMOKE_WAIT_ACTION=1 to wait up to
;; 60 s for a click on a demo button in the Material catalog window.
;;
;; Everything Emacs-side lands in a temporary directory: the managed tree,
;; the receipts database and the Glasspane demo Org corpus.  Exit 0 iff the
;; session reaches READY, the app:m3catalog and app:glasspane pushes return
;; applied, and (when waited for) the m3catalog.demo action arrives.

(require 'cl-lib)

;; A stale .elc beside a source file must not stand in for it.
(setq load-prefer-newer t)

(defvar smoke-desktop--scratch (make-temp-file "jetpacs-smoke-desktop-" t)
  "Where this run keeps every Emacs-side file.")

(let* ((here (file-name-directory (or load-file-name buffer-file-name)))
       (root (expand-file-name ".." here))
       (repos (or (getenv "JETPACS_REPOSITORIES_ROOT")
                  (expand-file-name ".." root))))
  ;; `jetpacs-install-root' and the receipts live under the Emacs directory.
  (setq user-emacs-directory
        (file-name-as-directory (expand-file-name "emacs.d" smoke-desktop--scratch)))
  (make-directory user-emacs-directory t)
  (setq org-directory (expand-file-name "org/" smoke-desktop--scratch))
  (make-directory org-directory t)
  (setq custom-file (expand-file-name "custom.el" smoke-desktop--scratch))
  ;; The same roots test/run-tests.sh gives every Emacs it starts.
  (dolist (dir (list (expand-file-name "ebp.el/lisp" repos)
                     (expand-file-name "ebp-org/lisp" repos)
                     (expand-file-name "jetpacs-authoring/lisp" repos)
                     (expand-file-name "emacs" root)
                     (expand-file-name "emacs/apps/jetpacs-material3" root)
                     (expand-file-name "emacs/apps/m3-catalog" root)
                     (expand-file-name "jetpacs-components/lisp/jetpacs-components" root)
                     (expand-file-name "jetpacs-automations/lisp" root)
                     (expand-file-name "jetpacs-component-catalog/lisp" root)
                     (expand-file-name "glasspane" repos)))
    (add-to-list 'load-path dir)))

;; `after-init-hook' has already run under -l, so `jetpacs-start' below is
;; the one dial; the variables are set before it.
(require 'jetpacs)
(setq jetpacs-host (or (getenv "JETPACS_HOST") "127.0.0.1")
      jetpacs-port (string-to-number (or (getenv "JETPACS_PORT") "8765"))
      jetpacs-pairing-id (or (getenv "JETPACS_PAIRING_ID") jetpacs-pairing-id)
      jetpacs-pairing-token (or (getenv "JETPACS_PAIRING_TOKEN") jetpacs-pairing-token))

;; Loading Glasspane registers it; the demo corpus gives its agenda rows.
(require 'glasspane)
(glasspane-demo-setup-org org-directory)

(defvar smoke-desktop--demo-seen nil
  "Set once `m3catalog.demo' reaches its handler.")
(advice-add 'jetpacs-m3--on-demo :after
            (lambda (&rest _) (setq smoke-desktop--demo-seen t)))

(defun smoke-desktop--wait (seconds predicate)
  "Pump the network for up to SECONDS until PREDICATE returns non-nil."
  (cl-loop repeat (* 10 seconds)
           until (funcall predicate)
           do (accept-process-output nil 0.1))
  (funcall predicate))

(jetpacs-start)
(unless (smoke-desktop--wait 30 #'jetpacs-connected-p)
  (message "smoke-desktop: no READY session on %s:%d (state=%s close=%S)"
           jetpacs-host jetpacs-port
           (and (jetpacs-client) (ebp-client-state (jetpacs-client)))
           (and (jetpacs-client) (ebp-client-close-reason (jetpacs-client))))
  (kill-emacs 1))

(let ((m3 nil) (glasspane nil))
  (jetpacs-m3-catalog)
  (jetpacs-shell-push "app:m3catalog"
                      :callback (lambda (status _error) (setq m3 status)))
  (glasspane)
  (jetpacs-shell-push "app:glasspane"
                      :callback (lambda (status _error) (setq glasspane status)))
  (smoke-desktop--wait 30 (lambda () (and m3 glasspane)))
  (message "smoke-desktop: state=%s snackbar-granted=%s app:m3catalog=%s app:glasspane=%s"
           (ebp-client-state (jetpacs-client))
           (jetpacs-granted-p "presentation.snackbar")
           m3 glasspane)
  (let ((ok (and (equal m3 "applied") (equal glasspane "applied"))))
    (when (and ok (getenv "JETPACS_SMOKE_WAIT_ACTION"))
      (message "smoke-desktop: click a demo button in the Material catalog within 60 s")
      (smoke-desktop--wait 60 (lambda () smoke-desktop--demo-seen))
      (message "smoke-desktop: m3catalog.demo %s"
               (if smoke-desktop--demo-seen "arrived and was accepted" "did not arrive"))
      (setq ok smoke-desktop--demo-seen))
    (kill-emacs (if ok 0 1))))

;;; smoke-desktop.el ends here
