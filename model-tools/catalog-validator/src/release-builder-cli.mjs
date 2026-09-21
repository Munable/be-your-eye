#!/usr/bin/env node
import { buildRelease } from './release-builder.mjs';

const [inputPath, outputDirectory, catalogKeyId, catalogPrivateKeyPath, manifestKeyId, manifestPrivateKeyPath] =
  process.argv.slice(2);

if (!manifestPrivateKeyPath) {
  process.stderr.write(
    'Usage: node src/release-builder-cli.mjs INPUT_JSON OUTPUT_DIR CATALOG_KEY_ID CATALOG_PRIVATE_PEM MANIFEST_KEY_ID MANIFEST_PRIVATE_PEM\n',
  );
  process.exitCode = 2;
} else {
  try {
    const result = await buildRelease({
      inputPath,
      outputDirectory,
      catalogKeyId,
      catalogPrivateKeyPath,
      manifestKeyId,
      manifestPrivateKeyPath,
    });
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
