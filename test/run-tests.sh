#!/bin/sh
# elisp suites: delineation guard, then the ERT conformance suite.
set -e
cd "$(dirname "$0")/.."

jetpacs_root=$(pwd)
EBP_SPEC_DIR=${EBP_SPEC_DIR:-"$jetpacs_root/../ebp-poc/ebp"}
EBP_EL_DIR=${EBP_EL_DIR:-"$jetpacs_root/../ebp-poc/ebp.el"}
EBP_ORG_DIR=${EBP_ORG_DIR:-"$jetpacs_root/../ebp-poc/ebp-org"}
EBP_KMP_DIR=${EBP_KMP_DIR:-"$jetpacs_root/../ebp-poc/ebp-kmp"}
EBP_COMPOSE_DIR=${EBP_COMPOSE_DIR:-"$jetpacs_root/../ebp-poc/ebp-compose"}
GLASSPANE_MATERIAL3_DIR=${GLASSPANE_MATERIAL3_DIR:-"$jetpacs_root/../glasspane-material3"}
JETPACS_COMPONENTS_DIR=${JETPACS_COMPONENTS_DIR:-"$jetpacs_root/../jetpacs-components"}
JETPACS_AUTHORING_DIR=${JETPACS_AUTHORING_DIR:-"$jetpacs_root/../jetpacs-authoring"}
JETPACS_AUTOMATIONS_DIR=${JETPACS_AUTOMATIONS_DIR:-"$jetpacs_root/../jetpacs-automations"}
JETPACS_COMPONENT_CATALOG_DIR=${JETPACS_COMPONENT_CATALOG_DIR:-"$jetpacs_root/../jetpacs-component-catalog"}
GLASSPANE_DIR=${GLASSPANE_DIR:-"$jetpacs_root/../glasspane"}
GROVE_DIR=${GROVE_DIR:-"$jetpacs_root/../../../grove"}
export EBP_SPEC_DIR EBP_EL_DIR EBP_ORG_DIR EBP_KMP_DIR EBP_COMPOSE_DIR
export GLASSPANE_MATERIAL3_DIR JETPACS_COMPONENTS_DIR JETPACS_AUTHORING_DIR
export JETPACS_AUTOMATIONS_DIR JETPACS_COMPONENT_CATALOG_DIR
export GLASSPANE_DIR
export GROVE_DIR

for dependency in \
  "$EBP_SPEC_DIR/contract.json" \
  "$EBP_EL_DIR/lisp/ebp.el" \
  "$EBP_ORG_DIR/lisp/ebp-org.el" \
  "$EBP_KMP_DIR/wire/build.gradle.kts" \
  "$EBP_COMPOSE_DIR/renderer/model/build.gradle.kts" \
  "$GROVE_DIR/elisp/grove.el"; do
  test -r "$dependency" || {
    echo "workspace dependency missing: $dependency" >&2
    exit 2
  }
done

# The workspace may contain ignored developer bytecode.  Every suite must read
# the current source without deleting or overwriting those unrelated artifacts.
# All invocations below start with `emacs -Q --batch'; consume that common
# prefix here and inject the source-preference setting before any test is loaded.
emacs() {
  if [ "$#" -ge 2 ] && [ "$1" = "-Q" ] && [ "$2" = "--batch" ]; then
    shift 2
  fi
  command emacs -Q --batch --eval '(setq load-prefer-newer t)' \
    -L "$EBP_EL_DIR/lisp" \
    -L "$EBP_ORG_DIR/lisp" \
    -L "$JETPACS_AUTHORING_DIR/lisp" \
    -L "$GLASSPANE_MATERIAL3_DIR/lisp/glasspane-material3" \
    -L "$GLASSPANE_MATERIAL3_DIR/lisp/m3-catalog" \
    -L "$GLASSPANE_MATERIAL3_DIR/lisp" \
    -L "$JETPACS_COMPONENTS_DIR/lisp/jetpacs-components" \
    -L "$JETPACS_AUTOMATIONS_DIR/lisp" \
    -L "$JETPACS_COMPONENT_CATALOG_DIR/lisp" \
    -L "$GLASSPANE_DIR" \
    -L "$GROVE_DIR/elisp" "$@"
}

