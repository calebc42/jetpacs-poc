#!/usr/bin/env bash
# Setup script for Jetpacs Claude Code cloud sessions.  Paste this file whole
# into the environment's "Setup script" field; docs/CLOUD-ENVIRONMENT.md holds
# the other three dialog values.
#
# Contract imposed by the platform:
#   * runs as root on Ubuntu 24.04 x86_64, after the clones, before Claude starts
#   * must finish in roughly 300s or the filesystem snapshot is never built and
#     every later session pays the whole cost again
#   * the dialog's environment variables are NOT in scope here; they reach the
#     commands Claude runs, not this script.  That is why /opt/android-sdk is
#     written out literally in both places.
#
# No `set -e': a failed Android or Emacs install must leave a session that is
# still useful for the parts that do not need them.  Failures land in
# /opt/jetpacs-cloud/report.txt instead of aborting startup.
set -u

ANDROID_HOME=/opt/android-sdk
ANDROID_PLATFORM='platforms;android-37.0'   # companion/ compiles against 37
ANDROID_BUILD_TOOLS='build-tools;37.0.0'
STATE=/opt/jetpacs-cloud
SNAPSHOT_BUDGET=300

# Emacs: the suites declare `Package-Requires: ((emacs "30.1"))' and ebp-sync
# requires track-changes, which only exists from 30 onward.  Noble ships 29.3,
# so the PPA is load-bearing rather than a nicety.
EMACS_PPA=ubuntuhandbook1/emacs
EMACS_PPA_KEY=F4E48910A020E77056748B745738AE8480447DDF
EMACS_KEYRING=/usr/share/keyrings/ubuntuhandbook1-emacs.gpg

mkdir -p "$STATE"
started=$(date +%s)

log() { printf '%s\n' "$*" >>"$STATE/$1.log"; }

# ---------------------------------------------------------------------------
# Emacs 30
#
# `add-apt-repository' is unusable in this image for two independent reasons,
# both of which cost a session before they were understood:
#
#   1. its shebang is /usr/bin/python3, which the image points at 3.13, while
#      python3-apt only ships apt_pkg for cpython-312.  It dies with
#      ModuleNotFoundError before it ever opens a socket.
#   2. it resolves a `ppa:' shortcut through api.launchpad.net, which is not in
#      the default allowlist and is not worth adding.
#
# Writing the deb822 stanza and the keyring by hand sidesteps both.  It needs
# only ppa.launchpadcontent.net (added to Allowed domains) and
# keyserver.ubuntu.com (already in the default list).
install_emacs() {
  local url="https://ppa.launchpadcontent.net/${EMACS_PPA}/ubuntu/"

  curl -fsS --max-time 60 \
    "https://keyserver.ubuntu.com/pks/lookup?op=get&options=mr&search=0x${EMACS_PPA_KEY}" \
    | gpg --dearmor >"$EMACS_KEYRING" 2>/dev/null
  if [ ! -s "$EMACS_KEYRING" ]; then
    log emacs "emacs: could not fetch PPA key ${EMACS_PPA_KEY} from keyserver.ubuntu.com"
    return 1
  fi

  cat >/etc/apt/sources.list.d/ubuntuhandbook1-emacs.sources <<EOF
Types: deb
URIs: ${url}
Suites: noble
Components: main
Signed-By: ${EMACS_KEYRING}
EOF

  if ! apt-get update -o Dir::Etc::sourceparts=- \
       -o APT::Get::List-Cleanup=0 \
       -o Dir::Etc::sourcelist=/etc/apt/sources.list.d/ubuntuhandbook1-emacs.sources \
       >/dev/null 2>&1; then
    log emacs "emacs: apt-get update failed for ${url} (is ppa.launchpadcontent.net in Allowed domains?)"
    return 1
  fi

  # Guard the floor explicitly: a PPA that has moved on to a different series
  # would otherwise leave 29.3 installed and the failure unexplained.
  local candidate
  candidate=$(apt-cache policy emacs-nox 2>/dev/null | awk '/Candidate:/ {print $2}')
  case "$candidate" in
    *:30.*|*:31.*|30.*|31.*) ;;
    *) log emacs "emacs: PPA candidate is ${candidate:-none}, below the 30.1 floor; keeping distro Emacs"
       return 1 ;;
  esac

  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    emacs-nox >/dev/null 2>&1 \
    || { log emacs "emacs: apt-get install emacs-nox failed"; return 1; }
}

