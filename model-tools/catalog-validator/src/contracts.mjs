import { createHash } from 'node:crypto';
import { validateAgainstSchema } from './schema-validator.mjs';

const SHA_256 = /^[0-9a-f]{64}$/;
const SUPPORTED_LOCALES = new Set(['en', 'zh-Hans', 'zh-Hant', 'ja', 'ko', 'es', 'fr', 'de', 'pt-BR']);
const PUBLISHED_PREBUILT_EXPORT_TOOL_NOT_DISCLOSED =
  'published-prebuilt-export-tool-not-disclosed';
const UNKNOWN_EXPORT_TOOL_MARKER =
  /(?:^|[-./])(?:unknown|undisclosed|unspecified|unavailable|pending|tbd)(?:$|[-./])|(?:^|[-./])not-(?:disclosed|available)(?:$|[-./])/;
const INTENT_PATTERN = /^[a-z0-9][a-z0-9_-]*(?:\.[a-z0-9][a-z0-9_-]*)*(?:\.\*)?$/;
const ctcScoreSemantics = new Set([
  'probabilities_v1',
  'unnormalized_logits_softmax_v1'
]);

const familyPolicy = Object.freeze({
  object_detection_v1: Object.freeze({
    supportedTasks: new Set(['visual_target', 'visible_state']),
    promptModes: new Set(['object_class']),
    outputSchemaIds: new Set(['object_detection_v1'])
  }),
  similarity_match_v1: Object.freeze({
    supportedTasks: new Set(['visual_target', 'visible_state']),
    promptModes: new Set(['reference_images']),
    outputSchemaIds: new Set(['similarity_match_v1'])
  }),
  reading_pipeline_v1: Object.freeze({
    supportedTasks: new Set(['structured_reading']),
    promptModes: new Set(['none']),
    outputSchemaIds: new Set(['structured_reading_v2'])
  })
});

function issue(path, code, message) {
  return { path, code, message };
}

function duplicateValues(values) {
  const seen = new Set();
  const duplicates = new Set();
  for (const value of values) {
    if (seen.has(value)) duplicates.add(value);
    seen.add(value);
  }
  return [...duplicates];
}


function validFixedHttpsUrl(value) {
  try {
    const parsed = new URL(value);
    return parsed.protocol === 'https:' && parsed.username === '' && parsed.password === '' &&
      parsed.hash === '' && parsed.toString() === value;
  } catch {
    return false;
  }
}

export function intentPatternMatches(pattern, intentKey) {
  if (!INTENT_PATTERN.test(pattern) || !INTENT_PATTERN.test(intentKey) || intentKey.endsWith('.*')) {
    return false;
  }
  return pattern.endsWith('.*')
    ? intentKey.startsWith(pattern.slice(0, -1))
    : intentKey === pattern;
}

function sameArray(left, right) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function sameStringSet(left, right) {
  return left.length === right.length && left.every((value) => right.includes(value));
}

function validateExportToolSource(manifest) {
  const errors = [];
  const source = manifest.export_tool_source;
  const version = source.version;
  if (version === PUBLISHED_PREBUILT_EXPORT_TOOL_NOT_DISCLOSED) {
    if (source.url !== manifest.model_source.url && source.url !== manifest.weights_source.url) {
      errors.push(issue(
        '$/export_tool_source/url',
        'prebuilt_export_source_mismatch',
        'an undisclosed publisher-prebuilt export must use the pinned model or weights publication URL'
      ));
    }
    return errors;
  }

  const normalized = version.trim().toLowerCase().replace(/[\s_]+/g, '-');
  if (
    UNKNOWN_EXPORT_TOOL_MARKER.test(normalized) ||
    ['n/a', 'na', 'none', 'null'].includes(normalized)
  ) {
    errors.push(issue(
      '$/export_tool_source/version',
      'export_tool_unknown_sentinel_invalid',
      `unknown export tooling must use exactly ${PUBLISHED_PREBUILT_EXPORT_TOOL_NOT_DISCLOSED}`
    ));
  }
  return errors;
}

function validateConfirmedReadingFormat(format, path) {
  const errors = [];
  // Shape/required-field failures belong to the JSON Schema result. Semantic validation only
  // runs once this structured field is actually present (mutated target modes may omit it).
  if (format === null || typeof format !== 'object') return errors;
  if (format.kind === 'time') {
    if (format.fractional_digits !== 0 || ![2, 3].includes(format.time_segments) || format.unit !== null) {
      errors.push(issue(
        path,
        'confirmed_time_format_invalid',
        'time readings require zero fractional digits, two or three segments, and no unit'
      ));
    }
  } else if (format.time_segments !== null) {
    errors.push(issue(
      `${path}/time_segments`,
      'confirmed_non_time_segments_invalid',
      'non-time readings must not declare time segments'
    ));
  }
  if (format.kind === 'percent' && format.unit !== '%') {
    errors.push(issue(
      `${path}/unit`,
      'confirmed_percent_unit_invalid',
      'percent readings require the percent unit'
    ));
  }
  return errors;
}

function validateImageInput(input, path) {
  const errors = [];
  if (input.layout === null || input.color_space === null || input.runtime_shape.length !== 4) {
    errors.push(issue(
      path,
      'image_tensor_metadata_required',
      'image inputs require layout, color_space, and a rank-4 runtime_shape'
    ));
    return errors;
  }
  const expectedChannels = input.color_space === 'GRAY' ? 1 : 3;
  const actualChannels = input.layout === 'NHWC'
    ? input.runtime_shape[3]
    : input.runtime_shape[1];
  if (actualChannels !== expectedChannels) {
    errors.push(issue(
      `${path}/runtime_shape`,
      'input_color_channels_mismatch',
      `${input.color_space} requires ${expectedChannels} channel(s)`
    ));
  }
  return errors;
}

const mediaTypeByRuntime = Object.freeze({
  litert: 'application/vnd.google.litert',
  onnx: 'application/onnx',
  classic_vision: 'application/vnd.beyoureyes.classic-vision+json',
  ctc_vocabulary: 'application/vnd.beyoureyes.ctc-vocabulary+json'
});

const sidecarRuntimes = new Set(['ctc_vocabulary']);
const readingKnownAnswerExecutableRuntimes = new Set(['litert', 'onnx']);

const runtimeOnlyInputRoles = new Set([
  'image',
  'reference_images',
  'reading_task_spec'
]);

const imageInputRoles = new Set(['image', 'image_tensor', 'reference_images']);

const bindingRequiredInputRoles = new Set([
  'image_tensor',
  'image_features',
  'detection_boxes',
  'detection_classes',
  'detection_scores',
  'detection_count'
]);
const referenceTargetInputRoles = new Set(['reference_images', 'reference_features']);
const modelPromptInputRoles = new Set([...referenceTargetInputRoles]);

const bindableOutputRoles = new Set([
  'image_tensor',
  'image_features',
  'reference_features',
  'detection_boxes',
  'detection_classes',
  'detection_scores',
  'detection_count'
]);
const finalOutputRoles = new Set([
  'detection_boxes',
  'detection_classes',
  'detection_scores',
  'detection_count',
  'ctc_logits',
  'similarity_scores'
]);

function validateComponentQuantization(input, path) {
  const errors = [];
  const quantized = input.dtype === 'uint8' || input.dtype === 'int8';
  if (quantized && input.quantization.mode !== 'per_tensor') {
    errors.push(issue(`${path}/quantization/mode`, 'input_quantization_required', `${input.dtype} input requires explicit per_tensor quantization`));
  }
  if (!quantized && input.quantization.mode !== 'none') {
    errors.push(issue(`${path}/quantization/mode`, 'input_quantization_forbidden', `${input.dtype} input requires quantization mode none`));
  }
  if (input.quantization.mode === 'per_tensor') {
    const [minimum, maximum] = input.dtype === 'uint8' ? [0, 255] : [-128, 127];
    if (input.quantization.zero_point < minimum || input.quantization.zero_point > maximum) {
      errors.push(issue(`${path}/quantization/zero_point`, 'quantization_zero_point_out_of_range', `zero_point must fit the ${input.dtype} range ${minimum}..${maximum}`));
    }
  }
  return errors;
}

