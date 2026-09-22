import { loadConfig, api, readSession, saveSession, accessToken } from './auth.js';
const { t, localeTag } = window.byeI18n;
const $ = (id) => document.getElementById(id);
const message = (text) => { $('message').textContent = text; };
const authRedirect = () => 'https://beyoureye.com/auth/callback';
let ready = false;
let busy = false;
let priceReady = false;
const billing = async (body) => api('/functions/v1/website-billing', body, await accessToken());
function failure(error) {
  if (error.status === 401 || error.message === 'sign_in_required') {
    saveSession(null); $('login-panel').hidden = false; $('account-panel').hidden = true;
    message(t('loginRequired'));
  } else if (error.status === 429) message(t('tooFrequent'));
  else if (error.message === 'website_billing_not_enabled') message(t('billingClosed'));
  else if (error.message === 'weak_password') message(t('weakPassword'));
  else if (error.message === 'new_checkout_required') {
    sessionStorage.removeItem('beyoureye.checkout'); message(t('checkoutExpired'));
  } else if (error.status === 400 || error.status === 422) message(t('invalidCredentials'));
  else message(t('serviceUnavailable'));
}
async function action(fn) {
  if (busy) return;
  busy = true;
  renderButtons();
  try { await fn(); } catch (error) { failure(error); }
  finally { busy = false; renderButtons(); }
}
function renderButtons() {
  document.querySelectorAll('button').forEach((button) => { button.disabled = busy || !ready; });
  $('buy').disabled = busy || !readSession() || !priceReady;
}
async function loadAccount() {
  priceReady = false;
  renderButtons();
  const session = readSession();
  $('login-panel').hidden = !!session; $('account-panel').hidden = !session;
  if (!session) return;
  // Preference sync should never prevent a signed-in user from viewing access.
  try { await billing({ action: 'set_locale', locale: localeTag() }); } catch { /* best effort */ }
  $('account-email').textContent = session.user?.email || '';
  $('access-status').textContent = t('checking');
  $('price').textContent = t('readingPrice');
  $('expiry').textContent = '';
  $('trial').hidden = true;
  let result;
  try { result = await billing({ action: 'status' }); }
  catch (error) { $('access-status').textContent = t('unavailable'); $('price').textContent = t('notOpen'); throw error; }
  const active = result.expires_at && Date.parse(result.expires_at) > Date.now();
  $('access-status').textContent = active ? (result.state === 'WEBSITE_TRIAL_ACTIVE' ? t('trialActive') : t('active')) : t('noAccess');
  $('expiry').textContent = active ? t('expiry') + new Date(result.expires_at).toLocaleString(localeTag()) : '';
  $('price').textContent = result.billing_enabled === false ? t('notOpen') : new Intl.NumberFormat(localeTag(), { style: 'currency', currency: result.currency }).format(result.amount / 100);
  $('trial').hidden = !result.trial_available;
  priceReady = result.billing_enabled !== false && Number.isSafeInteger(result.amount) && result.amount > 0;
  $('billing-note').textContent = result.billing_enabled === false ? t('billingClosed') : t('billingNote');
  renderButtons();
}
$('auth-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const signup = event.submitter?.value === 'signup';
  action(async () => {
    const email = $('email').value.trim(), password = $('password').value;
    if (signup && (!/[a-zA-Z]/.test(password) || !/[0-9]/.test(password))) { message(t('weakPassword')); return; }
    const result = await api(signup ? `/auth/v1/signup?redirect_to=${encodeURIComponent(authRedirect())}` : '/auth/v1/token?grant_type=password', signup ? { email, password, data: { preferred_locale: localeTag() } } : { email, password });
    $('password').value = '';
    if (signup) { message(t('signupSent')); return; }
    saveSession(result); await loadAccount(); await syncReturn();
    if (!new URL(location.href).searchParams.has('session_id')) message(t('loggedIn'));
  });
});
$('recover').addEventListener('click', () => action(async () => {
  if (!$('email').reportValidity()) return;
  await api(`/auth/v1/recover?redirect_to=${encodeURIComponent(authRedirect())}`, { email: $('email').value.trim() });
  $('password').value = '';
  message(t('recoverySent'));
}));
$('resend').addEventListener('click', () => action(async () => {
  if (!$('email').reportValidity()) return;
  await api(`/auth/v1/resend?redirect_to=${encodeURIComponent(authRedirect())}`, { type: 'signup', email: $('email').value.trim() });
  message(t('signupSent'));
}));
$('logout').addEventListener('click', () => action(async () => {
  try { if (readSession()) await api('/auth/v1/logout?scope=local', {}, await accessToken()); }
  finally { saveSession(null); sessionStorage.removeItem('beyoureye.checkout'); await loadAccount(); message(t('loggedOut')); }
}));
$('trial').addEventListener('click', () => action(async () => {
  await billing({ action: 'trial', locale: localeTag() }); await loadAccount(); message(t('trialStarted'));
}));
$('buy').addEventListener('click', () => action(async () => {
  let requestId = sessionStorage.getItem('beyoureye.checkout');
  if (!requestId) { requestId = crypto.randomUUID(); sessionStorage.setItem('beyoureye.checkout', requestId); }
  const result = await billing({ action: 'checkout', request_id: requestId, locale: localeTag() });
  const url = new URL(result.url);
  if (url.protocol !== 'https:' || url.hostname !== 'checkout.stripe.com') throw new Error('invalid_checkout');
  location.assign(url.href);
}));
$('refresh').addEventListener('click', () => action(async () => { await syncReturn(); await loadAccount(); message(t('accessRefreshed')); }));
async function syncReturn() {
  const url = new URL(location.href), sessionId = url.searchParams.get('session_id');
  if (!sessionId || !readSession()) return;
  message(t('paymentChecking'));
  await billing({ action: 'sync', session_id: sessionId });
  await loadAccount();
  sessionStorage.removeItem('beyoureye.checkout');
  message(t('paymentChecked'));
}
try {
  renderButtons();
  await loadConfig(); ready = true;
  await loadAccount(); await syncReturn();
} catch (error) { failure(error); }

finally { renderButtons(); }