# Upstream and extension owners gate their own source. Jetpacs then runs the
# integration suites against those exact neighboring working trees.
EBP_SPEC_DIR="$EBP_SPEC_DIR" "$EBP_EL_DIR/test/run-tests.sh"
EBP_EL_DIR="$EBP_EL_DIR" "$EBP_ORG_DIR/test/run-tests.sh"
"$JETPACS_AUTHORING_DIR/test/run-tests.sh"
"$GLASSPANE_MATERIAL3_DIR/test/run-tests.sh"
"$JETPACS_COMPONENTS_DIR/test/run-tests.sh"
"$JETPACS_AUTOMATIONS_DIR/test/run-tests.sh"
"$JETPACS_COMPONENT_CATALOG_DIR/test/run-tests.sh"
JETPACS_ROOT="$jetpacs_root" \
EBP_EL_ROOT="$EBP_EL_DIR" \
EBP_ORG_ROOT="$EBP_ORG_DIR" \
  "$GROVE_DIR/test-elisp/run-tests.sh"

python3 "$EBP_KMP_DIR/tools/gen-vocabulary.py" \
  --spec-dir "$EBP_SPEC_DIR" \
  --output "$EBP_KMP_DIR/wire/src/jvmMain/kotlin/com/calebc42/ebp/wire/Vocabulary.kt" \
  --check
python3 tools/gen-jetpacs-vocabulary.py --check
python3 "$EBP_COMPOSE_DIR/test/test_renderer_extension_generator.py"

# Amendment #181: one deterministic 10,000-case corpus must agree across the
# Python reference receiver, Kotlin receiver (the wire suite), and public
# Elisp author.  This runner covers the first and third against identical rows.
sh test/run-text-input-corpus.sh

# The startup-layout contract: one selected HOME, one marked early-init
# redirect, one marked normal-init seam, and every managed artifact below
# ~/.emacs.d/jetpacs. This is a hermetic fake-device run of the exact Termux
# installer shipped by both desktop and in-app onboarding.
bash test/onboarding-layout-test.sh

# Delineation guard (REWRITE-PLAN "The ebp.el boundary", widened by the
# ratified naming rule of 2026-08-06): EVERY ebp file loads alone, in a
# process of its own, and defines nothing jetpacs-flavored.  The offender
# predicate covers functions, variables, faces, error conditions, group
# documentation, custom-group parent links (what a stray `:group (quote
# jetpacs)' actually leaves behind) and loaded features — a prefix is a
# claim about the require closure, not a spelling convention.
#
# A GLOB, not a name list, for the reason the byte-compile loop below
# (:58-66) records from the other direction: its predecessor was a
# hand-kept 16-name list that silently omitted jetpacs-modus.el, so
# "unguarded" was the default for anything added later.  Coverage here is
# derived from the canonical ebp.el checkout and the count is asserted, so a new file
# is guarded the day it lands and a glob that matches nothing cannot pass
# by saying nothing.
ebp_guard_count=0
for f in "$EBP_EL_DIR"/lisp/ebp*.el; do
  EBP_GUARD_FEATURE="$(basename "$f" .el)" \
  emacs -Q --batch -L "$EBP_EL_DIR/lisp" --eval '
(progn
  (require (intern (getenv "EBP_GUARD_FEATURE")))
  (let (offenders)
    (mapatoms
     (lambda (sym)
       (when (and (string-prefix-p "jetpacs" (symbol-name sym))
                  (or (fboundp sym) (boundp sym) (facep sym)
                      (get sym (quote error-conditions))
                      (get sym (quote group-documentation))
                      (get sym (quote custom-group))
                      (memq sym features)))
         (push sym offenders))))
    (when offenders
      (message "delineation guard: jetpacs symbols after loading %s.el: %S"
               (getenv "EBP_GUARD_FEATURE") offenders)
      (kill-emacs 1))
    (message "delineation guard: %s.el loads alone, no jetpacs symbols"
             (getenv "EBP_GUARD_FEATURE"))))'
  ebp_guard_count=$((ebp_guard_count + 1))