function validateArtifactAndInputSet(manifest) {
  const errors = [];
  const artifactsByRole = new Map();
  manifest.artifacts.forEach((artifact, index) => {
    const path = `$/artifacts/${index}`;
    if (artifactsByRole.has(artifact.role)) {
      errors.push(issue('$/artifacts', 'duplicate_artifact_role', `duplicate artifact role ${artifact.role}`));
    }
    artifactsByRole.set(artifact.role, artifact);
    if (artifact.media_type !== mediaTypeByRuntime[artifact.runtime]) {
      errors.push(issue(`${path}/media_type`, 'artifact_component_runtime_mismatch', `${artifact.runtime} requires ${mediaTypeByRuntime[artifact.runtime]}`));
    }
  });
  const primary = artifactsByRole.get('primary');
  if (!primary) {
    errors.push(issue('$/artifacts', 'primary_artifact_missing', 'artifacts must contain exactly one role=primary component'));
  }

  const inputKeys = [];
  const inputsByEndpoint = new Map();
  manifest.inputs.forEach((input, index) => {
    const path = `$/inputs/${index}`;
    const endpoint = `${input.artifact_role}:${input.tensor_name}`;
    inputKeys.push(`${input.artifact_role}:${input.tensor_index}`);
    if (inputsByEndpoint.has(endpoint)) {
      errors.push(issue('$/inputs', 'duplicate_artifact_tensor_name', `duplicate input endpoint ${endpoint}`));
    }
    inputsByEndpoint.set(endpoint, { ...input, index });
    if (!artifactsByRole.has(input.artifact_role)) {
      errors.push(issue(`${path}/artifact_role`, 'input_artifact_missing', `unknown artifact role ${input.artifact_role}`));
    }
    errors.push(...validateComponentQuantization(input, path));
    if (imageInputRoles.has(input.role)) {
      errors.push(...validateImageInput(input, path));
    } else if (input.layout !== null || input.color_space !== null) {
      errors.push(issue(path, 'non_image_tensor_metadata_forbidden', 'non-image inputs require null layout and color_space'));
    }
  });
  for (const duplicate of duplicateValues(inputKeys)) {
    errors.push(issue('$/inputs', 'duplicate_artifact_tensor_index', `duplicate artifact/tensor identity ${duplicate}`));
  }
  const primaryImages = manifest.inputs.filter((input) => input.role === 'image' && input.artifact_role === 'primary');
  if (primaryImages.length !== 1) {
    errors.push(issue('$/inputs', 'primary_image_input_missing', 'inputs must contain role=image for artifact_role=primary'));
  }

  const outputsByEndpoint = new Map();
  const outputsByRole = new Map();
  const outputIndexKeys = [];
  manifest.outputs.forEach((output, index) => {
    const path = `$/outputs/${index}`;
    const endpoint = `${output.artifact_role}:${output.tensor_name}`;
    outputIndexKeys.push(`${output.artifact_role}:${output.tensor_index}`);
    if (!artifactsByRole.has(output.artifact_role)) {
      errors.push(issue(`${path}/artifact_role`, 'output_artifact_missing', `unknown artifact role ${output.artifact_role}`));
    }
    if (outputsByEndpoint.has(endpoint)) {
      errors.push(issue('$/outputs', 'duplicate_artifact_output_name', `duplicate output endpoint ${endpoint}`));
    }
    if (outputsByRole.has(output.role)) {
      errors.push(issue('$/outputs', 'duplicate_output_role', `duplicate output role ${output.role}`));
    }
    outputsByEndpoint.set(endpoint, { ...output, index });
    outputsByRole.set(output.role, { ...output, index });
  });
  for (const duplicate of duplicateValues(outputIndexKeys)) {
    errors.push(issue('$/outputs', 'duplicate_artifact_output_index', `duplicate artifact/output identity ${duplicate}`));
  }

  const bindingTargets = new Set();
  const bindingKeys = new Set();
  const boundSourceEndpoints = new Set();
  const executableArtifacts = manifest.artifacts.filter((artifact) => !sidecarRuntimes.has(artifact.runtime));
  const executableRoles = new Set(executableArtifacts.map((artifact) => artifact.role));
  const graph = new Map(executableArtifacts.map((artifact) => [artifact.role, new Set()]));
  const indegree = new Map(executableArtifacts.map((artifact) => [artifact.role, 0]));
  manifest.bindings.forEach((binding, index) => {
    const path = `$/bindings/${index}`;
    const sourceEndpoint = `${binding.source_artifact_role}:${binding.source_tensor_name}`;
    const targetEndpoint = `${binding.target_artifact_role}:${binding.target_tensor_name}`;
    const bindingKey = `${sourceEndpoint}->${targetEndpoint}`;
    const source = outputsByEndpoint.get(sourceEndpoint);
    const target = inputsByEndpoint.get(targetEndpoint);
    if (bindingKeys.has(bindingKey)) {
      errors.push(issue('$/bindings', 'duplicate_binding', `duplicate binding ${bindingKey}`));
    }
    bindingKeys.add(bindingKey);
    if (!source) {
      errors.push(issue(`${path}/source_tensor_name`, 'binding_source_missing', `unknown output endpoint ${sourceEndpoint}`));
    }
    if (!target) {
      errors.push(issue(`${path}/target_tensor_name`, 'binding_target_missing', `unknown input endpoint ${targetEndpoint}`));
    }
    if (bindingTargets.has(targetEndpoint)) {
      errors.push(issue(`${path}/target_tensor_name`, 'binding_target_ambiguous', `input endpoint ${targetEndpoint} has more than one source`));
    }
    bindingTargets.add(targetEndpoint);
    if (source && target) {
      if (!executableRoles.has(binding.source_artifact_role) || !executableRoles.has(binding.target_artifact_role)) {
        errors.push(issue(path, 'binding_sidecar_forbidden', 'sidecar artifacts cannot participate in tensor bindings'));
      }
      if (!bindableOutputRoles.has(source.role) || source.role !== target.role) {
        errors.push(issue(path, 'binding_role_mismatch', 'bindings must connect matching internal tensor roles'));
      }
      if (source.dtype !== target.dtype || !sameArray(source.runtime_shape, target.runtime_shape)) {
        errors.push(issue(path, 'binding_tensor_mismatch', 'bound tensors must have identical dtype and runtime_shape'));
      }
      if (binding.source_artifact_role === binding.target_artifact_role) {
        errors.push(issue(path, 'binding_self_edge', 'a binding must connect two different artifacts'));
      } else if (graph.has(binding.source_artifact_role) && graph.has(binding.target_artifact_role)) {
        const edges = graph.get(binding.source_artifact_role);
        if (!edges.has(binding.target_artifact_role)) {
          edges.add(binding.target_artifact_role);
          indegree.set(binding.target_artifact_role, indegree.get(binding.target_artifact_role) + 1);
        }
      }
      boundSourceEndpoints.add(sourceEndpoint);
    }
  });

  manifest.inputs.forEach((input, index) => {
    const endpoint = `${input.artifact_role}:${input.tensor_name}`;
    if (bindingRequiredInputRoles.has(input.role) && !bindingTargets.has(endpoint)) {
      errors.push(issue(`$/inputs/${index}`, 'internal_input_unbound', `internal input ${endpoint} requires exactly one binding`));
    }
    if (runtimeOnlyInputRoles.has(input.role) && bindingTargets.has(endpoint)) {
      errors.push(issue(`$/inputs/${index}`, 'external_input_bound', `external input ${endpoint} must be supplied by the runtime`));
    }
  });
  const runtimeSuppliedInputs = manifest.inputs.filter((input) =>
    !bindingTargets.has(`${input.artifact_role}:${input.tensor_name}`)
  );
  if (
    manifest.prompt_modes.includes('reference_images') &&
    !runtimeSuppliedInputs.some((input) => referenceTargetInputRoles.has(input.role))
  ) {
    errors.push(issue('$/prompt_modes', 'reference_target_dependency_missing', 'prompt mode reference_images requires a runtime-supplied reference_images or reference_features input'));
  }
  if (
    !manifest.prompt_modes.includes('reference_images') &&
    runtimeSuppliedInputs.some((input) => referenceTargetInputRoles.has(input.role))
  ) {
    errors.push(issue('$/inputs', 'reference_target_dependency_forbidden', 'a runtime-supplied reference target input requires prompt mode reference_images'));
  }

  manifest.outputs.forEach((output, index) => {
    const endpoint = `${output.artifact_role}:${output.tensor_name}`;
    const runtimeConsumedReadingOutput = manifest.runtime_family === 'reading_pipeline_v1' &&
      output.role === 'text_probability_map';
    if (!boundSourceEndpoints.has(endpoint) && !finalOutputRoles.has(output.role) &&
        !runtimeConsumedReadingOutput) {
      errors.push(issue(`$/outputs/${index}`, 'internal_output_unbound', `internal output role ${output.role} must feed another artifact`));
    }
  });

  const rootRoles = new Set([...indegree.entries()].filter(([, degree]) => degree === 0).map(([role]) => role));
  const queue = [...rootRoles].toSorted();
  const visited = [];
  while (queue.length > 0) {
    const role = queue.shift();
    visited.push(role);
    for (const next of [...graph.get(role)].toSorted()) {
      indegree.set(next, indegree.get(next) - 1);
      if (indegree.get(next) === 0) queue.push(next);
    }
    queue.sort();
  }
  if (visited.length !== executableArtifacts.length) {
    errors.push(issue('$/bindings', 'execution_graph_cycle', 'artifact bindings must form an acyclic graph'));
  }
  for (const role of visited) {
    const artifactInputs = manifest.inputs.filter((input) => input.artifact_role === role);
    const artifactOutputs = manifest.outputs.filter((output) => output.artifact_role === role);
    if (artifactInputs.length === 0 || artifactOutputs.length === 0) {
      errors.push(issue('$/artifacts', 'artifact_graph_disconnected', `artifact ${role} must declare at least one input and output`));
    }
    if (rootRoles.has(role) && !artifactInputs.some((input) =>
      !bindingTargets.has(`${input.artifact_role}:${input.tensor_name}`)
    )) {
      errors.push(issue('$/artifacts', 'artifact_root_external_input_missing', `root artifact ${role} requires an external input`));
    }
  }
  const sidecarArtifacts = manifest.artifacts.filter((artifact) => sidecarRuntimes.has(artifact.runtime));
  for (const artifact of sidecarArtifacts) {
    if (manifest.inputs.some((input) => input.artifact_role === artifact.role) ||
        manifest.outputs.some((output) => output.artifact_role === artifact.role)) {
      errors.push(issue('$/artifacts', 'sidecar_tensor_forbidden', `sidecar ${artifact.role} cannot declare model tensors`));
    }
  }
  return errors;
}

function finalOutputs(manifest) {
  const boundSources = new Set(manifest.bindings.map((binding) =>
    `${binding.source_artifact_role}:${binding.source_tensor_name}`
  ));
  return manifest.outputs.filter((output) =>
    !boundSources.has(`${output.artifact_role}:${output.tensor_name}`) &&
      finalOutputRoles.has(output.role)
  );
}

