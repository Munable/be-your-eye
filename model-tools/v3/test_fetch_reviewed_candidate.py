import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from fetch_reviewed_candidate import AcquisitionError, extract_and_hash, load_candidate, verify_archive


def candidate() -> dict:
    return {
        "review_status": "approved_for_acquisition",
        "internal_evaluation_allowed": True,
        "commercial_catalog_eligible": False,
        "license_decision": {"decision": "internal_evaluation_only"},
        "acquisition": {
            "url": "https://example.invalid/model.zip?versionId=fixed",
            "published_size_bytes": 3,
            "published_checksum": {"algorithm": "SHA256", "base64": "fixture"},
        },
    }


class FetchReviewedCandidateTest(unittest.TestCase):
    def test_policy_is_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "candidate.json")
            for field, value in (
                ("review_status", "pending_review"),
                ("internal_evaluation_allowed", False),
                ("commercial_catalog_eligible", True),
            ):
                value_dict = candidate()
                value_dict[field] = value
                path.write_text(json.dumps(value_dict))
                with self.assertRaises(AcquisitionError):
                    load_candidate(path)

    def test_archive_size_and_sha_are_byte_exact(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "artifact.zip")
            path.write_bytes(b"abc")
            self.assertEqual(verify_archive(path, 3), (hashlib.sha256(b"abc").hexdigest(), 3))
            with self.assertRaises(AcquisitionError):
                verify_archive(path, 4)

    def test_zip_extraction_rejects_traversal_and_hashes_safe_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            good = root / "good.zip"
            with zipfile.ZipFile(good, "w") as bundle:
                bundle.writestr("models/model.tflite", b"model")
            result = extract_and_hash(good, root / "good")
            self.assertEqual(result[0]["path"], "models/model.tflite")
            self.assertEqual(result[0]["sha256"], hashlib.sha256(b"model").hexdigest())

            bad = root / "bad.zip"
            with zipfile.ZipFile(bad, "w") as bundle:
                bundle.writestr("../escape", b"bad")
            with self.assertRaises(AcquisitionError):
                extract_and_hash(bad, root / "bad")


if __name__ == "__main__":
    unittest.main()
