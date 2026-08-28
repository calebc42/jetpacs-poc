#!/usr/bin/env python3
"""Generate emacs/jetpacs-vocabulary.el from ebp/contract.json (format 9).

The sibling of tools/gen-vocabulary.py, which does the same for the
Companion's Vocabulary.kt.  Same W0 rule: wire vocabulary is generated from
the authored contract, never hand-maintained; a drift test re-reads
contract.json and fails if the committed generated file disagrees.

SCOPE.  This projects the per-node schema plus the universal Semantics
envelope, nested schemas, enums, limits, accessible-name order, and default
node-derived semantics.  These values must not acquire handwritten twins in
the authoring layer.  Run from the llm-poc-3 root:

    python3 tools/gen-jetpacs-vocabulary.py

Use ``--check`` in verification to fail without rewriting a stale projection.
"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
contract = json.loads((ROOT / "ebp" / "contract.json").read_text(encoding="utf-8"))

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
