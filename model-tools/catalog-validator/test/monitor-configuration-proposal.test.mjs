import assert from 'node:assert/strict';
import test from 'node:test';
import { validateAgainstSchema } from '../src/schema-validator.mjs';

const catalogBinding = Object.freeze({
  catalog_id: 'be-your-eye-internal',
  catalog_version: '2026.08.24.1',
  catalog_signed_payload_sha256: 'a'.repeat(64)
});

function common(kind) {
  return {
    schema_version: '3.0',
    kind,
    title: '目标监控',
    catalog_binding: catalogBinding,
    model_profile_key: 'object_detection',
    package_id: 'efficientdet_lite2_object_v1',
    intent_key: 'object.common.apple'
  };
}

test('the three monitor proposal branches are strict and independently valid', () => {
  const proposals = [
    {
      ...common('reference_images'),
      model_profile_key: 'reference_object_matching',
      package_id: 'similarity_mediapipe_mobilenet_v3_large_v1',
      intent_key: 'visual.reference.object',
      target: { mode: 'reference_images', required_image_count: 3 },
      rule: { type: 'target_presence', condition: 'appears', duration_seconds: 1 }
    },
    {
      ...common('visual_description'),
      target: { mode: 'visual_description', target_id: 'apple', display_text: '苹果' },
      rule: { type: 'target_presence', condition: 'remains', duration_seconds: 5 }
    },
    {
      ...common('structured_reading'),
      model_profile_key: 'numeric_display_reading',
      package_id: 'numeric_reader_ppocrv6_medium_v1',
      intent_key: 'reading.digital.threshold',
      target: { mode: 'structured_reading' },
      rule: {
        type: 'reading_threshold',
        condition: 'outside',
        lower_threshold_decimal: '10',
        upper_threshold_decimal: '20',
        duration_seconds: 3
      }
    }
  ];

  for (const proposal of proposals) {
    assert.deepEqual(
      validateAgainstSchema('monitor-configuration-proposal', proposal).errors,
      [],
      proposal.kind
    );
  }
});

test('proposal branches reject unknown fields, cross-kind targets, and unsupported durations', () => {
  const valid = {
    ...common('visual_description'),
    target: { mode: 'visual_description', target_id: 'apple', display_text: '苹果' },
    rule: { type: 'target_presence', condition: 'appears', duration_seconds: 1 }
  };

  const unknown = structuredClone(valid);
  unknown.runtime_url = 'https://example.invalid/model.tflite';
  assert.ok(validateAgainstSchema('monitor-configuration-proposal', unknown).errors.some(
    (error) => error.code === 'additional_property'
  ));

  const wrongTarget = structuredClone(valid);
  wrongTarget.target = { mode: 'reference_images', required_image_count: 3 };
  assert.equal(validateAgainstSchema('monitor-configuration-proposal', wrongTarget).ok, false);

  const unsupportedDuration = structuredClone(valid);
  unsupportedDuration.rule.duration_seconds = 61;
  assert.equal(validateAgainstSchema('monitor-configuration-proposal', unsupportedDuration).ok, false);
});

test('reading proposals require canonical decimal strings and an exact rule shape', () => {
  const proposal = {
    ...common('structured_reading'),
    model_profile_key: 'numeric_display_reading',
    package_id: 'numeric_reader_ppocrv6_medium_v1',
    intent_key: 'reading.digital.threshold',
    target: { mode: 'structured_reading' },
    rule: {
      type: 'reading_threshold',
      condition: 'above',
      threshold_decimal: '12.5',
      duration_seconds: 1
    }
  };
  assert.equal(validateAgainstSchema('monitor-configuration-proposal', proposal).ok, true);

  proposal.rule.threshold_decimal = '012.5';
  assert.equal(validateAgainstSchema('monitor-configuration-proposal', proposal).ok, false);
  proposal.rule.threshold_decimal = '12.5';
  proposal.rule.cooldown_ms = 0;
  assert.ok(validateAgainstSchema('monitor-configuration-proposal', proposal).errors.some(
    (error) => error.code === 'additional_property'
  ));
});
