#!/bin/bash
# Jetpacs cloud environment setup script.
#
# Maintained source for the "Setup script" field of the cloud environment at
# claude.ai/code; paste this file whole into that field.  See
# docs/CLOUD-ENVIRONMENT.md for the surrounding configuration and the sources
# of every constraint named below.
#
# It runs as root on Ubuntu 24.04 after the session's repositories are cloned
# and before Claude Code starts.  No shell profile is loaded, and the variables
# configured beside it in the same dialog are not visible here.
#
# Three constraints shape the structure:
#   - a non-zero exit fails session startup, so every install tolerates failure
#     and the script ends with an unconditional `exit 0';
#   - only a run under roughly five minutes is snapshotted and reused, so the
#     two large installs run concurrently and the pre-warm is bounded;
#   - the session chooses where the clones land, so no collection path is baked
#     in: the profile fragment installed below resolves it per shell.
set -u

sdk_root=/opt/android-sdk
state_dir=/opt/jetpacs-cloud
report="$state_dir/report.txt"
mkdir -p "$state_dir"

# Android SDK.  companion/ builds with AGP 9.2.1 against compileSdk 37 and
# local.properties is gitignored, so the build finds the SDK through
# ANDROID_HOME alone.  The bootstrap archive carries an older sdkmanager; it
# installs the current command-line tools first, and those drive the rest.
install_android_sdk() {
  if [ -x "$sdk_root/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "android: already present (reused snapshot)"
    return 0
  fi
  mkdir -p "$sdk_root/cmdline-tools"
  curl -fsSL --retry 3 -o /tmp/cmdline-tools.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip || {
      echo "android: bootstrap download failed; is dl.google.com in Allowed domains?"
      return 1
    }
  unzip -q -o /tmp/cmdline-tools.zip -d "$sdk_root/cmdline-tools" || return 1
  rm -rf "$sdk_root/cmdline-tools/bootstrap"
  mv "$sdk_root/cmdline-tools/cmdline-tools" "$sdk_root/cmdline-tools/bootstrap"

  sdkmanager="$sdk_root/cmdline-tools/bootstrap/bin/sdkmanager"
  yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null 2>&1
  "$sdkmanager" --sdk_root="$sdk_root" "cmdline-tools;latest" >/dev/null 2>&1
  [ -x "$sdk_root/cmdline-tools/latest/bin/sdkmanager" ] \
    && sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"

  yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null 2>&1
  "$sdkmanager" --sdk_root="$sdk_root" \
    "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" >/dev/null 2>&1 \
    || echo "android: a component failed; check sdkmanager --list_installed in session"
  echo "android: platforms [$(ls "$sdk_root/platforms" 2>/dev/null | tr '\n' ' ')]" \
       "build-tools [$(ls "$sdk_root/build-tools" 2>/dev/null | tr '\n' ' ')]"
}

# Emacs.  The elisp suites require 30.1 — docs/REWRITE-PLAN.md states the floor,
# and test/run-tests.sh depends on built-in track-changes.el and the SQLite
# backend.  Noble ships 29.3, so the PPA build is the only one that meets the
# floor inside the time budget.  A failure here leaves 29.3 in place and only
# the elisp suites unavailable, which is reported rather than fatal.
install_emacs() {
  if command -v emacs >/dev/null 2>&1 \
     && emacs --version | head -1 | grep -qE 'Emacs (30\.[1-9]|3[1-9])'; then
    echo "emacs: $(emacs --version | head -1) already present"
    return 0
  fi
  apt-get install -y --no-install-recommends software-properties-common >/dev/null 2>&1
  add-apt-repository -y ppa:ubuntuhandbook1/emacs >/dev/null 2>&1 \
    || echo "emacs: PPA not added; ppa.launchpadcontent.net must be in Allowed domains"
  apt-get update -qq >/dev/null 2>&1
  apt-get install -y --no-install-recommends emacs-nox >/dev/null 2>&1
  echo "emacs: $(emacs --version 2>/dev/null | head -1 || echo 'not installed')"
  echo "emacs: sqlite backend $(emacs -Q --batch \
    --eval '(princ (if (and (fboundp (quote sqlite-available-p)) (sqlite-available-p)) "yes" "no"))' \
    2>/dev/null || echo unknown)"
}

# Resolve the checkout collection the way tools/repositories-root.sh does, but
# against the clone root this session happened to use.
cloud_root() {
  local candidate found
  for candidate in "${JETPACS_REPOSITORIES_ROOT:-}" "$HOME" "$HOME/repos" \
                   "$HOME/workspace" /workspace /repos /repo; do
    [ -n "$candidate" ] || continue
    [ -d "$candidate/jetpacs-poc" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  found=$(find / -maxdepth 4 -type d -name jetpacs-poc -not -path '*/.*/*' 2>/dev/null | head -1)
  [ -n "$found" ] && dirname "$found"
}

# Seeds ~/.gradle with the 9.5.1 distribution and the platform-tools
# dependencies so the first in-session build is not also a cold download.
# Pure JVM, no Android SDK.  Bounded, because a slow registry here would cost
# the snapshot; drop this function if the recorded duration crowds the budget.
prewarm_gradle() {
  local root
  root=$(cloud_root) || return 0
  [ -n "$root" ] && [ -d "$root/jetpacs-poc/jetpacs-platform-tools" ] || {
    echo "gradle: no checkout to pre-warm"
    return 0
  }
  local status=0
  ( cd "$root/jetpacs-poc/jetpacs-platform-tools" \
    && timeout 120 ./gradlew --no-daemon --console=plain installDist >/dev/null 2>&1 ) \
    || status=$?
  echo "gradle: pre-warm exit $status, cache $(du -sh "${GRADLE_USER_HOME:-$HOME/.gradle}" 2>/dev/null | cut -f1)"
}

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null 2>&1
apt-get install -y --no-install-recommends curl unzip ca-certificates >/dev/null 2>&1

started=$SECONDS
install_android_sdk > "$state_dir/android.log" 2>&1 &
android_pid=$!
install_emacs > "$state_dir/emacs.log" 2>&1 &
emacs_pid=$!
wait "$android_pid"
wait "$emacs_pid"
installs_done=$((SECONDS - started))

# Shell environment.  The dialog's variables are not readable from this script
# and the clone root is not known until a session exists, so the fragment below
# resolves the collection at shell start.  It also restores the two names the
# tooling expects: the EBP checkout is `ebp/' everywhere in this repository but
# `ebp-poc' on GitHub (docs/LOCAL-REPOSITORY-LAYOUT.md), and AGENTS.md and the
# documents address the collection by its absolute workspace path.
cat > "$state_dir/env.sh" <<'FRAGMENT'
# Jetpacs cloud session environment; installed by jetpacs-poc/tools/cloud-setup.sh.
jetpacs_cloud_root() {
  for candidate in "$HOME" "$HOME/repos" "$HOME/workspace" /workspace /repos /repo; do
    [ -d "$candidate/jetpacs-poc" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  found=$(find / -maxdepth 4 -type d -name jetpacs-poc -not -path '*/.*/*' 2>/dev/null | head -1)
  [ -n "$found" ] && dirname "$found"
}

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
FRAGMENT

source_line='[ -r /opt/jetpacs-cloud/env.sh ] && . /opt/jetpacs-cloud/env.sh'
printf '%s\n' "$source_line" > /etc/profile.d/jetpacs-cloud.sh
# Prepended, not appended: the distribution .bashrc returns early for
# non-interactive shells, and Claude Code's shell snapshot is one of those.
rc="${HOME:-/root}/.bashrc"
touch "$rc"
grep -qxF "$source_line" "$rc" \
  || { printf '%s\n' "$source_line" | cat - "$rc" > "$rc.jetpacs" && mv "$rc.jetpacs" "$rc"; }

# shellcheck source=/dev/null
. "$state_dir/env.sh"
prewarm_gradle > "$state_dir/gradle.log" 2>&1

{
  echo "Jetpacs cloud setup — $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo "installs ${installs_done}s, total ${SECONDS}s (snapshot budget is ~300s)"
  echo "collection root: ${JETPACS_REPOSITORIES_ROOT:-unresolved at setup time}"
  echo "checkouts: $(ls "${JETPACS_REPOSITORIES_ROOT:-/nonexistent}" 2>/dev/null | tr '\n' ' ')"
  echo "java: $(java -version 2>&1 | head -1)"
  cat "$state_dir/android.log" "$state_dir/emacs.log" "$state_dir/gradle.log" 2>/dev/null
  echo "not installed here: emulator, system images, adb-attached devices —"
  echo "  the managed-device and S21 Ultra gates stay on local hardware."
} > "$report" 2>&1

cat "$report"
exit 0
