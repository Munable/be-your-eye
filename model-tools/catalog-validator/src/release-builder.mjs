import { createPrivateKey, createPublicKey, sign } from 'node:crypto';
import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import {
  canonicalize,
  sha256Hex,
  validateCatalogForRelease,
  validateCtcVocabularyArtifact,
  validateCommercialManifest,
  validateInternalEvaluationManifest,
  validateManifest,
  verifySignedDocument,
} from './index.mjs';

const INTERNAL_INPUT_FORMAT = 'beyoureyes.release-build-input.v1';
const COMMERCIAL_INPUT_FORMAT = 'beyoureyes.release-build-input.v2';
const RELEASE_FORMAT = 'beyoureyes.catalog-release.v1';
const SAFE_KEY_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
const SAFE_PACKAGE_ID = /^[a-z][a-z0-9_]{0,127}$/;

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

function string(value, path) {
  if (typeof value !== 'string' || value.length === 0) throw new Error(`${path} must be a non-empty string`);
  return value;
}

async function jsonFile(path) {
  const bytes = await readFile(path);
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  return JSON.parse(text);
}

function resolvedPath(base, value, path) {
  const raw = string(value, path);
  return isAbsolute(raw) ? resolve(raw) : resolve(base, raw);
}

async function privateEd25519(path, label) {
  const information = await stat(path);
  if (!information.isFile()) throw new Error(`${label} private key is not a regular file`);
  if ((information.mode & 0o077) !== 0) {
    throw new Error(`${label} private key must not be accessible by group or others`);
  }
  const key = createPrivateKey(await readFile(path));
  if (key.type !== 'private' || key.asymmetricKeyType !== 'ed25519') {
    throw new Error(`${label} private key must be Ed25519 PKCS#8`);
  }
  return key;
}

function signedDocument(template, keyId, privateKey) {
  if (!SAFE_KEY_ID.test(keyId)) throw new Error('invalid signing key id');
  const payload = structuredClone(record(template, '$document'));
  delete payload.signature;
  const signature = sign(null, Buffer.from(canonicalize(payload), 'utf8'), privateKey).toString('base64');
  return {
    ...payload,
    signature: {
      canonicalization: 'RFC8785',
      algorithm: 'Ed25519',
      signing_key_id: keyId,
      value: signature,
    },
  };
}

function canonicalDocumentBytes(value) {
  return Buffer.from(`${canonicalize(value)}\n`, 'utf8');
}

function validationError(label, result) {
  if (result.ok) return;
  const summary = result.errors.slice(0, 10).map((error) => `${error.path}:${error.code}`).join(', ');
  throw new Error(`${label} failed validation: ${summary}`);
}

function publicPem(privateKey) {
  return createPublicKey(privateKey).export({ type: 'spki', format: 'pem' }).toString();
}

function publicFingerprint(privateKey) {
  return sha256Hex(createPublicKey(privateKey).export({ type: 'spki', format: 'der' }));
}

