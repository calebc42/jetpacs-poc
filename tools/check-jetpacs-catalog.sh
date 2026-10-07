#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
owner_root=$(pwd)
repositories_root=$(tools/repositories-root.sh)
export JETPACS_REPOSITORIES_ROOT="$repositories_root"
export JETPACS_WORKSPACE="$owner_root"

# Drift check for the committed catalog renderings: a fresh rendering must
# equal each file byte for byte, as the vocabulary projections are checked.
emacs -Q --batch \
  -l "$owner_root/jetpacs-applet-mcp/jetpacs-applet-tooling.el" \
  -l "$owner_root/jetpacs-applet-mcp/jetpacs-applet-catalog.el" \
  --eval '(kill-emacs (if (jetpacs-applet-catalog-check) 0 1))'
