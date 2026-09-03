;;; jetpacs-entry-test.el --- Public package entry point -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)

(defconst jetpacs-entry-test--temporary-home
  (make-temp-file "jetpacs-entry-test-" t))
(defconst jetpacs-entry-test--user-emacs-directory
  (file-name-as-directory
   (expand-file-name ".emacs.d" jetpacs-entry-test--temporary-home)))
(defconst jetpacs-entry-test--vault
  (file-name-as-directory
   (expand-file-name "my-vault" jetpacs-entry-test--temporary-home)))
(defconst jetpacs-entry-test--default-directory
  (file-name-as-directory jetpacs-entry-test--temporary-home))

(defun jetpacs-entry-test--actions (_surface)
  'custom-actions)

(defun jetpacs-entry-test--dock (_surface)
  'custom-dock)

(defun jetpacs-entry-test--items (_surface)
  'custom-items)

;; This is the shape of an existing user's init immediately before its final
;; `(require 'jetpacs)'.  Package defaults must not replace any of it.
(setq user-emacs-directory jetpacs-entry-test--user-emacs-directory
      default-directory jetpacs-entry-test--default-directory
      jetpacs-vault-directory jetpacs-entry-test--vault
      org-directory (expand-file-name "existing-org" jetpacs-entry-test--vault)
      jetpacs-theme-mode 'dark
      jetpacs-clip-auto-refresh t
      jetpacs-apps-core-global-actions #'jetpacs-entry-test--actions
      jetpacs-apps-core-global-items #'jetpacs-entry-test--items
      jetpacs-apps-core-dock-items #'jetpacs-entry-test--dock)

(make-directory (expand-file-name "jetpacs" user-emacs-directory) t)
(with-temp-file (expand-file-name "jetpacs/user.el" user-emacs-directory)
  (insert "(setq jetpacs-entry-test--user-file-loaded t)\n"))

(require 'jetpacs)

(ert-deftest jetpacs-entry-require-preserves-existing-init-and-separates-state ()
  "The public entry loads anywhere on `load-path' without owning user config."
  (unwind-protect
      (progn
        (should (featurep 'jetpacs))
        (should (featurep 'jetpacs-init))
        ;; Downstream apps load from user configuration or their own package;
        ;; requiring Jetpacs may discover inert APK metadata for the Apps
        ;; screen, but must never load or activate the app itself.
        (should (featurep 'jetpacs-packaged-apps))
        (let ((entry (jetpacs-app-store--packaged-entry "glasspane.el")))
          (should entry)
          (should (eq (plist-get entry :feature) 'glasspane))
          (should (equal (plist-get entry :app-id) "glasspane")))
        (let ((entry (jetpacs-app-store--packaged-entry "grove.el")))
          (should entry)
          (should (eq (plist-get entry :feature) 'grove))
          (should (equal (plist-get entry :app-id) "grove")))
        (should-not (featurep 'glasspane))
        (should-not (featurep 'grove))
        (should-not (assoc "glasspane" jetpacs-apps--registry))
        (should-not (assoc "grove" jetpacs-apps--registry))
        (should (equal jetpacs-install-root
                       (expand-file-name "jetpacs/" user-emacs-directory)))
        (should (equal jetpacs-vault-directory jetpacs-entry-test--vault))
        (should (equal org-directory
                       (expand-file-name "existing-org"
                                         jetpacs-entry-test--vault)))
        (should (equal ebp-org-roots (jetpacs-files-effective-roots)))
        ;; Files may open Org documents anywhere in the selected Vault, not
        ;; only beneath `org-directory'.  Their structured actions must use
        ;; that same policy or the rendered menu becomes a dead affordance.
        (let ((outside-org (expand-file-name "vault-document.org"
                                             jetpacs-entry-test--vault)))
          (make-directory jetpacs-entry-test--vault t)
          (with-temp-file outside-org (insert "* Vault document\n"))
          (should (equal (ebp-org-file-allowed-p outside-org)
                         (file-truename outside-org))))
        (should (eq jetpacs-theme-mode 'dark))
        (should jetpacs-clip-auto-refresh)
        (should (eq jetpacs-apps-core-global-actions
                    #'jetpacs-entry-test--actions))
        ;; The S10 data seed is a defvar for the same reason: a user's
        ;; own globals survive the init's M-x default.
        (should (eq jetpacs-apps-core-global-items
                    #'jetpacs-entry-test--items))
        (should (eq jetpacs-apps-core-dock-items
                    #'jetpacs-entry-test--dock))
        (should (equal default-directory
                       jetpacs-entry-test--default-directory))
        (should jetpacs-entry-test--user-file-loaded))
    (delete-directory jetpacs-entry-test--temporary-home t)))

(ert-deftest jetpacs-entry-ready-landing-prefers-explicit-app-and-falls-back ()
  "The composition callback honors a seed without learning an app's name."
  (let ((opens 0) (hubs 0))
    (cl-letf (((symbol-function 'jetpacs-apps-open-seeded)
               (lambda () (cl-incf opens) t))
              ((symbol-function 'jetpacs-hub)
               (lambda () (cl-incf hubs))))
      (jetpacs-ready-landing nil)
      (should (= opens 1))
      (should (zerop hubs)))
    (setq opens 0 hubs 0)
    (cl-letf (((symbol-function 'jetpacs-apps-open-seeded)
               (lambda () (cl-incf opens) nil))
              ((symbol-function 'jetpacs-hub)
               (lambda () (cl-incf hubs))))
      (jetpacs-ready-landing nil)
      (should (= opens 1))
      (should (= hubs 1)))
    ;; A broken downstream landing is isolated and takes the same fallback.
    (setq hubs 0)
    (cl-letf (((symbol-function 'jetpacs-apps-open-seeded)
               (lambda () (error "broken seed")))
              ((symbol-function 'jetpacs-hub)
               (lambda () (cl-incf hubs))))
      (jetpacs-ready-landing nil)
      (should (= hubs 1)))))

(ert-deftest jetpacs-entry-start-requests-widget-surfaces ()
  "The packaged dialer negotiates the target used by Grove's widget roots."
  (let (connect-args)
    (cl-letf (((symbol-function 'jetpacs-connect)
               (lambda (&rest args)
                 (setq connect-args args)
                 'stub-client))
              ((symbol-function 'ebp-decode-pairing-token)
               (lambda (_token) "decoded")))
      (let ((jetpacs-pairing-id "pairing")
            (jetpacs-pairing-token "token"))
        (should (eq (jetpacs--start-1) 'stub-client))))
    (should (member "surfaces.widget"
                    (plist-get (nthcdr 2 connect-args) :wants)))))

(provide 'jetpacs-entry-test)
;;; jetpacs-entry-test.el ends here
