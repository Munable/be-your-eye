import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const root = resolve(import.meta.dirname, '../..');
const converter = join(root, 'tools/sbom/deno-lock-to-cyclonedx.mjs');

const cases = [
  {
    name: 'push-dispatch',
    lock: 'supabase/functions/push-dispatch/deno.lock',
    expected: [
      'pkg:npm/%40supabase/supabase-js@2.111.0',
      'pkg:npm/google-auth-library@11.0.0',
    ],
  },
];

test('Deno v5 top-level specifiers become exact non-empty root dependencies', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'beyoureyes-deno-sbom-'));
  t.after(() => rm(directory, { recursive: true, force: true }));

  for (const value of cases) {
    const output = join(directory, `${value.name}.cdx.json`);
    const result = spawnSync(process.execPath, [converter, join(root, value.lock), output], {
      encoding: 'utf8',
    });
    assert.equal(result.status, 0, result.stderr);
    const sbom = JSON.parse(await readFile(output, 'utf8'));
    const rootReference = sbom.metadata.component['bom-ref'];
    const rows = sbom.dependencies.filter((entry) => entry.ref === rootReference);
    assert.equal(rows.length, 1, value.name);
    assert.deepEqual(rows[0].dependsOn, value.expected, value.name);
    for (const reference of value.expected) {
      assert.equal(
        sbom.components.some((component) => component['bom-ref'] === reference),
        true,
        `${value.name}:${reference}`,
      );
    }
  }
});