# ---------------------------------------------------------------------------
# Android SDK
#
# Only what the JVM and assembly gates need.  No emulator and no system images:
# the managed device needs KVM, which no session has, so paying for the
# download would just crowd the snapshot budget.
install_android() {
  if ! command -v sdkmanager >/dev/null 2>&1 \
     && [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    log android "android: no sdkmanager in the base image and none installed"
    return 1
  fi
  local sdkmanager
  sdkmanager=$(command -v sdkmanager 2>/dev/null \
               || printf '%s' "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager")

  yes 2>/dev/null | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1
  "$sdkmanager" --sdk_root="$ANDROID_HOME" \
    "platform-tools" "$ANDROID_PLATFORM" "$ANDROID_BUILD_TOOLS" >/dev/null 2>&1 \
    || { log android "android: sdkmanager install failed"; return 1; }
}

# ---------------------------------------------------------------------------
# Gradle pre-warm
#
# Deletable: it seeds the wrapper distribution and the dependency cache and
# nothing else depends on it.  Drop it first if the budget gets tight.
#
# It must configure the companion build, not merely resolve a leaf module's
# dependencies.  AGP is applied in companion/build.gradle.kts's plugins block,
# so a pre-warm that skips configuration leaves `com.android.application' out
# of the cache and the first real build still pays ~2 minutes for it.
prewarm_gradle() {
  local root="$1"
  [ -d "$root/jetpacs-poc/companion" ] || return 0
  (
    cd "$root/jetpacs-poc/companion" || exit 0
    export JETPACS_REPOSITORIES_ROOT="$root"
    export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
    # `help' configures every project, which is what pulls the plugin
    # marker artifacts down; `--dry-run' on a test task would not.
    ./gradlew --no-daemon --console=plain help >/dev/null 2>&1
  )
}

# ---------------------------------------------------------------------------
# Profile fragment
#
# There is no .bashrc field in the dialog, so install one.  The sourcing line is
# PREPENDED because the distribution .bashrc returns early for non-interactive
# shells, which is exactly the kind of shell the Bash tool opens.
write_profile() {
  cat >"$STATE/env.sh" <<'PROFILE'
# Jetpacs cloud session environment; installed by jetpacs-poc/tools/cloud-setup.sh.
jetpacs_cloud_root() {
  for candidate in "$HOME" "$HOME/repos" "$HOME/workspace" /workspace /repos /repo; do
    [ -d "$candidate/jetpacs-poc" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  found=$(find / -maxdepth 4 -type d -name jetpacs-poc -not -path '*/.*/*' 2>/dev/null | head -1)
  [ -n "$found" ] && dirname "$found"
}

# JETPACS_REPOSITORIES_ROOT is pinned in the environment's variables, so this
# resolves nothing in a normal session.  It stays as the fallback for a session
# that clones to a different root before the variable is updated.
if [ -z "${JETPACS_REPOSITORIES_ROOT:-}" ]; then
  jetpacs_cloud_resolved=$(jetpacs_cloud_root)
  [ -n "$jetpacs_cloud_resolved" ] \
    && export JETPACS_REPOSITORIES_ROOT="$jetpacs_cloud_resolved"
  unset jetpacs_cloud_resolved
fi

if [ -n "${JETPACS_REPOSITORIES_ROOT:-}" ]; then
  # Every suite and companion/settings.gradle.kts resolves `ebp/'; the clone
  # arrives as `ebp-poc' after the GitHub rename.
  [ -e "$JETPACS_REPOSITORIES_ROOT/ebp" ] \
    || [ ! -d "$JETPACS_REPOSITORIES_ROOT/ebp-poc" ] \
    || ln -s ebp-poc "$JETPACS_REPOSITORIES_ROOT/ebp" 2>/dev/null
  # The absolute paths in AGENTS.md and docs/ resolve through this link.
  [ -e /home/calebc42/workspace ] \
    || { mkdir -p /home/calebc42 2>/dev/null \
         && ln -s "$JETPACS_REPOSITORIES_ROOT" /home/calebc42/workspace 2>/dev/null; }
fi

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
case ":$PATH:" in
  *":$ANDROID_HOME/platform-tools:"*) ;;
  *) PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
     export PATH ;;
esac
PROFILE

  grep -qF "$STATE/env.sh" /root/.bashrc 2>/dev/null || {
    printf '%s\n' "[ -r $STATE/env.sh ] && . $STATE/env.sh" >/tmp/jetpacs-bashrc.$$
    cat /root/.bashrc >>/tmp/jetpacs-bashrc.$$ 2>/dev/null
    mv /tmp/jetpacs-bashrc.$$ /root/.bashrc
  }
}

