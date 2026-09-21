import { createHash } from 'node:crypto';
import { mkdir, open, rename, rm, stat, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import {
  canonicalize,
  parseCanonicalFixedHttpsUrl,
  sha256Hex,
  validateCatalog,
  validateCatalogForRelease,
  validateCommercialManifest,
  verifyManifestHash,
  verifySignedDocument,
} from './index.mjs';

const RELEASE_FORMAT = 'beyoureyes.catalog-release.v1';
const SNAPSHOT_FORMAT = 'beyoureyes.commercial-release-freeze.v2';
const MAX_CATALOG_BYTES = 2 * 1024 * 1024;
const MAX_RELEASE_BYTES = 8 * 1024 * 1024;
const MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
const MAX_ARTIFACT_BYTES = 2 * 1024 * 1024 * 1024;
const DEFAULT_ATTEMPTS = 3;
const DEFAULT_TIMEOUT_MILLIS = 10 * 60 * 1000;
const MAX_CATALOG_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000;
const SHA_256 = /^[0-9a-f]{64}$/;
const SAFE_PACKAGE_ID = /^[a-z][a-z0-9_]{0,127}$/;
const SAFE_ARTIFACT_ROLE = /^[a-z][a-z0-9_]{0,63}$/;
export const GOOGLE_PLAY_FIRST_RELEASE_PROFILE = 'google-play-first-release-v1';

const GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS = Object.freeze([
  Object.freeze({
    recipeId: 'neural_reference_target_v1',
    capabilityKey: 'reference_object_matching',
    capabilityId: 'visual_target',
    runtimeFamily: 'similarity_match_v1',
    promptModes: Object.freeze(['reference_images']),
  }),
  Object.freeze({
    recipeId: 'reading_pipeline_general_v1',
    capabilityKey: 'numeric_display_reading',
    capabilityId: 'structured_reading',
    runtimeFamily: 'reading_pipeline_v1',
    promptModes: Object.freeze(['none']),
  }),
  Object.freeze({
    recipeId: 'object_detection_general_v1',
    capabilityKey: 'common_objects_tensorflow_efficientdet_lite2',
    capabilityId: 'visual_target',
    runtimeFamily: 'object_detection_v1',
    promptModes: Object.freeze(['object_class']),
    adapterSchemaId: 'object_detection_v1',
  }),
]);

function record(value, path) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${path} must be an object`);
  }
  return value;
}

function exactKeys(value, expected, path) {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new Error(`${path} contains unexpected or missing fields`);
  }
}

function canonicalBytes(value) {
  return Buffer.from(`${canonicalize(value)}\n`, 'utf8');
}

function parseJson(bytes, path) {
  try {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    return JSON.parse(text);
  } catch {
    throw new Error(`${path} is not valid UTF-8 JSON`);
  }
}

function strictBase64(value, path) {
  if (
    typeof value !== 'string' ||
    value.length === 0 ||
    value.length % 4 !== 0 ||
    !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)
  ) {
    throw new Error(`${path} must be canonical base64`);
  }
  const bytes = Buffer.from(value, 'base64');
  if (bytes.toString('base64') !== value) throw new Error(`${path} must be canonical base64`);
  return bytes;
}

function canonicalFixedHttpsUrl(value, path) {
  const source = value instanceof URL ? value.toString() : value;
  const parsed = parseCanonicalFixedHttpsUrl(source);
  if (parsed === undefined) {
    throw new Error(`${path} must be a canonical fixed HTTPS URL`);
  }
  return parsed;
}

function sameStrings(left, right) {
  const leftValues = [...left].sort();
  const rightValues = [...right].sort();
  return leftValues.length === rightValues.length &&
    leftValues.every((value, index) => value === rightValues[index]);
}

function assertGooglePlayFirstReleaseProfile(catalog, label) {
  if (catalog.build_channel !== 'commercial') {
    throw new Error(`${label} ${GOOGLE_PLAY_FIRST_RELEASE_PROFILE} requires build_channel commercial`);
  }
  if (catalog.runtime_recipes.length !== GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS.length) {
    throw new Error(
      `${label} ${GOOGLE_PLAY_FIRST_RELEASE_PROFILE} requires exactly ` +
      `${GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS.length} runtime recipes`,
    );
  }
  const recipes = GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS.map((requirement) => {
    const matches = catalog.runtime_recipes.filter((recipe) =>
      recipe.recipe_id === requirement.recipeId &&
      recipe.capability_id === requirement.capabilityId &&
      recipe.runtime_family === requirement.runtimeFamily &&
      sameStrings(recipe.prompt_modes, requirement.promptModes));
    if (matches.length !== 1) {
      throw new Error(
        `${label} ${GOOGLE_PLAY_FIRST_RELEASE_PROFILE} requires exactly ` +
        `${requirement.recipeId}:${requirement.capabilityId}:${requirement.runtimeFamily} ` +
        `with ${requirement.promptModes.join(',')}`,
      );
    }
    return matches[0];
  });
  const expectedCapabilityIds = [...new Set(
    GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS.map((value) => value.capabilityId),
  )];
  if (!sameStrings(catalog.capabilities.map((value) => value.capability_id), expectedCapabilityIds)) {
    throw new Error(`${label} ${GOOGLE_PLAY_FIRST_RELEASE_PROFILE} contains an unexpected capability`);
  }
  for (const capabilityId of expectedCapabilityIds) {
    const requirements = GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS.filter(
      (value) => value.capabilityId === capabilityId,
    );
    const requirementRecipes = requirements.map((requirement) =>
      recipes[GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS.indexOf(requirement)]);
    const capability = catalog.capabilities.find((value) => value.capability_id === capabilityId);
    if (
      capability === undefined ||
      !sameStrings(
        capability.target_modes,
        new Set(requirements.flatMap((value) => value.promptModes)),
      ) ||
      !sameStrings(capability.recipe_ids, requirementRecipes.map((recipe) => recipe.recipe_id))
    ) {
      throw new Error(
        `${label} ${GOOGLE_PLAY_FIRST_RELEASE_PROFILE} ${capabilityId} surface is not exact`,
      );
    }
  }
  for (let index = 0; index < GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS.length; index += 1) {
    const requirement = GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS[index];
    const recipe = recipes[index];
    if (recipe.candidate_package_ids.length !== 1) {
      throw new Error(
        `${label} ${GOOGLE_PLAY_FIRST_RELEASE_PROFILE} ${requirement.capabilityKey} must bind exactly one package`,
      );
    }
    const operations = catalog.operational_capabilities.filter(
      (value) => value.capability_key === requirement.capabilityKey,
    );
    if (
      operations.length !== 1 ||
      operations[0].capability_id !== requirement.capabilityId ||
      operations[0].status !== 'commercial' ||
      !sameStrings(operations[0].package_ids, recipe.candidate_package_ids)
    ) {
      throw new Error(
        `${label} ${GOOGLE_PLAY_FIRST_RELEASE_PROFILE} ${requirement.capabilityKey} operational surface is not exact`,
      );
    }
  }
  const allowedOperationalKeys = new Set(
    GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS.map((value) => value.capabilityKey),
  );
  for (const operation of catalog.operational_capabilities) {
    if (allowedOperationalKeys.has(operation.capability_key)) continue;
    throw new Error(
      `${label} ${GOOGLE_PLAY_FIRST_RELEASE_PROFILE} contains unexpected operation ${operation.capability_key}`,
    );
  }
  const candidateIds = new Set(recipes.flatMap((recipe) => recipe.candidate_package_ids));
  const activeIds = catalog.packages
    .filter((entry) => entry.status === 'active')
    .map((entry) => entry.package_id);
  if (
    candidateIds.size !== GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS.length ||
    activeIds.length !== GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS.length ||
    !sameStrings(candidateIds, activeIds)
  ) {
    throw new Error(
      `${label} ${GOOGLE_PLAY_FIRST_RELEASE_PROFILE} active packages must exactly match its recipe candidates`,
    );
  }
}

export function assertCommercialReleaseProfile(catalog, profile, label = 'commercial Catalog') {
  if (profile === GOOGLE_PLAY_FIRST_RELEASE_PROFILE) {
    assertGooglePlayFirstReleaseProfile(catalog, label);
    return;
  }
  throw new Error(`unknown commercial release profile ${String(profile)}`);
}

export function assertCommercialReleaseManifestProfile(
  catalog,
  manifest,
  profile,
  label = 'commercial Manifest',
) {
  if (profile !== GOOGLE_PLAY_FIRST_RELEASE_PROFILE) {
    throw new Error(`unknown commercial release profile ${String(profile)}`);
  }
  const recipes = catalog.runtime_recipes.filter((recipe) =>
    recipe.candidate_package_ids.includes(manifest.package_id));
  if (
    recipes.length !== 1 ||
    manifest.runtime_family !== recipes[0].runtime_family ||
    !sameStrings(manifest.prompt_modes, recipes[0].prompt_modes)
  ) {
    throw new Error(
      `${label} ${GOOGLE_PLAY_FIRST_RELEASE_PROFILE} package surface is not exact`,
    );
  }
  const requirement = GOOGLE_PLAY_FIRST_RELEASE_REQUIREMENTS.find((value) =>
    value.recipeId === recipes[0].recipe_id &&
    value.capabilityId === recipes[0].capability_id &&
    value.runtimeFamily === recipes[0].runtime_family &&
    sameStrings(value.promptModes, recipes[0].prompt_modes));
  if (
    requirement?.adapterSchemaId !== undefined &&
    manifest.adapter_contract?.schema_id !== requirement.adapterSchemaId
  ) {
    throw new Error(
      `${label} ${GOOGLE_PLAY_FIRST_RELEASE_PROFILE} must reuse ${requirement.runtimeFamily} ` +
      `with ${requirement.adapterSchemaId}`,
    );
  }
}

function validationError(label, result) {
  if (result.ok) return;
  const summary = result.errors.slice(0, 10).map((entry) => `${entry.path}:${entry.code}`).join(', ');
  throw new Error(`${label} failed validation: ${summary}`);
}

async function requireMissing(path) {
  try {
    await stat(path);
  } catch (error) {
    if (error?.code === 'ENOENT') return;
    throw error;
  }
  throw new Error(`release snapshot output already exists: ${path}`);
}

async function fetchResponse(url, label, fetchImplementation, signal) {
  const response = await fetchImplementation(url, {
    redirect: 'follow',
    signal,
    headers: { 'accept-encoding': 'identity' },
  });
  if (!response || response.ok !== true) {
    throw new Error(`${label} download failed with HTTP ${response?.status ?? 'unknown'}`);
  }
  if (typeof response.url === 'string' && response.url.length > 0) {
    const finalUrl = canonicalFixedHttpsUrl(response.url, `${label} final URL`);
    if (finalUrl.origin !== url.origin) {
      throw new Error(`${label} cross-origin redirect is forbidden`);
    }
  }
  if (response.body === null) throw new Error(`${label} response has no body`);
  const contentEncoding = response.headers?.get?.('content-encoding');
  if (contentEncoding !== null && contentEncoding !== undefined && contentEncoding !== '' && contentEncoding !== 'identity') {
    throw new Error(`${label} must be served without transport content encoding`);
  }
  return response;
}

async function writeComplete(file, bytes, label) {
  let offset = 0;
  while (offset < bytes.byteLength) {
    const result = await file.write(bytes, offset, bytes.byteLength - offset, null);
    if (!Number.isInteger(result.bytesWritten) || result.bytesWritten <= 0) {
      throw new Error(`${label} temporary file write made no progress`);
    }
    offset += result.bytesWritten;
  }
}

async function digestFile(path) {
  const file = await open(path, 'r');
  try {
    const information = await file.stat();
    const hash = createHash('sha256');
    const buffer = Buffer.allocUnsafe(64 * 1024);
    let position = 0;
    while (position < information.size) {
      const { bytesRead } = await file.read(buffer, 0, buffer.length, position);
      if (bytesRead <= 0) throw new Error('temporary file ended before its recorded size');
      hash.update(buffer.subarray(0, bytesRead));
      position += bytesRead;
    }
    return { bytes: information.size, sha256: hash.digest('hex') };
  } finally {
    await file.close();
  }
}

async function streamDownload({
  url,
  label,
  maximumBytes,
  expectedBytes,
  expectedSha256,
  destination,
  fetchImplementation,
  attempts,
  timeoutMillis,
}) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(new Error(`${label} timed out`)), timeoutMillis);
    let file;
    try {
      if (destination !== undefined) {
        await mkdir(dirname(destination), { recursive: true });
        await rm(destination, { force: true });
        file = await open(destination, 'wx', 0o600);
      }
      const response = await fetchResponse(url, label, fetchImplementation, controller.signal);
      const declaredText = response.headers?.get?.('content-length');
      if (declaredText !== null && declaredText !== undefined) {
        if (!/^(?:0|[1-9][0-9]*)$/.test(declaredText)) {
          throw new Error(`${label} returned an invalid Content-Length`);
        }
        const declared = Number(declaredText);
        if (!Number.isSafeInteger(declared)) throw new Error(`${label} returned an unsafe Content-Length`);
        if (expectedBytes !== undefined && declared !== expectedBytes) {
          throw new Error(`${label} Content-Length differs from the signed size`);
        }
        if (declared > maximumBytes) throw new Error(`${label} exceeds its download limit`);
      }
      const reader = response.body.getReader();
      const hash = createHash('sha256');
      const chunks = destination === undefined ? [] : undefined;
      let bytes = 0;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        if (!(value instanceof Uint8Array)) throw new Error(`${label} returned a non-byte stream`);
        bytes += value.byteLength;
        if (bytes > maximumBytes || (expectedBytes !== undefined && bytes > expectedBytes)) {
          await reader.cancel();
          throw new Error(`${label} exceeds the signed or configured size`);
        }
        hash.update(value);
        if (file !== undefined) await writeComplete(file, value, label);
        else chunks.push(Buffer.from(value));
      }
      const sha256 = hash.digest('hex');
      if (expectedBytes !== undefined && bytes !== expectedBytes) {
        throw new Error(`${label} byte count differs from the signed size`);
      }
      if (expectedSha256 !== undefined && sha256 !== expectedSha256) {
        throw new Error(`${label} SHA-256 differs from the signed hash`);
      }
      if (file !== undefined) {
        await file.sync();
        await file.close();
        file = undefined;
        const stored = await digestFile(destination);
        if (stored.bytes !== bytes || stored.sha256 !== sha256) {
          throw new Error(`${label} temporary file differs from the verified response bytes`);
        }
      }
      return {
        bytes,
        sha256,
        content: chunks === undefined ? undefined : Buffer.concat(chunks),
        final_url: typeof response.url === 'string' && response.url.length > 0
          ? response.url
          : url.toString(),
      };
    } catch (error) {
      lastError = error;
      if (file !== undefined) await file.close().catch(() => {});
      file = undefined;
      if (destination !== undefined) await rm(destination, { force: true });
      if (attempt < attempts) continue;
    } finally {
      clearTimeout(timeout);
      if (file !== undefined) await file.close();
    }
  }
  throw lastError;
}

async function downloadBytes(url, label, maximumBytes, options) {
  return streamDownload({
    url,
    label,
    maximumBytes,
    fetchImplementation: options.fetchImplementation,
    attempts: options.attempts,
    timeoutMillis: options.timeoutMillis,
  });
}

function parseRelease(releaseBytes, catalogPublicKeys) {
  const release = record(parseJson(releaseBytes, '$release'), '$release');
  exactKeys(release, [
    'format',
    'status',
    'catalog_bytes_base64',
    'packages',
    'descriptor_sha256',
    'signature',
  ], '$release');
  if (release.format !== RELEASE_FORMAT || release.status !== 'active') {
    throw new Error('commercial release descriptor must be an active beyoureyes.catalog-release.v1 document');
  }
  if (!verifySignedDocument(release, catalogPublicKeys)) {
    throw new Error('commercial release descriptor Ed25519 signature is invalid');
  }
  const content = {
    format: release.format,
    status: release.status,
    catalog_bytes_base64: release.catalog_bytes_base64,
    packages: release.packages,
  };
  const calculated = sha256Hex(Buffer.from(canonicalize(content), 'utf8'));
  if (release.descriptor_sha256 !== calculated) {
    throw new Error('commercial release descriptor SHA-256 is invalid');
  }
  return release;
}

function parsePackageBundle(value, path) {
  const bundle = record(value, path);
  exactKeys(bundle, [
    'manifest_url',
    'manifest_bytes_base64',
    'license_evidence_ref',
    'license_evidence_bytes_base64',
    'license_text_bytes_base64',
    'artifact_descriptors_by_role',
  ], path);
  return bundle;
}

function assertArtifactDescriptors(manifest, rawDescriptors, path) {
  const descriptors = record(rawDescriptors, path);
  const expectedRoles = manifest.artifacts.map((artifact) => artifact.role).sort();
  const actualRoles = Object.keys(descriptors).sort();
  if (expectedRoles.join('\0') !== actualRoles.join('\0')) {
    throw new Error(`${path} roles differ from the signed Manifest`);
  }
  for (const artifact of manifest.artifacts) {
    const descriptor = record(descriptors[artifact.role], `${path}.${artifact.role}`);
    exactKeys(descriptor, ['url', 'sha256', 'size_bytes'], `${path}.${artifact.role}`);
    if (
      descriptor.url !== artifact.url ||
      descriptor.sha256 !== artifact.sha256 ||
      descriptor.size_bytes !== artifact.size_bytes
    ) {
      throw new Error(`${path}.${artifact.role} differs from the signed Manifest`);
    }
  }
}

function checkedCatalog(bytes, publicKeys, label, requiredChannel) {
  const catalog = parseJson(bytes, label);
  validationError(label, validateCatalog(catalog));
  if (!verifySignedDocument(catalog, publicKeys)) {
    throw new Error(`${label} Ed25519 signature is invalid`);
  }
  if (catalog.build_channel !== requiredChannel) {
    throw new Error(`${label} build_channel must be ${requiredChannel}`);
  }
  return catalog;
}

async function freezeReleaseVersion({
  label,
  namespace,
  catalog,
  catalogBytes,
  catalogDownload,
  catalogSource,
  releaseBytes,
  releaseDownload,
  releaseSource,
  staging,
  catalogPublicKeys,
  manifestPublicKeys,
  fetchImplementation,
  attempts,
  timeoutMillis,
  now,
  options,
  releaseProfile,
}) {
  const release = parseRelease(releaseBytes, catalogPublicKeys);
  const embeddedCatalogBytes = strictBase64(release.catalog_bytes_base64, `$${namespace}.release.catalog_bytes_base64`);
  if (!embeddedCatalogBytes.equals(catalogBytes)) {
    throw new Error(`${label} Catalog differs from its signed release descriptor`);
  }
  const activeEntries = catalog.packages.filter((entry) => entry.status === 'active');
  if (activeEntries.length === 0) throw new Error(`${label} Catalog has no active model package`);
  const releasePackages = record(release.packages, `$${namespace}.release.packages`);
  if (activeEntries.map((entry) => entry.package_id).sort().join('\0') !== Object.keys(releasePackages).sort().join('\0')) {
    throw new Error(`${label} release descriptor packages must exactly match active Catalog packages`);
  }

  const manifests = [];
  const integrityByPackage = {};
  const packageRecords = {};
  const namespaceRoot = join(staging, namespace);
  await mkdir(join(namespaceRoot, 'manifests'), { recursive: true, mode: 0o755 });
  await mkdir(join(namespaceRoot, 'licenses'), { recursive: true, mode: 0o755 });
  for (const entry of activeEntries) {
    if (!SAFE_PACKAGE_ID.test(entry.package_id)) throw new Error(`unsafe package id ${entry.package_id}`);
    const bundle = parsePackageBundle(releasePackages[entry.package_id], `$${namespace}.release.packages.${entry.package_id}`);
    if (bundle.manifest_url !== entry.manifest_url) {
      throw new Error(`${label} ${entry.package_id} descriptor/Catalog Manifest URL mismatch`);
    }
    const manifestSource = canonicalFixedHttpsUrl(entry.manifest_url, `${label} ${entry.package_id} Manifest URL`);
    const manifestDownload = await downloadBytes(
      manifestSource,
      `${label} ${entry.package_id} Manifest`,
      MAX_MANIFEST_BYTES,
      options,
    );
    const embeddedManifestBytes = strictBase64(
      bundle.manifest_bytes_base64,
      `$${namespace}.release.packages.${entry.package_id}.manifest_bytes_base64`,
    );
    if (!manifestDownload.content.equals(embeddedManifestBytes)) {
      throw new Error(`${label} ${entry.package_id} downloaded Manifest differs from the release descriptor`);
    }
    if (!verifyManifestHash(entry, manifestDownload.content)) {
      throw new Error(`${label} ${entry.package_id} Manifest SHA-256 differs from the Catalog`);
    }
    const manifest = parseJson(manifestDownload.content, `${label} ${entry.package_id} Manifest`);
    if (!verifySignedDocument(manifest, manifestPublicKeys)) {
      throw new Error(`${label} ${entry.package_id} Manifest Ed25519 signature is invalid`);
    }
    if (manifest.package_id !== entry.package_id || manifest.package_version !== entry.package_version) {
      throw new Error(`${label} ${entry.package_id} Manifest identity/version mismatch`);
    }
    if (releaseProfile !== undefined) {
      assertCommercialReleaseManifestProfile(
        catalog,
        manifest,
        releaseProfile,
        `${label} ${entry.package_id} Manifest`,
      );
    }
    if (bundle.license_evidence_ref !== manifest.license.review_evidence_ref) {
      throw new Error(`${label} ${entry.package_id} license review evidence reference mismatch`);
    }
    const licenseReviewEvidenceBytes = strictBase64(
      bundle.license_evidence_bytes_base64,
      `$${namespace}.release.packages.${entry.package_id}.license_evidence_bytes_base64`,
    );
    const licenseReviewEvidenceSha256 = sha256Hex(licenseReviewEvidenceBytes);
    if (licenseReviewEvidenceSha256 !== manifest.license.review_evidence_sha256) {
      throw new Error(`${label} ${entry.package_id} license review evidence SHA-256 differs from the Manifest`);
    }
    const licenseBytes = strictBase64(
      bundle.license_text_bytes_base64,
      `$${namespace}.release.packages.${entry.package_id}.license_text_bytes_base64`,
    );
    const licenseSha256 = sha256Hex(licenseBytes);
    if (licenseSha256 !== manifest.license.license_text_sha256) {
      throw new Error(`${label} ${entry.package_id} license text SHA-256 differs from the Manifest`);
    }
    assertArtifactDescriptors(
      manifest,
      bundle.artifact_descriptors_by_role,
      `$${namespace}.release.packages.${entry.package_id}.artifact_descriptors_by_role`,
    );
    validationError(`${label} ${entry.package_id} commercial package policy`, validateCommercialManifest(manifest, {
      now,
      signatureValid: true,
      artifactHashesValid: true,
      licenseTextHashValid: true,
      licenseReviewEvidenceHashValid: true,
    }));

    const artifactRecords = [];
    for (const artifact of manifest.artifacts) {
      if (!SAFE_ARTIFACT_ROLE.test(artifact.role)) {
        throw new Error(`${label} ${entry.package_id} has an unsafe artifact role`);
      }
      if (!Number.isSafeInteger(artifact.size_bytes) || artifact.size_bytes <= 0 || artifact.size_bytes > MAX_ARTIFACT_BYTES) {
        throw new Error(`${label} ${entry.package_id}:${artifact.role} signed size exceeds the Android artifact limit`);
      }
      if (!SHA_256.test(artifact.sha256)) throw new Error(`${label} ${entry.package_id}:${artifact.role} has an invalid SHA-256`);
      const artifactSource = canonicalFixedHttpsUrl(artifact.url, `${label} ${entry.package_id}:${artifact.role} URL`);
      const relativeArtifactFile =
        `${namespace}/artifacts/${entry.package_id}/${artifact.role}/${artifact.sha256}.bin`;
      const retainedArtifact = join(staging, relativeArtifactFile);
      const downloaded = await streamDownload({
        url: artifactSource,
        label: `${label} ${entry.package_id}:${artifact.role} artifact`,
        maximumBytes: artifact.size_bytes,
        expectedBytes: artifact.size_bytes,
        expectedSha256: artifact.sha256,
        destination: retainedArtifact,
        fetchImplementation,
        attempts,
        timeoutMillis,
      });
      artifactRecords.push({
        role: artifact.role,
        runtime: artifact.runtime,
        media_type: artifact.media_type,
        url: artifact.url,
        final_url: downloaded.final_url,
        file: relativeArtifactFile,
        size_bytes: downloaded.bytes,
        sha256: downloaded.sha256,
        retained_in_snapshot: true,
      });
    }

    const manifestFile = `${namespace}/manifests/${entry.package_id}.json`;
    const licenseFile = `${namespace}/licenses/${entry.package_id}.txt`;
    const licenseReviewEvidenceFile = `${namespace}/licenses/${entry.package_id}.review-evidence`;
    await writeFile(join(staging, manifestFile), manifestDownload.content, { flag: 'wx', mode: 0o644 });
    await writeFile(join(staging, licenseFile), licenseBytes, { flag: 'wx', mode: 0o644 });
    await writeFile(
      join(staging, licenseReviewEvidenceFile),
      licenseReviewEvidenceBytes,
      { flag: 'wx', mode: 0o644 },
    );
    manifests.push(manifest);
    integrityByPackage[entry.package_id] = {
      manifestHashValid: true,
      signatureValid: true,
      artifactHashesValid: true,
      licenseTextHashValid: true,
      licenseReviewEvidenceHashValid: true,
    };
    packageRecords[entry.package_id] = {
      package_version: entry.package_version,
      manifest: {
        url: entry.manifest_url,
        final_url: manifestDownload.final_url,
        file: manifestFile,
        size_bytes: manifestDownload.bytes,
        sha256: manifestDownload.sha256,
        signing_key_id: manifest.signature.signing_key_id,
      },
      license_text: {
        file: licenseFile,
        size_bytes: licenseBytes.length,
        sha256: licenseSha256,
      },
      license_review_evidence: {
        ref: manifest.license.review_evidence_ref,
        file: licenseReviewEvidenceFile,
        size_bytes: licenseReviewEvidenceBytes.length,
        sha256: licenseReviewEvidenceSha256,
      },
      artifacts: artifactRecords,
    };
  }

  validationError(`${label} Catalog release policy`, validateCatalogForRelease(catalog, {
    manifests,
    integrityByPackage,
    catalogSignatureValid: true,
    now,
  }));
  const catalogFile = `${namespace}/catalog.json`;
  const releaseFile = `${namespace}/release.json`;
  await writeFile(join(staging, catalogFile), catalogBytes, { flag: 'wx', mode: 0o644 });
  await writeFile(join(staging, releaseFile), releaseBytes, { flag: 'wx', mode: 0o644 });
  return {
    catalog: {
      url: catalogSource.toString(),
      final_url: catalogDownload.final_url,
      file: catalogFile,
      catalog_id: catalog.catalog_id,
      catalog_version: catalog.catalog_version,
      issued_at: catalog.issued_at,
      build_channel: catalog.build_channel,
      signing_key_id: catalog.signature.signing_key_id,
      size_bytes: catalogDownload.bytes,
      sha256: catalogDownload.sha256,
    },
    release_descriptor: {
      url: releaseSource.toString(),
      final_url: releaseDownload.final_url,
      file: releaseFile,
      signing_key_id: release.signature.signing_key_id,
      descriptor_sha256: release.descriptor_sha256,
      size_bytes: releaseDownload.bytes,
      sha256: releaseDownload.sha256,
    },
    packages: packageRecords,
  };
}

/**
 * Freeze and verify every byte that a commercial Android build is allowed to
 * reference. Model artifacts are streamed directly to content-addressed paths
 * inside the immutable snapshot and retained after their signed size and
 * SHA-256 are verified, together with signed metadata, license text, and the exact license-review record.
 */
export async function freezeCommercialRelease({
  catalogUrl,
  releaseUrl,
  rollbackCatalogUrl,
  rollbackReleaseUrl,
  outputDirectory,
  catalogPublicKeys,
  manifestPublicKeys,
  fetchImplementation = globalThis.fetch,
  attempts = DEFAULT_ATTEMPTS,
  timeoutMillis = DEFAULT_TIMEOUT_MILLIS,
  now = new Date(),
  releaseProfile,
}) {
  if (typeof fetchImplementation !== 'function') throw new Error('fetch implementation is required');
  if (!Number.isInteger(attempts) || attempts < 1 || attempts > 5) throw new Error('attempts must be 1..5');
  if (!Number.isInteger(timeoutMillis) || timeoutMillis < 100 || timeoutMillis > DEFAULT_TIMEOUT_MILLIS) {
    throw new Error(`timeoutMillis must be 100..${DEFAULT_TIMEOUT_MILLIS}`);
  }
  const catalogSource = canonicalFixedHttpsUrl(catalogUrl, 'catalog URL');
  const releaseSource = canonicalFixedHttpsUrl(releaseUrl, 'release descriptor URL');
  const rollbackSource = canonicalFixedHttpsUrl(rollbackCatalogUrl, 'rollback Catalog URL');
  const rollbackReleaseSource = canonicalFixedHttpsUrl(rollbackReleaseUrl, 'rollback release descriptor URL');
  const output = resolve(outputDirectory);
  await requireMissing(output);
  const staging = `${output}.tmp-${process.pid}-${Date.now()}`;
  const options = { fetchImplementation, attempts, timeoutMillis };
  try {
    const catalogDownload = await downloadBytes(catalogSource, 'commercial Catalog', MAX_CATALOG_BYTES, options);
    const releaseDownload = await downloadBytes(releaseSource, 'commercial release descriptor', MAX_RELEASE_BYTES, options);
    const rollbackDownload = await downloadBytes(rollbackSource, 'rollback Catalog', MAX_CATALOG_BYTES, options);
    const rollbackReleaseDownload = await downloadBytes(
      rollbackReleaseSource,
      'rollback release descriptor',
      MAX_RELEASE_BYTES,
      options,
    );
    const catalogBytes = catalogDownload.content;
    const releaseBytes = releaseDownload.content;
    const rollbackBytes = rollbackDownload.content;
    const rollbackReleaseBytes = rollbackReleaseDownload.content;
    const catalog = checkedCatalog(catalogBytes, catalogPublicKeys, 'commercial Catalog', 'commercial');
    const rollbackCatalog = checkedCatalog(rollbackBytes, catalogPublicKeys, 'rollback Catalog', 'commercial');
    if (releaseProfile !== undefined) {
      assertCommercialReleaseProfile(catalog, releaseProfile, 'current commercial Catalog');
      assertCommercialReleaseProfile(rollbackCatalog, releaseProfile, 'rollback commercial Catalog');
    }
    if (catalogDownload.sha256 === rollbackDownload.sha256) {
      throw new Error('rollback Catalog must be a distinct signed version');
    }
    if (catalog.catalog_id !== rollbackCatalog.catalog_id) {
      throw new Error('rollback Catalog must belong to the same Catalog identity');
    }
    if (catalog.catalog_version === rollbackCatalog.catalog_version) {
      throw new Error('rollback Catalog must have a distinct version');
    }
    if (Date.parse(rollbackCatalog.issued_at) > Date.parse(catalog.issued_at)) {
      throw new Error('rollback Catalog must not be newer than the current Catalog');
    }
    if (Date.parse(catalog.issued_at) > now.getTime() + 5 * 60 * 1000) {
      throw new Error('commercial Catalog issued_at is too far in the future');
    }
    if (
      Date.parse(catalog.issued_at) <= now.getTime() - MAX_CATALOG_AGE_MILLIS ||
      Date.parse(rollbackCatalog.issued_at) <= now.getTime() - MAX_CATALOG_AGE_MILLIS
    ) {
      throw new Error('current and rollback Catalogs must both be fresh enough for Android activation');
    }
    const currentFrozen = await freezeReleaseVersion({
      label: 'current commercial release',
      namespace: 'current',
      catalog,
      catalogBytes,
      catalogDownload,
      catalogSource,
      releaseBytes,
      releaseDownload,
      releaseSource,
      staging,
      catalogPublicKeys,
      manifestPublicKeys,
      fetchImplementation,
      attempts,
      timeoutMillis,
      now,
      options,
      releaseProfile,
    });
    const rollbackFrozen = await freezeReleaseVersion({
      label: 'rollback commercial release',
      namespace: 'rollback',
      catalog: rollbackCatalog,
      catalogBytes: rollbackBytes,
      catalogDownload: rollbackDownload,
      catalogSource: rollbackSource,
      releaseBytes: rollbackReleaseBytes,
      releaseDownload: rollbackReleaseDownload,
      releaseSource: rollbackReleaseSource,
      staging,
      catalogPublicKeys,
      manifestPublicKeys,
      fetchImplementation,
      attempts,
      timeoutMillis,
      now,
      options,
      releaseProfile,
    });

    const snapshot = {
      format: SNAPSHOT_FORMAT,
      status: 'verified',
      verified_at: now.toISOString(),
      ...(releaseProfile === undefined ? {} : { release_profile: releaseProfile }),
      current_catalog: currentFrozen.catalog,
      release_descriptor: currentFrozen.release_descriptor,
      packages: currentFrozen.packages,
      rollback_catalog: rollbackFrozen.catalog,
      rollback_release_descriptor: rollbackFrozen.release_descriptor,
      rollback_packages: rollbackFrozen.packages,
      invariants: {
        commercial_only: true,
        catalog_manifest_release_bytes_equal: true,
        signatures_verified: true,
        license_text_hashes_verified: true,
        license_review_evidence_hashes_verified: true,
        declared_optional_evidence_hashes_verified: true,
        all_artifact_bytes_streamed_and_verified: true,
        dynamic_code_allowed: false,
        artifact_bytes_retained: true,
      },
    };
    await writeFile(join(staging, 'snapshot.json'), canonicalBytes(snapshot), { flag: 'wx', mode: 0o644 });
    await rename(staging, output);
    return snapshot;
  } catch (error) {
    await rm(staging, { recursive: true, force: true });
    throw error;
  }
}

export const commercialReleaseFreezeLimits = Object.freeze({
  catalogBytes: MAX_CATALOG_BYTES,
  releaseBytes: MAX_RELEASE_BYTES,
  manifestBytes: MAX_MANIFEST_BYTES,
  artifactBytes: MAX_ARTIFACT_BYTES,
});

export const commercialReleaseFreezeTestSupport = Object.freeze({
  canonicalFixedHttpsUrl,
  writeComplete,
});
