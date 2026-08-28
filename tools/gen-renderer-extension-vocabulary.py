#!/usr/bin/env python3
"""Project any renderer-owned extension manifest into Kotlin and Elisp.

EBP intentionally does not register design systems.  This generator keeps a
renderer extension's schema, target support, stateful rules, and identity in
one manifest while producing the endpoint vocabulary consumed by Jetpacs.

With no arguments it regenerates the established Glasspane Material 3 files:

    python3 tools/gen-renderer-extension-vocabulary.py

Another extension supplies its manifest and both explicit output paths:

    python3 tools/gen-renderer-extension-vocabulary.py \
      renderer-extensions/example-design.json \
      --kotlin-output companion/renderer/example/src/main/kotlin/\
com/example/render/ExampleDesignVocabulary.kt \
      --kotlin-package com.example.render \
      --elisp-output emacs/apps/example-design/example-design-vocabulary.el

Use ``--check`` in verification to reject stale generated files without
modifying the worktree.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MANIFEST = ROOT / "renderer-extensions" / "glasspane-material3.json"
DEFAULT_KOTLIN_OUTPUT = (
    ROOT
    / "companion/renderer/material3/src/main/kotlin/com/calebc42/ebp/companion/render"
    / "GlasspaneMaterial3Vocabulary.kt"
)
DEFAULT_ELISP_OUTPUT = (
    ROOT
    / "emacs/apps/glasspane-material3/glasspane-material3-vocabulary.el"
)
DEFAULT_KOTLIN_PACKAGE = "com.calebc42.ebp.companion.render"

_EXTENSION_ID = re.compile(r"[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*\Z")
_TARGET_ID = re.compile(r"[a-z][a-z0-9_-]*\Z")
_KOTLIN_PACKAGE = re.compile(
    r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*\Z"
)


class ManifestError(ValueError):
    """The renderer manifest cannot be projected without guessing."""


def _repo_path(value: str | Path) -> Path:
    """Resolve VALUE against the repository, independent of the caller's cwd."""
    path = Path(value)
    return path if path.is_absolute() else ROOT / path


def _display_path(path: Path) -> str:
    """Return a stable repository-relative path when PATH belongs to this tree."""
    try:
        return path.resolve().relative_to(ROOT.resolve()).as_posix()
    except ValueError:
        return path.resolve().as_posix()


def _string_list(value: Any, where: str, *, allow_empty: bool = True) -> list[str]:
    if not isinstance(value, list) or any(
        not isinstance(item, str) or not item for item in value
    ):
        raise ManifestError(f"{where} must be an array of non-empty strings")
    if not allow_empty and not value:
        raise ManifestError(f"{where} must not be empty")
    if len(value) != len(set(value)):
        raise ManifestError(f"{where} must not contain duplicates")
    return value


