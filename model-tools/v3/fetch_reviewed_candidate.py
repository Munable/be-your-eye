#!/usr/bin/env python3
"""Fetch or verify one explicitly reviewed internal-evaluation model archive.

The tracked candidate record is policy input. Downloaded bytes and the generated acquisition
result stay in the Git-ignored output directory. This tool never turns an evaluation candidate
into a commercial package and never infers missing permissions.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import ssl
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from typing import BinaryIO


class AcquisitionError(RuntimeError):
    pass


def load_candidate(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AcquisitionError("candidate must be a JSON object")
    if value.get("review_status") != "approved_for_acquisition":
        raise AcquisitionError("candidate is not approved_for_acquisition")
    if value.get("internal_evaluation_allowed") is not True:
        raise AcquisitionError("internal evaluation is not explicitly allowed")
    if value.get("commercial_catalog_eligible") is not False:
        raise AcquisitionError("evaluation fetch requires commercial_catalog_eligible=false")
    decision = value.get("license_decision")
    if not isinstance(decision, dict) or decision.get("decision") != "internal_evaluation_only":
        raise AcquisitionError("license decision must be internal_evaluation_only")
    acquisition = value.get("acquisition")
    if not isinstance(acquisition, dict):
        raise AcquisitionError("acquisition record is missing")
    parsed = urllib.parse.urlparse(str(acquisition.get("url", "")))
    if parsed.scheme != "https" or not parsed.netloc or not parsed.query:
        raise AcquisitionError("acquisition URL must be immutable HTTPS with a query identity")
    if not isinstance(acquisition.get("published_size_bytes"), int) or acquisition["published_size_bytes"] <= 0:
        raise AcquisitionError("published_size_bytes must be positive")
    checksum = acquisition.get("published_checksum")
    if not isinstance(checksum, dict) or not checksum.get("algorithm") or not checksum.get("base64"):
        raise AcquisitionError("a published transport checksum is required")
    return value


def sha256_stream(stream: BinaryIO) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(block)
        size += len(block)
    return digest.hexdigest(), size


def verify_archive(path: Path, expected_size: int) -> tuple[str, int]:
    if not path.is_file():
        raise AcquisitionError("archive does not exist")
    with path.open("rb") as stream:
        sha256, size = sha256_stream(stream)
    if size != expected_size:
        raise AcquisitionError(f"archive size mismatch: expected {expected_size}, got {size}")
    return sha256, size


def safe_zip_member(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if path.is_absolute() or not path.parts or any(part in {"", ".", ".."} for part in path.parts):
        raise AcquisitionError(f"unsafe archive member: {name}")
    return path


def extract_and_hash(archive: Path, destination: Path) -> list[dict]:
    staging = Path(tempfile.mkdtemp(prefix="extract-", dir=destination.parent))
    try:
        results: list[dict] = []
        with zipfile.ZipFile(archive) as bundle:
            files = [entry for entry in bundle.infolist() if not entry.is_dir()]
            if not files:
                raise AcquisitionError("archive contains no files")
            for entry in files:
                relative = safe_zip_member(entry.filename)
                target = staging.joinpath(*relative.parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(entry) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output, length=1024 * 1024)
                with target.open("rb") as stream:
                    sha256, size = sha256_stream(stream)
                results.append({"path": relative.as_posix(), "sha256": sha256, "size_bytes": size})
        if destination.exists():
            shutil.rmtree(destination)
        os.replace(staging, destination)
        return sorted(results, key=lambda item: item["path"])
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def download(url: str, destination: Path, expected_size: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    part = destination.with_suffix(destination.suffix + ".part")
    offset = part.stat().st_size if part.exists() else 0
    headers = {"User-Agent": "Be-Your-Eye-model-acquisition/1.0"}
    if offset:
        headers["Range"] = f"bytes={offset}-"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, context=ssl.create_default_context()) as source:
        status = getattr(source, "status", 200)
        if offset and status != 206:
            offset = 0
            part.unlink(missing_ok=True)
            return download(url, destination, expected_size)
        mode = "ab" if offset else "wb"
        with part.open(mode) as output:
            shutil.copyfileobj(source, output, length=1024 * 1024)
    if part.stat().st_size != expected_size:
        raise AcquisitionError(
            f"download size mismatch: expected {expected_size}, got {part.stat().st_size}",
        )
    os.replace(part, destination)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--archive", type=Path, help="Verify an already downloaded archive instead of fetching")
    args = parser.parse_args()

    candidate = load_candidate(args.candidate)
    acquisition = candidate["acquisition"]
    args.output.mkdir(parents=True, exist_ok=True)
    archive = args.archive or args.output / "artifact.zip"
    if args.archive is None:
        download(acquisition["url"], archive, acquisition["published_size_bytes"])
    archive_sha256, archive_size = verify_archive(archive, acquisition["published_size_bytes"])
    extracted = extract_and_hash(archive, args.output / "extracted")
    result = {
        "schema_version": "1.0",
        "candidate_id": candidate["candidate_id"],
        "review_status": candidate["review_status"],
        "commercial_catalog_eligible": False,
        "source_url": acquisition["url"],
        "published_checksum": acquisition["published_checksum"],
        "archive": {"sha256": archive_sha256, "size_bytes": archive_size},
        "files": extracted,
    }
    (args.output / "acquisition-result.json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
