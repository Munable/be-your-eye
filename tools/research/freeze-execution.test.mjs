import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import test from 'node:test';

import {
  freezeCapsule,
  freezePlan,
  freezeReceipt,
  recoverRecord,
  sourceSnapshot,
  verifyRecord,
} from './freeze-execution.mjs';

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'be-your-eye-execution-'));
  const repo = join(root, 'repo');
  const archive = join(root, 'archive');
  execFileSync('/bin/mkdir', ['-p', repo]);
  execFileSync('/usr/bin/git', ['-C', repo, 'init', '-q']);
  execFileSync('/usr/bin/git', ['-C', repo, 'config', 'user.name', 'Evidence Test']);
  execFileSync('/usr/bin/git', ['-C', repo, 'config', 'user.email', 'evidence@example.invalid']);
  writeFileSync(join(repo, 'runner.mjs'), 'process.stdout.write("ok\\n");\n', 'utf8');
  execFileSync('/usr/bin/git', ['-C', repo, 'add', 'runner.mjs']);
  execFileSync('/usr/bin/git', ['-C', repo, 'commit', '-q', '-m', 'fixture']);
  return {
    root,
    repo,
    archive,
    cleanup: () => rmSync(root, { recursive: true, force: true }),
  };
}

function receiptMetadata() {
  return {
    invocation_id: 'quality-contract-001',
    attempt_index: 0,
    producer: { id: 'ci.current-model-quality', source_path: 'runner.mjs' },
    started_at: '2026-08-28T00:00:00.000Z',
    finished_at: '2026-08-28T00:00:03.000Z',
    environment: { level: 'unit' },
    execution: { status: 'passed', exit_code: 0 },
    bindings: [{
      role: 'catalog',
      id: 'fixture-catalog',
      version: '1',
      sha256: 'a'.repeat(64),
    }],
    claim_boundary: 'Static contract validation passed for the fixture only.',
    open_gates: ['physical device and product quality remain open'],
  };
}

function exploratoryPlan(trialId = 'sampling-policy-trial-001') {
  return {
    trial_id: trialId,
    classification: 'exploratory_run',
    question: 'Does the candidate preserve the same bounded event semantics?',
    claim_boundary: 'Exploratory fixture robustness only; no natural-scene accuracy claim.',
    parent_ref: 'git:fixture',
    statistical_unit: 'monitoring_episode',
    baseline: { id: 'baseline', policy: 'package-default' },
    candidate: { id: 'candidate', policy: 'bounded-candidate' },
    changed_variables: ['sampling policy'],
    held_stable_factors: ['fixture', 'catalog', 'runtime family'],
    metrics: ['event semantic agreement'],
    expected_effect: 'Candidate retains baseline event semantics.',
    falsifier: 'Any paired fixture produces a different event state.',
    run_budget: 2,
    design_context: {
      pairing_key: 'fixture-id',
      block: 'single-host-window',
      assignment_method: 'baseline-then-candidate exploratory order',
      ignored_runtime_inputs: [],
    },
    bindings: [{
      role: 'policy',
      id: 'sampling-policy',
      version: 'candidate-1',
      sha256: 'b'.repeat(64),
    }],
  };
}

function exploratoryResult(trialId = 'sampling-policy-trial-001') {
  return {
    trial_id: trialId,
    first_attempt_started_at: '2026-08-28T00:10:00.000Z',
    finished_at: '2026-08-28T00:20:00.000Z',
    attempts: [
      {
        run_id: 'baseline-run-1',
        attempt_index: 0,
        actual_order: 1,
        started_at: '2026-08-28T00:10:00.000Z',
        finished_at: '2026-08-28T00:11:00.000Z',
        status: 'passed',
        pairing_key: 'fixture-1',
        block: 'window-1',
        variant: 'baseline',
      },
      {
        run_id: 'candidate-run-1',
        attempt_index: 0,
        actual_order: 2,
        started_at: '2026-08-28T00:12:00.000Z',
        finished_at: '2026-08-28T00:13:00.000Z',
        status: 'passed',
        pairing_key: 'fixture-1',
        block: 'window-1',
        variant: 'candidate',
      },
    ],
    assignment_method: 'baseline-then-candidate exploratory order',
    task_valid: true,
    invalid_reason: null,
    scoring_status: 'scored',
    metrics: { paired_semantic_agreement: 1 },
    failures: [],
    anomalies: [],
    decision: 'Retain as an exploratory candidate.',
    open_gates: ['repeat on a physical camera route'],
  };
}

