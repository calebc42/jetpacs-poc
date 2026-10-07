#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
owner_root=$(pwd)
repositories_root=$(tools/repositories-root.sh)
export JETPACS_REPOSITORIES_ROOT="$repositories_root"
export JETPACS_WORKSPACE="$owner_root"

# The agent-facing catalog export (jetpacs-applet-mcp/jetpacs-applet-catalog.el):
# regenerate jetpacs-applet-mcp/catalog/{jetpacs-catalog.json,CATALOG.md} and
# skills/jetpacs-applet/SKILL.md from contract.json, the renderer-extension
# manifests and the platform's builders. Static: nothing is evaluated.
emacs -Q --batch \
  -l "$owner_root/jetpacs-applet-mcp/jetpacs-applet-tooling.el" \
  -l "$owner_root/jetpacs-applet-mcp/jetpacs-applet-catalog.el" \
  --eval '(progn (dolist (path (jetpacs-applet-catalog-write)) (message "wrote %s" path)) (kill-emacs 0))'
