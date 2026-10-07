#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
owner_root=$(pwd)
repositories_root=$(tools/repositories-root.sh)
export JETPACS_REPOSITORIES_ROOT="$repositories_root"
export JETPACS_WORKSPACE="$owner_root"

# The applet registry's batch tool (jetpacs-applet-mcp/jetpacs-applet-registry.el):
#
#   tools/jetpacs-registry.sh build    [SOURCE] [OUT]      publish items with content inlined
#   tools/jetpacs-registry.sh validate [SOURCE]            report on every item, no write
#   tools/jetpacs-registry.sh check    [SOURCE] [OUT]      drift check of the committed build
#   tools/jetpacs-registry.sh resolve  ADDRESS... [--dir DIR]
#   tools/jetpacs-registry.sh dry-run  ADDRESS... [--dir DIR]
#   tools/jetpacs-registry.sh diff     ADDRESS... [--dir DIR]
#   tools/jetpacs-registry.sh install  ADDRESS... [--dir DIR] [--overwrite]
#
# SOURCE defaults to jetpacs-applet-mcp/registry/registry.json and OUT to
# jetpacs-applet-mcp/registry/public/r. The @jetpacs namespace defaults to
# that committed build; JETPACS_REGISTRIES overrides every namespace. The
# install directory defaults to ~/.emacs.d/jetpacs/apps/. Nothing here
# loads an applet: installing writes files and a lockfile, and the App
# Store's consent gate is what runs code.
if [ -z "${JETPACS_REGISTRIES:-}" ]; then
  JETPACS_REGISTRIES="{\"@jetpacs\":\"file://$owner_root/jetpacs-applet-mcp/registry/public/r/{name}.json\"}"
  export JETPACS_REGISTRIES
fi

command=${1:-help}
shift || true
case "$command" in
  build|check)
    source=${1:-jetpacs-applet-mcp/registry/registry.json}
    out=${2:-jetpacs-applet-mcp/registry/public/r}
    set -- "$command" "$source" "$out"
    ;;
  validate)
    source=${1:-jetpacs-applet-mcp/registry/registry.json}
    set -- "$command" "$source"
    ;;
  *)
    set -- "$command" "$@"
    ;;
esac

args=""
for a in "$@"; do
  escaped=$(printf '%s' "$a" | sed 's/\\/\\\\/g; s/"/\\"/g')
  args="$args \"$escaped\""
done

emacs -Q --batch --eval '(setq load-prefer-newer t)' \
  -L "$owner_root/emacs" \
  -l "$owner_root/jetpacs-applet-mcp/jetpacs-applet-tooling.el" \
  -l "$owner_root/jetpacs-applet-mcp/jetpacs-applet-catalog.el" \
  -l "$owner_root/emacs/jetpacs-registry.el" \
  -l "$owner_root/jetpacs-applet-mcp/jetpacs-applet-registry.el" \
  --eval "(kill-emacs (jetpacs-applet-registry-batch (list $args)))"
