#!/usr/bin/env node
import { createHash, randomUUID } from 'node:crypto';
import {
  constants as fsConstants,
  existsSync,
  lstatSync,
  linkSync,
  mkdirSync,
  openSync,
  readFileSync,
  realpathSync,
  closeSync,
  fsyncSync,
  writeFileSync,
  unlinkSync,
} from 'node:fs';
import { homedir } from 'node:os';
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const SCRIPT_PATH = fileURLToPath(import.meta.url);
export const PROJECT_ROOT = resolve(dirname(SCRIPT_PATH), '../..');
export const ARCHIVE_ROOT = '/Volumes/DevDisk/DeveloperData/ResearchLedger/be-your-eyes';
export const LEDGER_PATH = resolve(
  homedir(),
  'Library/Application Support/ResearchLedger/bin/ledger.py',
);

const PROJECT_ID = '3bb5c833-e0b4-419d-a546-eae53ad00d25';
const PROJECT_SLUG = 'be-your-eyes';
const MAX_JSON_BYTES = 5 * 1024 * 1024;
const SHA256 = /^[0-9a-f]{64}$/u;
const SAFE_ID = /^[a-z0-9][a-z0-9._-]{0,95}$/u;
const UTC_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/u;
const ALLOWED_EXECUTION_STATUS = new Set([
  'passed', 'failed', 'timed_out', 'cancelled', 'invalid',
]);
const ALLOWED_LEVEL = new Set(['unit', 'emulator', 'physical_device', 'hosted', 'human']);
const SENSITIVE_KEY = /(^|_)(secret|token|password|authorization|cookie|api_?key|access_?key|private_?key|device_?serial|email|account|user_?id)($|_)/iu;
const FORBIDDEN_CONTENT_KEY = /(^|_)(stdout|stderr|command|command_line|shell_command|log|logs|reasoning|chain_of_thought|transcript|screenshot|recording|raw_frame|raw_image|raw_audio|raw_video|image_base64|audio_base64|video_base64|aac|wav|photo|photos|frame|frames|image|images|audio|video)($|_)/iu;
const SENSITIVE_VALUE = /(bearer\s+[a-z0-9._~+\/-]+=*|\bsk-[a-z0-9_-]{12,}|eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\.)/iu;
const BASE64_BLOB = /^[a-zA-Z0-9+/_\-\r\n]+={0,2}$/u;
const BASE64_DATA_URI = /^data:[^,\r\n]*;base64,/iu;
const FORBIDDEN_UNTRACKED = /(^|\/)(\.env(?:\.|$)|[^/]+\.(?:aac|wav|mp3|m4a|png|jpe?g|webp|gif|mp4|mov))$/iu;

function canonicalValue(value) {
  if (Array.isArray(value)) return value.map(canonicalValue);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value).sort().map((key) => [key, canonicalValue(value[key])]),
    );
  }
  return value;
}

export function canonicalJson(value) {
  return `${JSON.stringify(canonicalValue(value))}\n`;
}

function sha256Bytes(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function sha256File(path) {
  return sha256Bytes(readFileSync(path));
}

function utcNow() {
  return new Date().toISOString();
}

function requireObject(value, label) {
  if (value === null || Array.isArray(value) || typeof value !== 'object') {
    throw new Error(`${label} must be an object`);
  }
  return value;
}

function requireString(value, label, max = 2000) {
  if (typeof value !== 'string' || value.trim().length === 0 || value.length > max) {
    throw new Error(`${label} must be a non-empty string no longer than ${max}`);
  }
  return value;
}

function requireSafeId(value, label) {
  const id = requireString(value, label, 96);
  if (!SAFE_ID.test(id)) throw new Error(`${label} is not a safe identifier`);
  return id;
}

function requireInteger(value, label, minimum = 0) {
  if (!Number.isSafeInteger(value) || value < minimum) {
    throw new Error(`${label} must be an integer >= ${minimum}`);
  }
  return value;
}

function parseTimestamp(value, label) {
  requireString(value, label, 64);
  if (!UTC_TIMESTAMP.test(value)) throw new Error(`${label} must be a UTC ISO timestamp`);
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) throw new Error(`${label} must be an ISO timestamp`);
  const normalized = value.includes('.') ? value : value.replace(/Z$/u, '.000Z');
  if (new Date(parsed).toISOString() !== normalized) {
    throw new Error(`${label} must be a real UTC calendar timestamp`);
  }
  return parsed;
}

function requireStringArray(value, label, { nonEmpty = false } = {}) {
  if (!Array.isArray(value) || (nonEmpty && value.length === 0)
      || value.some((entry) => typeof entry !== 'string'
        || entry.trim().length === 0 || entry.length > 2000)) {
    throw new Error(`${label} must be ${nonEmpty ? 'a non-empty' : 'an'} array of bounded strings`);
  }
  return value;
}

function assertNoSensitiveContent(value, path = '$', state = { opaqueChars: 0 }) {
  if (Array.isArray(value)) {
    if (value.length > 4096) throw new Error(`oversized array rejected at ${path}`);
    if (value.length > 256 && value.every(
      (entry) => Number.isInteger(entry) && entry >= 0 && entry <= 255,
    )) {
      throw new Error(`binary byte array rejected at ${path}`);
    }
    value.forEach((entry, index) => assertNoSensitiveContent(entry, `${path}[${index}]`, state));
    return;
  }
  if (value !== null && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) {
      const normalizedKey = key
        .replace(/([a-z0-9])([A-Z])/gu, '$1_$2')
        .replace(/[-\s]+/gu, '_')
        .toLowerCase();
      if (SENSITIVE_KEY.test(normalizedKey)) {
        throw new Error(`sensitive key rejected at ${path}.${key}`);
      }
      if (FORBIDDEN_CONTENT_KEY.test(normalizedKey)) {
        throw new Error(`raw or private content key rejected at ${path}.${key}`);
      }
      assertNoSensitiveContent(child, `${path}.${key}`, state);
    }
    return;
  }
  if (typeof value === 'string') {
    if (SENSITIVE_VALUE.test(value)) throw new Error(`credential-like value rejected at ${path}`);
    if (BASE64_DATA_URI.test(value)) throw new Error(`embedded base64 data URI rejected at ${path}`);
    if (value.length > 128 * 1024) throw new Error(`oversized text rejected at ${path}`);
    if (value.length >= 128 && BASE64_BLOB.test(value)) {
      state.opaqueChars += value.length;
      if (value.length > 512 || state.opaqueChars > 1024) {
        throw new Error(`base64 blob rejected at ${path}`);
      }
    }
  }
}

