#!/bin/sh
# Resolve the explicit checkout collection without treating its contents as
# one repository. Standard sibling and nested hand-rewrite layouts are supported.
set -eu
jetpacs_source_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
if [ -n "${JETPACS_REPOSITORIES_ROOT:-}" ]; then
  CDPATH= cd -- "$JETPACS_REPOSITORIES_ROOT"
  pwd -P
  exit 0
fi
for candidate in "$jetpacs_source_root/.." "$jetpacs_source_root/../.."; do
  if [ -f "$candidate/ebp/SPEC.md" ]; then
    CDPATH= cd -- "$candidate"
    pwd -P
    exit 0
  fi
done
echo 'Repository collection not found; set JETPACS_REPOSITORIES_ROOT to the directory containing ebp, ebp.el, ebp-kmp, and the other checkouts.' >&2
exit 2