test('freezes and independently verifies one engineering receipt', () => {
  const fx = fixture();
  try {
    const metadataPath = join(fx.repo, 'receipt-input.json');
    const resultPath = join(fx.repo, 'result.json');
    writeJson(metadataPath, receiptMetadata());
    writeJson(resultPath, { ok: true, status: 'internal_evaluation_only' });
    const frozen = freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:05:00.000Z',
    });
    assert.equal(frozen.status, 'frozen');
    assert.equal(verifyRecord({ recordPath: frozen.record_path, archiveRoot: fx.archive }).status, 'verified');
    assert.throws(() => freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:05:00.000Z',
    }), /EEXIST|exist/iu);
  } finally {
    fx.cleanup();
  }
});

test('recovers a missing verification receipt without rewriting the immutable record', () => {
  const fx = fixture();
  try {
    const metadataPath = join(fx.repo, 'receipt-input.json');
    const resultPath = join(fx.repo, 'result.json');
    writeJson(metadataPath, { ...receiptMetadata(), invocation_id: 'recovery-contract-001' });
    writeJson(resultPath, { ok: true });
    const frozen = freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:05:00.000Z',
    });
    const before = readFileSync(frozen.record_path);
    rmSync(frozen.verification_path);
    const recovered = recoverRecord({
      recordPath: frozen.record_path,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:06:00.000Z',
    });
    assert.equal(recovered.status, 'recovered');
    assert.deepEqual(readFileSync(frozen.record_path), before);
    assert.equal(verifyRecord({
      recordPath: frozen.record_path,
      archiveRoot: fx.archive,
    }).status, 'verified');
  } finally {
    fx.cleanup();
  }
});

test('rejects credential-like result content before freezing', () => {
  const fx = fixture();
  try {
    const metadataPath = join(fx.repo, 'receipt-input.json');
    const resultPath = join(fx.repo, 'result.json');
    writeJson(metadataPath, receiptMetadata());
    writeJson(resultPath, { access_token: 'not-stored' });
    assert.throws(() => freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
    }), /sensitive key rejected/iu);
  } finally {
    fx.cleanup();
  }
});

test('rejects ambiguous or normalized execution timestamps', () => {
  const fx = fixture();
  try {
    const metadataPath = join(fx.repo, 'receipt-input.json');
    const resultPath = join(fx.repo, 'result.json');
    writeJson(resultPath, { ok: true });
    writeJson(metadataPath, { ...receiptMetadata(), started_at: '0' });
    assert.throws(() => freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      dryRun: true,
    }), /UTC ISO timestamp/iu);
    writeJson(metadataPath, { ...receiptMetadata(), started_at: '2026-02-30T00:00:00.000Z' });
    assert.throws(() => freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      dryRun: true,
    }), /real UTC calendar timestamp/iu);
  } finally {
    fx.cleanup();
  }
});

