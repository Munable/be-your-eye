#!/usr/bin/env python3
"""Prepare the small public-data bundle used by External Replay Set v1.

Tracked files define selection and acceptance policy. Source annotations, selected media, the
prepared bundle, and all transient downloads remain on the configured external volume. This tool
does not run models or write product evidence.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Iterable


SCRIPT_PATH = Path(__file__).resolve()
REPO_ROOT = SCRIPT_PATH.parents[2]
DEFAULT_SUITE_PATH = REPO_ROOT / "model-tools/v3/evaluation/external-replay-set-v1.json"
BUNDLE_PREPARER_VERSION = 3
BUNDLE_SCHEMA_VERSION = "beyoureye.external-replay-bundle.v1"


class PreparationError(RuntimeError):
    pass


def read_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PreparationError(f"cannot read JSON {path}: {error}") from error
    if not isinstance(value, dict):
        raise PreparationError(f"JSON root must be an object: {path}")
    return value


def canonical_json_sha256(value: dict) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8"),
    ).hexdigest()


def repo_path(relative: str) -> Path:
    path = (REPO_ROOT / relative).resolve()
    try:
        path.relative_to(REPO_ROOT)
    except ValueError as error:
        raise PreparationError(f"suite path escapes repository: {relative}") from error
    return path


def load_and_validate_suite(path: Path) -> dict:
    suite = read_json(path)
    if suite.get("schema_version") != "beyoureye.external-replay-suite.v1":
        raise PreparationError("unsupported suite schema_version")
    if suite.get("suite_id") != "external-replay-set-v1":
        raise PreparationError("unexpected suite_id")

    catalog_record = suite.get("catalog")
    if not isinstance(catalog_record, dict):
        raise PreparationError("suite catalog record is missing")
    catalog = read_json(repo_path(str(catalog_record.get("template_path", ""))))
    if catalog.get("catalog_version") != catalog_record.get("catalog_version"):
        raise PreparationError("suite Catalog version differs from the current template")
    active = {
        item.get("package_id"): item.get("package_version")
        for item in catalog.get("packages", [])
        if isinstance(item, dict) and item.get("status") == "active"
    }
    required = catalog_record.get("required_active_package_ids")
    if not isinstance(required, list) or set(required) != set(active):
        raise PreparationError("suite package set must equal the current active Catalog package set")

    packages = suite.get("packages")
    if not isinstance(packages, list) or len(packages) != len(required):
        raise PreparationError("suite must contain one record for every active package")
    seen: set[str] = set()
    for package in packages:
        if not isinstance(package, dict):
            raise PreparationError("suite package record must be an object")
        package_id = package.get("package_id")
        if package_id in seen or package_id not in active:
            raise PreparationError(f"unexpected or duplicate package_id: {package_id}")
        seen.add(package_id)
        if package.get("package_version") != active[package_id]:
            raise PreparationError(f"Catalog package version drift: {package_id}")
        manifest = read_json(repo_path(str(package.get("manifest_template_path", ""))))
        if manifest.get("package_id") != package_id or manifest.get("package_version") != active[package_id]:
            raise PreparationError(f"Manifest identity drift: {package_id}")
        source = package.get("source")
        license_record = package.get("license")
        if not isinstance(source, dict) or not str(source.get("home_url", "")).startswith("https://"):
            raise PreparationError(f"HTTPS source home is required: {package_id}")
        if not isinstance(license_record, dict) or not any(
            key.endswith("license_id") for key in license_record
        ):
            raise PreparationError(f"explicit dataset license is required: {package_id}")

    return suite


def package_by_dataset(suite: dict, dataset_id: str) -> dict:
    for package in suite["packages"]:
        if package.get("dataset_id") == dataset_id:
            return package
    raise PreparationError(f"dataset is not declared by this suite: {dataset_id}")


def validate_root(root: Path, *, for_media_write: bool) -> Path:
    expanded = root.expanduser().absolute()
    resolved = expanded.resolve()
    for candidate, repository in ((expanded, REPO_ROOT.absolute()), (resolved, REPO_ROOT.resolve())):
        try:
            candidate.relative_to(repository)
        except ValueError:
            continue
        raise PreparationError("external replay media root must not be inside the repository")
    if for_media_write:
        if not str(resolved).startswith("/Volumes/"):
            raise PreparationError("prepare requires an external-volume root under /Volumes")
        volume_root = Path("/Volumes") / resolved.relative_to("/Volumes").parts[0]
        if not volume_root.is_dir():
            raise PreparationError(f"external volume is not mounted: {volume_root}")
    return resolved


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def direct_opener() -> urllib.request.OpenerDirector:
    # The developer machine may have stale proxy variables. Public evaluation data should first
    # take a direct route rather than silently depending on that ambient proxy state.
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def download_file(
    url: str,
    destination: Path,
    *,
    retries: int = 3,
    expected_sha256: str | None = None,
) -> None:
    if destination.is_file() and destination.stat().st_size > 0:
        if expected_sha256 is not None and sha256_file(destination) != expected_sha256:
            raise PreparationError(f"existing source checksum mismatch: {destination}")
        return
    if not url.startswith("https://"):
        raise PreparationError(f"refusing non-HTTPS source URL: {url}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".download")
    temporary.unlink(missing_ok=True)
    headers = {"User-Agent": "Be-Your-Eye-External-Replay-Set/1.0"}
    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            request = urllib.request.Request(url, headers=headers)
            with direct_opener().open(request, timeout=90) as source, temporary.open("wb") as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)
            if temporary.stat().st_size == 0:
                raise PreparationError(f"downloaded an empty file: {url}")
            if expected_sha256 is not None and sha256_file(temporary) != expected_sha256:
                raise PreparationError(f"downloaded source checksum mismatch: {url}")
            os.replace(temporary, destination)
            return
        except (OSError, urllib.error.URLError, PreparationError) as error:
            last_error = error
            temporary.unlink(missing_ok=True)
            if attempt < retries:
                time.sleep(attempt)
    raise PreparationError(f"direct download failed after {retries} attempts: {url}: {last_error}")


def safe_zip_member(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if path.is_absolute() or not path.parts or any(part in {"", ".", ".."} for part in path.parts):
        raise PreparationError(f"unsafe archive member: {name}")
    return path


def link_or_copy_verified(
    source: Path,
    destination: Path,
    *,
    expected_sha256: str | None = None,
    expected_size: int | None = None,
) -> None:
    if not source.is_file():
        raise PreparationError(f"source media is missing: {source}")
    actual_size = source.stat().st_size
    actual_sha256 = sha256_file(source)
    if expected_size is not None and actual_size != expected_size:
        raise PreparationError(f"source media size mismatch: {source}")
    if expected_sha256 is not None and actual_sha256 != expected_sha256:
        raise PreparationError(f"source media checksum mismatch: {source}")
    if destination.is_file():
        if destination.stat().st_size != actual_size or sha256_file(destination) != actual_sha256:
            raise PreparationError(f"existing bundle media identity mismatch: {destination}")
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".download")
    temporary.unlink(missing_ok=True)
    try:
        try:
            os.link(source, temporary)
        except OSError:
            shutil.copy2(source, temporary)
        os.replace(temporary, destination)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def stable_rank(suite_id: str, dataset_id: str, target_id: str, image_id: str) -> str:
    value = f"{suite_id}:{dataset_id}:{target_id}:{image_id}".encode("utf-8")
    return hashlib.sha256(value).hexdigest()


def choose_distinct_candidates(
    candidates_by_target: dict[str, dict[str, list[dict]]],
    *,
    count: int,
    suite_id: str,
    dataset_id: str,
) -> dict[str, list[tuple[str, list[dict]]]]:
    selected: dict[str, list[tuple[str, list[dict]]]] = {}
    used_images: set[str] = set()
    for target_id in sorted(candidates_by_target):
        candidates = sorted(
            candidates_by_target[target_id].items(),
            key=lambda item: stable_rank(suite_id, dataset_id, target_id, item[0]),
        )
        unused = [item for item in candidates if item[0] not in used_images]
        reused = [item for item in candidates if item[0] in used_images]
        chosen = (unused + reused)[:count]
        if len(chosen) != count:
            raise PreparationError(
                f"{dataset_id}/{target_id} has {len(candidates)} eligible images; {count} required",
            )
        selected[target_id] = chosen
        used_images.update(image_id for image_id, _ in chosen)
    return selected


def catalog_object_target_ids(suite: dict) -> list[str]:
    catalog = read_json(repo_path(suite["catalog"]["template_path"]))
    matches = [
        capability
        for capability in catalog.get("operational_capabilities", [])
        if capability.get("capability_key") == "common_objects_tensorflow_efficientdet_lite2"
    ]
    if len(matches) != 1 or not isinstance(matches[0].get("target_ids"), list):
        raise PreparationError("current Catalog common-object target list is missing")
    return matches[0]["target_ids"]


def normalized_label(value: str) -> str:
    return value.strip().lower().replace(" ", "_").replace("-", "_")


def select_coco_cases(suite: dict, annotations_path: Path) -> list[dict]:
    package = package_by_dataset(suite, "coco-2017-val")
    selection = package["selection"]
    source = read_json(annotations_path)
    images = {int(image["id"]): image for image in source.get("images", [])}
    categories = {
        normalized_label(str(category["name"])): int(category["id"])
        for category in source.get("categories", [])
    }
    licenses = {
        int(license_record["id"]): license_record
        for license_record in source.get("licenses", [])
        if isinstance(license_record, dict) and "id" in license_record
    }
    target_ids = catalog_object_target_ids(suite)
    missing_categories = sorted(set(target_ids) - set(categories))
    if missing_categories:
        raise PreparationError(f"COCO category mapping is incomplete: {missing_categories}")
    category_to_target = {categories[target_id]: target_id for target_id in target_ids}
    eligibility = selection["eligibility"]
    source_boxes: dict[str, dict[str, list[dict]]] = defaultdict(lambda: defaultdict(list))
    candidates: dict[str, dict[str, list[dict]]] = defaultdict(lambda: defaultdict(list))
    for annotation in source.get("annotations", []):
        category_id = int(annotation.get("category_id", -1))
        target_id = category_to_target.get(category_id)
        image = images.get(int(annotation.get("image_id", -1)))
        bbox = annotation.get("bbox")
        if target_id is None or image is None or not isinstance(bbox, list) or len(bbox) != 4:
            continue
        x, y, width, height = (float(value) for value in bbox)
        image_width = float(image["width"])
        image_height = float(image["height"])
        if (
            image_width <= 0
            or image_height <= 0
            or width <= 0
            or height <= 0
            or x < 0
            or y < 0
            or x + width > image_width
            or y + height > image_height
        ):
            continue
        if int(annotation.get("iscrowd", 0)) != int(eligibility["iscrowd"]):
            continue
        normalized = {
            "xmin": round(x / image_width, 8),
            "ymin": round(y / image_height, 8),
            "xmax": round((x + width) / image_width, 8),
            "ymax": round((y + height) / image_height, 8),
            "file_name": image["file_name"],
            "area_ratio": round(width * height / (image_width * image_height), 8),
        }
        source_boxes[target_id][str(image["id"])].append(normalized)
        if width < eligibility["minimum_bbox_side_pixels"] or height < eligibility["minimum_bbox_side_pixels"]:
            continue
        if width * height / (image_width * image_height) < eligibility["minimum_bbox_area_ratio"]:
            continue
        candidates[target_id][str(image["id"])].append(normalized)

    roles = selection["cases_per_target"]
    if roles != {"largest_bbox_anchor": 1, "observational": 2}:
        raise PreparationError(
            "COCO selection must declare one largest-bbox anchor and two observational cases",
        )
    chosen: dict[str, list[tuple[str, list[dict], str]]] = {}
    used_images: set[str] = set()
    for target_id in sorted(candidates):
        ranked_by_area = sorted(
            candidates[target_id].items(),
            key=lambda item: (-max(box["area_ratio"] for box in item[1]), int(item[0])),
        )
        if not ranked_by_area:
            raise PreparationError(
                f"{package['dataset_id']}/{target_id} has no eligible largest-bbox anchor",
            )
        image_id, boxes = ranked_by_area[0]
        chosen[target_id] = [(image_id, boxes, "largest_bbox_anchor")]
        used_images.add(image_id)
    for target_id in sorted(candidates):
        anchor_id = chosen[target_id][0][0]
        ranked = sorted(
            ((image_id, boxes) for image_id, boxes in candidates[target_id].items() if image_id != anchor_id),
            key=lambda item: stable_rank(suite["suite_id"], package["dataset_id"], target_id, item[0]),
        )
        unused = [item for item in ranked if item[0] not in used_images]
        reused = [item for item in ranked if item[0] in used_images]
        observational = (unused + reused)[:2]
        if len(observational) != 2:
            raise PreparationError(
                f"{package['dataset_id']}/{target_id} has {len(ranked)} non-anchor images; 2 required",
            )
        chosen[target_id].extend((image_id, boxes, "observational") for image_id, boxes in observational)
        used_images.update(image_id for image_id, _ in observational)

    cases: list[dict] = []
    for target_id, target_cases in chosen.items():
        for image_id, boxes, evaluation_role in target_cases:
            file_name = boxes[0]["file_name"]
            image = images[int(image_id)]
            license_id = int(image.get("license", -1))
            license_record = licenses.get(license_id)
            if license_record is None or not str(license_record.get("url", "")).startswith("http"):
                raise PreparationError(f"COCO image license metadata is missing: {image_id}")
            complete_boxes = source_boxes[target_id].get(image_id, [])
            if not complete_boxes:
                raise PreparationError(
                    f"{package['dataset_id']}/{target_id}/{image_id} has no complete source boxes",
                )
            normalized_boxes = [
                {key: box[key] for key in ("xmin", "ymin", "xmax", "ymax")}
                for box in complete_boxes
            ]
            cases.append(
                {
                    "id": f"coco-2017-val__{target_id}__{image_id}",
                    "dataset_id": package["dataset_id"],
                    "package_id": package["package_id"],
                    "kind": "object_presence",
                    "asset": f"media/coco-2017-val/{file_name}",
                    "target_id": target_id,
                    "expected": {"present": True, "source_boxes": normalized_boxes},
                    "source": {
                        "image_id": image_id,
                        "file_name": file_name,
                        "evaluation_role": evaluation_role,
                    },
                    "source_license": {
                        "license_id": str(license_id),
                        "license_name": license_record.get("name"),
                        "license_url": license_record["url"],
                    },
                },
            )
    return sorted(cases, key=lambda case: case["id"])


def ensure_coco_annotations(suite: dict, root: Path) -> Path:
    package = package_by_dataset(suite, "coco-2017-val")
    source_dir = root / "coco-2017"
    annotations = source_dir / "instances_val2017.json"
    if annotations.is_file() and annotations.stat().st_size > 0:
        if sha256_file(annotations) != package["source"]["annotations_member_sha256"]:
            raise PreparationError(f"existing COCO annotations checksum mismatch: {annotations}")
        return annotations
    archive = source_dir / "annotations_trainval2017.zip"
    download_file(
        package["source"]["annotations_url"],
        archive,
        expected_sha256=package["source"]["annotations_archive_sha256"],
    )
    member = package["source"]["annotations_member"]
    temporary = annotations.with_name(annotations.name + ".download")
    annotations.parent.mkdir(parents=True, exist_ok=True)
    try:
        with zipfile.ZipFile(archive) as bundle, bundle.open(member) as source, temporary.open("wb") as output:
            shutil.copyfileobj(source, output, length=1024 * 1024)
        if sha256_file(temporary) != package["source"]["annotations_member_sha256"]:
            raise PreparationError(f"extracted COCO annotations checksum mismatch: {member}")
        os.replace(temporary, annotations)
    except (KeyError, OSError, PreparationError, zipfile.BadZipFile) as error:
        temporary.unlink(missing_ok=True)
        raise PreparationError(f"cannot extract {member} from {archive}: {error}") from error
    return annotations


def mendeley_image_number(url: str) -> int:
    decoded = urllib.parse.unquote(url)
    match = re.search(r"-(\d+)\.(?:jpe?g)$", decoded, flags=re.IGNORECASE)
    if match is None:
        raise PreparationError(f"Mendeley image URL has no terminal numbered JPEG: {url}")
    return int(match.group(1))


def jpeg_exif_orientation(path: Path) -> int:
    """Read TIFF orientation from JPEG APP1 without adding an image dependency."""
    data = path.read_bytes()
    if not data.startswith(b"\xff\xd8"):
        return 1
    offset = 2
    while offset + 4 <= len(data) and data[offset] == 0xFF:
        marker = data[offset + 1]
        offset += 2
        if marker in {0xD8, 0xD9} or 0xD0 <= marker <= 0xD7:
            continue
        length = int.from_bytes(data[offset : offset + 2], "big")
        if length < 2 or offset + length > len(data):
            break
        segment = data[offset + 2 : offset + length]
        offset += length
        if marker != 0xE1 or not segment.startswith(b"Exif\x00\x00"):
            continue
        tiff = segment[6:]
        if len(tiff) < 8 or tiff[:2] not in {b"II", b"MM"}:
            break
        byteorder = "little" if tiff[:2] == b"II" else "big"
        ifd_offset = int.from_bytes(tiff[4:8], byteorder)
        if ifd_offset + 2 > len(tiff):
            break
        count = int.from_bytes(tiff[ifd_offset : ifd_offset + 2], byteorder)
        for index in range(count):
            entry = tiff[ifd_offset + 2 + index * 12 : ifd_offset + 14 + index * 12]
            if len(entry) != 12:
                break
            if int.from_bytes(entry[:2], byteorder) == 0x0112:
                return int.from_bytes(entry[8:10], byteorder)
        break
    return 1


def upright_digit_box(annotation: dict, width: float, height: float, orientation: int) -> dict:
    x, y, box_width, box_height = (float(value) for value in annotation["bbox"])
    left, top = x / width, y / height
    right, bottom = (x + box_width) / width, (y + box_height) / height
    if orientation == 3:
        left, top, right, bottom = 1 - right, 1 - bottom, 1 - left, 1 - top
    elif orientation != 1:
        raise PreparationError(f"unsupported JPEG EXIF orientation: {orientation}")
    return {
        "xmin": round(left, 8),
        "ymin": round(top, 8),
        "xmax": round(right, 8),
        "ymax": round(bottom, 8),
    }


def ensure_mendeley_sources(suite: dict, root: Path) -> tuple[Path, dict[int, Path]]:
    package = package_by_dataset(suite, "mendeley-seven-segment-energy-meter-v1")
    source_root = root / "numeric-reading/mendeley-seven-segment"
    downloads = source_root / "downloads"
    archive_record = package["source"]["images_archive"]
    annotations_record = package["source"]["annotations"]
    archive = downloads / "RAW_IMAGES.zip"
    annotations = downloads / "export-coco-2019-06-11T14_40_00.348423.json"
    download_file(archive_record["url"], archive, expected_sha256=archive_record["sha256"])
    download_file(annotations_record["url"], annotations, expected_sha256=annotations_record["sha256"])
    if archive.stat().st_size != int(archive_record["size_bytes"]):
        raise PreparationError("Mendeley image archive size mismatch")
    if annotations.stat().st_size != int(annotations_record["size_bytes"]):
        raise PreparationError("Mendeley annotations size mismatch")

    annotation_value = read_json(annotations)
    required_numbers = {mendeley_image_number(image["file_name"]) for image in annotation_value.get("images", [])}
    if len(required_numbers) != 168 or 97 in required_numbers:
        raise PreparationError("Mendeley annotated image-number set drifted from the reviewed 168 cases")

    image_paths: dict[int, Path] = {}
    try:
        with zipfile.ZipFile(archive) as bundle:
            members: dict[int, zipfile.ZipInfo] = {}
            for info in bundle.infolist():
                if info.is_dir() or not info.filename.lower().endswith((".jpg", ".jpeg")):
                    continue
                relative = safe_zip_member(info.filename)
                try:
                    number = int(Path(relative.name).stem)
                except ValueError as error:
                    raise PreparationError(f"unexpected numbered image member: {relative}") from error
                if number in members:
                    raise PreparationError(f"duplicate numbered image in Mendeley archive: {number}")
                members[number] = info
            missing = sorted(required_numbers - set(members))
            if missing:
                raise PreparationError(f"Mendeley archive is missing annotated images: {missing}")

            for number in sorted(required_numbers):
                info = members[number]
                relative = safe_zip_member(info.filename)
                destination = source_root / "data" / Path(*relative.parts)
                destination.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(info) as source:
                    archive_bytes = source.read()
                archive_sha256 = hashlib.sha256(archive_bytes).hexdigest()
                if destination.is_file():
                    if destination.stat().st_size != len(archive_bytes) or sha256_file(destination) != archive_sha256:
                        raise PreparationError(f"extracted Mendeley image identity mismatch: {destination}")
                else:
                    temporary = destination.with_name(destination.name + ".download")
                    temporary.write_bytes(archive_bytes)
                    os.replace(temporary, destination)
                image_paths[number] = destination
    except (OSError, PreparationError, zipfile.BadZipFile) as error:
        raise PreparationError(f"cannot prepare Mendeley image archive: {error}") from error
    return annotations, image_paths


def select_mendeley_cases(
    suite: dict,
    annotations_path: Path,
    image_paths: dict[int, Path],
) -> tuple[list[dict], dict[str, dict]]:
    package = package_by_dataset(suite, "mendeley-seven-segment-energy-meter-v1")
    source = read_json(annotations_path)
    categories: dict[int, str] = {}
    for category in source.get("categories", []):
        match = re.fullmatch(r"Text ([0-9])", str(category.get("name", "")))
        if match is None:
            raise PreparationError(f"unexpected Mendeley digit category: {category}")
        categories[int(category["id"])] = match.group(1)
    if set(categories.values()) != set("0123456789"):
        raise PreparationError("Mendeley digit categories are incomplete")

    annotations_by_image: dict[str, list[dict]] = defaultdict(list)
    for annotation in source.get("annotations", []):
        annotations_by_image[str(annotation["image_id"])].append(annotation)

    cases: list[dict] = []
    local_assets: dict[str, dict] = {}
    for image in source.get("images", []):
        number = mendeley_image_number(image["file_name"])
        annotations = annotations_by_image.get(str(image["id"]), [])
        if not annotations:
            raise PreparationError(f"Mendeley image has no digit annotations: {image['id']}")
        width = float(image["width"])
        height = float(image["height"])
        orientation = jpeg_exif_orientation(image_paths[number])
        upright = sorted(
            [
                (
                upright_digit_box(annotation, width, height, orientation),
                categories[int(annotation["category_id"])],
                )
                for annotation in annotations
            ],
            key=lambda item: item[0]["xmin"],
        )
        boxes = [box for box, _ in upright]
        expected_digits = "".join(digit for _, digit in upright)
        asset = f"media/mendeley-seven-segment/{number:03d}.jpg"
        cases.append(
            {
                "id": f"mendeley-seven-segment__{number:03d}",
                "dataset_id": package["dataset_id"],
                "package_id": package["package_id"],
                "kind": "numeric_reading",
                "asset": asset,
                "target_id": "numeric_display",
                "expected": {
                    "expected_digits": expected_digits,
                    "digit_boxes": boxes,
                    "input_mode": "full_frame",
                },
                "source": {
                    "annotation_image_id": str(image["id"]),
                    "published_file_number": number,
                    "exif_orientation": orientation,
                },
                "source_license": {
                    "license_id": package["license"]["dataset_license_id"],
                    "license_url": package["license"]["license_url"],
                },
            },
        )
        local_assets[asset] = {"path": image_paths[number]}
    if len(cases) != 168 or len({case["id"] for case in cases}) != 168:
        raise PreparationError("Mendeley selection must contain exactly 168 unique annotated images")
    return sorted(cases, key=lambda case: case["id"]), local_assets


def union_crop(digit_boxes: list[dict], padding: float) -> dict:
    if not digit_boxes or not 0 <= padding < 0.5:
        raise PreparationError("manual ROI requires digit boxes and bounded fixed padding")
    xmin = max(0.0, min(float(box["xmin"]) for box in digit_boxes) - padding)
    ymin = max(0.0, min(float(box["ymin"]) for box in digit_boxes) - padding)
    xmax = min(1.0, max(float(box["xmax"]) for box in digit_boxes) + padding)
    ymax = min(1.0, max(float(box["ymax"]) for box in digit_boxes) + padding)
    if xmin >= xmax or ymin >= ymax:
        raise PreparationError("manual ROI digit-box union is empty")
    return {
        "coordinate_space": "normalized_source_image_v1",
        "left": round(xmin, 8),
        "top": round(ymin, 8),
        "right": round(xmax, 8),
        "bottom": round(ymax, 8),
        "source": "published_digit_boxes_union",
        "padding_normalized": padding,
    }


def select_mendeley_manual_roi_cases(
    suite: dict,
    full_frame_cases: list[dict],
) -> list[dict]:
    package = package_by_dataset(suite, "mendeley-seven-segment-energy-meter-v1")
    diagnostic = package["selection"]["manual_roi_diagnostic"]
    full_by_id = {case["id"]: case for case in full_frame_cases}
    if len(full_by_id) != 168:
        raise PreparationError("manual ROI diagnostic requires all 168 full-frame cases")
    if any(case.get("expected", {}).get("input_mode") != "full_frame" for case in full_frame_cases):
        raise PreparationError("all numeric base cases must declare expected.input_mode=full_frame")
    file_numbers = diagnostic.get("file_numbers")
    if (
        not isinstance(file_numbers, list)
        or len(file_numbers) != 24
        or len(set(file_numbers)) != 24
        or not all(isinstance(number, int) and number > 0 for number in file_numbers)
    ):
        raise PreparationError("manual ROI diagnostic must declare 24 unique positive file numbers")
    selected_ids = [f"mendeley-seven-segment__{number:03d}" for number in file_numbers]
    if any(case_id not in full_by_id for case_id in selected_ids):
        raise PreparationError("manual ROI fixed cohort is absent from current published truth")

    padding = float(diagnostic["crop"]["padding_normalized"])
    diagnostic_cases: list[dict] = []
    for case_id in selected_ids:
        base = full_by_id[case_id]
        number = int(base["source"]["published_file_number"])
        expected = dict(base["expected"])
        expected["input_mode"] = "manual_roi_diagnostic"
        expected["input_crop"] = union_crop(expected["digit_boxes"], padding)
        diagnostic_cases.append(
            {
                "id": f"mendeley-seven-segment-manual-roi__{number:03d}",
                "dataset_id": base["dataset_id"],
                "package_id": base["package_id"],
                "kind": base["kind"],
                "asset": base["asset"],
                "target_id": base["target_id"],
                "expected": expected,
                "source": {
                    **base["source"],
                    "evaluation_mode": "manual_roi_diagnostic",
                    "source_full_frame_case_id": base["id"],
                },
                "source_license": base["source_license"],
            },
        )
    return sorted(diagnostic_cases, key=lambda case: case["id"])


def safe_slug(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")
    if not slug:
        raise PreparationError(f"cannot form safe slug from: {value}")
    return slug


def select_objectron_cases(suite: dict, root: Path) -> tuple[list[dict], dict[str, dict], Path]:
    package = package_by_dataset(suite, "objectron-v1")
    source_path = root / package["source"]["local_manifest_relative_path"]
    if not source_path.is_file() or sha256_file(source_path) != package["source"]["local_manifest_sha256"]:
        raise PreparationError(f"verified Objectron subset manifest is missing or changed: {source_path}")
    source = read_json(source_path)
    if source.get("schema") != "be-your-eye-external-reference-subset/v1":
        raise PreparationError("unexpected Objectron subset schema")
    if source.get("source", {}).get("license_sha256") != "69aeb2c407f2ffce7f31c3aba0f1eeffac248009ec2070fcd989586ac8229c82":
        raise PreparationError("Objectron license identity drift")

    by_category: dict[str, list[dict]] = defaultdict(list)
    for sequence in source.get("sequences", []):
        by_category[str(sequence["category"])].append(sequence)
    expected_categories = set(package["selection"]["categories"])
    if set(by_category) != expected_categories or any(len(sequences) != 2 for sequences in by_category.values()):
        raise PreparationError("Objectron subset must contain two identities for each reviewed category")

    cases: list[dict] = []
    local_assets: dict[str, dict] = {}
    source_root = source_path.parent
    for category in sorted(by_category):
        sequences = sorted(by_category[category], key=lambda sequence: sequence["publisher_sequence_id"])
        for sequence_index, sequence in enumerate(sequences):
            paired = sequences[1 - sequence_index]
            sequence_dir = PurePosixPath(sequence["assets"][0]["file"]).parent
            paired_dir = PurePosixPath(paired["assets"][0]["file"]).parent
            frames = {frame["role"]: frame for frame in sequence["frames"]}
            paired_frames = {frame["role"]: frame for frame in paired["frames"]}
            if set(frames) != {"reference-01", "reference-02", "reference-03", "positive-01", "positive-02"}:
                raise PreparationError(f"Objectron frame-role drift: {sequence['publisher_sequence_id']}")
            identity = safe_slug(sequence["local_instance_label"])
            references: list[str] = []
            for role in ("reference-01", "reference-02", "reference-03"):
                frame = frames[role]
                asset = f"media/objectron/{identity}/{role}.jpg"
                references.append(asset)
                local_assets[asset] = {
                    "path": source_root / Path(*sequence_dir.parts) / frame["file"],
                    "sha256": frame["sha256"],
                    "size_bytes": int(frame["bytes"]),
                }

            for role in ("positive-01", "positive-02"):
                frame = frames[role]
                asset = f"media/objectron/{identity}/{role}.jpg"
                local_assets[asset] = {
                    "path": source_root / Path(*sequence_dir.parts) / frame["file"],
                    "sha256": frame["sha256"],
                    "size_bytes": int(frame["bytes"]),
                }
                cases.append(
                    {
                        "id": f"objectron__{identity}__{role}",
                        "dataset_id": package["dataset_id"],
                        "package_id": package["package_id"],
                        "kind": "reference_match",
                        "asset": asset,
                        "references": references,
                        "target_id": identity,
                        "expected": {"reference_relation": "same_object"},
                        "source": {
                            "category": category,
                            "publisher_sequence_id": sequence["publisher_sequence_id"],
                            "frame_role": role,
                        },
                        "source_license": {
                            "license_id": package["license"]["dataset_license_id"],
                            "license_url": package["license"]["license_url"],
                        },
                    },
                )

            negative_frame = paired_frames["positive-01"]
            paired_identity = safe_slug(paired["local_instance_label"])
            negative_asset = f"media/objectron/{paired_identity}/positive-01.jpg"
            local_assets[negative_asset] = {
                "path": source_root / Path(*paired_dir.parts) / negative_frame["file"],
                "sha256": negative_frame["sha256"],
                "size_bytes": int(negative_frame["bytes"]),
            }
            cases.append(
                {
                    "id": f"objectron__{identity}__hard-negative",
                    "dataset_id": package["dataset_id"],
                    "package_id": package["package_id"],
                    "kind": "reference_match",
                    "asset": negative_asset,
                    "references": references,
                    "target_id": identity,
                    "expected": {"reference_relation": "different_object"},
                    "source": {
                        "category": category,
                        "publisher_sequence_id": paired["publisher_sequence_id"],
                        "frame_role": "positive-01",
                        "reference_identity": sequence["publisher_sequence_id"],
                    },
                    "source_license": {
                        "license_id": package["license"]["dataset_license_id"],
                        "license_url": package["license"]["license_url"],
                    },
                },
            )
    if len(cases) != 18:
        raise PreparationError("Objectron replay selection must contain 12 positives and six hard negatives")
    return sorted(cases, key=lambda case: case["id"]), local_assets, source_path


def image_downloads_for_cases(suite: dict, bundle_root: Path, cases: Iterable[dict]) -> dict[Path, str]:
    downloads: dict[Path, str] = {}
    for case in cases:
        package = package_by_dataset(suite, case["dataset_id"])
        template = package["source"]["image_url_template"]
        url = template.format(**case["source"])
        destination = bundle_root / case["asset"]
        downloads[destination] = url
    return downloads


def download_images(downloads: dict[Path, str], jobs: int) -> None:
    if jobs < 1 or jobs > 16:
        raise PreparationError("--jobs must be between 1 and 16")
    failures: list[str] = []
    with ThreadPoolExecutor(max_workers=jobs) as executor:
        pending = {executor.submit(download_file, url, path): (path, url) for path, url in downloads.items()}
        for future in as_completed(pending):
            path, url = pending[future]
            try:
                future.result()
            except Exception as error:  # Report every failed public sample in one bounded error.
                failures.append(f"{path.name}: {url}: {error}")
    if failures:
        preview = "\n".join(failures[:5])
        raise PreparationError(f"{len(failures)} image downloads failed; first failures:\n{preview}")


def bundle_media_path(bundle_root: Path, relative_value: str) -> Path:
    relative = PurePosixPath(relative_value)
    if relative.is_absolute() or not relative.parts or relative.parts[0] != "media":
        raise PreparationError(f"bundle media path must be relative under media/: {relative_value}")
    if any(part in {"", ".", ".."} for part in relative.parts):
        raise PreparationError(f"unsafe bundle media path: {relative_value}")
    return bundle_root.joinpath(*relative.parts)


def materialize_local_assets(bundle_root: Path, assets: dict[str, dict]) -> None:
    for relative, record in sorted(assets.items()):
        link_or_copy_verified(
            Path(record["path"]),
            bundle_media_path(bundle_root, relative),
            expected_sha256=record.get("sha256"),
            expected_size=record.get("size_bytes"),
        )


def bind_case_asset_identities(bundle_root: Path, cases: list[dict]) -> list[dict]:
    cache: dict[str, tuple[str, int]] = {}

    def identity(relative: str) -> tuple[str, int]:
        if relative not in cache:
            path = bundle_media_path(bundle_root, relative)
            if not path.is_file():
                raise PreparationError(f"bundle case media is missing: {path}")
            cache[relative] = (sha256_file(path), path.stat().st_size)
        return cache[relative]

    bound: list[dict] = []
    for original in cases:
        case = dict(original)
        asset_sha256, asset_size = identity(str(case.get("asset", "")))
        case["asset_sha256"] = asset_sha256
        case["asset_size_bytes"] = asset_size
        references = case.get("references")
        if references is not None:
            if not isinstance(references, list) or not references:
                raise PreparationError(f"reference case has invalid references: {case.get('id')}")
            case["reference_asset_identities"] = [
                {
                    "asset": relative,
                    "sha256": identity(relative)[0],
                    "size_bytes": identity(relative)[1],
                }
                for relative in references
            ]
        bound.append(case)
    return bound


def bundle_root_for(suite: dict, root: Path) -> Path:
    return root / suite["storage"]["bundle_relative_path"]


def existing_bundle_cases(bundle_manifest: Path) -> list[dict]:
    if not bundle_manifest.is_file():
        return []
    value = read_json(bundle_manifest)
    if value.get("schema_version") != BUNDLE_SCHEMA_VERSION:
        raise PreparationError(f"unexpected existing bundle schema: {bundle_manifest}")
    cases = value.get("cases")
    if not isinstance(cases, list):
        raise PreparationError(f"existing bundle cases are invalid: {bundle_manifest}")
    return cases


def write_bundle(suite: dict, root: Path, cases: list[dict], source_hashes: dict[str, str]) -> Path:
    bundle_root = bundle_root_for(suite, root)
    manifest_path = bundle_root / "manifest.json"
    existing = read_json(manifest_path) if manifest_path.is_file() else None
    if existing is not None and existing.get("schema_version") != BUNDLE_SCHEMA_VERSION:
        raise PreparationError(f"unexpected existing bundle schema: {manifest_path}")
    replaced_datasets = {case["dataset_id"] for case in cases}
    all_datasets = {package["dataset_id"] for package in suite["packages"]}
    suite_policy_sha256 = canonical_json_sha256(suite)
    if existing is not None and replaced_datasets != all_datasets:
        expected_identity = {
            "suite_id": suite["suite_id"],
            "catalog_version": suite["catalog"]["catalog_version"],
            "suite_policy_sha256": suite_policy_sha256,
            "preparer_version": BUNDLE_PREPARER_VERSION,
        }
        actual_identity = {key: existing.get(key) for key in expected_identity}
        if actual_identity != expected_identity:
            raise PreparationError(
                "existing bundle belongs to another suite/catalog/cohort; rebuild with --dataset all",
            )
    retained = [
        case
        for case in (existing.get("cases", []) if existing else [])
        if case.get("dataset_id") not in replaced_datasets
    ]
    merged = bind_case_asset_identities(
        bundle_root,
        sorted(retained + cases, key=lambda case: case["id"]),
    )
    merged_hashes = dict(
        existing.get("source_index_sha256", {})
        if existing and replaced_datasets != all_datasets
        else {},
    )
    merged_hashes.update(source_hashes)
    bundle = {
        "schema_version": BUNDLE_SCHEMA_VERSION,
        "suite_id": suite["suite_id"],
        "catalog_version": suite["catalog"]["catalog_version"],
        "suite_policy_sha256": suite_policy_sha256,
        "preparer_version": BUNDLE_PREPARER_VERSION,
        "generated_at": existing.get("generated_at") if existing else None,
        "source_index_sha256": dict(sorted(merged_hashes.items())),
        "cases": merged,
    }
    if existing == bundle:
        return manifest_path
    bundle["generated_at"] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    bundle_root.mkdir(parents=True, exist_ok=True)
    temporary = manifest_path.with_name("manifest.json.download")
    temporary.write_text(json.dumps(bundle, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, manifest_path)
    return manifest_path


def selected_datasets(argument: str) -> list[str]:
    if argument == "all":
        return [
            "objectron-v1",
            "mendeley-seven-segment-energy-meter-v1",
            "coco-2017-val",
        ]
    return [argument]


def prepare(suite: dict, root: Path, dataset_argument: str, jobs: int) -> dict:
    all_cases: list[dict] = []
    network_cases: list[dict] = []
    local_assets: dict[str, dict] = {}
    hashes: dict[str, str] = {}
    for dataset_id in selected_datasets(dataset_argument):
        if dataset_id == "objectron-v1":
            cases, dataset_assets, source_index = select_objectron_cases(suite, root)
            local_assets.update(dataset_assets)
        elif dataset_id == "mendeley-seven-segment-energy-meter-v1":
            source_index, image_paths = ensure_mendeley_sources(suite, root)
            full_frame_cases, dataset_assets = select_mendeley_cases(suite, source_index, image_paths)
            manual_roi_cases = select_mendeley_manual_roi_cases(suite, full_frame_cases)
            cases = full_frame_cases + manual_roi_cases
            local_assets.update(dataset_assets)
        elif dataset_id == "coco-2017-val":
            source_index = ensure_coco_annotations(suite, root)
            cases = select_coco_cases(suite, source_index)
            network_cases.extend(cases)
        else:
            raise PreparationError(f"preparation is not implemented for {dataset_id}")
        hashes[dataset_id] = sha256_file(source_index)
        all_cases.extend(cases)

    bundle_root = bundle_root_for(suite, root)
    downloads = image_downloads_for_cases(suite, bundle_root, network_cases)
    download_images(downloads, jobs)
    materialize_local_assets(bundle_root, local_assets)
    manifest = write_bundle(suite, root, all_cases, hashes)
    return {
        "status": "prepared",
        "bundle_manifest": str(manifest),
        "prepared_case_count": len(all_cases),
        "downloaded_or_verified_asset_count": len(downloads),
        "linked_or_verified_asset_count": len(local_assets),
        "datasets": selected_datasets(dataset_argument),
    }


def expected_case_count(package: dict, suite: dict) -> int | None:
    if package["dataset_id"] == "objectron-v1":
        selection = package["selection"]
        return (
            len(selection["categories"])
            * int(selection["object_identities_per_category"])
            * (
                int(selection["later_positive_frames_per_identity"])
                + int(selection["same_category_hard_negatives_per_identity"])
            )
        )
    if package["dataset_id"] == "mendeley-seven-segment-energy-meter-v1":
        diagnostic = package["selection"]["manual_roi_diagnostic"]
        return int(package["selection"].get("full_frame_case_count", 168)) + len(
            diagnostic["file_numbers"],
        )
    if package["dataset_id"] == "coco-2017-val":
        return len(catalog_object_target_ids(suite)) * sum(
            int(count) for count in package["selection"]["cases_per_target"].values()
        )
    return None


def expected_source_index(package: dict) -> dict[str, str]:
    dataset_id = str(package["dataset_id"])
    source = package["source"]
    if dataset_id == "objectron-v1":
        digest = source["local_manifest_sha256"]
    elif dataset_id == "mendeley-seven-segment-energy-meter-v1":
        digest = source["annotations"]["sha256"]
    elif dataset_id == "coco-2017-val":
        digest = source["annotations_member_sha256"]
    else:
        raise PreparationError(f"unsupported replay dataset: {dataset_id}")
    return {dataset_id: str(digest)}


def status(suite: dict, root: Path) -> dict:
    bundle_root = bundle_root_for(suite, root)
    manifest_path = bundle_root / "manifest.json"
    manifest = read_json(manifest_path) if manifest_path.is_file() else {}
    cases_value = manifest.get("cases", [])
    cases_are_objects = isinstance(cases_value, list) and all(
        isinstance(case, dict) for case in cases_value
    )
    cases = cases_value if cases_are_objects else []
    expected_manifest_identity = {
        "schema_version": BUNDLE_SCHEMA_VERSION,
        "suite_id": suite["suite_id"],
        "catalog_version": suite["catalog"]["catalog_version"],
        "suite_policy_sha256": canonical_json_sha256(suite),
        "preparer_version": BUNDLE_PREPARER_VERSION,
    }
    manifest_case_ids = [case.get("id") for case in cases]
    manifest_case_ids_ready = (
        all(isinstance(case_id, str) and case_id for case_id in manifest_case_ids)
        and len(manifest_case_ids) == len(set(manifest_case_ids))
    )
    manifest_identity_ready = all(
        manifest.get(key) == expected
        for key, expected in expected_manifest_identity.items()
    ) and cases_are_objects and manifest_case_ids_ready
    source_indexes = manifest.get("source_index_sha256")
    if not isinstance(source_indexes, dict):
        source_indexes = {}
    expected_source_indexes = {
        dataset_id: digest
        for package in suite["packages"]
        for dataset_id, digest in expected_source_index(package).items()
    }
    source_index_identity_ready = source_indexes == expected_source_indexes
    expected_package_datasets = {
        (package["package_id"], package["dataset_id"])
        for package in suite["packages"]
    }
    expected_total_case_count = sum(
        expected_case_count(package, suite) or 0
        for package in suite["packages"]
    )
    manifest_cohort_identity_ready = (
        len(cases) == expected_total_case_count
        and all(
            (case.get("package_id"), case.get("dataset_id")) in expected_package_datasets
            for case in cases
        )
    )
    result_packages: list[dict] = []
    for package in suite["packages"]:
        package_cases = [case for case in cases if case.get("package_id") == package["package_id"]]
        dataset_cases = [case for case in cases if case.get("dataset_id") == package["dataset_id"]]
        expected = expected_case_count(package, suite)
        missing_assets = sum(not (bundle_root / case.get("asset", "")).is_file() for case in package_cases)
        case_ids = [case.get("id") for case in package_cases]
        cohort_identity_ready = (
            len(package_cases) == len(dataset_cases)
            and all(
                case.get("package_id") == package["package_id"]
                and case.get("dataset_id") == package["dataset_id"]
                for case in package_cases
            )
            and all(isinstance(case_id, str) and case_id for case_id in case_ids)
            and len(case_ids) == len(set(case_ids))
        )
        if package["dataset_id"] == "coco-2017-val":
            expected_targets = set(catalog_object_target_ids(suite))
            expected_roles = package["selection"]["cases_per_target"]
            cohort_identity_ready = cohort_identity_ready and {
                case.get("target_id") for case in package_cases
            } == expected_targets and all(
                sum(
                    case.get("target_id") == target_id
                    and isinstance(case.get("source"), dict)
                    and case["source"].get("evaluation_role") == role
                    for case in package_cases
                ) == int(count)
                for target_id in expected_targets
                for role, count in expected_roles.items()
            )
        source_index_ready = source_index_identity_ready
        ready = (
            manifest_identity_ready
            and source_index_ready
            and manifest_cohort_identity_ready
            and cohort_identity_ready
            and expected is not None
            and len(package_cases) == expected
            and missing_assets == 0
        )
        result_packages.append(
            {
                "package_id": package["package_id"],
                "dataset_id": package["dataset_id"],
                "preparation": package["preparation"],
                "expected_case_count": expected,
                "prepared_case_count": len(package_cases),
                "missing_asset_count": missing_assets,
                "source_index_ready": source_index_ready,
                "cohort_identity_ready": cohort_identity_ready,
                "ready": ready,
            },
        )
    automated = [item for item in result_packages if item["expected_case_count"] is not None]
    return {
        "suite_id": suite["suite_id"],
        "root": str(root),
        "bundle_manifest": str(manifest_path),
        "manifest_identity_ready": manifest_identity_ready,
        "manifest_case_ids_ready": manifest_case_ids_ready,
        "source_index_identity_ready": source_index_identity_ready,
        "manifest_cohort_identity_ready": manifest_cohort_identity_ready,
        "automated_datasets_ready": bool(automated) and all(item["ready"] for item in automated),
        "full_bundle_ready": all(item["ready"] for item in result_packages),
        "packages": result_packages,
    }


def dry_run(suite: dict, root: Path, dataset_argument: str) -> dict:
    packages = []
    selected = set(selected_datasets(dataset_argument))
    for package in suite["packages"]:
        if package["dataset_id"] not in selected:
            continue
        packages.append(
            {
                "package_id": package["package_id"],
                "dataset_id": package["dataset_id"],
                "planned_case_count": expected_case_count(package, suite),
                "annotations_url": package["source"].get("annotations_url")
                or package["source"].get("annotations", {}).get("url"),
                "image_url_template": package["source"].get("image_url_template"),
            },
        )
    return {
        "status": "dry_run",
        "suite_id": suite["suite_id"],
        "root": str(root),
        "bundle_manifest": str(bundle_root_for(suite, root) / "manifest.json"),
        "network_used": False,
        "packages": packages,
    }


def build_parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--suite", type=Path, default=DEFAULT_SUITE_PATH)
    common.add_argument("--root", type=Path, help="External cache root; defaults to suite storage.default_root")
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    dry = commands.add_parser("dry-run", parents=[common], help="Validate policy and print the network-free plan")
    dry.add_argument(
        "--dataset",
        choices=(
            "all",
            "objectron-v1",
            "mendeley-seven-segment-energy-meter-v1",
            "coco-2017-val",
        ),
        default="all",
    )
    commands.add_parser("status", parents=[common], help="Inspect local bundle state without network access")
    prepare_command = commands.add_parser("prepare", parents=[common], help="Prepare selected public data on the external volume")
    prepare_command.add_argument(
        "--dataset",
        choices=(
            "all",
            "objectron-v1",
            "mendeley-seven-segment-energy-meter-v1",
            "coco-2017-val",
        ),
        default="all",
    )
    prepare_command.add_argument("--jobs", type=int, default=8)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        suite = load_and_validate_suite(args.suite.resolve())
        configured_root = Path(args.root or suite["storage"]["default_root"])
        root = validate_root(configured_root, for_media_write=args.command == "prepare")
        if args.command == "dry-run":
            result = dry_run(suite, root, args.dataset)
        elif args.command == "status":
            result = status(suite, root)
        else:
            result = prepare(suite, root, args.dataset, args.jobs)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except PreparationError as error:
        print(f"external replay preparation error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
