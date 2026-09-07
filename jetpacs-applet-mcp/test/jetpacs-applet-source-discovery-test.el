;;; jetpacs-applet-source-discovery-test.el --- Bounded source walks -*- lexical-binding: t; -*-

(require 'ert)
(load (expand-file-name "../jetpacs-applet-tooling.el"
                        (file-name-directory (or load-file-name buffer-file-name)))
      nil t)

(ert-deftest jetpacs-applet-source-discovery-bounds-and-exclusions ()
  (let* ((workspace (make-temp-file "jetpacs-source-walk-" t))
         (outside (make-temp-file "jetpacs-source-outside-" t))
         (jetpacs-applet-tooling-workspace-root workspace)
         (jetpacs-applet-tooling-repositories-root workspace)
         (jetpacs-applet-tooling-source-directories '("emacs"))
         (jetpacs-applet-tooling-max-source-files 10)
         (jetpacs-applet-tooling-max-source-entries 100))
    (unwind-protect
        (progn
          (dolist (directory '("emacs" "emacs/build" "emacs/.git"))
            (make-directory (expand-file-name directory workspace) t))
          (dolist (file '("emacs/z.el" "emacs/a.el" "emacs/build/generated.el"
                          "emacs/.git/hidden.el"))
            (with-temp-file (expand-file-name file workspace) (insert "; fixture\n")))
          (with-temp-file (expand-file-name "outside.el" outside) (insert "; outside\n"))
          (make-symbolic-link outside (expand-file-name "emacs/escape" workspace))
          (make-symbolic-link workspace (expand-file-name "emacs/cycle" workspace))
          (should (equal (mapcar #'file-name-nondirectory
                                 (jetpacs-applet-tooling--source-files))
                         '("a.el" "z.el")))
          (let ((jetpacs-applet-tooling-max-source-files 1))
            (should-error (jetpacs-applet-tooling--source-files)))
          (let ((jetpacs-applet-tooling-max-source-entries 1))
            (should-error (jetpacs-applet-tooling--source-files)))
          (let ((jetpacs-applet-tooling-source-directories (list outside)))
            (should-error (jetpacs-applet-tooling--source-files))))
      (delete-directory workspace t)
      (delete-directory outside t))))

(ert-deftest jetpacs-applet-source-discovery-rejects-checkout-overlap ()
  (let* ((collection (make-temp-file "jetpacs-checkout-overlap-" t))
         (workspace (expand-file-name "jetpacs-poc" collection))
         (jetpacs-applet-tooling-workspace-root workspace)
         (jetpacs-applet-tooling-repositories-root collection)
         (jetpacs-applet-tooling-app-source-roots nil))
    (unwind-protect
        (progn
          (make-directory workspace)
          (dolist (name jetpacs-applet-tooling-app-source-names)
            (make-directory (expand-file-name name collection)))
          (should (= (length jetpacs-applet-tooling-app-source-names)
                     (length (jetpacs-applet-tooling-resolved-app-source-roots))))
          (let ((alias (expand-file-name "glasspane" collection)))
            (delete-directory alias)
            (dolist (target (list workspace (expand-file-name "ebp.el" collection)))
              (make-symbolic-link target alias)
              (should-error (jetpacs-applet-tooling-resolved-app-source-roots))
              (delete-file alias))))
      (delete-directory collection t))))

(ert-deftest jetpacs-applet-consolidated-modules-exclude-stale-siblings ()
  (let* ((collection (make-temp-file "jetpacs-consolidated-" t))
         (workspace (expand-file-name "jetpacs-poc" collection))
         (jetpacs-applet-tooling-workspace-root workspace)
         (jetpacs-applet-tooling-repositories-root collection)
         (jetpacs-applet-tooling-app-source-roots nil))
    (unwind-protect
        (progn
          (make-directory workspace)
          (dolist (name '("jetpacs-components" "jetpacs-automations"
                          "jetpacs-component-catalog"))
            (let ((local (expand-file-name (concat name "/lisp") workspace))
                  (stale (expand-file-name name collection)))
              (make-directory local t)
              (make-directory stale)
              (with-temp-file (expand-file-name "live.el" local)
                (insert "(provide 'live)"))
              (with-temp-file (expand-file-name "stale.el" stale)
                (insert "(provide 'stale)"))
              (make-symbolic-link stale (expand-file-name "escape" local))))
          (should (equal (mapcar #'jetpacs-applet-tooling--relative
                                 (jetpacs-applet-tooling--source-files))
                         '("jetpacs-automations/lisp/live.el"
                           "jetpacs-component-catalog/lisp/live.el"
                           "jetpacs-components/lisp/live.el")))
          (should-not (jetpacs-applet-tooling-resolved-app-source-roots)))
      (delete-directory collection t))))

;;; jetpacs-applet-source-discovery-test.el ends here
