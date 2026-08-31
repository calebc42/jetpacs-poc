#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
jetpacs_dir=${JETPACS_DIR:-"$(pwd)/../jetpacs"}
ebp_el_dir=${EBP_EL_DIR:-"$(pwd)/../ebp-poc/ebp.el"}
authoring_dir=${JETPACS_AUTHORING_DIR:-"$(pwd)/../jetpacs-authoring"}
for required in "$jetpacs_dir/emacs/jetpacs-widgets.el" \
                "$ebp_el_dir/lisp/ebp.el" \
                "$authoring_dir/lisp/jetpacs-authoring.el"; do
  test -r "$required" || { echo "missing dependency: $required" >&2; exit 2; }
done

for suite in jetpacs-automation-model-test \
             jetpacs-automation-runtime-test \
             jetpacs-automations-test; do
  emacs -Q --batch \
    -L "$ebp_el_dir/lisp" -L "$jetpacs_dir/emacs" \
    -L "$authoring_dir/lisp" -L lisp -L test \
    --eval '(setq load-prefer-newer t)' \
    -l "test/$suite.el" -f ert-run-tests-batch-and-exit
done
