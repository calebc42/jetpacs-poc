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
        (let ((entry (jetpacs-app-store--packaged-entry "bloom.el")))
          (should entry)
          (should (eq (plist-get entry :feature) 'bloom))
          (should (equal (plist-get entry :app-id) "bloom")))
        (let ((entry (jetpacs-app-store--packaged-entry "orgzly.el")))
          (should entry)
          (should (eq (plist-get entry :feature) 'orgzly))
          (should (equal (plist-get entry :app-id) "orgzly")))
        (let ((entry (jetpacs-app-store--packaged-entry "harp.el")))
          (should entry)
          (should (eq (plist-get entry :feature) 'harp))
          (should (equal (plist-get entry :app-id) "harp")))
        (should-not (featurep 'harp))
        (should-not (assoc "harp" jetpacs-apps--registry))
        (should-not (featurep 'orgzly))
        (should-not (assoc "orgzly" jetpacs-apps--registry))
        (should-not (featurep 'glasspane))
        (should-not (featurep 'bloom))
        (should-not (assoc "glasspane" jetpacs-apps--registry))
        (should-not (assoc "bloom" jetpacs-apps--registry))
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
  "The packaged dialer negotiates the target used by Bloom's widget roots."
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

(ert-deftest jetpacs-entry-start-binds-the-emacs-unix-socket-when-selected ()
  "Under `emacs-unix' Emacs binds the socket (SPEC 5.6): path, nil port, :transport."
  (let (connect-args)
    (cl-letf (((symbol-function 'jetpacs-connect)
               (lambda (&rest args)
                 (setq connect-args args)
                 'stub-client))
              ((symbol-function 'ebp-decode-pairing-token)
               (lambda (_token) "decoded")))
      (let ((jetpacs-transport 'emacs-unix)
            (jetpacs-socket "/tmp/x/ebp.sock")
            (jetpacs-pairing-id "pairing")
            (jetpacs-pairing-token "token"))
        (should (eq (jetpacs--start-1) 'stub-client)))
      (should (equal "/tmp/x/ebp.sock" (nth 0 connect-args)))
      (should (null (nth 1 connect-args)))
      (should (eq 'emacs-unix (plist-get (nthcdr 2 connect-args) :transport)))
      (should (member "surfaces.widget"
                      (plist-get (nthcdr 2 connect-args) :wants)))
      ;; A nil socket is ebp.el's own default path.
      (let ((jetpacs-transport 'emacs-unix)
            (jetpacs-socket nil)
            (jetpacs-pairing-id "pairing")
            (jetpacs-pairing-token "token"))
        (jetpacs--start-1))
      (should (equal (expand-file-name (ebp-unix-default-path))
                     (nth 0 connect-args)))
      ;; The default profile dials loopback-tcp and names no transport.
      (let ((jetpacs-pairing-id "pairing")
            (jetpacs-pairing-token "token"))
        (jetpacs--start-1))
      (should (equal (list jetpacs-host jetpacs-port)
                     (list (nth 0 connect-args) (nth 1 connect-args))))
      (should-not (plist-member (nthcdr 2 connect-args) :transport))
      (let ((jetpacs-transport 'bogus)
            (jetpacs-pairing-id "pairing")
            (jetpacs-pairing-token "token"))
        (should-error (jetpacs--start-1))))))

(ert-deftest jetpacs-entry-start-dials-the-loopback-unix-socket-when-selected ()
  "Under `loopback-unix' Emacs dials the Companion's socket (SPEC 5.3):
path, nil port, :transport, and no default path to fall back on."
  (let (connect-args)
    (cl-letf (((symbol-function 'jetpacs-connect)
               (lambda (&rest args)
                 (setq connect-args args)
                 'stub-client))
              ((symbol-function 'ebp-decode-pairing-token)
               (lambda (_token) "decoded")))
      (let ((jetpacs-transport 'loopback-unix)
            (jetpacs-socket "/data/user/0/app/files/ebp/companion.sock")
            (jetpacs-pairing-id "pairing")
            (jetpacs-pairing-token "token"))
        (should (eq (jetpacs--start-1) 'stub-client)))
      (should (equal "/data/user/0/app/files/ebp/companion.sock"
                     (nth 0 connect-args)))
      (should (null (nth 1 connect-args)))
      (should (eq 'loopback-unix (plist-get (nthcdr 2 connect-args) :transport)))
      (let ((jetpacs-transport 'loopback-unix)
            (jetpacs-socket nil)
            (jetpacs-pairing-id "pairing")
            (jetpacs-pairing-token "token"))
        (should-error (jetpacs--start-1))))))

(provide 'jetpacs-entry-test)
;;; jetpacs-entry-test.el ends here
