#!/usr/bin/env node
import { createPublicKey } from 'node:crypto';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  BUILTIN_CATALOG_PUBLIC_KEYS,
  BUILTIN_MANIFEST_PUBLIC_KEYS,
} from './release-public-keys.mjs';
import {
  freezeCommercialRelease,
  GOOGLE_PLAY_FIRST_RELEASE_PROFILE,
} from './commercial-release-freezer.mjs';

function argumentsByName(values) {
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const name = values[index];
    const value = values[index + 1];
    if (!name?.startsWith('--') || value === undefined || value.startsWith('--')) {
      throw new Error('arguments must be --name value pairs');
    }
    const key = name.slice(2);
    if (Object.hasOwn(result, key)) throw new Error(`duplicate argument --${key}`);
    result[key] = value;
  }
  return result;
}

function trustedKeys(values, label) {
  const entries = Object.entries(values);
  if (entries.length < 1 || entries.length > 2) throw new Error(`${label} key ring must contain one or two keys`);
  return Object.fromEntries(entries.map(([keyId, pem]) => {
    const key = createPublicKey(pem);
    if (key.type !== 'public' || key.asymmetricKeyType !== 'ed25519') {
      throw new Error(`${label} ${keyId} is not an Ed25519 public key`);
    }
    return [keyId, key];
  }));
}

export async function runCommercialReleaseFreezerCli(values, dependencies = {}) {
  const args = argumentsByName(values);
  const expected = ['catalog-url', 'release-url', 'rollback-catalog-url', 'rollback-release-url', 'output'];
  const actual = Object.keys(args).sort();
  if (actual.join('\0') !== expected.sort().join('\0')) {
    throw new Error(`usage: ${process.argv[1]} --catalog-url URL --release-url URL --rollback-catalog-url URL --rollback-release-url URL --output DIRECTORY`);
  }
  const snapshot = await (dependencies.freezeImplementation ?? freezeCommercialRelease)({
    catalogUrl: args['catalog-url'],
    releaseUrl: args['release-url'],
    rollbackCatalogUrl: args['rollback-catalog-url'],
    rollbackReleaseUrl: args['rollback-release-url'],
    outputDirectory: resolve(args.output),
    catalogPublicKeys: dependencies.catalogPublicKeys ?? trustedKeys(BUILTIN_CATALOG_PUBLIC_KEYS, 'Catalog'),
    manifestPublicKeys: dependencies.manifestPublicKeys ?? trustedKeys(BUILTIN_MANIFEST_PUBLIC_KEYS, 'Manifest'),
    releaseProfile: GOOGLE_PLAY_FIRST_RELEASE_PROFILE,
  });
  return {
    status: snapshot.status,
    catalog_version: snapshot.current_catalog.catalog_version,
    catalog_sha256: snapshot.current_catalog.sha256,
    rollback_catalog_version: snapshot.rollback_catalog.catalog_version,
    rollback_catalog_sha256: snapshot.rollback_catalog.sha256,
    package_count: Object.keys(snapshot.packages).length,
    snapshot_file: resolve(args.output, 'snapshot.json'),
  };
}

if (
  process.argv[1] !== undefined &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  try {
    const summary = await runCommercialReleaseFreezerCli(process.argv.slice(2));
    process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}
