import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

import { validateCatalog } from '../src/index.mjs';

const root = path.resolve(import.meta.dirname, '../../..');
const catalogPath = path.join(
  root,
  'model-tools/v3/releases/current-internal/templates/catalog.template.json',
);
const placeholderSignature = Object.freeze({
  canonicalization: 'RFC8785',
  algorithm: 'Ed25519',
  signing_key_id: 'model-key-2026-a',
  value: 'AA==',
});

async function currentCatalog() {
  const catalog = JSON.parse(await fs.readFile(catalogPath, 'utf8'));
  catalog.signature = placeholderSignature;
  return catalog;
}

test('Catalog v4 lists three executable billing-neutral packages', async () => {
  const catalog = await currentCatalog();

  assert.deepEqual(validateCatalog(catalog).errors, []);
  assert.deepEqual(
    catalog.packages.map((entry) => entry.package_id),
    [
      'similarity_mediapipe_mobilenet_v3_large_v1',
      'numeric_reader_ppocrv6_medium_v1',
      'efficientdet_lite2_object_v1',
    ],
  );
  assert.equal(catalog.packages.every((entry) => !Object.hasOwn(entry, 'access_tier')), true);
  assert.equal(catalog.operational_capabilities.length, 3);
  assert.equal(catalog.operational_capabilities.every((entry) =>
    entry.package_ids.length > 0 &&
      typeof entry.recipe_id === 'string' &&
      entry.model_card !== null &&
      !Object.hasOwn(entry, 'quality_status')), true);
});

test('Catalog v4 rejects package billing metadata and non-executable placeholder rows', async () => {
  const packageTier = await currentCatalog();
  packageTier.packages[0].access_tier = 'free';
  assert.equal(validateCatalog(packageTier).ok, false);

  const placeholder = await currentCatalog();
  placeholder.operational_capabilities.push({
    ...structuredClone(placeholder.operational_capabilities[0]),
    capability_key: 'future_placeholder',
    recipe_id: null,
    model_card: null,
    status: 'unsupported',
    package_ids: [],
  });
  assert.equal(validateCatalog(placeholder).ok, false);

  const candidateInventory = await currentCatalog();
  candidateInventory.operational_capabilities[0].candidate_models = [];
  assert.equal(validateCatalog(candidateInventory).ok, false);
});
