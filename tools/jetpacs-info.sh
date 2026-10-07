#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
owner_root=$(pwd)
repositories_root=$(tools/repositories-root.sh)
export JETPACS_REPOSITORIES_ROOT="$repositories_root"
export JETPACS_WORKSPACE="$owner_root"

# The live project context an authoring agent reads before writing an
# applet (the shadcn skill's `info --json' idea): the contract and its
# digest, the installed renderer extensions, the packaged applets, the
# registries, and `last_profile' — what the Companion advertised the last
# time a session reached READY (emacs/jetpacs-profile-snapshot.el). Static:
# nothing is evaluated; absent files leave their key out.
emacs -Q --batch \
  -l "$owner_root/jetpacs-applet-mcp/jetpacs-applet-tooling.el" \
  -l "$owner_root/jetpacs-applet-mcp/jetpacs-applet-catalog.el" \
  --eval '(progn (princ (jetpacs-applet-catalog-info-json)) (kill-emacs 0))'