function validateObjectDetectionOutput(manifest) {
  const errors = [];
  const outputs = finalOutputs(manifest);
  const tensorsBySemantic = new Map(outputs.map((tensor) => [tensor.role, tensor]));
  const expectedSemantics = [
    'detection_boxes',
    'detection_classes',
    'detection_scores',
    'detection_count'
  ];
  if (
    outputs.length !== expectedSemantics.length ||
    expectedSemantics.some((semantic) => !tensorsBySemantic.has(semantic))
  ) {
    errors.push(issue(
      '$/outputs',
      'object_detection_tensor_semantics_mismatch',
      `object_detection_v1 requires exactly ${expectedSemantics.join(', ')}`
    ));
    return errors;
  }

  const boxes = tensorsBySemantic.get('detection_boxes');
  const classes = tensorsBySemantic.get('detection_classes');
  const scores = tensorsBySemantic.get('detection_scores');
  const count = tensorsBySemantic.get('detection_count');
  const maximum = boxes.runtime_shape[1];
  if (
    !sameArray(boxes.runtime_shape, [1, maximum, 4]) ||
    !sameArray(classes.runtime_shape, [1, maximum]) ||
    !sameArray(scores.runtime_shape, [1, maximum]) ||
    !sameArray(count.runtime_shape, [1])
  ) {
    errors.push(issue(
      '$/outputs',
      'object_detection_tensor_shape_mismatch',
      'boxes/classes/scores/count runtime shapes must be [1,N,4], [1,N], [1,N], and [1] with the same N'
    ));
  }
  if (outputs.some((tensor) => tensor.dtype !== 'float32')) {
    errors.push(issue(
      '$/outputs',
      'object_detection_tensor_dtype_mismatch',
      'object_detection_v1 output tensors must all be float32'
    ));
  }
  const contract = manifest.adapter_contract;
  if (
    contract.embedded_postprocess?.max_detections !== null &&
    contract.embedded_postprocess?.max_detections !== undefined &&
    contract.embedded_postprocess.max_detections !== maximum
  ) {
    errors.push(issue(
      '$/adapter_contract/embedded_postprocess/max_detections',
      'postprocess_max_detections_mismatch',
      'max_detections must equal the N dimension of detection output tensors'
    ));
  }
  return errors;
}

function normalizeClassMapName(value) {
  return value.normalize('NFKC').trim().replace(/\s+/gu, ' ').toLocaleLowerCase('en-US');
}

function validateObjectClassMap(manifest) {
  if (manifest.runtime_family !== 'object_detection_v1') return [];
  const errors = [];
  const classMap = manifest.adapter_contract.class_map;
  if (classMap === null) {
    return [issue(
      '$/adapter_contract/class_map',
      'object_detection_class_map_missing',
      'object_detection_v1 requires a signed finite bilingual class map'
    )];
  }
  if (classMap.class_id_base !== 0 || classMap.targets.length === 0) {
    errors.push(issue(
      '$/adapter_contract/class_map',
      'object_detection_class_map_invalid',
      'object_detection_v1 class maps must use a zero-based non-empty target table'
    ));
  }
  const rawIds = new Set();
  const targetIds = new Set();
  const names = new Map();
  classMap.targets.forEach((target, index) => {
    const path = `$/adapter_contract/class_map/targets/${index}`;
    if (rawIds.has(target.raw_class_id)) {
      errors.push(issue(`${path}/raw_class_id`, 'object_detection_duplicate_raw_class_id', 'raw class IDs must be unique'));
    }
    rawIds.add(target.raw_class_id);
    if (targetIds.has(target.target_id)) {
      errors.push(issue(`${path}/target_id`, 'object_detection_duplicate_target_id', 'target IDs must be unique'));
    }
    targetIds.add(target.target_id);
    const labels = target.labels ?? {};
    for (const [locale, label] of Object.entries(labels)) {
      if (!SUPPORTED_LOCALES.has(locale)) {
        errors.push(issue(`${path}/labels/${locale}`, 'object_detection_label_locale_invalid', 'localized class-map labels must use one of the nine supported locale tags'));
      }
      if (typeof label !== 'string' || label.trim().length === 0 || label.length > 40) {
        errors.push(issue(`${path}/labels/${locale}`, 'object_detection_label_invalid', 'localized class-map labels must be non-empty strings of at most 40 characters'));
      }
    }
    const values = [target.label_zh_cn, target.label_en, ...Object.values(labels), ...target.aliases];
    for (const value of values) {
      const normalized = normalizeClassMapName(value);
      const previous = names.get(normalized);
      if (previous !== undefined && previous !== target.target_id) {
        errors.push(issue(`${path}/aliases`, 'object_detection_alias_conflict', `class-map name ${value} is assigned to multiple target IDs`));
      }
      names.set(normalized, target.target_id);
    }
  });
  return errors;
}

function validateReadingKnownAnswer(manifest) {
  const errors = [];
  const selfTest = manifest.self_test;
  if (manifest.runtime_family !== 'reading_pipeline_v1') {
    if (selfTest !== undefined) {
      errors.push(issue('$/self_test', 'reading_known_answer_forbidden', 'known-answer reading self-test is reserved for reading_pipeline_v1'));
    }
    return errors;
  }
  if (selfTest === undefined) {
    errors.push(issue('$/self_test', 'reading_known_answer_missing', 'reading packages require one signed known-answer vector before activation'));
    return errors;
  }
  if (selfTest.artifact_role !== 'primary') {
    errors.push(issue('$/self_test/artifact_role', 'reading_known_answer_artifact_role', 'reading_known_answer_v1 must bind artifact_role=primary'));
  }
  const artifact = manifest.artifacts.find((candidate) => candidate.role === 'primary');
  if (artifact === undefined || artifact.sha256 !== selfTest.artifact_sha256) {
    errors.push(issue('$/self_test/artifact_sha256', 'reading_known_answer_artifact_mismatch', 'known-answer vector must bind the primary artifact and exact SHA-256'));
  }
  if (artifact !== undefined && !readingKnownAnswerExecutableRuntimes.has(artifact.runtime)) {
    errors.push(issue('$/artifacts', 'reading_known_answer_primary_runtime', 'reading_known_answer_v1 primary runtime must be litert or onnx'));
  }
  let bytes;
  try {
    bytes = Buffer.from(selfTest.input.rgb888_base64, 'base64');
    if (bytes.toString('base64') !== selfTest.input.rgb888_base64) throw new Error('non-canonical base64');
  } catch {
    errors.push(issue('$/self_test/input/rgb888_base64', 'reading_known_answer_input_invalid', 'known-answer RGB bytes must use canonical base64'));
    return errors;
  }
  if (bytes.length !== selfTest.input.width * selfTest.input.height * 3) {
    errors.push(issue('$/self_test/input/rgb888_base64', 'reading_known_answer_input_size_mismatch', 'RGB888 bytes must equal width * height * 3'));
  }
  if (createHash('sha256').update(bytes).digest('hex') !== selfTest.input.sha256) {
    errors.push(issue('$/self_test/input/sha256', 'reading_known_answer_input_hash_mismatch', 'known-answer RGB bytes must match the signed SHA-256'));
  }
  if (selfTest.expected.value_decimal === '-0' || selfTest.expected.value_decimal.startsWith('-0.')) {
    errors.push(issue('$/self_test/expected/value_decimal', 'reading_known_answer_decimal_invalid', 'known-answer decimal must be canonical and cannot encode negative zero'));
  }
  return errors;
}

