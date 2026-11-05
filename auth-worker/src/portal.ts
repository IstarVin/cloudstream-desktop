export const page = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Cloudstream account</title><link rel="stylesheet" href="/style.css"><script src="/app.js" defer></script></head>
<body><main><a class="brand" href="/">Cloudstream</a><div id="loading" role="status">Opening your account…</div>
<section id="login" hidden><h1>Sign in</h1><p>Use your Cloudstream owner account to connect a device.</p>
<form id="login-form"><label>Username<input name="username" autocomplete="username" required maxlength="64"></label><label>Password<input name="password" type="password" autocomplete="current-password" required maxlength="256"></label><button>Sign in</button></form></section>
<section id="signed-in" hidden><div class="account-line"><span id="identity"></span><button id="logout" class="secondary">Sign out</button></div>
<h1>Connect a device</h1><p>Enter the six-character code shown on your TV, phone, or browser.</p>
<form id="code-form"><label>Device code<input name="userCode" id="user-code" class="code" autocomplete="off" autocapitalize="characters" spellcheck="false" maxlength="7" placeholder="ABC 234" required></label><button>Check device</button></form>
<div id="approval" hidden role="region" aria-labelledby="approval-heading"><h2 id="approval-heading" tabindex="-1">Is this your device?</h2><dl><dt>Server</dt><dd id="server-name"></dd><dt>Device</dt><dd id="device-name"></dd></dl><p>Approve only if this code is displayed on the device you are signing in to.</p><div class="actions"><button id="approve">Approve sign-in</button><button id="decline" class="secondary">Decline</button></div></div>
<details><summary>Account settings</summary><h2>Change your sign-in</h2><p>Changing your credentials signs out all connected devices within one minute.</p><form id="account-form"><label>Username<input name="username" id="account-username" autocomplete="username" required maxlength="64"></label><label>Current password<input name="currentPassword" type="password" autocomplete="current-password" required maxlength="256"></label><label>New password<input name="password" type="password" autocomplete="new-password" minlength="12" maxlength="256" required aria-describedby="password-help"></label><p id="password-help">Use at least 12 characters.</p><button>Save credentials</button></form><h2>Connected devices</h2><p>Sign out devices on every server. You stay signed in here.</p><button id="revoke-all" class="secondary">Sign out all devices</button></details></section>
<p id="message" role="status" aria-live="polite"></p></main></body></html>`;

export const script = String.raw`
const $ = id => document.getElementById(id);
let checkedCode = '';
function message(text, error = false) { $('message').textContent = text; $('message').className = error ? 'error' : ''; }
function signedIn(name) {
  $('loading').hidden = true; $('login').hidden = !!name; $('signed-in').hidden = !name;
  if (name) { $('identity').textContent = 'Signed in as ' + name; $('account-username').value = name; }
}
async function api(path, data) {
  const res = await fetch('/api/' + path, { method: data === undefined ? 'GET' : 'POST', headers: data === undefined ? {} : {'content-type':'application/json'}, body: data === undefined ? undefined : JSON.stringify(data) });
  const result = await res.json();
  if (!res.ok) { if (res.status === 401 && path !== 'login' && path !== 'account') signedIn(''); throw Error(result.error || 'Could not complete this action. Try again.'); }
  return result;
}
function action(element, event, fn) {
  element.addEventListener(event, async e => {
    e.preventDefault(); message('');
    const buttons = Array.from(document.querySelectorAll('button')); buttons.forEach(b => b.disabled = true);
    let focus;
    try { focus = await fn(); } catch (e) { message(e.message, true); }
    finally { buttons.forEach(b => b.disabled = false); }
    focus?.focus();
  });
}
action($('login-form'), 'submit', async () => {
  const form = $('login-form'), data = Object.fromEntries(new FormData(form));
  form.elements.password.value = '';
  signedIn((await api('login', data)).username); $('user-code').focus();
});
action($('logout'), 'click', async () => { await api('logout', {}); $('approval').hidden = true; signedIn(''); });
action($('code-form'), 'submit', async () => {
  $('approval').hidden = true; checkedCode = $('user-code').value.toUpperCase().replace(/[\s-]/g, '');
  const info = await api('device/lookup', { userCode: checkedCode });
  $('server-name').textContent = info.serverName; $('device-name').textContent = info.deviceName;
  $('approval').hidden = false; return $('approval-heading');
});
$('user-code').addEventListener('input', () => { $('approval').hidden = true; checkedCode = ''; });
for (const approve of [true, false]) action($(approve ? 'approve' : 'decline'), 'click', async () => {
  await api('device/approve', { userCode: checkedCode, approve });
  $('approval').hidden = true; $('user-code').value = ''; checkedCode = '';
  message(approve ? 'Device approved. It will sign in automatically.' : 'Sign-in declined.');
});
action($('account-form'), 'submit', async () => {
  const form = $('account-form'), data = Object.fromEntries(new FormData(form));
  form.elements.password.value = ''; form.elements.currentPassword.value = '';
  const result = await api('account', data); signedIn(result.username); $('approval').hidden = true;
  message('Credentials saved. Other devices will be signed out within one minute.');
});
action($('revoke-all'), 'click', async () => {
  if (!confirm('Sign out every device on all your servers?')) return;
  await api('revoke-all', {}); $('approval').hidden = true;
  message('Devices will be signed out within one minute.');
});
api('account').then(result => signedIn(result.username)).catch(e => { signedIn(''); if (e.message !== 'Sign in to continue') message(e.message, true); });
`;

export const style = `
:root{color-scheme:dark;font:16px/1.5 system-ui,sans-serif;background:#0e0f13;color:#eceef3;accent-color:#ff8a4c}
*{box-sizing:border-box}body{margin:0}main{width:min(100% - 40px,480px);margin:clamp(24px,7vh,80px) auto;padding-bottom:40px}a{color:#ff8a4c;text-underline-offset:4px}.brand{display:inline-block;font-size:20px;font-weight:750;text-decoration:none;margin-bottom:40px}
h1{font-size:32px;line-height:1.2;letter-spacing:-.025em;margin:0 0 12px}h2{font-size:20px;margin:28px 0 12px}p{color:#a6abba;margin:0 0 24px}form,label{display:grid;gap:8px}form{gap:20px}label{font-weight:600;font-size:14px}input,button{font:inherit;border-radius:8px;min-height:48px;padding:12px 14px;border:1px solid #3c414e;max-width:100%;min-width:0}input{background:#16181e;color:#eceef3;width:100%;caret-color:#ff8a4c}input::placeholder{color:#a6abba}button{background:#ff8a4c;color:#1d0e05;border-color:transparent;font-weight:650;cursor:pointer}button:hover{filter:brightness(1.08)}button:disabled{opacity:.5;cursor:wait}.secondary{background:#1e2129;color:#eceef3;border-color:#3c414e}:focus-visible{outline:2px solid #ff8a4c;outline-offset:3px}::selection{background:#ff8a4c;color:#1d0e05}.code{font-size:28px;letter-spacing:.15em;text-transform:uppercase;font-variant-numeric:tabular-nums}.account-line{display:flex;align-items:center;justify-content:space-between;gap:12px;color:#a6abba;font-size:14px;margin-bottom:28px}.account-line button{padding:8px 12px}.actions{display:flex;flex-wrap:wrap;gap:12px}details{margin-top:40px;border-top:1px solid #333741;padding-top:24px}summary{cursor:pointer;font-weight:600;min-height:44px}dl{display:grid;grid-template-columns:auto 1fr;gap:8px 20px}dt{color:#a6abba}dd{margin:0;overflow-wrap:anywhere}#approval{margin-top:28px;padding-top:4px;border-top:1px solid #333741}#message{margin-top:24px;color:#53d3a3;overflow-wrap:anywhere}#message.error{color:#ff8290}[hidden]{display:none!important}
`;
