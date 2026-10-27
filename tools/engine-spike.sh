#!/usr/bin/env bash
# Device evidence for the bundled Emacs engine (docs/PLAN-bundled-emacs.md,
# E1 and later): drives the debug `bundled` flavor on one adb device and
# prints what the plan's gates ask for. Nothing here touches the standalone
# install; the bundled application ID is distinct.
#
#   tools/engine-spike.sh install            build :app:assembleBundledDebug and install it
#   tools/engine-spike.sh launch             start the launcher activity (which starts the engine)
#   tools/engine-spike.sh watch [SECONDS]    engine + Emacs + bridge logcat lines (default 120 s)
#   tools/engine-spike.sh status             processes, engine HOME, dump files, socket state
#   tools/engine-spike.sh cpu [SECONDS]      CPU share of the :emacs process sampled over SECONDS
#   tools/engine-spike.sh night on|off       toggle the system dark theme (the event-queue test)
#   tools/engine-spike.sh kill               kill (not force-stop) the :emacs process
#   tools/engine-spike.sh wipe               clear the bundled app's data (fresh install state)
#   tools/engine-spike.sh init-el            print the provisioned init.el and install.conf
#
# SERIAL comes from $ANDROID_SERIAL; set it when two transports show one device.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"
APP_ID="com.calebc42.jetpacs.companion.bundled"
ENGINE_PROCESS="$APP_ID:emacs"
APK="$REPO_ROOT/companion/app/build/outputs/apk/bundled/debug/app-bundled-debug.apk"

adbs() { if [ -n "${ANDROID_SERIAL:-}" ]; then adb -s "$ANDROID_SERIAL" "$@"; else adb "$@"; fi; }
engine_pid() { adbs shell pidof "$ENGINE_PROCESS" 2>/dev/null | tr -d '\r' || true; }

cmd="${1:-}"; shift || true
case "$cmd" in
  install)
    (cd "$REPO_ROOT/companion" && ./gradlew --console=plain -q :app:assembleBundledDebug)
    ls -la "$APK"
    adbs install -r "$APK"
    ;;
  launch)
    adbs shell am start -W -n "$APP_ID/com.calebc42.jetpacs.companion.MainActivity"
    ;;
  watch)
    seconds="${1:-120}"
    adbs logcat -c
    timeout "$seconds" adbs logcat -v time -s JetpacsEngine:* Emacs:* DeviceBridge:* AndroidRuntime:E ActivityManager:I \
      | grep -E "JetpacsEngine|Emacs|DeviceBridge|AndroidRuntime|$APP_ID" || true
    ;;
  status)
    echo "== processes"; adbs shell "ps -A | grep -E '$APP_ID' || echo '(none)'"
    pid="$(engine_pid)"
    if [ -n "$pid" ]; then
      echo "== :emacs pid $pid threads"; adbs shell "ls /proc/$pid/task | wc -l"
      echo "== :emacs oom_score_adj $(adbs shell cat /proc/$pid/oom_score_adj | tr -d '\r')"
    fi
    # run-as accepts one command, and `sh -c` under it is denied on the
    # tablet, so each probe is its own run-as.
    home="/data/user/0/$APP_ID/files/emacs"
    echo "== engine HOME $home"; adbs shell run-as "$APP_ID" ls -la "$home" "$home/.emacs.d" 2>&1
    echo "== stamps: engine=$(adbs shell run-as "$APP_ID" cat "$home/engine.stamp" 2>/dev/null) dump=$(adbs shell run-as "$APP_ID" cat "$home/dump.stamp" 2>/dev/null)"
    adbs shell run-as "$APP_ID" du -sh "$home/.emacs.d/jetpacs" 2>/dev/null
    echo "== activities of the app (should be only the Companion UI)"; adbs shell "dumpsys activity activities | grep -E 'Hist.*$APP_ID|Run #' | head -5"
    echo "== loopback listener"; adbs shell "cat /proc/net/tcp | awk '\$2 ~ /:223D/ {print}' | head -3"
    ;;
  cpu)
    seconds="${1:-30}"
    pid="$(engine_pid)"; [ -n "$pid" ] || { echo "no :emacs process"; exit 1; }
    read -r u1 s1 < <(adbs shell cat /proc/$pid/stat | awk '{print $14, $15}')
    sleep "$seconds"
    read -r u2 s2 < <(adbs shell cat /proc/$pid/stat | awk '{print $14, $15}')
    hz=$(adbs shell getconf CLK_TCK | tr -d '\r')
    ticks=$(( (u2 + s2) - (u1 + s1) ))
    echo "pid $pid used $ticks ticks in $seconds s = $(awk -v t="$ticks" -v h="$hz" -v s="$seconds" 'BEGIN{printf "%.1f", 100*t/(h*s)}')% of one core"
    ;;
  night)
    adbs shell cmd uimode night "${1:?on|off}"
    ;;
  kill)
    # The shell user may not signal another app's process; run-as can on a
    # debuggable build. This is a plain kill, not am force-stop, so the
    # sticky service is restarted by the system.
    pid="$(engine_pid)"; [ -n "$pid" ] || { echo "no :emacs process"; exit 1; }
    adbs shell run-as "$APP_ID" kill -9 "$pid" && echo "killed $pid"
    ;;
  wipe)
    adbs shell pm clear "$APP_ID"
    ;;
  init-el)
    home="/data/user/0/$APP_ID/files/emacs"
    adbs shell run-as "$APP_ID" cat "$home/.emacs.d/init.el"; echo
    adbs shell run-as "$APP_ID" cat "$home/.emacs.d/jetpacs/install.conf" 2>/dev/null || true
    ;;
  *)
    sed -n 2,16p "$0"; exit 1
    ;;
esac
