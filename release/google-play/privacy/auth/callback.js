import { loadConfig, api, saveSession, recoveryTokens } from '/account/auth.js';
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
    $('title').textContent = '设置新密码 / Reset password';
    $('message').textContent = '邮箱已验证。请在下方设置新密码。Your email is verified. Choose a new password below.';
    $('account-email').textContent = user.email;
    $('recovery-panel').hidden = false;
  } else {
    const { type, ...session } = result;
    saveSession({ ...session, user });
    $('title').textContent = '邮箱已确认 / Email confirmed';
    $('message').textContent = '账号已登录，可前往账号页面；在 App 中使用相同邮箱和密码登录。You can now open your account, or sign in to the app with the same email and password.';
  }
} catch {
  $('title').textContent = '请重新打开邮件链接 / Reopen the email link';
  $('message').textContent = '链接可能已使用、过期，或暂时无法连接。请回到账号页面重发确认邮件或找回密码。The link may be used, expired, or temporarily unavailable. Request a new link from the account page.';
} finally { fragment = ''; }
$('recovery-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!recovery || busy) return;
  const password = $('password').value;
  if (password !== $('password-again').value) { $('message').textContent = '两次密码不一致。Passwords do not match.'; return; }
  if (!/[a-zA-Z]/.test(password) || !/[0-9]/.test(password)) { $('message').textContent = '密码需要同时包含字母和数字。Include both letters and numbers.'; return; }
  busy = true; $('save-password').disabled = true;
  try {
    await api('/auth/v1/user', { password }, recovery.access_token, 'PUT');
    $('password').value = ''; $('password-again').value = '';
    $('recovery-panel').hidden = true;
    // The recovery session is not retained as a normal website login.
    try { await api('/auth/v1/logout', {}, recovery.access_token); } catch { /* The recovery session may already be invalidated. */ }
    recovery = null; saveSession(null);
    $('title').textContent = '密码已更新 / Password updated';
    $('message').textContent = '请使用新密码登录网页或 App。Sign in to the website or app with your new password.';
  } catch (error) {
    $('message').textContent = error.message === 'weak_password' ? '密码至少 8 位，包含字母和数字。Use at least 8 characters, including letters and numbers.' : '未能更新密码。请重试；链接过期时请重新发送恢复邮件。Could not update your password. Retry or request a new recovery link.';
  } finally { busy = false; $('save-password').disabled = false; }
});
