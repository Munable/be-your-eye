import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import {
  validateCatalog,
  validateEvent,
  validateManifest,
  validateSync,
  validateSyncEventUpsert,
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

test('sync Event upserts require source identity without widening the generic Event contract', async () => {
  const event = await readJson('test-vectors/valid/event.json');
  const syncEvent = {
    ...event,
    monitoring_device_id: '0198f000-0000-7000-8000-000000000002'
  };
  const sync = {
    schema_version: '3.0',
    cursor: 'sync_43',
    has_more: false,
    changes: [{
      change_id: 'event:0198f000-0000-7000-8000-000000000005:43',
      entity_type: 'event',
      operation: 'upsert',
      updated_at: '2026-08-02T00:03:00Z',
      entity: syncEvent
    }]
  };

  assert.deepEqual(validateSyncEventUpsert(syncEvent).errors, []);
  assert.deepEqual(validateSync(sync).errors, []);
  assert.ok(validateEvent(syncEvent).errors.some((error) =>
    error.path === '$/monitoring_device_id' && error.code === 'additional_property'
  ));

  const missingSource = structuredClone(sync);
  delete missingSource.changes[0].entity.monitoring_device_id;
  assert.equal(validateSync(missingSource).ok, false);

  const invalidSource = structuredClone(sync);
  invalidSource.changes[0].entity.monitoring_device_id = event.event_id.replace('-7', '-4');
  assert.equal(validateSync(invalidSource).ok, false);

  const tombstone = structuredClone(sync);
  tombstone.changes[0].operation = 'delete';
  tombstone.changes[0].entity = null;
  assert.deepEqual(validateSync(tombstone).errors, []);
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
