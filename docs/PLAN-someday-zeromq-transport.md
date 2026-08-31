# Someday/maybe: ZeroMQ transport and Jetpacs Emacs distribution

**Status:** deferred research and implementation plan; not scheduled, approved,
or normative.

**Last reviewed:** 2026-08-27

**Owners if promoted:** the EBP specification repository for wire semantics; the
EBP Emacs endpoint and Companion `:wire` module for runtime adapters; a separate
Jetpacs Emacs packaging repository for the Android Emacs distribution.

This document preserves a possible route from the current JSON-RPC over
Content-Length TCP implementation to a ZeroMQ-based EBP transport. It exists so
the idea and its unanswered questions do not need to be resolved now. Nothing
in this document amends `ebp/SPEC.md`, changes the current architecture, or
authorizes implementation work.

If this work is ever promoted, revalidate every pinned version, Android
toolchain assumption, license conclusion, and source citation first. This is a
research snapshot, not a dependency lock file.

## 1. Why this is deferred

The possible benefits are attractive:

- multipart messages and native binary buffers instead of base64 payloads;
- explicit socket topologies for separating control, commands, and bulk state;
- transport behavior similar to the Jupyter/IPython execution ecosystem;
- less dependence on the behavioral quirks of Emacs `jsonrpc.el`; and
- a potentially cleaner foundation for correlated execution events, streaming,
  cancellation, and other future execution semantics.

The proposal also creates several independent projects and unresolved choices:

1. Jetpacs would need to build, sign, update, and distribute its own Emacs APK.
2. That Emacs must reliably load `emacs-zmq` and libzmq on Android.
3. The Emacs event loop must receive messages without blocking the UI.
4. The Companion needs a compatible Android ZeroMQ implementation.
5. ZeroMQ queues do not replace EBP durability, backpressure, authentication,
   ordering, or authoritative-session rules.
6. A new message envelope may warrant an EBP major version rather than a new
   transport profile alone.
7. A control socket cannot preempt non-yielding Elisp; cancellation would still
   be cooperative unless execution moves out of process.

The first question is therefore not "how should EBP use ZeroMQ?" It is "can a
reproducible, supportable Jetpacs Emacs APK run ZeroMQ correctly on the target
Android devices?"

## 2. Current baseline that must remain true

The current normative authority is `ebp/SPEC.md`. It defines JSON-RPC 2.0 over
the `android-loopback-tcp` profile, with Content-Length framing, Companion bind
on `127.0.0.1:8765`, Emacs dial, mutual HMAC authentication, one authoritative
session, bounded resources, and explicit supersession.

The implementation is correspondingly coupled:

- `emacs/ebp.el` rents core `jsonrpc.el` for framing, dispatch, request IDs,
  continuations, and process integration, with additional EBP bounds and
  workarounds around it.
- Companion `:wire` owns transport, framing, envelopes, authentication, and
  protocol/session handling while `:ebp-kmp` owns storage-neutral durability.
- `docs/ARCHITECTURE-POC3.md` currently requires an `ebp-` module to load using
  vanilla Emacs and built-in dependencies. Bundled `emacs-zmq` would be an
  explicit architecture change.
- The Companion currently targets the official `org.gnu.emacs` package and
  paths under `/data/data/org.gnu.emacs/files` in its manifest, intents,
  onboarding, and tests.

Any future design must preserve these invariants even if its transport changes:

- EBP remains implementation-, platform-, and Jetpacs-neutral.
- Emacs owns application state and application decisions.
- The Companion owns native presentation, negotiated platform integration,
  accepted presentation state, and its durable delivery records.
- EBP carries declarative data and named semantic actions, never executable
  Elisp or another host language.
- Durable acceptance is a receiver transaction conclusion. Enqueueing or
  transmitting a ZeroMQ message is never durable acceptance.
- Every message, queue, retained diagnostic, tree, aggregate, and buffer is
  bounded at the layer that owns the resource.
- Transport choice does not silently weaken authentication, replay,
  supersession, ordering, reconciliation, or offline-delivery semantics.

