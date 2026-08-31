#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
jetpacs_dir=${JETPACS_DIR:-"$(pwd)/../jetpacs"}
ebp_el_dir=${EBP_EL_DIR:-"$(pwd)/../ebp-poc/ebp.el"}
authoring_dir=${JETPACS_AUTHORING_DIR:-"$(pwd)/../jetpacs-authoring"}
components_dir=${JETPACS_COMPONENTS_DIR:-"$(pwd)/../jetpacs-components"}

for suite in jetpacs-component-authoring-test \
             jetpacs-component-catalog-actions-test \
             jetpacs-component-catalog-test; do
  emacs -Q --batch \
    -L "$ebp_el_dir/lisp" -L "$jetpacs_dir/emacs" \
    -L "$authoring_dir/lisp" -L "$components_dir/lisp/jetpacs-components" \
    -L lisp -L test --eval '(setq load-prefer-newer t)' \
    -l "test/$suite.el" -f ert-run-tests-batch-and-exit
done
