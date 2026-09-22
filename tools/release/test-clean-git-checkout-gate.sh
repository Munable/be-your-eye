#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
readonly GATE="$SCRIPT_DIR/require-clean-git-checkout.sh"
TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/be-your-eyes-clean-source-test.XXXXXX")"
readonly TEMP_ROOT
trap 'rm -rf -- "$TEMP_ROOT"' EXIT

new_repo() {
    local name="$1"
    local repo="$TEMP_ROOT/$name"
    mkdir -p "$repo"
    git -C "$repo" init --quiet
    git -C "$repo" config user.name 'Release Gate Test'
    git -C "$repo" config user.email 'release-gate-test@example.invalid'
    printf 'ignored-output/\n' > "$repo/.gitignore"
    printf 'baseline\n' > "$repo/tracked.txt"
    git -C "$repo" add .gitignore tracked.txt
    git -C "$repo" commit --quiet -m baseline
    printf '%s\n' "$repo"
}

expect_pass() {
    local label="$1"
    local repo="$2"
    local expected
    local actual
    expected="$(git -C "$repo" rev-parse HEAD)"
    if ! actual="$(bash "$GATE" "$repo" 2>"$TEMP_ROOT/$label.stderr")"; then
        printf 'expected clean-source gate to pass: %s\n' "$label" >&2
        cat "$TEMP_ROOT/$label.stderr" >&2
        exit 1
    fi
    if [[ "$actual" != "$expected" ]]; then
        printf 'clean-source gate returned wrong commit for %s\n' "$label" >&2
        exit 1
    fi
}

expect_fail() {
    local label="$1"
    local repo="$2"
    if bash "$GATE" "$repo" >"$TEMP_ROOT/$label.stdout" 2>"$TEMP_ROOT/$label.stderr"; then
        printf 'expected clean-source gate to fail: %s\n' "$label" >&2
        exit 1
    fi
}

clean_repo="$(new_repo clean)"
expect_pass clean "$clean_repo"

tracked_repo="$(new_repo tracked)"
printf 'changed\n' >> "$tracked_repo/tracked.txt"
expect_fail tracked "$tracked_repo"

staged_repo="$(new_repo staged)"
printf 'changed\n' >> "$staged_repo/tracked.txt"
git -C "$staged_repo" add tracked.txt
expect_fail staged "$staged_repo"

untracked_repo="$(new_repo untracked)"
printf 'new\n' > "$untracked_repo/untracked.txt"
expect_fail untracked "$untracked_repo"

assume_repo="$(new_repo assume-unchanged)"
git -C "$assume_repo" update-index --assume-unchanged tracked.txt
printf 'hidden change\n' >> "$assume_repo/tracked.txt"
expect_fail assume-unchanged "$assume_repo"

skip_repo="$(new_repo skip-worktree)"
git -C "$skip_repo" update-index --skip-worktree tracked.txt
printf 'hidden change\n' >> "$skip_repo/tracked.txt"
expect_fail skip-worktree "$skip_repo"

ignored_repo="$(new_repo ignored)"
mkdir -p "$ignored_repo/ignored-output"
printf 'generated\n' > "$ignored_repo/ignored-output/result.txt"
expect_pass ignored "$ignored_repo"

if [[ "$(grep -c 'require-clean-git-checkout.sh' "$SCRIPT_DIR/build-community.sh")" -ne 2 ]]; then
    printf 'Community release must verify clean source before and after its build\n' >&2
    exit 1
fi
printf 'clean-source release gate: 8/8 passed\n'
