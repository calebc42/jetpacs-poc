#!/usr/bin/env bash
# Fetch the engine AAR companion/gradle/engine.lock pins from the GitHub
# release of jetpacs-emacs-engine (docs/PLAN-bundled-emacs.md, E7) and verify
# its SHA-256 against the lock. For machines without the engine repository
# built beside this checkout: CI, or a developer who only needs the bundled
# flavor. Prints the path and, under GitHub Actions, exports JETPACS_ENGINE_AAR
# for the following steps.
#
#   tools/fetch-engine-aar.sh [DIR]      default DIR: companion/build/engine
#
# Needs gh, authenticated (GH_TOKEN under Actions; JETPACS_ENGINE_REPO
# overrides the repository). Release assets spell the "+" of the engine id as
# "-" because GitHub rewrites "+" in asset names; the file is renamed back so
# the name the Gradle default path convention expects also holds.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"
lock="$REPO_ROOT/companion/gradle/engine.lock"
die() { echo "fetch-engine-aar: $*" >&2; exit 1; }

id="$(sed -n 's/^engine=//p' "$lock")"
sha="$(sed -n 's/^sha256=//p' "$lock")"
[ -n "$id" ] && [ -n "$sha" ] || die "$lock names no engine= and sha256="
repo="${JETPACS_ENGINE_REPO:-calebc42/jetpacs-emacs-engine}"
dir="${1:-$REPO_ROOT/companion/build/engine}"
asset="jetpacs-emacs-engine-${id//+/-}.aar"
out="$dir/jetpacs-emacs-engine-$id.aar"
mkdir -p "$dir"

if [ -f "$out" ] && echo "$sha  $out" | sha256sum -c --quiet - 2>/dev/null; then
  echo "fetch-engine-aar: $out already matches the lock" >&2
else
  command -v gh >/dev/null || die "gh is required"
  gh release download "engine-$id" --repo "$repo" --pattern "$asset" --dir "$dir" --clobber \
    || die "release engine-$id of $repo has no $asset (its README, 'Releases and CI')"
  mv -f "$dir/$asset" "$out"
  echo "$sha  $out" | sha256sum -c - >&2 \
    || die "the release asset does not match gradle/engine.lock; the lock pins one AAR, the release carries another"
fi
echo "$out"
[ -z "${GITHUB_ENV:-}" ] || echo "JETPACS_ENGINE_AAR=$out" >> "$GITHUB_ENV"
