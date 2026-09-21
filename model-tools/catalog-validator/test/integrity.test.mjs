import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import test from 'node:test';
import {
  canonicalize,
  parseCanonicalFixedHttpsUrl,
  sha256Hex,
  signedPayload,
  verifyArtifactHash,
  verifyArtifactHashes,
  verifyManifestHash,
  verifySignedDocument
} from '../src/index.mjs';

test('canonicalization is independent of object insertion order', () => {
  assert.equal(
    canonicalize({ z: 1, nested: { b: true, a: 'x' }, a: [3, 2, 1] }),
    canonicalize({ a: [3, 2, 1], nested: { a: 'x', b: true }, z: 1 })
  );
});

test('canonicalization rejects malformed Unicode instead of normalizing signature bytes', () => {
  assert.throws(() => canonicalize({ value: '\ud800' }), /lone Unicode surrogate/);
});

test('fixed release URL parsing accepts only canonical absolute HTTPS without credentials or fragments', () => {
  assert.equal(
    parseCanonicalFixedHttpsUrl('https://release.example/catalog.json')?.toString(),
    'https://release.example/catalog.json'
  );
  for (const value of [
    'http://release.example/catalog.json',
    'https://user@release.example/catalog.json',
    'https://release.example/catalog.json#mutable',
    'https://release.example/catalog.json#',
    'https://release.example',
    'https://release.example:443/catalog.json',
    './catalog.json',
    undefined
  ]) {
    assert.equal(parseCanonicalFixedHttpsUrl(value), undefined, String(value));
  }
});

test('Ed25519 verification rejects mutation and unknown rotation key', () => {
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  const document = {
    schema_version: '3.0',
    package_id: 'example',
    signature: {
      canonicalization: 'RFC8785',
      algorithm: 'Ed25519',
      signing_key_id: 'key-a',
      value: ''
    }
  };
  document.signature.value = sign(
    null,
    Buffer.from(canonicalize(signedPayload(document))),
    privateKey
  ).toString('base64');

  assert.equal(verifySignedDocument(document, { 'key-a': publicKey }), true);
  assert.equal(verifySignedDocument(document, { 'key-b': publicKey }), false);
  document.package_id = 'mutated';
  assert.equal(verifySignedDocument(document, { 'key-a': publicKey }), false);
});

test('artifact hash validation is byte-exact', () => {
  const bytes = Buffer.from('model artifact bytes');
  const manifest = { artifacts: [{ role: 'primary', sha256: sha256Hex(bytes) }] };
  assert.equal(verifyArtifactHash(manifest, bytes), true);
  assert.equal(verifyArtifactHash(manifest, Buffer.from('changed')), false);
});

test('multi-artifact package verification is role-complete and byte-exact', () => {
  const vision = Buffer.from('vision encoder');
  const text = Buffer.from('text detector');
  const manifest = {
    artifacts: [
      { role: 'primary', sha256: sha256Hex(text) },
      { role: 'vision_encoder', sha256: sha256Hex(vision) }
    ]
  };
  assert.equal(verifyArtifactHashes(manifest, { primary: text, vision_encoder: vision }), true);
  assert.equal(verifyArtifactHashes(manifest, { primary: text }), false);
  assert.equal(verifyArtifactHashes(manifest, { primary: text, vision_encoder: Buffer.from('changed') }), false);
  assert.equal(verifyArtifactHashes(manifest, { primary: text, vision_encoder: vision, extra: vision }), false);
});

test('catalog manifest hash validation is independent of model artifact hash', () => {
  const bytes = Buffer.from('{"manifest":"bytes"}');
  const packageEntry = { manifest_sha256: sha256Hex(bytes) };
  assert.equal(verifyManifestHash(packageEntry, bytes), true);
  assert.equal(verifyManifestHash(packageEntry, Buffer.from('{"changed":true}')), false);
});