function validateFamilyOutput(manifest) {
  const errors = [];
  const output = manifest.adapter_contract;
  const outputs = finalOutputs(manifest);
  const policy = familyPolicy[manifest.runtime_family];
  errors.push(...validateReadingKnownAnswer(manifest));
  if (
    manifest.runtime_family !== 'reading_pipeline_v1' &&
    (manifest.ctc_decoding !== undefined || manifest.artifacts.some((artifact) => artifact.runtime === 'ctc_vocabulary'))
  ) {
    errors.push(issue('$/ctc_decoding', 'ctc_decoding_forbidden', 'CTC vocabulary and decoding contracts are reserved for reading_pipeline_v1'));
  }
  if (!policy.outputSchemaIds.has(output.schema_id)) {
    errors.push(issue(
      '$/adapter_contract/schema_id',
      'runtime_family_output_mismatch',
      `${manifest.runtime_family} does not accept ${output.schema_id}`
    ));
    return errors;
  }
  if (manifest.runtime_family === 'object_detection_v1') {
    errors.push(...validateObjectDetectionOutput(manifest));
    errors.push(...validateObjectClassMap(manifest));
  } else if (manifest.runtime_family === 'similarity_match_v1') {
    if (manifest.prompt_modes.includes('reference_images') &&
        manifest.preprocess_id !== 'class_agnostic_localize_letterbox_multi_crop_rgb_v3') {
      errors.push(issue(
        '$/preprocess_id',
        'reference_preprocess_contract_mismatch',
        'reference-image matching requires localized letterbox-crop preprocessing'
      ));
    }
    if (
      Object.hasOwn(manifest.parameter_profile.defaults, 'reference_cohesion_min_cosine') ||
      Object.hasOwn(manifest.parameter_profile.allowed_bounds, 'reference_cohesion_min_cosine')
    ) {
      errors.push(issue(
        '$/parameter_profile',
        'reference_cohesion_parameter_forbidden',
        'all 3 to 20 normalized imported references participate; runtime cohesion filtering is forbidden'
      ));
    }
    const semantics = outputs.map((tensor) => tensor.role);
    if (semantics.length !== 1 || semantics[0] !== 'similarity_scores') {
      errors.push(issue('$/outputs', 'similarity_match_tensor_semantics_mismatch', 'similarity_match_v1 requires exactly similarity_scores'));
    } else {
      const tensor = outputs[0];
      if (tensor.dtype !== 'float32' || tensor.runtime_shape.length !== 2 || tensor.runtime_shape[0] !== 1) {
        errors.push(issue('$/outputs', 'similarity_match_tensor_shape_mismatch', 'similarity scores must be float32 [1,N]'));
      }
    }
    if (output.class_map !== null || output.embedded_postprocess !== null) {
      errors.push(issue('$/adapter_contract', 'similarity_match_detection_metadata_forbidden', 'similarity matching uses Manifest parameters rather than detection metadata'));
    }
    const bindingTargets = new Set(manifest.bindings.map((binding) =>
      `${binding.target_artifact_role}:${binding.target_tensor_name}`
    ));
    const runtimeReferenceFeatures = manifest.inputs.filter((input) =>
      input.role === 'reference_features' &&
      !bindingTargets.has(`${input.artifact_role}:${input.tensor_name}`)
    );
    if (runtimeReferenceFeatures.length > 0) {
      const reference = runtimeReferenceFeatures.length === 1 ? runtimeReferenceFeatures[0] : undefined;
      const imageFeatures = reference === undefined ? undefined : manifest.inputs.filter((input) =>
        input.role === 'image_features' && input.artifact_role === reference.artifact_role
      );
      if (reference === undefined || imageFeatures?.length > 1 ||
          reference.dtype !== 'float32' ||
          reference.runtime_shape.length !== 2 || ![1, 4, 12].includes(reference.runtime_shape[0]) ||
          (imageFeatures?.length === 1 && (
            imageFeatures[0].dtype !== 'float32' ||
            imageFeatures[0].runtime_shape.length !== 2 || imageFeatures[0].runtime_shape[0] !== 4 ||
            reference.runtime_shape[1] !== imageFeatures[0].runtime_shape[1]
          ))) {
        errors.push(issue(
          '$/inputs',
          'reference_prototype_tensor_shape_mismatch',
          'runtime reference features require one float32 [1,N], [4,N], or [12,N] prototype tensor matching one float32 [4,N] candidate-feature input'
        ));
      }
    }
  } else {
    if (manifest.inputs.some((input) => modelPromptInputRoles.has(input.role))) {
      errors.push(issue(
        '$/inputs',
        'reading_model_prompt_input_forbidden',
        'reading_pipeline_v1 accepts automatic image discovery, not text or reference-image model prompts'
      ));
    }
    if (manifest.postprocess_id !== 'structured_reading_ctc_v2') {
      errors.push(issue('$/postprocess_id', 'structured_reading_ctc_adapter_mismatch', 'reading_pipeline_v1 requires structured_reading_ctc_v2'));
    }
    let classCount = null;
    if (outputs.length !== 1 || outputs[0].role !== 'ctc_logits') {
      errors.push(issue('$/outputs', 'numeric_ctc_tensor_semantics_mismatch', 'reading_pipeline_v1 requires exactly one raw ctc_logits tensor'));
    } else {
      const tensor = outputs[0];
      if (tensor.dtype !== 'float32' || tensor.runtime_shape.length !== 3 ||
          tensor.runtime_shape[0] !== 1 || tensor.runtime_shape[1] < 1 ||
          tensor.runtime_shape[2] < 2 || tensor.runtime_shape[2] > 65_536) {
        errors.push(issue('$/outputs', 'numeric_ctc_tensor_shape_mismatch', 'ctc_logits must be float32 [1,T,C] with T >= 1 and 2 <= C <= 65536'));
      } else {
        classCount = tensor.runtime_shape[2];
      }
    }
    const decoding = manifest.ctc_decoding;
    if (decoding === undefined) {
      errors.push(issue('$/ctc_decoding', 'ctc_decoding_missing', 'reading_pipeline_v1 requires a signed vocabulary reference and finite CTC decoding semantics'));
    } else {
      if (!ctcScoreSemantics.has(decoding.score_semantics)) {
        errors.push(issue('$/ctc_decoding/score_semantics', 'ctc_score_semantics_unsupported', 'reading_pipeline_v1 requires one finite signed CTC score interpretation'));
      }
      if (decoding.collapse_semantics !== 'ctc_greedy_argmax_v1') {
        errors.push(issue('$/ctc_decoding/collapse_semantics', 'ctc_collapse_semantics_unsupported', 'structured reading requires full-vocabulary greedy CTC decoding'));
      }
      if (decoding.beam_width !== undefined) {
        errors.push(issue('$/ctc_decoding/beam_width', 'ctc_beam_width_forbidden', 'greedy CTC decoding cannot carry beam_width'));
      }
      const vocabularyArtifacts = manifest.artifacts.filter((artifact) => artifact.runtime === 'ctc_vocabulary');
      const referenced = manifest.artifacts.filter((artifact) => artifact.role === decoding.vocabulary_artifact_role);
      if (referenced.length !== 1) {
        errors.push(issue('$/ctc_decoding/vocabulary_artifact_role', 'ctc_vocabulary_artifact_missing', 'vocabulary_artifact_role must reference exactly one package artifact'));
      } else if (referenced[0].runtime !== 'ctc_vocabulary' ||
          referenced[0].media_type !== 'application/vnd.beyoureyes.ctc-vocabulary+json') {
        errors.push(issue('$/ctc_decoding/vocabulary_artifact_role', 'ctc_vocabulary_artifact_type_mismatch', 'the referenced artifact must be a ctc_vocabulary JSON sidecar'));
      }
      if (vocabularyArtifacts.length !== 1 || vocabularyArtifacts[0].role !== decoding.vocabulary_artifact_role) {
        errors.push(issue('$/artifacts', 'ctc_vocabulary_artifact_set_mismatch', 'reading packages require exactly one referenced CTC vocabulary sidecar'));
      }
      if (classCount !== null && decoding.blank_index >= classCount) {
        errors.push(issue('$/ctc_decoding/blank_index', 'ctc_blank_index_out_of_range', 'blank_index must address the final C dimension of ctc_logits'));
      }
    }
    if (output.class_map !== null || output.embedded_postprocess !== null) {
      errors.push(issue('$/adapter_contract', 'numeric_reading_detection_metadata_forbidden', 'reading output does not use detection class-map or NMS metadata'));
    }
    const locatorArtifacts = manifest.artifacts.filter((artifact) => artifact.role === 'locator');
    const locatorInputs = manifest.inputs.filter((input) =>
      input.artifact_role === 'locator' && input.role === 'image'
    );
    const locatorOutputs = manifest.outputs.filter((tensor) =>
      tensor.artifact_role === 'locator' && tensor.role === 'text_probability_map'
    );
    if (locatorArtifacts.length !== 1 || sidecarRuntimes.has(locatorArtifacts[0]?.runtime) ||
        locatorInputs.length !== 1 || locatorOutputs.length !== 1) {
      errors.push(issue('$/artifacts', 'reading_auto_locator_missing', 'reading_pipeline_v1 requires exactly one executable locator image-to-probability-map artifact'));
    } else {
      const input = locatorInputs[0];
      const tensor = locatorOutputs[0];
      const validInput = input.dtype === 'float32' && input.layout === 'NCHW' &&
        input.color_space === 'BGR' && input.runtime_shape.length === 4 &&
        input.runtime_shape[0] === 1 && input.runtime_shape[1] === 3 &&
        input.runtime_shape[2] >= 32 && input.runtime_shape[2] <= 1280 &&
        input.runtime_shape[3] >= 32 && input.runtime_shape[3] <= 1280 &&
        input.runtime_shape[2] % 32 === 0 && input.runtime_shape[3] % 32 === 0;
      const expectedOutput = validInput ?
        [1, 1, input.runtime_shape[2], input.runtime_shape[3]] : [];
      if (manifest.inputs.filter((input) => input.role === 'image').length !== 2 || !validInput ||
          tensor.dtype !== 'float32' ||
          JSON.stringify(tensor.runtime_shape) !== JSON.stringify(expectedOutput)) {
        errors.push(issue('$/inputs', 'reading_auto_locator_tensor_contract_invalid', 'locator must be float32 BGR NCHW image to same-spatial float32 [1,1,H,W] probability map'));
      }
    }
    const locatorParameters = [
      ['locator_pixel_threshold', 0, 1],
      ['locator_box_threshold', 0, 1],
      ['locator_unclip_ratio', 1, 3],
    ];
    for (const [name, minimum, maximum] of locatorParameters) {
      const value = manifest.parameter_profile.defaults[name];
      if (!Number.isFinite(value) || value < minimum || value > maximum) {
        errors.push(issue(`$/parameter_profile/defaults/${name}`, 'reading_auto_locator_parameter_invalid', `${name} must stay within the reading locator family range`));
      }
    }
    const maximumCandidates = manifest.parameter_profile.defaults.locator_max_candidates;
    if (!Number.isSafeInteger(maximumCandidates) || maximumCandidates < 1 || maximumCandidates > 3000) {
      errors.push(issue('$/parameter_profile/defaults/locator_max_candidates', 'reading_auto_locator_max_candidates_invalid', 'locator_max_candidates must be an integer from 1 through 3000'));
    }
    const minimumConfidence = manifest.parameter_profile.defaults.minimum_confidence;
    if (minimumConfidence === undefined) {
      errors.push(issue('$/parameter_profile/defaults/minimum_confidence', 'reading_minimum_confidence_missing', 'reading_pipeline_v1 requires a signed minimum confidence'));
    } else if (!Number.isFinite(minimumConfidence) || minimumConfidence < 0 || minimumConfidence > 1) {
      errors.push(issue('$/parameter_profile/defaults/minimum_confidence', 'reading_minimum_confidence_invalid', 'reading minimum confidence must be between 0 and 1'));
    }
  }
  return errors;
}

