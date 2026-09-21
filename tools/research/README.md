# Be Your Eye execution evidence

This directory contains the narrow execution boundary for research-significant work. It does not
replace `evidence/current`, the signed Catalog, product validators, or the external ResearchLedger.

## Boundaries

- Ordinary build, unit, emulator, hosted, device, or human checks produce an `engineering_event`
  receipt only when their outer runner already has one structured JSON result.
- Exploratory and confirmatory comparisons must freeze a plan before the first attempt, then freeze
  one capsule after all planned attempts finish.
- The freezer accepts bounded JSON only. Credential-like keys or values, device serials, accounts,
  private media, raw camera frames, AAC, and full command logs are rejected.
- The source snapshot covers Git HEAD, staged and unstaged tracked changes, and safe non-ignored
  untracked content. Git-ignored content is deliberately not read because it includes credentials,
  private media, caches, and build products; only its path/size/mtime metadata is digested.
- Every plan declares `design_context.ignored_runtime_inputs` (an empty array is explicit). Each
  listed path must be Git-ignored, safe to hash, and backed by a binding whose `source_path`, SHA-256,
  and byte size are rechecked at both the plan and capsule boundaries. Tracked and safe untracked
  source digests must also remain unchanged between those boundaries.
- A successful receipt or capsule is written with exclusive creation under
  `/Volumes/DevDisk/DeveloperData/ResearchLedger/be-your-eyes/executions/`, independently verified,
  and indexed through the existing locked ResearchLedger inbox.
- None of these commands updates `evidence/current` or closes a product, device, hosted, quality, or
  release gate.

This optional maintainer workflow requires a separately installed ResearchLedger. It is not needed to build or run Community.

## Commands

```bash
# Freeze one ordinary engineering receipt after the outer runner writes result.json.
mise exec 'node@24.16.0' -- node tools/research/freeze-execution.mjs \
  freeze-receipt --input receipt-metadata.json --result result.json

# Freeze a comparison plan before any attempt.
mise exec 'node@24.16.0' -- node tools/research/freeze-execution.mjs \
  freeze-plan --input plan.json

# After all attempts, bind the result to the previously frozen plan.
mise exec 'node@24.16.0' -- node tools/research/freeze-execution.mjs \
  freeze-capsule --plan /absolute/path/to/frozen/plan.json --result result.json

# Recompute the stored record and artifact hashes.
mise exec 'node@24.16.0' -- node tools/research/freeze-execution.mjs \
  verify --record /absolute/path/to/receipt-or-capsule.json

# Resume after an interruption that left a complete record without verification or indexing.
mise exec 'node@24.16.0' -- node tools/research/freeze-execution.mjs \
  recover-record --record /absolute/path/to/receipt-plan-or-capsule.json
```

Use `--dry-run` with a freeze command to validate its input without writing or registering evidence.
Writes are atomically published without overwrite. A content-addressed artifact or capsule result
left before its parent record is safe to reuse when its bytes match. If a complete record exists but
verification was interrupted, use `recover-record`. If its verification receipt also exists and only
Ledger registration failed, register the existing record directly:

```bash
/usr/bin/python3 "$HOME/Library/Application Support/ResearchLedger/bin/ledger.py" \
  record-execution /absolute/path/to/the/frozen/record.json
```

Until that command succeeds, the record remains unindexed and must not support a research claim.
The tests cover exclusive writes, credential and raw-blob rejection, staged and untracked source
digests, symlink escape rejection, temporal prespecification, independent hash verification, tamper
detection, and confirmatory-plan gates.

```bash
mise exec 'node@24.16.0' -- node --test tools/research/freeze-execution.test.mjs
python3 "$HOME/Library/Application Support/ResearchLedger/bin/ledger.py" self-test
```
