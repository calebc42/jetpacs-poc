# Cloud environment

Configuration for Claude Code cloud sessions on this workspace. The dialog at
claude.ai/code asks for four things; this document holds the value for each and
`tools/cloud-setup.sh` is the maintained source of the longest one.

A cloud session is a fresh Ubuntu 24.04 x86_64 VM with the attached GitHub
repositories cloned into it. Nothing from this machine carries over: not
`~/.bashrc`, not a local `.env`, not `~/.local/share/android-sdk`, not the
user-scope MCP registration. Only what is committed to an attached repository
and what the environment itself installs exists in the session.

Last verified in a live session on 2026-10-20; see [Verified](#verified) for
what was measured and what is still open.

## The four fields

| Field | What it is | Value |
|---|---|---|
| Name | Label in the environment selector | `jetpacs` |
| Network access | Outbound allowlist for the session | **Custom**, defaults included, plus the two hosts below |
| Environment variables | `.env` format, one `KEY=value` per line, no secrets | the block below |
| Setup script | Bash, run as root after the clones and before Claude Code starts | `tools/cloud-setup.sh`, pasted whole |

There is no `.bashrc` field. The setup script writes one: it installs a profile
fragment at `/opt/jetpacs-cloud/env.sh` and prepends a line sourcing it to
`/root/.bashrc`, prepended because the distribution `.bashrc` returns early for
non-interactive shells.

Anyone who can use the environment can read its variables and its setup script,
so neither may carry a credential. A key that an applet task genuinely needs
goes in **Network secrets** instead, where the agent proxy attaches it to
requests for the hosts you name and the session never sees the value.

### Network access

Select **Custom**, check *Also include default list of common package
managers*, and add:

```text
dl.google.com
ppa.launchpadcontent.net
```

Both are absent from the default **Trusted** list and both are load-bearing.
Gradle's `google()` repository resolves to `https://dl.google.com/dl/android/maven2/`
and `sdkmanager` downloads from the same host, so on Trusted alone the
companion build cannot resolve AGP 9.2.1 and the SDK install fails — the
default list carries `maven.google.com`, which is not the host Gradle uses.
Launchpad serves PPA packages from `ppa.launchpadcontent.net`, which the
default list does not carry even though it does carry `launchpad.net`,
`www.launchpad.net` and the long-superseded `ppa.launchpad.net`.

`keyserver.ubuntu.com`, which the setup script uses to fetch the PPA's signing
key, is already covered by the default list's `*.ubuntu.com`. So are
`services.gradle.org`, `plugins.gradle.org`, `repo1.maven.org` and
`raw.githubusercontent.com`, each of which is listed by name.

One host is deliberately *not* added: `api.launchpad.net`. `add-apt-repository`
resolves a `ppa:` shortcut through it, but that command cannot run in this image
at all (see [Emacs 30](#emacs-30)), so the setup script writes the deb822 stanza
by hand and the API host is never needed.

### Environment variables

```text
# companion/ resolves the SDK through ANDROID_HOME because local.properties is
# gitignored; tools/cloud-setup.sh installs it at this path.
ANDROID_HOME=/opt/android-sdk
ANDROID_SDK_ROOT=/opt/android-sdk

# Gradle builds, lint, and the ERT suites run well past the two-minute default
# the Bash tool applies to a foreground command.
BASH_DEFAULT_TIMEOUT_MS=600000
BASH_MAX_TIMEOUT_MS=1800000

# The dependency cache the setup script pre-warms lives here and is kept in the
# environment snapshot.
GRADLE_USER_HOME=/root/.gradle

# The clone root. Sessions with several repositories start above the clones, so
# this is the directory holding them, not a checkout.
JETPACS_REPOSITORIES_ROOT=/home/user
```

`JETPACS_REPOSITORIES_ROOT` is pinned because the first session reported
`/home/user` as the clone root. Pinning it removes the per-shell `find` in the
profile fragment and makes the variable authoritative, exactly as
`tools/repositories-root.sh` and `companion/settings.gradle.kts` already honour
it locally. The fragment keeps its fallback for a session that clones somewhere
else before this value is updated.

The setup script cannot read any of these: the dialog's variables reach the
commands Claude runs, not the script that runs before Claude starts. That is
why `/opt/android-sdk` appears literally in both places, and why the script
re-resolves the clone root itself rather than trusting the variable.

## Repositories to attach

Cloud sessions clone by GitHub repository name, and this workspace's local
directory names are not all the same (`docs/LOCAL-REPOSITORY-LAYOUT.md`). The
EBP specification checkout is the one that matters: every suite and
`companion/settings.gradle.kts` resolves `ebp/`, while GitHub serves it as
`calebc42/ebp-poc`. The profile fragment links `ebp -> ebp-poc` in the clone
root for that reason.

Attach what the task needs; the minimum sets follow from the dependency gate in
`test/run-tests.sh` and the project paths in `companion/settings.gradle.kts`.

| Task | Attach |
|---|---|
| Specification, goldens, contract (`python3 ebp/validate.py`) | `jetpacs-poc`, `ebp-poc` |
| Companion Kotlin and JVM suites (`:wire:jvmTest`, renderer, `:app`) | `jetpacs-poc`, `ebp-poc`, `ebp-kmp`, `ebp-compose` |
| Elisp suites (`test/run-tests.sh`) | the four above plus `ebp.el`, `ebp-org`, `grove-native` |
| Applet work | add `glasspane`, `orgzly-native`, `harp-native`, `jetpacs-authoring` as the task names them |

A missing checkout is a loud failure, not a silent one: `settings.gradle.kts`
errors with *Set JETPACS_REPOSITORIES_ROOT to the directory containing the EBP
checkouts*, and `test/run-tests.sh` exits 2 naming the first missing file. The
gate checks six paths: `ebp/contract.json`, `ebp.el/lisp/ebp.el`,
`ebp-org/lisp/ebp-org.el`, `ebp-kmp/wire/build.gradle.kts`,
`ebp-compose/renderer/model/build.gradle.kts` and `grove-native/elisp/grove.el`.
`orgzly-native` and `jetpacs-authoring` are referenced by the runner but not
gated, so the Elisp row is genuinely seven repositories.

Every repository in this workspace is currently public. Should any become
private, it needs the Claude GitHub App installed on it, or a `gh` token sent to
your account with `/web-setup` from the terminal.

Selecting seven repositories by hand each time is tedious. Links of this shape
pre-fill the form:

```text
https://claude.ai/code?environment=jetpacs&repositories=calebc42/jetpacs-poc,calebc42/ebp-poc
https://claude.ai/code?environment=jetpacs&repositories=calebc42/jetpacs-poc,calebc42/ebp-poc,calebc42/ebp-kmp,calebc42/ebp-compose
https://claude.ai/code?environment=jetpacs&repositories=calebc42/jetpacs-poc,calebc42/ebp-poc,calebc42/ebp-kmp,calebc42/ebp-compose,calebc42/ebp.el,calebc42/ebp-org,calebc42/grove-native
```

The `repositories` and `environment` query parameters are not documented
anywhere in the Claude Code docs, so treat them as a convenience that may stop
working without notice rather than a supported interface. Check that the form
really came up pre-filled before assuming the session has the right checkouts.

## First session

Ask Claude to run these, in this order; each line answers one question the
configuration above leaves open.

```bash
cat /opt/jetpacs-cloud/report.txt
echo "$JETPACS_REPOSITORIES_ROOT" && ls "$JETPACS_REPOSITORIES_ROOT"
cd "$JETPACS_REPOSITORIES_ROOT/jetpacs-poc" && tools/repositories-root.sh
emacs --version | head -1 && ls "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools"
emacs -Q --batch --eval '(princ (list (sqlite-available-p) (locate-library "track-changes")))'
cd "$JETPACS_REPOSITORIES_ROOT/jetpacs-poc/companion" && ./gradlew :wire:jvmTest --console=plain
cd "$JETPACS_REPOSITORIES_ROOT/jetpacs-poc" && test/run-tests.sh
```

The fifth line is the one that matters most, and it is new. Both values must be
non-nil: `sqlite-available-p` for the receipt store, and `track-changes` for
`ebp-sync`, which exists only from Emacs 30 on. A nil there means the PPA did
not install and `test/run-tests.sh` will die partway through with
*Cannot open load file ... track-changes* rather than a clean gate failure.

The report records how long setup took. Keep the total under roughly 300
seconds: a longer run is not snapshotted, so every session would pay it again.
If the pre-warm crowds the budget, delete `prewarm_gradle` from the script —
it only seeds the Gradle distribution and dependency cache.

## What a cloud session cannot do

- **Device gates.** The `pixel2Api34` managed device needs KVM, and no session
  has an attached device, so the S21 Ultra checks and the desktop-daemon plus
  `adb forward 8765` recipe stay on local hardware. Cloud work ends at the JVM
  suites, lint, and the APK assembly.
- **The platform MCP server.** A session with several repositories starts above
  the clones, so it reads no repository's `.mcp.json` and, from each
  `.claude/settings.json`, only the `enabledPlugins` and
  `extraKnownMarketplaces` keys — not hooks, permission rules, or `env`. The
  `jetpacs-platform` MCP interface named throughout `AGENTS.md` is therefore
  unavailable there. Use the CLI instead: `./gradlew installDist` in
  `jetpacs-platform-tools`, then
  `build/install/jetpacs-platform-tools/bin/jetpacs-platform-tools`. The clone
  is fresh each session so the binary is rebuilt each time, but the setup
  script's pre-warm keeps that rebuild short.
- **`AGENTS.md` as instructions** is version-dependent: Claude reads it only
  while no `CLAUDE.md`, `.claude/CLAUDE.md` or `CLAUDE.local.md` exists in the
  working directory or above it, and only on v2.1.277 or later. In a
  several-repository session the working directory sits *above* the clones, so
  no repository's `AGENTS.md` is in scope at startup at all; a subdirectory's
  file is picked up only once Claude reads a file there. Adding a `CLAUDE.md`
  that imports `AGENTS.md` is the durable form if a session reports it unread.
- **Heavy builds.** Roughly 4 vCPUs, 16 GB RAM, 30 GB disk per session.
  `:app:assembleRelease` with both lint variants is minutes of wall time, which
  is what the raised Bash timeouts above are for.
- **Emacs 31.** The PPA build is 30.2, above the 30.1 floor in
  `docs/REWRITE-PLAN.md` and in every `Package-Requires` header, but not the
  source build this machine keeps in `~/workspace/emacs-31.1`. Anything that
  depends on 31 stays local.

## Emacs 30

Noble ships Emacs 29.3. The suites need 30.1 or better — not as a preference
but because `ebp-sync` requires `track-changes`, which does not exist before 30
— so the PPA is load-bearing and a session that silently falls back to 29.3
cannot run `test/run-tests.sh` at all.

`add-apt-repository` is unusable in this image, for two independent reasons:

1. Its shebang is `/usr/bin/python3`, which the image points at 3.13, while
   `python3-apt` ships `apt_pkg` only for cpython-312. It dies with
   `ModuleNotFoundError: No module named 'apt_pkg'` before opening a socket.
   Invoking it as `python3.12 /usr/bin/add-apt-repository` gets past this.
2. It resolves a `ppa:` shortcut through `api.launchpad.net`, which is not in
   the default allowlist.

Rather than add a host and pin an interpreter, `tools/cloud-setup.sh` writes the
deb822 stanza and the keyring itself, which needs only
`ppa.launchpadcontent.net` and the already-allowed `keyserver.ubuntu.com`:

```bash
curl -fsS "https://keyserver.ubuntu.com/pks/lookup?op=get&options=mr&search=0xF4E48910A020E77056748B745738AE8480447DDF" \
  | gpg --dearmor >/usr/share/keyrings/ubuntuhandbook1-emacs.gpg
cat >/etc/apt/sources.list.d/ubuntuhandbook1-emacs.sources <<'EOF'
Types: deb
URIs: https://ppa.launchpadcontent.net/ubuntuhandbook1/emacs/ubuntu/
Suites: noble
Components: main
Signed-By: /usr/share/keyrings/ubuntuhandbook1-emacs.gpg
EOF
apt-get update && apt-get install -y --no-install-recommends emacs-nox
```

The PPA is `ubuntuhandbook1/emacs`, not the more familiar `kelleyk/emacs`, which
has no `noble` series. The script asserts the candidate version is 30 or newer
before installing, so a PPA that moves on reports itself in the report file
instead of leaving 29.3 in place unexplained.

## Maintenance

The setup script runs on the first session in the environment and its
filesystem result is snapshotted and reused, which is why later sessions start
with the SDK and Emacs already installed and skip the script entirely. It runs
again when you edit the script or the allowed-domains list, and after roughly
seven days when the snapshot expires.

Editing a variable does not reach a running session immediately: it keeps the
values it last read until its VM is next restored from idle or rebuilt, and
picks the change up from then on. To use a new value now, pass it on the
command — `FOO=bar ./gradlew …` — or start a new session. A network-access
change is different and needs neither: existing sessions follow it within about
a minute.

When a session refuses to start, the setup script exited non-zero or outran the
budget. Add `set -x` to the top of the pasted copy to see the failing command,
and keep the `|| true` on anything that is not required for the session to be
useful. A failed Android or Emacs install is reported in
`/opt/jetpacs-cloud/report.txt` and does not fail startup by design — which is
why the first-session checklist reads that file before anything else.

## Verified

Measured in a live `jetpacs` session on 2026-10-20, nine repositories attached.

Confirmed working:

- Clone root `/home/user`, all nine checkouts present, `ebp -> ebp-poc` linked.
- `dl.google.com` reachable and load-bearing: `./gradlew :wire:jvmTest` resolves
  AGP 9.2.1 and passes in about 2 minutes cold.
- Android SDK at `/opt/android-sdk`: platform `android-37.0`, build-tools
  `37.0.0`, platform-tools 37.0.1 — matching `compileSdk = 37` across the
  companion modules. Java 21.0.12.1.
- All four raised-timeout and path variables present in the session.
- Setup finished in 89s of the ~300s budget (22s of installs).

Found broken, and fixed in this revision:

- **`tools/cloud-setup.sh` did not exist** in any attached repository or
  anywhere in their history, so the "maintained source" above was fiction and
  the only copy of the script was the one pasted into the dialog. The file now
  exists, reconstructed from the deployed `/opt/jetpacs-cloud/env.sh`, the
  report format, and the three install logs.
- **Emacs installed as 29.3, below the floor.** `test/run-tests.sh` exits 255
  with *Cannot open load file ... track-changes*. Root cause is the
  `add-apt-repository` trap above; the report's own message blamed
  `ppa.launchpadcontent.net`, which is in fact reachable (HTTP 302). The
  rewritten `install_emacs` reaches candidate `1:30.2-0build1~ubuntu2404`;
  `apt-get update` against the hand-written stanza was confirmed to succeed in
  session, though the final `apt-get install` was not run there.
- **The Gradle pre-warm missed AGP.** `--offline` builds failed on
  `com.android.application:9.2.1` against the 727 MB snapshot cache, and one
  real build took it to 1.6 GB. `prewarm_gradle` now runs `gradlew help` in
  `companion/`, which configures every project and pulls the plugin markers.

Still open:

- The `repositories` and `environment` query parameters are undocumented.
- Whether the setup script can read the dialog's variables is asserted from the
  docs, not measured; the script does not depend on it either way.

Sources: [Configure cloud environments](https://code.claude.com/docs/en/cloud-environments),
[Use Claude Code in the cloud](https://code.claude.com/docs/en/claude-code-on-the-web),
[Settings in cloud sessions](https://code.claude.com/docs/en/settings#settings-in-cloud-sessions),
[How Claude remembers your project](https://code.claude.com/docs/en/memory).
