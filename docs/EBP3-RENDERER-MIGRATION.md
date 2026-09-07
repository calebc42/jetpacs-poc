# EBP 3 renderer-extension boundary

EBP 3 separates the implementation-neutral protocol and Compose Foundation
renderer from downstream design systems. This was a protocol-major cutover;
the migration is complete, and this document now records the boundary that
must remain true.

## Compatibility

An EBP 2 endpoint is not treated as compatible:

- the session protocol and authentication labels are EBP 3;
- every target profile carries an `extensions` array;
- an extension node requires both its node type and its owner extension;
- the wire theme contains the 13 neutral EBP roles; and
- old unnamespaced Material-only node aliases are rejected.

The EBP 2 Material names moved as follows:

| Old name | EBP 3 name | Owner |
|---|---|---|
| `assist_chip` | `material3.assist_chip` | `jetpacs.material3` |
| `split_button` | `material3.split_button` | `jetpacs.material3` |
| `app_bar_row` | `material3.app_bar_row` | `jetpacs.material3` |
| `app_bar_column` | `material3.app_bar_column` | `jetpacs.material3` |
| `fab_menu` | `material3.fab_menu` | `jetpacs.material3` |

These schemas live in the Jetpacs Material 3 repository, not in the EBP
specification or generated core vocabulary.

## Installed extensions

The Companion currently knows three independently owned extension IDs:

| Extension | Nodes | Targets |
|---|---|---|
| `jetpacs.material3` | Five namespaced Material nodes | App and dialog profiles as declared by its manifest |
| `jetpacs.components` | `jetpacs.action`, `choice`, `list_item`, `panel`, `scope`, `section_navigator`, and `tabs` | App only |
| `jetpacs.design` | `jetpacs.design_scope`, `jetpacs.styled`, and `jetpacs.pressable` plus typed design definitions | App only and default-off |

The first two are in the normal app installation. `jetpacs.design` is enabled
only by the explicit receiver setting. Disabled installations recognize the
conventional child shape of cached design nodes for safe degradation but do not
advertise, semantically admit, or execute the extension.

## Android ownership

- `:renderer:model` combines contributions into exact profiles and contains no
  Compose or design-system dependency.
- `:renderer:compose` renders the EBP core with Compose Foundation/UI and owns
  shared semantics and input/editor controllers. It imports neither Material
  nor experimental Styles.
- `:renderer:material3` implements Jetpacs' Material design and extension.
- `:renderer:jetpacs` implements both Jetpacs manifests with Foundation, private
  tokens, and experimental Styles; it has no Material dependency.
- `:renderer:glance` derives a separate restricted widget profile.
- `:app` is the only composition root. It installs the matching vocabulary,
  profile, semantic validator, Compose dispatcher, and canonical overrides as
  one internally consistent `CompanionRendererInstallation`.

Dialogs receive core plus Jetpacs Material only. Notifications, widgets, and
tiles each use narrower target-specific profiles. No prefix implies support;
the receiver registry derives exactly what is advertised.

`jetpacs.scope` reselects canonical `text_input` and `editor` through Jetpacs
components. When enabled, `jetpacs.design_scope` adds canonical overrides for
the 17 node types listed in [`ARCHITECTURE-POC3.md`](ARCHITECTURE-POC3.md).
Overrides remain selection/presentation only: they do not redefine core schema,
state, action, or reconciliation semantics, and they may decline an unsupported
node to the normal dispatcher.

## Elisp ownership

Each renderer owner generates its Elisp vocabulary from the same manifest as
its Kotlin projection. Jetpacs registers those projections without copying
their schemas. `jetpacs-node-advertised-p` and the final shell gate apply the
node-and-owner check over the complete document.

`jetpacs-defapp :requires-extensions` prevents a builder from running when its
required extension is absent. The Apps surface explains the missing receiver
support instead. Apps that use only core nodes acquire no implicit design
dependency.

## Projection generation

The generic generator belongs to `../../ebp-compose`:

```text
../../ebp-compose/tools/gen-renderer-extension-vocabulary.py
```

Extension owners control their manifests and output paths. Run their checked
wrappers from the Jetpacs POC root:

```sh
tools/check-material3-projections.sh
jetpacs-components/tools/check-projections.sh
```

The generator does not register an extension in EBP. It projects one owner's
manifest into Kotlin and Elisp so endpoints cannot acquire separately authored
names or schemas.

## Theme boundary

The wire carries only `primary`, `on_primary`, `secondary`, `on_secondary`,
`error`, `on_error`, `background`, `on_background`, `surface`, `on_surface`,
`outline`, `success`, and `warning`. Material container, tertiary, variant, and
matching foreground colors are private derivations inside the Material
renderer. Jetpacs design can rebind the same neutral roles inside its app scope;
it does not add Material roles to EBP.

## Verification

Run the EBP validator first, then both extension projection checks, the owner
renderer/Elisp suites, Jetpacs app/profile tests, aggregate Elisp integration,
reviewed screenshot validation, and affected device semantics. Exact Android
commands are maintained in [`../companion/TESTING.md`](../companion/TESTING.md).
