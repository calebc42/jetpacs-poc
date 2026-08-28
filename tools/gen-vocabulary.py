#!/usr/bin/env python3
"""Generate companion/wire Vocabulary.kt from ebp/contract.json (format 9).

The W0 pattern: wire vocabulary is generated from the authored contract,
never hand-maintained; a drift test re-reads contract.json and fails if the
committed generated file disagrees. Run from the llm-poc-3 root:

    python3 tools/gen-vocabulary.py

Use ``--check`` in verification to fail without rewriting a stale projection.
"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
contract = json.loads((ROOT / "ebp" / "contract.json").read_text(encoding="utf-8"))

OUT = ROOT / "companion/wire/src/jvmMain/kotlin/com/calebc42/ebp/wire/Vocabulary.kt"

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument(
    "--check",
    action="store_true",
    help="fail instead of writing when the committed projection is stale",
)
options = parser.parse_args()


def kt_set(values):
    inner = ", ".join(f"\"{v}\"" for v in values)
    return f"setOf({inner})"


def kt_list(values):
    inner = ", ".join(f'"{v}"' for v in values)
    return f"listOf({inner})"


def kt_map(values):
    inner = ", ".join(f'"{k}" to "{v}"' for k, v in values.items())
    return f"mapOf({inner})"


def kt_string(value):
    return "null" if value is None else f'"{value}"'


def kt_double(value):
    return "null" if value is None else repr(float(value))


rows = []
for name in contract["node_types"]:
    row = contract["node_schema"][name]
    rows.append(
        f"    \"{name}\" to NodeRow({kt_set(row['required'])}, "
        f"{kt_set(row['optional'])}),"
    )

action_rows = []
for name, row in contract["actions"]["schema"].items():
    action_rows.append(
        f"    \"{name}\" to ActionRow({kt_set(row['required'])}, "
        f"{kt_set(row['optional'])}),"
    )

# LD-10: the contract's flat member-name -> value-type map, so the validator
# type-checks against the contract instead of hand-duplicating two rows.
field_type_rows = [
    f"    \"{name}\" to \"{t}\","
    for name, t in sorted(contract["field_types"].items())
]

enum_rows = [
    f'    "{name}" to {kt_set(values)},'
    for name, values in contract["enums"].items()
]

semantics = contract["semantics_schema"]
semantic_objects = []
for name, row in semantics["objects"].items():
    semantic_objects.append(
        f'    "{name}" to SemanticObjectRow('
        f'{kt_set(row["required"])}, {kt_set(row["optional"])}, '
        f'{kt_map(row["field_types"])}),'
    )

default_rows = []
default_field_names = {
    "role_condition_member": "roleConditionMember",
    "heading_level": "headingLevel",
    "enabled_member": "enabledMember",
    "read_only_member": "readOnlyMember",
    "read_only_inverted": "readOnlyInverted",
    "checked_member": "checkedMember",
    "checked_default": "checkedDefault",
    "toggle_state_member": "toggleStateMember",
    "selected_member": "selectedMember",
    "selected_default": "selectedDefault",
    "selection_member": "selectionMember",
    "selection_default_index": "selectionDefaultIndex",
    "expanded_member": "expandedMember",
    "expanded_inverted": "expandedInverted",
    "progress_value_member": "progressValueMember",
    "progress_min_member": "progressMinMember",
    "progress_max_member": "progressMaxMember",
    "progress_min": "progressMin",
    "progress_max": "progressMax",
    "indeterminate_when_value_absent": "indeterminateWhenValueAbsent",
    "progress_value_defaults_to_min": "progressValueDefaultsToMin",
    "custom_actions_from": "customActionsFrom",
}
for name, row in semantics["default_node_semantics"].items():
    args = []
    string_fields = (
        "role", "role_condition_member", "enabled_member",
        "read_only_member", "checked_member", "toggle_state_member",
        "selected_member", "selection_member", "expanded_member", "progress_value_member",
        "progress_min_member", "progress_max_member",
    )
    for field in string_fields:
        if field in row:
            args.append(
                f'{default_field_names.get(field, field)} = '
                f'{kt_string(row[field])}'
            )
    if "heading_level" in row:
        args.append(f'headingLevel = {row["heading_level"]}')
    for field in (
        "read_only_inverted", "expanded_inverted",
        "indeterminate_when_value_absent", "progress_value_defaults_to_min",
    ):
        if field in row:
            args.append(
                f'{default_field_names[field]} = {str(row[field]).lower()}'
            )
    for field in ("checked_default", "selected_default"):
        if field in row:
            args.append(
                f'{default_field_names[field]} = {str(row[field]).lower()}'
            )
    if "selection_default_index" in row:
        args.append(f'selectionDefaultIndex = {row["selection_default_index"]}')
    for field in ("progress_min", "progress_max"):
        if field in row:
            args.append(f'{default_field_names[field]} = {kt_double(row[field])}')
    if "custom_actions_from" in row:
        args.append(
            f'customActionsFrom = {kt_list(row["custom_actions_from"])}'
        )
    default_rows.append(
        f'    "{name}" to DefaultSemanticRow({", ".join(args)}),'
    )

body = f"""// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from ebp/contract.json (format {contract["contract_format"]},
// spec {contract["spec_version"]}) by tools/gen-vocabulary.py — DO NOT EDIT.
// VocabularyDriftTest re-reads the contract and fails on any disagreement.
package com.calebc42.ebp.wire

