# Audit: EBP `text_input` contract drift

Date: 2026-08-27
Status: Complete; ratified by EBP amendment #180

## Finding

The normative `ebp/SPEC.md` §17.4 lists fifteen optional `text_input`
members. `ebp/contract.json`, its generated Kotlin and Elisp mirrors, the
public `jetpacs-text-input` builder, the Kotlin validator, and the Glasspane
Material renderer additionally recognize thirteen members:

- `variant`
- `is_error`
- `supporting_text`
- `prefix`
- `suffix`
- `leading_icon`
- `trailing_icon`
- `max_length`
- `selection`
- `hide_keyboard_on_submit`
- `content_padding`
- `mask`
- `filter`

This is a source-of-truth violation under SPEC §2.2. The projection and
implementations cannot amend the normative protocol by existing.

## Provenance

The first eight members entered the EBP contract in `4089ac0` (`contract: the
m3-catalog vocabulary — 2 node types and ~45 members`). The remaining five
entered in `be4c091` (`contract: the input-member sweep — range and vertical
sliders, tri-state, text-field locals, carets`). Neither commit amended
`SPEC.md` or `SPEC-CHANGES.md`.

`docs/DRAFT-amendments-156-167-m3-tier1.md` contains an unratified proposed
amendment #163 for the first group. Its wording is explicitly tied to Material
3 slots and disagrees with the subsequently landed implementation about
`max_length`: the draft makes it announcement-only, while the renderer and
catalog make it a hard local input bound. No normative draft covers the full
second group.

Current use is concentrated in `emacs/apps/m3-catalog/jetpacs-m3-text-fields.el`.
No production applet outside that catalog authors these thirteen members, so
the compatibility concern is accepted catalog traffic and retained test/demo
documents rather than application policy.

## Classification decision

Retain all thirteen members in EBP and ratify them as toolkit-neutral. None
requires a Material class, token, shape, or layout algorithm on the wire:

| Members | Classification | Neutral contract meaning |
|---|---|---|
| `variant` | Presentation intent | Receiver-native outlined or filled field treatment; exact styling remains renderer-owned. |
| `is_error`, `supporting_text` | Semantic state/content | Error state and field-bound supporting content, including accessible error fallback. |
| `prefix`, `suffix` | Field structure | Non-editable affixes excluded from the logical value. |
| `leading_icon`, `trailing_icon` | Field structure | Non-interactive decorations named by the existing icon vocabulary. |
| `max_length` | Input constraint | Positive Unicode-scalar bound applied before a local value commits. |
| `selection` | Presentation seed | Initial half-open selection in Unicode-scalar offsets. |
| `hide_keyboard_on_submit` | Platform editing behavior | Dismiss the software input method after the authored submit occurrence. |
| `content_padding` | Layout intent | One non-negative dp inset applied to all four interior sides. |
| `mask` | Output transformation | `#`-slot display template that never changes the logical value. |
| `filter` | Input transformation | Deterministic ASCII `digits` or `alnum` containment before commit. |

Moving these to `glasspane.material3` would invalidate already accepted core
documents and require renderer-extension manifests to augment members on an
EBP-owned node. That extra mechanism buys no neutrality once the observable
rules above are defined without Material terminology. Glasspane and Jetpacs
remain free to draw them differently.

## Normative decisions

### Order of local transformations

Before committing a locally proposed value, the Companion applies:

1. U+000A deletion for `single_line`;
2. `filter`, when present; and
3. Unicode-scalar truncation to `max_length`, when present.

The resulting logical value is the only value retained, published through
`state.changed`, captured, or supplied to `on_change`/`on_submit`.

`digits` admits only ASCII `0` through `9`; `alnum` admits only ASCII letters
and digits. A fixed set avoids endpoint disagreement caused by platform
Unicode-database versions. Emacs must still validate any domain constraint it
relies on.

An authored value must already satisfy `single_line`, `filter`, and
`max_length`. A retained draft that no longer satisfies one of those members
is incompatible under §13.6 and is cleared without a synthetic user event.

### Selection

`selection` is exactly two non-negative safe integers `[start, end]` with
`start <= end`. Offsets count Unicode scalar values and must not exceed the
authored `value` (empty by default). It seeds a new presentation only when no
retained dirty draft supersedes the authored value. It is not part of the
logical value, `state.changed`, capture fields, or input-state reconciliation.

### Mask

A mask is a non-empty string containing at least one `#`. Each `#` consumes
one logical Unicode scalar; other scalars are display literals. Literals never
enter the value. If the value exceeds the slot count, the remaining value is
displayed unformatted rather than lost. A mask is invalid with `password` or
`syntax` because those members compete for the same output transformation.

### Error and accessibility

`is_error` changes state, not application validity. When true, an authored
`semantics.error` is the accessible error description; otherwise a non-empty
`supporting_text` is the fallback. `max_length` is also exposed as the
accessible maximum, but Emacs remains responsible for validating received
values.

### Remaining defaults and constraints

- `variant` defaults to `outlined`; an unknown value safely falls back to it.
- An unknown `filter` safely falls back to no local filter.
- `content_padding` is one finite non-negative dp value for all interior sides.
- `hide_keyboard_on_submit: true` requires `on_submit`; dismissal follows the
  local submit occurrence and does not wait for remote completion.
- Prefixes, suffixes, and icons are excluded from the logical value. Icons are
  decorations, not independent click targets.
- `is_error` and `hide_keyboard_on_submit` default to false. Every other added
  member defaults to absence.

## Required correction

1. Add the thirteen members and rules to normative §17.4 and update §13.6
   reconciliation.
2. Add a structured `text_input_schema` projection and advance contract format
   from 8 to 9 while retaining protocol major 3.
3. Project the new schema and enum registry into Kotlin and Elisp vocabulary.
4. Add accepted/rejected `text_input` goldens, including astral Unicode.
5. Make the EBP validator, Kotlin receiver validator, Elisp authoring helper,
   and Material renderer conform to the same scalar counts and combinations.
6. Add cross-language replay tests and preserve existing Material pixels.

The correction is additive at the wire level: all thirteen members were
already optional in the published contract projection, and their omission
preserves the existing rendering. Contract format 9 describes the new
machine-readable constraint projection; it does not change protocol major 3.

## Implemented result

The correction is complete. `SPEC.md` and `SPEC-CHANGES.md` now govern the
rules above, `contract.json` format 9 projects them as `text_input_schema`, and
the documented generators produce the Kotlin and Elisp mirrors. The Python
validator and Kotlin receiver replay the same 27-case golden, including astral
Unicode, invalid combinations, unsafe integers, and enum fallback. Elisp
constructors enforce the authoring side of the envelope.

Glasspane keeps its Material presentation, but its value normalization,
selection conversion, and mask mapping now use the shared scalar-safe rules.
Accessibility projection composes explicit semantic errors with supporting
text and exposes the maximum text length. All eleven existing Material
screenshot references validated unchanged.
