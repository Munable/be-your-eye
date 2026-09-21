import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';

import {
  CURRENT_EVIDENCE_FILES,
  loadCurrentEvidence,
  loadWorkspaceIdentity,
  validateCurrentEvidence,
} from './check-current-evidence.mjs';

function document(identity) {
  return {
    status: 'current_candidate_internal_open',
    source_snapshot: {
      implementation_commit: identity.implementation,
      catalog_version: identity.catalog_version,
      catalog_sha256: identity.catalog_sha256,
      catalog_url: identity.catalog_url,
    },
    current_apk: {
      path: identity.apk_path,
      sha256: identity.apk_sha256,
      version_name: identity.version_name,
      version_code: identity.version_code,
    },
    open: ['real-scene acceptance'],
  };
}

function coherentDocuments() {
  const identity = {
    implementation: '0123456789abcdef0123456789abcdef01234567',
    catalog_version: '2026.08.18.2',
    catalog_sha256: 'a'.repeat(64),
    catalog_url: 'https://catalog.example/2026.08.18.2/catalog.json',
    apk_path: 'android/app/build/outputs/apk/internal/app-internal.apk',
    apk_sha256: 'b'.repeat(64),
    version_name: '0.2.21-dev-internal',
    version_code: 22,
  };
  const documents = Object.fromEntries(
    CURRENT_EVIDENCE_FILES.map((file) => [file, document(identity)]),
  );
  documents['05-release.json'].release_quality_ready = false;
  documents['05-release.json'].internal_real_scene_acceptance_passed = false;
  return documents;
}

function coherentWorkspaceIdentity() {
  return {
    implementation: '0123456789abcdef0123456789abcdef01234567',
    catalog: {
      version: '2026.08.18.2',
      url: 'https://catalog.example/2026.08.18.2/catalog.json',
    },
    apk: {
      path: 'android/app/build/outputs/apk/internal/app-internal.apk',
      sha256: 'b'.repeat(64),
      versionName: '0.2.21-dev-internal',
      versionCode: 22,
    },
  };
}