data class NodeRow(val required: Set<String>, val optional: Set<String>)
data class ActionRow(val required: Set<String>, val optional: Set<String>)
/** Required, optional, and typed members for one contract-projected object. */
data class SemanticObjectRow(
    val required: Set<String>,
    val optional: Set<String>,
    val fieldTypes: Map<String, String>,
)

/** Contract-projected node defaults; no receiver may invent a role override. */
data class DefaultSemanticRow(
    val role: String? = null,
    val roleConditionMember: String? = null,
    val headingLevel: Int? = null,
    val enabledMember: String? = null,
    val readOnlyMember: String? = null,
    val readOnlyInverted: Boolean = false,
    val checkedMember: String? = null,
    val checkedDefault: Boolean? = null,
    val toggleStateMember: String? = null,
    val selectedMember: String? = null,
    val selectedDefault: Boolean? = null,
    val selectionMember: String? = null,
    val selectionDefaultIndex: Int? = null,
    val expandedMember: String? = null,
    val expandedInverted: Boolean = false,
    val progressValueMember: String? = null,
    val progressMinMember: String? = null,
    val progressMaxMember: String? = null,
    val progressMin: Double? = null,
    val progressMax: Double? = null,
    val indeterminateWhenValueAbsent: Boolean = false,
    val progressValueDefaultsToMin: Boolean = false,
    val customActionsFrom: List<String> = emptyList(),
)

/** Contract-projected authored selection seed for a text input. */
data class TextInputSelectionContract(
    val type: String,
    val unit: String,
    val minimum: Long,
    val order: String,
    val upperBound: String,
    val lifecycle: String,
    val whenRetainedDraftWins: String,
)

/** Contract-projected maximum-length rule for a text input. */
data class TextInputMaxLengthContract(
    val type: String,
    val unit: String,
    val behavior: String,
    val authoredValueMustFit: Boolean,
    val retainedDraftMustFit: Boolean,
)

/** Contract-projected mask grammar for a text input. */
data class TextInputMaskContract(
    val slot: String,
    val minimumSlots: Int,
    val unit: String,
    val overflow: String,
    val incompatibleWith: Set<String>,
)

/** Contract-projected interior-padding rule for a text input. */
data class TextInputPaddingContract(
    val type: String,
    val appliesTo: String,
)

/** Complete generated §17.4 constraint envelope for `text_input`. */
data class TextInputContract(
    val selection: TextInputSelectionContract,
    val maxLength: TextInputMaxLengthContract,
    val transformOrder: List<String>,
    val filterCharacterSets: Map<String, String>,
    val filterUnknown: String,
    val filterAuthoredValueMustMatch: Boolean,
    val filterRetainedDraftMustMatch: Boolean,
    val mask: TextInputMaskContract,
    val contentPadding: TextInputPaddingContract,
    val variantDefault: String,
    val variantUnknown: String,
    val hideKeyboardOnSubmitRequires: String,
    val errorDescriptionPrecedence: List<String>,
    val logicalValueExcludes: List<String>,
)

const val CONTRACT_FORMAT = {contract["contract_format"]}
const val PROTOCOL_VERSION = {contract["protocol_version"]}
const val SPEC_VERSION = "{contract["spec_version"]}"

val CORE_NODE_SET: Set<String> = {kt_set(contract["core_node_set"])}

val UNIVERSAL_NODE_ATTRIBUTES: Set<String> = {kt_set(contract["universal_node_attributes"])}

const val MAX_SEMANTIC_ACTIONS_PER_NODE = {contract["limits"]["fixed"]["max_semantic_actions_per_node"]}

val SEMANTICS_SCHEMA = SemanticObjectRow(
    required = {kt_set(semantics["required"])},
    optional = {kt_set(semantics["optional"])},
    fieldTypes = {kt_map(semantics["field_types"])},
)

val SEMANTIC_OBJECT_SCHEMA: Map<String, SemanticObjectRow> = mapOf(
{chr(10).join(semantic_objects)}
)

val SEMANTIC_LIVE_REGIONS: Set<String> = {kt_set(semantics["enums"]["live_region"])}
val SEMANTIC_ROLES: Set<String> = {kt_set(semantics["enums"]["role"])}
val ACCESSIBLE_NAME_PRECEDENCE: List<String> = listOf(
    {", ".join(f'"{v}"' for v in semantics["accessible_name_precedence"])}
)

val DEFAULT_NODE_SEMANTICS: Map<String, DefaultSemanticRow> = mapOf(
{chr(10).join(default_rows)}
)

