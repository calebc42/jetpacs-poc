#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
owner_root=$(pwd)
repositories_root=$(tools/repositories-root.sh)
export JETPACS_REPOSITORIES_ROOT="$repositories_root"
export JETPACS_WORKSPACE="$owner_root"
ebp_el_dir=${EBP_EL_DIR:-"$repositories_root/ebp.el"}
authoring_dir=${JETPACS_AUTHORING_DIR:-"$repositories_root/jetpacs-authoring"}

# Drift check for the committed examples rendering: a fresh harvest must
# equal jetpacs-applet-mcp/catalog/examples.json byte for byte.
emacs -Q --batch --eval '(setq load-prefer-newer t)' \
  -L "$ebp_el_dir/lisp" -L "$owner_root/emacs" \
  -L "$authoring_dir/lisp" \
  -L "$owner_root/emacs/apps/jetpacs-material3" \
  -L "$owner_root/emacs/apps/m3-catalog" \
  -L "$owner_root/jetpacs-components/lisp/jetpacs-components" \
  -L "$owner_root/jetpacs-component-catalog/lisp" \
  -l "$owner_root/jetpacs-applet-mcp/jetpacs-applet-examples.el" \
  --eval '(kill-emacs (if (jetpacs-applet-examples-check) 0 1))'
