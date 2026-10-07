#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

# Drift check for the committed applet registry build: a fresh build of
# jetpacs-applet-mcp/registry/registry.json must equal every file under
# jetpacs-applet-mcp/registry/public/r byte for byte, and nothing else
# may sit there.
exec sh tools/jetpacs-registry.sh check
