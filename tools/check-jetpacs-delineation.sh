#!/bin/sh
# Jetpacs delineation guard: EVERY emacs/jetpacs-*.el loads alone, in a
# process of its own with only EBP and Jetpacs on the load path, and leaves
# no downstream app (Glasspane, Bloom, Orgzly, Harp) symbol, face, error,
# custom group or feature behind.  The textual layering guard
# (tools/check-layering.sh) reads source; this one proves the require
# closure.  A glob with an asserted count, so a new module is guarded the
# day it lands.
set -eu

cd "$(dirname "$0")/.."
jetpacs_root=$(pwd)
repositories_root=$(tools/repositories-root.sh)
ebp_el_dir=${EBP_EL_DIR:-"$repositories_root/ebp.el"}
ebp_org_dir=${EBP_ORG_DIR:-"$repositories_root/ebp-org"}
components_dir=${JETPACS_COMPONENTS_DIR:-"$jetpacs_root/jetpacs-components"}
authoring_dir=${JETPACS_AUTHORING_DIR:-"$repositories_root/jetpacs-authoring"}
# The load path test/run-tests.sh gives every suite, so the composition
# roots (jetpacs-init.el, jetpacs.el) load headless too and are checked.
export DELINEATION_LOAD_PATH="$ebp_el_dir/lisp:$ebp_org_dir/lisp:$authoring_dir/lisp:$jetpacs_root/emacs:$jetpacs_root/emacs/apps/jetpacs-material3:$jetpacs_root/emacs/apps/m3-catalog:$components_dir/lisp/jetpacs-components:$jetpacs_root/jetpacs-automations/lisp:$jetpacs_root/jetpacs-component-catalog/lisp"
export DELINEATION_CHECKER="$jetpacs_root/tools/check-jetpacs-delineation.el"

count=$(ls emacs/jetpacs-*.el | wc -l)
if test "$count" -lt 55; then
  echo "delineation guard: emacs/jetpacs-*.el matched $count files, expected at least 55" >&2
  exit 1
fi

ls emacs/jetpacs-*.el |
  xargs -P "${DELINEATION_JOBS:-8}" -n 1 sh -c '
    feature=$(basename "$1" .el)
    if DELINEATION_FEATURE="$feature" EMACSLOADPATH="$DELINEATION_LOAD_PATH:" \
         emacs -Q --batch --eval "(setq load-prefer-newer t)" \
         -l "$DELINEATION_CHECKER" >/dev/null 2>"/tmp/delineation-$$-$feature.log"; then
      rm -f "/tmp/delineation-$$-$feature.log"
    else
      echo "delineation guard: $feature.el failed:" >&2
      tail -n 5 "/tmp/delineation-$$-$feature.log" >&2
      rm -f "/tmp/delineation-$$-$feature.log"
      exit 255
    fi' sh
echo "delineation guard: $count jetpacs modules load alone, no downstream app symbols"
