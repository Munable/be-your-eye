import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { generateKeyPairSync } from 'node:crypto';
import test from 'node:test';
import { tmpdir } from 'node:os';
import {
  sha256Hex,
  validateAgainstSchema,
  validateCatalog,
  validateCatalogForRelease,
  validateCommercialManifest,
  validateInternalEvaluationManifest,
  validateManifest,
  verifyManifestHash,
  verifyArtifactHashes,
  verifySignedDocument
} from '../src/index.mjs';
import { buildRelease } from '../src/release-builder.mjs';

const root = path.resolve(import.meta.dirname, '../../..');

async function buildProductAlignedFixture(t, { commercial = false } = {}) {
  const directory = await fs.mkdtemp(path.join(tmpdir(), 'beyoureyes-internal-reference-fixture-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const fixtureDirectory = path.join(directory, 'output');
  const catalog = JSON.parse(await fs.readFile(
    path.join(root, 'test-vectors/valid/capability-catalog.json'),
    'utf8'
  ));
  const manifest = JSON.parse(await fs.readFile(
    path.join(root, 'test-vectors/valid/model-manifest-reference-target.json'),
    'utf8'
  ));
  const packageId = 'similarity_mediapipe_mobilenet_v3_large_v1';
  const packageVersion = '0.1.0-internal.4';
  const recipeId = 'neural_reference_target_v1';
  const artifactBytesByRole = {
    primary: Buffer.from('hermetic-neural-reference-localizer-fixture-v2'),
    object_crop: Buffer.from('{"schema_version":"1.0","runtime_family":"prominent_object_candidates_v2","selection":"top_three_plus_full_frame_v2","box_encoding":"ymin_xmin_ymax_xmax_normalized_v1","score_threshold":0.2,"padding_fraction":0.0}\n'),
    embedder: Buffer.from('hermetic-neural-reference-embedder-fixture-v2'),
    similarity_head: Buffer.from('{"schema_version":"1.0","runtime_family":"l2_prototype_cosine_candidates_v2"}\n')
  };
  const licenseBytes = await fs.readFile(path.join(root, 'model-tools/v3/licenses/Apache-2.0.txt'));

  catalog.catalog_version = '2026.08.03.6';
  catalog.build_channel = commercial ? 'commercial' : 'internal-evaluation';
  catalog.capabilities = [{
    ...catalog.capabilities.find((value) => value.capability_id === 'visual_target'),
    target_modes: ['reference_images'],
    allowed_rule_types: ['presence_duration', 'absence_duration'],
    recipe_ids: [recipeId]
  }];
  catalog.runtime_recipes = [{
    recipe_id: recipeId,
    capability_id: 'visual_target',
    runtime_family: 'similarity_match_v1',
    prompt_modes: ['reference_images'],
    candidate_package_ids: [packageId]
  }];
  catalog.operational_capabilities = [{
    ...catalog.operational_capabilities.find((value) => value.capability_key === 'reference_object_matching'),
    recipe_id: recipeId,
    status: commercial ? 'commercial' : 'internal-evaluation',
    package_ids: [packageId],
    human_review: {
      ...catalog.operational_capabilities.find((value) => value.capability_key === 'reference_object_matching').human_review,
      license_conclusion: commercial
        ? 'approved-for-commercial'
        : 'approved-for-internal-evaluation'
    }
  }];
  catalog.packages = [{
    package_id: packageId,
    package_version: packageVersion,
    manifest_url: `https://fixtures.invalid/internal-evaluation/2026.08.03.6/${packageId}/${packageVersion}.json`,
    manifest_sha256: '0'.repeat(64),
    status: 'active'
  }];

  manifest.package_id = packageId;
  manifest.package_version = packageVersion;
  manifest.supported_tasks = ['visual_target'];
  manifest.license.license_text_sha256 = sha256Hex(licenseBytes);
  const licenseReviewEvidenceBytes = Buffer.from('approved Apache-2.0 package review\n');
  if (commercial) {
    manifest.license.review_evidence_ref = 'commercial-license-review.txt';
    manifest.license.review_evidence_sha256 = sha256Hex(licenseReviewEvidenceBytes);
    catalog.operational_capabilities[0].human_review.evidence_refs = [
      manifest.license.review_evidence_ref
    ];
  } else {
    delete manifest.license.review_evidence_sha256;
  }
  for (const artifact of manifest.artifacts) {
    const bytes = artifactBytesByRole[artifact.role];
    artifact.url = `https://fixtures.invalid/${packageId}/${packageVersion}/${artifact.role}.fixture`;
    artifact.sha256 = sha256Hex(bytes);
    artifact.size_bytes = bytes.length;
  }

  const inputDirectory = path.join(directory, 'input');
  await fs.mkdir(inputDirectory);
  const paths = {
    catalog: path.join(inputDirectory, 'catalog-template.json'),
    manifest: path.join(inputDirectory, 'manifest-template.json'),
    license: path.join(inputDirectory, 'LICENSE.txt'),
    licenseReview: path.join(inputDirectory, 'commercial-license-review.txt'),
    input: path.join(inputDirectory, 'release-input.json'),
    catalogPrivate: path.join(inputDirectory, 'catalog.private.pem'),
    manifestPrivate: path.join(inputDirectory, 'manifest.private.pem')
  };
  const artifactPaths = {};
  for (const [role, bytes] of Object.entries(artifactBytesByRole)) {
    artifactPaths[role] = path.join(inputDirectory, `${role}.fixture`);
    await fs.writeFile(artifactPaths[role], bytes);
  }
  await Promise.all([
    fs.writeFile(paths.catalog, JSON.stringify(catalog)),
    fs.writeFile(paths.manifest, JSON.stringify(manifest)),
    fs.writeFile(paths.license, licenseBytes),
    ...(commercial ? [fs.writeFile(paths.licenseReview, licenseReviewEvidenceBytes)] : []),
    fs.writeFile(paths.input, JSON.stringify({
      format: commercial
        ? 'beyoureyes.release-build-input.v2'
        : 'beyoureyes.release-build-input.v1',
      catalog_template: 'catalog-template.json',
      packages: [{
        package_id: packageId,
        manifest_template: 'manifest-template.json',
        license_text: 'LICENSE.txt',
        ...(commercial ? { license_review_evidence: 'commercial-license-review.txt' } : {}),
        artifacts_by_role: Object.fromEntries(
          Object.entries(artifactPaths).map(([role, artifactPath]) => [role, path.basename(artifactPath)])
        )
      }]
    }))
  ]);
  const catalogKeys = generateKeyPairSync('ed25519');
  const manifestKeys = generateKeyPairSync('ed25519');
  await Promise.all([
    fs.writeFile(
      paths.catalogPrivate,
      catalogKeys.privateKey.export({ type: 'pkcs8', format: 'pem' }),
      { mode: 0o600 }
    ),
    fs.writeFile(
      paths.manifestPrivate,
      manifestKeys.privateKey.export({ type: 'pkcs8', format: 'pem' }),
      { mode: 0o600 }
    )
  ]);
  await buildRelease({
    inputPath: paths.input,
    outputDirectory: fixtureDirectory,
    catalogKeyId: 'catalog-hermetic-test-a',
    catalogPrivateKeyPath: paths.catalogPrivate,
    manifestKeyId: 'manifest-hermetic-test-a',
    manifestPrivateKeyPath: paths.manifestPrivate,
    now: new Date('2026-08-03T02:40:00.000Z')
  });

  const manifestPath = path.join(
    fixtureDirectory,
    `manifests/${packageId}.json`
  );
  const manifestBytes = await fs.readFile(manifestPath);
  const catalogBytes = await fs.readFile(path.join(fixtureDirectory, 'catalog.json'));
  const releaseBytes = await fs.readFile(path.join(fixtureDirectory, 'release.json'));
  const checksums = JSON.parse(await fs.readFile(path.join(fixtureDirectory, 'checksums.json')));
  const builtManifest = JSON.parse(manifestBytes);
  const builtCatalog = JSON.parse(catalogBytes);
  const release = JSON.parse(releaseBytes);
  return {
    catalog: builtCatalog,
    catalogBytes,
    catalogKey: catalogKeys.publicKey,
    checksums,
    fixtureDirectory,
    licenseBytes,
    manifest: builtManifest,
    manifestBytes,
    manifestKey: manifestKeys.publicKey,
    release,
    releaseBytes
  };
}

test('product-aligned internal reference release is signed, exact, and billing-neutral', async (t) => {
  const fixture = await buildProductAlignedFixture(t);
  const {
    catalog,
    catalogBytes,
    catalogKey,
    checksums,
    fixtureDirectory,
    licenseBytes,
    manifest,
    manifestBytes,
    manifestKey,
    release
  } = fixture;

  assert.equal(validateAgainstSchema('model-manifest', manifest).ok, true);
  assert.equal(validateManifest(manifest).ok, true);
  assert.equal(validateAgainstSchema('capability-catalog', catalog).ok, true);
  assert.equal(validateCatalog(catalog).ok, true);
  assert.equal(
    verifySignedDocument(manifest, { [manifest.signature.signing_key_id]: manifestKey }),
    true
  );
  assert.equal(
    verifySignedDocument(catalog, { [catalog.signature.signing_key_id]: catalogKey }),
    true
  );
  assert.equal(
    verifySignedDocument(release, { [release.signature.signing_key_id]: catalogKey }),
    true
  );
  assert.equal(verifyManifestHash(catalog.packages[0], manifestBytes), true);
  assert.equal(sha256Hex(licenseBytes), manifest.license.license_text_sha256);
  assert.equal(release.catalog_bytes_base64, catalogBytes.toString('base64'));
  assert.equal(
    release.packages[manifest.package_id].manifest_bytes_base64,
    manifestBytes.toString('base64')
  );
  assert.equal(
    release.packages[manifest.package_id].license_text_bytes_base64,
    licenseBytes.toString('base64')
  );
  assert.equal(Object.hasOwn(release.packages[manifest.package_id], 'license_evidence_bytes_base64'), false);
  for (const artifact of manifest.artifacts) {
    assert.deepEqual(
      release.packages[manifest.package_id].artifact_descriptors_by_role[artifact.role],
      { url: artifact.url, sha256: artifact.sha256, size_bytes: artifact.size_bytes }
    );
    assert.equal(
      checksums.packages[manifest.package_id].artifact_sha256_by_role[artifact.role],
      artifact.sha256
    );
  }
  assert.equal(checksums.catalog_sha256, sha256Hex(catalogBytes));
  assert.equal(checksums.packages[manifest.package_id].manifest_sha256, sha256Hex(manifestBytes));
  assert.equal(checksums.packages[manifest.package_id].license_text_sha256, sha256Hex(licenseBytes));
  assert.equal(checksums.private_key_material_written, false);

  assert.equal(catalog.catalog_version, '2026.08.03.6');
  assert.equal(catalog.build_channel, 'internal-evaluation');
  assert.deepEqual(catalog.capabilities.map((value) => value.capability_id), ['visual_target']);
  assert.deepEqual(catalog.capabilities[0].target_modes, ['reference_images']);
  assert.deepEqual(catalog.capabilities[0].allowed_rule_types, ['presence_duration', 'absence_duration']);
  assert.deepEqual(catalog.runtime_recipes.map((value) => value.recipe_id), ['neural_reference_target_v1']);
  assert.deepEqual(manifest.supported_tasks, ['visual_target']);
  assert.deepEqual(manifest.prompt_modes, ['reference_images']);
  assert.equal(manifest.package_version, '0.1.0-internal.4');
  assert.match(catalog.packages[0].manifest_url, /2026\.08\.03\.6\/.+\/0\.1\.0-internal\.4\.json$/);

  const integrity = {
    manifestHashValid: true,
    signatureValid: true,
    artifactHashesValid: true,
    licenseTextHashValid: true
  };
  assert.equal(validateInternalEvaluationManifest(manifest, integrity).ok, true);
  assert.equal(validateCatalogForRelease(catalog, {
    manifests: [manifest],
    integrityByPackage: { [manifest.package_id]: integrity },
    catalogSignatureValid: true
  }).ok, true);
  const commercial = validateCommercialManifest(manifest, integrity);
  assert.equal(commercial.ok, false);
  assert.ok(commercial.errors.some((error) => error.code === 'license_review_evidence_sha256_missing'));

  const outputEntries = await fs.readdir(fixtureDirectory, { recursive: true });
  assert.equal(outputEntries.some((name) => /private|\.pem$|\.key$/i.test(name)), false);
  for (const name of outputEntries) {
    const candidate = path.join(fixtureDirectory, name);
    const information = await fs.stat(candidate);
    if (information.isFile()) {
      assert.equal((await fs.readFile(candidate)).includes('BEGIN PRIVATE KEY'), false);
    }
  }
});

test('commercial release requires exact integrity and license review without product accuracy claims', async (t) => {
  const fixture = await buildProductAlignedFixture(t, { commercial: true });
  const integrity = {
    manifestHashValid: true,
    signatureValid: true,
    artifactHashesValid: true,
    licenseTextHashValid: true,
    licenseReviewEvidenceHashValid: true
  };

  assert.equal(validateCommercialManifest(fixture.manifest, integrity).ok, true);
  assert.equal(validateCatalogForRelease(fixture.catalog, {
    manifests: [fixture.manifest],
    integrityByPackage: { [fixture.manifest.package_id]: integrity },
    catalogSignatureValid: true
  }).ok, true);
  assert.equal(
    Object.hasOwn(
      fixture.release.packages[fixture.manifest.package_id],
      'license_evidence_bytes_base64'
    ),
    true
  );
});

test('product-aligned internal reference release fails closed on bad signatures and hashes', async (t) => {
  const { catalog, catalogKey, manifest, manifestBytes, manifestKey } = await buildProductAlignedFixture(t);
  const badCatalogSignature = structuredClone(catalog);
  badCatalogSignature.capabilities[0].display_name = 'tampered';
  assert.equal(
    verifySignedDocument(badCatalogSignature, { [catalog.signature.signing_key_id]: catalogKey }),
    false
  );
  const badManifestSignature = structuredClone(manifest);
  badManifestSignature.parameter_profile.defaults.match_threshold = 0.5;
  assert.equal(
    verifySignedDocument(badManifestSignature, { [manifest.signature.signing_key_id]: manifestKey }),
    false
  );
  assert.equal(
    verifyManifestHash({ ...catalog.packages[0], manifest_sha256: '0'.repeat(64) }, manifestBytes),
    false
  );
  assert.equal(
    verifyArtifactHashes(manifest, {
      primary: Buffer.from('tampered'),
      object_crop: Buffer.from('tampered'),
      embedder: Buffer.from('tampered'),
      similarity_head: Buffer.from('tampered')
    }),
    false
  );
  const rejected = validateCatalogForRelease(catalog, {
    manifests: [manifest],
    integrityByPackage: {
      [manifest.package_id]: {
        manifestHashValid: false,
        signatureValid: false,
        artifactHashesValid: false,
        licenseTextHashValid: false
      }
    },
    catalogSignatureValid: false
  });
  assert.equal(rejected.ok, false);
  assert.ok(rejected.errors.some((error) => error.code === 'catalog_signature_invalid'));
  assert.ok(rejected.errors.some((error) => error.code === 'manifest_hash_invalid'));
});

test('third same-contract neural model is only Manifest and artifact fixture data', async () => {
  const directory = path.join(root, 'test-vectors/model-packages/neural-reference-third-v3');
  const manifest = JSON.parse(await fs.readFile(path.join(directory, 'manifest.json')));
  const sourceRoot = path.join(root, 'android/core/vision/src/main');
  const productionSources = await Promise.all(
    (await fs.readdir(path.join(sourceRoot, 'java/app/beyoureyes/core/vision')))
      .filter((name) => name.endsWith('.kt'))
      .map((name) => fs.readFile(path.join(sourceRoot, 'java/app/beyoureyes/core/vision', name), 'utf8'))
  );

  assert.equal(validateAgainstSchema('model-manifest', manifest).ok, true);
  assert.equal(validateManifest(manifest).ok, true);
  assert.equal(
    verifyArtifactHashes(manifest, {
      primary: await fs.readFile(path.join(directory, 'artifacts/primary.fixture')),
      object_crop: await fs.readFile(path.join(directory, 'artifacts/object-crop.json')),
      embedder: await fs.readFile(path.join(directory, 'artifacts/embedder.fixture')),
      similarity_head: await fs.readFile(path.join(directory, 'artifacts/similarity-head.json'))
    }),
    true
  );
  assert.equal(productionSources.some((source) => source.includes(manifest.package_id)), false);
});

test('neural reference packages forbid the deleted runtime cohesion filter', async () => {
  const directory = path.join(root, 'test-vectors/model-packages/neural-reference-third-v3');
  const manifest = JSON.parse(await fs.readFile(path.join(directory, 'manifest.json')));
  const legacy = structuredClone(manifest);
  legacy.parameter_profile.defaults.reference_cohesion_min_cosine = 0.6;
  legacy.parameter_profile.allowed_bounds.reference_cohesion_min_cosine = {
    minimum: 0.1,
    maximum: 0.99
  };

  assert.equal(validateManifest(manifest).ok, true);
  assert.ok(validateManifest(legacy).errors.some((error) =>
    error.code === 'reference_cohesion_parameter_forbidden'
  ));
});

test('neural reference packages reject every deleted reference preprocess path', async () => {
  const directory = path.join(root, 'test-vectors/model-packages/neural-reference-third-v3');
  const manifest = JSON.parse(await fs.readFile(path.join(directory, 'manifest.json')));

  for (const preprocessId of ['rgb_direct_resize_0_1_v1', 'classic_reference_rgb_v1']) {
    const legacy = structuredClone(manifest);
    legacy.preprocess_id = preprocessId;
    assert.ok(validateManifest(legacy).errors.some((error) =>
      error.code === 'reference_preprocess_contract_mismatch'
    ));
  }
});
