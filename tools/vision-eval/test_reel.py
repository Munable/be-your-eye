#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("reel.py")
SPEC = importlib.util.spec_from_file_location("reel", MODULE_PATH)
assert SPEC and SPEC.loader
reel = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reel)


class ReelTest(unittest.TestCase):
    def write_spec(self, root: Path) -> Path:
        absent = root / "external-absent.jpg"
        present = root / "external-present.mp4"
        absent.write_bytes(b"external absent fixture")
        present.write_bytes(b"external present fixture")
        spec = {
            "schema_version": "1.0",
            "reel_id": "public-source-smoke",
            "expectation": {"mode": "object_detection", "target_id": "person"},
            "sources": {
                "absent": {
                    "path": absent.name,
                    "media_type": "image",
                    "source_url": "https://example.test/absent",
                    "license": "test-only provenance fixture",
                    "sha256": hashlib.sha256(absent.read_bytes()).hexdigest(),
                },
                "present": {
                    "path": present.name,
                    "media_type": "video",
                    "start_seconds": 1.25,
                    "source_url": "https://example.test/present",
                    "license": "test-only provenance fixture",
                    "sha256": hashlib.sha256(present.read_bytes()).hexdigest(),
                },
            },
        }
        spec_path = root / "spec.json"
        spec_path.write_text(json.dumps(spec), encoding="utf-8")
        return spec_path

    def test_fixed_timeline_is_5_7_6_7(self) -> None:
        self.assertEqual(
            reel.TIMELINE,
            (("absent", 0, 5), ("present", 5, 12), ("absent", 12, 18), ("present", 18, 25)),
        )

    def test_spec_hashes_and_dry_run_command(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            spec = reel.validate_spec(self.write_spec(Path(directory)))
            command = reel.build_command(spec, Path(directory) / "out.mp4")
            joined = " ".join(command)
            self.assertEqual(spec["sources"]["present"]["media_type"], "video")
            self.assertIn("force_original_aspect_ratio=decrease", joined)
            self.assertIn("pad=1280:720", joined)
            self.assertIn("concat=n=4", joined)
            self.assertEqual(command.count("-i"), 4)

    def test_modified_source_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            spec_path = self.write_spec(root)
            (root / "external-absent.jpg").write_bytes(b"changed")
            with self.assertRaisesRegex(reel.ReelError, "SHA-256 mismatch"):
                reel.validate_spec(spec_path)


if __name__ == "__main__":
    unittest.main()
