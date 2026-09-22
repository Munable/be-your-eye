import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import {
  validateCatalog,
  validateCatalogForRelease,
  validateManifest,
  validateTaskConfig
} from '../src/index.mjs';
import { intentPatternMatches } from '../src/contracts.mjs';

const root = path.resolve(import.meta.dirname, '../../..');

async function readVector(name) {
  return JSON.parse(await fs.readFile(path.join(root, 'test-vectors/valid', name), 'utf8'));
}

test('the finite bilingual object package and Catalog are structurally valid', async () => {
  const [manifest, catalog, task] = await Promise.all([
    readVector('model-manifest-object-detection.json'),
    readVector('capability-catalog.json'),
    readVector('task-config.json')
  ]);
  assert.deepEqual(validateManifest(manifest).errors, []);
  assert.deepEqual(validateCatalog(catalog).errors, []);
  assert.deepEqual(validateTaskConfig(task, catalog).errors, []);
});

test('duplicate raw IDs and normalized aliases are rejected by the signed class map', async () => {
  const manifest = await readVector('model-manifest-object-detection.json');
  manifest.adapter_contract.class_map.targets.push({
    ...manifest.adapter_contract.class_map.targets[0],
    target_id: 'another-person'
  });
  const result = validateManifest(manifest);
  assert.ok(result.errors.some((error) => error.code === 'object_detection_duplicate_raw_class_id'));

  const aliasManifest = await readVector('model-manifest-object-detection.json');
  aliasManifest.adapter_contract.class_map.targets[1].aliases.push('人物');
  const aliasResult = validateManifest(aliasManifest);
  assert.ok(aliasResult.errors.some((error) => error.code === 'object_detection_alias_conflict'));
});

test('locale-keyed Catalog labels are validated and participate in exact matching', async () => {
  const manifest = await readVector('model-manifest-object-detection.json');
  manifest.adapter_contract.class_map.targets[0].labels = {
    en: 'person',
    'zh-Hans': '人',
    'zh-Hant': '人',
    ja: '人',
    ko: '사람',
    es: 'persona',
    fr: 'personne',
    de: 'Person',
    'pt-BR': 'pessoa'
  };
  assert.deepEqual(validateManifest(manifest).errors, []);

  const conflict = structuredClone(manifest);
  conflict.adapter_contract.class_map.targets[1].labels = { es: 'persona' };
  assert.ok(validateManifest(conflict).errors.some((error) => error.code === 'object_detection_alias_conflict'));
});

test('unknown task text is rejected instead of being routed to a model', async () => {
  const [task, catalog] = await Promise.all([
    readVector('task-config.json'),
    readVector('capability-catalog.json')
  ]);
  task.target_definition.target_id = 'a cat near the door';
  assert.ok(validateTaskConfig(task, catalog).errors.some((error) => error.code === 'pattern'));
});

test('fixed-monitor presence episode uses the complete condition-bound TaskConfig rule', async () => {
  const [task, catalog] = await Promise.all([
    readVector('task-config.json'),
    readVector('capability-catalog.json')
  ]);
  task.rule = {
    type: 'presence_duration',
    condition: 'appears',
    duration_ms: 500,
    min_positive_count: 2,
    max_positive_gap_ms: 1500,
    rearm_absence_ms: 5000
  };

  assert.deepEqual(validateTaskConfig(task, catalog).errors, []);
  assert.deepEqual(Object.keys(task.rule).sort(), [
    'condition',
    'duration_ms',
    'max_positive_gap_ms',
    'min_positive_count',
    'rearm_absence_ms',
    'type'
  ]);

  const localStorageRuleOnly = {
    ...task,
    rule: { type: 'presence_duration', duration_ms: 1000 }
  };
  assert.ok(
    validateTaskConfig(localStorageRuleOnly, catalog).errors.some((error) => error.code === 'required')
  );

  const mismatchedCondition = structuredClone(task);
  mismatchedCondition.rule.condition = 'disappears';
  assert.ok(validateTaskConfig(mismatchedCondition, catalog).errors.some((error) =>
    error.path.endsWith('/condition')));
});

test('intent patterns only authorize an exact key or one terminal wildcard namespace', () => {
  assert.equal(intentPatternMatches('object.common.apple', 'object.common.apple'), true);
  assert.equal(intentPatternMatches('object.common.*', 'object.common.apple'), true);
  assert.equal(intentPatternMatches('object.common.*', 'object.common'), false);
  assert.equal(intentPatternMatches('object.*.apple', 'object.common.apple'), false);
  assert.equal(intentPatternMatches('object.common.*', 'object.common.*'), false);
  assert.equal(intentPatternMatches('object.common.apple', 'object.common.banana'), false);
});

