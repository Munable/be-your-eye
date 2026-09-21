import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import prepare_external_replay_set as replay


class ExternalReplaySetPreparationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.suite = replay.load_and_validate_suite(replay.DEFAULT_SUITE_PATH)

    def test_suite_matches_three_active_packages(self):
        self.assertEqual(len(self.suite["packages"]), 3)
        self.assertEqual(
            {package["preparation"] for package in self.suite["packages"]},
            {"automated_v1", "automated_from_verified_external_manifest_v1"},
        )
        self.assertFalse(self.suite["storage"]["repository_media_allowed"])
        self.assertEqual(
            {package["selection"]["parameter_policy"] for package in self.suite["packages"]},
            {"frozen signed product defaults; no sweep"},
        )
        reader = replay.package_by_dataset(
            self.suite,
            "mendeley-seven-segment-energy-meter-v1",
        )
        self.assertEqual(len(reader["selection"]["manual_roi_diagnostic"]["file_numbers"]), 24)
        self.assertNotIn("source_summary_sha256", reader["selection"]["manual_roi_diagnostic"])
        lite2 = replay.package_by_dataset(self.suite, "coco-2017-val")
        self.assertEqual(
            lite2["selection"]["cases_per_target"],
            {"largest_bbox_anchor": 1, "observational": 2},
        )
        self.assertEqual(lite2["observation_contract"]["runner_errors_maximum"], 0)

    def test_media_root_rejects_repository_and_non_external_prepare_root(self):
        with self.assertRaises(replay.PreparationError):
            replay.validate_root(replay.REPO_ROOT / ".local/replay", for_media_write=False)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(replay.PreparationError):
                replay.validate_root(Path(directory), for_media_write=True)

    def test_existing_source_checksum_is_fail_closed_without_network(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "annotations.csv")
            path.write_bytes(b"fixture")
            replay.download_file(
                "https://example.invalid/annotations.csv",
                path,
                expected_sha256=hashlib.sha256(b"fixture").hexdigest(),
            )
            with self.assertRaises(replay.PreparationError):
                replay.download_file(
                    "https://example.invalid/annotations.csv",
                    path,
                    expected_sha256="0" * 64,
                )

    def test_coco_selects_three_eligible_images_for_every_catalog_target(self):
        targets = replay.catalog_object_target_ids(self.suite)
        categories = [{"id": index + 1, "name": target.replace("_", " ")} for index, target in enumerate(targets)]
        images = []
        annotations = []
        annotation_id = 1
        image_id = 1
        for category in categories:
            for sample_index in range(4):
                images.append(
                    {
                        "id": image_id,
                        "file_name": f"{image_id:012d}.jpg",
                        "width": 640,
                        "height": 480,
                        "license": 1,
                    },
                )
                annotations.append(
                    {
                        "id": annotation_id,
                        "image_id": image_id,
                        "category_id": category["id"],
                        "bbox": [
                            20,
                            20,
                            [100, 160, 220, 8][sample_index],
                            [80, 120, 180, 8][sample_index],
                        ],
                        "iscrowd": 0,
                    },
                )
                annotation_id += 1
                # A same-label instance that is too small to select the image still belongs to
                # the complete localization truth after another instance selects that image.
                annotations.append(
                    {
                        "id": annotation_id,
                        "image_id": image_id,
                        "category_id": category["id"],
                        "bbox": [300, 200, 8, 8],
                        "iscrowd": 0,
                    },
                )
                annotation_id += 1
                # Crowd annotations remain outside both selection and localization truth.
                annotations.append(
                    {
                        "id": annotation_id,
                        "image_id": image_id,
                        "category_id": category["id"],
                        "bbox": [320, 220, 120, 100],
                        "iscrowd": 1,
                    },
                )
                annotation_id += 1
                image_id += 1
        with tempfile.TemporaryDirectory() as directory:
            annotations_path = Path(directory, "instances_val2017.json")
            annotations_path.write_text(
                json.dumps(
                    {
                        "images": images,
                        "categories": categories,
                        "annotations": annotations,
                        "licenses": [{"id": 1, "name": "fixture", "url": "https://example.invalid/license"}],
                    },
                ),
                encoding="utf-8",
            )
            first = replay.select_coco_cases(self.suite, annotations_path)
            second = replay.select_coco_cases(self.suite, annotations_path)

        self.assertEqual(first, second)
        self.assertEqual(len(first), 80 * 3)
        self.assertEqual(len({case["id"] for case in first}), len(first))
        self.assertTrue(all(case["expected"]["present"] for case in first))
        self.assertTrue(all(len(case["expected"]["source_boxes"]) == 2 for case in first))
        self.assertTrue(all(case["source_license"]["license_url"] for case in first))
        counts = {target: 0 for target in targets}
        for case in first:
            counts[case["target_id"]] += 1
        self.assertEqual(set(counts.values()), {3})
        self.assertEqual(
            sum(case["source"]["evaluation_role"] == "largest_bbox_anchor" for case in first),
            80,
        )
        self.assertEqual(
            sum(case["source"]["evaluation_role"] == "observational" for case in first),
            160,
        )
        for target_index, target_id in enumerate(targets):
            anchor = next(
                case
                for case in first
                if case["target_id"] == target_id
                and case["source"]["evaluation_role"] == "largest_bbox_anchor"
            )
            self.assertEqual(int(anchor["source"]["image_id"]), target_index * 4 + 3)

    def test_mendeley_maps_terminal_file_number_and_x_sorted_digits_for_168_cases(self):
        categories = [{"id": digit + 1, "name": f"Text {digit}"} for digit in range(10)]
        images = []
        annotations = []
        image_paths = {}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            numbers = [number for number in range(1, 170) if number != 97]
            for number in numbers:
                image_id = f"image-{number}"
                images.append(
                    {
                        "id": image_id,
                        "file_name": f"https://example.invalid/account-193903.appspot/path-{number}.JPG",
                        "width": 100,
                        "height": 50,
                    },
                )
                path = root / f"{number}.JPG"
                path.write_bytes(f"image-{number}".encode())
                image_paths[number] = path
                if number in {75, 117}:
                    raw_digits = "814410" if number == 75 else "124410"
                    annotations.extend(
                        {
                            "image_id": image_id,
                            "category_id": int(digit) + 1,
                            "bbox": [5 + index * 14, 5, 10, 30],
                        }
                        for index, digit in enumerate(raw_digits)
                    )
                else:
                    annotations.extend(
                        [
                            {"image_id": image_id, "category_id": 3, "bbox": [60, 5, 20, 30]},
                            {"image_id": image_id, "category_id": 2, "bbox": [10, 5, 20, 30]},
                        ],
                    )
            annotations_path = root / "annotations.json"
            annotations_path.write_text(
                json.dumps({"images": images, "annotations": annotations, "categories": categories}),
                encoding="utf-8",
            )
            with mock.patch.object(
                replay,
                "jpeg_exif_orientation",
                side_effect=lambda path: 3 if int(path.stem) in {75, 117} else 1,
            ):
                cases, assets = replay.select_mendeley_cases(self.suite, annotations_path, image_paths)

            suite = copy.deepcopy(self.suite)
            manual_cases = replay.select_mendeley_manual_roi_cases(suite, cases)
            repeated_cases = replay.select_mendeley_manual_roi_cases(suite, cases)
            invalid_cases = copy.deepcopy(cases)
            invalid_cases[0]["expected"].pop("input_mode")
            with self.assertRaises(replay.PreparationError):
                replay.select_mendeley_manual_roi_cases(suite, invalid_cases)

        self.assertEqual(len(cases), 168)
        self.assertEqual(len(assets), 168)
        by_id = {case["id"]: case for case in cases}
        self.assertEqual(by_id["mendeley-seven-segment__075"]["expected"]["expected_digits"], "014418")
        self.assertEqual(by_id["mendeley-seven-segment__117"]["expected"]["expected_digits"], "014421")
        self.assertEqual(by_id["mendeley-seven-segment__075"]["source"]["exif_orientation"], 3)
        self.assertEqual(by_id["mendeley-seven-segment__117"]["source"]["exif_orientation"], 3)
        self.assertEqual({case["expected"]["input_mode"] for case in cases}, {"full_frame"})
        self.assertNotIn("expected_exact", cases[0]["expected"])
        self.assertNotIn("value_decimal", cases[0]["expected"])
        self.assertEqual(len(manual_cases), 24)
        self.assertEqual(
            [case["id"] for case in manual_cases],
            [case["id"] for case in repeated_cases],
        )
        self.assertEqual(len({case["asset"] for case in manual_cases}), 24)
        expected_numbers = replay.package_by_dataset(
            suite,
            "mendeley-seven-segment-energy-meter-v1",
        )["selection"]["manual_roi_diagnostic"]["file_numbers"]
        self.assertTrue(
            {75, 117} <= set(expected_numbers),
        )
        self.assertEqual(
            {case["source"]["published_file_number"] for case in manual_cases},
            set(expected_numbers),
        )
        self.assertEqual(
            {case["expected"]["input_mode"] for case in manual_cases},
            {"manual_roi_diagnostic"},
        )
        self.assertTrue(
            all(
                set(case["expected"]["input_crop"])
                == {
                    "coordinate_space",
                    "left",
                    "top",
                    "right",
                    "bottom",
                    "source",
                    "padding_normalized",
                }
                and case["expected"]["input_crop"]["coordinate_space"]
                == "normalized_source_image_v1"
                for case in manual_cases
            ),
        )
        by_id = {case["id"]: case for case in cases}
        manual_by_source = {case["source"]["source_full_frame_case_id"]: case for case in manual_cases}
        for case_id in {"mendeley-seven-segment__075", "mendeley-seven-segment__117"}:
            crop = manual_by_source[case_id]["expected"]["input_crop"]
            for box in by_id[case_id]["expected"]["digit_boxes"]:
                self.assertLessEqual(crop["left"], box["xmin"])
                self.assertLessEqual(crop["top"], box["ymin"])
                self.assertGreaterEqual(crop["right"], box["xmax"])
                self.assertGreaterEqual(crop["bottom"], box["ymax"])

    def test_objectron_builds_two_positives_and_one_paired_hard_negative_per_identity(self):
        suite = copy.deepcopy(self.suite)
        package = replay.package_by_dataset(suite, "objectron-v1")
        sequences = []
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            objectron_root = root / "objectron"
            for category in ("book", "bottle", "cup"):
                for identity_index in range(2):
                    sequence_slug = f"{category}__fixture-{identity_index}__0"
                    sequence_dir = objectron_root / "sequences" / sequence_slug
                    frames = []
                    for role in ("reference-01", "reference-02", "reference-03", "positive-01", "positive-02"):
                        content = f"{sequence_slug}:{role}".encode()
                        frame_path = sequence_dir / "frames" / f"{role}.jpg"
                        frame_path.parent.mkdir(parents=True, exist_ok=True)
                        frame_path.write_bytes(content)
                        frames.append(
                            {
                                "role": role,
                                "file": f"frames/{role}.jpg",
                                "sha256": hashlib.sha256(content).hexdigest(),
                                "bytes": len(content),
                            },
                        )
                    sequences.append(
                        {
                            "category": category,
                            "publisher_sequence_id": f"{category}/fixture-{identity_index}/0",
                            "local_instance_label": f"{category}-{identity_index}",
                            "assets": [{"file": f"sequences/{sequence_slug}/video.MOV"}],
                            "frames": frames,
                        },
                    )
            source = {
                "schema": "be-your-eye-external-reference-subset/v1",
                "source": {
                    "license_sha256": "69aeb2c407f2ffce7f31c3aba0f1eeffac248009ec2070fcd989586ac8229c82",
                },
                "sequences": sequences,
            }
            manifest = objectron_root / "manifest.json"
            manifest.parent.mkdir(parents=True, exist_ok=True)
            manifest.write_text(json.dumps(source), encoding="utf-8")
            package["source"]["local_manifest_sha256"] = replay.sha256_file(manifest)
            cases, assets, source_path = replay.select_objectron_cases(suite, root)

        self.assertEqual(source_path, manifest)
        self.assertEqual(len(cases), 18)
        self.assertEqual(len(assets), 30)
        self.assertEqual(
            [case["expected"]["reference_relation"] for case in cases].count("same_object"),
            12,
        )
        self.assertTrue(all(len(case["references"]) == 3 for case in cases))

    def test_dry_run_and_empty_status_do_not_need_network_or_media(self):
        with tempfile.TemporaryDirectory() as directory:
            root = replay.validate_root(Path(directory), for_media_write=False)
            plan = replay.dry_run(self.suite, root, "all")
            current = replay.status(self.suite, root)
        self.assertFalse(plan["network_used"])
        self.assertEqual(sum(item["planned_case_count"] for item in plan["packages"]), 450)
        self.assertFalse(current["automated_datasets_ready"])
        self.assertFalse(current["full_bundle_ready"])

    def test_status_fails_closed_on_bundle_and_cohort_identity_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle_root = replay.bundle_root_for(self.suite, root)
            cases = []
            source_indexes = {}
            for package in self.suite["packages"]:
                asset = f"media/{package['dataset_id']}.jpg"
                asset_path = bundle_root / asset
                asset_path.parent.mkdir(parents=True, exist_ok=True)
                asset_path.write_bytes(b"fixture")
                source_indexes.update(replay.expected_source_index(package))
                if package["dataset_id"] == "coco-2017-val":
                    cohort = [
                        (target_id, role)
                        for target_id in replay.catalog_object_target_ids(self.suite)
                        for role, count in package["selection"]["cases_per_target"].items()
                        for _ in range(int(count))
                    ]
                else:
                    cohort = [
                        ("fixture", "observational")
                        for _ in range(replay.expected_case_count(package, self.suite))
                    ]
                cases.extend(
                    {
                        "id": f"{package['dataset_id']}__{index}",
                        "package_id": package["package_id"],
                        "dataset_id": package["dataset_id"],
                        "target_id": target_id,
                        "asset": asset,
                        "source": (
                            {"evaluation_role": role}
                            if package["dataset_id"] == "coco-2017-val"
                            else {"fixture_source_id": str(index)}
                        ),
                    }
                    for index, (target_id, role) in enumerate(cohort)
                )

            manifest = {
                "schema_version": replay.BUNDLE_SCHEMA_VERSION,
                "suite_id": self.suite["suite_id"],
                "catalog_version": self.suite["catalog"]["catalog_version"],
                "suite_policy_sha256": replay.canonical_json_sha256(self.suite),
                "preparer_version": replay.BUNDLE_PREPARER_VERSION,
                "source_index_sha256": source_indexes,
                "cases": cases,
            }
            manifest_path = bundle_root / "manifest.json"

            def write_and_status(value):
                manifest_path.write_text(json.dumps(value), encoding="utf-8")
                return replay.status(self.suite, root)

            current = write_and_status(manifest)
            self.assertTrue(current["manifest_identity_ready"])
            self.assertTrue(current["full_bundle_ready"])

            old_version = copy.deepcopy(manifest)
            old_version["preparer_version"] = 2
            current = write_and_status(old_version)
            self.assertFalse(current["manifest_identity_ready"])
            self.assertFalse(current["full_bundle_ready"])

            wrong_policy = copy.deepcopy(manifest)
            wrong_policy["suite_policy_sha256"] = "0" * 64
            self.assertFalse(write_and_status(wrong_policy)["full_bundle_ready"])

            wrong_catalog = copy.deepcopy(manifest)
            wrong_catalog["catalog_version"] = "stale-catalog"
            self.assertFalse(write_and_status(wrong_catalog)["full_bundle_ready"])

            missing_source = copy.deepcopy(manifest)
            del missing_source["source_index_sha256"]["coco-2017-val"]
            current = write_and_status(missing_source)
            coco = next(item for item in current["packages"] if item["dataset_id"] == "coco-2017-val")
            self.assertFalse(coco["source_index_ready"])
            self.assertFalse(coco["ready"])

            wrong_source_digest = copy.deepcopy(manifest)
            wrong_source_digest["source_index_sha256"]["coco-2017-val"] = "0" * 64
            current = write_and_status(wrong_source_digest)
            self.assertFalse(current["source_index_identity_ready"])
            self.assertFalse(current["full_bundle_ready"])

            extra_source = copy.deepcopy(manifest)
            extra_source["source_index_sha256"]["unknown-dataset"] = "0" * 64
            current = write_and_status(extra_source)
            self.assertFalse(current["source_index_identity_ready"])
            self.assertFalse(current["full_bundle_ready"])

            old_role = copy.deepcopy(manifest)
            coco_case = next(case for case in old_role["cases"] if case["dataset_id"] == "coco-2017-val")
            coco_case["source"]["evaluation_role"] = "clear_anchor"
            current = write_and_status(old_role)
            coco = next(item for item in current["packages"] if item["dataset_id"] == "coco-2017-val")
            self.assertFalse(coco["cohort_identity_ready"])
            self.assertFalse(coco["ready"])

            missing_source = copy.deepcopy(manifest)
            coco_case = next(
                case for case in missing_source["cases"]
                if case["dataset_id"] == "coco-2017-val"
            )
            del coco_case["source"]
            current = write_and_status(missing_source)
            coco = next(item for item in current["packages"] if item["dataset_id"] == "coco-2017-val")
            self.assertFalse(coco["cohort_identity_ready"])
            self.assertFalse(coco["ready"])

            wrong_cohort = copy.deepcopy(manifest)
            wrong_cohort["cases"][0]["dataset_id"] = "coco-2017-val"
            self.assertFalse(write_and_status(wrong_cohort)["full_bundle_ready"])

            unknown_case = copy.deepcopy(manifest)
            unknown_case["cases"].append(
                {
                    "id": "unknown-package__unknown-dataset__0",
                    "package_id": "unknown-package",
                    "dataset_id": "unknown-dataset",
                    "asset": unknown_case["cases"][0]["asset"],
                    "source": {"fixture_source_id": "unknown"},
                },
            )
            current = write_and_status(unknown_case)
            self.assertFalse(current["manifest_cohort_identity_ready"])
            self.assertFalse(current["full_bundle_ready"])

            duplicate_case_id = copy.deepcopy(manifest)
            duplicate_case_id["cases"][-1]["id"] = duplicate_case_id["cases"][0]["id"]
            current = write_and_status(duplicate_case_id)
            self.assertFalse(current["manifest_case_ids_ready"])
            self.assertFalse(current["full_bundle_ready"])

    def test_incremental_bundle_keeps_other_dataset_cases_and_source_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first_case = {
                "id": "first",
                "dataset_id": "coco-2017-val",
                "package_id": "efficientdet_lite2_object_v1",
                "kind": "object_presence",
                "asset": "media/coco/first.jpg",
                "target_id": "apple",
                "expected": {"present": True},
            }
            second_case = {
                "id": "second",
                "dataset_id": "objectron-v1",
                "package_id": "similarity_mediapipe_mobilenet_v3_large_v1",
                "kind": "reference_match",
                "asset": "media/objectron/second.jpg",
                "target_id": "cup",
                "expected": {"reference_relation": "same_object"},
            }
            bundle_root = replay.bundle_root_for(self.suite, root)
            first_asset = bundle_root / first_case["asset"]
            second_asset = bundle_root / second_case["asset"]
            first_asset.parent.mkdir(parents=True, exist_ok=True)
            second_asset.parent.mkdir(parents=True, exist_ok=True)
            first_asset.write_bytes(b"first")
            second_asset.write_bytes(b"second")
            manifest = replay.write_bundle(self.suite, root, [first_case], {"coco-2017-val": "a" * 64})
            replay.write_bundle(
                self.suite,
                root,
                [second_case],
                {"objectron-v1": "b" * 64},
            )
            stable_bytes = manifest.read_bytes()
            replay.write_bundle(
                self.suite,
                root,
                [second_case],
                {"objectron-v1": "b" * 64},
            )
            self.assertEqual(manifest.read_bytes(), stable_bytes)
            value = json.loads(manifest.read_text(encoding="utf-8"))
            drifted_suite = copy.deepcopy(self.suite)
            replay.package_by_dataset(
                drifted_suite,
                "objectron-v1",
            )["selection"]["object_identities_per_category"] = 3
            with self.assertRaisesRegex(replay.PreparationError, "another suite/catalog/cohort"):
                replay.write_bundle(
                    drifted_suite,
                    root,
                    [second_case],
                    {"objectron-v1": "b" * 64},
                )
        self.assertEqual([case["id"] for case in value["cases"]], ["first", "second"])
        self.assertEqual(set(value["source_index_sha256"]), {"coco-2017-val", "objectron-v1"})
        self.assertEqual(value["cases"][0]["asset_sha256"], hashlib.sha256(b"first").hexdigest())
        self.assertEqual(value["cases"][0]["asset_size_bytes"], 5)
        self.assertEqual(value["preparer_version"], replay.BUNDLE_PREPARER_VERSION)
        self.assertRegex(value["suite_policy_sha256"], r"^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
