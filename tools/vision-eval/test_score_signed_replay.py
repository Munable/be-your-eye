from __future__ import annotations

import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = Path(__file__).with_name("score_signed_replay.py")
SPEC = importlib.util.spec_from_file_location("score_signed_replay", MODULE_PATH)
assert SPEC and SPEC.loader
score = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(score)


class ExactReplayCohortTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.suite = json.loads(
            (ROOT / "model-tools/v3/evaluation/external-replay-set-v1.json").read_text(
                encoding="utf-8",
            ),
        )
        cls.catalog = json.loads(
            (
                ROOT
                / "model-tools/v3/releases/current-internal/templates/catalog.template.json"
            ).read_text(encoding="utf-8"),
        )

    def package(self, package_id: str) -> dict:
        return score.package_definition(self.suite, package_id)

    def test_reference_requires_all_six_identities_and_eighteen_cases(self) -> None:
        package_id = "similarity_mediapipe_mobilenet_v3_large_v1"
        cases = []
        for target_index in range(6):
            target = f"reference-{target_index}"
            for index in range(2):
                cases.append(
                    {
                        "id": f"{target}-same-{index}",
                        "target_id": target,
                        "expected": {"reference_relation": "same_object"},
                    },
                )
            cases.append(
                {
                    "id": f"{target}-different",
                    "target_id": target,
                    "expected": {"reference_relation": "different_object"},
                },
            )
        score.require_exact_cohort(
            package_id,
            self.package(package_id),
            self.catalog,
            cases,
            None,
        )
        with self.assertRaisesRegex(score.ScoreError, "Reference cohort"):
            score.require_exact_cohort(
                package_id,
                self.package(package_id),
                self.catalog,
                cases[:-1],
                None,
            )

    def test_reader_requires_exact_full_and_fixed_manual_roi_cases(self) -> None:
        package_id = "numeric_reader_ppocrv6_medium_v1"
        package = self.package(package_id)
        manual_numbers = package["selection"]["manual_roi_diagnostic"]["file_numbers"]
        full = [
            {
                "id": f"full-{number:03d}",
                "expected": {"input_mode": "full_frame"},
                "source": {"published_file_number": number},
            }
            for number in range(1, 169)
        ]
        manual = [
            {
                "id": f"manual-{number:03d}",
                "expected": {"input_mode": "manual_roi_diagnostic"},
                "source": {"published_file_number": number},
            }
            for number in manual_numbers
        ]
        score.require_exact_cohort(package_id, package, self.catalog, full + manual, None)
        with self.assertRaisesRegex(score.ScoreError, "Reader cohort"):
            score.require_exact_cohort(package_id, package, self.catalog, full + manual[:-1], None)

    def test_lite2_requires_every_catalog_target_with_one_plus_two_roles(self) -> None:
        package_id = "efficientdet_lite2_object_v1"
        targets = score.catalog_targets(self.catalog, package_id)
        cases = []
        for target in sorted(targets):
            cases.append(
                {
                    "id": f"{target}-anchor",
                    "target_id": target,
                    "source": {"evaluation_role": "largest_bbox_anchor"},
                },
            )
            cases.extend(
                {
                    "id": f"{target}-observational-{index}",
                    "target_id": target,
                    "source": {"evaluation_role": "observational"},
                }
                for index in range(2)
            )
        score.require_exact_cohort(
            package_id,
            self.package(package_id),
            self.catalog,
            cases,
            None,
        )
        removed_target = sorted(targets)[0]
        with self.assertRaisesRegex(score.ScoreError, "target set differs"):
            score.require_exact_cohort(
                package_id,
                self.package(package_id),
                self.catalog,
                [case for case in cases if case["target_id"] != removed_target],
                None,
            )

    def test_source_indexes_are_exactly_derived_from_the_tracked_suite(self) -> None:
        for package in self.suite["packages"]:
            identity = score.expected_source_index(package)
            self.assertEqual(set(identity), {package["dataset_id"]})
            self.assertRegex(identity[package["dataset_id"]], r"^[0-9a-f]{64}$")
            score.require_source_index(package, identity)
            with self.assertRaisesRegex(score.ScoreError, "source-index identity differs"):
                score.require_source_index(package, {package["dataset_id"]: "0" * 64})

    def test_object_target_label_presence_includes_localization_mismatches(self) -> None:
        metric = score.object_target_label_present_metric(
            [
                {"outcome": "hit"},
                {"outcome": "localization_mismatch"},
                {"outcome": "miss"},
                {"outcome": "unavailable"},
            ],
        )
        self.assertEqual(metric, "target_label_present=2/4=0.5000")
        with self.assertRaisesRegex(score.ScoreError, "at least one case"):
            score.object_target_label_present_metric([])

    def test_suite_policy_hash_changes_with_observation_contract(self) -> None:
        import copy

        changed = copy.deepcopy(self.suite)
        changed["packages"][0]["observation_contract"]["runner_errors_maximum"] = 1
        self.assertNotEqual(
            score.canonical_json_sha256(self.suite),
            score.canonical_json_sha256(changed),
        )

    def test_reference_unavailable_is_not_counted_as_a_correct_absence(self) -> None:
        package_id = "similarity_mediapipe_mobilenet_v3_large_v1"
        filtered_by_id = {}
        result_by_id = {}
        for index in range(12):
            case_id = f"same-{index}"
            filtered_by_id[case_id] = {"expected": {"reference_relation": "same_object"}}
            result_by_id[case_id] = {
                "expected": "same_object",
                "outcome": "correct_same_object",
            }
        for index in range(6):
            case_id = f"different-{index}"
            filtered_by_id[case_id] = {"expected": {"reference_relation": "different_object"}}
            result_by_id[case_id] = {
                "expected": "different_object",
                "outcome": "correct_different_object",
            }
        metrics = score.summarize_reference_results(
            result_by_id=result_by_id,
            filtered_by_id=filtered_by_id,
        )
        self.assertIn("unavailable_states=0", metrics)

        result_by_id["different-0"]["outcome"] = "unavailable"
        metrics = score.summarize_reference_results(
            result_by_id=result_by_id,
            filtered_by_id=filtered_by_id,
        )
        self.assertIn("unavailable_states=1", metrics)

        result_by_id["different-0"]["outcome"] = "correct_same_object"
        with self.assertRaisesRegex(score.ScoreError, "outcome is invalid"):
            score.summarize_reference_results(
                result_by_id=result_by_id,
                filtered_by_id=filtered_by_id,
            )


