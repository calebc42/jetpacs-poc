#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
owner_root=$(pwd)
repositories_root=$(../tools/repositories-root.sh)
ebp_compose_dir=${EBP_COMPOSE_DIR:-"$repositories_root/ebp-compose"}
generator="$ebp_compose_dir/tools/gen-renderer-extension-vocabulary.py"
test -r "$generator" || {
  echo "ebp-compose generator not found at $generator" >&2
  exit 2
}

python3 "$generator" \
  "$owner_root/renderer-extensions/jetpacs-components.json" \
  --kotlin-output "$owner_root/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/JetpacsComponentsVocabulary.kt" \
  --kotlin-package com.calebc42.jetpacs.renderer.jetpacs \
  --elisp-output "$owner_root/lisp/jetpacs-components/jetpacs-components-vocabulary.el" \
  --check

python3 "$generator" \
  "$owner_root/renderer-extensions/jetpacs-design.json" \
  --kotlin-output "$owner_root/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/JetpacsDesignVocabulary.kt" \
  --kotlin-package com.calebc42.jetpacs.renderer.jetpacs \
  --elisp-output "$owner_root/lisp/jetpacs-components/jetpacs-design-vocabulary.el" \
  --check
