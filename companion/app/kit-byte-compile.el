;;; kit-byte-compile.el --- Byte-compile the staged Jetpacs kit  -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; Run by the Companion's Gradle build for the bundled flavor
;; (docs/PLAN-bundled-emacs.md, E6): one batch Emacs of the engine's major
;; version compiles every `.el' of the staged onboarding payload and writes
;; the `.elc' beside its source path under a second generated asset root,
;; which AGP merges next to the sources.  A file that cannot be compiled on
;; the host (an app that requires a library the kit does not carry) is
;; reported and left to load from source on the device; only a build-time
;; error of this script itself fails the build.
;;
;;   emacs -Q --batch -l kit-byte-compile.el -- PAYLOAD OUTPUT

;;; Code:

(require 'bytecomp)

(let* ((args (cdr (member "--" command-line-args)))
       (payload (file-name-as-directory (expand-file-name (nth 0 args))))
       (output (file-name-as-directory (expand-file-name (nth 1 args))))
       (emacs-dir (expand-file-name "emacs/" payload))
       (apps-dir (expand-file-name "apps/" emacs-dir))
       (compiled 0) (skipped nil))
  (unless (and (nth 0 args) (nth 1 args) (file-directory-p emacs-dir))
    (error "kit-byte-compile: usage PAYLOAD OUTPUT (payload/emacs missing)"))
  ;; The same load-path the device composition root builds: the kit, then
  ;; every app directory.  Org and the rest come from this Emacs's own lisp,
  ;; which is the engine's version.
  (push emacs-dir load-path)
  (when (file-directory-p apps-dir)
    (dolist (dir (directory-files apps-dir t directory-files-no-dot-files-regexp))
      (when (file-directory-p dir) (push dir load-path))))
  (setq byte-compile-dest-file-function
        (lambda (source)
          (let ((relative (file-relative-name source payload)))
            (concat output (file-name-sans-extension relative) ".elc")))
        byte-compile-verbose nil
        byte-compile-warnings nil)
  (dolist (source (directory-files-recursively emacs-dir "\\.el\\'"))
    (let ((dest (funcall byte-compile-dest-file-function source)))
      (make-directory (file-name-directory dest) t)
      (condition-case err
          (if (byte-compile-file source)
              (setq compiled (1+ compiled))
            (push (file-relative-name source payload) skipped))
        (error (push (format "%s: %s" (file-relative-name source payload)
                             (error-message-string err))
                     skipped)
               (ignore-errors (delete-file dest))))))
  (message "kit-byte-compile: %d files compiled, %d left to source%s"
           compiled (length skipped)
           (if skipped (concat ":\n  " (mapconcat #'identity (nreverse skipped) "\n  ")) "")))

;;; kit-byte-compile.el ends here
