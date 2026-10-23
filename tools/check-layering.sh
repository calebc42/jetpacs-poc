#!/bin/sh
# Layering guard: dependency direction is EBP -> Jetpacs -> downstream apps.
#
# (a) Foundation Elisp names no downstream app: no `(require 'APP' and no
#     APP-prefixed symbol, word-anchored so a foundation name that merely
#     contains one (`jetpacs-grove-capture-widget-fixture') does not trip,
#     plus the case-insensitive Glasspane check this replaced.
# (b) Every library the foundation requires is Jetpacs, EBP or built into
#     Emacs: in an `emacs -Q' with no added load path, each required feature
#     not prefixed `jetpacs'/`ebp' must be locatable.  A soft require
#     (NOERROR non-nil) of a third-party package passes only when named in
#     tools/third-party-allowlist.txt.
#
# JETPACS_LAYERING_DIR overrides the scanned directory (a scratch copy for a
# negative check); the default is this checkout's emacs/.
set -eu

cd "$(dirname "$0")/.."
dir=${JETPACS_LAYERING_DIR:-emacs}
allowlist=$(pwd)/tools/third-party-allowlist.txt
apps='glasspane|bloom|orgzly|harp'
status=0

if grep -n -E "\(require '($apps)([[:space:])]|$)" "$dir"/*.el; then
  echo "layering guard: foundation Elisp must not require a downstream app" >&2
  status=1
fi
if grep -n -E "(^|[^[:alnum:]_-])($apps)-[a-z]" "$dir"/*.el; then
  echo "layering guard: foundation Elisp must not name a downstream app's symbols" >&2
  status=1
fi
if grep -n -i 'glasspane' "$dir"/*.el; then
  echo "layering guard: foundation Elisp must not know Glasspane" >&2
  status=1
fi

files=$(ls "$dir"/*.el "$dir"/apps/packaged-apps/*.el 2>/dev/null || true)
LAYERING_FILES="$files" LAYERING_ALLOWLIST="$allowlist" \
  emacs -Q --batch -l tools/check-layering.el || status=1

if test "$status" -eq 0; then
  echo "layering guard: foundation names no downstream app and requires only Jetpacs, EBP and built-in libraries"
fi
exit "$status"
