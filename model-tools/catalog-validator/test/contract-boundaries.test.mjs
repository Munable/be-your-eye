import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import {
  validateCatalog,
  validateEvent,
  validateManifest,
  validateTaskConfig
} from '../src/index.mjs';

const root = path.resolve(import.meta.dirname, '../../..');
const placeholderSignature = Object.freeze({
  canonicalization: 'RFC8785',
  algorithm: 'Ed25519',
  signing_key_id: 'model-key-2026-a',
  value: 'AA=='
});

async function readJson(relativePath) {
  return JSON.parse(await fs.readFile(path.join(root, relativePath), 'utf8'));
}

test('structured reading TaskConfig requires its continuous confirmation duration', async () => {
  const [task, catalog] = await Promise.all([
    readJson('test-vectors/valid/task-config-reading.json'),
    readJson('test-vectors/valid/capability-catalog.json')
  ]);

  assert.deepEqual(validateTaskConfig(task, catalog).errors, []);
  const missing = structuredClone(task);
  delete missing.rule.duration_ms;
  assert.ok(validateTaskConfig(missing, catalog).errors.some((error) => error.code === 'required'));
  const unsupported = structuredClone(task);
  unsupported.rule.duration_ms = 2000;
  assert.ok(validateTaskConfig(unsupported, catalog).errors.some((error) => error.code === 'enum'));
});

test('visual duration events stay distinct from open and close episodes', async () => {
  const event = await readJson('test-vectors/valid/event.json');
  event.payload = {
    type: 'visual_condition_met',
    target_id: 'apple',
    condition: 'present_for_duration',
    duration_ms: 3000
  };

  assert.deepEqual(validateEvent(event).errors, []);
  const episodeCondition = structuredClone(event);
  episodeCondition.payload.condition = 'appeared';
  assert.equal(validateEvent(episodeCondition).ok, false);
  const extraCount = structuredClone(event);
  extraCount.payload.count = 1;
  assert.equal(validateEvent(extraCount).ok, false);
});

test('current Manifest templates remain structurally valid and visual_target exposes reachable rules', async () => {
  const templateRoot = 'model-tools/v3/releases/current-internal/templates';
  for (const name of ['reference', 'object', 'reader']) {
    const manifest = await readJson(`${templateRoot}/${name}.manifest.template.json`);
    manifest.signature = placeholderSignature;
    assert.deepEqual(validateManifest(manifest).errors, [], name);
  }

  const catalog = await readJson(`${templateRoot}/catalog.template.json`);
  catalog.signature = placeholderSignature;
  assert.deepEqual(validateCatalog(catalog).errors, []);
  const visualTarget = catalog.capabilities.find((capability) => capability.capability_id === 'visual_target');
  assert.deepEqual(visualTarget.allowed_rule_types, ['presence_duration', 'absence_duration']);

  visualTarget.allowed_rule_types.push('object_count');
  assert.ok(validateCatalog(catalog).errors.some((error) =>
    error.code === 'visual_target_rule_types_mismatch'
  ));
});
