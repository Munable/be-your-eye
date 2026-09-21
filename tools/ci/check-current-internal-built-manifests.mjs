#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { canonicalize } from '../../model-tools/catalog-validator/src/index.mjs';

export const CURRENT_MANIFESTS = Object.freeze({
  reference: Object.freeze({
    packageId: 'similarity_mediapipe_mobilenet_v3_large_v1',
    template: 'reference.manifest.template.json',
  }),
  reader: Object.freeze({
    packageId: 'numeric_reader_ppocrv6_medium_v1',
    template: 'reader.manifest.template.json',
  }),
  object: Object.freeze({
    packageId: 'efficientdet_lite2_object_v1',
    template: 'object.manifest.template.json',
  }),
});

export function sha256Bytes(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function record(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function parseBuiltJson(bytes, code, issues) {
  if (bytes === undefined) {
    issues.push({ code: `${code}_missing` });
    return null;
  }
  try {
    const parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    if (!record(parsed)) throw new Error('root must be an object');
    return parsed;
  } catch (error) {
    issues.push({
      code: `${code}_invalid_json`,
      detail: error instanceof Error ? error.message : String(error),
    });
    return null;
  }
}

function sameCanonicalDocument(actual, expected, code, issues) {
  try {
    return canonicalize(actual) === canonicalize(expected);
  } catch (error) {
    issues.push({
      code: `${code}_canonicalization_failed`,
      detail: error instanceof Error ? error.message : String(error),
    });
    return false;
  }
}

export function auditBuiltSourceBindings({
  builtCatalogBytes,
  builtManifestBytesBySlot,
  sourceCatalog,
  sourceManifestsBySlot,
}) {
  const issues = [];
  const actualManifestShaByPackageId = {};
  const manifests = {};

  for (const [slot, spec] of Object.entries(CURRENT_MANIFESTS)) {
    const bytes = builtManifestBytesBySlot?.[slot];
    const built = parseBuiltJson(bytes, `${slot}_built_manifest`, issues);
    const source = sourceManifestsBySlot?.[slot];
    const sourceValid = record(source) && source.package_id === spec.packageId;
    if (!sourceValid) issues.push({ code: `${slot}_source_template_invalid` });

    const actualSha256 = bytes === undefined ? null : sha256Bytes(bytes);
    if (actualSha256 !== null) actualManifestShaByPackageId[spec.packageId] = actualSha256;
    const builtIdentityValid = built?.package_id === spec.packageId;
    if (built !== null && !builtIdentityValid) {
      issues.push({ code: `${slot}_built_manifest_identity_invalid` });
    }

    let sourceTemplateMatch = false;
    if (built !== null && sourceValid && builtIdentityValid) {
      const unsignedBuilt = structuredClone(built);
      delete unsignedBuilt.signature;
      sourceTemplateMatch = sameCanonicalDocument(
        unsignedBuilt,
        source,
        `${slot}_source_template`,
        issues,
      );
      if (!sourceTemplateMatch) issues.push({ code: `${slot}_source_template_mismatch` });
    }
    manifests[slot] = {
      package_id: spec.packageId,
      actual_sha256: actualSha256,
      source_template_match: sourceTemplateMatch,
    };
  }

  const builtCatalog = parseBuiltJson(builtCatalogBytes, 'built_catalog', issues);
  const expectedCatalog = record(sourceCatalog) ? structuredClone(sourceCatalog) : null;
  if (expectedCatalog === null || !Array.isArray(expectedCatalog.packages)) {
    issues.push({ code: 'source_catalog_template_invalid' });
  }

  const expectedPackageIds = Object.values(CURRENT_MANIFESTS)
    .map((spec) => spec.packageId)
    .sort();
  let sourceActiveSetValid = false;
  if (expectedCatalog !== null && Array.isArray(expectedCatalog.packages)) {
    const activeEntries = expectedCatalog.packages.filter((entry) => entry?.status === 'active');
    const activeIds = activeEntries.map((entry) => entry?.package_id).sort();
    sourceActiveSetValid = activeIds.length === expectedPackageIds.length
      && activeIds.every((packageId, index) => packageId === expectedPackageIds[index]);
    if (!sourceActiveSetValid) issues.push({ code: 'source_catalog_active_package_set_invalid' });
    if (sourceActiveSetValid) {
      for (const entry of activeEntries) {
        entry.manifest_sha256 = actualManifestShaByPackageId[entry.package_id];
      }
    }
  }

  let catalogSourceTemplateMatch = false;
  if (builtCatalog !== null && expectedCatalog !== null && sourceActiveSetValid
      && Object.keys(actualManifestShaByPackageId).length === expectedPackageIds.length) {
    const unsignedBuiltCatalog = structuredClone(builtCatalog);
    delete unsignedBuiltCatalog.signature;
    catalogSourceTemplateMatch = sameCanonicalDocument(
      unsignedBuiltCatalog,
      expectedCatalog,
      'catalog_source_template',
      issues,
    );
    if (!catalogSourceTemplateMatch) issues.push({ code: 'catalog_source_template_mismatch' });
  }

  return {
    schema_version: 'be-your-eye-current-built-source-binding-v1',
    ok: Object.values(manifests).every((manifest) => manifest.source_template_match)
      && catalogSourceTemplateMatch
      && issues.length === 0,
    manifests,
    catalog: { source_template_match: catalogSourceTemplateMatch },
    issues,
  };
}

function runCli() {
  const outputDirectory = process.argv[2];
  if (typeof outputDirectory !== 'string' || outputDirectory.length === 0) {
    throw new Error('Usage: check-current-internal-built-manifests.mjs OUTPUT_DIR');
  }

  const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
  const templateDirectory = join(
    repositoryRoot,
    'model-tools/v3/releases/current-internal/templates',
  );
  const resolvedOutput = resolve(outputDirectory);
  const builtManifestBytesBySlot = {};
  const sourceManifestsBySlot = {};
  const readErrors = {};

  for (const [slot, spec] of Object.entries(CURRENT_MANIFESTS)) {
    try {
      builtManifestBytesBySlot[slot] = readFileSync(
        join(resolvedOutput, 'manifests', `${spec.packageId}.json`),
      );
    } catch (error) {
      readErrors[`${slot}_built_manifest`] = error instanceof Error ? error.message : String(error);
    }
    try {
      sourceManifestsBySlot[slot] = JSON.parse(
        readFileSync(join(templateDirectory, spec.template), 'utf8'),
      );
    } catch (error) {
      readErrors[`${slot}_source_template`] = error instanceof Error ? error.message : String(error);
    }
  }

  let builtCatalogBytes;
  let sourceCatalog;
  try {
    builtCatalogBytes = readFileSync(join(resolvedOutput, 'catalog.json'));
  } catch (error) {
    readErrors.built_catalog = error instanceof Error ? error.message : String(error);
  }
  try {
    sourceCatalog = JSON.parse(readFileSync(join(templateDirectory, 'catalog.template.json'), 'utf8'));
  } catch (error) {
    readErrors.source_catalog_template = error instanceof Error ? error.message : String(error);
  }

  const sourceBindings = auditBuiltSourceBindings({
    builtCatalogBytes,
    builtManifestBytesBySlot,
    sourceCatalog,
    sourceManifestsBySlot,
  });
  const result = {
    schema_version: 'be-your-eye-current-built-artifact-check-v3',
    ok: sourceBindings.ok && Object.keys(readErrors).length === 0,
    source_bindings: sourceBindings,
    ...(Object.keys(readErrors).length === 0 ? {} : { read_errors: readErrors }),
  };
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if (!result.ok) process.exitCode = 2;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  runCli();
}