function validateParameterProfile(profile, { requireResolvedSampling = false } = {}) {
  const errors = [];
  const defaultKeys = Object.keys(profile.defaults);
  const boundKeys = Object.keys(profile.allowed_bounds);
  for (const key of defaultKeys) {
    const path = `$/parameter_profile/defaults/${key}`;
    const bounds = profile.allowed_bounds[key];
    if (!bounds) {
      errors.push(issue(path, 'parameter_bounds_missing', `default parameter ${key} requires allowed_bounds`));
      continue;
    }
    if (bounds.minimum > bounds.maximum) {
      errors.push(issue(`$/parameter_profile/allowed_bounds/${key}`, 'parameter_bounds_inverted', 'minimum must not exceed maximum'));
    } else if (profile.defaults[key] < bounds.minimum || profile.defaults[key] > bounds.maximum) {
      errors.push(issue(path, 'parameter_default_out_of_bounds', `default parameter ${key} is outside its allowed bounds`));
    }
  }
  for (const key of boundKeys) {
    if (!Object.hasOwn(profile.defaults, key)) {
      errors.push(issue(`$/parameter_profile/allowed_bounds/${key}`, 'parameter_default_missing', `allowed_bounds for ${key} requires a default`));
    }
  }

  const sampling = profile.sampling_policy;
  const values = [sampling.default_interval_ms, sampling.min_interval_ms, sampling.max_interval_ms];
  const nullCount = values.filter((value) => value === null).length;
  if (nullCount !== 0 && nullCount !== values.length) {
    errors.push(issue('$/parameter_profile/sampling_policy', 'sampling_policy_incomplete', 'sampling interval default, minimum, and maximum must all be concrete or all be null'));
  }
  if (nullCount === 0 && !(sampling.min_interval_ms <= sampling.default_interval_ms && sampling.default_interval_ms <= sampling.max_interval_ms)) {
    errors.push(issue('$/parameter_profile/sampling_policy', 'sampling_policy_range_invalid', 'must satisfy min_interval_ms <= default_interval_ms <= max_interval_ms'));
  }
  if (requireResolvedSampling && nullCount !== 0) {
    errors.push(issue('$/parameter_profile/sampling_policy', 'sampling_policy_unknown', 'release packages require concrete default and allowed sampling bounds'));
  }
  return errors;
}

function validateCommercialDetectionMetadata(manifest) {
  if (manifest.runtime_family !== 'object_detection_v1') return [];
  const output = manifest.adapter_contract;
  const errors = [];
  const postprocess = output.embedded_postprocess;
  if (postprocess === null) {
    errors.push(issue('$/adapter_contract/embedded_postprocess', 'embedded_postprocess_unknown', 'commercial object detector packages require exact embedded postprocess metadata'));
    return errors;
  }
  if (postprocess.max_detections === null) {
    errors.push(issue('$/adapter_contract/embedded_postprocess/max_detections', 'postprocess_max_detections_unknown', 'commercial object detector packages require max_detections'));
  }
  if (postprocess.score_threshold === null) {
    errors.push(issue('$/adapter_contract/embedded_postprocess/score_threshold', 'postprocess_score_threshold_unknown', 'commercial object detector packages require score_threshold'));
  }
  if (postprocess.nms_iou_threshold === null) {
    errors.push(issue('$/adapter_contract/embedded_postprocess/nms_iou_threshold', 'postprocess_nms_threshold_unknown', 'commercial object detector packages require nms_iou_threshold'));
  }
  return errors;
}

function validatePositiveAreaNormalizedRoi(roi, path) {
  if (roi.left < roi.right && roi.top < roi.bottom) return [];
  return [issue(path, 'invalid_roi', 'left must be less than right and top must be less than bottom')];
}

function validateCanonicalDecimal(value, path) {
  if (typeof value === 'string' && /^-0(?:\.0+)?$/.test(value)) {
    return [issue(path, 'noncanonical_decimal', 'negative zero is not a canonical decimal string')];
  }
  return [];
}

function compareCanonicalDecimals(left, right) {
  const scaled = (value, scale) => {
    const negative = value.startsWith('-');
    const unsigned = negative ? value.slice(1) : value;
    const [whole, fraction = ''] = unsigned.split('.');
    const magnitude = BigInt(`${whole}${fraction.padEnd(scale, '0')}`);
    return negative ? -magnitude : magnitude;
  };
  const leftScale = left.includes('.') ? left.length - left.indexOf('.') - 1 : 0;
  const rightScale = right.includes('.') ? right.length - right.indexOf('.') - 1 : 0;
  const scale = Math.max(leftScale, rightScale);
  const leftValue = scaled(left, scale);
  const rightValue = scaled(right, scale);
  return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
}

export function validateCatalog(catalog) {
  const schema = validateAgainstSchema('capability-catalog', catalog);
  if (!schema.ok) return schema;
  const errors = [];

  for (const duplicate of duplicateValues(catalog.capabilities.map((item) => item.capability_id))) {
    errors.push(issue('$/capabilities', 'duplicate_capability', `duplicate capability_id ${duplicate}`));
  }
  for (const duplicate of duplicateValues(catalog.operational_capabilities.map((item) => item.capability_key))) {
    errors.push(issue('$/operational_capabilities', 'duplicate_operational_capability', `duplicate capability_key ${duplicate}`));
  }
  for (const duplicate of duplicateValues(catalog.runtime_recipes.map((item) => item.recipe_id))) {
    errors.push(issue('$/runtime_recipes', 'duplicate_recipe', `duplicate recipe_id ${duplicate}`));
  }
  for (const duplicate of duplicateValues(catalog.packages.map((item) => item.package_id))) {
    errors.push(issue('$/packages', 'duplicate_package', `duplicate package_id ${duplicate}`));
  }

  const capabilities = new Map(catalog.capabilities.map((item) => [item.capability_id, item]));
  const recipes = new Map(catalog.runtime_recipes.map((item) => [item.recipe_id, item]));
  const packageEntries = new Map(catalog.packages.map((item) => [item.package_id, item]));
  const packages = new Set(packageEntries.keys());

  catalog.capabilities.forEach((capability, index) => {
    const path = `$/capabilities/${index}`;
    const expectedModes = capability.capability_id === 'structured_reading'
      ? ['none']
      : capability.target_modes.filter((mode) => mode !== 'none');
    if (!sameStringSet(capability.target_modes, expectedModes)) {
      errors.push(issue(
        `${path}/target_modes`,
        'capability_target_mode_mismatch',
        capability.capability_id === 'structured_reading'
          ? 'structured_reading requires exactly target mode none'
          : 'visual capabilities cannot use target mode none'
      ));
    }
    if (
      capability.capability_id === 'visual_target' &&
      capability.allowed_rule_types.some((rule) =>
        !['presence_duration', 'absence_duration'].includes(rule)
      )
    ) {
      errors.push(issue(
        `${path}/allowed_rule_types`,
        'visual_target_rule_types_mismatch',
        'visual_target supports only presence_duration and absence_duration'
      ));
    }
    for (const recipeId of capability.recipe_ids) {
      const recipe = recipes.get(recipeId);
      if (!recipe) {
        errors.push(issue(`${path}/recipe_ids`, 'missing_recipe', `unknown recipe_id ${recipeId}`));
      } else if (recipe.capability_id !== capability.capability_id) {
        errors.push(issue(`${path}/recipe_ids`, 'recipe_capability_mismatch', `${recipeId} belongs to ${recipe.capability_id}`));
      }
    }
  });

  catalog.runtime_recipes.forEach((recipe, index) => {
    const path = `$/runtime_recipes/${index}`;
    const capability = capabilities.get(recipe.capability_id);
    if (!capability) {
      errors.push(issue(`${path}/capability_id`, 'missing_capability', `unknown capability_id ${recipe.capability_id}`));
      return;
    }
    if (!capability.recipe_ids.includes(recipe.recipe_id)) {
      errors.push(issue(path, 'unreferenced_recipe', `${recipe.recipe_id} is not referenced by its capability`));
    }
    for (const mode of recipe.prompt_modes) {
      if (!capability.target_modes.includes(mode)) {
        errors.push(issue(`${path}/prompt_modes`, 'recipe_prompt_mode_mismatch', `${mode} is not enabled by the capability`));
      }
    }
    const family = familyPolicy[recipe.runtime_family];
    if (!family.supportedTasks.has(recipe.capability_id)) {
      errors.push(issue(`${path}/runtime_family`, 'runtime_family_capability_mismatch', `${recipe.runtime_family} cannot implement ${recipe.capability_id}`));
    }
    for (const mode of recipe.prompt_modes) {
      if (!family.promptModes.has(mode)) {
        errors.push(issue(`${path}/prompt_modes`, 'runtime_family_prompt_mode_mismatch', `${recipe.runtime_family} cannot accept ${mode}`));
      }
    }
    for (const packageId of recipe.candidate_package_ids) {
      if (!packages.has(packageId)) {
        errors.push(issue(`${path}/candidate_package_ids`, 'missing_package', `unknown package_id ${packageId}`));
      }
    }
  });

  const expectedOperationalStatus = catalog.build_channel === 'community'
    ? 'community'
    : catalog.build_channel === 'commercial'
    ? 'commercial'
    : catalog.build_channel === 'internal-evaluation'
      ? 'internal-evaluation'
      : undefined;
  const liveOperationalPackageIds = new Set();
  catalog.operational_capabilities.forEach((operational, index) => {
    const path = `$/operational_capabilities/${index}`;
    const targetIds = operational.target_ids ?? [];
    for (const pattern of operational.intent_patterns) {
      if (!INTENT_PATTERN.test(pattern)) {
        errors.push(issue(`${path}/intent_patterns`, 'intent_pattern_invalid', `${pattern} is not a canonical exact or terminal-wildcard intent pattern`));
      }
    }
    if (!capabilities.has(operational.capability_id)) {
      errors.push(issue(`${path}/capability_id`, 'missing_capability', `unknown capability_id ${operational.capability_id}`));
    }
    const recipe = recipes.get(operational.recipe_id);
    if (!recipe || recipe.capability_id !== operational.capability_id) {
      errors.push(issue(`${path}/recipe_id`, 'operational_recipe_mismatch', 'supported model profile requires one matching capability recipe'));
    }
    if (operational.model_card === null) {
      errors.push(issue(`${path}/model_card`, 'operational_model_card_missing', 'supported model profile requires provider model metadata'));
    } else if (!validFixedHttpsUrl(operational.model_card.model_home_url)) {
      errors.push(issue(`${path}/model_card/model_home_url`, 'model_home_url_invalid', 'model home must be a canonical fixed HTTPS URL'));
    }
    if (recipe?.runtime_family === 'object_detection_v1' && targetIds.length === 0) {
      errors.push(issue(`${path}/target_ids`, 'operational_target_coverage_missing', 'object-detection model profiles require a finite target_ids list'));
    } else if (recipe?.runtime_family !== 'object_detection_v1' && targetIds.length !== 0) {
      errors.push(issue(`${path}/target_ids`, 'operational_target_coverage_forbidden', 'only object-detection model profiles may declare target_ids'));
    }
    if (expectedOperationalStatus !== undefined && operational.status !== expectedOperationalStatus) {
      errors.push(issue(`${path}/status`, 'operational_channel_mismatch', `${catalog.build_channel} Catalog cannot activate ${operational.status} capability`));
    }
    if (operational.package_ids.length === 0) {
      errors.push(issue(`${path}/package_ids`, 'operational_capability_without_package', 'supported operational capability requires at least one package'));
    }
    const expectedConclusion = operational.status === 'community'
      ? 'approved-for-community'
      : operational.status === 'commercial'
      ? 'approved-for-commercial'
      : 'approved-for-internal-evaluation';
    if (operational.human_review.license_conclusion !== expectedConclusion) {
      errors.push(issue(`${path}/human_review/license_conclusion`, 'operational_review_mismatch', `${operational.status} capability requires ${expectedConclusion}`));
    }
    for (const packageId of operational.package_ids) {
      const entry = packageEntries.get(packageId);
      if (!entry) {
        errors.push(issue(`${path}/package_ids`, 'missing_package', `unknown package_id ${packageId}`));
        continue;
      }
      if (entry.status !== 'active') {
        errors.push(issue(`${path}/package_ids`, 'operational_package_not_active', `${packageId} is not active`));
      } else {
        liveOperationalPackageIds.add(packageId);
      }
      if (!recipe?.candidate_package_ids.includes(packageId)) {
        errors.push(issue(`${path}/package_ids`, 'operational_package_recipe_mismatch', `${packageId} is not a candidate of ${operational.recipe_id}`));
      }
    }
  });
  catalog.packages.forEach((entry, index) => {
    if (catalog.build_channel !== 'development-no-model' && entry.status === 'active' && !liveOperationalPackageIds.has(entry.package_id)) {
      errors.push(issue(`$/packages/${index}`, 'active_package_not_in_operational_inventory', `${entry.package_id} has no supported operational capability`));
    }
  });

  return { ok: errors.length === 0, errors };
}

