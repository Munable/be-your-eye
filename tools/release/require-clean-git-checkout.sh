#!/usr/bin/env bash
set -euo pipefail

readonly REPO_ROOT="${1:?usage: require-clean-git-checkout.sh REPO_ROOT}"

if ! GIT_TOP_LEVEL="$(git -C "$REPO_ROOT" rev-parse --show-toplevel 2>/dev/null)"; then
    printf 'release source is not a Git checkout: %s\n' "$REPO_ROOT" >&2
    exit 1
fi
readonly GIT_TOP_LEVEL

REPO_ROOT_REAL="$(CDPATH='' cd -- "$REPO_ROOT" && pwd -P)"
readonly REPO_ROOT_REAL
GIT_TOP_LEVEL_REAL="$(CDPATH='' cd -- "$GIT_TOP_LEVEL" && pwd -P)"
readonly GIT_TOP_LEVEL_REAL
if [[ "$REPO_ROOT_REAL" != "$GIT_TOP_LEVEL_REAL" ]]; then
    printf 'release source must be the Git checkout root: expected %s, got %s\n' \
        "$REPO_ROOT_REAL" "$GIT_TOP_LEVEL_REAL" >&2
    exit 1
fi

if ! GIT_COMMIT="$(git -C "$REPO_ROOT_REAL" rev-parse --verify 'HEAD^{commit}' 2>/dev/null)"; then
    printf 'release source has no exact Git commit: %s\n' "$REPO_ROOT_REAL" >&2
    exit 1
fi
readonly GIT_COMMIT

# Git deliberately hides tracked-file changes behind assume-unchanged and
# skip-worktree index flags. A release checkout cannot use either flag because
# its on-disk source would no longer be verifiable against the recorded commit.
HIDDEN_TRACKED_FILES="$(
    git -C "$REPO_ROOT_REAL" ls-files -v |
        LC_ALL=C awk 'substr($0, 1, 1) == "S" || substr($0, 1, 1) ~ /[a-z]/ { print }'
)"
readonly HIDDEN_TRACKED_FILES
if [[ -n "$HIDDEN_TRACKED_FILES" ]]; then
    printf 'release source has assume-unchanged or skip-worktree tracked files\n' >&2
    printf '%s\n' "$HIDDEN_TRACKED_FILES" >&2
    exit 1
fi

# Porcelain v1 covers staged, unstaged, conflict, submodule, and untracked
# changes. Git's normal ignore rules remain in force, so generated ignored
# build outputs do not make a verified checkout dirty.
GIT_STATUS="$(
    git -c core.fsmonitor=false -c core.untrackedCache=false \
        -C "$REPO_ROOT_REAL" status \
        --porcelain=v1 \
        --untracked-files=all \
        --ignore-submodules=none
)"
readonly GIT_STATUS
if [[ -n "$GIT_STATUS" ]]; then
    printf 'release source checkout is dirty; commit or remove every tracked/untracked change first\n' >&2
    printf '%s\n' "$GIT_STATUS" >&2
    exit 1
fi

printf '%s\n' "$GIT_COMMIT"
