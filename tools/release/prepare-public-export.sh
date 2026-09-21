#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)"
[[ $# == 1 ]] || { echo 'Usage: prepare-public-export.sh ABSOLUTE_OUTPUT_DIRECTORY' >&2; exit 64; }
node "$ROOT/tools/release/prepare-public-export.mjs" "$1"
