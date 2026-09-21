#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
readonly CHECKER="$SCRIPT_DIR/check-secret-hygiene.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

# The real checkout remains part of the local CI gate.
bash "$CHECKER"

fixture_repo="$tmp_dir/repository"
fixture_checker="$fixture_repo/tools/release/check-secret-hygiene.sh"
mkdir -p "$(dirname -- "$fixture_checker")"
cp "$CHECKER" "$fixture_checker"
git -C "$fixture_repo" init -q

printf 'ordinary fixture content\n' >"$fixture_repo/safe.txt"
git -C "$fixture_repo" add safe.txt tools/release/check-secret-hygiene.sh
bash "$fixture_checker"

# Build the synthetic value only in the disposable repository so no complete
# provider-like credential is stored in a tracked source file.
printf '%s%s\n' 'sk-' 'fixture-secret-material-0123456789' >"$fixture_repo/provider.txt"
git -C "$fixture_repo" add provider.txt
tracked_output="$tmp_dir/tracked-output.txt"
if bash "$fixture_checker" >"$tracked_output" 2>&1; then
    printf 'tracked provider fixture unexpectedly passed\n' >&2
    exit 1
else
    tracked_status=$?
fi
if ((tracked_status != 2)); then
    printf 'tracked provider fixture returned %d, expected 2\n' "$tracked_status" >&2
    cat "$tracked_output" >&2
    exit 1
fi
grep -Fq 'tracked repository: secret-like material found' "$tracked_output"

printf 'private material: %s\n' 'placeholder only' >"$tmp_dir/safe.txt"
bash "$CHECKER" --path "$tmp_dir/safe.txt"

explicit_output="$tmp_dir/explicit-output.txt"
if bash "$CHECKER" --path "$fixture_repo/provider.txt" \
    >"$explicit_output" 2>&1; then
    printf 'explicit provider fixture unexpectedly passed\n' >&2
    exit 1
else
    explicit_status=$?
fi
if ((explicit_status != 2)); then
    printf 'explicit provider fixture returned %d, expected 2\n' "$explicit_status" >&2
    cat "$explicit_output" >&2
    exit 1
fi
grep -Fq 'artifact scan: secret-like material found' "$explicit_output"

for stripe_prefix in sk_live_ sk_test_ rk_live_ rk_test_ whsec_; do
    printf '%s%s\n' "$stripe_prefix" 'FixtureOnly01234567890123456789' >"$tmp_dir/stripe.txt"
    if bash "$CHECKER" --path "$tmp_dir/stripe.txt" >"$explicit_output" 2>&1; then
        printf 'Stripe credential fixture unexpectedly passed\n' >&2
        exit 1
    else
        [[ "$?" == 2 ]]
    fi
    grep -Fq 'artifact scan: secret-like material found' "$explicit_output"
done

if bash "$CHECKER" --path "$tmp_dir/missing.txt" >/dev/null 2>&1; then
    printf 'missing path unexpectedly passed\n' >&2
    exit 1
fi

non_repo_checker="$tmp_dir/non-repository/tools/release/check-secret-hygiene.sh"
mkdir -p "$(dirname -- "$non_repo_checker")"
cp "$CHECKER" "$non_repo_checker"
git_error_output="$tmp_dir/git-error-output.txt"
if bash "$non_repo_checker" >"$git_error_output" 2>&1; then
    printf 'tracked scanner execution error unexpectedly passed\n' >&2
    exit 1
else
    git_error_status=$?
fi
if ((git_error_status != 2)); then
    printf 'tracked scanner execution error returned %d, expected 2\n' \
        "$git_error_status" >&2
    cat "$git_error_output" >&2
    exit 1
fi
grep -Fq 'tracked repository: scanner failed' "$git_error_output"

fake_rg="$tmp_dir/fake-rg"
cat >"$fake_rg" <<'EOF'
#!/usr/bin/env bash
exit 73
EOF
chmod +x "$fake_rg"
for scanner_source in "$tmp_dir/safe.txt" "$tmp_dir"; do
    scanner_output="$tmp_dir/scanner-output-$(basename -- "$scanner_source").txt"
    if env RG_BIN="$fake_rg" bash "$CHECKER" --path "$scanner_source" \
        >"$scanner_output" 2>&1; then
        printf 'scanner execution error unexpectedly passed for %s\n' \
            "$scanner_source" >&2
        exit 1
    else
        scanner_status=$?
    fi
    if ((scanner_status != 2)); then
        printf 'scanner execution error returned %d for %s, expected 2\n' \
            "$scanner_status" "$scanner_source" >&2
        cat "$scanner_output" >&2
        exit 1
    fi
    grep -Fq 'artifact scan: scanner failed' "$scanner_output"
done

printf 'secret hygiene tests passed\n'
