#!/usr/bin/env python3
"""Build and verify deterministic camera replay reels from external media."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from fractions import Fraction
from pathlib import Path
from typing import Any


WIDTH = 1280
HEIGHT = 720
FPS = 30
TOTAL_SECONDS = 25
TIMELINE = (
    ("absent", 0, 5),
    ("present", 5, 12),
    ("absent", 12, 18),
    ("present", 18, 25),
)
DEFAULT_ROOT = Path(
    os.environ.get(
        "BEYOUREYES_VISION_EVAL_ROOT",
        "/Volumes/DevDisk/DeveloperData/be-your-eyes/external-eval-cache/external-replay-set-v1",
    )
)


class ReelError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReelError(f"invalid JSON {path}: {error}") from error
    if not isinstance(value, dict):
        raise ReelError(f"JSON root must be an object: {path}")
    return value


def resolve_source(path_value: str, spec_path: Path) -> Path:
    path = Path(path_value).expanduser()
    if not path.is_absolute():
        path = spec_path.parent / path
    return path.resolve()


def validate_spec(spec_path: Path) -> dict[str, Any]:
    spec = load_json(spec_path)
    if spec.get("schema_version") != "1.0":
        raise ReelError("schema_version must be 1.0")
    reel_id = spec.get("reel_id")
    if not isinstance(reel_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{1,79}", reel_id):
        raise ReelError("reel_id must be 2-80 lowercase safe characters")
    expectation = spec.get("expectation")
    if not isinstance(expectation, dict) or not expectation:
        raise ReelError("expectation must be a non-empty object")
    sources = spec.get("sources")
    if not isinstance(sources, dict):
        raise ReelError("sources must be an object")

    normalized_sources: dict[str, dict[str, Any]] = {}
    for state in ("absent", "present"):
        source = sources.get(state)
        if not isinstance(source, dict):
            raise ReelError(f"sources.{state} must be an object")
        media_type = source.get("media_type")
        if media_type not in {"image", "video"}:
            raise ReelError(f"sources.{state}.media_type must be image or video")
        source_url = source.get("source_url")
        if not isinstance(source_url, str) or not re.match(r"^https?://", source_url):
            raise ReelError(f"sources.{state}.source_url must be an http(s) URL")
        license_value = source.get("license")
        if not isinstance(license_value, str) or not license_value.strip():
            raise ReelError(f"sources.{state}.license is required")
        expected_hash = source.get("sha256")
        if not isinstance(expected_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
            raise ReelError(f"sources.{state}.sha256 must be lowercase SHA-256")
        path_value = source.get("path")
        if not isinstance(path_value, str) or not path_value:
            raise ReelError(f"sources.{state}.path is required")
        source_path = resolve_source(path_value, spec_path)
        if not source_path.is_file():
            raise ReelError(f"missing sources.{state} media: {source_path}")
        actual_hash = sha256(source_path)
        if actual_hash != expected_hash:
            raise ReelError(
                f"sources.{state} SHA-256 mismatch: expected {expected_hash}, got {actual_hash}"
            )
        start_seconds = source.get("start_seconds", 0)
        if not isinstance(start_seconds, (int, float)) or start_seconds < 0:
            raise ReelError(f"sources.{state}.start_seconds must be non-negative")
        normalized_sources[state] = {
            **source,
            "path": str(source_path),
            "start_seconds": float(start_seconds),
        }

    return {
        **spec,
        "sources": normalized_sources,
    }


def require_tool(name: str) -> str:
    path = shutil.which(name)
    if path is None:
        raise ReelError(f"required tool is missing: {name}")
    return path


def build_command(spec: dict[str, Any], output_path: Path) -> list[str]:
    ffmpeg = require_tool("ffmpeg")
    command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y"]
    filter_parts: list[str] = []
    concat_inputs: list[str] = []

    for index, (state, start, end) in enumerate(TIMELINE):
        source = spec["sources"][state]
        if source["media_type"] == "image":
            command.extend(["-loop", "1", "-framerate", str(FPS)])
        else:
            command.extend(["-stream_loop", "-1", "-ss", f"{source['start_seconds']:g}"])
        command.extend(["-i", source["path"]])
        duration = end - start
        filter_parts.append(
            f"[{index}:v]trim=duration={duration},setpts=PTS-STARTPTS,"
            f"scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease:flags=lanczos:out_range=tv,"
            f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:color=black,"
            f"setsar=1,fps={FPS},format=yuv420p[v{index}]"
        )
        concat_inputs.append(f"[v{index}]")

    filter_parts.append("".join(concat_inputs) + f"concat=n={len(TIMELINE)}:v=1:a=0[outv]")
    command.extend(
        [
            "-filter_complex",
            ";".join(filter_parts),
            "-map",
            "[outv]",
            "-an",
            "-c:v",
            "libx264",
            "-preset",
            "medium",
            "-crf",
            "18",
            "-pix_fmt",
            "yuv420p",
            "-r",
            str(FPS),
            "-g",
            str(FPS),
            "-keyint_min",
            str(FPS),
            "-sc_threshold",
            "0",
            "-threads",
            "1",
            "-map_metadata",
            "-1",
            "-movflags",
            "+faststart",
            "-t",
            str(TOTAL_SECONDS),
            str(output_path),
        ]
    )
    return command


def probe_reel(reel_path: Path) -> dict[str, Any]:
    ffprobe = require_tool("ffprobe")
    result = subprocess.run(
        [
            ffprobe,
            "-v",
            "error",
            "-count_frames",
            "-show_entries",
            "format=duration:stream=index,codec_type,codec_name,width,height,pix_fmt,r_frame_rate,nb_read_frames",
            "-of",
            "json",
            str(reel_path),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    probe = json.loads(result.stdout)
    streams = probe.get("streams", [])
    video_streams = [stream for stream in streams if stream.get("codec_type") == "video"]
    audio_streams = [stream for stream in streams if stream.get("codec_type") == "audio"]
    if len(video_streams) != 1 or audio_streams:
        raise ReelError("reel must contain exactly one video stream and no audio")
    video = video_streams[0]
    duration = float(probe.get("format", {}).get("duration", 0))
    rate = float(Fraction(video.get("r_frame_rate", "0/1")))
    checks = {
        "codec_name": video.get("codec_name"),
        "width": video.get("width"),
        "height": video.get("height"),
        "pix_fmt": video.get("pix_fmt"),
        "fps": rate,
        "frame_count": int(video.get("nb_read_frames", 0)),
        "duration_seconds": duration,
    }
    expected_frames = FPS * TOTAL_SECONDS
    if checks["codec_name"] != "h264":
        raise ReelError(f"unexpected codec: {checks['codec_name']}")
    if (checks["width"], checks["height"]) != (WIDTH, HEIGHT):
        raise ReelError(f"unexpected dimensions: {checks['width']}x{checks['height']}")
    if checks["pix_fmt"] != "yuv420p":
        raise ReelError(f"unexpected pixel format: {checks['pix_fmt']}")
    if abs(rate - FPS) > 0.001:
        raise ReelError(f"unexpected frame rate: {rate}")
    if checks["frame_count"] != expected_frames:
        raise ReelError(
            f"unexpected frame count: {checks['frame_count']} (expected {expected_frames})"
        )
    if abs(duration - TOTAL_SECONDS) > 0.05:
        raise ReelError(f"unexpected duration: {duration}")
    return checks


def sidecar_path_for(reel_path: Path) -> Path:
    return reel_path.with_suffix(".reel.json")


def write_sidecar(spec: dict[str, Any], reel_path: Path, checks: dict[str, Any]) -> Path:
    sidecar_path = sidecar_path_for(reel_path)
    sidecar = {
        "schema_version": "1.0",
        "reel_id": spec["reel_id"],
        "expectation": spec["expectation"],
        "sources": spec["sources"],
        "timeline": [
            {
                "state": state,
                "start_seconds": start,
                "end_seconds": end,
                "expected_events": 0 if state == "absent" else 1,
            }
            for state, start, end in TIMELINE
        ],
        "render": checks,
        "reel": {
            "path": str(reel_path.resolve()),
            "sha256": sha256(reel_path),
        },
    }
    sidecar_path.write_text(
        json.dumps(sidecar, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return sidecar_path


def build(args: argparse.Namespace) -> None:
    spec_path = args.spec.expanduser().resolve()
    spec = validate_spec(spec_path)
    output_path = (
        args.output.expanduser().resolve()
        if args.output
        else (DEFAULT_ROOT / "reels" / f"{spec['reel_id']}.mp4").resolve()
    )
    command = build_command(spec, output_path)
    if args.dry_run:
        print(shlex.join(command))
        print(f"sidecar: {sidecar_path_for(output_path)}")
        return

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="reel-build-", dir=output_path.parent) as directory:
        temporary_output = Path(directory) / output_path.name
        temporary_command = build_command(spec, temporary_output)
        subprocess.run(temporary_command, check=True)
        checks = probe_reel(temporary_output)
        temporary_output.replace(output_path)
    sidecar_path = write_sidecar(spec, output_path, checks)
    print(f"PASS reel={output_path}")
    print(f"PASS sidecar={sidecar_path}")


def verify(args: argparse.Namespace) -> None:
    reel_path = args.reel.expanduser().resolve()
    if not reel_path.is_file():
        raise ReelError(f"missing reel: {reel_path}")
    sidecar_path = (
        args.sidecar.expanduser().resolve() if args.sidecar else sidecar_path_for(reel_path)
    )
    sidecar = load_json(sidecar_path)
    if sidecar.get("schema_version") != "1.0":
        raise ReelError("sidecar schema_version must be 1.0")
    timeline = [
        (item.get("state"), item.get("start_seconds"), item.get("end_seconds"))
        for item in sidecar.get("timeline", [])
        if isinstance(item, dict)
    ]
    if timeline != list(TIMELINE):
        raise ReelError("sidecar timeline is not the fixed 5/7/6/7 sequence")
    expected_hash = sidecar.get("reel", {}).get("sha256")
    actual_hash = sha256(reel_path)
    if expected_hash != actual_hash:
        raise ReelError(f"reel SHA-256 mismatch: expected {expected_hash}, got {actual_hash}")
    checks = probe_reel(reel_path)
    print(json.dumps({"status": "PASS", "reel": str(reel_path), **checks}, sort_keys=True))


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    commands = value.add_subparsers(dest="command", required=True)
    build_parser = commands.add_parser("build", help="build a fixed 25-second reel")
    build_parser.add_argument("--spec", required=True, type=Path)
    build_parser.add_argument("--output", type=Path)
    build_parser.add_argument("--dry-run", action="store_true")
    build_parser.set_defaults(function=build)
    verify_parser = commands.add_parser("verify", help="verify a reel and its sidecar")
    verify_parser.add_argument("--reel", required=True, type=Path)
    verify_parser.add_argument("--sidecar", type=Path)
    verify_parser.set_defaults(function=verify)
    return value


def main() -> int:
    try:
        args = parser().parse_args()
        args.function(args)
        return 0
    except (ReelError, subprocess.CalledProcessError, OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
