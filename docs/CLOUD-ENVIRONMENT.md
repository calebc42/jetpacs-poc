# Cloud environment

Configuration for Claude Code cloud sessions on this workspace. The dialog at
claude.ai/code asks for four things; this document holds the value for each and
`tools/cloud-setup.sh` is the maintained source of the longest one.

A cloud session is a fresh Ubuntu 24.04 x86_64 VM with the attached GitHub
repositories cloned into it. Nothing from this machine carries over: not
`~/.bashrc`, not a local `.env`, not `~/.local/share/android-sdk`, not the
user-scope MCP registration. Only what is committed to an attached repository
and what the environment itself installs exists in the session.

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
The PPA that carries Emacs 30 for noble serves its packages from
`ppa.launchpadcontent.net`; `launchpad.net` itself, `*.ubuntu.com`,
`services.gradle.org`, `plugins.gradle.org`, `repo1.maven.org` and
`raw.githubusercontent.com` are already in the default list.

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
```

Add `JETPACS_REPOSITORIES_ROOT=<path>` once the first session has reported the
clone root. Until then the profile fragment resolves it per shell, falling back
to a bounded `find`; pinning it removes that search and makes the variable
authoritative, exactly as `tools/repositories-root.sh` and
`companion/settings.gradle.kts` already honour it locally.

The setup script cannot read any of these: the dialog's variables reach the
commands Claude runs, not the script that runs before Claude starts. That is
why `/opt/android-sdk` appears literally in both places.

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
| Elisp suites (`test/run-tests.sh`) | the four above plus `ebp.el`, `ebp-org`, `bloom-native` |
| Applet work | add `glasspane`, `orgzly-native`, `harp-native`, `jetpacs-authoring` as the task names them |

A missing checkout is a loud failure, not a silent one: `settings.gradle.kts`
errors with *Set JETPACS_REPOSITORIES_ROOT to the directory containing the EBP
checkouts*, and `test/run-tests.sh` exits 2 naming the first missing file.

Private repositories need the Claude GitHub App installed on each one, or a
`gh` token sent to your account with `/web-setup` from the terminal.

Selecting seven repositories by hand each time is avoidable: claude.ai/code
accepts `repositories` and `environment` as query parameters, so one link per
task shape pre-fills the form.

```text
https://claude.ai/code?environment=jetpacs&repositories=calebc42/jetpacs-poc,calebc42/ebp-poc
https://claude.ai/code?environment=jetpacs&repositories=calebc42/jetpacs-poc,calebc42/ebp-poc,calebc42/ebp-kmp,calebc42/ebp-compose
https://claude.ai/code?environment=jetpacs&repositories=calebc42/jetpacs-poc,calebc42/ebp-poc,calebc42/ebp-kmp,calebc42/ebp-compose,calebc42/ebp.el,calebc42/ebp-org,calebc42/grove-native
```

## First session

Ask Claude to run these, in this order; each line answers one question the
configuration above leaves open.

```bash
cat /opt/jetpacs-cloud/report.txt
echo "$JETPACS_REPOSITORIES_ROOT" && ls "$JETPACS_REPOSITORIES_ROOT"
cd "$JETPACS_REPOSITORIES_ROOT/jetpacs-poc" && tools/repositories-root.sh
emacs --version | head -1 && ls "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools"
cd "$JETPACS_REPOSITORIES_ROOT/jetpacs-poc/companion" && ./gradlew :wire:jvmTest --console=plain
cd "$JETPACS_REPOSITORIES_ROOT/jetpacs-poc" && test/run-tests.sh
```

The report records how long setup took. Keep the total under roughly 300
seconds: a longer run is not snapshotted, so every session would pay it again.
If the pre-warm crowds the budget, delete `prewarm_gradle` from the script —
it only seeds the Gradle distribution and dependency cache.

## What a cloud session cannot do

- **Device gates.** The `pixel2Api34` managed device needs KVM, and no session
  has an attached device, so the S21 Ultra checks and the desktop-daemon plus
  `adb forward 8765` recipe stay on local hardware. Cloud work ends at the JVM
  suites, lint, and the APK assembly.
- **The platform MCP server.** A session with several repositories loads no
  repository's `.claude/settings.json` hooks and no `.mcp.json`, so the
  `jetpacs-platform` MCP interface named throughout `AGENTS.md` is unavailable
  there. Use the CLI instead: `./gradlew installDist` in
  `jetpacs-platform-tools`, then
  `build/install/jetpacs-platform-tools/bin/jetpacs-platform-tools`. The clone
  is fresh each session so the binary is rebuilt each time, but the setup
  script's pre-warm keeps that rebuild short.
- **`AGENTS.md` as instructions** is version-dependent: Claude reads it only
  while no `CLAUDE.md` exists at or above the working directory, and only on
  v2.1.277 or later. Adding a `CLAUDE.md` that imports `AGENTS.md` is the
  durable form if a session reports it unread.
- **Heavy builds.** Roughly 4 vCPUs, 16 GB RAM, 30 GB disk per session.
  `:app:assembleRelease` with both lint variants is minutes of wall time, which
  is what the raised Bash timeouts above are for.
- **Emacs 31.** The PPA build is 30.x, above the 30.1 floor in
  `docs/REWRITE-PLAN.md` but not the source build this machine keeps in
  `~/workspace/emacs-31.1`. Anything that depends on 31 stays local.

## Maintenance

The setup script runs on the first session in the environment and its
filesystem result is snapshotted and reused, which is why later sessions start
with the SDK and Emacs already installed and skip the script entirely. It runs
again when you edit the script or the allowed-domains list, and after roughly
seven days when the snapshot expires. Editing a variable does not reach a
running session until its VM is rebuilt; pass the value on the command instead,
or start a new session.

When a session refuses to start, the setup script exited non-zero or outran the
budget. Add `set -x` to the top of the pasted copy to see the failing command,
and keep the `|| true` on anything that is not required for the session to be
useful. A failed Android or Emacs install is reported in
`/opt/jetpacs-cloud/report.txt` and does not fail startup by design.

Sources: [Configure cloud environments](https://code.claude.com/docs/en/cloud-environments),
[Use Claude Code in the cloud](https://code.claude.com/docs/en/claude-code-on-the-web),
[How Claude remembers your project](https://code.claude.com/docs/en/memory).
