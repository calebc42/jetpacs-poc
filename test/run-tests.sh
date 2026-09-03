#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
jetpacs_dir=${JETPACS_DIR:-"$(pwd)/../jetpacs"}
ebp_el_dir=${EBP_EL_DIR:-"$(pwd)/../ebp-poc/ebp.el"}

tools/check-projections.sh

# `load-prefer-newer' so a stale sibling .elc cannot shadow the source under
# test, as every other suite in the workspace already does.
emacs -Q --batch -L "$ebp_el_dir/lisp" -L "$jetpacs_dir/emacs" \
  -L lisp/jetpacs-components \
  --eval '(setq load-prefer-newer t)' \
  -l test/jetpacs-design-test.el \
  -f ert-run-tests-batch-and-exit

compile_dir=$(mktemp -d)
trap 'rm -rf "$compile_dir"' EXIT
for source in lisp/jetpacs-components/*.el; do
  emacs -Q --batch -L "$ebp_el_dir/lisp" -L "$jetpacs_dir/emacs" \
    -L lisp/jetpacs-components \
    --eval "(progn
      (setq load-prefer-newer t byte-compile-error-on-warn t)
      (setq byte-compile-dest-file-function
            (lambda (file)
              (expand-file-name
               (concat (file-name-nondirectory file) \"c\")
               \"$compile_dir\"))))" \
    -f batch-byte-compile "$source"
done
