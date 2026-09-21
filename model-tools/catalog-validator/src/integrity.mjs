import { createHash, verify } from 'node:crypto';

function serialize(value) {
  if (typeof value === 'string' && !value.isWellFormed()) {
    throw new TypeError('RFC 8785 input cannot contain lone Unicode surrogates');
  }
  if (value === null || typeof value === 'boolean' || typeof value === 'string') {
    return JSON.stringify(value);
  }
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new TypeError('RFC 8785 input cannot contain non-finite numbers');
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(serialize).join(',')}]`;
  if (typeof value === 'object') {
    const keys = Object.keys(value);
    if (keys.some((key) => !key.isWellFormed())) {
      throw new TypeError('RFC 8785 object keys cannot contain lone Unicode surrogates');
    }
    const pairs = keys
      .sort()
      .map((key) => `${JSON.stringify(key)}:${serialize(value[key])}`);
    return `{${pairs.join(',')}}`;
  }
  throw new TypeError(`RFC 8785 input cannot contain ${typeof value}`);
}

export function canonicalize(value) {
  return serialize(value);
}

export function signedPayload(document) {
  if (!document || typeof document !== 'object' || Array.isArray(document)) {
    throw new TypeError('signed document must be an object');
  }
  const { signature: _signature, ...payload } = document;
  return payload;
}

export function verifySignedDocument(document, publicKeys) {
  const signature = document?.signature;
  if (
    signature?.canonicalization !== 'RFC8785' ||
    signature?.algorithm !== 'Ed25519' ||
    typeof signature?.signing_key_id !== 'string' ||
    typeof signature?.value !== 'string'
  ) return false;

  const publicKey = publicKeys instanceof Map
    ? publicKeys.get(signature.signing_key_id)
    : publicKeys?.[signature.signing_key_id];
  if (!publicKey) return false;

  try {
    return verify(
      null,
      Buffer.from(canonicalize(signedPayload(document)), 'utf8'),
      publicKey,
      Buffer.from(signature.value, 'base64')
    );
  } catch {
    return false;
  }
}

export function sha256Hex(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

export function parseCanonicalFixedHttpsUrl(value) {
  if (typeof value !== 'string') return undefined;
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    return undefined;
  }
  if (
    parsed.protocol !== 'https:' ||
    parsed.username.length > 0 ||
    parsed.password.length > 0 ||
    value.includes('#') ||
    parsed.hash.length > 0 ||
    parsed.toString() !== value
  ) return undefined;
  return parsed;
}

export function verifyArtifactHash(manifest, bytes) {
  const expected = manifest?.artifacts?.find((artifact) => artifact.role === 'primary')?.sha256;
  return typeof expected === 'string' && sha256Hex(bytes) === expected;
}

export function verifyArtifactHashes(manifest, artifactBytesByRole) {
  if (!Array.isArray(manifest?.artifacts) || manifest.artifacts.length === 0) return false;
  const supplied = artifactBytesByRole instanceof Map
    ? artifactBytesByRole
    : new Map(Object.entries(artifactBytesByRole ?? {}));
  if (supplied.size !== manifest.artifacts.length) return false;
  return manifest.artifacts.every((artifact) => {
    const bytes = supplied.get(artifact.role);
    return bytes !== undefined && sha256Hex(bytes) === artifact.sha256;
  });
}

export function verifyManifestHash(packageEntry, manifestBytes) {
  const expected = packageEntry?.manifest_sha256;
  return typeof expected === 'string' && sha256Hex(manifestBytes) === expected;
}
