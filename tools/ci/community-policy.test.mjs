import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { validateCommunityWorkflow } from './community-policy.mjs';
const workflow = readFileSync(new URL('../../.github/workflows/community.yml', import.meta.url), 'utf8');
test('Community checks run untrusted changes with no publishing authority', () => {
  assert.deepEqual(validateCommunityWorkflow(workflow), []);
  for (const unsafe of [workflow.replace('pull_request:', 'pull_request_target:'), workflow.replace('contents: read', 'contents: write'), workflow.replace(/checkout@[0-9a-f]+/u, 'checkout@main'), workflow + '\n  env: ${{ secrets.RELEASE_KEY }}']) {
    assert.ok(validateCommunityWorkflow(unsafe).length);
  }
});
