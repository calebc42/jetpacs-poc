#!/usr/bin/env bash
# Gate ONE catalog component module: byte-compile (warnings are errors)
# and then build/validate every screen it contributes.
#
#   tools/m3-check.sh buttons [more-slugs...]
#
# Compiled output goes to a temp directory, never beside the sources —
# a stale .elc would shadow a sibling module for every other check
# running at the same time.
set -euo pipefail
cd "$(dirname "$0")/.."
jetpacs_root=${JETPACS_ROOT:-"$(pwd)"}
repositories_root=$(tools/repositories-root.sh)
export JETPACS_REPOSITORIES_ROOT="$repositories_root"
ebp_el_root=${EBP_EL_DIR:-"$repositories_root/ebp.el"}
authoring_root=${JETPACS_AUTHORING_DIR:-"$repositories_root/jetpacs-authoring"}

if [ $# -eq 0 ]; then
  echo "usage: tools/m3-check.sh <component-slug> [...]" >&2
  exit 2
fi

dest=$(mktemp -d)
trap 'rm -rf "$dest"' EXIT

for slug in "$@"; do
  file="emacs/apps/m3-catalog/jetpacs-m3-${slug}.el"
  if [ ! -f "$file" ]; then
    echo "m3-check: no module $file" >&2
    exit 2
  fi
  emacs -Q --batch -L "$ebp_el_root/lisp" -L "$jetpacs_root/emacs" \
    -L "$authoring_root/lisp" \
    -L emacs/apps/m3-catalog -L emacs/apps/jetpacs-material3 \
    --eval "(progn
              (setq load-prefer-newer t)
              (setq byte-compile-error-on-warn t)
              (setq byte-compile-dest-file-function
                    (lambda (f)
                      (expand-file-name (concat (file-name-nondirectory f) \"c\")
                                        \"$dest\"))))" \
    -f batch-byte-compile "$file"
done

emacs -Q --batch -L "$ebp_el_root/lisp" -L "$jetpacs_root/emacs" \
  -L "$authoring_root/lisp" \
  -L emacs/apps/m3-catalog -L emacs/apps/jetpacs-material3 \
  --eval '(setq load-prefer-newer t)' \
  -l tools/m3-check.el -f jetpacs-m3-check-batch "$@"
