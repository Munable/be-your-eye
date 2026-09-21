import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  assertCommercialReleaseManifestProfile,
  assertCommercialReleaseProfile,
  GOOGLE_PLAY_FIRST_RELEASE_PROFILE,
} from '../src/commercial-release-freezer.mjs';

const CURRENT_CATALOG_URL = new URL(
  '../../v3/releases/current-internal/templates/catalog.template.json',
  import.meta.url,
);
const OBJECT_MANIFEST_URL = new URL(
  '../../v3/releases/current-internal/templates/object.manifest.template.json',
  import.meta.url,
);

async function json(url) {
  return JSON.parse(await readFile(url, 'utf8'));
}

async function currentCommercialCatalog() {
  const catalog = await json(CURRENT_CATALOG_URL);
  catalog.build_channel = 'commercial';
  for (const operation of catalog.operational_capabilities) {
    operation.status = 'commercial';
    operation.human_review.license_conclusion = 'approved-for-commercial';
  }
  return catalog;
}

test('Google Play profile accepts the exact three-package launch surface', async () => {
  const catalog = await currentCommercialCatalog();

  assert.doesNotThrow(() =>
    assertCommercialReleaseProfile(catalog, GOOGLE_PLAY_FIRST_RELEASE_PROFILE));
  assert.equal(catalog.runtime_recipes.length, 3);
  assert.equal(catalog.packages.filter((entry) => entry.status === 'active').length, 3);
});

test('Google Play profile binds Lite2 to the existing generic object runtime', async () => {
  const catalog = await currentCommercialCatalog();
  const manifest = await json(OBJECT_MANIFEST_URL);

  assert.doesNotThrow(() =>
    assertCommercialReleaseManifestProfile(
      catalog,
      manifest,
      GOOGLE_PLAY_FIRST_RELEASE_PROFILE,
    ));
  assert.equal(manifest.package_id, 'efficientdet_lite2_object_v1');
  assert.equal(manifest.runtime_family, 'object_detection_v1');
});

test('Google Play profile rejects an unexpected fourth model surface', async () => {
  const catalog = await currentCommercialCatalog();
  catalog.runtime_recipes.push({
    ...structuredClone(catalog.runtime_recipes[1]),
    recipe_id: 'unexpected_specialist_v1',
  });

  assert.throws(
    () => assertCommercialReleaseProfile(catalog, GOOGLE_PLAY_FIRST_RELEASE_PROFILE),
    /requires exactly 3 runtime recipes/,
  );
});

test('Google Play profile rejects a genericized object capability key', async () => {
  const catalog = await currentCommercialCatalog();
  catalog.operational_capabilities.find(
    (operation) => operation.capability_key === 'common_objects_tensorflow_efficientdet_lite2',
  ).capability_key = 'object_detection';

  assert.throws(
    () => assertCommercialReleaseProfile(catalog, GOOGLE_PLAY_FIRST_RELEASE_PROFILE),
    /common_objects_tensorflow_efficientdet_lite2 operational surface is not exact/,
  );
});