export function validateManifest(manifest) {
  const schema = validateAgainstSchema('model-manifest', manifest);
  if (!schema.ok) return schema;
  const errors = [];
  const compatibility = manifest.device_compatibility;
  if (compatibility.max_android_api !== null && compatibility.max_android_api < compatibility.min_android_api) {
    errors.push(issue('$/device_compatibility/max_android_api', 'invalid_api_range', 'must be null or at least min_android_api'));
  }

  const family = familyPolicy[manifest.runtime_family];
  for (const task of manifest.supported_tasks) {
    if (!family.supportedTasks.has(task)) {
      errors.push(issue('$/supported_tasks', 'runtime_family_task_mismatch', `${manifest.runtime_family} cannot implement ${task}`));
    }
  }
  for (const mode of manifest.prompt_modes) {
    if (!family.promptModes.has(mode)) {
      errors.push(issue('$/prompt_modes', 'runtime_family_prompt_mode_mismatch', `${manifest.runtime_family} cannot accept ${mode}`));
    }
  }

  errors.push(...validateArtifactAndInputSet(manifest));
  errors.push(...validateExportToolSource(manifest));
  errors.push(...validateFamilyOutput(manifest));
  errors.push(...validateParameterProfile(manifest.parameter_profile));

  const license = manifest.license;
  const hasGrant = license.license_grant_id !== null;
  const hasExpiry = license.grant_expires_at !== null;
  if (hasGrant !== hasExpiry) {
    errors.push(issue('$/license', 'incomplete_license_grant', 'license_grant_id and grant_expires_at must either both be set or both be null'));
  }
  if (license.review_status === 'approved') {
    if (license.reviewed_at === null) errors.push(issue('$/license/reviewed_at', 'missing_review_time', 'approved license must have reviewed_at'));
    if (license.review_evidence_ref === null || license.review_evidence_ref.length === 0) {
      errors.push(issue('$/license/review_evidence_ref', 'missing_review_evidence', 'approved license must have review evidence'));
    }
  }
  return { ok: errors.length === 0, errors };
}

function wellFormedUnicode(value) {
  if (typeof value.isWellFormed === 'function') return value.isWellFormed();
  for (let index = 0; index < value.length; index += 1) {
    const unit = value.charCodeAt(index);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) return false;
      index += 1;
    } else if (unit >= 0xdc00 && unit <= 0xdfff) {
      return false;
    }
  }
  return true;
}

function illegalCtcToken(token) {
  if (!wellFormedUnicode(token) || [...token].length > 64) return true;
  for (const symbol of token) {
    const codePoint = symbol.codePointAt(0);
    if (
      codePoint <= 0x1f ||
      (codePoint >= 0x7f && codePoint <= 0x9f) ||
      (codePoint >= 0xfdd0 && codePoint <= 0xfdef) ||
      (codePoint & 0xffff) === 0xfffe ||
      (codePoint & 0xffff) === 0xffff
    ) return true;
  }
  return false;
}

/**
 * Validates the exact hash-protected CTC vocabulary bytes for a reading
 * Manifest. Manifest-only validation establishes the artifact reference and
 * tensor C dimension; this package-time/runtime boundary validates the bytes.
 */
export function validateCtcVocabularyArtifact(manifest, artifactBytes) {
  const structural = validateManifest(manifest);
  if (!structural.ok) return structural;
  const errors = [];
  const decoding = manifest.ctc_decoding;
  const artifact = manifest.artifacts.find((candidate) =>
    candidate.role === decoding.vocabulary_artifact_role
  );
  if (!(artifactBytes instanceof Uint8Array)) {
    return {
      ok: false,
      errors: [issue('$/ctc_vocabulary', 'ctc_vocabulary_bytes_missing', 'the referenced vocabulary artifact bytes are required')]
    };
  }
  const bytes = Buffer.from(artifactBytes.buffer, artifactBytes.byteOffset, artifactBytes.byteLength);
  if (bytes.byteLength !== artifact.size_bytes) {
    errors.push(issue('$/ctc_vocabulary', 'ctc_vocabulary_size_mismatch', 'vocabulary bytes do not match the signed artifact size'));
  }
  if (createHash('sha256').update(bytes).digest('hex') !== artifact.sha256) {
    errors.push(issue('$/ctc_vocabulary', 'ctc_vocabulary_hash_mismatch', 'vocabulary bytes do not match the signed artifact SHA-256'));
  }
  if (bytes.byteLength < 1 || bytes.byteLength > 4 * 1024 * 1024) {
    errors.push(issue('$/ctc_vocabulary', 'ctc_vocabulary_size_unsupported', 'vocabulary artifact must be between 1 byte and 4 MiB'));
    return { ok: false, errors };
  }

  let sidecar;
  try {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    sidecar = JSON.parse(text);
  } catch {
    errors.push(issue('$/ctc_vocabulary', 'ctc_vocabulary_json_invalid', 'vocabulary sidecar must be valid UTF-8 JSON'));
    return { ok: false, errors };
  }
  if (sidecar === null || typeof sidecar !== 'object' || Array.isArray(sidecar)) {
    errors.push(issue('$/ctc_vocabulary', 'ctc_vocabulary_format_unknown', 'vocabulary sidecar must use ctc_vocabulary_v1'));
    return { ok: false, errors };
  }
  const fields = Object.keys(sidecar).toSorted();
  if (!sameArray(fields, ['family', 'schema_version', 'tokens'])) {
    errors.push(issue('$/ctc_vocabulary', 'ctc_vocabulary_format_unknown', 'vocabulary sidecar must contain exactly schema_version, family, and tokens'));
  }
  if (sidecar.schema_version !== '1.0' || sidecar.family !== 'ctc_vocabulary_v1') {
    errors.push(issue('$/ctc_vocabulary', 'ctc_vocabulary_format_unknown', 'unknown CTC vocabulary schema version or family'));
  }
  if (!Array.isArray(sidecar.tokens) || sidecar.tokens.length < 2 || sidecar.tokens.length > 65_536) {
    errors.push(issue('$/ctc_vocabulary/tokens', 'ctc_vocabulary_tokens_invalid', 'tokens must contain between 2 and 65536 entries'));
    return { ok: false, errors };
  }

  const tensor = finalOutputs(manifest).find((output) => output.role === 'ctc_logits');
  const classCount = tensor.runtime_shape[2];
  if (sidecar.tokens.length !== classCount) {
    errors.push(issue('$/ctc_vocabulary/tokens', 'ctc_vocabulary_shape_mismatch', 'token count must equal the final C dimension of ctc_logits'));
  }
  const duplicates = new Set();
  const seen = new Set();
  sidecar.tokens.forEach((token, index) => {
    const path = `$/ctc_vocabulary/tokens/${index}`;
    if (typeof token !== 'string') {
      errors.push(issue(path, 'ctc_vocabulary_symbol_invalid', 'every CTC token must be a string'));
      return;
    }
    if (index === decoding.blank_index) {
      if (token !== '') {
        errors.push(issue(path, 'ctc_vocabulary_blank_mismatch', 'the signed blank_index must contain the empty-string blank token'));
      }
      return;
    }
    if (token.length === 0 || illegalCtcToken(token)) {
      errors.push(issue(path, 'ctc_vocabulary_symbol_invalid', 'non-blank tokens must be non-empty, well-formed Unicode without controls or noncharacters'));
      return;
    }
    if (seen.has(token)) duplicates.add(token);
    seen.add(token);
  });
  if (duplicates.size > 0) {
    errors.push(issue('$/ctc_vocabulary/tokens', 'ctc_vocabulary_duplicate_token', 'non-blank vocabulary tokens must be unique'));
  }
  return { ok: errors.length === 0, errors };
}