function readJson(path, label = 'JSON') {
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink()) throw new Error(`${label} must be a regular file`);
  if (stat.size <= 0 || stat.size > MAX_JSON_BYTES) {
    throw new Error(`${label} must be between 1 and ${MAX_JSON_BYTES} bytes`);
  }
  const parsed = JSON.parse(readFileSync(path, 'utf8'));
  assertNoSensitiveContent(parsed);
  return requireObject(parsed, label);
}

function isWithin(path, parent) {
  const rel = relative(resolve(parent), resolve(path));
  return rel === '' || (!rel.startsWith(`..${sep}`) && rel !== '..' && !isAbsolute(rel));
}

function requireWithin(path, parent, label) {
  const resolved = resolve(path);
  if (!isWithin(resolved, parent)) throw new Error(`${label} escapes its allowed root`);
  if (existsSync(resolved)) {
    const realParent = realpathSync(parent);
    const realTarget = realpathSync(resolved);
    if (!isWithin(realTarget, realParent)) throw new Error(`${label} resolves outside its allowed root`);
  }
  return resolved;
}

function ensureSafeDirectory(path, allowedRoot) {
  mkdirSync(allowedRoot, { recursive: true, mode: 0o700 });
  const lexicalRoot = resolve(allowedRoot);
  const lexicalTarget = resolve(path);
  if (!isWithin(lexicalTarget, lexicalRoot)) throw new Error('write path escapes its allowed root');
  const realRoot = realpathSync(lexicalRoot);
  const rel = relative(lexicalRoot, lexicalTarget);
  let current = realRoot;
  for (const component of rel.split(sep).filter(Boolean)) {
    const next = resolve(current, component);
    if (existsSync(next)) {
      const stat = lstatSync(next);
      if (stat.isSymbolicLink() || !stat.isDirectory()) {
        throw new Error('write path contains a symlink or non-directory parent');
      }
    } else {
      mkdirSync(next, { mode: 0o700 });
    }
    current = realpathSync(next);
    if (!isWithin(current, realRoot)) throw new Error('write path resolves outside its allowed root');
  }
}

function writeExclusive(path, text, allowedRoot) {
  ensureSafeDirectory(dirname(path), allowedRoot);
  const tempPath = resolve(
    dirname(path),
    `.${basename(path)}.${process.pid}.${randomUUID()}.tmp`,
  );
  const fd = openSync(
    tempPath,
    fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL,
    0o600,
  );
  try {
    writeFileSync(fd, text, 'utf8');
    fsyncSync(fd);
  } finally {
    closeSync(fd);
  }
  try {
    linkSync(tempPath, path);
    const directoryFd = openSync(dirname(path), fsConstants.O_RDONLY);
    try {
      fsyncSync(directoryFd);
    } finally {
      closeSync(directoryFd);
    }
  } finally {
    try {
      unlinkSync(tempPath);
    } catch (error) {
      if (error?.code !== 'ENOENT') throw error;
    }
  }
}

function writeExclusiveOrMatch(path, text, allowedRoot) {
  try {
    writeExclusive(path, text, allowedRoot);
    return;
  } catch (error) {
    if (error?.code !== 'EEXIST') throw error;
    const safePath = requireWithin(path, allowedRoot, 'existing immutable file');
    const stat = lstatSync(safePath);
    const expected = Buffer.from(text, 'utf8');
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size !== expected.length
        || sha256File(safePath) !== sha256Bytes(expected)) {
      throw error;
    }
  }
}

function writeContentAddressedJson(value, archiveRoot) {
  const bytes = Buffer.from(canonicalJson(value), 'utf8');
  const sha256 = sha256Bytes(bytes);
  const target = resolve(archiveRoot, 'executions/artifacts/sha256', sha256.slice(0, 2), `${sha256}.json`);
  try {
    writeExclusive(target, bytes.toString('utf8'), archiveRoot);
  } catch (error) {
    if (error?.code !== 'EEXIST') throw error;
    const safeTarget = requireWithin(target, archiveRoot, 'content-addressed artifact');
    const stat = lstatSync(safeTarget);
    if (!stat.isFile() || stat.isSymbolicLink() || sha256File(safeTarget) !== sha256) throw error;
  }
  return {
    ref: relative(resolve(archiveRoot), target),
    sha256,
    size_bytes: bytes.length,
  };
}