## 3. Tentative packaging direction

The following choices are useful starting assumptions, not ratified product
decisions:

| Subject | Tentative direction |
|---|---|
| Emacs baseline | GNU Emacs 31.1 |
| Android application ID | `com.calebc42.jetpacs.emacs` |
| Coexistence | Install alongside official GNU Emacs |
| Java/JNI namespace | Retain `org.gnu.emacs`; change application identity only |
| First ABI | `arm64-v8a` |
| First distribution | Signed sideload APK |
| Native module | Bundle a dynamic `emacs-zmq` module |
| libzmq linkage | Statically link libzmq into that module |
| Source ownership | New small packaging repository with pinned upstreams and patches |
| Initial libzmq security | Current EBP HMAC model; no CURVE until interoperability is proven |

The research snapshot used Emacs 31.1, emacs-zmq v1.0.2, libzmq v4.3.5,
JeroMQ 0.6.0, Android API 37, an arm64 API 35 compiler target, and Android NDK
r29 as candidate pins. All must be checked again when the work is activated.

Keeping the internal `org.gnu.emacs` Java and JNI namespace avoids a broad and
risky rename of Java classes and `Java_org_gnu_emacs_*` symbols. A focused
Emacs patch would instead introduce a build-time application ID, use absolute
component names, keep the generated `R` namespace compatible, and replace only
identity-bearing package strings such as provider authorities, notification
actions, task affinities, and package-context lookups.

The preferred native artifact is one `libemacs-zmq.so` containing statically
linked position-independent libzmq. This avoids Android shared-library preload
ordering problems and lets APK inspection prove that no external `libzmq.so`
is required.

## 4. Promotion gates

Do not begin an EBP amendment merely because the native libraries compile.
Promotion should proceed through the following gates, in order.

### Gate 0 — reason to reconsider

Before spending implementation effort, write down the concrete limitation in
the current system. Examples could include binary-payload cost, control traffic
blocked behind bulk state, an execution-stream requirement that is awkward in
JSON-RPC, or unacceptable maintenance cost in the `jsonrpc.el` adapter.

The gate passes only when there is a measured or committed requirement, not
when ZeroMQ is merely architecturally appealing.

### Gate A — reproducible custom Emacs APK

Create a separate repository, provisionally `jetpacs-emacs-android`, containing:

- a source lock manifest with URLs, commits/tags, hashes, and signatures;
- a pinned containerized Android build toolchain;
- a reviewable patch series against an unmodified Emacs release;
- scripts for fetching, building, signing, inspecting, and testing the APK;
- bundled Lisp that loads the native module without downloading or compiling;
- license texts, notices, a source-distribution procedure, and an SBOM; and
- CI artifacts for an unsigned reproducible build and separately signed release.

Build libzmq as static position-independent arm64 code. Enable only the
features needed by the experiment, including draft APIs if the selected
emacs-zmq polling interface still requires them. Disable tests, tools,
documentation, optional transports, and libsodium/CURVE unless later evidence
requires them.

Build emacs-zmq against the exact Emacs `emacs-module.h` and the static libzmq.
Package the resulting module in the APK's ABI library directory. Bundle
`zmq.el` and a small loader that resolves an absolute module path, reports the
Emacs/emacs-zmq/libzmq versions, never performs a network fetch, and fails with
an actionable diagnostic.

Gate A passes on a physical arm64 device only when:

- Jetpacs Emacs installs and runs beside official GNU Emacs;
- each app has independent storage, providers, tasks, and uninstall behavior;
- an upgrade signed with the same key preserves Jetpacs Emacs data;
- `(module-load ...)`, `(require 'zmq)`, and the libzmq version query work
  offline;
- creating and closing a context and socket succeeds repeatedly;
- APK/ELF inspection finds only expected ABI artifacts and no `libzmq.so`
  `DT_NEEDED` entry;
- native objects satisfy the target Android page-alignment requirements; and
- a clean pinned environment can reproduce the unsigned build.