async function readBuildInput(inputPath) {
  const input = record(await jsonFile(inputPath), '$input');
  exactKeys(input, ['format', 'catalog_template', 'packages'], '$input');
  if (![INTERNAL_INPUT_FORMAT, COMMERCIAL_INPUT_FORMAT].includes(input.format)) {
    throw new Error(`unsupported build input format ${input.format}`);
  }
  if (!Array.isArray(input.packages) || input.packages.length === 0) {
    throw new Error('$input.packages must be a non-empty array');
  }
  const base = dirname(inputPath);
  const packages = input.packages.map((value, index) => {
    const item = record(value, `$input.packages[${index}]`);
    const packageKeys = [
      'package_id',
      'manifest_template',
      'license_text',
      'artifacts_by_role',
    ];
    if (input.format === COMMERCIAL_INPUT_FORMAT) {
      packageKeys.push('license_review_evidence');
    }
    exactKeys(item, packageKeys, `$input.packages[${index}]`);
    const packageId = string(item.package_id, `$input.packages[${index}].package_id`);
    if (!SAFE_PACKAGE_ID.test(packageId)) throw new Error(`invalid package id ${packageId}`);
    const artifacts = record(item.artifacts_by_role, `$input.packages[${index}].artifacts_by_role`);
    if (Object.keys(artifacts).length === 0) throw new Error(`${packageId} has no artifacts`);
    return {
      packageId,
      manifestPath: resolvedPath(base, item.manifest_template, `${packageId}.manifest_template`),
      licensePath: resolvedPath(base, item.license_text, `${packageId}.license_text`),
      licenseReviewEvidencePath: input.format === COMMERCIAL_INPUT_FORMAT
        ? resolvedPath(base, item.license_review_evidence, `${packageId}.license_review_evidence`)
        : undefined,
      artifactPaths: Object.fromEntries(Object.entries(artifacts).map(([role, path]) => {
        if (!/^[a-z][a-z0-9_]*$/.test(role)) throw new Error(`${packageId} has invalid artifact role ${role}`);
        return [role, resolvedPath(base, path, `${packageId}.artifacts_by_role.${role}`)];
      })),
    };
  });
  if (new Set(packages.map((item) => item.packageId)).size !== packages.length) {
    throw new Error('duplicate package id in build input');
  }
  return {
    format: input.format,
    catalogPath: resolvedPath(base, input.catalog_template, '$input.catalog_template'),
    packages,
  };
}

async function packageMaterial(spec, manifestKeyId, manifestPrivateKey, buildChannel, now) {
  const template = record(await jsonFile(spec.manifestPath), `${spec.packageId}.manifest`);
  if (template.package_id !== spec.packageId) throw new Error(`${spec.packageId} Manifest identity mismatch`);
  const artifacts = Array.isArray(template.artifacts) ? template.artifacts : [];
  const expectedRoles = artifacts.map((artifact) => artifact?.role);
  if (expectedRoles.some((role) => typeof role !== 'string') || new Set(expectedRoles).size !== expectedRoles.length) {
    throw new Error(`${spec.packageId} Manifest artifact roles are invalid`);
  }
  const suppliedRoles = Object.keys(spec.artifactPaths);
  if (expectedRoles.slice().sort().join('\0') !== suppliedRoles.slice().sort().join('\0')) {
    throw new Error(`${spec.packageId} artifact role set differs from its Manifest`);
  }
  const artifactDescriptors = {};
  const artifactBytesByRole = {};
  for (const artifact of artifacts) {
    const path = spec.artifactPaths[artifact.role];
    const information = await stat(path);
    if (!information.isFile() || information.size <= 0) throw new Error(`${spec.packageId}:${artifact.role} is not a non-empty file`);
    const bytes = await readFile(path);
    artifactBytesByRole[artifact.role] = bytes;
    const hash = sha256Hex(bytes);
    if (artifact.sha256 !== hash || artifact.size_bytes !== information.size) {
      throw new Error(`${spec.packageId}:${artifact.role} bytes do not match the Manifest`);
    }
    artifactDescriptors[artifact.role] = {
      url: artifact.url,
      sha256: hash,
      size_bytes: information.size,
    };
  }
  const licenseBytes = await readFile(spec.licensePath);
  if (template.license?.license_text_sha256 !== sha256Hex(licenseBytes)) {
    throw new Error(`${spec.packageId} license text hash does not match the Manifest`);
  }
  let licenseReviewEvidenceBytes;
  if (['commercial', 'community'].includes(buildChannel)) {
    licenseReviewEvidenceBytes = await readFile(spec.licenseReviewEvidencePath);
    if (template.license?.review_evidence_sha256 !== sha256Hex(licenseReviewEvidenceBytes)) {
      throw new Error(`${spec.packageId} license review evidence hash does not match the Manifest`);
    }
  }
  const manifest = signedDocument(template, manifestKeyId, manifestPrivateKey);
  validationError(`${spec.packageId} structure`, validateManifest(manifest));
  if (manifest.runtime_family === 'reading_pipeline_v1') {
    const vocabularyRole = manifest.ctc_decoding?.vocabulary_artifact_role;
    validationError(
      `${spec.packageId} CTC vocabulary`,
      validateCtcVocabularyArtifact(manifest, artifactBytesByRole[vocabularyRole]),
    );
  }
  const policy = ['commercial', 'community'].includes(buildChannel)
    ? validateCommercialManifest(manifest, {
      now,
      signatureValid: true,
      artifactHashesValid: true,
      licenseTextHashValid: true,
      licenseReviewEvidenceHashValid: true,
    })
    : validateInternalEvaluationManifest(manifest, {
      now,
      signatureValid: true,
      artifactHashesValid: true,
      licenseTextHashValid: true,
    });
  validationError(`${spec.packageId} package policy`, policy);
  const manifestBytes = canonicalDocumentBytes(manifest);
  return {
    manifest,
    manifestBytes,
    licenseBytes,
    licenseReviewEvidenceBytes,
    artifactDescriptors,
    artifactHashes: Object.fromEntries(Object.entries(artifactDescriptors).map(([role, value]) => [role, value.sha256])),
  };
}