/** Contract-projected enum vocabulary; senders reject unknown authored values. */
val ENUMS: Map<String, Set<String>> = mapOf(
{chr(10).join(enum_rows)}
)

val TEXT_INPUT_CONTRACT = TextInputContract(
    selection = TextInputSelectionContract(
        type = "{contract["text_input_schema"]["selection"]["type"]}",
        unit = "{contract["text_input_schema"]["selection"]["unit"]}",
        minimum = {contract["text_input_schema"]["selection"]["minimum"]}L,
        order = "{contract["text_input_schema"]["selection"]["order"]}",
        upperBound = "{contract["text_input_schema"]["selection"]["upper_bound"]}",
        lifecycle = "{contract["text_input_schema"]["selection"]["lifecycle"]}",
        whenRetainedDraftWins = "{contract["text_input_schema"]["selection"]["when_retained_draft_wins"]}",
    ),
    maxLength = TextInputMaxLengthContract(
        type = "{contract["text_input_schema"]["max_length"]["type"]}",
        unit = "{contract["text_input_schema"]["max_length"]["unit"]}",
        behavior = "{contract["text_input_schema"]["max_length"]["behavior"]}",
        authoredValueMustFit = {str(contract["text_input_schema"]["max_length"]["authored_value_must_fit"]).lower()},
        retainedDraftMustFit = {str(contract["text_input_schema"]["max_length"]["retained_draft_must_fit"]).lower()},
    ),
    transformOrder = {kt_list(contract["text_input_schema"]["transform_order"])},
    filterCharacterSets = {kt_map(contract["text_input_schema"]["filter_character_sets"])},
    filterUnknown = "{contract["text_input_schema"]["filter_unknown"]}",
    filterAuthoredValueMustMatch = {str(contract["text_input_schema"]["filter_authored_value_must_match"]).lower()},
    filterRetainedDraftMustMatch = {str(contract["text_input_schema"]["filter_retained_draft_must_match"]).lower()},
    mask = TextInputMaskContract(
        slot = "{contract["text_input_schema"]["mask"]["slot"]}",
        minimumSlots = {contract["text_input_schema"]["mask"]["minimum_slots"]},
        unit = "{contract["text_input_schema"]["mask"]["unit"]}",
        overflow = "{contract["text_input_schema"]["mask"]["overflow"]}",
        incompatibleWith = {kt_set(contract["text_input_schema"]["mask"]["incompatible_with"])},
    ),
    contentPadding = TextInputPaddingContract(
        type = "{contract["text_input_schema"]["content_padding"]["type"]}",
        appliesTo = "{contract["text_input_schema"]["content_padding"]["applies_to"]}",
    ),
    variantDefault = "{contract["text_input_schema"]["variant_default"]}",
    variantUnknown = "{contract["text_input_schema"]["variant_unknown"]}",
    hideKeyboardOnSubmitRequires = "{contract["text_input_schema"]["hide_keyboard_on_submit_requires"]}",
    errorDescriptionPrecedence = {kt_list(contract["text_input_schema"]["error_description_precedence"])},
    logicalValueExcludes = {kt_list(contract["text_input_schema"]["logical_value_excludes"])},
)

/** SPEC 14.6: node types whose id/value participate in input state. */
val STATEFUL_NODE_TYPES: Set<String> = setOf(
    "text_input", "checkbox", "switch", "enum_list", "slider", "editor",
    "search_bar", "dropdown", "segmented_button", "variant_host",
    // Conditionally stateful: a plain button carries no state and needs no
    // id. SpecValidator's isStateful predicate registers these ONLY when
    // `checked` is present — see the `editor` precedent.
    "button", "icon_button")

val ACTION_HOOK_KEYS: Set<String> = {kt_set(contract["actions"]["hook_keys"])}

val OFFLINE_POLICIES: Set<String> = {kt_set(contract["actions"]["offline_policies"])}
const val OFFLINE_DEFAULT = "{contract["actions"]["offline_default"]}"

val NODE_SCHEMA: Map<String, NodeRow> = mapOf(
{chr(10).join(rows)}
)

val ACTION_SCHEMA: Map<String, ActionRow> = mapOf(
{chr(10).join(action_rows)}
)

/** SPEC 4/16-17: each member name's contract value type. LD-10 drives the
 * validator's scalar type-checks off this instead of coercing accessors. */
val FIELD_TYPES: Map<String, String> = mapOf(
{chr(10).join(field_type_rows)}
)
"""

if options.check:
    if not OUT.exists() or OUT.read_text(encoding="utf-8") != body:
        raise SystemExit(f"stale generated vocabulary: {OUT.relative_to(ROOT)}")
    print(f"checked {OUT.relative_to(ROOT)}: {len(rows)} nodes, "
          f"{len(action_rows)} action rows")
else:
    OUT.write_text(body, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(rows)} nodes, "
          f"{len(action_rows)} action rows")
