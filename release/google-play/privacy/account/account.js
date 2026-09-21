import { loadConfig, api, readSession, saveSession, accessToken } from './auth.js';
const $ = (id) => document.getElementById(id);
const message = (text) => { $('message').textContent = text; };
let ready = false;
let busy = false;
let priceReady = false;
const billing = async (body) => api('/functions/v1/website-billing', body, await accessToken());
function failure(error) {
  if (error.status === 401 || error.message === 'sign_in_required') {
    saveSession(null); $('login-panel').hidden = false; $('account-panel').hidden = true;
    message('请登录已确认邮箱的账号。如果刚注册，请先点击确认邮件中的链接。');
  } else if (error.status === 429) message('请求较频繁，请稍后重试。已经完成的付款不会因此丢失。');
  else if (error.message === 'website_billing_not_enabled') message('官网购买与领取试用尚未开放。已有账号仍可登录并查看权益。');
  else if (error.message === 'weak_password') message('密码至少 8 位，并须包含字母和数字。');
  else if (error.message === 'new_checkout_required') {
    sessionStorage.removeItem('beyoureye.checkout'); message('这笔结账已结束或过期，请重新点击购买。');
  } else if (error.status === 400 || error.status === 422) message('请检查邮箱和密码；密码至少 8 位，包含字母和数字。若刚注册，请先确认邮箱，也可重新发送确认邮件。');
  else message('暂时无法连接服务，请稍后重试。若已付款，请勿重复支付，可刷新权益或联系产品支持。');
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
  $('account-email').textContent = session.user?.email || '';
  $('access-status').textContent = '正在核对账号权益…';
  $('price').textContent = '正在读取价格…';
  $('expiry').textContent = '';
  $('trial').hidden = true;
  let result;
  try { result = await billing({ action: 'status' }); }
  catch (error) { $('access-status').textContent = '权益暂时无法读取'; $('price').textContent = '暂不可购买'; throw error; }
  const active = result.expires_at && Date.parse(result.expires_at) > Date.now();
  $('access-status').textContent = active ? (result.state === 'WEBSITE_TRIAL_ACTIVE' ? '全功能试用中' : 'Pro 使用权有效') : '尚无有效使用权';
  $('expiry').textContent = active ? '当前权益到期：' + new Date(result.expires_at).toLocaleString() : '';
  $('price').textContent = result.billing_enabled === false ? '尚未开放' : new Intl.NumberFormat('zh-CN', { style: 'currency', currency: result.currency }).format(result.amount / 100);
  $('trial').hidden = !result.trial_available;
  priceReady = result.billing_enabled !== false && Number.isSafeInteger(result.amount) && result.amount > 0;
  $('billing-note').textContent = result.billing_enabled === false ? '官网购买与领取试用尚未开放；已有权益不受影响。' : '购买或领取试用后，在 App 登录此账号，点击“刷新权益”即可使用。';
  renderButtons();
}
$('auth-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const signup = event.submitter?.value === 'signup';
  action(async () => {
    const email = $('email').value.trim(), password = $('password').value;
    if (signup && (!/[a-zA-Z]/.test(password) || !/[0-9]/.test(password))) { message('密码至少 8 位，并须包含字母和数字。'); return; }
    const result = await api(signup ? '/auth/v1/signup?redirect_to=https%3A%2F%2Fbeyoureye.com%2Fauth%2Fcallback' : '/auth/v1/token?grant_type=password', { email, password });
    $('password').value = '';
    if (signup) { message('请查收确认邮件（也请检查垃圾邮件），确认后在这里登录。'); return; }
    saveSession(result); await loadAccount(); await syncReturn();
    if (!new URL(location.href).searchParams.has('session_id')) message('登录成功。');
  });
});
$('recover').addEventListener('click', () => action(async () => {
  if (!$('email').reportValidity()) return;
  await api('/auth/v1/recover?redirect_to=https%3A%2F%2Fbeyoureye.com%2Fauth%2Fcallback', { email: $('email').value.trim() });
  $('password').value = '';
  message('如果该邮箱已注册，你会收到重置密码邮件。请检查收件箱及垃圾邮件；链接可在网页或 App 中打开。');
}));
$('resend').addEventListener('click', () => action(async () => {
  if (!$('email').reportValidity()) return;
  await api('/auth/v1/resend?redirect_to=https%3A%2F%2Fbeyoureye.com%2Fauth%2Fcallback', { type: 'signup', email: $('email').value.trim() });
  message('如果该账号需要确认邮箱，确认邮件已重新发送。请同时检查垃圾邮件。');
}));
$('logout').addEventListener('click', () => action(async () => {
  try { if (readSession()) await api('/auth/v1/logout?scope=local', {}, await accessToken()); }
  finally { saveSession(null); sessionStorage.removeItem('beyoureye.checkout'); await loadAccount(); message('已退出登录。'); }
}));
$('trial').addEventListener('click', () => action(async () => {
  await billing({ action: 'trial' }); await loadAccount(); message('试用已开通。请回到 App 刷新权益。');
}));
$('buy').addEventListener('click', () => action(async () => {
  let requestId = sessionStorage.getItem('beyoureye.checkout');
  if (!requestId) { requestId = crypto.randomUUID(); sessionStorage.setItem('beyoureye.checkout', requestId); }
  const result = await billing({ action: 'checkout', request_id: requestId });
  const url = new URL(result.url);
  if (url.protocol !== 'https:' || url.hostname !== 'checkout.stripe.com') throw new Error('invalid_checkout');
  location.assign(url.href);
}));
$('refresh').addEventListener('click', () => action(async () => { await syncReturn(); await loadAccount(); message('权益已刷新。'); }));
async function syncReturn() {
  const url = new URL(location.href), sessionId = url.searchParams.get('session_id');
  if (!sessionId || !readSession()) return;
  message('正在向支付服务核对到账结果…');
  await billing({ action: 'sync', session_id: sessionId });
  await loadAccount();
  sessionStorage.removeItem('beyoureye.checkout');
  message('已核对支付状态，请以上方权益状态为准。到账处理可能需要片刻，可稍后刷新。');
}
try {
  renderButtons();
  await loadConfig(); ready = true;
  await loadAccount(); await syncReturn();
} catch (error) { failure(error); }

finally { renderButtons(); }
