'use strict';

/**
 * Login page controller.
 *
 * Flow, matching the existing backend contracts:
 *   1. POST /user-access/api/auth/login  -> { accessToken, tokenType }
 *   2. POST /api/v1/session              -> 204 + HttpOnly MMIV_SESSION cookie
 *   3. GET  /api/v1/session              -> confirms the cookie and returns the
 *                                           CSRF contract for later pages
 *
 * An invitation that arrived in the URL fragment is passed on to the invitation
 * page instead of the workspace, so it can still be redeemed after signing in.
 *
 * The token only lives in a local variable for the duration of step 2. It is
 * never written to localStorage, sessionStorage or a script-readable cookie;
 * the browser keeps it in the HttpOnly cookie that step 2 sets.
 */
(() => {
  const LOGIN_URL = '/user-access/api/auth/login';
  const SESSION_URL = '/api/v1/session';
  const HOME_URL = '/pages/home.html';
  // The page that redeems an invitation, which the Gateway still serves.
  const INVITE_URL = '/invite.html';
  // The shape the invitation endpoint issues and redeems.
  const INVITATION_TOKEN = /^[a-f0-9]{64}$/;
  const REQUEST_TIMEOUT_MS = 20000;

  const form = document.getElementById('login-form');
  const username = document.getElementById('username');
  const password = document.getElementById('password');
  const messageBox = document.getElementById('msg');
  const submit = form ? form.querySelector('button[type="submit"]') : null;

  if (!form || !username || !password || !messageBox || !submit) return;

  // The markup exposes no live region, so screen readers would miss the result.
  messageBox.setAttribute('role', 'status');
  messageBox.setAttribute('aria-live', 'polite');

  let submitting = false;

  /**
   * Writes the result into the existing .msg element, keeping the base class
   * that login.css styles and adding .ok only for success.
   */
  function message(text, kind) {
    messageBox.textContent = text;
    messageBox.className = kind === 'success' ? 'msg ok' : 'msg';
  }

  function credentialsError(status) {
    if (status === 401) return 'Invalid username or password. Please try again.';
    if (status === 400) return 'Check your username and password and try again.';
    if (status === 429) return 'Too many requests. Please try again later.';
    if (status === 403 || status === 404)
      return 'The login service is unavailable. Contact your administrator.';
    return 'The login service is unavailable. Please try again later.';
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (submitting) return;

    if (location.protocol === 'file:') {
      message('Open this page through the development server, not as a local file.', 'error');
      return;
    }

    const name = username.value.trim();
    if (!name || !password.value) {
      message('Enter your username and password.', 'error');
      username.focus();
      return;
    }
    // User Access rejects passwords longer than 72 UTF-8 bytes; reporting that
    // here avoids a misleading "invalid username or password" reply.
    if (new TextEncoder().encode(password.value).length > 72) {
      message('Your password is too long.', 'error');
      password.focus();
      return;
    }

    submitting = true;
    submit.disabled = true;
    form.setAttribute('aria-busy', 'true');
    message('Checking your credentials…');

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    let authenticated = false;
    try {
      const response = await fetch(LOGIN_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ username: name, password: password.value }),
        credentials: 'same-origin',
        cache: 'no-store',
        signal: controller.signal,
      });
      if (!response.ok) throw new Error(credentialsError(response.status));

      let data;
      try {
        data = await response.json();
      } catch (_) {
        throw new Error('The login service returned an invalid response.');
      }
      if (
        typeof data.accessToken !== 'string' ||
        !data.accessToken.trim() ||
        typeof data.tokenType !== 'string' ||
        data.tokenType.toLowerCase() !== 'bearer'
      ) {
        throw new Error('The login service did not return a valid access token.');
      }

      // Exchange the verified Bearer token for the HttpOnly browser cookie.
      const exchange = await fetch(SESSION_URL, {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + data.accessToken },
        credentials: 'same-origin',
        cache: 'no-store',
        signal: controller.signal,
      });
      if (!exchange.ok)
        throw new Error('Could not establish your login session. Please try again.');

      const session = await fetch(SESSION_URL, {
        credentials: 'same-origin',
        cache: 'no-store',
        signal: controller.signal,
      });
      if (!session.ok)
        throw new Error('Your login cookie could not be verified. Enable cookies and try again.');

      // Read the session contract to prove the cookie authenticated the request.
      // Nothing from it is persisted on this page; later pages fetch it again.
      let current;
      try {
        current = await session.json();
      } catch (_) {
        throw new Error('Your login cookie could not be verified. Enable cookies and try again.');
      }
      if (typeof current.userId !== 'string' || !current.userId.trim())
        throw new Error('Your login cookie could not be verified. Enable cookies and try again.');

      authenticated = true;
      password.value = '';
      message('Signed in successfully. Opening your workspace…', 'success');
      // An invitation carried in with the sign-in is handed back to the page
      // that redeems it, so the recipient never has to follow the link again.
      const invitation = new URLSearchParams((location.hash || '').slice(1)).get('invite');
      const valid = invitation && INVITATION_TOKEN.test(invitation);
      location.assign(valid ? INVITE_URL + '#invite=' + invitation : HOME_URL);
    } catch (error) {
      message(
        error.name === 'AbortError'
          ? 'The login request timed out. Please try again.'
          : error instanceof TypeError
            ? 'Could not reach the login service. Check your connection and try again.'
            : error.message,
        'error',
      );
    } finally {
      clearTimeout(timeout);
      submitting = false;
      // Stay disabled while the browser navigates away, so a second click
      // cannot start another login.
      if (!authenticated) {
        submit.disabled = false;
        form.removeAttribute('aria-busy');
      }
    }
  });
})();