test('rejects camelCase credentials and opaque base64 result blobs', () => {
  const fx = fixture();
  try {
    const metadataPath = join(fx.repo, 'receipt-input.json');
    const resultPath = join(fx.repo, 'result.json');
    writeJson(metadataPath, receiptMetadata());

    writeJson(resultPath, { accessToken: 'not-stored' });
    assert.throws(() => freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      dryRun: true,
    }), /sensitive key rejected/iu);

    writeJson(resultPath, { payload: 'A'.repeat(2048) });
    assert.throws(() => freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      dryRun: true,
    }), /base64 blob rejected/iu);

    writeJson(resultPath, { payload: `data:image/png;base64,${'A'.repeat(2048)}` });
    assert.throws(() => freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      dryRun: true,
    }), /base64 data URI rejected/iu);

    writeJson(resultPath, { payload: 'A-_'.repeat(683) });
    assert.throws(() => freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      dryRun: true,
    }), /base64 blob rejected/iu);

    writeJson(resultPath, { payload: Array.from({ length: 8 }, () => 'A'.repeat(1000)) });
    assert.throws(() => freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      dryRun: true,
    }), /base64 blob rejected/iu);

    writeJson(resultPath, { payload: `data:application/octet-stream;base64,${'A'.repeat(512)}` });
    assert.throws(() => freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      dryRun: true,
    }), /base64 data URI rejected/iu);
  } finally {
    fx.cleanup();
  }
});

test('source snapshot changes for staged and untracked content changes', () => {
  const fx = fixture();
  try {
    const runnerPath = join(fx.repo, 'runner.mjs');
    writeFileSync(runnerPath, 'process.stdout.write("staged-one\\n");\n', 'utf8');
    execFileSync('/usr/bin/git', ['-C', fx.repo, 'add', 'runner.mjs']);
    const stagedOne = sourceSnapshot(fx.repo);

    writeFileSync(runnerPath, 'process.stdout.write("staged-two\\n");\n', 'utf8');
    execFileSync('/usr/bin/git', ['-C', fx.repo, 'add', 'runner.mjs']);
    const stagedTwo = sourceSnapshot(fx.repo);
    assert.notEqual(stagedOne.tracked_diff_sha256, stagedTwo.tracked_diff_sha256);

    const candidatePath = join(fx.repo, 'candidate.txt');
    writeFileSync(candidatePath, 'candidate-one\n', 'utf8');
    const untrackedOne = sourceSnapshot(fx.repo);
    writeFileSync(candidatePath, 'candidate-two\n', 'utf8');
    const untrackedTwo = sourceSnapshot(fx.repo);
    assert.notEqual(untrackedOne.untracked_content_sha256, untrackedTwo.untracked_content_sha256);
    assert.equal(untrackedOne.untracked_paths_sha256, untrackedTwo.untracked_paths_sha256);

    writeFileSync(join(fx.repo, '.gitignore'), 'ignored-input.bin\n', 'utf8');
    execFileSync('/usr/bin/git', ['-C', fx.repo, 'add', '.gitignore']);
    execFileSync('/usr/bin/git', ['-C', fx.repo, 'commit', '-q', '-m', 'ignore fixture input']);
    writeFileSync(join(fx.repo, 'ignored-input.bin'), 'ignored-one\n', 'utf8');
    const ignoredOne = sourceSnapshot(fx.repo);
    writeFileSync(join(fx.repo, 'ignored-input.bin'), 'ignored-two-longer\n', 'utf8');
    const ignoredTwo = sourceSnapshot(fx.repo);
    assert.equal(ignoredOne.ignored_content_included, false);
    assert.equal(ignoredOne.ignored_input_policy, 'bind_relevant_ignored_inputs_explicitly');
    assert.equal(ignoredOne.ignored_entry_count, 1);
    assert.notEqual(ignoredOne.ignored_metadata_sha256, ignoredTwo.ignored_metadata_sha256);
  } finally {
    fx.cleanup();
  }
});

test('rejects archive writes through a symlinked parent directory', () => {
  const fx = fixture();
  try {
    const metadataPath = join(fx.repo, 'receipt-input.json');
    const resultPath = join(fx.repo, 'result.json');
    const outside = join(fx.root, 'outside');
    mkdirSync(fx.archive, { recursive: true });
    mkdirSync(outside, { recursive: true });
    symlinkSync(outside, join(fx.archive, 'executions'), 'dir');
    writeJson(metadataPath, receiptMetadata());
    writeJson(resultPath, { ok: true });

    assert.throws(() => freezeReceipt({
      metadataPath,
      resultPath,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
    }), /symlink/iu);
    assert.deepEqual(readdirSync(outside), []);
  } finally {
    fx.cleanup();
  }
});

