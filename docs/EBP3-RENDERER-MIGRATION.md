# EBP 3 renderer-extension migration

EBP 3 separates Jetpacs' Compose-shaped foundation from design-system
implementations. The protocol remains toolkit-neutral and does not register a
downstream renderer. The reference Android Companion selects Glasspane's
Material 3 implementation, while Glasspane and Jetpacs Components declare that
selection as an app requirement.

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

## Android ownership

The receiver is split into three seams:

- `:renderer:model` combines installed renderer contributions into exact EBP
  profiles and contains no Compose or design-system dependency.
- `:renderer:compose` renders only the eight EBP core nodes with Compose
  Foundation and imports neither Material nor experimental Styles.
- `:renderer:material3` is Glasspane's selected Material implementation and
  the rich reference renderer. Its generated extension vocabulary, Material
  dependencies, experimental Styles opt-in, screenshots, and component
  semantics tests live here.

The app is the composition root that selects `:renderer:material3`; that
selection is not inherited by protocol, storage, model, or Foundation modules.
Advertised profiles are derived from the installed contributions. The
composition root injects their schemas and ownership into the generic wire
validator; receiver configuration rejects an extension node whose owner is
missing without the wire module naming that renderer.

## Elisp ownership

Generated Glasspane vocabulary maps its extension to its renderer-owned nodes.
`jetpacs-node-advertised-p` applies the node-and-extension gate, and the final
shell gate repeats it over the complete document. `jetpacs-defapp` accepts
`:requires-extensions`; a missing live requirement opens an explanatory Apps
screen without invoking the app's builders.

Jetpacs Components and both Glasspane registration branches require
`glasspane.material3`. Other apps can remain on the core/general vocabulary and
need not acquire that dependency.

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
Elisp sender/app gates, renderer registry tests, Material screenshot matrix,
and device semantics flow. The exact commands are maintained in
[`../companion/TESTING.md`](../companion/TESTING.md) and the repository
implementation guide.