export function validateCommercialManifest(
  manifest,
  {
    now = new Date(),
    signatureValid = false,
    artifactHashesValid = false,
    licenseTextHashValid = false,
    licenseReviewEvidenceHashValid = false
  } = {}
) {
  const structural = validateManifest(manifest);
  if (!structural.ok) return structural;
  const errors = [];
  const license = manifest.license;
  if (license.review_status !== 'approved') errors.push(issue('$/license/review_status', 'license_not_approved', 'commercial package license must be approved'));
  if (license.commercial_use_allowed !== true) errors.push(issue('$/license/commercial_use_allowed', 'commercial_use_denied', 'must be true'));
  if (license.redistribution_allowed !== true) errors.push(issue('$/license/redistribution_allowed', 'redistribution_denied', 'must be true'));
  if (license.source_disclosure_required !== false) errors.push(issue('$/license/source_disclosure_required', 'source_disclosure_required', 'must be false'));
  if (license.grant_expires_at !== null && Date.parse(license.grant_expires_at) <= now.getTime()) {
    errors.push(issue('$/license/grant_expires_at', 'license_grant_expired', 'license grant is expired'));
  }
  if (!SHA_256.test(license.review_evidence_sha256 ?? '')) {
    errors.push(issue(
      '$/license/review_evidence_sha256',
      'license_review_evidence_sha256_missing',
      'commercial package license review evidence must be bound by SHA-256'
    ));
  }
  errors.push(...validateParameterProfile(manifest.parameter_profile, { requireResolvedSampling: true }));
  errors.push(...validateCommercialDetectionMetadata(manifest));
  if (signatureValid !== true) errors.push(issue('$/signature', 'signature_invalid', 'manifest Ed25519 signature was not verified'));
  if (artifactHashesValid !== true) errors.push(issue('$/artifacts', 'artifact_hashes_invalid', 'every artifact role and SHA-256 must be verified'));
  if (licenseTextHashValid !== true) errors.push(issue('$/license/license_text_sha256', 'license_text_hash_invalid', 'license text SHA-256 was not verified'));
  if (licenseReviewEvidenceHashValid !== true) errors.push(issue('$/license/review_evidence_sha256', 'license_review_evidence_hash_invalid', 'license review evidence SHA-256 was not verified'));
  return { ok: errors.length === 0, errors };
}

/**
 * Internal evaluation remains package-scoped and fail-closed. A v4 package
 * needs an approved review plus complete signature, artifact, and license-text
 * integrity even when its commercial-use flags are false.
 */
export function validateInternalEvaluationManifest(
  manifest,
  {
    now = new Date(),
    signatureValid = false,
    artifactHashesValid = false,
    licenseTextHashValid = false
  } = {}
) {
  const structural = validateManifest(manifest);
  if (!structural.ok) return structural;
  const errors = [];
  const license = manifest.license;
  if (license.review_status !== 'approved') {
    errors.push(issue('$/license/review_status', 'license_not_approved_for_internal_evaluation', 'internal evaluation requires an approved package license review'));
  }
  if (license.grant_expires_at !== null && Date.parse(license.grant_expires_at) <= now.getTime()) {
    errors.push(issue('$/license/grant_expires_at', 'license_grant_expired', 'license grant is expired'));
  }
  errors.push(...validateParameterProfile(manifest.parameter_profile, { requireResolvedSampling: true }));
  if (signatureValid !== true) errors.push(issue('$/signature', 'signature_invalid', 'manifest Ed25519 signature was not verified'));
  if (artifactHashesValid !== true) errors.push(issue('$/artifacts', 'artifact_hashes_invalid', 'every artifact role and SHA-256 must be verified'));
  if (licenseTextHashValid !== true) errors.push(issue('$/license/license_text_sha256', 'license_text_hash_invalid', 'license text SHA-256 was not verified'));
  return { ok: errors.length === 0, errors };
}

function manifestMatchesRecipe(manifest, recipe) {
  return manifest.runtime_family === recipe.runtime_family &&
    manifest.supported_tasks.includes(recipe.capability_id) &&
    recipe.prompt_modes.every((mode) => manifest.prompt_modes.includes(mode));
}

export function validateCatalogForRelease(
  catalog,
  {
    manifests = [],
    integrityByPackage = {},
    catalogSignatureValid = false,
    now = new Date()
  } = {}
) {
  const catalogValidation = validateCatalog(catalog);
  if (!catalogValidation.ok) return catalogValidation;
  const errors = [];
  if (catalogSignatureValid !== true) {
    errors.push(issue('$/signature', 'catalog_signature_invalid', 'Catalog Ed25519 signature was not verified'));
  }
  if (catalog.build_channel === 'development-no-model') {
    catalog.packages.forEach((entry, index) => {
      if (entry.status === 'active') {
        errors.push(issue(
          `$/packages/${index}`,
          'model_forbidden_in_development_no_model',
          'development-no-model Catalogs must not publish an active model package'
        ));
      }
    });
    return { ok: errors.length === 0, errors };
  }
  const manifestList = manifests instanceof Map ? [...manifests.values()] : manifests;
  for (const packageId of duplicateValues(manifestList.map((manifest) => manifest?.package_id))) {
    errors.push(issue('$/packages', 'duplicate_manifest', `multiple Manifests supplied for ${packageId}`));
  }
  const manifestMap = new Map(manifestList.map((manifest) => [manifest?.package_id, manifest]));
  catalog.packages.forEach((entry, index) => {
    if (entry.status !== 'active') return;
    const path = `$/packages/${index}`;
    const manifest = manifestMap.get(entry.package_id);
    if (!manifest) {
      errors.push(issue(path, 'manifest_missing', `active package ${entry.package_id} has no Manifest`));
      return;
    }
    if (manifest.package_version !== entry.package_version) {
      errors.push(issue(path, 'package_version_mismatch', 'Catalog and Manifest versions differ'));
    }
    const recipes = catalog.runtime_recipes.filter((recipe) => recipe.candidate_package_ids.includes(entry.package_id));
    const compatibleRecipes = recipes.filter((recipe) => manifestMatchesRecipe(manifest, recipe));
    if (recipes.length === 0 || compatibleRecipes.length === 0) {
      errors.push(issue(path, 'package_recipe_incompatible', 'active package Manifest does not match any Catalog recipe that references it'));
    }
    const operationalCapabilities = catalog.operational_capabilities.filter((item) =>
      item.package_ids.includes(entry.package_id));
    for (const operational of operationalCapabilities) {
      const operationalRecipe = catalog.runtime_recipes.find((recipe) =>
        recipe.recipe_id === operational.recipe_id);
      if (operationalRecipe === undefined || !manifestMatchesRecipe(manifest, operationalRecipe)) {
        errors.push(issue(
          path,
          'package_operational_recipe_mismatch',
          `${entry.package_id} Manifest does not match model profile ${operational.capability_key} recipe ${operational.recipe_id}`
        ));
      }
      const missingDeviceProfiles = operational.device_profile_ids.filter((profileId) =>
        !manifest.device_compatibility.device_profile_ids.includes(profileId));
      if (missingDeviceProfiles.length > 0) {
        errors.push(issue(
          path,
          'package_operational_device_mismatch',
          `${entry.package_id} does not support operational device profile(s): ${missingDeviceProfiles.join(', ')}`
        ));
      }
      if (!operational.human_review.evidence_refs.includes(manifest.license.review_evidence_ref)) {
        errors.push(issue(
          path,
          'package_operational_review_evidence_mismatch',
          `${entry.package_id} Manifest license review is not bound by ${operational.capability_key}`
        ));
      }
    }
    const integrity = integrityByPackage[entry.package_id] ?? {};
    if (integrity.manifestHashValid !== true) {
      errors.push(issue(`${path}/manifest_sha256`, 'manifest_hash_invalid', 'Catalog Manifest SHA-256 was not verified'));
    }
    const policyOptions = {
      now,
      signatureValid: integrity.signatureValid,
      artifactHashesValid: integrity.artifactHashesValid,
      licenseTextHashValid: integrity.licenseTextHashValid,
      licenseReviewEvidenceHashValid: integrity.licenseReviewEvidenceHashValid
    };
    const packageValidation = ['commercial', 'community'].includes(catalog.build_channel)
      ? validateCommercialManifest(manifest, policyOptions)
      : validateInternalEvaluationManifest(manifest, policyOptions);
    if (!packageValidation.ok) {
      errors.push(issue(path, 'package_policy_failed', packageValidation.errors));
    }
  });
  for (const operational of catalog.operational_capabilities) {
    const recipe = catalog.runtime_recipes.find((candidate) =>
      candidate.recipe_id === operational.recipe_id);
    if (recipe?.runtime_family !== 'object_detection_v1') continue;
    const manifestTargetIds = operational.package_ids.flatMap((packageId) =>
      manifestMap.get(packageId)?.adapter_contract?.class_map?.targets
        ?.map((target) => target.target_id) ?? []);
    if (!sameStringSet(
      [...new Set(manifestTargetIds)].toSorted(),
      [...(operational.target_ids ?? [])].toSorted(),
    )) {
      errors.push(issue(
        '$/operational_capabilities',
        'package_operational_target_coverage_mismatch',
        `${operational.capability_key} target_ids must exactly equal the union of its package Manifest targets`,
      ));
    }
  }
  return { ok: errors.length === 0, errors };
}