Failure ends the investigation before any EBP spec or runtime rewrite.

### Gate B — Android event-loop and interoperability proof

Build a deliberately non-EBP spike between Emacs/emacs-zmq and a small Android
JeroMQ peer. Test loopback bind/connect, multipart JSON, raw binary frames,
Unicode, explicit size rejection, bounded high-water marks, nonblocking send
and receive, `LINGER=0`, socket/context recreation, and clean shutdown.

Start with timer-driven nonblocking polling integrated into the Emacs event
loop. If it is inefficient or unreliable, evaluate a minimal native polling
bridge. Native worker threads must not invoke arbitrary Lisp callbacks. Do not
assume emacs-zmq's subprocess-based asynchronous mode works on Android.

Exercise foreground/background transitions, activity and process death,
reconnect, sustained traffic, queue saturation, and thread/file-descriptor
leaks. Shut down JeroMQ using explicit socket and context closure rather than
thread interruption.

Gate B passes only when the Emacs UI remains responsive, every queue is
bounded, lifecycle recreation is reliable, no resource leak is observed, and
JeroMQ/libzmq multipart behavior is interoperable on supported devices.

Also demonstrate the cancellation boundary: control traffic can bypass a
saturated data lane while Emacs is yielding, but it cannot interrupt
non-yielding Elisp.

### Gate C — protocol decision

Only after Gates A and B pass should the EBP owners decide whether to:

1. keep JSON-RPC semantics and define only a ZeroMQ transport/framing profile;
2. define an EBP-native request/reply/event envelope over ZeroMQ; or
3. retain the current transport and use ZeroMQ only for a narrower execution or
   binary-data facility.

This decision requires an ADR and an EBP major-version analysis. It must not be
made implicitly in an implementation adapter.

## 5. Candidate EBP v4 design, if Gate C selects a rewrite

This section is a design seed for later evaluation, not a specification.

### 5.1 Socket topology

Use three Companion-bound `ROUTER` sockets and Emacs `DEALER` sockets:

| Lane | Candidate responsibility |
|---|---|
| Command | Requests, terminal replies, durable actions/replay, dialogs, and platform operations |
| State | Latest-wins surfaces, ordered editor streams, and binary attachments |
| Control | Authentication, lane attachment, cancellation, supersession, ping/pong, and shutdown |

Do not copy Jupyter's five-channel topology without EBP-specific evidence.
Avoid PUB/SUB for authoritative or durable traffic and avoid REQ/REP's
lockstep state machine.

A possible loopback layout is base port 8765 with command/state/control on
base, base+1, and base+2. Listener startup should be atomic: inability to bind
any required lane fails the whole listener. The pairing descriptor would carry
the selected base port.

### 5.2 Candidate multipart envelope

One possible message shape is:

```text
[
  magic-and-version,
  signature,
  header-json,
  metadata-json,
  content-json,
  zero-or-more-binary-buffers
]
```

The header would include protocol major, session ID, unique message ID,
message class, method/event name, lane, and optional parent/root correlation
IDs. Metadata would describe each binary buffer's index, media type, byte
length, and digest. Application content would remain RFC 8259 JSON.

The protocol must set maximum frame count, individual-frame bytes, total
multipart bytes, JSON depth/members, buffer count/bytes, and per-lane
application queue bytes before an implementation allocates or parses
untrusted content.

### 5.3 Authentication and lane attachment

A candidate design retains the explicit pairing token and mutual nonces. It
derives a per-session key using HKDF-SHA256 and authenticates every message with
HMAC-SHA256 over an unambiguous length-prefixed transcript of every frame other
than the signature frame.

Emacs should use the same random DEALER identity for all lanes. Authentication
would begin on the command lane; state and control would then send signed lane
attachment messages bound to the pairing identity, both nonces, session ID,
lane name, and actual routing identity. The session must not become active
until every required lane is attached.

The exact derivation strings, byte encodings, transcript construction, and
failure behavior require normative prose and byte-exact goldens. This design
provides authentication and integrity, not encryption. A future encrypted
profile is a separate decision.

