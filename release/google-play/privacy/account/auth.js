const sessionKey = 'beyoureye.session';
let config;
export async function loadConfig() {
  const response = await fetch('/account/config.json', { cache: 'no-store' });
  if (!response.ok) throw new Error('configuration_unavailable');
  const value = await response.json();
  const url = new URL(value.supabase_url);
  if (url.protocol !== 'https:' || !url.hostname.endsWith('.supabase.co') || url.pathname !== '/' || url.username || url.password || url.search || url.hash) throw new Error('invalid_configuration');
  config = value;
}
export async function api(path, body, token, method = 'POST') {
  const response = await fetch(config.supabase_url + path, {
    method,
    headers: { 'Content-Type': 'application/json', apikey: config.publishable_key, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(25000),
  });
  const result = response.status === 204 ? null : await response.json();
  if (!response.ok) {
    const error = new Error(result?.error_code || result?.error || result?.code || 'unavailable');
    error.status = response.status;
    throw error;
  }
  return result;
}
export function readSession() {
  try { return JSON.parse(sessionStorage.getItem(sessionKey) || 'null'); } catch { return null; }
}
export function saveSession(value) {
  if (readSession()?.user?.id !== value?.user?.id) sessionStorage.removeItem('beyoureye.checkout');
  if (value) sessionStorage.setItem(sessionKey, JSON.stringify(value));
  else sessionStorage.removeItem(sessionKey);
}
export async function accessToken() {
  let session = readSession();
  if (!session) throw new Error('sign_in_required');
  if (!Number.isFinite(session.expires_at) || session.expires_at * 1000 < Date.now() + 60000) {
    try {
      session = await api('/auth/v1/token?grant_type=refresh_token', { refresh_token: session.refresh_token });
      saveSession(session);
    } catch (error) {
      if (error.status === 400 || error.status === 401) saveSession(null);
      throw error;
    }
  }
  return session.access_token;
}
export function recoveryTokens(hash) {
  const params = new URLSearchParams(hash.replace(/^#/, ''));
  if (params.has('error') || params.has('error_code')) throw new Error('link_expired');
  const access = params.get('access_token'), refresh = params.get('refresh_token');
  const type = params.get('type');
  const lifetime = Number(params.get('expires_in'));
  if (!access || !refresh || !['signup', 'recovery', 'email_change', 'magiclink'].includes(type) || !Number.isSafeInteger(lifetime) || lifetime <= 0 || lifetime > 86400) throw new Error('link_invalid');
  return { access_token: access, refresh_token: refresh, expires_at: Math.floor(Date.now() / 1000) + lifetime, type };
}
