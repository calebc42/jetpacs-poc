#!/usr/bin/env python3
"""Generate Glasspane renderer vocabulary for Kotlin and Elisp.

The EBP contract intentionally does not register design systems.  This script
projects the renderer-owned manifest into the two Jetpacs endpoints so their
schema, target support, stateful rules, and extension identity cannot drift.
"""

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "renderer-extensions" / "glasspane-material3.json"
manifest = json.loads(SOURCE.read_text(encoding="utf-8"))


def kt_set(values):
    return "setOf(" + ", ".join(f'"{value}"' for value in values) + ")"


def el_strings(values):
    return " ".join(f'"{value}"' for value in values)


schema_rows = []
for node_type in manifest["node_types"]:
    row = manifest["node_schema"][node_type]
    schema_rows.append(
        f'    "{node_type}" to NodeRow({kt_set(row["required"])}, '
        f'{kt_set(row["optional"])}),'
    )

target_rows = [
    f'    "{target}" to {kt_set(node_types)},'
    for target, node_types in manifest["targets"].items()
]
stateful_rows = [
    f'    "{node_type}" to "{member}",'
    for node_type, member in manifest["stateful_when_present"].items()
]
non_empty_rows = [
    f'    "{node_type}" to {kt_set(members)},'
    for node_type, members in manifest["at_least_one_non_empty"].items()
]

kotlin = f'''// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/glasspane-material3.json (format
// {manifest["format"]}) by tools/gen-renderer-extension-vocabulary.py.
// DO NOT EDIT: GlasspaneMaterial3VocabularyTest pins this projection.
package com.calebc42.ebp.companion.render

import com.calebc42.ebp.wire.NodeRow

const val GLASSPANE_MATERIAL3_EXTENSION = "{manifest["extension"]}"

val GLASSPANE_MATERIAL3_NODE_SCHEMA: Map<String, NodeRow> = mapOf(
{chr(10).join(schema_rows)}
)

val GLASSPANE_MATERIAL3_TARGET_NODE_TYPES: Map<String, Set<String>> = mapOf(
{chr(10).join(target_rows)}
)

val GLASSPANE_MATERIAL3_STATEFUL_WHEN_PRESENT: Map<String, String> = mapOf(
{chr(10).join(stateful_rows)}
)

val GLASSPANE_MATERIAL3_AT_LEAST_ONE_NON_EMPTY: Map<String, Set<String>> = mapOf(
{chr(10).join(non_empty_rows)}
)
'''

kotlin_out = (
    ROOT
    / "companion/renderer/material3/src/main/kotlin/com/calebc42/ebp/companion/render"
    / "GlasspaneMaterial3Vocabulary.kt"
)
kotlin_out.write_text(kotlin, encoding="utf-8")


el_schema_rows = []
for node_type in manifest["node_types"]:
    row = manifest["node_schema"][node_type]
    el_schema_rows.append(
        f'    ("{node_type}" ({el_strings(sorted(row["required"]))}) '
        f'({el_strings(sorted(row["optional"]))}))'
    )

el_targets = [
    f'    ({target} . ({el_strings(node_types)}))'
    for target, node_types in manifest["targets"].items()
]

elisp = f''';;; glasspane-material3-vocabulary.el --- generated renderer vocabulary -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; GENERATED from renderer-extensions/glasspane-material3.json (format
;; {manifest["format"]}) by tools/gen-renderer-extension-vocabulary.py.
;; DO NOT EDIT.  This is Glasspane authority, not an EBP registry.

;;; Code:

(defconst glasspane-material3-extension "{manifest["extension"]}"
  "Renderer extension implemented by Glasspane's Material design layer.")

(defconst glasspane-material3-node-schema
  '(
{chr(10).join(el_schema_rows)})
  "Glasspane Material node schemas as (TYPE REQUIRED OPTIONAL).")

(defconst glasspane-material3-target-node-types
  '(
{chr(10).join(el_targets)})
  "Glasspane Material node types supported by each Jetpacs target.")

(provide 'glasspane-material3-vocabulary)
;;; glasspane-material3-vocabulary.el ends here
'''

elisp_out = (
    ROOT
    / "emacs/apps/glasspane-material3/glasspane-material3-vocabulary.el"
)
elisp_out.write_text(elisp, encoding="utf-8")

print(
    "wrote",
    kotlin_out.relative_to(ROOT),
    "and",
    elisp_out.relative_to(ROOT),
    f'({len(manifest["node_types"])} node types)',
)
