#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const INTERNAL_CHANNEL = 'internal-evaluation';
const COMMERCIAL_CHANNEL = 'commercial';
const SHA256 = /^[0-9a-f]{64}$/u;
const CURRENT_CATALOG_ID = 'be-your-eye-internal-2026-09-15';
const CURRENT_CATALOG_VERSION = '2026.09.15.1';
const FORBIDDEN_FIELDS = new Set(['access_tier', 'quality_gates', 'quality_status', 'evidence']);
// Match Android StrictSignedJson: catch stale releases before building or booting a device.
const CATALOG_MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000;
const METADATA_CLOCK_SKEW_MILLIS = 5 * 60 * 1000;
// Package bytes and their URLs stay frozen when only the Catalog is refreshed.
const RELEASE_PREFIX = 'https://pub-a59bfd86a96840039bf5caa3ff8d254a.r2.dev/'
  + 'internal-evaluation/2026-08-31/be-your-eye-v1';

export const CURRENT_TEMPLATE_PATHS = Object.freeze({
  catalog: 'model-tools/v3/releases/current-internal/templates/catalog.template.json',
  reference_manifest: 'model-tools/v3/releases/current-internal/templates/reference.manifest.template.json',
  reader_manifest: 'model-tools/v3/releases/current-internal/templates/reader.manifest.template.json',
  object_manifest: 'model-tools/v3/releases/current-internal/templates/object.manifest.template.json',
});

export const CURRENT_PACKAGE_SPECS = Object.freeze([
  Object.freeze({
    slot: 'reference',
    packageId: 'similarity_mediapipe_mobilenet_v3_large_v1',
    packageVersion: '0.1.0-internal.15',
    runtimeFamily: 'similarity_match_v1',
    recipeId: 'neural_reference_target_v1',
    capabilityKey: 'reference_object_matching',
    manifestUrl: `${RELEASE_PREFIX}/manifests/similarity_mediapipe_mobilenet_v3_large_v1/0.1.0-internal.15.json`,
  }),
  Object.freeze({
    slot: 'reader',
    packageId: 'numeric_reader_ppocrv6_medium_v1',
    packageVersion: '0.1.0-internal.14',
    runtimeFamily: 'reading_pipeline_v1',
    recipeId: 'reading_pipeline_general_v1',
    capabilityKey: 'numeric_display_reading',
    manifestUrl: `${RELEASE_PREFIX}/manifests/numeric_reader_ppocrv6_medium_v1/0.1.0-internal.14.json`,
  }),
  Object.freeze({
    slot: 'object',
    packageId: 'efficientdet_lite2_object_v1',
    packageVersion: '1.0.0-internal.1',
    runtimeFamily: 'object_detection_v1',
    recipeId: 'object_detection_general_v1',
    capabilityKey: 'common_objects_tensorflow_efficientdet_lite2',
    manifestUrl: `${RELEASE_PREFIX}/manifests/efficientdet_lite2_object_v1/1.0.0-internal.1.json`,
  }),
]);

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function issue(issues, condition, code, details = undefined) {
  if (!condition) issues.push({ code, ...(details === undefined ? {} : { details }) });
}

function scanForbidden(value, path = '$', findings = []) {
  if (Array.isArray(value)) {
    value.forEach((entry, index) => scanForbidden(entry, `${path}[${index}]`, findings));
  } else if (value !== null && typeof value === 'object') {
    for (const [key, entry] of Object.entries(value)) {
      if (FORBIDDEN_FIELDS.has(key)) findings.push(`${path}.${key}`);
      scanForbidden(entry, `${path}.${key}`, findings);
    }
  }
  return findings;
}

function exactPackageIds(catalog) {
  return (catalog?.packages ?? [])
    .filter((entry) => entry?.status === 'active')
    .map((entry) => entry?.package_id)
    .sort();
}