# ---------------------------------------------------------------------------
# Run.  The two installs are independent, so overlap them; both are network
# bound and serialising them is most of the wall time.
install_start=$(date +%s)
install_emacs & emacs_pid=$!
install_android & android_pid=$!
wait "$emacs_pid"; emacs_rc=$?
wait "$android_pid"; android_rc=$?
install_elapsed=$(( $(date +%s) - install_start ))

write_profile

# Resolve the collection root the same way the profile fragment does; this
# script cannot read JETPACS_REPOSITORIES_ROOT from the dialog.
root=""
for candidate in /home/user "$HOME" /workspace /repos /repo; do
  [ -d "$candidate/jetpacs-poc" ] && { root="$candidate"; break; }
done
[ -n "$root" ] || root=$(find / -maxdepth 4 -type d -name jetpacs-poc \
  -not -path '*/.*/*' 2>/dev/null | head -1 | xargs -r dirname)

# The link the suites need has to exist before the pre-warm configures
# settings.gradle.kts, which resolves ebp/SPEC.md.
if [ -n "$root" ] && [ ! -e "$root/ebp" ] && [ -d "$root/ebp-poc" ]; then
  ln -s ebp-poc "$root/ebp" 2>/dev/null
fi

[ -n "$root" ] && prewarm_gradle "$root"
gradle_rc=$?

# ---------------------------------------------------------------------------
# Report.  Read it first thing in a new session: it is the only record of what
# the snapshot actually contains.
total=$(( $(date +%s) - started ))
{
  printf 'Jetpacs cloud setup — %s\n' "$(date -u '+%Y-%m-%d %H:%M UTC')"
  printf 'installs %ss, total %ss (snapshot budget is ~%ss)\n' \
    "$install_elapsed" "$total" "$SNAPSHOT_BUDGET"
  [ "$total" -gt "$SNAPSHOT_BUDGET" ] && printf \
    'WARNING: over budget, this run is not snapshotted; drop prewarm_gradle\n'
  printf 'collection root: %s\n' "${root:-NOT FOUND}"
  [ -n "$root" ] && printf 'checkouts: %s\n' "$(ls "$root" 2>/dev/null | tr '\n' ' ')"
  printf 'java: %s\n' "$(java -version 2>&1 | head -1)"

  if [ "$android_rc" -eq 0 ]; then
    printf 'android: platforms [%s] build-tools [%s]\n' \
      "$(ls "$ANDROID_HOME/platforms" 2>/dev/null | tr '\n' ' ')" \
      "$(ls "$ANDROID_HOME/build-tools" 2>/dev/null | tr '\n' ' ')"
  else
    cat "$STATE/android.log" 2>/dev/null
  fi

  [ "$emacs_rc" -eq 0 ] || cat "$STATE/emacs.log" 2>/dev/null
  printf 'emacs: %s\n' "$(emacs --version 2>/dev/null | head -1)"
  printf 'emacs: sqlite backend %s\n' \
    "$(emacs -Q --batch --eval '(princ (if (sqlite-available-p) "yes" "no"))' 2>/dev/null)"
  printf 'emacs: track-changes %s (ebp-sync needs it; Emacs 30+)\n' \
    "$(emacs -Q --batch --eval '(princ (if (locate-library "track-changes") "yes" "no"))' 2>/dev/null)"

  printf 'gradle: pre-warm exit %s, cache %s\n' \
    "$gradle_rc" "$(du -sh /root/.gradle 2>/dev/null | cut -f1)"
  printf 'not installed here: emulator, system images, adb-attached devices —\n'
  printf '  the managed-device and S21 Ultra gates stay on local hardware.\n'
} >"$STATE/report.txt" 2>&1

cat "$STATE/report.txt"
exit 0
