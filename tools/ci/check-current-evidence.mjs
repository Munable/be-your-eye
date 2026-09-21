#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const CURRENT_EVIDENCE_FILES = [
  '01-foundation.json',
  '02-reference.json',
  '03-reading.json',
  '04-cloud.json',
  '05-release.json',
];

const STALE_OPEN_BINDING = 'stale_open';
const GATE_KEYS = new Set([
  'blockers',
  'closed',
  'currentgate',
  'currentgates',
  'gates',
  'open',
  'qualitygates',
  'releasegates',
]);

function normalizedKey(value) {
  return value.toLowerCase().replaceAll(/[^a-z0-9]/gu, '');
}

function addIssue(issues, code, path, message) {
  issues.push({ code, path, message });
}

function inspectCurrentGates(value, path, issues, gateContext = false) {
  if (Array.isArray(value)) {
    value.forEach((entry, index) => inspectCurrentGates(entry, `${path}/${index}`, issues, gateContext));
    return;
  }
  if (value && typeof value === 'object') {
    for (const [key, entry] of Object.entries(value)) {
      const normalized = normalizedKey(key);
      const entryPath = `${path}/${key}`;
      if (normalized === 'retainedbaseline') {
        addIssue(
          issues,
          'retained_baseline_forbidden',
          entryPath,
          'current evidence must not retain a historical baseline alongside the current candidate',
        );
        continue;
      }
      if (normalized === 'modelrouter') {
        addIssue(
          issues,
          'model_router_current_gate_forbidden',
          entryPath,
          'a removed model router must not remain a current evidence gate',
        );
        continue;
      }
      inspectCurrentGates(entry, entryPath, issues, gateContext || GATE_KEYS.has(normalized));
    }
    return;
  }
  if (gateContext && typeof value === 'string' && /model[\s_-]*router/iu.test(value)) {
    addIssue(
      issues,
      'removed_model_router_current_gate_forbidden',
      path,
      'the removed model-router architecture must not remain a current evidence gate',
    );
  }
}

function evidenceIdentity(document) {
  const source = document?.source_snapshot;
  const buildScope = document?.build_scope;
  const apk = document?.build_scope?.apk ?? document?.current_apk ?? document?.current_candidate;
  return {
    implementation: source?.implementation_commit,
    catalog: {
      version: source?.catalog_version,
      sha256: source?.catalog_sha256,
      url: source?.catalog_url
        ?? document?.build_scope?.apk?.catalog_url_at_build
        ?? document?.current_candidate?.catalog_url_at_build,
    },
    apk: {
      path: apk?.path ?? apk?.apk_path,
      sha256: apk?.sha256 ?? apk?.apk_sha256,
      versionName: buildScope?.version_name ?? apk?.version_name,
      versionCode: buildScope?.version_code ?? apk?.version_code,
    },
  };
}

function identityValueComplete(value) {
  if (typeof value === 'string') return value.length > 0;
  if (typeof value === 'number') return Number.isInteger(value);
  return value && typeof value === 'object' && Object.values(value).every(identityValueComplete);
}

function checkIdentity(identities, key, issues) {
  const observed = [];
  for (const [file, identity] of Object.entries(identities)) {
    const value = identity[key];
    if (!identityValueComplete(value)) {
      addIssue(issues, `${key}_identity_missing`, file, `${file} is missing its current ${key} identity`);
      continue;
    }
    observed.push(JSON.stringify(value));
  }
  if (new Set(observed).size > 1) {
    addIssue(
      issues,
      `${key}_identity_mismatch`,
      '$',
      `all five current evidence streams must bind the same ${key} identity`,
    );
  }
}

function inspectImplementationLifecycle(recordedCommit, headCommit, workspaceRoot) {
  if (!workspaceRoot) {
    return { accepted: false, reason: 'repository context is unavailable' };
  }
  if (typeof recordedCommit !== 'string' || recordedCommit.length === 0) {
    return { accepted: false, reason: 'the recorded implementation commit is missing' };
  }

  const gitOutput = (args) => execFileSync('git', args, {
    cwd: workspaceRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore'],
  }).trim();

  let resolvedRecorded;
  let resolvedHead;
  try {
    resolvedRecorded = gitOutput(['rev-parse', '--verify', '--end-of-options', `${recordedCommit}^{commit}`]);
    resolvedHead = gitOutput(['rev-parse', '--verify', '--end-of-options', `${headCommit}^{commit}`]);
  } catch {
    return { accepted: false, reason: 'the recorded implementation commit cannot be resolved' };
  }
  if (resolvedRecorded === resolvedHead) return { accepted: true };

  try {
    execFileSync('git', ['merge-base', '--is-ancestor', resolvedRecorded, resolvedHead], {
      cwd: workspaceRoot,
      stdio: 'ignore',
    });
  } catch {
    return { accepted: false, reason: 'the recorded implementation commit is not an ancestor of HEAD' };
  }

  let changedPaths;
  try {
    changedPaths = gitOutput([
      'log',
      '--format=',
      '--name-only',
      `${resolvedRecorded}..${resolvedHead}`,
      '--',
    ]).split('\n').map((path) => path.trim()).filter(Boolean);
  } catch {
    return { accepted: false, reason: 'the implementation-to-HEAD path set cannot be read' };
  }
  const implementationPath = changedPaths.find((path) => !path.startsWith('evidence/current/'));
  if (implementationPath) {
    return {
      accepted: false,
      reason: `the implementation-to-HEAD range changes ${implementationPath}`,
    };
  }
  return { accepted: true };
}