function git(directory, ...args) {
  return execFileSync('git', args, {
    cwd: directory,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

async function createGitRepository(t) {
  const directory = await mkdtemp(join(tmpdir(), 'be-your-eye-evidence-git-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  git(directory, 'init', '--quiet');
  git(directory, 'config', 'user.name', 'Evidence Test');
  git(directory, 'config', 'user.email', 'evidence@example.test');
  return directory;
}

async function commitFile(directory, path, contents, message) {
  const absolutePath = join(directory, path);
  await mkdir(dirname(absolutePath), { recursive: true });
  await writeFile(absolutePath, contents);
  git(directory, 'add', '--', path);
  git(directory, 'commit', '--quiet', '-m', message);
  return git(directory, 'rev-parse', 'HEAD');
}

function bindImplementation(documents, implementation) {
  for (const documentValue of Object.values(documents)) {
    documentValue.source_snapshot.implementation_commit = implementation;
  }
}

test('coherent open evidence stays valid without reporting release readiness', () => {
  const result = validateCurrentEvidence(coherentDocuments(), [], coherentWorkspaceIdentity());
  assert.equal(result.ok, true);
  assert.equal(result.status, 'current_evidence_open');
  assert.deepEqual(result.release_flags, {
    release_quality_ready: false,
    internal_real_scene_acceptance_passed: false,
    release_ready: false,
  });
});

test('workspace identity drift fails unless all five evidence streams are stale/open', () => {
  const documents = coherentDocuments();
  const workspaceIdentity = coherentWorkspaceIdentity();
  workspaceIdentity.implementation = 'f'.repeat(40);
  workspaceIdentity.catalog.version = '2026.08.24.1';
  workspaceIdentity.catalog.url = 'https://catalog.example/2026.08.24.1/catalog.json';
  workspaceIdentity.apk.sha256 = 'c'.repeat(64);
  workspaceIdentity.apk.versionName = '0.2.21-dev-internal';
  workspaceIdentity.apk.versionCode = 22;

  const drift = validateCurrentEvidence(documents, [], workspaceIdentity);
  assert.equal(drift.ok, false);
  assert.deepEqual(
    [...new Set(drift.issues.map((issue) => issue.code))].sort(),
    ['apk_workspace_drift', 'catalog_workspace_drift', 'implementation_workspace_drift'],
  );

  for (const documentValue of Object.values(documents)) {
    documentValue.candidate_binding_status = 'stale_open';
  }
  const stale = validateCurrentEvidence(documents, [], workspaceIdentity);
  assert.equal(stale.ok, true);
  assert.equal(stale.status, 'unified_stale_open');
});

test('an evidence-only descendant may follow the recorded implementation commit', async (t) => {
  const repository = await createGitRepository(t);
  const implementation = await commitFile(repository, 'src/product.txt', 'ready\n', 'implementation');
  const head = await commitFile(
    repository,
    'evidence/current/01-foundation.json',
    '{}\n',
    'record current evidence',
  );
  const documents = coherentDocuments();
  bindImplementation(documents, implementation);
  const workspaceIdentity = coherentWorkspaceIdentity();
  workspaceIdentity.implementation = head;

  const result = validateCurrentEvidence(documents, [], workspaceIdentity, repository);
  assert.equal(result.ok, true);
  assert.equal(result.status, 'current_evidence_open');
  assert.equal(result.issues.some((issue) => issue.code === 'implementation_workspace_drift'), false);

  workspaceIdentity.catalog.version = '2026.08.24.1';
  const otherDrift = validateCurrentEvidence(documents, [], workspaceIdentity, repository);
  assert.equal(otherDrift.ok, false);
  assert.ok(otherDrift.issues.some((issue) => issue.code === 'catalog_workspace_drift'));
});

test('implementation lifecycle rejects unresolvable, non-ancestor, and source-changing commits', async (t) => {
  await t.test('unresolvable commit', async (subtest) => {
    const repository = await createGitRepository(subtest);
    const head = await commitFile(repository, 'src/product.txt', 'ready\n', 'implementation');
    const documents = coherentDocuments();
    bindImplementation(documents, 'f'.repeat(40));
    const workspaceIdentity = coherentWorkspaceIdentity();
    workspaceIdentity.implementation = head;

    const result = validateCurrentEvidence(documents, [], workspaceIdentity, repository);
    assert.equal(result.ok, false);
    assert.match(
      result.issues.find((issue) => issue.code === 'implementation_workspace_drift').message,
      /cannot be resolved/u,
    );
  });

  await t.test('non-ancestor commit', async (subtest) => {
    const repository = await createGitRepository(subtest);
    const base = await commitFile(repository, 'src/product.txt', 'base\n', 'base');
    git(repository, 'switch', '--quiet', '-c', 'recorded');
    const recorded = await commitFile(repository, 'src/product.txt', 'recorded\n', 'recorded');
    git(repository, 'switch', '--quiet', '-c', 'current', base);
    const head = await commitFile(repository, 'evidence/current/01-foundation.json', '{}\n', 'evidence');
    const documents = coherentDocuments();
    bindImplementation(documents, recorded);
    const workspaceIdentity = coherentWorkspaceIdentity();
    workspaceIdentity.implementation = head;

    const result = validateCurrentEvidence(documents, [], workspaceIdentity, repository);
    assert.equal(result.ok, false);
    assert.match(
      result.issues.find((issue) => issue.code === 'implementation_workspace_drift').message,
      /not an ancestor/u,
    );
  });

  await t.test('source-changing descendant', async (subtest) => {
    const repository = await createGitRepository(subtest);
    const implementation = await commitFile(repository, 'src/product.txt', 'ready\n', 'implementation');
    const head = await commitFile(repository, 'src/product.txt', 'changed\n', 'later implementation');
    const documents = coherentDocuments();
    bindImplementation(documents, implementation);
    const workspaceIdentity = coherentWorkspaceIdentity();
    workspaceIdentity.implementation = head;

    const result = validateCurrentEvidence(documents, [], workspaceIdentity, repository);
    assert.equal(result.ok, false);
    assert.match(
      result.issues.find((issue) => issue.code === 'implementation_workspace_drift').message,
      /changes src\/product\.txt/u,
    );
  });
});

test('workspace identity loader reads the live source-of-truth fields', () => {
  const identity = loadWorkspaceIdentity();
  assert.match(identity.implementation, /^[0-9a-f]{40}$/u);
  assert.match(identity.catalog.version, /^\d{4}\.\d{2}\.\d{2}\.\d+$/u);
  assert.match(identity.catalog.url, /^https:\/\//u);
  assert.equal(identity.apk.path, 'android/app/build/outputs/apk/internal/app-internal.apk');
  assert.match(identity.apk.versionName, /-internal$/u);
  assert.ok(Number.isInteger(identity.apk.versionCode));
  if (identity.apk.sha256 !== undefined) assert.match(identity.apk.sha256, /^[0-9a-f]{64}$/u);
});

test('candidate identity drift fails unless every stream is explicitly stale/open', () => {
  const documents = coherentDocuments();
  documents['03-reading.json'].source_snapshot.implementation_commit = 'fedcba9876543210';
  documents['03-reading.json'].source_snapshot.catalog_sha256 = 'c'.repeat(64);
  documents['03-reading.json'].current_apk.sha256 = 'd'.repeat(64);

  const drift = validateCurrentEvidence(documents);
  assert.equal(drift.ok, false);
  assert.deepEqual(
    drift.issues.filter((issue) => issue.code.endsWith('_identity_mismatch')).map((issue) => issue.code).sort(),
    ['apk_identity_mismatch', 'catalog_identity_mismatch', 'implementation_identity_mismatch'],
  );

  for (const documentValue of Object.values(documents)) {
    documentValue.candidate_binding_status = 'stale_open';
  }
  const stale = validateCurrentEvidence(documents);
  assert.equal(stale.ok, true);
  assert.equal(stale.status, 'unified_stale_open');
  assert.equal(stale.release_flags.release_ready, false);
});

test('unified stale/open evidence cannot claim either release gate', () => {
  const documents = coherentDocuments();
  for (const documentValue of Object.values(documents)) {
    documentValue.candidate_binding_status = 'stale_open';
  }
  documents['05-release.json'].release_quality_ready = true;
  documents['05-release.json'].internal_real_scene_acceptance_passed = true;

  const result = validateCurrentEvidence(documents);
  assert.equal(result.ok, false);
  assert.equal(result.status, 'current_evidence_invalid');
  assert.equal(result.release_flags.release_ready, false);
  assert.ok(result.issues.some((issue) => issue.code === 'stale_open_release_flags_inconsistent'));
});

test('the current assistant may be a gate while historical baselines and the removed model router are rejected', () => {
  const documents = coherentDocuments();
  documents['02-reference.json'].retained_baseline = {};
  documents['03-reading.json'].open.push('DeepSeek Agent configuration assistant');
  documents['04-cloud.json'].result_stream = { model_router: 'OPEN' };

  const result = validateCurrentEvidence(documents);
  assert.equal(result.ok, false);
  assert.deepEqual(
    result.issues.map((issue) => issue.code).sort(),
    [
      'model_router_current_gate_forbidden',
      'retained_baseline_forbidden',
    ],
  );
});

test('model-router wording in a current gate string is still rejected without rejecting assistant work', () => {
  const documents = coherentDocuments();
  documents['02-reference.json'].open.push('multi-turn Agent proposal UI');
  documents['04-cloud.json'].open.push('legacy model-router rollout');

  const result = validateCurrentEvidence(documents);
  assert.equal(result.ok, false);
  assert.deepEqual(
    result.issues.map((issue) => issue.code),
    ['removed_model_router_current_gate_forbidden'],
  );
});

test('loader reports invalid JSON as an evidence issue', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'be-your-eye-current-evidence-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const documents = coherentDocuments();
  await Promise.all(CURRENT_EVIDENCE_FILES.map((file) => writeFile(
    join(directory, file),
    file === '03-reading.json' ? '{' : JSON.stringify(documents[file]),
  )));

  const loaded = loadCurrentEvidence(directory);
  const result = validateCurrentEvidence(loaded.documents, loaded.issues);
  assert.equal(result.ok, false);
  assert.ok(result.issues.some((issue) => issue.code === 'evidence_json_invalid' && issue.path === '03-reading.json'));
});
