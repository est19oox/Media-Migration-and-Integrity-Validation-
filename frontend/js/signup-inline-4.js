'use strict';
const form = document.getElementById('signupForm');
const username = document.getElementById('username');
const password = document.getElementById('password');
const confirmPassword = document.getElementById('confirm');
const submit = document.getElementById('submit');
const status = document.getElementById('status');
const toggle = document.getElementById('toggle');
function message(text, kind) {
  status.textContent = text;
  status.className = 'msg' + (kind === 'success' ? ' ok' : '');
  if (kind === 'error')
    globalThis.mmivFeedback?.notice('Please check your account details', text, 'error');
}
toggle.addEventListener('click', () => {
  const show = password.type === 'password';
  password.type = show ? 'text' : 'password';
  toggle.textContent = show ? 'Hide' : 'Show';
  toggle.setAttribute('aria-pressed', String(show));
});
form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (location.protocol === 'file:' || location.port === '8083') {
    message('Open signup through the Gateway on port 8080.', 'error');
    return;
  }
  const name = username.value.trim();
  if (!/^[A-Za-z0-9_.-]{3,40}$/.test(name)) {
    message('Use 3–40 letters, numbers, dots, underscores or hyphens for your username.', 'error');
    return;
  }
  if (
    password.value.length < 8 ||
    !password.value.trim() ||
    new TextEncoder().encode(password.value).length > 72
  ) {
    message(
      'Choose a nonblank password with at least 8 characters and no more than 72 UTF-8 bytes.',
      'error',
    );
    return;
  }
  if (password.value !== confirmPassword.value) {
    message('The passwords do not match.', 'error');
    return;
  }
  submit.disabled = true;
  submit.textContent = 'Creating account…';
  form.setAttribute('aria-busy', 'true');
  message('Creating your account…');
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20000);
  try {
    const response = await fetch('/user-access/api/auth/signup', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ username: name, password: password.value }),
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal,
    });
    if (response.status === 409)
      throw new Error('That username is already taken. Choose another one.');
    if (response.status === 400) throw new Error('Check the username and password requirements.');
    if (response.status !== 201)
      throw new Error('Could not create your account. Please try again later.');
    password.value = '';
    confirmPassword.value = '';
    message('Account created. Opening sign in…', 'success');
    location.assign('/login.html?registered=1');
  } catch (error) {
    message(
      error.name === 'AbortError'
        ? 'The request timed out. Try signing in first; your account may have been created.'
        : error instanceof TypeError
          ? 'Could not reach the signup service. Please try again.'
          : error.message,
      'error',
    );
  } finally {
    clearTimeout(timeout);
    submit.disabled = false;
    submit.textContent = 'Create account';
    form.removeAttribute('aria-busy');
  }
});