class PreparedReplayCacheTest(unittest.TestCase):
    def test_corrupted_prepared_cache_is_rebuilt_from_bound_inputs(self) -> None:
        package_id = "efficientdet_lite2_object_v1"
        suite = json.loads(
            (ROOT / "model-tools/v3/evaluation/external-replay-set-v1.json").read_text(
                encoding="utf-8",
            ),
        )
        catalog_version = suite["catalog"]["catalog_version"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "bundle"
            fixtures = root / "fixtures"
            work = root / "work"
            asset = bundle / "media/fixture.jpg"
            artifact = fixtures / "artifacts" / package_id / "model.tflite"
            asset.parent.mkdir(parents=True)
            artifact.parent.mkdir(parents=True)
            asset.write_bytes(b"external replay media")
            artifact.write_bytes(b"signed model artifact")

            import hashlib

            def digest(value: bytes) -> str:
                return hashlib.sha256(value).hexdigest()

            manifest = {
                "package_id": package_id,
                "package_version": "fixture",
                "artifacts": [
                    {
                        "role": "primary",
                        "sha256": digest(artifact.read_bytes()),
                        "size_bytes": artifact.stat().st_size,
                    },
                ],
            }
            manifest_bytes = (json.dumps(manifest) + "\n").encode()
            signed_manifest = fixtures / "release/manifests" / f"{package_id}.json"
            signed_manifest.parent.mkdir(parents=True)
            signed_manifest.write_bytes(manifest_bytes)
            catalog = {
                "catalog_version": catalog_version,
                "packages": [
                    {
                        "package_id": package_id,
                        "status": "active",
                        "manifest_sha256": digest(manifest_bytes),
                    },
                ],
            }
            catalog_path = fixtures / "release/catalog.json"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            bundle_manifest = {
                "schema_version": "beyoureye.external-replay-bundle.v1",
                "suite_id": "fixture",
                "catalog_version": catalog_version,
                "source_index_sha256": {"coco-2017-val": "a" * 64},
                "cases": [
                    {
                        "id": "fixture-case",
                        "dataset_id": "coco-2017-val",
                        "package_id": package_id,
                        "asset": "media/fixture.jpg",
                        "asset_sha256": digest(asset.read_bytes()),
                        "asset_size_bytes": asset.stat().st_size,
                        "expected": {"present": True},
                    },
                ],
            }
            bundle.joinpath("manifest.json").write_text(json.dumps(bundle_manifest), encoding="utf-8")

            command = [
                str(ROOT / "tools/vision-eval/run-signed-replay.sh"),
                "--package",
                package_id,
                "--bundle",
                str(bundle),
                "--fixtures",
                str(fixtures),
                "--work-root",
                str(work),
                "--prepare-only",
            ]
            first = subprocess.run(command, cwd=ROOT, check=False, capture_output=True, text=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            prepared_asset = (
                work
                / "prepared"
                / package_id
                / "bundle"
                / "media"
                / "fixture.jpg"
            )
            prepared_asset.unlink()
            prepared_asset.write_bytes(b"corrupted cache")
            second = subprocess.run(command, cwd=ROOT, check=False, capture_output=True, text=True)
            self.assertEqual(second.returncode, 0, second.stderr)
            self.assertIn("REBUILD:", second.stdout)
            self.assertEqual(prepared_asset.read_bytes(), asset.read_bytes())


class HostBinaryIdentityTest(unittest.TestCase):
    def make_fixture(self, root: Path, *, clean: bool = True) -> tuple[Path, Path, Path]:
        app_apk = root / "app.apk"
        test_apk = root / "test.apk"
        metadata_path = root / "host-run-metadata.json"
        app_apk.write_bytes(b"exact functional APK")
        test_apk.write_bytes(b"exact package-filtered test APK")

        def apk_identity(path: Path) -> dict[str, object]:
            digest = score.file_sha256(path)
            return {
                "sha256": digest,
                "size_bytes": path.stat().st_size,
                "installed_sha256": digest,
            }

        metadata_path.write_text(
            json.dumps(
                {
                    "schema_version": score.RUN_METADATA_SCHEMA,
                    "recorded_at": "2026-08-30T00:00:00Z",
                    "package_id": "efficientdet_lite2_object_v1",
                    "input_mode_filter": "all",
                    "implementation_commit": "a" * 40,
                    "git_status_clean": clean,
                    "device": {
                        "manufacturer": "Fixture",
                        "model": "PJA110",
                        "api_level": 35,
                        "primary_abi": "arm64-v8a",
                        "build_fingerprint": "fixture/pja110/build:user/release-keys",
                    },
                    "app_apk": apk_identity(app_apk),
                    "test_apk": apk_identity(test_apk),
                },
            ),
            encoding="utf-8",
        )
        return metadata_path, app_apk, test_apk

    def verify(
        self,
        metadata_path: Path,
        app_apk: Path,
        test_apk: Path,
    ) -> dict:
        return score.verify_host_binary_identities(
            run_metadata_path=metadata_path,
            app_apk_path=app_apk,
            test_apk_path=test_apk,
            package_id="efficientdet_lite2_object_v1",
            input_mode_filter=None,
        )

    def test_exact_host_and_installed_apks_bind_to_implementation_commit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            metadata, app_apk, test_apk = self.make_fixture(Path(directory))
            identities = self.verify(metadata, app_apk, test_apk)
            self.assertEqual(identities["implementation_commit"], "a" * 40)
            self.assertTrue(identities["git_status_clean"])
            self.assertEqual(identities["app_apk_sha256"], score.file_sha256(app_apk))
            self.assertEqual(identities["test_apk_sha256"], score.file_sha256(test_apk))

    def test_apk_change_after_install_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            metadata, app_apk, test_apk = self.make_fixture(Path(directory))
            test_apk.write_bytes(b"different test APK")
            with self.assertRaisesRegex(score.ScoreError, "test APK bytes differ"):
                self.verify(metadata, app_apk, test_apk)

    def test_dirty_source_is_recorded_without_turning_observation_into_quality_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            metadata, app_apk, test_apk = self.make_fixture(Path(directory), clean=False)
            self.assertFalse(self.verify(metadata, app_apk, test_apk)["git_status_clean"])


if __name__ == "__main__":
    unittest.main()
