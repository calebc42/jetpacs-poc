#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
owner_root=$(pwd)
repositories_root=$(tools/repositories-root.sh)
export JETPACS_REPOSITORIES_ROOT="$repositories_root"
export JETPACS_WORKSPACE="$owner_root"
ebp_el_dir=${EBP_EL_DIR:-"$repositories_root/ebp.el"}
authoring_dir=${JETPACS_AUTHORING_DIR:-"$repositories_root/jetpacs-authoring"}

# The catalog's examples (jetpacs-applet-mcp/jetpacs-applet-examples.el):
# regenerate jetpacs-applet-mcp/catalog/examples.json from the component
# catalog's canonical documents and the Material 3 catalog's samples. This
# step loads the platform and builds real nodes; the static catalog
# generator (tools/gen-jetpacs-catalog.sh) then joins the result without
# evaluating anything. Run this one first.
emacs -Q --batch --eval '(setq load-prefer-newer t)' \
  -L "$ebp_el_dir/lisp" -L "$owner_root/emacs" \
  -L "$authoring_dir/lisp" \
  -L "$owner_root/emacs/apps/jetpacs-material3" \
  -L "$owner_root/emacs/apps/m3-catalog" \
  -L "$owner_root/jetpacs-components/lisp/jetpacs-components" \
  -L "$owner_root/jetpacs-component-catalog/lisp" \
  -l "$owner_root/jetpacs-applet-mcp/jetpacs-applet-examples.el" \
  --eval '(progn (message "wrote %s" (jetpacs-applet-examples-write)) (kill-emacs 0))'
