import { readFileSync } from 'node:fs';

const schemaNames = [
  'common',
  'capability-catalog',
  'monitor-configuration-proposal',
  'model-manifest',
  'task-config',
  'observation',
  'event',
  'device',
  'sync-event-upsert',
  'sync',
  'push-envelope'
];

const schemas = new Map(
  schemaNames.map((name) => {
    const url = new URL(`../../../contracts/schemas/${name}.schema.json`, import.meta.url);
    return [`${name}.schema.json`, JSON.parse(readFileSync(url, 'utf8'))];
  })
);

function valueType(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (Number.isInteger(value)) return 'integer';
  return typeof value === 'number' ? 'number' : typeof value;
}

function acceptsType(value, expected) {
  const actual = valueType(value);
  if (expected === 'number') return actual === 'number' || actual === 'integer';
  if (expected === 'object') return actual === 'object';
  return actual === expected;
}

function sameJsonValue(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function resolvePointer(document, pointer) {
  if (!pointer || pointer === '#') return document;
  if (!pointer.startsWith('#/')) throw new Error(`Unsupported JSON pointer: ${pointer}`);
  return pointer
    .slice(2)
    .split('/')
    .map((part) => part.replaceAll('~1', '/').replaceAll('~0', '~'))
    .reduce((current, part) => current?.[part], document);
}

function resolveRef(ref, currentSchemaName) {
  const [fileName, fragment = ''] = ref.split('#', 2);
  const resolvedName = fileName || currentSchemaName;
  const document = schemas.get(resolvedName);
  if (!document) throw new Error(`Unknown schema reference: ${ref}`);
  const target = resolvePointer(document, fragment ? `#${fragment}` : '#');
  if (!target) throw new Error(`Unresolved schema reference: ${ref}`);
  return { schema: target, schemaName: resolvedName };
}

function validDateTime(value) {
  return typeof value === 'string' && Number.isFinite(Date.parse(value)) && /(?:Z|[+-][0-9]{2}:[0-9]{2})$/.test(value);
}

function validUri(value) {
  if (typeof value !== 'string') return false;
  try {
    const parsed = new URL(value);
    return Boolean(parsed.protocol && parsed.hostname);
  } catch {
    return false;
  }
}

function pushError(errors, path, code, message) {
  errors.push({ path, code, message });
}

function validateNode(schema, value, path, schemaName, errors) {
  if (schema.$ref) {
    const resolved = resolveRef(schema.$ref, schemaName);
    validateNode(resolved.schema, value, path, resolved.schemaName, errors);
    return;
  }

  if (schema.oneOf) {
    const branches = schema.oneOf.map((branch) => {
      const branchErrors = [];
      validateNode(branch, value, path, schemaName, branchErrors);
      return branchErrors;
    });
    const matches = branches.filter((branchErrors) => branchErrors.length === 0);
    if (matches.length !== 1) {
      const nearest = branches.toSorted((left, right) => left.length - right.length)[0] ?? [];
      pushError(errors, path, 'one_of', `must match exactly one allowed shape; nearest shape has ${nearest.length} error(s)`);
      errors.push(...nearest.slice(0, 3));
    }
    return;
  }

  if (schema.const !== undefined && !sameJsonValue(value, schema.const)) {
    pushError(errors, path, 'const', `must equal ${JSON.stringify(schema.const)}`);
    return;
  }

  if (schema.enum && !schema.enum.some((candidate) => sameJsonValue(value, candidate))) {
    pushError(errors, path, 'enum', `unknown value ${JSON.stringify(value)}`);
    return;
  }

  if (schema.type) {
    const expected = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!expected.some((type) => acceptsType(value, type))) {
      pushError(errors, path, 'type', `must be ${expected.join(' or ')}`);
      return;
    }
  }

  if (typeof value === 'string') {
    // JSON Schema measures string length in Unicode code points, not UTF-16
    // code units. This keeps Node parity with Ajv and Android codePointCount.
    const characterLength = [...value].length;
    if (schema.minLength !== undefined && characterLength < schema.minLength) {
      pushError(errors, path, 'min_length', `must contain at least ${schema.minLength} character(s)`);
    }
    if (schema.maxLength !== undefined && characterLength > schema.maxLength) {
      pushError(errors, path, 'max_length', `must contain at most ${schema.maxLength} character(s)`);
    }
    if (schema.pattern && !(new RegExp(schema.pattern).test(value))) {
      pushError(errors, path, 'pattern', 'has an invalid format');
    }
    if (schema.format === 'date-time' && !validDateTime(value)) {
      pushError(errors, path, 'date_time', 'must be an RFC 3339 date-time with an explicit offset');
    }
    if (schema.format === 'uri' && !validUri(value)) {
      pushError(errors, path, 'uri', 'must be an absolute URI');
    }
  }

  if (typeof value === 'number') {
    if (!Number.isFinite(value)) pushError(errors, path, 'finite', 'must be finite');
    if (schema.minimum !== undefined && value < schema.minimum) {
      pushError(errors, path, 'minimum', `must be at least ${schema.minimum}`);
    }
    if (schema.maximum !== undefined && value > schema.maximum) {
      pushError(errors, path, 'maximum', `must be at most ${schema.maximum}`);
    }
    if (schema.exclusiveMinimum !== undefined && value <= schema.exclusiveMinimum) {
      pushError(errors, path, 'exclusive_minimum', `must be greater than ${schema.exclusiveMinimum}`);
    }
  }

  if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) {
      pushError(errors, path, 'min_items', `must contain at least ${schema.minItems} item(s)`);
    }
    if (schema.maxItems !== undefined && value.length > schema.maxItems) {
      pushError(errors, path, 'max_items', `must contain at most ${schema.maxItems} item(s)`);
    }
    if (schema.uniqueItems) {
      const serialized = value.map((item) => JSON.stringify(item));
      if (new Set(serialized).size !== serialized.length) {
        pushError(errors, path, 'unique_items', 'must not contain duplicate items');
      }
    }
    if (schema.items) {
      value.forEach((item, index) => validateNode(schema.items, item, `${path}/${index}`, schemaName, errors));
    }
  }

  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    const properties = schema.properties ?? {};
    for (const required of schema.required ?? []) {
      if (!Object.hasOwn(value, required)) {
        pushError(errors, `${path}/${required}`, 'required', 'is required');
      }
    }
    for (const [key, child] of Object.entries(value)) {
      if (Object.hasOwn(properties, key)) {
        validateNode(properties[key], child, `${path}/${key}`, schemaName, errors);
      } else if (schema.additionalProperties === false) {
        pushError(errors, `${path}/${key}`, 'additional_property', 'is not allowed');
      } else if (schema.additionalProperties && typeof schema.additionalProperties === 'object') {
        validateNode(schema.additionalProperties, child, `${path}/${key}`, schemaName, errors);
      }
    }
  }
}

export const documentKinds = Object.freeze(
  schemaNames.filter((name) => name !== 'common')
);

export function validateAgainstSchema(kind, value) {
  const schemaName = `${kind}.schema.json`;
  const schema = schemas.get(schemaName);
  if (!schema) {
    return { ok: false, errors: [{ path: '$', code: 'unknown_kind', message: `unknown contract kind ${kind}` }] };
  }
  const errors = [];
  validateNode(schema, value, '$', schemaName, errors);
  return { ok: errors.length === 0, errors };
}
