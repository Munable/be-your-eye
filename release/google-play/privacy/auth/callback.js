import { loadConfig, api, saveSession, recoveryTokens } from '/account/auth.js';
const { t } = window.byeI18n;
const $ = (id) => document.getElementById(id);
// Remove bearer tokens before any request or further navigation. Never log them.
let fragment = location.hash;
history.replaceState(null, '', location.pathname);
let recovery;
let busy = false;
try {
  const result = recoveryTokens(fragment);
  fragment = '';
  await loadConfig();
  const user = await api('/auth/v1/user', undefined, result.access_token, 'GET');
  if (!user.id || !user.email_confirmed_at) throw new Error('unconfirmed_email');
  saveSession(null);
  if (result.type === 'recovery') {
    recovery = result;
    $('title').textContent = t('recoveryTitle');
    $('message').textContent = t('recoveryVerified');
    $('account-email').textContent = user.email;
    $('recovery-panel').hidden = false;
  } else {
    const { type, ...session } = result;
    saveSession({ ...session, user });
    $('title').textContent = t('confirmedTitle');
    $('message').textContent = t('confirmedMessage');
  }
} catch {
  $('title').textContent = t('recoveryExpiredTitle');
  $('message').textContent = t('recoveryExpiredMessage');
} finally { fragment = ''; }
$('recovery-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!recovery || busy) return;
  const password = $('password').value;
  if (password !== $('password-again').value) { $('message').textContent = t('passwordMismatch'); return; }
  if (!/[a-zA-Z]/.test(password) || !/[0-9]/.test(password)) { $('message').textContent = t('passwordRequirements'); return; }
  busy = true; $('save-password').disabled = true;
  try {
    await api('/auth/v1/user', { password }, recovery.access_token, 'PUT');
    $('password').value = ''; $('password-again').value = '';
    $('recovery-panel').hidden = true;
    // The recovery session is not retained as a normal website login.
    try { await api('/auth/v1/logout', {}, recovery.access_token); } catch { /* The recovery session may already be invalidated. */ }
    recovery = null; saveSession(null);
    $('title').textContent = t('passwordUpdatedTitle');
    $('message').textContent = t('passwordUpdatedMessage');
  } catch (error) {
    $('message').textContent = error.message === 'weak_password' ? t('passwordRequirements') : t('passwordUpdateFailed');
  } finally { busy = false; $('save-password').disabled = false; }
});
