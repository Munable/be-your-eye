import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, existsSync, chmodSync } from 'node:fs';
import { resolve, dirname, isAbsolute } from 'node:path';
import { createHash } from 'node:crypto';
const root = resolve(import.meta.dirname, '../..');
const output = process.argv[2];
if (!output || !isAbsolute(output) || existsSync(output) || resolve(output).startsWith(root + '/')) throw new Error('Choose a new absolute output directory outside the source repository');
for (const args of [['diff', '--quiet'], ['diff', '--cached', '--quiet']]) execFileSync('git', args, { cwd: root });
const commit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
const publicationAuthorized = JSON.parse(execFileSync('git', ['show', `${commit}:evidence/current/05-release.json`], { cwd: root, encoding: 'utf8', maxBuffer: 1024 * 1024 })).community?.source_publication_authorized ?? false;
const entries = execFileSync('git', ['ls-tree', '-rz', '--full-tree', commit], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean);
const exclude = [
  [/^BE_YOUR_EYE_MASTER_HANDOFF_/, 'private supplied handoff'],
  [/^evidence\/(?:current|releases)\//, 'private historical execution evidence replaced with candidate scope'],
  [/^release\/google-play\/(?:screenshots|ready-to-upload|assets)\//, 'unreviewed publishing assets'],
  [/^release\/google-play\/privacy\/account\/config\.json$/, 'production client project binding'],
  [/^release\/website\/.*\.json$/, 'connected deployment state'],
  [/^release\/community\/launch\//, 'unpublished site and outreach drafts'],
  [/^\.env(?!\.example$)/, 'local environment'],
  [/(?:^|\/)(?:\.local|\.git|work|secrets|node_modules|build)\//, 'non-source data'],
  [/\.(?:pem|key|jks|keystore|p12|apk|aab|mp4|zip)$/i, 'private keys or release binaries'],
];
mkdirSync(output, { recursive: true });
const inventory = [], excluded = [];
for (const entry of entries) {
  const [metadata, path] = entry.split('\t');
  const [mode, kind, object] = metadata.split(' ');
  const reason = exclude.find(([pattern]) => pattern.test(path))?.[1];
  if (reason || kind !== 'blob' || !['100644', '100755'].includes(mode)) {
    excluded.push({ path, reason: reason ?? 'nonregular Git entry' }); continue;
  }
  const bytes = execFileSync('git', ['cat-file', 'blob', object], { cwd: root, maxBuffer: 16 * 1024 * 1024 });
  const target = resolve(output, path);
  if (!target.startsWith(resolve(output) + '/')) throw new Error('unsafe path');
  mkdirSync(dirname(target), { recursive: true }); writeFileSync(target, bytes);
  chmodSync(target, mode === '100755' ? 0o755 : 0o644);
  inventory.push({ path, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
}
for (const [n, name] of ['foundation', 'reference', 'reading', 'cloud', 'release'].entries()) {
  const path = `evidence/current/0${n + 1}-${name}.json`;
  mkdirSync(dirname(resolve(output, path)), { recursive: true });
  const source = JSON.parse(execFileSync('git', ['show', `${commit}:${path}`], { cwd: root, encoding: 'utf8', maxBuffer: 1024 * 1024 }));
  writeFileSync(resolve(output, path), JSON.stringify({ source_commit: commit, scope: 'Community developer source preview',
    release_quality_ready: false, public_release_authorized: false,
    source_publication_authorized: publicationAuthorized,
    status: 'open', community: source.community ?? null,
    detail: 'Private historical evidence excluded. Only the current sanitized Community scope is included; open gates remain open.' }, null, 2) + '\n');
}
// Preserve account/auth route code, but a public source snapshot must not activate the production project.
const config = resolve(output, 'release/google-play/privacy/account/config.json');
mkdirSync(dirname(config), { recursive: true }); writeFileSync(config, JSON.stringify({ supabase_url: 'https://PROJECT.supabase.co', publishable_key: 'PUBLIC_CLIENT_KEY' }, null, 2) + '\n');
const headers = 'release/google-play/privacy/_headers';
const headersSource = execFileSync('git', ['show', `${commit}:${headers}`], { cwd: root, encoding: 'utf8' });
const headersBytes = Buffer.from(headersSource.replace(/https:\/\/[a-z0-9]+\.supabase\.co/g, 'https://PROJECT.supabase.co'));
writeFileSync(resolve(output, headers), headersBytes);
const headersEntry = inventory.find(entry => entry.path === headers);
Object.assign(headersEntry, { bytes: headersBytes.length, sha256: createHash('sha256').update(headersBytes).digest('hex'), transformation: 'production CSP binding replaced with example' });
writeFileSync(resolve(output, 'PUBLIC_EXPORT.json'), JSON.stringify({ source_commit: commit,
  has_git_history: false, scope: 'source snapshot; no APK publication', included: inventory, excluded,
  generated: ['PUBLIC_EXPORT.json', 'evidence/current/01-foundation.json', 'evidence/current/02-reference.json', 'evidence/current/03-reading.json', 'evidence/current/04-cloud.json', 'evidence/current/05-release.json', 'release/google-play/privacy/account/config.json'] }, null, 2) + '\n');
console.log(`Prepared ${inventory.length} source files; excluded ${excluded.length}. No Git history, push or publication: ${output}`);
