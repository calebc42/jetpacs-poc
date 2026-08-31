#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)"
EBP_EL_ROOT="${EBP_EL_DIR:-$REPO_ROOT/../ebp-poc/ebp.el}"
EBP_ORG_ROOT="${EBP_ORG_DIR:-$REPO_ROOT/../ebp-poc/ebp-org}"
TEST_ROOT="$(mktemp -d /tmp/jetpacs-package-test.XXXXXX)"
CHECKOUT="$TEST_ROOT/checkout"
EMACS_DIRECTORY="$TEST_ROOT/emacs.d"
DEPENDENCIES="$TEST_ROOT/dependencies"
trap 'rm -rf "$TEST_ROOT"' EXIT

mkdir -p "$CHECKOUT" "$EMACS_DIRECTORY/elpa" "$DEPENDENCIES"
cp -R "$REPO_ROOT/.elpaignore" "$REPO_ROOT/emacs" "$REPO_ROOT/org" "$CHECKOUT/"
cp "$EBP_EL_ROOT"/lisp/*.el "$DEPENDENCIES/"
cp "$EBP_ORG_ROOT"/lisp/*.el "$DEPENDENCIES/"
# Model a clean source checkout even when the developer tree contains ignored
# bytecode.  This only removes files from TEST_ROOT, which the test owns.
find "$CHECKOUT" -type f -name '*.elc' -delete

# package-vc expects a real checkout even though this test never uses a network.
git -C "$CHECKOUT" init -q
git -C "$CHECKOUT" config user.email package-test@example.invalid
git -C "$CHECKOUT" config user.name "Jetpacs package test"
git -C "$CHECKOUT" add .
git -C "$CHECKOUT" commit -qm initial

JETPACS_PACKAGE_TEST_ROOT="$TEST_ROOT" \
JETPACS_PACKAGE_TEST_CHECKOUT="$CHECKOUT" \
JETPACS_PACKAGE_TEST_EMACS_DIRECTORY="$EMACS_DIRECTORY" \
emacs -Q --batch -L "$DEPENDENCIES" --eval '
(progn
  (setq user-emacs-directory
        (file-name-as-directory (getenv "JETPACS_PACKAGE_TEST_EMACS_DIRECTORY"))
        package-user-dir (expand-file-name "elpa" user-emacs-directory)
        package-quickstart nil
        package-quickstart-file
        (expand-file-name "package-quickstart.el" user-emacs-directory)
        custom-file (expand-file-name "custom.el" user-emacs-directory)
        package-archives nil)
  (require (quote package))
  (require (quote package-vc))
  ;; Keep this regression hermetic: there are no package dependencies beyond
  ;; the required Emacs version, so archive refreshes add no useful coverage.
  (setq package--initialized t
        package-archive-contents (quote ((jetpacs-test-sentinel)))
        package-vc--archive-data-alist (quote ((jetpacs-test-sentinel)))
        package-vc-selected-packages
        (quote ((jetpacs :lisp-dir "emacs"))))
  (let ((inhibit-message t))
    (package-vc-install-from-checkout
     (getenv "JETPACS_PACKAGE_TEST_CHECKOUT") "jetpacs"))
  (require (quote jetpacs))
  ;; Emacs 31 activates a local package-vc checkout in place, while Emacs 30
  ;; may stage it below the package user directory.  Assert against the
  ;; activated Lisp root instead of assuming either package-vc storage layout.
  (let* ((library (locate-library "jetpacs"))
         (lisp-root (file-name-directory library))
         (packaged-apps (expand-file-name "apps/packaged-apps" lisp-root))
         (spike-elc (expand-file-name "spike/jetpacs-spike-rows.elc"
                                      lisp-root)))
    (unless (and (featurep (quote jetpacs))
                 (featurep (quote jetpacs-init))
                 (string-prefix-p user-emacs-directory jetpacs-install-root)
                 (string-suffix-p "/emacs/jetpacs.elc" library)
                 (member packaged-apps load-path)
                 (featurep (quote jetpacs-packaged-apps))
                 (equal (plist-get (car jetpacs-app-store-packaged-apps) :name)
                        "glasspane.el")
                 (not (featurep (quote glasspane)))
                 (not (file-exists-p spike-elc)))
      (error "Jetpacs package installation contract failed: %S"
             (list :jetpacs (featurep (quote jetpacs))
                   :init (featurep (quote jetpacs-init))
                   :install-root jetpacs-install-root
                   :library library
                   :packaged-load-path (member packaged-apps load-path)
                   :manifest (featurep (quote jetpacs-packaged-apps))
                   :catalog jetpacs-app-store-packaged-apps
                   :glasspane-loaded (featurep (quote glasspane))
                   :spike-elc (file-exists-p spike-elc)))))
  (princ "package-vc-install-test: clean checkout installs and requires Jetpacs\n"))'
