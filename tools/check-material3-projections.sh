#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
owner_root=$(pwd)
repositories_root=$(tools/repositories-root.sh)
export JETPACS_REPOSITORIES_ROOT="$repositories_root"
ebp_compose_dir=${EBP_COMPOSE_DIR:-"$repositories_root/ebp-compose"}
generator="$ebp_compose_dir/tools/gen-renderer-extension-vocabulary.py"
test -r "$generator" || {
  echo "ebp-compose generator not found at $generator" >&2
  exit 2
}

python3 "$generator" \
  "$owner_root/renderer-extensions/jetpacs-material3.json" \
  --kotlin-output "$owner_root/companion/renderer/material3/src/main/kotlin/com/calebc42/jetpacs/material3/JetpacsMaterial3Vocabulary.kt" \
  --kotlin-package com.calebc42.jetpacs.material3 \
  --elisp-output "$owner_root/emacs/apps/jetpacs-material3/jetpacs-material3-vocabulary.el" \
  --check
