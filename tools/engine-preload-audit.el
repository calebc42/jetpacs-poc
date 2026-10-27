;;; engine-preload-audit.el --- Audit the bundled engine's dump preload  -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; The bundled flavor's engine dumps the kit's libraries on its first start
;; (docs/PLAN-bundled-emacs.md, E6): site-init.el requires the features of
;; `EngineFiles.PRELOAD' interactively, before the composition root.  A
;; library whose load writes files or derives paths from variables the root
;; seeds later (`org-directory', the Files roots) bakes the loadup-time values
;; into the dump.  This audit repeats that load on the host, interactively and
;; under a scratch HOME, then fails when
;;
;;   - the load created anything in HOME other than .emacs.d;
;;   - a variable other than `org-directory' itself names ~/org;
;;   - a variable jetpacs-init.el seeds with a top-level defvar is still bound
;;     after site-init's unbind step, which would turn the seed into a no-op.
;;
;; Run it after any change to the list, with a host Emacs of the engine's
;; major version and the staged kit (the bundled build stages it):
;;
;;   (cd companion && ./gradlew :app:stageBundledDebugOnboardingAssets)
;;   emacs -Q --batch -l tools/engine-preload-audit.el

;;; Code:

(require 'seq)

(let* ((repo (file-name-directory
              (directory-file-name
               (file-name-directory (or load-file-name buffer-file-name)))))
       (kit (expand-file-name
             "companion/app/build/generated/onboardingAssets/bundledDebug/jetpacs-onboarding/payload/emacs"
             repo))
       (engine-files (expand-file-name
                      "companion/app/src/bundled/kotlin/com/calebc42/jetpacs/companion/engine/EngineFiles.kt"
                      repo))
       (root (expand-file-name "emacs/jetpacs-init.el" repo))
       (home (file-name-as-directory (make-temp-file "engine-preload-audit" t)))
       (failures 0)
       features)
  (unless (file-directory-p kit)
    (message "engine-preload-audit: no staged kit at %s; run :app:stageBundledDebugOnboardingAssets" kit)
    (kill-emacs 2))
  ;; The list, from its one source of truth.
  (with-temp-buffer
    (insert-file-contents engine-files)
    (goto-char (point-min))
    (re-search-forward "val PRELOAD: List<String> = listOf(")
    (let ((end (save-excursion (re-search-forward "^    )$"))))
      (while (re-search-forward "\"\\([a-z0-9-]+\\)\"" end t)
        (push (intern (match-string 1)) features))))
  (setq features (nreverse features))
  ;; The device's loadup: HOME fixed, user-emacs-directory as site-init sets it.
  (setenv "HOME" home)
  (setq user-emacs-directory (expand-file-name ".emacs.d/" home))
  (add-to-list 'load-path kit)
  (dolist (dir (directory-files (expand-file-name "apps" kit) t directory-files-no-dot-files-regexp))
    (when (file-directory-p dir) (add-to-list 'load-path dir t)))
  (let ((noninteractive nil))
    (dolist (feature features)
      (condition-case err
          (require feature)
        (error (setq failures (1+ failures))
               (message "FAIL %s does not preload: %S" feature err)))))
  ;; 1. Files the load created.
  (dolist (entry (directory-files home nil directory-files-no-dot-files-regexp))
    (unless (equal entry ".emacs.d")
      (setq failures (1+ failures))
      (message "FAIL the preload created ~/%s" entry)))
  ;; 2. Paths derived from the loadup-time org-directory.
  (let ((needles (list (expand-file-name "org" home) "~/org")))
    (mapatoms
     (lambda (symbol)
       (when (and (boundp symbol) (not (keywordp symbol)) (not (eq symbol 'org-directory)))
         (let ((printed (ignore-errors (prin1-to-string (default-value symbol)))))
           (when (and printed (< (length printed) 4000)
                      (seq-some (lambda (needle) (string-search needle printed)) needles))
             (setq failures (1+ failures))
             (message "FAIL %s = %s names the loadup-time ~/org" symbol
                      (truncate-string-to-width printed 100))))))))
  ;; 3. site-init's unbind step, then the root's defvar seeds must be unbound.
  (let ((seeds nil))
    (with-temp-buffer
      (insert-file-contents root)
      (goto-char (point-min))
      (condition-case nil
          (while t
            (let ((form (read (current-buffer))))
              (when (and (eq (car-safe form) 'defvar) (cddr form))
                (push (cadr form) seeds))))
        (end-of-file nil)))
    (dolist (symbol seeds) (when (boundp symbol) (makunbound symbol)))
    (dolist (symbol seeds)
      (when (boundp symbol)
        (setq failures (1+ failures))
        (message "FAIL root seed %s stays bound after the unbind" symbol))))
  (message "engine-preload-audit: %d features, %d failure%s"
           (length features) failures (if (= failures 1) "" "s"))
  (kill-emacs (if (zerop failures) 0 1)))

;;; engine-preload-audit.el ends here