test('freezes a plan before attempts and builds a verified exploratory capsule', () => {
  const fx = fixture();
  try {
    const planInput = join(fx.repo, 'plan-input.json');
    const resultInput = join(fx.root, 'experiment-result.json');
    writeJson(planInput, exploratoryPlan());
    writeJson(resultInput, exploratoryResult());
    const plan = freezePlan({
      inputPath: planInput,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:00:00.000Z',
    });
    const capsule = freezeCapsule({
      planPath: plan.plan_path,
      resultPath: resultInput,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:25:00.000Z',
    });
    assert.equal(capsule.status, 'frozen');
    assert.equal(verifyRecord({ recordPath: capsule.capsule_path, archiveRoot: fx.archive }).status, 'verified');

    const manifest = JSON.parse(readFileSync(capsule.capsule_path, 'utf8'));
    const frozenResult = join(fx.archive, manifest.result.ref);
    writeFileSync(frozenResult, '{}\n', 'utf8');
    assert.equal(verifyRecord({ recordPath: capsule.capsule_path, archiveRoot: fx.archive }).status, 'failed');
  } finally {
    fx.cleanup();
  }
});

test('rejects a result whose first attempt predates the frozen plan', () => {
  const fx = fixture();
  try {
    const planInput = join(fx.repo, 'plan-input.json');
    const resultInput = join(fx.repo, 'experiment-result.json');
    writeJson(planInput, exploratoryPlan('late-plan-trial'));
    const result = exploratoryResult('late-plan-trial');
    result.attempts[0].started_at = '2026-08-28T00:04:00.000Z';
    result.attempts[0].finished_at = '2026-08-28T00:04:30.000Z';
    writeJson(resultInput, result);
    const plan = freezePlan({
      inputPath: planInput,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:05:00.000Z',
    });
    assert.throws(() => freezeCapsule({
      planPath: plan.plan_path,
      resultPath: resultInput,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:25:00.000Z',
    }), /attempts\[0\] predates the frozen plan/iu);
  } finally {
    fx.cleanup();
  }
});

test('confirmatory plans fail closed when prespecification fields are missing', () => {
  const fx = fixture();
  try {
    const input = join(fx.repo, 'confirmatory-plan.json');
    writeJson(input, { ...exploratoryPlan('confirmatory-trial'), classification: 'confirmatory_run' });
    assert.throws(() => freezePlan({
      inputPath: input,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      dryRun: true,
    }), /estimand must be/iu);

    writeJson(input, {
      ...exploratoryPlan('confirmatory-placeholders'),
      classification: 'confirmatory_run',
      estimand: 'paired agreement difference',
      data_boundary: 'frozen fixture split',
      access_state: 'unseen before freeze',
      thresholds: {},
      exclusions: [],
      repeats: 0,
      aggregation: 'episode mean',
      uncertainty_method: 'paired bootstrap',
      assignment_method: 'baseline-then-candidate exploratory order',
      stop_rule: 'frozen run budget exhausted',
      analysis_plan: 'paired comparison by episode',
    });
    assert.throws(() => freezePlan({
      inputPath: input,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      dryRun: true,
    }), /non-empty thresholds/iu);

    const complete = {
      ...exploratoryPlan('confirmatory-complete'),
      classification: 'confirmatory_run',
      estimand: 'paired agreement difference',
      data_boundary: 'frozen fixture split',
      access_state: 'unseen before freeze',
      thresholds: { paired_agreement_minimum: 0.95 },
      exclusions: ['task_invalid before scoring'],
      repeats: 2,
      aggregation: 'episode-level paired mean',
      uncertainty_method: 'paired bootstrap with fixed seed',
      assignment_method: 'baseline-then-candidate exploratory order',
      stop_rule: 'stop after the frozen two-attempt budget',
      analysis_plan: 'score paired episodes, then compare against the frozen threshold',
    };
    writeJson(input, complete);
    assert.equal(freezePlan({
      inputPath: input,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      dryRun: true,
    }).status, 'dry_run');
  } finally {
    fx.cleanup();
  }
});

