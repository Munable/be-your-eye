#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { validateContract, validateCommercialManifest } from './index.mjs';

const [kind, file, ...flags] = process.argv.slice(2);
if (!kind || !file) {
  console.error('Usage: node src/cli.mjs <contract-kind> <json-file> [--commercial]');
  process.exitCode = 2;
} else {
  try {
    const value = JSON.parse(await readFile(file, 'utf8'));
    const result = flags.includes('--commercial')
      ? validateCommercialManifest(value)
      : validateContract(kind, value);
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    if (!result.ok) process.exitCode = 1;
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 2;
  }
}
