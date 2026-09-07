#!/bin/sh
set -eu

cd "$(dirname "$0")/../.."
jetpacs_dir=${JETPACS_DIR:-"$(pwd)"}
repositories_root=$(tools/repositories-root.sh)
export JETPACS_REPOSITORIES_ROOT="$repositories_root"
ebp_el_dir=${EBP_EL_DIR:-"$repositories_root/ebp.el"}
authoring_dir=${JETPACS_AUTHORING_DIR:-"$repositories_root/jetpacs-authoring"}
components_dir=${JETPACS_COMPONENTS_DIR:-"$jetpacs_dir/jetpacs-components"}

tools/check-material3-projections.sh

for suite in jetpacs-m3-catalog-test jetpacs-m3-repl-test; do
  emacs -Q --batch \
    -L "$ebp_el_dir/lisp" -L "$jetpacs_dir/emacs" \
    -L "$authoring_dir/lisp" -L "$components_dir/lisp/jetpacs-components" \
    -L emacs/apps/m3-catalog \
    -L emacs/apps/jetpacs-material3 -L emacs -L test/material3 \
    --eval '(setq load-prefer-newer t)' \
    -l "test/material3/$suite.el" -f ert-run-tests-batch-and-exit
done