done
if [ "$ebp_guard_count" -lt 5 ]; then
  echo "delineation guard: ebp.el/lisp/ebp*.el matched $ebp_guard_count files, expected at least 5" >&2
  exit 1
fi

# Dependency direction is EBP -> Jetpacs -> downstream apps.  Foundation
# Elisp must not name a downstream app even in a soft require or declaration;
# keeping the source name-free also prevents documentary assumptions from
# turning into the next reverse edge.  App code, integration tests, and plans
# are outside this deliberately narrow boundary.
if rg -n -i 'glasspane' emacs/*.el; then
  echo "layering guard: foundation Elisp must not know Glasspane" >&2
  exit 1
fi

# Byte-compile guard: free-variable and undefined-function warnings are
# treated as errors (catches unescaped-quote docstrings and typos before
# they reach a device).  Outputs belong to this test run, never to the source
# tree: a developer may already have .elc files there, and verification must
# neither overwrite nor delete artifacts it does not own.
jetpacs_compile_dest=$(mktemp -d)
trap 'rm -rf "$jetpacs_compile_dest"' EXIT
emacs -Q --batch -L emacs \
  --eval "(progn
             (setq load-prefer-newer t)
             (setq byte-compile-error-on-warn t)
             (setq byte-compile-dest-file-function
                   (lambda (file)
                     (expand-file-name
                      (concat (file-name-nondirectory file) \"c\")
                      \"$jetpacs_compile_dest\"))))" \
  -f batch-byte-compile "$EBP_EL_DIR/lisp/ebp.el"

# Same guard for EVERY application-layer module. A glob, not a list: the
# previous hand-kept 16-name list silently omitted jetpacs-modus.el, and a
# module added later would have been unguarded by default — the exact
# docstring-quote bug class this guard exists for would then reach a device.
# ebp.el compiles twice (here and above); two seconds buys never maintaining
# the list again.
# Extracted app owners run their warning-as-error compile gates above.  This
# local glob covers Jetpacs foundation plus its packaged-app manifest, the only
# application source still owned by this checkout.
for f in emacs/*.el emacs/apps/packaged-apps/*.el; do
  emacs -Q --batch -L emacs \
    --eval "(progn
               (setq load-prefer-newer t)
               (setq byte-compile-error-on-warn t)
               (setq byte-compile-dest-file-function
                     (lambda (file)
                       (expand-file-name
                        (concat (file-name-nondirectory file) \"c\")
                        \"$jetpacs_compile_dest\"))))" \
    -f batch-byte-compile "$f"
done

# Storage-independent SPEC 14.4 receipt/work contract and its built-in
# Emacs 30.1 SQLite backend.  No Jetpacs or ebp.el dependency.
emacs -Q --batch -L emacs -l "$EBP_EL_DIR/test/ebp-store-test.el" \
  -f ert-run-tests-batch-and-exit

# GUI-over-Lisp Automations: inert one-form reader and closed interpreter;
# separate durable inbox/frozen revisions; Inspector/Tree/Lisp projections.
emacs -Q --batch -L emacs -l "$JETPACS_AUTOMATIONS_DIR/test/jetpacs-automation-model-test.el" \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch -L emacs -l "$JETPACS_AUTOMATIONS_DIR/test/jetpacs-automation-runtime-test.el" \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch -L emacs \
  -l "$JETPACS_AUTOMATIONS_DIR/test/jetpacs-automations-test.el" \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch -L emacs -l "$EBP_EL_DIR/test/ebp-wire-test.el" \
  -f ert-run-tests-batch-and-exit

# Section 19 buffer bridge on built-in track-changes.el (PLAN-poc1-parity
# P1); Jetpacs-agnostic like ebp.el itself.
emacs -Q --batch -L emacs -l "$EBP_EL_DIR/test/ebp-sync-test.el" \
  -f ert-run-tests-batch-and-exit

# JA-4 exit gate: the org engine — refs/tokens (D-4), resolve guards,
# the cache key, mutations, and (from O2/O3) the query grammar and
# capture primitives.  It sits in THIS block by the placement rule the
# ebp-complete comment below records from the other side: a suite lives
# with the pure ones iff its process loads no application layer.  This
# one asserts that about itself (ebp-org-suite-loads-no-application-
# layer), so the rule is checked rather than remembered.
emacs -Q --batch -L emacs -l "$EBP_ORG_DIR/test/ebp-org-test.el" \
  -f ert-run-tests-batch-and-exit

# Generated authoring metadata: field kinds, ActionDescriptor policy, and
# Editor toolbar vocabulary retain the exact Elisp shapes from contract.json.
emacs -Q --batch -L emacs \
  --eval '(setq load-prefer-newer t)' \
  -l test/jetpacs-vocabulary-authoring-test.el \
  -f ert-run-tests-batch-and-exit

# Application-layer builder suite (jetpacs-widgets; requires ebp, so it is
# absent from the delineation guard above).
emacs -Q --batch -L emacs -l test/jetpacs-widgets-test.el \
  -f ert-run-tests-batch-and-exit

# JC-0 floor exit gate (docs/SPEC-JC-0-floor.md section 7).
emacs -Q --batch -L emacs -l test/jetpacs-floor-test.el \
  -f ert-run-tests-batch-and-exit

# JC-1 Tier-0 renderer exit gate (golden + budgets + D2 actions).
emacs -Q --batch -L emacs -l test/jetpacs-buffer-test.el \
  -f ert-run-tests-batch-and-exit

# JC-2 results/tablist skins exit gate.
emacs -Q --batch -L emacs -l test/jetpacs-results-test.el \
  -f ert-run-tests-batch-and-exit

# JC-3a/3c sections + comint skins exit gate.
emacs -Q --batch -L emacs -l test/jetpacs-sections-test.el \
  -f ert-run-tests-batch-and-exit

# JC-3b hypertext skin exit gate (golden + image resolver + nav).
emacs -Q --batch -L emacs -l test/jetpacs-hypertext-test.el \
  -f ert-run-tests-batch-and-exit

# JC-4a prompt floor exit gate (advice gating, dialog specs, conclusions).
emacs -Q --batch -L emacs -l test/jetpacs-dialog-test.el \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch -L emacs -l test/jetpacs-transient-test.el \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch -L emacs -l test/jetpacs-package-browser-test.el \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch -L emacs -l test/jetpacs-settings-test.el \
  -f ert-run-tests-batch-and-exit

# GR-7b promotion: pure locale-stable date arithmetic is Jetpacs-owned,
# independently gated rather than hidden inside a downstream app suite.
emacs -Q --batch -L emacs -l test/jetpacs-dates-test.el \
  -f ert-run-tests-batch-and-exit

# The relocated org/calendar settings sections + phone-generic seeding
# (PLAN-jetpacs-debt-and-scaffold §3 step 1 + the step-4 ruling).
emacs -Q --batch -L emacs -l test/jetpacs-org-settings-test.el \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch -L emacs -l test/jetpacs-project-sql-test.el \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch -L emacs -l test/jetpacs-apps-test.el \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch -L emacs -l test/jetpacs-app-store-test.el \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch -L emacs -l test/jetpacs-home-test.el \
  -f ert-run-tests-batch-and-exit

# Public installation boundary: package code may live anywhere on load-path,
# `(require 'jetpacs)' composes it, existing init values win, and mutable state
# remains below ~/.emacs.d/jetpacs rather than beside package code.
emacs -Q --batch -L emacs -l test/jetpacs-entry-test.el \
  -f ert-run-tests-batch-and-exit

# Exercise the real package-vc boundary from a clean local checkout.  This
# catches package metadata, generated-autoload load-path setup, recursive
# compilation, and accidental inclusion of experimental source as one contract.
bash test/package-vc-install-test.sh

# JC-5 completion harvester exit gate (the :edit-complete-function seam).
# ebp-complete.el is wire-and-Emacs only, but this suite also pins
# jetpacs-connect's fboundp seam, so it loads the application layer and
# belongs down here rather than with the ebp-agnostic suites above.
emacs -Q --batch -L emacs -l test/jetpacs-ebp-complete-integration-test.el \
  -f ert-run-tests-batch-and-exit

# JA-1 theme + modus exit gate (docs/PLAN-jetpacs-apps.md).
emacs -Q --batch -L emacs -l test/jetpacs-theme-test.el \
  -f ert-run-tests-batch-and-exit

# The promoted theme-picker scaffold (PLAN-jetpacs-debt-and-scaffold §3
# step 3, reversing FOUNDATION-GAPS #8): scaffold-alone coverage, moved
# from the downstream suite with the scaffold; concrete provider
# instantiations stay with their owning applets.
emacs -Q --batch -L emacs -l test/jetpacs-theme-picker-test.el \
  -f ert-run-tests-batch-and-exit

# JA-1 reminders wrapper exit gate.
emacs -Q --batch -L emacs -l test/jetpacs-device-test.el \
  -f ert-run-tests-batch-and-exit

# JA-1 clip view exit gate (golden: test/goldens/clip-view.golden).
emacs -Q --batch -L emacs -l test/jetpacs-clip-test.el \
  -f ert-run-tests-batch-and-exit

# JA-2 teardown exit gate (selector mandatory: the loopback harness file
# defines its own suite too).
emacs -Q --batch -L emacs -l "$EBP_EL_DIR/test/ebp-wire-test.el" -l test/jetpacs-teardown-test.el \
  --eval '(ert-run-tests-batch-and-exit "^jetpacs-teardown-")'

# JA-2 buffer-view host exit gate.
emacs -Q --batch -L emacs -l test/jetpacs-navigate-test.el \
  -f ert-run-tests-batch-and-exit

# JA-2 chrome kit exit gate.
emacs -Q --batch -L emacs -l test/jetpacs-chrome-test.el \
  -f ert-run-tests-batch-and-exit

# Devtools exit gate: the push-loop profiler (build wall clock, push
# sizes, last spec, the storm predicate) and the failure flight
# recorder — keeping locally, bounded, and OFF BY DEFAULT what SPEC
# 23.3 scrubs off the wire, with the wire's scrubbing asserted too.
emacs -Q --batch -L emacs -l test/jetpacs-devtools-test.el \
  -f ert-run-tests-batch-and-exit

# The org ADAPTER's gate (the JA-4 engine suite moved UP to the
# Jetpacs-free block with the engine itself).  jetpacs-org.el exports no
# callable symbol, so this suite asserts EFFECTS: it drives the floor's
# own jetpacs-teardown-owner and looks for the swept token on the other
# side (the add-hook is the whole file, and calling the sweep directly —
# what every other suite does — stays green with it deleted); it proves
# the floor's reset seam still reaches ebp-org-reset, which the shim puts
# on jetpacs-reset-functions because the engine may not name a floor
# symbol; and it pins the render -> dialogs -> shim chain that carries
# the registrations onto the device.  Three jobs, all three SILENT.
emacs -Q --batch -L emacs -l test/jetpacs-org-test.el \
  -f ert-run-tests-batch-and-exit

# Vulpea arm (Tier-1 staging — jetpacs-org-vulpea.el, never required by
# base): synthetic-struct accessor semantics + index-evaluable routing.
emacs -Q --batch -L emacs -l test/jetpacs-org-vulpea-test.el \
  -f ert-run-tests-batch-and-exit

# JA-5b exit gate: the org render skin — the golden, native-upgrade
# budget charging (A2 cells, frame bytes), data:-URI images through the
# root allowlist, the span-action arms, the org_parser trap fixture,
# and the checkbox/widen verbs' gate order.
emacs -Q --batch -L emacs -l test/jetpacs-org-render-test.el \
  -f ert-run-tests-batch-and-exit

# Mode-app architecture: the reusable reader/editor registries, the Org
# adapters' built-in search/visibility/crypt behavior, synchronized-only
# toolbar commands, and `jetpacs-org-mode' app identity.
emacs -Q --batch -L emacs -l test/jetpacs-mode-app-test.el \
  -f ert-run-tests-batch-and-exit

# GR-6b save-policy seam: encryption-abort rollback, optional Vulpea,
# whole-cache coherence, EBP seam ownership, the additive downstream editor
# adapter, and SRS durability inside the engine form.
emacs -Q --batch -L emacs -L ../glasspane \
  -l test/jetpacs-editor-org-test.el \
  -f ert-run-tests-batch-and-exit

# GR-4 capture-owner cutover: picker/form conclusions, share stash and 1301
# successor safety, protocol forms, prefix filtering, cache scope, and D1.
emacs -Q --batch -L emacs -l test/jetpacs-org-capture-test.el \
  -f ert-run-tests-batch-and-exit

# GR-5 clock-owner cutover: chronometer shape, durable pre-READY dispatch,
# lifecycle/grant settlement, and the Org Crypt guard on deferred saves.
emacs -Q --batch -L emacs -l test/jetpacs-org-clock-test.el \
  -f ert-run-tests-batch-and-exit

# GR-3 reminder-owner cutover: canonical agenda extraction, horizon/id/dedupe,
# confirmed-set suppression, and the three-pipeline hook singleton.
emacs -Q --batch -L emacs -L ../glasspane \
  -l test/jetpacs-org-reminders-test.el \
  -f ert-run-tests-batch-and-exit

# JA-5d exit gate: the org dialogs — profile-pinned specs, drop-only
# remote descriptors, fresh ids, 23.2 re-validation, the engine-backed
# sheet mutations, token+confirm Archive, and the can-bridge gate.
emacs -Q --batch -L emacs -l test/jetpacs-org-dialogs-test.el \
  -f ert-run-tests-batch-and-exit

# JA-5g exit gate: the habits screen — the windowed graph with rotten
# habits skipped, the one-canvas strip's A2 spend, the durable Done
# with the prompt hazard loud, token honesty, and the grep pin keeping
# the walk off the raw agenda forms.
emacs -Q --batch -L emacs -l test/jetpacs-org-habits-test.el \
  -f ert-run-tests-batch-and-exit

# JA-5h: the Tier-1 outline STAGING view (never required by base) —
# own-records cards, the cap/seam/empty body, the no-base-require pin.
emacs -Q --batch -L emacs -l test/jetpacs-org-outline-test.el \
  -f ert-run-tests-batch-and-exit

# JA-3b exit gate: binding extraction (incl. the minor-mode-map poc-bug
# regression), menu-bar mining over the :filter/:enable/:visible fixture,
# and suppressed-commands unreachability.
emacs -Q --batch -L emacs -l test/jetpacs-keymap-test.el \
  -f ert-run-tests-batch-and-exit

# JA-3c/3d exit gate: imenu flatten (both index shapes), the 23.1
# exposure gates on the buffer-addressed actions, the static-candidates
# obarray guard, and the message->toast bridge's gates.
emacs -Q --batch -L emacs -l test/jetpacs-emacs-ui-test.el \
  -f ert-run-tests-batch-and-exit

# JA-6 F1 exit gate: the floor guard's :require modes driven directly
# (symlink-inside-root, path-prefix-not-component, remote-filename,
# absent-target containment), the /sdcard probe, the dired card skin's
# ordering and both caps, and the browse verbs' D1/D2 shape through the
# real dispatch.
emacs -Q --batch -L emacs -l test/jetpacs-files-test.el \
  -f ert-run-tests-batch-and-exit

# JA-6 F5 launcher exit gate: the registry read, the open verb's
# membership stale-guard, and show's :any-surface exemption.
emacs -Q --batch -L emacs -l test/jetpacs-launcher-test.el \
  -f ert-run-tests-batch-and-exit

# Cross-module seam wiring (AUDIT-ja1-ja2 3(h)): the ONLY process that
# loads the application layer together.  A seam that exists only when two
# modules are both loaded — chrome publishing itself as the navigator's
# drill host, device adding its teardown sweep — is tested by no other
# suite, so deleting it is green everywhere else.  Selector-scoped: the
# loopback harness file defines its own suite too.
emacs -Q --batch -L emacs -l "$EBP_EL_DIR/test/ebp-wire-test.el" -l test/jetpacs-integration-test.el \
  --eval '(ert-run-tests-batch-and-exit "^jetpacs-integration-")'

# Phase A cross-file seams + the comint P1s.  Several of these regress by
# HANGING rather than failing (a prompt reached inside a dispatch extent),
# so this suite is the one that must never be skipped.
emacs -Q --batch -L emacs -l test/jetpacs-phase-a-test.el \
  -f ert-run-tests-batch-and-exit

# Shared exact-source projection used by the catalog viewers: authored forms,
# honest loaded-function/unavailable fallbacks, and bounded output.
emacs -Q --batch -L emacs -l "$JETPACS_AUTHORING_DIR/test/jetpacs-elisp-source-test.el" \
  -f ert-run-tests-batch-and-exit

# Glasspane Material 3 Catalog exit gate: the upstream
# inventory — 41 components, 279 examples, upstream order — plus a build
# of EVERY screen it can show, each checked for the §16.2 profile, §16.1
# id uniqueness, and canonical serialization.  Nothing else in the tree
# exercises this much of the builder surface at once.  It also holds the
# Material version pin: the toml is read off disk and asserted equal to
# `jetpacs-m3-material-version', so the two move unanimously or go red.
emacs -Q --batch -L emacs \
  --eval '(setq load-prefer-newer t)' \
  -l "$GLASSPANE_MATERIAL3_DIR/test/jetpacs-m3-catalog-test.el" \
  -f ert-run-tests-batch-and-exit

# Jetpacs' Foundation-only design extension and its separate catalog: the
# closed inert authoring model, trace/arm policy, four projections, public
# builders, app gating, screen builds, and the real action/state loop.
emacs -Q --batch -L emacs \
  --eval '(setq load-prefer-newer t)' \
  -l "$JETPACS_COMPONENT_CATALOG_DIR/test/jetpacs-component-authoring-test.el" \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch -L emacs \
  --eval '(setq load-prefer-newer t)' \
  -l "$JETPACS_COMPONENT_CATALOG_DIR/test/jetpacs-component-catalog-actions-test.el" \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch -L emacs \
  --eval '(setq load-prefer-newer t)' \
  -l "$JETPACS_COMPONENT_CATALOG_DIR/test/jetpacs-component-catalog-test.el" \
  -f ert-run-tests-batch-and-exit

# The shared Elisp REPL loop (jetpacs-repl.el): the loop the device
# home screen IS and the catalog Playground rides.  These two suites
# landed WITH the Playground branch but were never wired here — a suite
# that exists and never runs is the trap this runner's explicit list
# invites, so their absence was itself a merge-review catch.
emacs -Q --batch -L emacs -l test/jetpacs-repl-test.el \
  -f ert-run-tests-batch-and-exit

# The Catalog Playground (jetpacs-m3-repl.el): the REPL-over-one-sample
# whose print step is a rendering — the placement pin (a duplicate
# :sheet fails silently) and the override blast radius.
emacs -Q --batch -L emacs \
  -l "$GLASSPANE_MATERIAL3_DIR/test/jetpacs-m3-repl-test.el" \
  -f ert-run-tests-batch-and-exit

# Glasspane is now a separately owned downstream applet at ../glasspane.
# The legacy ladder suites retained in this POC are implementation evidence,
# not current authority.  Glasspane's own focused ERT, static validator,
# warning-as-error compile, and trusted determinism check run from that repo.

# Icon lint: SPEC 17.1's placeholder degrade means a misspelled icon
# never fails at runtime — this is the only gate that catches a typo.
# Ground truth is Glasspane Material's generated M3-ICON-REFERENCE.org
# (regenerate in that repository after a dependency bump).
emacs -Q --batch -l test/jetpacs-icon-lint-test.el \
  -f ert-run-tests-batch-and-exit