function checkWorkspaceIdentity(identities, workspaceIdentity, issues, workspaceRoot) {
  if (!workspaceIdentity) return;
  const implementationLifecycle = new Map();
  const checks = [
    ['implementation', 'implementation', workspaceIdentity.implementation, (identity) => identity.implementation],
    ['catalog', 'version', workspaceIdentity.catalog?.version, (identity) => identity.catalog.version],
    ['catalog', 'url', workspaceIdentity.catalog?.url, (identity) => identity.catalog.url],
    ['apk', 'path', workspaceIdentity.apk?.path, (identity) => identity.apk.path],
    ['apk', 'sha256', workspaceIdentity.apk?.sha256, (identity) => identity.apk.sha256],
    ['apk', 'versionName', workspaceIdentity.apk?.versionName, (identity) => identity.apk.versionName],
    ['apk', 'versionCode', workspaceIdentity.apk?.versionCode, (identity) => identity.apk.versionCode],
  ];
  for (const [scope, field, expected, readActual] of checks) {
    if (expected === undefined) continue;
    for (const [file, identity] of Object.entries(identities)) {
      const actual = readActual(identity);
      if (actual !== expected) {
        let lifecycleReason;
        if (scope === 'implementation') {
          if (!implementationLifecycle.has(actual)) {
            implementationLifecycle.set(
              actual,
              inspectImplementationLifecycle(actual, expected, workspaceRoot),
            );
          }
          const lifecycle = implementationLifecycle.get(actual);
          if (lifecycle.accepted) continue;
          lifecycleReason = `; ${lifecycle.reason}`;
        }
        addIssue(
          issues,
          `${scope}_workspace_drift`,
          `${file}/${scope}/${field}`,
          `${file} records ${JSON.stringify(actual)} but the workspace ${scope}.${field} is ${JSON.stringify(expected)}${lifecycleReason ?? ''}`,
        );
      }
    }
  }
}

function requiredMatch(contents, pattern, description) {
  const match = contents.match(pattern);
  if (!match) throw new Error(`cannot read ${description} from the current workspace`);
  return match[1];
}

export function loadWorkspaceIdentity(workspaceRoot = resolve(import.meta.dirname, '../..')) {
  const implementation = execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: workspaceRoot,
    encoding: 'utf8',
  }).trim();
  const catalogTemplate = JSON.parse(readFileSync(
    resolve(workspaceRoot, 'model-tools/v3/releases/current-internal/templates/catalog.template.json'),
    'utf8',
  ));
  const runLocal = readFileSync(resolve(workspaceRoot, 'tools/ci/run-local.sh'), 'utf8');
  const catalogUrl = requiredMatch(
    runLocal,
    /readonly CURRENT_CATALOG_URL="([^"]+)"/u,
    'current Catalog URL',
  );
  const androidBuild = readFileSync(resolve(workspaceRoot, 'android/app/build.gradle.kts'), 'utf8');
  const versionCode = Number(requiredMatch(
    androidBuild,
    /configuredVersionCode[^\n]*\?:\s*(\d+)/u,
    'Android version code',
  ));
  const baseVersionName = requiredMatch(
    androidBuild,
    /configuredVersionName[^\n]*\{\s*"([^"]+)"\s*\}/u,
    'Android version name',
  );
  const apkRelativePath = 'android/app/build/outputs/apk/internal/app-internal.apk';
  const apkPath = resolve(workspaceRoot, apkRelativePath);
  const apkIdentity = {
    path: apkRelativePath,
    versionName: `${baseVersionName}-internal`,
    versionCode,
  };
  if (existsSync(apkPath)) {
    apkIdentity.sha256 = createHash('sha256').update(readFileSync(apkPath)).digest('hex');
  }
  return {
    implementation,
    catalog: {
      version: catalogTemplate.catalog_version,
      url: catalogUrl,
    },
    apk: apkIdentity,
  };
}

export function loadCurrentEvidence(directory) {
  const documents = {};
  const issues = [];
  for (const file of CURRENT_EVIDENCE_FILES) {
    const path = resolve(directory, file);
    if (!existsSync(path)) {
      addIssue(issues, 'evidence_file_missing', file, `missing current evidence stream: ${file}`);
      continue;
    }
    try {
      const document = JSON.parse(readFileSync(path, 'utf8'));
      if (!document || Array.isArray(document) || typeof document !== 'object') {
        addIssue(issues, 'evidence_document_invalid', file, `${file} must contain one JSON object`);
      } else {
        documents[file] = document;
      }
    } catch (error) {
      addIssue(issues, 'evidence_json_invalid', file, `${file} is not valid JSON: ${error.message}`);
    }
  }
  return { documents, issues };
}

