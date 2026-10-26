;;; check-jetpacs-delineation.el --- One foundation file, loaded alone -*- lexical-binding: t; -*-

;;; Commentary:

;; Run by tools/check-jetpacs-delineation.sh once per emacs/jetpacs-*.el,
;; each in a fresh `emacs -Q --batch' whose load path holds only EBP and
;; Jetpacs.  Loads DELINEATION_FEATURE and fails when any downstream app
;; symbol (glasspane-, bloom-, grove-, orgzly-, harp-) is defined: a function,
;; variable, face, error condition, custom group, or loaded feature.  The
;; predicate is the ebp.el guard's in test/run-tests.sh, aimed one layer
;; down.

;;; Code:

(let* ((feature (intern (getenv "DELINEATION_FEATURE")))
       (prefix "\\`\\(glasspane\\|bloom\\|grove\\|orgzly\\|harp\\)\\(-\\|\\'\\)")
       offenders)
  (require feature)
  (mapatoms
   (lambda (sym)
     (when (and (string-match-p prefix (symbol-name sym))
                (or (fboundp sym) (boundp sym) (facep sym)
                    (get sym 'error-conditions)
                    (get sym 'group-documentation)
                    (get sym 'custom-group)
                    (memq sym features)))
       (push sym offenders))))
  (when offenders
    (message "delineation guard: downstream app symbols after loading %s.el: %S"
             feature offenders)
    (kill-emacs 1)))

;;; check-jetpacs-delineation.el ends here
