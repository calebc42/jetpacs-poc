;;; jetpacs-org-save.el --- The synchronous Org save policy -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The one Org write the foundation performs: pre-write transforms,
;; durable save, post-save hooks, and whole-cache invalidation as one
;; operation.  The Org editor installs it as
;; `ebp-org-file-save-function' when it registers, so engine mutations
;; and the engine's deferred save run it too.
;;
;; A leaf on purpose.  The Org reader, render skin, dialogs and table
;; verbs all save through this policy, and the Org editor requires
;; them; when the policy lived in the editor, each of them had to
;; require the editor lazily inside its handlers to dodge the cycle,
;; and whatever happened to load first decided which definitions a
;; test stub replaced.  This file requires only the engine, the floor
;; and the Files hooks it runs.

;;; Code:

(require 'ebp-org)
(require 'jetpacs-surfaces)
(require 'jetpacs-files)                ; the before/after save hooks

(defun jetpacs-org-save--run-prewrite (path buffer)
  "Run the mandatory Files pre-write chain for PATH and BUFFER.
If a transform signals, restore BUFFER's text, restriction, point, and
modified state to their exact pre-transform values before re-signaling.
The caller has not attempted disk I/O yet, so this is an unambiguous
pre-durability rollback."
  (with-current-buffer buffer
    (let* ((original-beg (point-min))
           (original-end (point-max))
           (original-point (point))
           (original-modified (buffer-modified-p))
           (original-full
            (save-restriction
              (widen)
              (buffer-substring-no-properties (point-min) (point-max)))))
      (condition-case err
          (run-hook-with-args 'jetpacs-files-before-buffer-save-hook
                              path buffer)
        (error
         (let ((inhibit-read-only t)
               (buffer-undo-list t))
           (widen)
           (delete-region (point-min) (point-max))
           (insert original-full)
           (narrow-to-region original-beg original-end)
           (goto-char (min (max original-point (point-min)) (point-max)))
           (set-buffer-modified-p original-modified))
         (signal (car err) (cdr err)))))))

;;;###autoload
(defun jetpacs-org-save-policy (&optional buffer)
  "Synchronously and safely save BUFFER, defaulting to the current buffer.
Run every Files pre-write transform before `save-buffer', run isolated
Files after-save hooks after the write, and invalidate the whole Org
projection cache.  A pre-write failure restores the buffer and aborts
the disk write; optional post-durability work is isolated.
Inside a builder extent it signals `jetpacs-builder-side-effect': a
render never writes."
  (jetpacs--builder-guard 'jetpacs-org-save-policy)
  (let ((buffer (or buffer (current-buffer))))
    (unless (buffer-live-p buffer)
      (user-error "Org save target is not a live buffer"))
    (with-current-buffer buffer
      (let* ((file (or buffer-file-name
                       (user-error "Buffer is not visiting a file")))
             (true (file-truename file))
             (save-silently t))
        (jetpacs-org-save--run-prewrite true buffer)
        (save-buffer)
        ;; Capture the file before optional callbacks: the durable save
        ;; is complete, and neither post-save hooks nor cache cleanup may
        ;; revise that verdict.
        (unwind-protect
            (jetpacs-run-isolated 'jetpacs-files-after-save-hook true)
          (ebp-org-cache-invalidate)))))
  t)

(provide 'jetpacs-org-save)
;;; jetpacs-org-save.el ends here
