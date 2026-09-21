import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  auditCurrentModelSurface,
  CURRENT_PACKAGE_SPECS,
  CURRENT_TEMPLATE_PATHS,
} from './check-current-model-quality.mjs';
import {
  auditBuiltSourceBindings,
  CURRENT_MANIFESTS,
  sha256Bytes,
} from './check-current-internal-built-manifests.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

function currentFixture() {
  const values = Object.fromEntries(Object.entries(CURRENT_TEMPLATE_PATHS).map(([key, path]) => [
    key,
    JSON.parse(readFileSync(join(ROOT, path), 'utf8')),
  ]));
  const manifests = {
    reference: values.reference_manifest,
    reader: values.reader_manifest,
    object: values.object_manifest,
  };
  const reviewEvidence = Object.fromEntries(Object.entries(manifests).map(([slot, manifest]) => {
    const path = manifest.license.review_evidence_ref;
    return [slot, { path, bytes: readFileSync(join(ROOT, path)) }];
  }));
  return { catalog: values.catalog, manifests, reviewEvidence };
}

function issueCodes(result) {
  return new Set(result.issues.map((issue) => issue.code));
}

test('current Internal surface is exactly three Catalog 4.0 packages', () => {
  const result = auditCurrentModelSurface(currentFixture());
  assert.equal(result.ok, true);
  assert.equal(result.package_count, 3);
  assert.equal(CURRENT_PACKAGE_SPECS.length, 3);
});

test('historical Internal inputs require a separately signed Commercial candidate', () => {
  const result = auditCurrentModelSurface(currentFixture(), 'commercial');
  assert.equal(result.ok, false);
  assert.equal(result.commercial_freeze_blocked, true);
  assert.deepEqual([...issueCodes(result)], ['commercial_candidate_required']);
});

test('Catalog age matches the Android seven-day expiry boundary', () => {
  const fixture = currentFixture();
  const expiresAt = Date.parse(fixture.catalog.issued_at) + 7 * 24 * 60 * 60 * 1000;
  assert.equal(auditCurrentModelSurface(fixture, 'internal-evaluation', expiresAt - 1).ok, true);
  assert.deepEqual(
    [...issueCodes(auditCurrentModelSurface(fixture, 'internal-evaluation', expiresAt))],
    ['catalog_expired'],
  );
});

test('invalid or future Catalog timestamps are rejected before device preparation', () => {
  const fixture = currentFixture();
  const issuedAt = Date.parse(fixture.catalog.issued_at);
  assert.ok(issueCodes(auditCurrentModelSurface(
    fixture, 'internal-evaluation', issuedAt - 5 * 60 * 1000 - 1,
  )).has('catalog_from_future'));
  fixture.catalog.issued_at = 'invalid';
  assert.ok(issueCodes(auditCurrentModelSurface(fixture)).has('catalog_timestamp_invalid'));
});

test('retired billing and pseudo-quality fields fail closed', () => {
  for (const field of ['access_tier', 'quality_gates', 'quality_status', 'evidence']) {
    const fixture = currentFixture();
    fixture.catalog.packages[0][field] = {};
    assert.ok(issueCodes(auditCurrentModelSurface(fixture)).has('retired_fields_present'));
  }
});

test('Lite2 exact artifact, source and provider threshold fail closed', () => {
  const mutations = [
    (fixture) => { fixture.manifests.object.artifacts[0].sha256 = 'a'.repeat(64); },
    (fixture) => { fixture.manifests.object.model_source.url = 'https://example.invalid/model'; },
    (fixture) => { fixture.manifests.object.parameter_profile.defaults.score_threshold = 0.4; },
  ];
  const expected = ['object_exact_artifact_invalid', 'object_source_invalid', 'object_threshold_invalid'];
  mutations.forEach((mutate, index) => {
    const fixture = currentFixture();
    mutate(fixture);
    assert.ok(issueCodes(auditCurrentModelSurface(fixture)).has(expected[index]));
  });
});

test('built source binding profile contains the same three packages', () => {
  const fixture = currentFixture();
  const builtManifestBytesBySlot = {};
  const sourceManifestsBySlot = {};
  const builtCatalog = structuredClone(fixture.catalog);
  for (const [slot, spec] of Object.entries(CURRENT_MANIFESTS)) {
    const source = fixture.manifests[slot];
    sourceManifestsBySlot[slot] = source;
    const built = { ...structuredClone(source), signature: { algorithm: 'fixture' } };
    const bytes = Buffer.from(`${JSON.stringify(built)}\n`);
    builtManifestBytesBySlot[slot] = bytes;
    builtCatalog.packages.find((entry) => entry.package_id === spec.packageId).manifest_sha256 = sha256Bytes(bytes);
  }
  const result = auditBuiltSourceBindings({
    builtCatalogBytes: Buffer.from(`${JSON.stringify({ ...builtCatalog, signature: { algorithm: 'fixture' } })}\n`),
    builtManifestBytesBySlot,
    sourceCatalog: fixture.catalog,
    sourceManifestsBySlot,
  });
  assert.equal(result.ok, true);
  assert.equal(Object.keys(result.manifests).length, 3);
});