test('executable model profiles fail closed on malformed routing metadata', async () => {
  const catalog = await readVector('capability-catalog.json');
  const profile = catalog.operational_capabilities.find((value) =>
    value.capability_key === 'common_objects_tensorflow_efficientdet_lite2');

  profile.intent_patterns = ['object.*.apple'];
  assert.equal(validateCatalog(catalog).ok, false);

  const recipeMismatch = await readVector('capability-catalog.json');
  const mismatchedProfile = recipeMismatch.operational_capabilities.find((value) =>
    value.capability_key === 'common_objects_tensorflow_efficientdet_lite2');
  mismatchedProfile.recipe_id = 'reference_match_general_v1';
  assert.ok(validateCatalog(recipeMismatch).errors.some((error) =>
    error.code === 'operational_package_recipe_mismatch'));

  const missingCard = await readVector('capability-catalog.json');
  missingCard.operational_capabilities.find((value) =>
    value.capability_key === 'common_objects_tensorflow_efficientdet_lite2').model_card = null;
  assert.equal(validateCatalog(missingCard).ok, false);

  const missingTargets = await readVector('capability-catalog.json');
  delete missingTargets.operational_capabilities.find((value) =>
    value.capability_key === 'common_objects_tensorflow_efficientdet_lite2').target_ids;
  assert.ok(validateCatalog(missingTargets).errors.some((error) =>
    error.code === 'operational_target_coverage_missing'));
});

test('model cards cannot shadow provider defaults from the signed Manifest', async () => {
  const catalog = await readVector('capability-catalog.json');
  const profile = catalog.operational_capabilities.find((value) =>
    value.capability_key === 'common_objects_tensorflow_efficientdet_lite2');
  profile.model_card.defaults = { score_threshold: 0.1 };

  const result = validateCatalog(catalog);
  assert.equal(result.ok, false);
  assert.ok(result.errors.some((error) => error.code === 'additional_property'));
});

test('release admission binds every model profile to its exact recipe', async () => {
  const catalog = await readVector('capability-catalog.json');
  const objectProfile = catalog.operational_capabilities.find((value) =>
    value.capability_key === 'common_objects_tensorflow_efficientdet_lite2');
  objectProfile.recipe_id = 'reference_match_general_v1';
  delete objectProfile.target_ids;
  catalog.runtime_recipes.find((value) =>
    value.recipe_id === 'reference_match_general_v1')
    .candidate_package_ids.push('efficientdet_lite2_object_v1');
  assert.deepEqual(validateCatalog(catalog).errors, []);

  const manifests = await Promise.all([
    readVector('model-manifest-reference-target.json'),
    readVector('model-manifest-object-detection.json'),
    readVector('model-manifest-reading.json')
  ]);
  const integrityByPackage = Object.fromEntries(manifests.map((manifest) => [
    manifest.package_id,
    {
      manifestHashValid: true,
      signatureValid: true,
      artifactHashesValid: true,
      licenseTextHashValid: true
    }
  ]));
  const result = validateCatalogForRelease(catalog, {
    manifests,
    integrityByPackage,
    catalogSignatureValid: true,
    now: new Date('2026-08-21T00:00:00Z')
  });

  assert.ok(result.errors.some((error) =>
    error.code === 'package_operational_recipe_mismatch'));
});

test('release admission requires exact profile target coverage from package Manifests', async () => {
  const catalog = await readVector('capability-catalog.json');
  const profile = catalog.operational_capabilities.find((value) =>
    value.capability_key === 'common_objects_tensorflow_efficientdet_lite2');
  profile.target_ids = profile.target_ids.filter((targetId) => targetId !== 'apple');
  assert.deepEqual(validateCatalog(catalog).errors, []);

  const manifests = await Promise.all([
    readVector('model-manifest-reference-target.json'),
    readVector('model-manifest-object-detection.json'),
    readVector('model-manifest-reading.json')
  ]);
  const integrityByPackage = Object.fromEntries(manifests.map((manifest) => [
    manifest.package_id,
    {
      manifestHashValid: true,
      signatureValid: true,
      artifactHashesValid: true,
      licenseTextHashValid: true
    }
  ]));
  const result = validateCatalogForRelease(catalog, {
    manifests,
    integrityByPackage,
    catalogSignatureValid: true,
    now: new Date('2026-08-21T00:00:00Z')
  });

  assert.ok(result.errors.some((error) =>
    error.code === 'package_operational_target_coverage_mismatch'));
});
