# EBP 3 renderer-extension migration

EBP 3 separates Jetpacs' Compose-shaped foundation from design-system
implementations. The protocol remains toolkit-neutral and does not register a
downstream renderer. The reference Android Companion composes Glasspane's
Material 3 implementation with Jetpacs' emerging Foundation-only component
implementation; each catalog declares its own selection as an app requirement.

## Compatibility boundary

This is an intentional protocol-major cutover. An EBP 2 endpoint must not be
treated as compatible:

- `session.hello.protocol` and the welcome protocol are `3`;
- authentication labels are `EBP/3 client:` and `EBP/3 companion:`;
- every target profile contains an `extensions` array;
- extension nodes require both their `node_types` entry and their owner in
  `extensions`;
- the wire theme has 13 neutral roles.

There is no alias period for the old Material-only node names. Senders and
receivers move together:

| EBP 2 name | EBP 3 name | Owner |
|---|---|---|
| `assist_chip` | `material3.assist_chip` | `glasspane.material3` |
| `split_button` | `material3.split_button` | `glasspane.material3` |
| `app_bar_row` | `material3.app_bar_row` | `glasspane.material3` |
| `app_bar_column` | `material3.app_bar_column` | `glasspane.material3` |
| `fab_menu` | `material3.fab_menu` | `glasspane.material3` |

These names and schemas live in
`renderer-extensions/glasspane-material3.json`, not in EBP's specification,
contract, generated vocabulary, or goldens. EBP transports the identifiers as
opaque negotiated data.

The same rule governs the additive `jetpacs.components` extension. Its first
app-only nodes are `jetpacs.action`, `jetpacs.choice`, and `jetpacs.panel`,
projected from `renderer-extensions/jetpacs-components.json`. Existing EBP core
or Glasspane Material traffic does not acquire or imply that extension.

## Android ownership

The receiver is split into composable seams:

- `:renderer:model` combines installed renderer contributions into exact EBP
  profiles and contains no Compose or design-system dependency.
- `:renderer:compose` renders only the eight EBP core nodes with Compose
  Foundation and imports neither Material nor experimental Styles.
- `:renderer:material3` is Glasspane's selected Material implementation and
  the rich reference renderer. Its generated extension vocabulary, Material
  dependencies, experimental Styles opt-in, screenshots, and component
  semantics tests live here.
- `:renderer:jetpacs` implements `jetpacs.components` using Compose Foundation,
  private Jetpacs tokens, and experimental Styles. It has no Material import or
  dependency.

The app is the sole composition root. It selects both modules for app surfaces,
keeps `jetpacs.components` out of dialog and notification profiles, and installs
the matching Compose node dispatcher. Those selections are not inherited by
protocol, storage, model, or Foundation modules. Advertised profiles are
derived from the installed contributions. The composition root injects their
schemas and ownership into the generic wire validator; receiver configuration
rejects an extension node whose owner is missing without the wire module naming
that renderer.

## Elisp ownership

Generated Glasspane vocabulary maps its extension to its renderer-owned nodes.
`jetpacs-node-advertised-p` applies the node-and-extension gate, and the final
shell gate repeats it over the complete document. `jetpacs-defapp` accepts
`:requires-extensions`; a missing live requirement opens an explanatory Apps
screen without invoking the app's builders.

The Glasspane Material 3 Catalog and both Glasspane registration branches
require `glasspane.material3`. The separate Jetpacs Components catalog requires
`jetpacs.components` and uses the public builders in
`emacs/apps/jetpacs-components/`. Other apps can remain on the core/general
vocabulary and need not acquire either dependency.

## Projection generation

`tools/gen-renderer-extension-vocabulary.py` projects a renderer-owned
manifest into Kotlin and Elisp without adding its vocabulary to EBP. With no
arguments it retains the established Glasspane paths. `--check` compares both
committed projections without writing them:

```sh
python3 tools/gen-renderer-extension-vocabulary.py --check
```

For another renderer, pass its manifest plus explicit `--kotlin-output` and
`--elisp-output` paths; `--kotlin-package` selects the receiving package. The
Kotlin constant prefix and Elisp feature prefix are derived from the manifest's
extension identifier, so endpoints cannot acquire separately authored names.
The checked-in Jetpacs projection is reproduced with:

```sh
python3 tools/gen-renderer-extension-vocabulary.py \
  renderer-extensions/jetpacs-components.json \
  --kotlin-output companion/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/JetpacsComponentsVocabulary.kt \
  --kotlin-package com.calebc42.jetpacs.renderer.jetpacs \
  --elisp-output emacs/apps/jetpacs-components/jetpacs-components-vocabulary.el \
  --check
```

## Theme migration

The wire accepts only:

`primary`, `on_primary`, `secondary`, `on_secondary`, `error`, `on_error`,
`background`, `on_background`, `surface`, `on_surface`, `outline`, `success`,
and `warning`.

Material container, tertiary, variant, and matching foreground colors are
derived inside `:renderer:material3`. Old Material role strings are rejected
rather than silently interpreted as EBP 3 roles.

## Verification

Run the protocol validator first, then the generated-vocabulary drift tests,
Elisp sender/app gates, renderer registry tests, both design-renderer screenshot
matrices, and device semantics flows. The exact commands are maintained in
[`../companion/TESTING.md`](../companion/TESTING.md) and the repository
implementation guide.