test('rehashes each declared ignored runtime input at plan and capsule boundaries', () => {
  const fx = fixture();
  try {
    writeFileSync(join(fx.repo, '.gitignore'), 'runtime-policy.bin\n', 'utf8');
    execFileSync('/usr/bin/git', ['-C', fx.repo, 'add', '.gitignore']);
    execFileSync('/usr/bin/git', ['-C', fx.repo, 'commit', '-q', '-m', 'ignore runtime policy']);
    const runtimePath = join(fx.repo, 'runtime-policy.bin');
    writeFileSync(runtimePath, 'policy-one\n', 'utf8');
    const planInput = join(fx.repo, 'plan-input.json');
    const resultInput = join(fx.repo, 'experiment-result.json');
    const planValue = exploratoryPlan('ignored-binding-trial');
    planValue.design_context.ignored_runtime_inputs = ['runtime-policy.bin'];
    planValue.bindings = [{
      role: 'policy',
      id: 'runtime-policy',
      version: '1',
      sha256: createHash('sha256').update(readFileSync(runtimePath)).digest('hex'),
      source_path: 'runtime-policy.bin',
    }];
    writeJson(planInput, planValue);
    writeJson(resultInput, exploratoryResult('ignored-binding-trial'));
    const plan = freezePlan({
      inputPath: planInput,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:00:00.000Z',
    });
    writeFileSync(runtimePath, 'policy-two\n', 'utf8');
    assert.throws(() => freezeCapsule({
      planPath: plan.plan_path,
      resultPath: resultInput,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:25:00.000Z',
    }), /binding source hash mismatch/iu);
  } finally {
    fx.cleanup();
  }
});

test('valid scored capsules require paired frozen variants and assignment method', () => {
  const fx = fixture();
  try {
    const planInput = join(fx.repo, 'plan-input.json');
    const resultInput = join(fx.root, 'experiment-result.json');
    writeJson(planInput, exploratoryPlan('unpaired-trial'));
    const plan = freezePlan({
      inputPath: planInput,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:00:00.000Z',
    });
    const unpaired = exploratoryResult('unpaired-trial');
    unpaired.attempts = [unpaired.attempts[0]];
    writeJson(resultInput, unpaired);
    assert.throws(() => freezeCapsule({
      planPath: plan.plan_path,
      resultPath: resultInput,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:25:00.000Z',
    }), /complete baseline\/candidate pairs/iu);

    const wrongAssignment = exploratoryResult('unpaired-trial');
    wrongAssignment.assignment_method = 'candidate-first';
    writeJson(resultInput, wrongAssignment);
    assert.throws(() => freezeCapsule({
      planPath: plan.plan_path,
      resultPath: resultInput,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:25:00.000Z',
    }), /assignment_method does not match/iu);

    const invalid = exploratoryResult('unpaired-trial');
    invalid.attempts = [invalid.attempts[0]];
    invalid.task_valid = false;
    invalid.invalid_reason = 'candidate attempt did not start';
    invalid.scoring_status = 'invalid';
    invalid.metrics = {};
    invalid.decision = 'Retain the incomplete pair as failure evidence only.';
    writeJson(resultInput, invalid);
    const capsule = freezeCapsule({
      planPath: plan.plan_path,
      resultPath: resultInput,
      projectRoot: fx.repo,
      archiveRoot: fx.archive,
      ledgerPath: null,
      now: '2026-08-28T00:25:00.000Z',
    });
    assert.equal(verifyRecord({
      recordPath: capsule.capsule_path,
      archiveRoot: fx.archive,
    }).status, 'verified');
  } finally {
    fx.cleanup();
  }
});