function reviewBinding(manifest, evidence) {
  const reference = manifest?.license?.review_evidence_ref;
  const expected = manifest?.license?.review_evidence_sha256;
  return typeof reference === 'string'
    && !isAbsolute(reference)
    && !reference.split('/').includes('..')
    && evidence?.path === reference
    && Buffer.isBuffer(evidence?.bytes)
    && SHA256.test(expected ?? '')
    && sha256(evidence.bytes) === expected;
}

export function auditCurrentModelSurface(
  { catalog, manifests, reviewEvidence },
  channel = INTERNAL_CHANNEL,
  nowEpochMillis = Date.now(),
) {
  const issues = [];
  issue(issues, channel === INTERNAL_CHANNEL || channel === COMMERCIAL_CHANNEL, 'channel_invalid');
  issue(issues, catalog?.schema_version === '4.0', 'catalog_schema_invalid');
  issue(issues, catalog?.catalog_id === CURRENT_CATALOG_ID, 'catalog_id_invalid');
  issue(issues, catalog?.catalog_version === CURRENT_CATALOG_VERSION, 'catalog_version_invalid');
  issue(issues, catalog?.build_channel === INTERNAL_CHANNEL, 'catalog_channel_invalid');
  const issuedAt = Date.parse(catalog?.issued_at);
  issue(issues, Number.isFinite(issuedAt), 'catalog_timestamp_invalid');
  if (Number.isFinite(issuedAt)) {
    issue(issues, issuedAt <= nowEpochMillis + METADATA_CLOCK_SKEW_MILLIS, 'catalog_from_future');
    issue(issues, nowEpochMillis < issuedAt + CATALOG_MAX_AGE_MILLIS, 'catalog_expired', {
      expires_at: new Date(issuedAt + CATALOG_MAX_AGE_MILLIS).toISOString(),
    });
  }

  const expectedIds = CURRENT_PACKAGE_SPECS.map((spec) => spec.packageId).sort();
  issue(
    issues,
    JSON.stringify(exactPackageIds(catalog)) === JSON.stringify(expectedIds)
      && catalog?.packages?.length === expectedIds.length,
    'active_package_set_invalid',
  );

  const documents = [catalog, ...Object.values(manifests ?? {})];
  const forbidden = documents.flatMap((document, index) => scanForbidden(document, `$[${index}]`));
  issue(issues, forbidden.length === 0, 'retired_fields_present', forbidden);

  for (const spec of CURRENT_PACKAGE_SPECS) {
    const manifest = manifests?.[spec.slot];
    const catalogEntry = catalog?.packages?.find((entry) => entry?.package_id === spec.packageId);
    const recipe = catalog?.runtime_recipes?.find((entry) => entry?.recipe_id === spec.recipeId);
    const capability = catalog?.operational_capabilities?.find(
      (entry) => entry?.capability_key === spec.capabilityKey,
    );
    issue(issues, manifest?.schema_version === '4.0', `${spec.slot}_schema_invalid`);
    issue(
      issues,
      manifest?.package_id === spec.packageId && manifest?.package_version === spec.packageVersion,
      `${spec.slot}_identity_invalid`,
    );
    issue(issues, manifest?.runtime_family === spec.runtimeFamily, `${spec.slot}_runtime_invalid`);
    issue(
      issues,
      catalogEntry?.package_version === spec.packageVersion
        && catalogEntry?.manifest_url === spec.manifestUrl
        && catalogEntry?.status === 'active',
      `${spec.slot}_catalog_binding_invalid`,
    );
    issue(
      issues,
      recipe?.runtime_family === spec.runtimeFamily
        && JSON.stringify(recipe?.candidate_package_ids) === JSON.stringify([spec.packageId]),
      `${spec.slot}_recipe_binding_invalid`,
    );
    issue(
      issues,
      JSON.stringify(capability?.package_ids) === JSON.stringify([spec.packageId])
        && capability?.recipe_id === spec.recipeId
        && typeof capability?.model_card?.model_home_url === 'string'
        && capability.model_card.model_home_url.startsWith('https://'),
      `${spec.slot}_capability_binding_invalid`,
    );
    issue(issues, reviewBinding(manifest, reviewEvidence?.[spec.slot]), `${spec.slot}_review_binding_invalid`);
    issue(
      issues,
      manifest?.license?.commercial_use_allowed === true
        && manifest?.license?.redistribution_allowed === true
        && manifest?.license?.review_status === 'approved',
      `${spec.slot}_license_invalid`,
    );
    issue(
      issues,
      Array.isArray(manifest?.artifacts) && manifest.artifacts.length > 0
        && manifest.artifacts.every((artifact) => artifact?.url?.startsWith('https://')
          && SHA256.test(artifact?.sha256 ?? '') && Number.isInteger(artifact?.size_bytes)
          && artifact.size_bytes > 0),
      `${spec.slot}_artifact_binding_invalid`,
    );
    issue(
      issues,
      manifest?.device_compatibility?.abis?.includes('arm64-v8a')
        && manifest?.device_compatibility?.min_memory_mb === 8192,
      `${spec.slot}_device_profile_invalid`,
    );
  }

  const object = manifests?.object;
  const objectArtifact = object?.artifacts?.find((artifact) => artifact?.role === 'primary');
  issue(issues, objectArtifact?.sha256 === '6fd32c84ab1eb0f7e7f3a7a20a20d7df1530daa8378728f7c79571096286bd52'
    && objectArtifact?.size_bytes === 7557887, 'object_exact_artifact_invalid');
  issue(issues, object?.model_source?.url === 'https://www.kaggle.com/models/tensorflow/efficientdet/tfLite/lite2-detection-metadata/1', 'object_source_invalid');
  issue(issues, object?.parameter_profile?.defaults?.score_threshold === 0.5, 'object_threshold_invalid');
  issue(issues, object?.inputs?.[0]?.runtime_shape?.join(',') === '1,448,448,3', 'object_input_invalid');
  issue(issues, object?.adapter_contract?.class_map?.targets?.length === 80, 'object_class_map_invalid');

  if (channel === COMMERCIAL_CHANNEL) {
    // This profile binds the historical Internal candidate. A publisher license review
    // cannot turn its signed channel into a Commercial candidate; freeze that separately.
    issue(issues, false, 'commercial_candidate_required');
  }

  return {
    schema_version: 'be-your-eye-current-model-surface-v2',
    channel,
    ok: issues.length === 0,
    package_count: expectedIds.length,
    commercial_freeze_blocked: channel === COMMERCIAL_CHANNEL && issues.some(
      (entry) => entry.code === 'commercial_candidate_required',
    ),
    issues,
  };
}

function loadFixture(repositoryRoot) {
  const templates = Object.fromEntries(
    Object.entries(CURRENT_TEMPLATE_PATHS).map(([key, relativePath]) => [
      key,
      JSON.parse(readFileSync(join(repositoryRoot, relativePath), 'utf8')),
    ]),
  );
  const manifests = {
    reference: templates.reference_manifest,
    reader: templates.reader_manifest,
    object: templates.object_manifest,
  };
  const reviewEvidence = Object.fromEntries(Object.entries(manifests).map(([slot, manifest]) => {
    const path = manifest.license.review_evidence_ref;
    return [slot, { path, bytes: readFileSync(join(repositoryRoot, path)) }];
  }));
  return { catalog: templates.catalog, manifests, reviewEvidence };
}

function runCli() {
  const channelIndex = process.argv.indexOf('--channel');
  const channel = channelIndex >= 0 ? process.argv[channelIndex + 1] : INTERNAL_CHANNEL;
  const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
  const result = auditCurrentModelSurface(loadFixture(repositoryRoot), channel);
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if (!result.ok) process.exitCode = 2;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) runCli();