export function validateTaskConfig(task, catalog) {
  const schema = validateAgainstSchema('task-config', task);
  if (!schema.ok) return schema;
  const errors = [];
  const targetMode = task.target_definition.mode;
  if (task.capability_id === 'structured_reading') {
    if (targetMode !== 'none') {
      errors.push(issue(
        '$/target_definition/mode',
        'structured_reading_target_mode_mismatch',
        'structured_reading requires the explicit no-prompt target mode none'
      ));
    }
    if (task.rule.type !== 'reading_threshold') {
      errors.push(issue('$/rule/type', 'structured_reading_rule_mismatch', 'structured_reading requires a reading_threshold rule'));
    }
    errors.push(...validateConfirmedReadingFormat(
      task.target_definition.confirmed_format,
      '$/target_definition/confirmed_format'
    ));
  } else if (targetMode === 'none') {
    errors.push(issue(
      '$/target_definition/mode',
      'visual_target_mode_none_forbidden',
      'visual capabilities require object_detection or reference_images'
    ));
  }
  errors.push(...validatePositiveAreaNormalizedRoi(task.roi, '$/roi'));
  if (task.rule.type === 'reading_threshold') {
    if (task.rule.operator === 'outside') {
      errors.push(...validateCanonicalDecimal(task.rule.lower_threshold_decimal, '$/rule/lower_threshold_decimal'));
      errors.push(...validateCanonicalDecimal(task.rule.upper_threshold_decimal, '$/rule/upper_threshold_decimal'));
      if (compareCanonicalDecimals(task.rule.lower_threshold_decimal, task.rule.upper_threshold_decimal) >= 0) {
        errors.push(issue('$/rule', 'reading_outside_range_invalid', 'outside lower threshold must be less than upper threshold'));
      }
    } else {
      errors.push(...validateCanonicalDecimal(task.rule.threshold_decimal, '$/rule/threshold_decimal'));
    }
    errors.push(...validateCanonicalDecimal(task.rule.hysteresis_decimal, '$/rule/hysteresis_decimal'));
    if (task.rule.hysteresis_decimal.startsWith('-')) {
      errors.push(issue('$/rule/hysteresis_decimal', 'negative_hysteresis', 'hysteresis must be a non-negative decimal string'));
    }
  }
  if (catalog) {
    const catalogValidation = validateCatalog(catalog);
    if (!catalogValidation.ok) {
      errors.push(issue('$', 'invalid_catalog_context', 'catalog context is invalid'));
      return { ok: false, errors };
    }
    if (task.catalog_version !== catalog.catalog_version) {
      errors.push(issue('$/catalog_version', 'catalog_version_mismatch', 'task must reference the supplied immutable catalog version'));
    }
    const capability = catalog.capabilities.find((item) => item.capability_id === task.capability_id);
    const recipe = catalog.runtime_recipes.find((item) =>
      item.recipe_id === task.route_binding.recipe_id);
    const packageEntry = catalog.packages.find((item) =>
      item.package_id === task.package_binding.package_id && item.status === 'active');
    const operational = catalog.operational_capabilities.find((item) =>
      item.capability_key === task.route_binding.model_profile_key &&
      item.capability_id === task.capability_id &&
      item.recipe_id === task.route_binding.recipe_id &&
      item.package_ids.includes(task.package_binding.package_id) &&
      item.intent_patterns.some((pattern) => intentPatternMatches(
        pattern,
        task.route_binding.intent_key,
      )));
    const catalogTargetMode = task.target_definition.mode === 'object_detection'
      ? 'object_class'
      : task.target_definition.mode;
    if (!capability) {
      errors.push(issue('$/capability_id', 'unknown_capability', 'capability is not present in catalog'));
    } else {
      if (!capability.target_modes.includes(catalogTargetMode)) {
        errors.push(issue('$/target_definition/mode', 'target_mode_not_allowed', 'target input mode is not allowed by capability'));
      }
      if (!capability.allowed_rule_types.includes(task.rule.type)) {
        errors.push(issue('$/rule/type', 'rule_not_allowed', 'rule type is not allowed by capability'));
      }
    }
    if (!recipe || recipe.capability_id !== task.capability_id ||
        !capability?.recipe_ids.includes(recipe.recipe_id) ||
        !recipe.candidate_package_ids.includes(task.package_binding.package_id)) {
      errors.push(issue('$/route_binding/recipe_id', 'recipe_binding_mismatch', 'route recipe must be authorized by the capability and selected package'));
    }
    if (!operational) {
      errors.push(issue('$/route_binding', 'operational_route_binding_mismatch', 'route must exactly match one active signed operational capability'));
    } else if (targetMode === 'object_detection' &&
        !operational.target_ids.includes(task.target_definition.target_id)) {
      errors.push(issue('$/target_definition/target_id', 'object_target_not_authorized', 'object target must be authorized by the exact operational route'));
    }
    if (!packageEntry ||
        packageEntry.package_version !== task.package_binding.package_version ||
        packageEntry.manifest_sha256 !== task.package_binding.manifest_sha256) {
      errors.push(issue('$/package_binding', 'package_binding_mismatch', 'package binding must exactly match one active signed Catalog package'));
    }
  }
  return { ok: errors.length === 0, errors };
}

export function validateObservation(observation) {
  const schema = validateAgainstSchema('observation', observation);
  if (!schema.ok) return schema;
  const errors = [];
  if (observation.kind === 'detections') {
    observation.detections.forEach((detection, index) => {
      errors.push(...validatePositiveAreaNormalizedRoi(detection.box, `$/detections/${index}/box`));
    });
  }
  if (observation.kind === 'reading' && observation.reading.value_decimal !== null) {
    errors.push(...validateCanonicalDecimal(observation.reading.value_decimal, '$/reading/value_decimal'));
    if (observation.reading.unit !== observation.reading.format.unit) {
      errors.push(issue(
        '$/reading/unit',
        'structured_reading_unit_mismatch',
        'reading unit must equal the confirmed format unit'
      ));
    }
  }
  if (observation.kind === 'reading') {
    errors.push(...validateConfirmedReadingFormat(observation.reading.format, '$/reading/format'));
  }
  return { ok: errors.length === 0, errors };
}

export function validateEvent(event) {
  const schema = validateAgainstSchema('event', event);
  if (!schema.ok) return schema;
  const errors = [];
  if (event.payload.type === 'reading_threshold_crossed') {
    errors.push(...validateConfirmedReadingFormat(event.payload.reading.format, '$/payload/reading/format'));
    if (event.payload.reading.status !== 'stable') {
      errors.push(issue('$/payload/reading/status', 'event_reading_not_stable', 'only a stable numeric reading can create a threshold event'));
    }
    if (event.payload.reading.value_decimal !== null) {
      errors.push(...validateCanonicalDecimal(event.payload.reading.value_decimal, '$/payload/reading/value_decimal'));
    }
    if (event.payload.reading.unit !== event.payload.reading.format.unit) {
      errors.push(issue(
        '$/payload/reading/unit',
        'structured_reading_unit_mismatch',
        'event reading unit must equal the confirmed format unit'
      ));
    }
    if (event.payload.operator === 'outside') {
      errors.push(...validateCanonicalDecimal(event.payload.lower_threshold_decimal, '$/payload/lower_threshold_decimal'));
      errors.push(...validateCanonicalDecimal(event.payload.upper_threshold_decimal, '$/payload/upper_threshold_decimal'));
      if (compareCanonicalDecimals(event.payload.lower_threshold_decimal, event.payload.upper_threshold_decimal) >= 0) {
        errors.push(issue('$/payload', 'reading_outside_range_invalid', 'outside lower threshold must be less than upper threshold'));
      }
    } else {
      errors.push(...validateCanonicalDecimal(event.payload.threshold_decimal, '$/payload/threshold_decimal'));
    }
  }
  return { ok: errors.length === 0, errors };
}

export function validateContract(kind, value, context = {}) {
  switch (kind) {
    case 'capability-catalog': return validateCatalog(value);
    case 'model-manifest': return validateManifest(value, { repositoryRoot: context.repositoryRoot });
    case 'task-config': return validateTaskConfig(value, context.catalog);
    case 'observation': return validateObservation(value);
    case 'event': return validateEvent(value);
    default: return validateAgainstSchema(kind, value);
  }
}
