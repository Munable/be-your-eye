import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { accessToken, loadConfig, readSession, recoveryTokens, saveSession } from '../../release/google-play/privacy/account/auth.js';

const config = { supabase_url: 'https://example.supabase.co', publishable_key: 'public-test-key' };
beforeEach(() => {
  const storage = new Map();
  globalThis.sessionStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) };
  globalThis.fetch = async () => Response.json(config);
});

test('switching accounts or signing out clears the prior checkout request', () => {
  saveSession({ user: { id: 'one' } });
  sessionStorage.setItem('beyoureye.checkout', 'order-one');
  saveSession({ user: { id: 'one' }, access_token: 'refreshed' });
  assert.equal(sessionStorage.getItem('beyoureye.checkout'), 'order-one');
  saveSession({ user: { id: 'two' } });
  assert.equal(sessionStorage.getItem('beyoureye.checkout'), null);
  sessionStorage.setItem('beyoureye.checkout', 'order-two');
  saveSession(null);
  assert.equal(sessionStorage.getItem('beyoureye.checkout'), null);
  assert.equal(readSession(), null);
});

test('missing and expired local expiry refresh before authenticated requests', async () => {
  await loadConfig();
  for (const expires_at of [undefined, 0]) {
    saveSession({ user: { id: 'one' }, access_token: 'old', refresh_token: 'refresh', expires_at });
    let calls = 0;
    globalThis.fetch = async (url, options) => {
      calls++;
      assert.equal(url, config.supabase_url + '/auth/v1/token?grant_type=refresh_token');
      assert.equal(JSON.parse(options.body).refresh_token, 'refresh');
      return Response.json({ user: { id: 'one' }, access_token: 'new', refresh_token: 'rotated', expires_at: Math.floor(Date.now() / 1000) + 3600 });
    };
    assert.equal(await accessToken(), 'new');
    assert.equal(calls, 1);
  }
});

test('revoked refresh tokens clear the session and checkout without returning a token', async () => {
  await loadConfig();
  saveSession({ user: { id: 'one' }, refresh_token: 'revoked', expires_at: 0 });
  sessionStorage.setItem('beyoureye.checkout', 'order-one');
  globalThis.fetch = async () => Response.json({ error_code: 'refresh_token_not_found' }, { status: 400 });
  await assert.rejects(accessToken, /refresh_token_not_found/);
  assert.equal(readSession(), null);
  assert.equal(sessionStorage.getItem('beyoureye.checkout'), null);
});

test('network errors retain a session for retry instead of silently switching accounts', async () => {
  await loadConfig();
  saveSession({ user: { id: 'one' }, refresh_token: 'valid', expires_at: 0 });
  globalThis.fetch = async () => { throw new Error('network unavailable'); };
  await assert.rejects(accessToken, /network unavailable/);
  assert.equal(readSession().user.id, 'one');
});

test('auth callback rejects missing, expired, unsupported and malformed credentials', () => {
  const base = '#access_token=fixture-access&refresh_token=fixture-refresh&expires_in=3600&type=';
  for (const fragment of ['', '#error=access_denied', base + 'unknown', base + 'recovery&error_code=otp_expired', base.replace('3600', 'NaN') + 'signup', base.replace('3600', '-1') + 'signup', base.replace('3600', '999999999') + 'signup']) {
    assert.throws(() => recoveryTokens(fragment));
  }
  const before = Math.floor(Date.now() / 1000);
  const result = recoveryTokens(base + 'recovery');
  assert.equal(result.type, 'recovery');
  assert.ok(result.expires_at >= before + 3600 && result.expires_at <= before + 3601);
});

test('configuration rejects credential-bearing and off-host API URLs', async () => {
  for (const url of ['http://example.supabase.co', 'https://evil.example', 'https://example.supabase.co.evil.example', 'https://secret@example.supabase.co', 'https://example.supabase.co/path', 'https://example.supabase.co?secret=1']) {
    globalThis.fetch = async () => Response.json({ ...config, supabase_url: url });
    await assert.rejects(loadConfig, /invalid_configuration/);
  }
});
