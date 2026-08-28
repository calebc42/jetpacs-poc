#!/usr/bin/env python3
"""Hermetic CLI tests for the renderer-extension vocabulary generator."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
GENERATOR = ROOT / "tools" / "gen-renderer-extension-vocabulary.py"


def example_manifest() -> dict:
    """Return a non-Glasspane manifest that exercises every projection map."""
    return {
        "format": 1,
        "extension": "jetpacs.components",
        "description": "Jetpacs' own component design implementation.",
        "node_types": ["jetpacs.action", "jetpacs.choice"],
        "targets": {
            "app": ["jetpacs.action", "jetpacs.choice"],
            "dialog": ["jetpacs.action"],
            "notification": [],
        },
        "node_schema": {
            "jetpacs.action": {
                "required": ["label", "on_tap"],
                "optional": ["enabled"],
            },
            "jetpacs.choice": {
                "required": ["label", "selected", "on_change"],
                "optional": ["description"],
            },
        },
        "stateful_when_present": {"jetpacs.choice": "selected"},
        "at_least_one_non_empty": {"jetpacs.action": ["label"]},
    }


class RendererExtensionGeneratorTest(unittest.TestCase):
    """Pin arbitrary projection, manifest validation, and drift checking."""

    def run_generator(self, *arguments: object) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(GENERATOR), *(str(value) for value in arguments)],
            cwd="/",
            text=True,
            capture_output=True,
            check=False,
        )

    def test_projects_an_arbitrary_manifest_and_checks_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            manifest = temp / "jetpacs-components.json"
            kotlin = temp / "JetpacsComponentsVocabulary.kt"
            elisp = temp / "jetpacs-components-vocabulary.el"
            manifest.write_text(json.dumps(example_manifest()), encoding="utf-8")

            arguments = (
                manifest,
                "--kotlin-output",
                kotlin,
                "--kotlin-package",
                "com.example.render",
                "--elisp-output",
                elisp,
            )
            generated = self.run_generator(*arguments)
            self.assertEqual(0, generated.returncode, generated.stderr)

            kotlin_text = kotlin.read_text(encoding="utf-8")
            self.assertIn("package com.example.render", kotlin_text)
            self.assertIn(
                'const val JETPACS_COMPONENTS_EXTENSION = "jetpacs.components"',
                kotlin_text,
            )
            self.assertIn("JetpacsComponentsVocabularyTest", kotlin_text)

            elisp_text = elisp.read_text(encoding="utf-8")
            self.assertIn(
                '(defconst jetpacs-components-extension "jetpacs.components"',
                elisp_text,
            )
            self.assertIn("Jetpacs Components node schemas", elisp_text)
            self.assertIn("implemented by Jetpacs' Components", elisp_text)
            self.assertIn("(provide 'jetpacs-components-vocabulary)", elisp_text)

            checked = self.run_generator(*arguments, "--check")
            self.assertEqual(0, checked.returncode, checked.stderr)
            kotlin.write_text(f"{kotlin_text}// stale\n", encoding="utf-8")
            stale = self.run_generator(*arguments, "--check")
            self.assertEqual(1, stale.returncode)
            self.assertIn("stale generated file", stale.stderr)

    def test_non_default_manifest_requires_explicit_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "jetpacs-components.json"
            manifest.write_text(json.dumps(example_manifest()), encoding="utf-8")
            result = self.run_generator(manifest)
            self.assertEqual(2, result.returncode)
            self.assertIn("--kotlin-output is required", result.stderr)

    def test_checked_in_jetpacs_projection_is_current(self) -> None:
        result = self.run_generator(
            ROOT / "renderer-extensions" / "jetpacs-components.json",
            "--kotlin-output",
            ROOT
            / "companion/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/JetpacsComponentsVocabulary.kt",
            "--kotlin-package",
            "com.calebc42.jetpacs.renderer.jetpacs",
            "--elisp-output",
            ROOT / "emacs/apps/jetpacs-components/jetpacs-components-vocabulary.el",
            "--check",
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_an_ambiguous_schema(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            authored = example_manifest()
            authored["node_types"].append("jetpacs.action")
            manifest = temp / "invalid.json"
            manifest.write_text(json.dumps(authored), encoding="utf-8")
            result = self.run_generator(
                manifest,
                "--kotlin-output",
                temp / "Invalid.kt",
                "--elisp-output",
                temp / "invalid.el",
            )
            self.assertEqual(2, result.returncode)
            self.assertIn("must not contain duplicates", result.stderr)


if __name__ == "__main__":
    unittest.main()
