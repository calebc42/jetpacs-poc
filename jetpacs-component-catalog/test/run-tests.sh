#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
jetpacs_dir=${JETPACS_DIR:-${JETPACS_ROOT:-"$(CDPATH= cd -- .. && pwd)"}}
repositories_root=$("$jetpacs_dir/tools/repositories-root.sh")
ebp_el_dir=${EBP_EL_DIR:-"$repositories_root/ebp.el"}
authoring_dir=${JETPACS_AUTHORING_DIR:-"$repositories_root/jetpacs-authoring"}
components_dir=${JETPACS_COMPONENTS_DIR:-"$jetpacs_dir/jetpacs-components"}

for suite in jetpacs-component-authoring-test \
             jetpacs-component-catalog-actions-test \
             jetpacs-component-catalog-test \
             jetpacs-design-lab-test; do
  emacs -Q --batch \
    -L "$ebp_el_dir/lisp" -L "$jetpacs_dir/emacs" \
    -L "$authoring_dir/lisp" -L "$components_dir/lisp/jetpacs-components" \
    -L lisp -L test --eval '(setq load-prefer-newer t)' \
    -l "test/$suite.el" -f ert-run-tests-batch-and-exit
done