function gitBytes(projectRoot, args) {
  return execFileSync('/usr/bin/git', ['-C', projectRoot, ...args], {
    encoding: null,
    maxBuffer: 64 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function gitText(projectRoot, args) {
  return gitBytes(projectRoot, args).toString('utf8').trim();
}

export function sourceSnapshot(projectRoot = PROJECT_ROOT) {
  const root = gitText(projectRoot, ['rev-parse', '--show-toplevel']);
  if (realpathSync(root) !== realpathSync(projectRoot)) throw new Error('project root mismatch');
  const dirty = gitBytes(projectRoot, ['status', '--porcelain=v1', '-z', '--untracked-files=all']);
  const trackedDiff = gitBytes(projectRoot, ['diff', 'HEAD', '--binary', '--no-ext-diff', '--']);
  const ignored = gitBytes(projectRoot, [
    'ls-files', '--others', '--ignored', '--exclude-standard', '-z',
  ]);
  const ignoredPaths = ignored.toString('utf8').split('\0').filter(Boolean).sort();
  const ignoredMetadata = [];
  let ignoredMetadataComplete = true;
  for (const path of ignoredPaths) {
    const absolute = resolve(projectRoot, path);
    if (!isWithin(absolute, projectRoot)) {
      ignoredMetadataComplete = false;
      continue;
    }
    try {
      const stat = lstatSync(absolute, { bigint: true });
      ignoredMetadata.push({
        path,
        kind: stat.isSymbolicLink() ? 'symlink' : stat.isDirectory() ? 'directory' : 'file',
        size_bytes: stat.size.toString(),
        mtime_ns: stat.mtimeNs.toString(),
      });
    } catch {
      ignoredMetadataComplete = false;
    }
  }
  const untracked = dirty.toString('utf8').split('\0')
    .filter((entry) => entry.startsWith('?? '))
    .map((entry) => entry.slice(3))
    .sort();
  let untrackedContentComplete = true;
  const untrackedRecords = [];
  for (const path of untracked) {
    const absolute = resolve(projectRoot, path);
    if (FORBIDDEN_UNTRACKED.test(path) || !isWithin(absolute, projectRoot)) {
      untrackedContentComplete = false;
      continue;
    }
    try {
      const stat = lstatSync(absolute);
      if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 16 * 1024 * 1024) {
        untrackedContentComplete = false;
        continue;
      }
      untrackedRecords.push({ path, sha256: sha256File(absolute), size_bytes: stat.size });
    } catch {
      untrackedContentComplete = false;
    }
  }
  return {
    scope: 'git_head_tracked_diff_and_nonignored_untracked_content',
    commit: gitText(projectRoot, ['rev-parse', 'HEAD']),
    branch: gitText(projectRoot, ['symbolic-ref', '--short', '-q', 'HEAD']) || null,
    worktree_clean: dirty.length === 0,
    dirty_count: dirty.toString('utf8').split('\0').filter(Boolean).length,
    dirty_status_sha256: sha256Bytes(dirty),
    tracked_diff_sha256: sha256Bytes(trackedDiff),
    untracked_paths_sha256: sha256Bytes(Buffer.from(canonicalJson(untracked), 'utf8')),
    untracked_content_sha256: sha256Bytes(Buffer.from(canonicalJson(untrackedRecords), 'utf8')),
    untracked_content_complete: untrackedContentComplete,
    untracked_file_count: untracked.length,
    ignored_content_included: false,
    ignored_input_policy: 'bind_relevant_ignored_inputs_explicitly',
    ignored_paths_sha256: sha256Bytes(ignored),
    ignored_metadata_sha256: sha256Bytes(
      Buffer.from(canonicalJson(ignoredMetadata), 'utf8'),
    ),
    ignored_metadata_complete: ignoredMetadataComplete,
    ignored_entry_count: ignoredPaths.length,
  };
}

function projectIdentity(projectRoot) {
  return { id: PROJECT_ID, slug: PROJECT_SLUG, root: realpathSync(projectRoot) };
}

function producer(metadata, projectRoot) {
  const input = requireObject(metadata.producer, '$.producer');
  const id = requireSafeId(input.id, '$.producer.id');
  const sourcePath = requireString(input.source_path, '$.producer.source_path', 500);
  const absolute = requireWithin(resolve(projectRoot, sourcePath), projectRoot, '$.producer.source_path');
  const stat = lstatSync(absolute);
  if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('producer source must be a regular file');
  return { id, source_path: relative(projectRoot, absolute), source_sha256: sha256File(absolute) };
}

function validateBindings(value, { nonEmpty = false } = {}) {
  if (value === undefined) {
    if (nonEmpty) throw new Error('bindings must be a non-empty array');
    return [];
  }
  if (!Array.isArray(value) || (nonEmpty && value.length === 0)) {
    throw new Error(`bindings must be ${nonEmpty ? 'a non-empty' : 'an'} array`);
  }
  return value.map((raw, index) => {
    const item = requireObject(raw, `bindings[${index}]`);
    const role = requireSafeId(item.role, `bindings[${index}].role`);
    const id = requireString(item.id, `bindings[${index}].id`, 300);
    const version = requireString(item.version, `bindings[${index}].version`, 300);
    const sha256 = requireString(item.sha256, `bindings[${index}].sha256`, 64);
    if (!SHA256.test(sha256)) throw new Error(`bindings[${index}].sha256 must be SHA-256`);
    const sourcePath = item.source_path === undefined
      ? null
      : requireString(item.source_path, `bindings[${index}].source_path`, 500);
    const sizeBytes = item.size_bytes === undefined
      ? null
      : requireInteger(item.size_bytes, `bindings[${index}].size_bytes`);
    return {
      role,
      id,
      version,
      sha256,
      ...(sourcePath === null ? {} : { source_path: sourcePath }),
      ...(sizeBytes === null ? {} : { size_bytes: sizeBytes }),
    };
  });
}

function verifyLocalBindings(bindings, projectRoot, ignoredRuntimeInputs = []) {
  const declaredIgnored = new Set(ignoredRuntimeInputs.map((raw, index) => {
    const path = requireString(raw, `design_context.ignored_runtime_inputs[${index}]`, 500);
    if (isAbsolute(path) || path.split('/').includes('..') || FORBIDDEN_UNTRACKED.test(path)) {
      throw new Error(`unsafe ignored runtime input path: ${path}`);
    }
    const check = spawnSync('/usr/bin/git', [
      '-C', projectRoot, 'check-ignore', '-q', '--', path,
    ]);
    if (check.status !== 0) throw new Error(`declared ignored runtime input is not ignored: ${path}`);
    return path;
  }));
  const boundPaths = new Set();
  const normalized = bindings.map((binding) => {
    if (binding.source_path === undefined) return binding;
    const rawPath = binding.source_path;
    if (isAbsolute(rawPath) || rawPath.split('/').includes('..') || FORBIDDEN_UNTRACKED.test(rawPath)) {
      throw new Error(`unsafe binding source_path: ${rawPath}`);
    }
    const path = requireWithin(resolve(projectRoot, rawPath), projectRoot, 'binding source_path');
    const stat = lstatSync(path);
    if (!stat.isFile() || stat.isSymbolicLink()) {
      throw new Error(`binding source_path must be a regular file: ${rawPath}`);
    }
    const relativePath = relative(projectRoot, path);
    const actualSha256 = sha256File(path);
    if (binding.sha256 !== actualSha256) {
      throw new Error(`binding source hash mismatch: ${relativePath}`);
    }
    if (binding.size_bytes !== undefined && binding.size_bytes !== stat.size) {
      throw new Error(`binding source size mismatch: ${relativePath}`);
    }
    boundPaths.add(relativePath);
    return { ...binding, source_path: relativePath, size_bytes: stat.size };
  });
  for (const path of declaredIgnored) {
    if (!boundPaths.has(path)) {
      throw new Error(`ignored runtime input lacks a verified source binding: ${path}`);
    }
  }
  return normalized;
}

function validateEnvironment(value) {
  const input = requireObject(value, 'environment');
  const level = requireString(input.level, 'environment.level', 32);
  if (!ALLOWED_LEVEL.has(level)) throw new Error('unsupported environment.level');
  const allowed = new Set(['level', 'device_model', 'android_api', 'host_region']);
  for (const key of Object.keys(input)) {
    if (!allowed.has(key)) throw new Error(`unsupported environment key: ${key}`);
  }
  return {
    level,
    ...(input.device_model === undefined ? {} : {
      device_model: requireString(input.device_model, 'environment.device_model', 100),
    }),
    ...(input.android_api === undefined ? {} : {
      android_api: requireInteger(input.android_api, 'environment.android_api', 1),
    }),
    ...(input.host_region === undefined ? {} : {
      host_region: requireString(input.host_region, 'environment.host_region', 100),
    }),
  };
}

function validateExecution(value) {
  const input = requireObject(value, 'execution');
  const status = requireString(input.status, 'execution.status', 32);
  if (!ALLOWED_EXECUTION_STATUS.has(status)) throw new Error('unsupported execution.status');
  const exitCode = input.exit_code;
  if (exitCode !== null && (!Number.isSafeInteger(exitCode) || exitCode < 0 || exitCode > 255)) {
    throw new Error('execution.exit_code must be null or an integer in 0..255');
  }
  if (status === 'passed' && exitCode !== 0) throw new Error('passed execution requires exit_code 0');
  return { status, exit_code: exitCode };
}

function datePart(timestamp) {
  return new Date(timestamp).toISOString().slice(0, 10);
}

function verificationPath(archiveRoot, recordId, createdAt) {
  return resolve(
    archiveRoot,
    'executions/verifications/v1',
    datePart(createdAt),
    `${recordId}.json`,
  );
}

function artifactFromRef(ref, archiveRoot) {
  const path = requireWithin(resolve(archiveRoot, ref.ref), archiveRoot, 'artifact.ref');
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('artifact ref must be a regular file');
  const actual = sha256File(path);
  return {
    ref: relative(resolve(archiveRoot), path),
    sha256: actual,
    size_bytes: stat.size,
    valid: actual === ref.sha256 && stat.size === ref.size_bytes,
  };
}

function validateStoredSource(value) {
  const source = requireObject(value, 'source');
  if (source.scope !== 'git_head_tracked_diff_and_nonignored_untracked_content') {
    throw new Error('source scope is unsupported');
  }
  if (!/^[0-9a-f]{40,64}$/u.test(requireString(source.commit, 'source.commit', 64))) {
    throw new Error('source.commit must be a Git object id');
  }
  if (source.branch !== null && typeof source.branch !== 'string') {
    throw new Error('source.branch must be a string or null');
  }
  if (typeof source.worktree_clean !== 'boolean'
      || typeof source.untracked_content_complete !== 'boolean'
      || typeof source.ignored_metadata_complete !== 'boolean'
      || source.ignored_content_included !== false
      || source.ignored_input_policy !== 'bind_relevant_ignored_inputs_explicitly') {
    throw new Error('source completeness contract is invalid');
  }
  for (const key of [
    'dirty_status_sha256', 'tracked_diff_sha256', 'untracked_paths_sha256',
    'untracked_content_sha256', 'ignored_paths_sha256', 'ignored_metadata_sha256',
  ]) {
    if (!SHA256.test(requireString(source[key], `source.${key}`, 64))) {
      throw new Error(`source.${key} must be SHA-256`);
    }
  }
  requireInteger(source.dirty_count, 'source.dirty_count');
  requireInteger(source.untracked_file_count, 'source.untracked_file_count');
  requireInteger(source.ignored_entry_count, 'source.ignored_entry_count');
  return source;
}

function validateStoredProducer(value) {
  const stored = requireObject(value, 'producer');
  requireSafeId(stored.id, 'producer.id');
  requireString(stored.source_path, 'producer.source_path', 500);
  if (!SHA256.test(requireString(stored.source_sha256, 'producer.source_sha256', 64))) {
    throw new Error('producer.source_sha256 must be SHA-256');
  }
}

function validateStoredRecord(record, archiveRoot) {
  requireObject(record.project, 'project');
  validateStoredSource(record.source);
  if (record.schema === 'be-your-eye.execution-receipt.v1') {
    const invocationId = requireSafeId(record.invocation_id, 'invocation_id');
    const attemptIndex = requireInteger(record.attempt_index, 'attempt_index');
    if (record.record_id !== `${invocationId}-a${attemptIndex}`
        || record.classification !== 'engineering_event') {
      throw new Error('engineering receipt identity or classification mismatch');
    }
    validateStoredProducer(record.producer);
    const started = parseTimestamp(record.started_at, 'started_at');
    const finished = parseTimestamp(record.finished_at, 'finished_at');
    const created = parseTimestamp(record.created_at, 'created_at');
    if (started > finished || finished > created) throw new Error('engineering receipt time order invalid');
    validateBindings(record.bindings);
    validateEnvironment(record.environment);
    validateExecution(record.execution);
    requireStringArray(record.observations, 'observations');
    requireString(record.claim_boundary, 'claim_boundary');
    requireStringArray(record.open_gates, 'open_gates');
    if (!record.source.untracked_content_complete) {
      throw new Error('engineering receipt source snapshot is incomplete');
    }
    return;
  }
  if (record.schema === 'be-your-eye.research-plan.v1') {
    const plan = validatePlanInput(record);
    if (record.record_id !== `${plan.trial_id}-plan`) throw new Error('research plan identity mismatch');
    parseTimestamp(record.frozen_at, 'frozen_at');
    if (!record.source.untracked_content_complete) {
      throw new Error('research plan source snapshot is incomplete');
    }
    return;
  }
  if (record.schema === 'be-your-eye.research-capsule.v1') {
    const trialId = requireSafeId(record.trial_id, 'trial_id');
    if (record.record_id !== `${trialId}-capsule`) throw new Error('capsule identity mismatch');
    parseTimestamp(record.created_at, 'created_at');
    requireString(record.claim_boundary, 'claim_boundary');
    if (!record.source.untracked_content_complete) {
      throw new Error('research capsule source snapshot is incomplete');
    }
    const planCheck = artifactFromRef(record.plan, archiveRoot);
    const resultCheck = artifactFromRef(record.result, archiveRoot);
    if (!planCheck.valid || !resultCheck.valid) throw new Error('capsule artifact mismatch');
    const plan = readJson(resolve(archiveRoot, record.plan.ref), 'capsule plan');
    const result = readJson(resolve(archiveRoot, record.result.ref), 'capsule result');
    if (plan.schema !== 'be-your-eye.research-plan.v1'
        || result.schema !== 'be-your-eye.research-result.v1') {
      throw new Error('capsule child schema mismatch');
    }
    validatePlanInput(plan);
    validateResultInput(result, plan);
    if (plan.trial_id !== trialId || result.trial_id !== trialId
        || record.classification !== plan.classification
        || record.claim_boundary !== plan.claim_boundary) {
      throw new Error('capsule lineage mismatch');
    }
    return;
  }
  throw new Error('unsupported execution record schema');
}

function writeVerification(
  recordPath,
  record,
  archiveRoot,
  checks,
  now = utcNow(),
  pathTimestamp = now,
) {
  validateStoredRecord(record, archiveRoot);
  const completeChecks = [{ role: 'record_contract', valid: true }, ...checks];
  const recordBytes = readFileSync(recordPath);
  const verification = {
    schema: 'be-your-eye.execution-verification.v1',
    record_id: record.record_id,
    record_schema: record.schema,
    record_ref: relative(resolve(archiveRoot), recordPath),
    record_sha256: sha256Bytes(recordBytes),
    verified_at: now,
    checks: completeChecks,
    valid: completeChecks.every((check) => check.valid === true),
  };
  if (!verification.valid) throw new Error('record verification failed');
  const path = verificationPath(archiveRoot, record.record_id, pathTimestamp);
  writeExclusive(path, canonicalJson(verification), archiveRoot);
  return { path, verification };
}

function registerRecord(recordPath, ledgerPath) {
  if (ledgerPath === null) return { status: 'skipped' };
  const result = spawnSync('/usr/bin/python3', [ledgerPath, 'record-execution', recordPath], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  if (result.status !== 0) {
    throw new Error(
      `Ledger registration failed after the immutable record was written at ${recordPath}. `
      + `Recover with: /usr/bin/python3 ${JSON.stringify(ledgerPath)} record-execution `
      + `${JSON.stringify(recordPath)}. Cause: ${(result.stderr || result.stdout).trim()}`,
    );
  }
  return JSON.parse(result.stdout);
}

export function freezeReceipt({
  metadataPath,
  resultPath,
  projectRoot = PROJECT_ROOT,
  archiveRoot = ARCHIVE_ROOT,
  ledgerPath = LEDGER_PATH,
  now = utcNow(),
  dryRun = false,
}) {
  const metadata = readJson(metadataPath, 'receipt metadata');
  const result = readJson(resultPath, 'receipt result');
  const invocationId = requireSafeId(metadata.invocation_id ?? randomUUID(), 'invocation_id');
  const attemptIndex = requireInteger(metadata.attempt_index ?? 0, 'attempt_index');
  const startedAt = requireString(metadata.started_at, 'started_at', 64);
  const finishedAt = requireString(metadata.finished_at, 'finished_at', 64);
  if (parseTimestamp(startedAt, 'started_at') > parseTimestamp(finishedAt, 'finished_at')) {
    throw new Error('started_at must not follow finished_at');
  }
  const recordId = `${invocationId}-a${attemptIndex}`;
  const validatedProducer = producer(metadata, projectRoot);
  const bindings = verifyLocalBindings(validateBindings(metadata.bindings), projectRoot);
  const environment = validateEnvironment(metadata.environment);
  const execution = validateExecution(metadata.execution);
  const claimBoundary = requireString(metadata.claim_boundary, 'claim_boundary', 2000);
  const observations = requireStringArray(metadata.observations ?? [], 'observations');
  const openGates = requireStringArray(metadata.open_gates ?? [], 'open_gates');
  const source = sourceSnapshot(projectRoot);
  if (!source.untracked_content_complete) {
    throw new Error('engineering receipt source snapshot has unhashed untracked content');
  }
  const resultArtifact = dryRun
    ? { ref: 'DRY_RUN_RESULT_ARTIFACT', sha256: sha256Bytes(Buffer.from(canonicalJson(result))), size_bytes: Buffer.byteLength(canonicalJson(result)) }
    : writeContentAddressedJson(result, archiveRoot);
  const record = {
    schema: 'be-your-eye.execution-receipt.v1',
    record_id: recordId,
    classification: 'engineering_event',
    project: projectIdentity(projectRoot),
    invocation_id: invocationId,
    attempt_index: attemptIndex,
    producer: validatedProducer,
    started_at: new Date(startedAt).toISOString(),
    finished_at: new Date(finishedAt).toISOString(),
    source,
    bindings,
    environment,
    execution,
    result_artifact: { role: 'result', ...resultArtifact },
    observations,
    claim_boundary: claimBoundary,
    open_gates: openGates,
    created_at: now,
  };
  if (parseTimestamp(record.created_at, 'created_at') < parseTimestamp(record.finished_at, 'finished_at')) {
    throw new Error('created_at must not predate finished_at');
  }
  if (dryRun) return { status: 'dry_run', record };
  const path = resolve(
    archiveRoot,
    'executions/receipts/v1',
    datePart(record.finished_at),
    `${recordId}.json`,
  );
  writeExclusive(path, canonicalJson(record), archiveRoot);
  const check = artifactFromRef(record.result_artifact, archiveRoot);
  const { path: verifyPath, verification } = writeVerification(
    path,
    record,
    archiveRoot,
    [{ role: 'result', ...check }],
    now,
  );
  const registration = registerRecord(path, ledgerPath);
  return {
    status: 'frozen',
    record_path: path,
    record_sha256: verification.record_sha256,
    verification_path: verifyPath,
    registration,
  };
}

function validatePlanInput(input, { projectRoot = null } = {}) {
  const classification = requireString(input.classification, 'classification', 32);
  if (!['exploratory_run', 'confirmatory_run'].includes(classification)) {
    throw new Error('classification must be exploratory_run or confirmatory_run');
  }
  const baseline = requireObject(input.baseline, 'baseline');
  const candidate = requireObject(input.candidate, 'candidate');
  const baselineId = requireString(baseline.id, 'baseline.id', 300);
  const candidateId = requireString(candidate.id, 'candidate.id', 300);
  if (baselineId === candidateId) throw new Error('baseline.id and candidate.id must differ');
  const designContext = requireObject(input.design_context, 'design_context');
  requireString(designContext.pairing_key, 'design_context.pairing_key', 300);
  requireString(designContext.block, 'design_context.block', 300);
  requireString(designContext.assignment_method, 'design_context.assignment_method', 1000);
  const ignoredRuntimeInputs = requireStringArray(
    designContext.ignored_runtime_inputs,
    'design_context.ignored_runtime_inputs',
  );
  const rawBindings = validateBindings(input.bindings, { nonEmpty: true });
  const bindings = projectRoot === null
    ? rawBindings
    : verifyLocalBindings(rawBindings, projectRoot, ignoredRuntimeInputs);
  const plan = {
    trial_id: requireSafeId(input.trial_id, 'trial_id'),
    classification,
    question: requireString(input.question, 'question'),
    claim_boundary: requireString(input.claim_boundary, 'claim_boundary'),
    parent_ref: requireString(input.parent_ref, 'parent_ref', 500),
    statistical_unit: requireString(input.statistical_unit, 'statistical_unit', 100),
    baseline,
    candidate,
    changed_variables: requireStringArray(input.changed_variables, 'changed_variables', { nonEmpty: true }),
    held_stable_factors: requireStringArray(input.held_stable_factors, 'held_stable_factors'),
    metrics: requireStringArray(input.metrics, 'metrics', { nonEmpty: true }),
    expected_effect: requireString(input.expected_effect, 'expected_effect'),
    falsifier: requireString(input.falsifier, 'falsifier'),
    run_budget: requireInteger(input.run_budget, 'run_budget', 1),
    design_context: designContext,
    bindings,
  };
  if (classification === 'confirmatory_run') {
    plan.estimand = requireString(input.estimand, 'estimand');
    plan.data_boundary = requireString(input.data_boundary, 'data_boundary');
    plan.access_state = requireString(input.access_state, 'access_state');
    plan.thresholds = requireObject(input.thresholds, 'thresholds');
    if (Object.keys(plan.thresholds).length === 0) {
      throw new Error('confirmatory plan requires non-empty thresholds');
    }
    for (const [name, threshold] of Object.entries(plan.thresholds)) {
      if (name.trim().length === 0 || typeof threshold !== 'number' || !Number.isFinite(threshold)) {
        throw new Error('confirmatory thresholds must map named metrics to finite numbers');
      }
    }
    plan.exclusions = requireStringArray(input.exclusions, 'exclusions', { nonEmpty: true });
    plan.repeats = requireInteger(input.repeats, 'repeats', 1);
    plan.aggregation = requireString(input.aggregation, 'aggregation');
    plan.uncertainty_method = requireString(input.uncertainty_method, 'uncertainty_method');
    plan.assignment_method = requireString(input.assignment_method, 'assignment_method');
    plan.stop_rule = requireString(input.stop_rule, 'stop_rule');
    plan.analysis_plan = requireString(input.analysis_plan, 'analysis_plan');
    if (plan.assignment_method !== designContext.assignment_method) {
      throw new Error('confirmatory assignment_method must match design_context.assignment_method');
    }
  }
  assertNoSensitiveContent(plan);
  return plan;
}

export function freezePlan({
  inputPath,
  projectRoot = PROJECT_ROOT,
  archiveRoot = ARCHIVE_ROOT,
  ledgerPath = LEDGER_PATH,
  now = utcNow(),
  dryRun = false,
}) {
  const input = readJson(inputPath, 'research plan');
  const core = validatePlanInput(input, { projectRoot });
  const source = sourceSnapshot(projectRoot);
  if (!source.untracked_content_complete) {
    throw new Error('research plan source snapshot has unhashed untracked content');
  }
  const record = {
    schema: 'be-your-eye.research-plan.v1',
    record_id: `${core.trial_id}-plan`,
    project: projectIdentity(projectRoot),
    ...core,
    source,
    frozen_at: now,
  };
  parseTimestamp(record.frozen_at, 'frozen_at');
  if (dryRun) return { status: 'dry_run', record };
  const path = resolve(
    archiveRoot,
    'executions/plans/v1',
    datePart(now),
    core.trial_id,
    'plan.json',
  );
  writeExclusive(path, canonicalJson(record), archiveRoot);
  const { path: verifyPath, verification } = writeVerification(path, record, archiveRoot, [], now);
  const registration = registerRecord(path, ledgerPath);
  return {
    status: 'frozen',
    plan_path: path,
    plan_sha256: verification.record_sha256,
    verification_path: verifyPath,
    registration,
  };
}

function validateResultInput(input, plan) {
  if (input.trial_id !== plan.trial_id) throw new Error('result trial_id does not match plan');
  const firstAttempt = requireString(input.first_attempt_started_at, 'first_attempt_started_at', 64);
  const finishedAt = requireString(input.finished_at, 'finished_at', 64);
  const frozenMillis = parseTimestamp(plan.frozen_at, 'plan.frozen_at');
  const firstAttemptMillis = parseTimestamp(firstAttempt, 'first_attempt_started_at');
  const finishedMillis = parseTimestamp(finishedAt, 'finished_at');
  if (frozenMillis > firstAttemptMillis) {
    throw new Error('plan was not frozen before first attempt');
  }
  if (firstAttemptMillis > finishedMillis) {
    throw new Error('first attempt must not follow result completion');
  }
  if (!Array.isArray(input.attempts) || input.attempts.length === 0
      || input.attempts.length > plan.run_budget) {
    throw new Error('attempts must be non-empty and within the frozen run budget');
  }
  const attempts = input.attempts.map((raw, index) => {
    const attempt = requireObject(raw, `attempts[${index}]`);
    const status = requireString(attempt.status, `attempts[${index}].status`, 32);
    if (!ALLOWED_EXECUTION_STATUS.has(status)) throw new Error(`invalid attempts[${index}].status`);
    const startedAt = requireString(attempt.started_at, `attempts[${index}].started_at`, 64);
    const endedAt = requireString(attempt.finished_at, `attempts[${index}].finished_at`, 64);
    const startedMillis = parseTimestamp(startedAt, 'attempt.started_at');
    const endedMillis = parseTimestamp(endedAt, 'attempt.finished_at');
    if (startedMillis < frozenMillis) {
      throw new Error(`attempts[${index}] predates the frozen plan`);
    }
    if (startedMillis > endedMillis) {
      throw new Error(`attempts[${index}] starts after it finishes`);
    }
    if (endedMillis > finishedMillis) {
      throw new Error(`attempts[${index}] finishes after the result window`);
    }
    const variant = requireString(attempt.variant, `attempts[${index}].variant`, 300);
    if (![plan.baseline.id, plan.candidate.id].includes(variant)) {
      throw new Error(`attempts[${index}].variant is not the frozen baseline or candidate`);
    }
    return {
      run_id: requireSafeId(attempt.run_id, `attempts[${index}].run_id`),
      attempt_index: requireInteger(attempt.attempt_index, `attempts[${index}].attempt_index`),
      actual_order: requireInteger(attempt.actual_order, `attempts[${index}].actual_order`, 1),
      started_at: new Date(startedAt).toISOString(),
      finished_at: new Date(endedAt).toISOString(),
      status,
      pairing_key: requireString(attempt.pairing_key, `attempts[${index}].pairing_key`, 300),
      block: requireString(attempt.block, `attempts[${index}].block`, 300),
      variant,
    };
  });
  const attemptStartMillis = attempts.map((attempt) => Date.parse(attempt.started_at));
  const attemptFinishMillis = attempts.map((attempt) => Date.parse(attempt.finished_at));
  if (Math.min(...attemptStartMillis) !== firstAttemptMillis) {
    throw new Error('first_attempt_started_at must equal the earliest actual attempt');
  }
  if (Math.max(...attemptFinishMillis) > finishedMillis) {
    throw new Error('finished_at must cover every actual attempt');
  }
  const actualOrders = attempts.map((attempt) => attempt.actual_order).sort((a, b) => a - b);
  if (actualOrders.some((order, index) => order !== index + 1)) {
    throw new Error('actual_order must be unique and contiguous from 1');
  }
  const attemptKeys = attempts.map((attempt) => `${attempt.run_id}\0${attempt.attempt_index}`);
  if (new Set(attemptKeys).size !== attemptKeys.length) {
    throw new Error('run_id and attempt_index pairs must be unique');
  }
  const chronologicalOrders = [...attempts]
    .sort((left, right) => Date.parse(left.started_at) - Date.parse(right.started_at))
    .map((attempt) => attempt.actual_order);
  if (chronologicalOrders.some((order, index) => order !== index + 1)) {
    throw new Error('actual_order must match chronological attempt starts');
  }
  const taskValid = input.task_valid;
  if (typeof taskValid !== 'boolean') throw new Error('task_valid must be boolean');
  const scoringStatus = requireString(input.scoring_status, 'scoring_status', 64);
  if (!['scored', 'partially_scored', 'not_scored', 'invalid'].includes(scoringStatus)) {
    throw new Error('unsupported scoring_status');
  }
  const metrics = requireObject(input.metrics, 'metrics');
  const pairGroups = new Map();
  for (const attempt of attempts) {
    const key = `${attempt.pairing_key}\0${attempt.block}`;
    const counts = pairGroups.get(key) ?? { baseline: 0, candidate: 0 };
    if (attempt.variant === plan.baseline.id) counts.baseline += 1;
    if (attempt.variant === plan.candidate.id) counts.candidate += 1;
    pairGroups.set(key, counts);
  }
  const pairingComplete = [...pairGroups.values()].every(
    (counts) => counts.baseline > 0 && counts.baseline === counts.candidate,
  );
  if (taskValid && !pairingComplete) {
    throw new Error('task_valid results require complete baseline/candidate pairs in every block');
  }
  if (!taskValid && !['not_scored', 'invalid'].includes(scoringStatus)) {
    throw new Error('invalid tasks must not be scored');
  }
  if (taskValid && scoringStatus === 'invalid') {
    throw new Error('valid tasks cannot have invalid scoring_status');
  }
  if (!taskValid && Object.keys(metrics).length !== 0) {
    throw new Error('invalid tasks must not contain metrics');
  }
  if (['scored', 'partially_scored'].includes(scoringStatus) && Object.keys(metrics).length === 0) {
    throw new Error('scored results require metrics');
  }
  if (scoringStatus === 'not_scored' && Object.keys(metrics).length !== 0) {
    throw new Error('not_scored results must not contain metrics');
  }
  const frozenAssignmentMethod = plan.classification === 'confirmatory_run'
    ? plan.assignment_method
    : plan.design_context.assignment_method;
  const assignmentMethod = requireString(input.assignment_method, 'assignment_method', 1000);
  if (assignmentMethod !== frozenAssignmentMethod) {
    throw new Error('assignment_method does not match the frozen plan');
  }
  let invalidReason = null;
  if (taskValid) {
    if (input.invalid_reason !== null && input.invalid_reason !== undefined) {
      throw new Error('valid tasks must not have invalid_reason');
    }
  } else {
    invalidReason = requireString(input.invalid_reason, 'invalid_reason');
  }
  return {
    schema: 'be-your-eye.research-result.v1',
    trial_id: plan.trial_id,
    first_attempt_started_at: new Date(firstAttempt).toISOString(),
    finished_at: new Date(finishedAt).toISOString(),
    attempts,
    assignment_method: assignmentMethod,
    task_valid: taskValid,
    invalid_reason: invalidReason,
    scoring_status: scoringStatus,
    metrics,
    failures: requireStringArray(input.failures ?? [], 'failures'),
    anomalies: requireStringArray(input.anomalies ?? [], 'anomalies'),
    decision: requireString(input.decision, 'decision'),
    open_gates: requireStringArray(input.open_gates ?? [], 'open_gates'),
  };
}

function readVerifiedPlan(planPath, archiveRoot) {
  const absolute = requireWithin(planPath, resolve(archiveRoot, 'executions/plans/v1'), 'plan path');
  const plan = readJson(absolute, 'frozen plan');
  if (plan.schema !== 'be-your-eye.research-plan.v1') throw new Error('unsupported plan schema');
  const verification = verificationPath(archiveRoot, plan.record_id, plan.frozen_at);
  const receipt = readJson(verification, 'plan verification');
  if (receipt.schema !== 'be-your-eye.execution-verification.v1'
      || receipt.record_id !== plan.record_id
      || receipt.record_schema !== plan.schema
      || receipt.record_ref !== relative(resolve(archiveRoot), absolute)
      || receipt.record_sha256 !== sha256File(absolute)
      || receipt.valid !== true
      || !Array.isArray(receipt.checks)
      || receipt.checks.some((check) => check?.valid !== true)) {
    throw new Error('plan verification receipt is missing or invalid');
  }
  return { absolute, plan };
}

export function freezeCapsule({
  planPath,
  resultPath,
  projectRoot = PROJECT_ROOT,
  archiveRoot = ARCHIVE_ROOT,
  ledgerPath = LEDGER_PATH,
  now = utcNow(),
  dryRun = false,
}) {
  const { absolute: frozenPlanPath, plan } = readVerifiedPlan(planPath, archiveRoot);
  verifyLocalBindings(
    validateBindings(plan.bindings, { nonEmpty: true }),
    projectRoot,
    plan.design_context.ignored_runtime_inputs,
  );
  const input = readJson(resultPath, 'research result');
  const result = validateResultInput(input, plan);
  assertNoSensitiveContent(result);
  const resultBytes = Buffer.from(canonicalJson(result), 'utf8');
  const resultSha256 = sha256Bytes(resultBytes);
  const source = sourceSnapshot(projectRoot);
  if (!source.untracked_content_complete) {
    throw new Error('research capsule source snapshot has unhashed untracked content');
  }
  for (const key of [
    'commit', 'tracked_diff_sha256', 'untracked_paths_sha256',
    'untracked_content_sha256', 'untracked_content_complete',
  ]) {
    if (source[key] !== plan.source[key]) {
      throw new Error(`research source changed after plan freeze: ${key}`);
    }
  }
  const capsuleDir = resolve(
    archiveRoot,
    'executions/capsules/v1',
    datePart(result.finished_at),
    plan.trial_id,
  );
  const frozenResultPath = resolve(capsuleDir, 'result.json');
  const manifestPath = resolve(capsuleDir, 'manifest.json');
  const manifest = {
    schema: 'be-your-eye.research-capsule.v1',
    record_id: `${plan.trial_id}-capsule`,
    project: projectIdentity(projectRoot),
    trial_id: plan.trial_id,
    classification: plan.classification,
    plan: {
      ref: relative(resolve(archiveRoot), frozenPlanPath),
      sha256: sha256File(frozenPlanPath),
      size_bytes: lstatSync(frozenPlanPath).size,
    },
    result: {
      ref: relative(resolve(archiveRoot), frozenResultPath),
      sha256: resultSha256,
      size_bytes: resultBytes.length,
    },
    source,
    claim_boundary: plan.claim_boundary,
    created_at: now,
  };
  if (parseTimestamp(manifest.created_at, 'created_at') < parseTimestamp(result.finished_at, 'result.finished_at')) {
    throw new Error('capsule created_at must not predate result completion');
  }
  if (dryRun) return { status: 'dry_run', manifest, result };
  ensureSafeDirectory(capsuleDir, archiveRoot);
  writeExclusiveOrMatch(frozenResultPath, resultBytes.toString('utf8'), archiveRoot);
  writeExclusive(manifestPath, canonicalJson(manifest), archiveRoot);
  const planCheck = artifactFromRef(manifest.plan, archiveRoot);
  const resultCheck = artifactFromRef(manifest.result, archiveRoot);
  const temporalCheck = {
    role: 'plan_precedes_first_attempt',
    valid: parseTimestamp(plan.frozen_at, 'plan.frozen_at')
      <= parseTimestamp(result.first_attempt_started_at, 'result.first_attempt_started_at'),
  };
  const { path: verifyPath, verification } = writeVerification(
    manifestPath,
    manifest,
    archiveRoot,
    [planCheck, resultCheck, temporalCheck],
    now,
  );
  const registration = registerRecord(manifestPath, ledgerPath);
  return {
    status: 'frozen',
    capsule_path: manifestPath,
    capsule_sha256: verification.record_sha256,
    verification_path: verifyPath,
    registration,
  };
}

function checksForRecord(path, record, archiveRoot) {
  const checks = [];
  try {
    validateStoredRecord(record, archiveRoot);
    checks.push({ role: 'record_contract', valid: true });
  } catch {
    checks.push({ role: 'record_contract', valid: false });
  }
  if (record.schema === 'be-your-eye.execution-receipt.v1') {
    checks.push(artifactFromRef(record.result_artifact, archiveRoot));
  } else if (record.schema === 'be-your-eye.research-plan.v1') {
    checks.push({ role: 'plan_contract', valid: true });
  } else if (record.schema === 'be-your-eye.research-capsule.v1') {
    const planCheck = artifactFromRef(record.plan, archiveRoot);
    const resultCheck = artifactFromRef(record.result, archiveRoot);
    let temporalValid = false;
    if (planCheck.valid && resultCheck.valid) {
      const plan = readJson(resolve(archiveRoot, record.plan.ref), 'capsule plan');
      const result = readJson(resolve(archiveRoot, record.result.ref), 'capsule result');
      temporalValid = parseTimestamp(plan.frozen_at, 'plan.frozen_at')
        <= parseTimestamp(result.first_attempt_started_at, 'result.first_attempt_started_at');
    }
    checks.push(planCheck, resultCheck, {
      role: 'plan_precedes_first_attempt',
      valid: temporalValid,
    });
  } else {
    throw new Error('unsupported execution record schema');
  }
  return checks;
}

export function verifyRecord({ recordPath, archiveRoot = ARCHIVE_ROOT }) {
  const path = requireWithin(recordPath, resolve(archiveRoot, 'executions'), 'record path');
  const record = readJson(path, 'execution record');
  const checks = checksForRecord(path, record, archiveRoot);
  const receiptPath = verificationPath(
    archiveRoot,
    record.record_id,
    record.created_at ?? record.frozen_at,
  );
  const receipt = readJson(receiptPath, 'verification receipt');
  const currentHash = sha256File(path);
  const receiptValid = receipt.schema === 'be-your-eye.execution-verification.v1'
    && receipt.record_id === record.record_id
    && receipt.record_schema === record.schema
    && receipt.record_ref === relative(resolve(archiveRoot), path)
    && receipt.record_sha256 === currentHash
    && receipt.valid === true
    && Array.isArray(receipt.checks)
    && receipt.checks.every((check) => check?.valid === true);
  return {
    status: receiptValid && checks.every((check) => check.valid === true) ? 'verified' : 'failed',
    record_path: path,
    record_sha256: currentHash,
    checks,
  };
}

export function recoverRecord({
  recordPath,
  archiveRoot = ARCHIVE_ROOT,
  ledgerPath = LEDGER_PATH,
  now = utcNow(),
}) {
  const path = requireWithin(recordPath, resolve(archiveRoot, 'executions'), 'record path');
  const record = readJson(path, 'execution record');
  const checks = checksForRecord(path, record, archiveRoot);
  if (checks.some((check) => check.valid !== true)) {
    throw new Error('immutable record or artifact verification failed during recovery');
  }
  const recordTimestamp = record.created_at ?? record.frozen_at;
  parseTimestamp(recordTimestamp, 'record timestamp');
  const receiptPath = verificationPath(archiveRoot, record.record_id, recordTimestamp);
  let verification;
  if (existsSync(receiptPath)) {
    const verified = verifyRecord({ recordPath: path, archiveRoot });
    if (verified.status !== 'verified') throw new Error('existing verification receipt is invalid');
    verification = readJson(receiptPath, 'verification receipt');
  } else {
    ({ verification } = writeVerification(
      path,
      record,
      archiveRoot,
      checks,
      now,
      recordTimestamp,
    ));
  }
  const registration = registerRecord(path, ledgerPath);
  return {
    status: 'recovered',
    record_path: path,
    record_sha256: verification.record_sha256,
    verification_path: receiptPath,
    registration,
  };
}

function options(argv) {
  const values = { dryRun: false };
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (token === '--dry-run') {
      values.dryRun = true;
      continue;
    }
    if (!token.startsWith('--') || index + 1 >= argv.length) throw new Error(`invalid option: ${token}`);
    values[token.slice(2)] = argv[index + 1];
    index += 1;
  }
  return values;
}

function requireOption(value, name) {
  return requireString(value, `--${name}`, 1000);
}

function runCli(argv) {
  const [command, ...rest] = argv;
  const parsed = options(rest);
  if (command === 'freeze-receipt') {
    return freezeReceipt({
      metadataPath: requireOption(parsed.input, 'input'),
      resultPath: requireOption(parsed.result, 'result'),
      dryRun: parsed.dryRun,
    });
  }
  if (command === 'freeze-plan') {
    return freezePlan({ inputPath: requireOption(parsed.input, 'input'), dryRun: parsed.dryRun });
  }
  if (command === 'freeze-capsule') {
    return freezeCapsule({
      planPath: requireOption(parsed.plan, 'plan'),
      resultPath: requireOption(parsed.result, 'result'),
      dryRun: parsed.dryRun,
    });
  }
  if (command === 'verify') {
    return verifyRecord({ recordPath: requireOption(parsed.record, 'record') });
  }
  if (command === 'recover-record') {
    return recoverRecord({ recordPath: requireOption(parsed.record, 'record') });
  }
  throw new Error(
    'usage: freeze-execution.mjs freeze-receipt|freeze-plan|freeze-capsule|verify|recover-record [options]',
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(SCRIPT_PATH)) {
  try {
    const result = runCli(process.argv.slice(2));
    process.stdout.write(canonicalJson(result));
  } catch (error) {
    process.stderr.write(canonicalJson({ status: 'error', error: String(error?.message ?? error) }));
    process.exitCode = 1;
  }
}