### 5.4 Delivery and ordering semantics

Set finite `SNDHWM`, `RCVHWM`, and `MAXMSGSIZE`; use `IMMEDIATE=1`, `LINGER=0`,
and Companion `ROUTER_MANDATORY` where supported. Send without indefinite
blocking and retain unsent traffic in application-owned bounded queues so the
handoff boundary is explicit.

ZeroMQ acceptance into an internal queue is not an EBP terminal result.
Durable acceptance is reported only after the receiver transaction commits.
Durable `event_id` replay must survive sessions and transport reconnection.

There is no implicit ordering across sockets. State-before-action flushes,
editor sequence numbers, revision preconditions, replay ordering, and any
cross-lane dependency must remain explicit EBP semantics. A control cancel is
cooperative and has no preemption guarantee.

## 6. Implementation sequence after protocol ratification

### 6.1 Separate semantics from the current transport

Before adding ZeroMQ, extract a transport-neutral dispatcher and typed outcomes
without changing observable v3 behavior. The current adapter and its complete
conformance suite remain the regression oracle.

On the Companion, keep storage-neutral durability in `:ebp-kmp`. Keep sockets,
multipart validation, lane lifecycle, and JeroMQ in `:wire`. Do not let JeroMQ
types enter reducers, Room adapters, renderers, or application policy.

On Emacs, separate message/session semantics from `jsonrpc.el`, then add an
`ebp-zmq.el` adapter responsible for contexts, sockets, polling, deadlines,
pending requests, lane queues, cancellation, and close behavior. Application
state, action registries, builders, and the durable inbox remain independent.

### 6.2 Run v3 and the candidate profile side by side

Implement the new profile behind explicit configuration. Do not silently
downgrade after failed authentication. Existing pairings should store their
allowed protocol/transport profile and require explicit re-pairing or confirmed
upgrade when authentication or channel binding changes.

Reach semantic parity before making the new profile the default. Remove the v3
JSON-RPC implementation only after a defined migration window, device dogfood,
durability crash tests, and a stale-name/dependency audit.

### 6.3 Integrate the custom Emacs identity into Jetpacs

Update package visibility, launch intents, onboarding, private-path guidance,
and tests to target `com.calebc42.jetpacs.emacs`. Detect a minimum compatible
Jetpacs Emacs build and native-module version. Do not use shared UID or assume
access to another application's private data. Pairing remains explicit.

## 7. Verification required before cutover

### Protocol and conformance

- byte-exact multipart and HMAC goldens;
- authentication and lane-attachment failures;
- request/reply/event correlation and duplicate IDs;
- malformed, missing, reordered, and excess frames;
- oversize rejection before JSON parsing or large allocation;
- binary descriptor/digest mismatch;
- session supersession and stale-lane traffic;
- cross-lane ordering and dependency tests;
- bounded HWM and application-queue overload behavior; and
- cancellation before send, after send, during handling, and after conclusion.

### Durability crash matrix

- crash before receiver commit;
- crash after commit but before terminal reply;
- disconnect while sending the terminal reply;
- reconnect and replay of the same durable event;
- no false acceptance and no duplicate durable effect; and
- state-before-action behavior across lane failure and reconnection.

### Android/device behavior

- foreground, background, Doze, activity recreation, and process death;
- Companion and Jetpacs Emacs upgrades;
- two Emacs installations and authoritative-session supersession;
- offline queue delivery and reconnect;
- editor traffic under concurrent surface/binary traffic;
- large image/binary payloads at and beyond configured limits;
- CPU, memory, battery, latency, APK-size, thread, and file-descriptor impact;
  and
- absence of Emacs UI jank under sustained traffic.

Run `ebp/validate.py`, affected wire conformance suites, focused and broad ERT,
warning-as-error Elisp byte compilation, Companion unit tests and APK assembly,
then real-device tests. Android compilation alone is not acceptance evidence.

## 8. Questions intentionally left open

These questions are the reason this is a someday/maybe plan. Answer them only
when the relevant gate is active.

