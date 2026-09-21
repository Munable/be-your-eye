#!/usr/bin/env python3
"""Validate and summarize one package-filtered External Replay Set v1 observation."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCORE_SCHEMA = "beyoureye.external-replay-observation.v1"
SUMMARY_SCHEMA = "beyoureye.external-replay-summary.v1"
BUNDLE_SCHEMA = "beyoureye.external-replay-bundle.v1"
RUN_METADATA_SCHEMA = "beyoureye.external-replay-host-run.v1"
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40,64}")
BUNDLE_PREPARER_VERSION = 3


class ScoreError(RuntimeError):
    pass


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ScoreError(f"cannot read JSON {path}: {error}") from error
    if not isinstance(value, dict):
        raise ScoreError(f"JSON root must be an object: {path}")
    return value


def canonical_json_sha256(value: Any) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def verify_host_binary_identities(
    *,
    run_metadata_path: Path,
    app_apk_path: Path,
    test_apk_path: Path,
    package_id: str,
    input_mode_filter: str | None,
) -> dict[str, Any]:
    metadata = load_object(run_metadata_path)
    if metadata.get("schema_version") != RUN_METADATA_SCHEMA:
        raise ScoreError("host run metadata has the wrong schema")
    if metadata.get("package_id") != package_id:
        raise ScoreError("host run metadata package differs from the selected package")
    expected_mode = input_mode_filter or "all"
    if metadata.get("input_mode_filter") != expected_mode:
        raise ScoreError("host run metadata input mode differs from the selected cohort")
    implementation_commit = metadata.get("implementation_commit")
    if not isinstance(implementation_commit, str) or not COMMIT_PATTERN.fullmatch(
        implementation_commit,
    ):
        raise ScoreError("host run metadata has no exact implementation commit")
    status_clean = metadata.get("git_status_clean")
    if not isinstance(status_clean, bool):
        raise ScoreError("host run metadata has no Git cleanliness result")
    device = metadata.get("device")
    if not isinstance(device, dict):
        raise ScoreError("host run metadata has no Android device profile")
    string_device_fields = ("manufacturer", "model", "primary_abi", "build_fingerprint")
    if any(
        not isinstance(device.get(name), str) or not device[name].strip()
        for name in string_device_fields
    ) or not isinstance(device.get("api_level"), int) or device["api_level"] <= 0:
        raise ScoreError("host run metadata Android device profile is incomplete")

    identities: dict[str, Any] = {
        "run_metadata_sha256": file_sha256(run_metadata_path),
        "implementation_commit": implementation_commit,
        "git_status_clean": status_clean,
        "device": {name: device[name] for name in (*string_device_fields, "api_level")},
    }
    for label, path in (("app", app_apk_path), ("test", test_apk_path)):
        if not path.is_file():
            raise ScoreError(f"host {label} APK is missing")
        actual_sha256 = file_sha256(path)
        actual_size = path.stat().st_size
        declared = metadata.get(f"{label}_apk")
        if not isinstance(declared, dict):
            raise ScoreError(f"host run metadata has no {label} APK identity")
        if declared.get("sha256") != actual_sha256 or declared.get("size_bytes") != actual_size:
            raise ScoreError(f"host {label} APK bytes differ from run metadata")
        installed_sha256 = declared.get("installed_sha256")
        if not isinstance(installed_sha256, str) or not SHA256_PATTERN.fullmatch(
            installed_sha256,
        ):
            raise ScoreError(f"host run metadata has no installed {label} APK readback")
        if installed_sha256 != actual_sha256:
            raise ScoreError(f"installed {label} APK differs from the host APK")
        identities[f"{label}_apk_sha256"] = actual_sha256
        identities[f"{label}_apk_size_bytes"] = actual_size
    return identities


def field(value: dict[str, Any], camel: str, snake: str) -> Any:
    return value.get(camel, value.get(snake))


def package_definition(suite: dict[str, Any], package_id: str) -> dict[str, Any]:
    matches = [item for item in suite.get("packages", []) if item.get("package_id") == package_id]
    if len(matches) != 1:
        raise ScoreError("tracked suite does not define exactly one selected package")
    return matches[0]


def expected_source_index(package: dict[str, Any]) -> dict[str, str]:
    dataset_id = str(package["dataset_id"])
    source = package["source"]
    if dataset_id == "objectron-v1":
        digest = source["local_manifest_sha256"]
    elif dataset_id == "mendeley-seven-segment-energy-meter-v1":
        digest = source["annotations"]["sha256"]
    elif dataset_id == "coco-2017-val":
        digest = source["annotations_member_sha256"]
    else:
        raise ScoreError(f"unsupported replay dataset: {dataset_id}")
    return {dataset_id: str(digest)}


def require_source_index(package: dict[str, Any], actual: Any) -> None:
    expected = expected_source_index(package)
    if actual != expected:
        raise ScoreError(
            f"filtered bundle source-index identity differs: actual={actual} expected={expected}",
        )


def catalog_targets(catalog: dict[str, Any], package_id: str) -> set[str]:
    matches = [
        capability
        for capability in catalog.get("operational_capabilities", [])
        if package_id in capability.get("package_ids", [])
        and isinstance(capability.get("target_ids"), list)
    ]
    if len(matches) != 1:
        raise ScoreError(f"Catalog does not expose exactly one target list for {package_id}")
    targets = matches[0]["target_ids"]
    if not targets or len(set(targets)) != len(targets) or not all(isinstance(item, str) for item in targets):
        raise ScoreError(f"Catalog target list is invalid for {package_id}")
    return set(targets)


def require_exact_cohort(
    package_id: str,
    package: dict[str, Any],
    catalog: dict[str, Any],
    manifest_cases: list[dict[str, Any]],
    input_mode_filter: str | None,
) -> None:
    if package_id == "similarity_mediapipe_mobilenet_v3_large_v1":
        selection = package["selection"]
        expected_targets = int(selection["object_identities_per_category"]) * len(selection["categories"])
        expected_same = expected_targets * int(selection["later_positive_frames_per_identity"])
        expected_different = expected_targets * int(selection["same_category_hard_negatives_per_identity"])
        relations = Counter(case.get("expected", {}).get("reference_relation") for case in manifest_cases)
        by_target: dict[str, Counter[str]] = defaultdict(Counter)
        for case in manifest_cases:
            by_target[str(case.get("target_id"))][str(case.get("expected", {}).get("reference_relation"))] += 1
        expected_per_target = Counter(
            {
                "same_object": int(selection["later_positive_frames_per_identity"]),
                "different_object": int(selection["same_category_hard_negatives_per_identity"]),
            }
        )
        if relations != Counter({"same_object": expected_same, "different_object": expected_different}):
            raise ScoreError(
                f"Reference cohort must be {expected_same} same + {expected_different} different; got {dict(relations)}"
            )
        if len(by_target) != expected_targets or any(counts != expected_per_target for counts in by_target.values()):
            raise ScoreError("Reference cohort must contain six identities with two same and one different case each")
        return

    if package_id == "numeric_reader_ppocrv6_medium_v1":
        selection = package["selection"]
        expected_full = int(selection.get("full_frame_case_count", 168))
        manual_numbers = selection["manual_roi_diagnostic"]["file_numbers"]
        expected_manual = len(manual_numbers)
        expected_by_filter = {
            None: Counter({"full_frame": expected_full, "manual_roi_diagnostic": expected_manual}),
            "full_frame": Counter({"full_frame": expected_full}),
            "manual_roi_diagnostic": Counter({"manual_roi_diagnostic": expected_manual}),
        }
        if input_mode_filter not in expected_by_filter:
            raise ScoreError(f"Reader input_mode_filter is invalid: {input_mode_filter}")
        modes = Counter(case.get("expected", {}).get("input_mode") for case in manifest_cases)
        if modes != expected_by_filter[input_mode_filter]:
            raise ScoreError(
                f"Reader cohort differs from the exact v1 mode counts: actual={dict(modes)} "
                f"expected={dict(expected_by_filter[input_mode_filter])}"
            )
        if modes.get("manual_roi_diagnostic"):
            actual_numbers = {
                int(case.get("source", {}).get("published_file_number"))
                for case in manifest_cases
                if case.get("expected", {}).get("input_mode") == "manual_roi_diagnostic"
            }
            if actual_numbers != {int(number) for number in manual_numbers}:
                raise ScoreError("Reader manual-ROI cohort does not match the suite's fixed file numbers")
        return

    by_role_and_target: dict[str, dict[str, list[dict[str, Any]]]] = defaultdict(
        lambda: defaultdict(list)
    )
    for case in manifest_cases:
        role = case.get("source", {}).get("evaluation_role")
        target = case.get("target_id")
        if not isinstance(role, str) or not isinstance(target, str):
            raise ScoreError("object cohort is missing evaluation role or target identity")
        by_role_and_target[role][target].append(case)

    if package_id == "efficientdet_lite2_object_v1":
        expected_targets = catalog_targets(catalog, package_id)
        counts = package["selection"]["cases_per_target"]
        expected_roles = {str(role): int(count) for role, count in counts.items()}
        if set(by_role_and_target) != set(expected_roles):
            raise ScoreError(
                f"Lite2 cohort roles differ: actual={sorted(by_role_and_target)} expected={sorted(expected_roles)}"
            )
        actual_targets = set().union(*(set(values) for values in by_role_and_target.values()))
        if actual_targets != expected_targets:
            raise ScoreError(
                f"Lite2 cohort target set differs: missing={sorted(expected_targets - actual_targets)} "
                f"unexpected={sorted(actual_targets - expected_targets)}"
            )
        for target in expected_targets:
            actual = {role: len(by_role_and_target[role].get(target, [])) for role in expected_roles}
            if actual != expected_roles:
                raise ScoreError(f"Lite2 cohort counts differ for {target}: {actual} expected={expected_roles}")
        return

    raise ScoreError(f"unsupported replay package: {package_id}")


def summarize_reference_results(
    *,
    result_by_id: dict[str, dict[str, Any]],
    filtered_by_id: dict[str, dict[str, Any]],
) -> list[str]:
    allowed_outcomes = {
        "same_object": {
            "correct_same_object",
            "wrong_same_object",
            "unavailable",
            "runner_error",
        },
        "different_object": {
            "correct_different_object",
            "wrong_different_object",
            "unavailable",
            "runner_error",
        },
    }
    for case_id, result in result_by_id.items():
        relation = filtered_by_id[case_id]["expected"]["reference_relation"]
        if result.get("expected") != relation:
            raise ScoreError(f"Reference result truth differs from the filtered manifest: {case_id}")
        if result.get("outcome") not in allowed_outcomes[relation]:
            raise ScoreError(f"Reference result outcome is invalid for {relation}: {case_id}")

    cases = list(result_by_id.values())
    same = [case for case in cases if case.get("expected") == "same_object"]
    different = [case for case in cases if case.get("expected") == "different_object"]
    same_counts = Counter(str(case.get("outcome")) for case in same)
    different_counts = Counter(str(case.get("outcome")) for case in different)
    unavailable = sum(case.get("outcome") == "unavailable" for case in cases)
    return [
        "same_object_outcomes="
        + ",".join(f"{outcome}:{same_counts[outcome]}" for outcome in sorted(same_counts))
        + f"/{len(same)}",
        "different_object_outcomes="
        + ",".join(
            f"{outcome}:{different_counts[outcome]}" for outcome in sorted(different_counts)
        )
        + f"/{len(different)}",
        f"unavailable_states={unavailable}",
    ]


def object_target_label_present_metric(cases: list[dict[str, Any]]) -> str:
    if not cases:
        raise ScoreError("object target-label presence requires at least one case")
    present = sum(
        case.get("outcome") in {"hit", "localization_mismatch"}
        for case in cases
    )
    return f"target_label_present={present}/{len(cases)}={present / len(cases):.4f}"


def score_replay(
    *,
    summary_path: Path,
    package_id: str,
    suite_path: Path,
    bundle_manifest_path: Path,
    catalog_path: Path,
    package_manifest_path: Path,
    host_binary_identities: dict[str, Any] | None = None,
) -> dict[str, Any]:
    summary = load_object(summary_path)
    suite = load_object(suite_path)
    filtered_manifest = load_object(bundle_manifest_path)
    catalog = load_object(catalog_path)
    signed_manifest = load_object(package_manifest_path)

    if summary.get("schema_version") != SUMMARY_SCHEMA:
        raise ScoreError("pulled summary has the wrong schema")
    if filtered_manifest.get("schema_version") != BUNDLE_SCHEMA:
        raise ScoreError("filtered bundle has the wrong schema")
    if filtered_manifest.get("preparer_version") != BUNDLE_PREPARER_VERSION:
        raise ScoreError("filtered bundle has the wrong preparer version")
    if filtered_manifest.get("suite_policy_sha256") != canonical_json_sha256(suite):
        raise ScoreError("filtered bundle was not prepared from the tracked suite policy")
    started_at = summary.get("started_at_epoch_ms")
    metadata_evaluated_at = summary.get("metadata_evaluated_at_epoch_ms")
    finished_at = summary.get("finished_at_epoch_ms")
    if not (
        isinstance(started_at, int)
        and isinstance(metadata_evaluated_at, int)
        and isinstance(finished_at, int)
        and 0 <= started_at <= metadata_evaluated_at <= finished_at
    ):
        raise ScoreError("summary does not prove current-time signed-metadata evaluation")
    if summary.get("catalog_version") != suite.get("catalog", {}).get("catalog_version"):
        raise ScoreError("pulled summary and tracked suite use different Catalog versions")
    if filtered_manifest.get("catalog_version") != summary.get("catalog_version"):
        raise ScoreError("filtered bundle and pulled summary use different Catalog versions")
    if filtered_manifest.get("package_filter") != package_id:
        raise ScoreError("filtered bundle does not bind the selected package filter")
    if summary.get("bundle_manifest_sha256") != file_sha256(bundle_manifest_path):
        raise ScoreError("pulled summary does not identify the injected filtered bundle manifest")
    if summary.get("catalog_sha256") != file_sha256(catalog_path):
        raise ScoreError("pulled summary does not identify the injected signed Catalog")
    if summary.get("source_index_sha256") != filtered_manifest.get("source_index_sha256"):
        raise ScoreError("pulled summary source-index identity differs from the filtered bundle")
    source_bundle_manifest_sha256 = filtered_manifest.get("source_bundle_manifest_sha256")
    if not isinstance(source_bundle_manifest_sha256, str) or not re.fullmatch(
        r"[0-9a-f]{64}",
        source_bundle_manifest_sha256,
    ):
        raise ScoreError("filtered bundle does not bind its source bundle manifest")

    package = package_definition(suite, package_id)
    if signed_manifest.get("package_id") != package_id:
        raise ScoreError("signed manifest package identity differs")
    if signed_manifest.get("package_version") != package.get("package_version"):
        raise ScoreError("signed manifest package version differs from the tracked suite")
    catalog_entries = [
        item
        for item in catalog.get("packages", [])
        if item.get("package_id") == package_id and item.get("status") == "active"
    ]
    if len(catalog_entries) != 1:
        raise ScoreError("signed Catalog does not contain exactly one active selected package")
    package_manifest_sha256 = file_sha256(package_manifest_path)
    if catalog_entries[0].get("manifest_sha256") != package_manifest_sha256:
        raise ScoreError("signed package manifest SHA-256 differs from the signed Catalog")
    if summary.get("package_versions", {}).get(package_id) != package.get("package_version"):
        raise ScoreError("pulled summary package version differs from the tracked suite")

    cases = summary.get("cases")
    filtered_cases = filtered_manifest.get("cases")
    if not isinstance(cases, list) or not isinstance(filtered_cases, list) or not cases:
        raise ScoreError("summary and filtered bundle must contain cases")
    if summary.get("case_count") != len(cases) or summary.get("result_count") != len(cases):
        raise ScoreError("pulled summary count mismatch")
    case_package_ids = {field(case, "packageId", "package_id") for case in cases}
    if case_package_ids != {package_id}:
        raise ScoreError("pulled summary contains the wrong package")
    filtered_by_id = {case.get("id"): case for case in filtered_cases}
    result_by_id = {case.get("id"): case for case in cases}
    if (
        len(filtered_by_id) != len(filtered_cases)
        or len(result_by_id) != len(cases)
        or filtered_by_id.keys() != result_by_id.keys()
    ):
        raise ScoreError("pulled summary case identities differ from the injected filtered manifest")
    require_source_index(package, filtered_manifest.get("source_index_sha256"))

    input_mode_filter = filtered_manifest.get("input_mode_filter")
    require_exact_cohort(package_id, package, catalog, filtered_cases, input_mode_filter)

    observation_contract = package.get("observation_contract", {})
    if observation_contract.get("runner_errors_maximum") != 0:
        raise ScoreError("tracked observation contract must require zero runner errors")
    failures: list[str] = []
    metrics: list[str] = []
    runner_errors = int(summary.get("runner_error_count", 0))
    case_runner_errors = sum(case.get("outcome") == "runner_error" for case in cases)
    if case_runner_errors != runner_errors:
        raise ScoreError("summary runner_error_count differs from per-case outcomes")
    metrics.append(f"runner_errors={runner_errors}/0")
    if runner_errors:
        failures.append(f"runner_errors {runner_errors}>0")

    if package_id == "similarity_mediapipe_mobilenet_v3_large_v1":
        reference_metrics = summarize_reference_results(
            result_by_id=result_by_id,
            filtered_by_id=filtered_by_id,
        )
        metrics += reference_metrics
    elif package_id == "numeric_reader_ppocrv6_medium_v1":
        results_by_mode: dict[str, list[dict[str, Any]]] = defaultdict(list)
        allowed_reader_outcomes = {"exact", "wrong_valid", "unavailable", "runner_error"}
        for case_id, result in result_by_id.items():
            mode = filtered_by_id[case_id]["expected"]["input_mode"]
            if field(result, "inputMode", "input_mode") != mode:
                raise ScoreError(f"Reader result input mode differs from the filtered manifest: {case_id}")
            if result.get("outcome") not in allowed_reader_outcomes:
                raise ScoreError(f"Reader result outcome is invalid: {case_id}")
            results_by_mode[mode].append(result)
        full_frame = results_by_mode.get("full_frame", [])
        if full_frame:
            counts = Counter(str(case.get("outcome")) for case in full_frame)
            metrics.append(
                "full_frame_observed="
                + ",".join(f"{outcome}:{counts[outcome]}" for outcome in sorted(counts))
                + f"/{len(full_frame)}"
            )
        manual_roi = results_by_mode.get("manual_roi_diagnostic", [])
        if manual_roi:
            exact = sum(case.get("outcome") == "exact" for case in manual_roi)
            wrong_valid = sum(case.get("outcome") == "wrong_valid" for case in manual_roi)
            unavailable = sum(case.get("outcome") == "unavailable" for case in manual_roi)
            exact_rate = exact / len(manual_roi)
            metrics += [
                f"manual_roi_digits_exact={exact}/{len(manual_roi)}={exact_rate:.4f}",
                f"manual_roi_wrong_valid={wrong_valid}",
                f"manual_roi_unavailable={unavailable}",
            ]
    else:
        if float(summary.get("object_hit_minimum_iou", -1)) != 0.5:
            raise ScoreError("object summary does not bind hit outcomes to IoU >= 0.5")
        by_target: dict[str, list[dict[str, Any]]] = defaultdict(list)
        by_role: dict[str, list[dict[str, Any]]] = defaultdict(list)
        allowed_object_outcomes = {
            "hit",
            "localization_mismatch",
            "miss",
            "unavailable",
            "runner_error",
        }
        for case_id, result in result_by_id.items():
            manifest_case = filtered_by_id[case_id]
            target = field(result, "targetId", "target_id")
            if target != manifest_case.get("target_id"):
                raise ScoreError(f"object result target differs from the filtered manifest: {case_id}")
            if result.get("outcome") not in allowed_object_outcomes:
                raise ScoreError(f"object result outcome is invalid: {case_id}")
            role = str(manifest_case["source"]["evaluation_role"])
            by_target[str(target)].append(result)
            by_role[role].append(result)
        unavailable = sum(case.get("outcome") == "unavailable" for case in cases)
        metrics.append(f"unavailable={unavailable}")
        metrics.append(object_target_label_present_metric(cases))

        def iou_report(label: str, role_cases: list[dict[str, Any]]) -> str:
            values = []
            for case in role_cases:
                match = re.search(
                    r"(?:^|,)best_iou=([0-9]+(?:\.[0-9]+)?)",
                    str(case.get("actual", "")),
                )
                if match:
                    values.append(float(match.group(1)))
            if not values:
                return f"{label}_iou_reported=0/{len(role_cases)}"
            return (
                f"{label}_iou_reported={len(values)}/{len(role_cases)},"
                f"mean={sum(values) / len(values):.4f},min={min(values):.4f},max={max(values):.4f}"
            )

        for role in sorted(by_role):
            counts = Counter(str(case.get("outcome")) for case in by_role[role])
            metrics += [
                f"{role}="
                + ",".join(f"{outcome}:{counts[outcome]}" for outcome in sorted(counts))
                + f"/{len(by_role[role])}",
                iou_report(role, by_role[role]),
            ]
        metrics.append(f"targets={len(by_target)}")
    observation_status = "INCOMPLETE" if failures else "OBSERVED"
    return {
        "schema_version": SCORE_SCHEMA,
        "scored_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "package_id": package_id,
        "package_version": package["package_version"],
        "input_mode_filter": input_mode_filter or "all",
        "case_count": len(cases),
        "metadata_evaluated_at_epoch_ms": metadata_evaluated_at,
        "observation_status": observation_status,
        "interpretation": "runtime_wiring_observation_not_product_accuracy_evidence",
        "metrics": metrics,
        "wiring_failures": failures,
        "identities": {
            "summary_sha256": file_sha256(summary_path),
            "suite_definition_sha256": file_sha256(suite_path),
            "bundle_manifest_sha256": file_sha256(bundle_manifest_path),
            "source_bundle_manifest_sha256": source_bundle_manifest_sha256,
            "catalog_sha256": file_sha256(catalog_path),
            "package_manifest_sha256": package_manifest_sha256,
            "source_index_sha256": filtered_manifest["source_index_sha256"],
            **(host_binary_identities or {}),
        },
    }


def write_score(path: Path, score: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(score, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--summary", type=Path, required=True)
    value.add_argument("--package", required=True)
    value.add_argument("--suite", type=Path, required=True)
    value.add_argument("--bundle-manifest", type=Path, required=True)
    value.add_argument("--catalog", type=Path, required=True)
    value.add_argument("--package-manifest", type=Path, required=True)
    value.add_argument("--run-metadata", type=Path, required=True)
    value.add_argument("--app-apk", type=Path, required=True)
    value.add_argument("--test-apk", type=Path, required=True)
    value.add_argument("--output", type=Path, required=True)
    return value


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        host_binary_identities = verify_host_binary_identities(
            run_metadata_path=args.run_metadata,
            app_apk_path=args.app_apk,
            test_apk_path=args.test_apk,
            package_id=args.package,
            input_mode_filter=load_object(args.bundle_manifest).get("input_mode_filter"),
        )
        score = score_replay(
            summary_path=args.summary,
            package_id=args.package,
            suite_path=args.suite,
            bundle_manifest_path=args.bundle_manifest,
            catalog_path=args.catalog,
            package_manifest_path=args.package_manifest,
            host_binary_identities=host_binary_identities,
        )
    except Exception as error:  # The CLI must retain one compact invalid verdict for malformed input.
        failure = str(error) if isinstance(error, ScoreError) else f"{type(error).__name__}: {error}"
        score = {
            "schema_version": SCORE_SCHEMA,
            "scored_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "package_id": args.package,
            "observation_status": "INVALID",
            "interpretation": "runtime_wiring_observation_not_product_accuracy_evidence",
            "metrics": [],
            "wiring_failures": [failure],
            "identities": {
                name: file_sha256(path) if path.is_file() else None
                for name, path in {
                    "summary_sha256": args.summary,
                    "suite_definition_sha256": args.suite,
                    "bundle_manifest_sha256": args.bundle_manifest,
                    "catalog_sha256": args.catalog,
                    "package_manifest_sha256": args.package_manifest,
                    "run_metadata_sha256": args.run_metadata,
                    "app_apk_sha256": args.app_apk,
                    "test_apk_sha256": args.test_apk,
                }.items()
            },
        }
        write_score(args.output, score)
        print(f"SCORING_INVALID: {failure}", file=sys.stderr)
        return 4

    write_score(args.output, score)
    print(
        f"SUMMARY: package={score['package_id']} input_mode={score['input_mode_filter']} "
        f"cases={score['case_count']}"
    )
    print(
        f"OBSERVATION: {score['observation_status']} "
        f"metrics={' ; '.join(score['metrics'])}"
    )
    if score["wiring_failures"]:
        print(f"WIRING_FAILURES: {' ; '.join(score['wiring_failures'])}")
    if score["observation_status"] != "OBSERVED":
        return 6
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
