#!/usr/bin/env python3
"""Generate emacs/jetpacs-vocabulary.el from ../ebp-poc/ebp/contract.json.

The sibling of tools/gen-vocabulary.py, which does the same for the
Companion's Vocabulary.kt.  Same W0 rule: wire vocabulary is generated from
the authored contract, never hand-maintained; a drift test re-reads
contract.json and fails if the committed generated file disagrees.

SCOPE.  This projects the per-node schema plus field types, the universal
Semantics envelope, nested schemas, enums, limits, accessible-name order,
default node-derived semantics, ActionDescriptor metadata, and the Editor
toolbar vocabulary.  These values must not acquire handwritten twins in the
authoring layer.  Run from the Jetpacs repository root:

    python3 tools/gen-jetpacs-vocabulary.py

Use ``--check`` in verification to fail without rewriting a stale projection.
"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
contract = json.loads(
    (ROOT.parent / "ebp-poc" / "ebp" / "contract.json").read_text(encoding="utf-8")
)

OUT = ROOT / "emacs" / "jetpacs-vocabulary.el"

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument(
    "--check",
    action="store_true",
    help="fail instead of writing when the committed projection is stale",
)
options = parser.parse_args()


def el_strings(values):
    return " ".join(f'"{v}"' for v in values)


def el_keywords(values):
    return " ".join(f':{v}' for v in values)


def el_value(value):
    if value is True:
        return "t"
    if value is False:
        return ":json-false"
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, list):
        return f'({" ".join(el_value(v) for v in value)})'
    if isinstance(value, dict):
        return el_plist(value)
    return str(value)


def el_plist(mapping):
    return "(" + " ".join(
        f':{name} {el_value(value)}' for name, value in mapping.items()
    ) + ")"


rows = []
for name in contract["node_types"]:
    row = contract["node_schema"][name]
    rows.append(
        f'    ("{name}" ({el_strings(sorted(row["required"]))})'
        f' ({el_strings(sorted(row["optional"]))}))'
    )

semantics = contract["semantics_schema"]
semantic_object_rows = []
for name, row in semantics["objects"].items():
    types = " ".join(
        f'("{field}" . "{field_type}")'
        for field, field_type in row["field_types"].items()
    )
    semantic_object_rows.append(
        f'    ("{name}" (:required ({el_strings(row["required"])}) '
        f':optional ({el_strings(row["optional"])}) '
        f':field-types ({types})))'
    )

semantic_types = " ".join(
    f'("{field}" . "{field_type}")'
    for field, field_type in semantics["field_types"].items()
)
default_semantic_rows = [
    f'    ("{name}" . {el_plist(row)})'
    for name, row in semantics["default_node_semantics"].items()
]
enum_rows = [
    f'    ("{name}" . ({el_strings(values)}))'
    for name, values in contract["enums"].items()
]
field_type_rows = [
    f'    ({el_value(name)} . {el_value(field_type)})'
    for name, field_type in contract["field_types"].items()
]

actions = contract["actions"]
action_schema_rows = [
    f'    ({el_value(name)} '
    f':required ({el_strings(row["required"])}) '
    f':optional ({el_strings(row["optional"])}))'
    for name, row in actions["schema"].items()
]
action_injection_rows = [
    f'    ({el_value(hook)} . ({el_strings(members)}))'
    for hook, members in actions["injections"].items()
]

body = f''';;; jetpacs-vocabulary.el --- the contract node schema -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; GENERATED from ebp/contract.json (format {contract["contract_format"]}, spec
;; {contract["spec_version"]}) by tools/gen-jetpacs-vocabulary.py -- DO NOT EDIT.
;; `jetpacs-widgets/catalog-node-schema' re-reads the contract and fails on
;; any disagreement, exactly as the other `catalog-*' mirrors do.
;;
;; The sibling of the Companion's generated Vocabulary.kt, and for the same
;; reason: an amendment that adds a member must not have to be remembered in
;; three places.  Emacs cannot read contract.json at run time -- the device
;; load-path holds only the .el files device/install.sh pushes -- so the
;; contract is compiled to Elisp here and drift-tested off-device.
;;
;; This is what lets the container constructors REFUSE an unknown trailing
;; option instead of silently dropping it, which is how `:padding 8' on a
;; row used to vanish (it is a SPEC 16.5 universal attribute and belongs on
;; `jetpacs-with-attrs').

;;; Code:

(defconst jetpacs-contract-format {contract["contract_format"]}
  "The `contract_format' this vocabulary was generated from.")

(defconst jetpacs-protocol-version {contract["protocol_version"]}
  "The EBP wire major this vocabulary implements.")

(defconst jetpacs-contract-spec-version "{contract["spec_version"]}"
  "The SPEC version this vocabulary was generated from.")

(defconst jetpacs-universal-attributes
  '({el_keywords(contract["universal_node_attributes"])})
  "Contract-projected universal member keywords accepted on every Node.")

(defconst jetpacs-semantics-schema
  '(:required ({el_strings(semantics["required"])})
    :optional ({el_strings(semantics["optional"])})
    :field-types ({semantic_types}))
  "Contract-projected outer Semantics object schema.")

(defconst jetpacs-semantic-object-schema
  '(
{chr(10).join(semantic_object_rows)})
  "Contract-projected schemas for nested Semantics objects.")

(defconst jetpacs-semantic-members
  '({el_keywords(semantics["optional"])})
  "Known Semantics member keywords; authoring helpers reject all others.")

(defconst jetpacs-semantic-live-regions
  '({el_strings(semantics["enums"]["live_region"])})
  "The contract-projected Semantics live-region enum.")

(defconst jetpacs-semantic-roles
  '({el_strings(semantics["enums"]["role"])})
  "The receiver-derived semantic role vocabulary; not an author override.")

(defconst jetpacs-max-semantic-actions-per-node
  {contract["limits"]["fixed"]["max_semantic_actions_per_node"]}
  "The fixed maximum number of authored custom actions on one Node.")

(defconst jetpacs-renderer-profile-contract
  '{el_plist(contract["renderer_profile_schema"])}
  "Contract-projected target profile and exact member-support shape.")

(defconst jetpacs-widget-surface-contract
  '{el_plist(contract["surface_spec_variants"]["widget"])}
  "Contract-projected `widget:*' SurfaceSpec wrapper.")

(defconst jetpacs-widget-size-variant-contract
  '{el_plist(contract["widget_size_variant_schema"])}
  "Contract-projected adaptive widget body shape.")

(defconst jetpacs-swipe-contract
  '{el_plist(contract["swipe_schema"])}
  "Contract-projected legacy and reveal-first rich swipe shapes.")

(defconst jetpacs-max-widget-nodes
  {contract["limits"]["fixed"]["max_widget_nodes"]})
(defconst jetpacs-max-widget-lazy-items
  {contract["limits"]["fixed"]["max_widget_lazy_items"]})
(defconst jetpacs-max-widget-node-depth
  {contract["limits"]["fixed"]["max_widget_node_depth"]})
(defconst jetpacs-max-widget-size-variants
  {contract["limits"]["fixed"]["max_widget_size_variants"]})
(defconst jetpacs-max-widget-remote-views-bytes
  {contract["limits"]["fixed"]["max_widget_remote_views_bytes"]})
(defconst jetpacs-max-swipe-actions-per-side
  {contract["limits"]["fixed"]["max_swipe_actions_per_side"]})

(defconst jetpacs-accessible-name-precedence
  '({el_strings(semantics["accessible_name_precedence"])})
  "Accessible-name sources in normative first-present order.")

(defconst jetpacs-default-node-semantics
  '(
{chr(10).join(default_semantic_rows)})
  "Contract-projected roles and state derivations keyed by Node type.")

(defconst jetpacs-contract-enums
  '(
{chr(10).join(enum_rows)})
  "Contract-projected enum values keyed by their qualified field name.")

(defconst jetpacs-field-types
  '(
{chr(10).join(field_type_rows)})
  "Contract-projected wire field names and their declared value types.")

(defconst jetpacs-action-hook-keys
  '({el_strings(actions["hook_keys"])})
  "Contract-projected Node members which accept an ActionDescriptor.")

(defconst jetpacs-action-descriptor-fields
  '({el_strings(actions["descriptor_fields"])})
  "Contract-projected fields named by the ActionDescriptor envelope.")

(defconst jetpacs-action-offline-policies
  '({el_strings(actions["offline_policies"])})
  "Contract-projected remote-action offline policy vocabulary.")

(defconst jetpacs-action-offline-default
  {el_value(actions["offline_default"])}
  "Contract-projected offline policy used when the member is absent.")

(defconst jetpacs-action-descriptor-schema
  '(
{chr(10).join(action_schema_rows)})
  "Contract-projected closed schemas for remote and builtin actions.
Each row is (KIND :required (MEMBER...) :optional (MEMBER...)).")

(defconst jetpacs-action-injections
  '(
{chr(10).join(action_injection_rows)})
  "Contract-projected receiver-injected arguments keyed by action hook.")

(defconst jetpacs-action-open-surface-feature
  {el_value(actions["open_surface_feature"])}
  "Feature required by the ActionDescriptor `open_surface' member.")

(defconst jetpacs-toolbar-contract
  '{el_plist(contract["toolbar"])}
  "Contract-projected Editor toolbar operation and placeholder vocabulary.")

(defconst jetpacs-text-input-contract
  '{el_plist(contract["text_input_schema"])}
  "Contract-projected §17.4 text-input constraint envelope.")

(defconst jetpacs-node-schema
  '(
{chr(10).join(rows)})
  "Contract members per node type: (TYPE (REQUIRED...) (OPTIONAL...)).
WIRE names, so the table compares directly against contract.json.  The
constructors spell a multi-word member with a hyphen (`:content-padding'
for `content_padding'); `jetpacs--wire-name' is the map between them.")

(provide 'jetpacs-vocabulary)
;;; jetpacs-vocabulary.el ends here
'''

if options.check:
    if not OUT.exists() or OUT.read_text(encoding="utf-8") != body:
        raise SystemExit(f"stale generated vocabulary: {OUT.relative_to(ROOT)}")
    print(f"checked {OUT.relative_to(ROOT)}: {len(rows)} node types")
else:
    OUT.write_text(body, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(rows)} node types")