### Product and distribution

- Is maintaining a Jetpacs-specific Emacs an acceptable permanent product
  responsibility?
- How will users install, update, roll back, and verify it?
- Is sideload-only distribution sufficient, and for how long?
- What is the signing-key custody and disaster-recovery policy?
- Which Android versions, ABIs, and device vendors must be supported?
- How quickly must Jetpacs track GNU Emacs security and Android fixes?

### Build and licensing

- Which exact JDK, SDK, NDK, and container image produce the supported build?
- Can the APK and all native objects be reproducibly built and audited?
- What source bundle, written offer, notices, and patch publication are needed
  for the exact pinned Emacs, emacs-zmq, and libzmq licenses?
- Does enabling draft libzmq API create an acceptable compatibility burden?

### Runtime

- Does emacs-zmq's polling model behave correctly on Android Emacs?
- Is timer polling adequate, or is a native event-loop bridge required?
- Does JeroMQ interoperate with every libzmq feature the profile would use?
- What happens across Android process suspension while messages sit in native
  queues?
- Can native and application queue memory be bounded and measured reliably?
- Is cooperative cancellation sufficient for the desired execution semantics?

### Protocol

- Is ZeroMQ only a transport profile, or does it justify replacing JSON-RPC?
- Are three physical lanes useful enough to justify their lifecycle and
  ordering complexity?
- Which messages benefit from binary frames in actual workloads?
- Should the initial profile retain HMAC, adopt CURVE, or use a different local
  authenticated channel?
- What is the exact signed transcript and key-derivation construction?
- How are partial lane attachment, lane restart, and port conflicts recovered?
- Which ordering dependencies must be made explicit across lanes?
- Can a migration preserve pairings, or must every endpoint re-pair?

### Architecture and maintenance

- Does the "vanilla Emacs built-ins" rule change globally, or only for the
  Android reference distribution?
- Can v3 and v4 share enough typed semantics to avoid two protocol engines?
- At what point is `jsonrpc.el` actually removable?
- Would a smaller execution-specific ZeroMQ subsystem yield most of the value
  without rewriting the general EBP transport?

## 9. Activation checklist

Promote this document into active work only when all of the following are true:

- [ ] A concrete current-system limitation and success metric are recorded.
- [ ] An owner accepts ongoing custom-Emacs release responsibility.
- [ ] Active EBP semantic work has landed or reached a coordinated freeze.
- [ ] A clean branch/worktree and separate packaging repository are available.
- [ ] Gate A has a funded device/build spike with explicit stop conditions.
- [ ] No spec change is planned before Gates A and B pass.
- [ ] Gate C has named EBP decision-makers and an ADR location.
- [ ] Migration, rollback, and removal criteria are defined before v4 coding.

Until then, the current `android-loopback-tcp` JSON-RPC implementation remains
the only authority and this plan should receive no implementation commits.

## 10. Research pointers

- Normative current protocol: `ebp/SPEC.md`, especially Sections 5–7 and the
  authentication, session, limits, and durability sections.
- Current architecture: `docs/ARCHITECTURE-POC3.md` and
  `docs/REWRITE-PLAN.md`.
- Current Emacs adapter: `emacs/ebp.el`.
- Current Companion adapter: `companion/wire/src/jvmMain/kotlin/com/calebc42/ebp/wire/`.
- GNU Emacs Android build instructions: upstream `java/INSTALL` and
  `java/Makefile.in` for the selected release.
- GNU Emacs Android releases: <https://ftp.gnu.org/gnu/emacs/android/>.
- emacs-zmq: <https://github.com/nnicandro/emacs-zmq>.
- libzmq: <https://github.com/zeromq/libzmq>.
- JeroMQ: <https://github.com/zeromq/jeromq>.
- Android NDK releases: <https://developer.android.com/ndk/downloads>.
- Useful design comparison, not an EBP template: local checkouts
  `/home/calebc42/workspace/jupyter_client` and
  `/home/calebc42/workspace/ipython`.
