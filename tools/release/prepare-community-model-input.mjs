import { readFileSync, mkdirSync, existsSync, renameSync, writeFileSync, statSync } from 'node:fs';
import { resolve, isAbsolute, dirname } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
const root = resolve(import.meta.dirname, '../..');
const [cache, output] = process.argv.slice(2);
if (!cache || !output || !isAbsolute(cache) || !isAbsolute(output) || existsSync(output)) throw new Error('Use absolute artifact-cache and a new input JSON path');
mkdirSync(cache, { recursive: true }); mkdirSync(dirname(output), { recursive: true });
const templates = resolve(root, 'model-tools/v3/releases/community/templates');
const input = JSON.parse(readFileSync(resolve(templates, 'release-build-input.json')));
input.catalog_template = resolve(templates, input.catalog_template);
for (const spec of input.packages) {
  spec.manifest_template = resolve(templates, spec.manifest_template);
  spec.license_text = resolve(templates, spec.license_text);
  spec.license_review_evidence = resolve(templates, spec.license_review_evidence);
  const manifest = JSON.parse(readFileSync(spec.manifest_template));
  const verify = (path, artifact) => statSync(path).size === artifact.size_bytes &&
    createHash('sha256').update(readFileSync(path)).digest('hex') === artifact.sha256;
  for (const artifact of manifest.artifacts) {
    const file = resolve(cache, artifact.sha256);
    if (existsSync(file)) {
      if (!verify(file, artifact)) throw new Error(`Corrupt cache: ${artifact.role}`);
    } else {
      const partial = file + '.part';
      execFileSync('curl', ['--fail', '--silent', '--show-error', '--max-time', '900', '--retry', '2',
        '--proto', '=https', '--output', partial, artifact.url], { stdio: 'inherit' });
      if (!verify(partial, artifact)) throw new Error(`Artifact integrity failure: ${spec.package_id}/${artifact.role}`);
      renameSync(partial, file);
    }
    spec.artifacts_by_role[artifact.role] = file;
  }
}
writeFileSync(output, JSON.stringify(input, null, 2) + '\n');
console.log(`Verified public artifacts and wrote ${output}; no signing or publication performed.`);