export function validateCurrentEvidence(
  documents,
  initialIssues = [],
  workspaceIdentity = null,
  workspaceRoot = null,
) {
  const issues = [...initialIssues];
  for (const file of CURRENT_EVIDENCE_FILES) {
    if (!documents[file] && !issues.some((issue) => issue.path === file)) {
      addIssue(issues, 'evidence_document_missing', file, `missing parsed current evidence stream: ${file}`);
    }
  }

  const availableDocuments = Object.fromEntries(
    CURRENT_EVIDENCE_FILES.filter((file) => documents[file]).map((file) => [file, documents[file]]),
  );
  const bindings = Object.values(availableDocuments).map((document) => document.candidate_binding_status);
  const hasBinding = bindings.some((binding) => binding !== undefined);
  const unifiedStaleOpen = bindings.length === CURRENT_EVIDENCE_FILES.length
    && bindings.every((binding) => binding === STALE_OPEN_BINDING);
  if (hasBinding && !unifiedStaleOpen) {
    addIssue(
      issues,
      'candidate_binding_status_incomplete',
      '$',
      `candidate_binding_status must be omitted or equal '${STALE_OPEN_BINDING}' in all five streams`,
    );
  }

  const identities = Object.fromEntries(
    Object.entries(availableDocuments).map(([file, document]) => [file, evidenceIdentity(document)]),
  );
  if (!unifiedStaleOpen) {
    for (const key of ['implementation', 'catalog', 'apk']) checkIdentity(identities, key, issues);
    checkWorkspaceIdentity(identities, workspaceIdentity, issues, workspaceRoot);
  }
  for (const [file, document] of Object.entries(availableDocuments)) {
    inspectCurrentGates(document, file, issues);
  }

  const release = documents['05-release.json'];
  const releaseQualityReady = release?.release_quality_ready;
  const internalAcceptancePassed = release?.internal_real_scene_acceptance_passed;
  if (typeof releaseQualityReady !== 'boolean') {
    addIssue(
      issues,
      'release_quality_flag_missing',
      '05-release.json/release_quality_ready',
      'release_quality_ready must be an explicit boolean',
    );
  }
  if (typeof internalAcceptancePassed !== 'boolean') {
    addIssue(
      issues,
      'internal_acceptance_flag_missing',
      '05-release.json/internal_real_scene_acceptance_passed',
      'internal_real_scene_acceptance_passed must be an explicit boolean',
    );
  }
  if (releaseQualityReady === true && internalAcceptancePassed !== true) {
    addIssue(
      issues,
      'release_flags_inconsistent',
      '05-release.json',
      'release quality cannot be ready while internal real-scene acceptance is not passed',
    );
  }
  if (unifiedStaleOpen && (releaseQualityReady !== false || internalAcceptancePassed !== false)) {
    addIssue(
      issues,
      'stale_open_release_flags_inconsistent',
      '05-release.json',
      'stale/open evidence must keep release_quality_ready and internal_real_scene_acceptance_passed false',
    );
  }

  const releaseReady = !unifiedStaleOpen
    && releaseQualityReady === true
    && internalAcceptancePassed === true;
  const ok = issues.length === 0;
  return {
    schema_version: 'be-your-eye-current-evidence-validation-v1',
    ok,
    status: !ok
      ? 'current_evidence_invalid'
      : unifiedStaleOpen
        ? 'unified_stale_open'
        : releaseReady
          ? 'current_evidence_release_ready'
          : 'current_evidence_open',
    identity_mode: unifiedStaleOpen ? STALE_OPEN_BINDING : 'current_candidate',
    release_flags: {
      release_quality_ready: releaseQualityReady ?? null,
      internal_real_scene_acceptance_passed: internalAcceptancePassed ?? null,
      release_ready: releaseReady,
    },
    workspace_identity: workspaceIdentity,
    identities,
    issues,
  };
}

function parseDirectoryArgument(argv) {
  if (argv.length === 0) return resolve(import.meta.dirname, '../../evidence/current');
  if (argv.length === 2 && argv[0] === '--directory') return resolve(argv[1]);
  throw new Error('usage: check-current-evidence.mjs [--directory DIRECTORY]');
}

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  try {
    const workspaceRoot = resolve(import.meta.dirname, '../..');
    const directory = parseDirectoryArgument(process.argv.slice(2));
    const loaded = loadCurrentEvidence(directory);
    const result = validateCurrentEvidence(
      loaded.documents,
      loaded.issues,
      loadWorkspaceIdentity(workspaceRoot),
      workspaceRoot,
    );
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    if (!result.ok) process.exitCode = 2;
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 2;
  }
}
