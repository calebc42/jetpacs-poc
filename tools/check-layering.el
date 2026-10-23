;;; check-layering.el --- Foundation require-closure guard -*- lexical-binding: t; -*-

;;; Commentary:

;; Run by tools/check-layering.sh in an `emacs -Q --batch' with no added
;; load path.  Every `(require 'X ...)' in LAYERING_FILES whose X is not
;; prefixed `jetpacs' or `ebp' must name a library this Emacs can locate
;; (a built-in), unless the require is soft (NOERROR non-nil) and X is
;; listed in LAYERING_ALLOWLIST (one name per line, `#' comments).
;; Exits 1 after reporting every violation.

;;; Code:

(defun check-layering--allowlist (file)
  "The feature names allowlisted in FILE, ignoring blanks and comments."
  (when (and file (file-readable-p file))
    (with-temp-buffer
      (insert-file-contents file)
      (seq-remove (lambda (line) (string-prefix-p "#" line))
                  (split-string (buffer-string) "\n" t "[ \t]+")))))

(defun check-layering--lambda-list-default-p (start)
  "Non-nil when the form at START is a default in a lambda list.
`(cl-defun f (x &optional (require \\='readable)) ...)' reads like a
require but binds a parameter named `require'."
  (let ((open (nth 1 (save-excursion (syntax-ppss start)))))
    (and open
         (save-excursion
           (goto-char open)
           (re-search-forward "&\\(optional\\|key\\|rest\\|aux\\)\\_>"
                              start t)))))

(defun check-layering--violations (file allowed)
  "Report and return the number of disallowed requires in FILE.
ALLOWED names third-party features a soft require may load."
  (let ((count 0))
    (with-temp-buffer
      (insert-file-contents file)
      (emacs-lisp-mode)
      (goto-char (point-min))
      (while (re-search-forward "(require \\(?:(quote \\|'\\)" nil t)
        (let ((start (match-beginning 0)))
          ;; `syntax-ppss' moves point; the search position must survive.
          (unless (or (nth 8 (save-excursion (syntax-ppss start)))
                      (check-layering--lambda-list-default-p start))
            (let* ((form (save-excursion
                           (goto-char start)
                           (condition-case nil (read (current-buffer))
                             (error nil))))
                   (feature (and (eq (car-safe (nth 1 form)) 'quote)
                                 (cadr (nth 1 form))))
                   (name (and feature (symbolp feature)
                              (symbol-name feature)))
                   (soft (nth 3 form)))
              (when (and name
                         (not (string-prefix-p "jetpacs" name))
                         (not (string-prefix-p "ebp" name))
                         (not (locate-library name))
                         (not (and soft (member name allowed))))
                (setq count (1+ count))
                (message "layering guard: %s:%d requires %s, which is not Jetpacs, EBP or built-in%s"
                         file (line-number-at-pos start) name
                         (if soft
                             " (soft; not in tools/third-party-allowlist.txt)"
                           ""))))))))
    count))

(let ((allowed (check-layering--allowlist (getenv "LAYERING_ALLOWLIST")))
      (failures 0))
  (dolist (file (split-string (or (getenv "LAYERING_FILES") "") "\n" t))
    (setq failures (+ failures (check-layering--violations file allowed))))
  (kill-emacs (if (zerop failures) 0 1)))

;;; check-layering.el ends here
