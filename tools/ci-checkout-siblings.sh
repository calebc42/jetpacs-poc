#!/usr/bin/env bash
# Clone the repository collection the Companion build and the Elisp suites
# read (docs/LOCAL-REPOSITORY-LAYOUT.md) for a machine that has only this
# checkout: CI. Each sibling is a shallow clone of its GitHub default branch
# beside this checkout, under the directory name the layout table gives it,
# so CI composes the published heads in a developer's workspace layout. The
# layout matters: tools/repositories-root.sh and the Gradle settings accept
# JETPACS_REPOSITORIES_ROOT, but the applet tooling's discovery, which
# test/run-tests.sh runs with that variable unset, only looks beside the
# checkout. emacs-websocket, the third-party library ebp.el's delineation
# guard loads, is pinned to a commit, and so is emacs/emacs, of which only
# lisp/org is fetched: the read-only Org sources the applet tooling's `org'
# reference root reads (jetpacs-applet-tooling--legacy-org-candidate), from
# the Emacs 31.1 release. Prints the collection directory and,
# under GitHub Actions, exports JETPACS_REPOSITORIES_ROOT for the following
# steps.
#
#   tools/ci-checkout-siblings.sh [DIR]   default under GitHub Actions: the
#                                         directory holding this checkout;
#                                         DIR must not hold the siblings yet
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"
die() { echo "ci-checkout-siblings: $*" >&2; exit 1; }

# directory               GitHub repository           pinned commit                             only
siblings="
ebp                       calebc42/ebp-poc
ebp.el                    calebc42/ebp.el
ebp-kmp                   calebc42/ebp-kmp
ebp-compose               calebc42/ebp-compose
ebp-org                   calebc42/ebp-org
glasspane                 calebc42/glasspane
jetpacs-authoring         calebc42/jetpacs-authoring
bloom-native              calebc42/grove-native
orgzly-native             calebc42/orgzly-native
harp-native               calebc42/harp-native
emacs/emacs-websocket     ahyatt/emacs-websocket      2195e1247ecb04c30321702aa5f5618a51c329c5
emacs/emacs               emacs-mirror/emacs          a360712c9d272d950d8d8255ef74570f7e90b7d9  lisp/org
"

if [ -n "${1:-}" ]; then
  root="$1"
elif [ -n "${GITHUB_ACTIONS:-}" ]; then
  root="$REPO_ROOT/.."
else
  die "outside GitHub Actions give the DIR"
fi
mkdir -p "$root"
root="$(cd "$root" && pwd -P)"

while read -r dir repo commit only; do
  [ -n "$dir" ] || continue
  dest="$root/$dir"
  [ ! -e "$dest" ] || die "$dest exists"
  url="https://github.com/$repo.git"
  if [ -z "$commit" ]; then
    git clone -q --depth 1 "$url" "$dest" || die "cannot clone $url"
  else
    git init -q "$dest"
    git -C "$dest" remote add origin "$url"
    filter=()
    if [ -n "$only" ]; then
      # A partial clone: the checkout fetches the blobs below ONLY alone.
      git -C "$dest" config remote.origin.promisor true
      git -C "$dest" config remote.origin.partialclonefilter blob:none
      git -C "$dest" sparse-checkout set --no-cone "/$only/"
      filter=(--filter=blob:none)
    fi
    git -C "$dest" fetch -q --depth 1 "${filter[@]}" origin "$commit" \
      || die "cannot fetch $commit from $url"
    git -C "$dest" checkout -q FETCH_HEAD
  fi
  printf '%-22s %-28s %s\n' "$dir" "$repo" "$(git -C "$dest" rev-parse HEAD)" >&2
done <<< "$siblings"

echo "$root"
[ -z "${GITHUB_ENV:-}" ] || echo "JETPACS_REPOSITORIES_ROOT=$root" >> "$GITHUB_ENV"