def _mapping(value: Any, where: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ManifestError(f"{where} must be an object")
    return value


def load_manifest(path: Path) -> dict[str, Any]:
    """Read and validate the manifest fields this projection consumes."""
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except OSError as error:
        raise ManifestError(f"cannot read {path}: {error}") from error
    except json.JSONDecodeError as error:
        raise ManifestError(f"invalid JSON in {path}: {error}") from error

    manifest = _mapping(raw, "manifest")
    if type(manifest.get("format")) is not int or manifest["format"] < 1:
        raise ManifestError("manifest.format must be a positive integer")

    extension = manifest.get("extension")
    if not isinstance(extension, str) or not _EXTENSION_ID.fullmatch(extension):
        raise ManifestError(
            "manifest.extension must be a lowercase separated identifier"
        )
    description = manifest.get("description")
    if not isinstance(description, str) or not description.strip():
        raise ManifestError("manifest.description must be a non-empty string")

    node_types = _string_list(
        manifest.get("node_types"), "manifest.node_types", allow_empty=False
    )
    known_types = set(node_types)
    schemas = _mapping(manifest.get("node_schema"), "manifest.node_schema")
    if set(schemas) != known_types:
        raise ManifestError(
            "manifest.node_schema keys must exactly match manifest.node_types"
        )
    for node_type in node_types:
        row = _mapping(schemas[node_type], f"manifest.node_schema[{node_type!r}]")
        if set(row) != {"required", "optional"}:
            raise ManifestError(
                f"manifest.node_schema[{node_type!r}] must contain only "
                "required and optional"
            )
        required = _string_list(
            row["required"], f"manifest.node_schema[{node_type!r}].required"
        )
        optional = _string_list(
            row["optional"], f"manifest.node_schema[{node_type!r}].optional"
        )
        overlap = set(required) & set(optional)
        if overlap:
            raise ManifestError(
                f"manifest.node_schema[{node_type!r}] repeats members across "
                f"required and optional: {sorted(overlap)}"
            )

    targets = _mapping(manifest.get("targets"), "manifest.targets")
    if not targets:
        raise ManifestError("manifest.targets must not be empty")
    for target, values in targets.items():
        if not isinstance(target, str) or not _TARGET_ID.fullmatch(target):
            raise ManifestError(f"invalid target identifier: {target!r}")
        members = _string_list(values, f"manifest.targets[{target!r}]")
        unknown = set(members) - known_types
        if unknown:
            raise ManifestError(
                f"manifest.targets[{target!r}] names unknown nodes: "
                f"{sorted(unknown)}"
            )

    stateful = _mapping(
        manifest.get("stateful_when_present"),
        "manifest.stateful_when_present",
    )
    non_empty = _mapping(
        manifest.get("at_least_one_non_empty"),
        "manifest.at_least_one_non_empty",
    )
    for node_type, member in stateful.items():
        if node_type not in known_types:
            raise ManifestError(
                f"manifest.stateful_when_present names unknown node {node_type!r}"
            )
        if not isinstance(member, str) or not member:
            raise ManifestError(
                f"manifest.stateful_when_present[{node_type!r}] must be a member"
            )
        row = schemas[node_type]
        if member not in row["required"] + row["optional"]:
            raise ManifestError(
                f"manifest.stateful_when_present[{node_type!r}] names unknown "
                f"member {member!r}"
            )
    for node_type, values in non_empty.items():
        if node_type not in known_types:
            raise ManifestError(
                f"manifest.at_least_one_non_empty names unknown node {node_type!r}"
            )
        members = _string_list(
            values,
            f"manifest.at_least_one_non_empty[{node_type!r}]",
            allow_empty=False,
        )
        row = schemas[node_type]
        unknown = set(members) - set(row["required"] + row["optional"])
        if unknown:
            raise ManifestError(
                f"manifest.at_least_one_non_empty[{node_type!r}] names unknown "
                f"members: {sorted(unknown)}"
            )
    return manifest


def _identity(extension: str) -> tuple[str, str, str, str, str]:
    """Return Pascal, constant, Elisp, authority, and human design prefixes."""
    parts = re.split(r"[._-]", extension)
    pascal = "".join(part[0].upper() + part[1:] for part in parts)
    constant = "_".join(part.upper() for part in parts)
    elisp = "-".join(parts)

    def human(part: str) -> str:
        return re.sub(r"(?<=[a-z])(?=[0-9])", " ", part).capitalize()

    authority = human(parts[0])
    design = " ".join(human(part) for part in parts[1:]) or authority
    design_family = re.sub(r"\s+[0-9]+\Z", "", design)
    human_prefix = (
        f"{authority} {design_family}" if len(parts) > 1 else authority
    )
    return pascal, constant, elisp, authority, human_prefix


def _quoted(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def _kt_set(values: list[str]) -> str:
    return "setOf(" + ", ".join(_quoted(value) for value in values) + ")"


def _el_strings(values: list[str]) -> str:
    return " ".join(_quoted(value) for value in values)


def render_kotlin(
    manifest: dict[str, Any], source_label: str, package_name: str
) -> str:
    """Render one Kotlin projection without consulting an endpoint registry."""
    pascal, constant, _, _, _ = _identity(manifest["extension"])
    schema_rows = []
    for node_type in manifest["node_types"]:
        row = manifest["node_schema"][node_type]
        schema_rows.append(
            f'    "{node_type}" to NodeRow({_kt_set(row["required"])}, '
            f'{_kt_set(row["optional"])}),'
        )
    target_rows = [
        f'    "{target}" to {_kt_set(node_types)},'
        for target, node_types in manifest["targets"].items()
    ]
    stateful_rows = [
        f'    "{node_type}" to "{member}",'
        for node_type, member in manifest["stateful_when_present"].items()
    ]
    non_empty_rows = [
        f'    "{node_type}" to {_kt_set(members)},'
        for node_type, members in manifest["at_least_one_non_empty"].items()
    ]
    return f'''// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from {source_label} (format
// {manifest["format"]}) by tools/gen-renderer-extension-vocabulary.py.
// DO NOT EDIT: {pascal}VocabularyTest pins this projection.
package {package_name}

import com.calebc42.ebp.wire.NodeRow

const val {constant}_EXTENSION = "{manifest["extension"]}"

val {constant}_NODE_SCHEMA: Map<String, NodeRow> = mapOf(
{chr(10).join(schema_rows)}
)

val {constant}_TARGET_NODE_TYPES: Map<String, Set<String>> = mapOf(
{chr(10).join(target_rows)}
)

val {constant}_STATEFUL_WHEN_PRESENT: Map<String, String> = mapOf(
{chr(10).join(stateful_rows)}
)

val {constant}_AT_LEAST_ONE_NON_EMPTY: Map<String, Set<String>> = mapOf(
{chr(10).join(non_empty_rows)}
)
'''


def render_elisp(
    manifest: dict[str, Any], source_label: str, output_name: str
) -> str:
    """Render one Elisp projection using names derived from the extension ID."""
    _, _, prefix, authority, human_prefix = _identity(manifest["extension"])
    schema_rows = []
    for node_type in manifest["node_types"]:
        row = manifest["node_schema"][node_type]
        schema_rows.append(
            f'    ("{node_type}" ({_el_strings(sorted(row["required"]))}) '
            f'({_el_strings(sorted(row["optional"]))}))'
        )
    targets = [
        f'    ({target} . ({_el_strings(node_types)}))'
        for target, node_types in manifest["targets"].items()
    ]
    design_family = human_prefix.removeprefix(f"{authority} ")
    possessive = f"{authority}'" if authority.endswith("s") else f"{authority}'s"
    return f''';;; {output_name} --- generated renderer vocabulary -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; GENERATED from {source_label} (format
;; {manifest["format"]}) by tools/gen-renderer-extension-vocabulary.py.
;; DO NOT EDIT.  This is {authority} authority, not an EBP registry.

;;; Code:

(defconst {prefix}-extension "{manifest["extension"]}"
  "Renderer extension implemented by {possessive} {design_family} design layer.")

(defconst {prefix}-node-schema
  '(
{chr(10).join(schema_rows)})
  "{human_prefix} node schemas as (TYPE REQUIRED OPTIONAL).")

(defconst {prefix}-target-node-types
  '(
{chr(10).join(targets)})
  "{human_prefix} node types supported by each Jetpacs target.")

(provide '{prefix}-vocabulary)
;;; {output_name} ends here
'''


def _write_or_check(path: Path, content: str, check: bool) -> bool:
    """Write CONTENT or return whether PATH already contains it in check mode."""
    if check:
        try:
            current = path.read_text(encoding="utf-8")
        except OSError:
            return False
        return current == content
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return True


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "manifest",
        nargs="?",
        default=DEFAULT_MANIFEST,
        help="renderer-extension manifest (defaults to Glasspane Material 3)",
    )
    parser.add_argument(
        "--kotlin-output",
        help="generated Kotlin file; required for a non-default manifest",
    )
    parser.add_argument(
        "--kotlin-package",
        default=DEFAULT_KOTLIN_PACKAGE,
        help=f"Kotlin package (default: {DEFAULT_KOTLIN_PACKAGE})",
    )
    parser.add_argument(
        "--elisp-output",
        help="generated Elisp file; required for a non-default manifest",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail when either output is missing or stale; write nothing",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    manifest_path = _repo_path(args.manifest)
    is_default = manifest_path.resolve() == DEFAULT_MANIFEST.resolve()
    if not is_default and not args.kotlin_output:
        parser.error("--kotlin-output is required for a non-default manifest")
    if not is_default and not args.elisp_output:
        parser.error("--elisp-output is required for a non-default manifest")
    if not _KOTLIN_PACKAGE.fullmatch(args.kotlin_package):
        parser.error("--kotlin-package is not a valid Kotlin package")

    kotlin_output = (
        _repo_path(args.kotlin_output)
        if args.kotlin_output
        else DEFAULT_KOTLIN_OUTPUT
    )
    elisp_output = (
        _repo_path(args.elisp_output) if args.elisp_output else DEFAULT_ELISP_OUTPUT
    )
    try:
        manifest = load_manifest(manifest_path)
    except ManifestError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    source_label = _display_path(manifest_path)
    kotlin = render_kotlin(manifest, source_label, args.kotlin_package)
    elisp = render_elisp(manifest, source_label, elisp_output.name)
    kotlin_ok = _write_or_check(kotlin_output, kotlin, args.check)
    elisp_ok = _write_or_check(elisp_output, elisp, args.check)
    if args.check and not (kotlin_ok and elisp_ok):
        if not kotlin_ok:
            print(
                f"stale generated file: {_display_path(kotlin_output)}",
                file=sys.stderr,
            )
        if not elisp_ok:
            print(
                f"stale generated file: {_display_path(elisp_output)}",
                file=sys.stderr,
            )
        return 1

    verb = "checked" if args.check else "wrote"
    print(
        verb,
        _display_path(kotlin_output),
        "and",
        _display_path(elisp_output),
        f'({len(manifest["node_types"])} node types)',
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