export async function buildRelease({
  inputPath,
  outputDirectory,
  catalogKeyId,
  catalogPrivateKeyPath,
  manifestKeyId,
  manifestPrivateKeyPath,
  now = new Date(),
}) {
  const resolvedInput = resolve(inputPath);
  const resolvedOutput = resolve(outputDirectory);
  const input = await readBuildInput(resolvedInput);
  const catalogPrivateKey = await privateEd25519(resolve(catalogPrivateKeyPath), 'Catalog');
  const manifestPrivateKey = await privateEd25519(resolve(manifestPrivateKeyPath), 'Manifest');
  const catalogPublicKey = createPublicKey(catalogPrivateKey);
  const manifestPublicKey = createPublicKey(manifestPrivateKey);
  const catalogTemplate = record(await jsonFile(input.catalogPath), '$catalog');
  if (!['commercial', 'community', 'internal-evaluation'].includes(catalogTemplate.build_channel)) {
    throw new Error('release builder accepts only commercial or internal-evaluation Catalogs');
  }
  if (['commercial', 'community'].includes(catalogTemplate.build_channel) && input.format !== COMMERCIAL_INPUT_FORMAT) {
    throw new Error(`commercial Catalogs require ${COMMERCIAL_INPUT_FORMAT} with license review evidence`);
  }
  const activeEntries = Array.isArray(catalogTemplate.packages)
    ? catalogTemplate.packages.filter((entry) => entry?.status === 'active')
    : [];
  const activeIds = activeEntries.map((entry) => entry.package_id).sort();
  const suppliedIds = input.packages.map((item) => item.packageId).sort();
  if (activeIds.join('\0') !== suppliedIds.join('\0')) {
    throw new Error('build input packages must exactly match active Catalog packages');
  }

  const materialByPackage = {};
  for (const spec of input.packages) {
    materialByPackage[spec.packageId] = await packageMaterial(
      spec,
      manifestKeyId,
      manifestPrivateKey,
      catalogTemplate.build_channel,
      now,
    );
  }
  const catalogPayload = structuredClone(catalogTemplate);
  delete catalogPayload.signature;
  catalogPayload.packages = catalogPayload.packages.map((entry) => {
    if (entry.status !== 'active') return entry;
    const material = materialByPackage[entry.package_id];
    if (material.manifest.package_version !== entry.package_version) {
      throw new Error(`${entry.package_id} Catalog/Manifest version mismatch`);
    }
    return { ...entry, manifest_sha256: sha256Hex(material.manifestBytes) };
  });
  const catalog = signedDocument(catalogPayload, catalogKeyId, catalogPrivateKey);
  const catalogBytes = canonicalDocumentBytes(catalog);
  const integrityByPackage = Object.fromEntries(activeIds.map((packageId) => [packageId, {
    manifestHashValid: true,
    signatureValid: true,
    artifactHashesValid: true,
    licenseTextHashValid: true,
    ...(['commercial', 'community'].includes(catalogTemplate.build_channel)
      ? { licenseReviewEvidenceHashValid: true }
      : {}),
  }]));
  validationError('Catalog release', validateCatalogForRelease(catalog, {
    manifests: Object.values(materialByPackage).map((value) => value.manifest),
    integrityByPackage,
    catalogSignatureValid: true,
    now,
  }));
  if (!verifySignedDocument(catalog, { [catalogKeyId]: catalogPublicKey })) {
    throw new Error('self-verification of Catalog signature failed');
  }
  for (const material of Object.values(materialByPackage)) {
    if (!verifySignedDocument(material.manifest, { [manifestKeyId]: manifestPublicKey })) {
      throw new Error(`self-verification of ${material.manifest.package_id} signature failed`);
    }
  }

  const packages = Object.fromEntries(activeIds.map((packageId) => {
    const entry = catalog.packages.find((candidate) => candidate.package_id === packageId);
    const material = materialByPackage[packageId];
    return [packageId, {
      manifest_url: entry.manifest_url,
      manifest_bytes_base64: material.manifestBytes.toString('base64'),
      license_evidence_ref: material.manifest.license.review_evidence_ref,
      ...(material.licenseReviewEvidenceBytes === undefined
        ? {}
        : { license_evidence_bytes_base64: material.licenseReviewEvidenceBytes.toString('base64') }),
      license_text_bytes_base64: material.licenseBytes.toString('base64'),
      artifact_descriptors_by_role: material.artifactDescriptors,
    }];
  }));
  const descriptorContent = {
    format: RELEASE_FORMAT,
    status: 'active',
    catalog_bytes_base64: catalogBytes.toString('base64'),
    packages,
  };
  const descriptorSha256 = sha256Hex(Buffer.from(canonicalize(descriptorContent), 'utf8'));
  const release = signedDocument(
    { ...descriptorContent, descriptor_sha256: descriptorSha256 },
    catalogKeyId,
    catalogPrivateKey,
  );
  if (!verifySignedDocument(release, { [catalogKeyId]: catalogPublicKey })) {
    throw new Error('self-verification of release descriptor failed');
  }
  const releaseBytes = canonicalDocumentBytes(release);
  const checksums = {
    format: 'beyoureyes.release-checksums.v1',
    created_at: now.toISOString(),
    catalog_key_id: catalogKeyId,
    catalog_public_key_sha256: publicFingerprint(catalogPrivateKey),
    manifest_key_id: manifestKeyId,
    manifest_public_key_sha256: publicFingerprint(manifestPrivateKey),
    private_key_material_written: false,
    catalog_sha256: sha256Hex(catalogBytes),
    release_sha256: sha256Hex(releaseBytes),
    packages: Object.fromEntries(activeIds.map((packageId) => [packageId, {
      manifest_sha256: sha256Hex(materialByPackage[packageId].manifestBytes),
      license_text_sha256: sha256Hex(materialByPackage[packageId].licenseBytes),
      ...(materialByPackage[packageId].licenseReviewEvidenceBytes === undefined
        ? {}
        : {
          license_review_evidence_sha256: sha256Hex(
            materialByPackage[packageId].licenseReviewEvidenceBytes,
          ),
        }),
      artifact_sha256_by_role: materialByPackage[packageId].artifactHashes,
    }])),
  };

  const temporary = `${resolvedOutput}.tmp-${process.pid}-${Date.now()}`;
  try {
    await mkdir(join(temporary, 'manifests'), { recursive: true, mode: 0o755 });
    await writeFile(join(temporary, 'catalog.json'), catalogBytes, { flag: 'wx', mode: 0o644 });
    await writeFile(join(temporary, 'release.json'), releaseBytes, { flag: 'wx', mode: 0o644 });
    await writeFile(
      join(temporary, 'checksums.json'),
      canonicalDocumentBytes(checksums),
      { flag: 'wx', mode: 0o644 },
    );
    for (const packageId of activeIds) {
      await writeFile(
        join(temporary, 'manifests', `${packageId}.json`),
        materialByPackage[packageId].manifestBytes,
        { flag: 'wx', mode: 0o644 },
      );
    }
    await rename(temporary, resolvedOutput);
  } catch (error) {
    await rm(temporary, { recursive: true, force: true });
    throw error;
  }
  return checksums;
}

export const releaseBuilderFormats = Object.freeze({
  commercialInput: COMMERCIAL_INPUT_FORMAT,
  internalInput: INTERNAL_INPUT_FORMAT,
  output: RELEASE_FORMAT,
});
