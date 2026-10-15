'use strict';

/**
 * Shared session helper for the authenticated MMIV pages.
 *
 * Ported from the proven services/transfer-service/src/main/resources/static/auth.js,
 * with the page paths adapted to this frontend. The browser never holds the JWT:
 * Transfer keeps it in the HttpOnly MMIV_SESSION cookie, and this helper only
 * reads the session contract that the cookie authorises. Nothing is written to
 * localStorage, sessionStorage or a script-readable cookie.
 *
 * Load this before any page script that calls mmivAuth.fetch, and never on the
 * login or signup pages: it redirects to the login page when there is no session.
 */
(() => {
  // A repeated <script> tag must not start a second session request or attach a
  // second logout listener.
  if (window.mmivAuth) return;

  const SESSION_URL = '/api/v1/session';
  const LOGIN_PAGE = '/pages/login.html';
  const SAFE_METHODS = ['GET', 'HEAD', 'OPTIONS'];
  // The shape the invitation endpoint issues and redeems.
  const INVITATION_TOKEN = /^[a-f0-9]{64}$/;

  let session;
  let logoutWired = false;

  /**
   * Leaves for the login page.
   *
   * An invitation the recipient arrived with is carried across in the fragment,
   * so it survives the sign-in detour and the invitation page can still redeem
   * it afterwards. A fragment that is not a well-formed token is dropped rather
   * than forwarded. The token is never logged or stored, and location.replace
   * keeps it out of the session history.
   *
   * The throw stops the calling code path, because the navigation itself
   * completes asynchronously.
   */
  function login() {
    const invitation = new URLSearchParams((location.hash || '').slice(1)).get('invite');
    const valid = invitation && INVITATION_TOKEN.test(invitation);
    location.replace(LOGIN_PAGE + (valid ? '#invite=' + invitation : ''));
    throw new Error('Please sign in again.');
  }

  /** Reports an unrecoverable session problem in the page's status area. */
  function report(message) {
    const status = document.getElementById('status') || document.getElementById('result');
    if (status) status.textContent = message;
    else alert(message);
  }

  async function requestSession() {
    const response = await fetch(SESSION_URL, {
      credentials: 'same-origin',
      cache: 'no-store',
    });
    if (response.status === 401) return login();
    if (!response.ok) throw new Error('Unable to verify your login. Please reload the page.');
    return response.json();
  }

  /**
   * Resolves with { userId, csrfToken, csrfHeader, expiresAt, expiresInSeconds }.
   * Every authenticated request waits for this, so page scripts never have to
   * sequence their own calls behind the session check.
   */
  const ready = requestSession().then((value) => {
    session = value;
    const expiresIn = Number(value.expiresInSeconds) * 1000;
    if (Number.isFinite(expiresIn)) {
      // Uses the remaining lifetime the server reported, so an incorrect browser
      // clock cannot end a valid session early.
      setTimeout(() => location.replace(LOGIN_PAGE), Math.max(0, expiresIn));
    }
    return value;
  });

  // Handle the rejection here while leaving it visible to callers.
  ready.catch((error) => report(error.message));

  function send(url, options, headers) {
    return fetch(url, {
      ...options,
      headers,
      credentials: 'same-origin',
      cache: 'no-store',
    });
  }

  /**
   * Fetch wrapper for every authenticated backend call.
   *
   * Waits for the session, attaches the CSRF header to every request that is not
   * GET, HEAD or OPTIONS (covering POST, PUT, PATCH and DELETE), redirects on
   * 401, and replays once when the server rejects the CSRF token.
   */
  async function authenticatedFetch(url, options = {}) {
    await ready;
    const method = (options.method || 'GET').toUpperCase();
    const headers = new Headers(options.headers);
    if (!SAFE_METHODS.includes(method)) headers.set(session.csrfHeader, session.csrfToken);

    let response = await send(url, options, headers);
    if (response.status === 401) return login();

    if (response.status === 403 && !SAFE_METHODS.includes(method)) {
      let failure;
      try {
        // Read the clone: the caller still needs the untouched original body.
        failure = await response.clone().json();
      } catch (_) {}
      if (failure && failure.error === 'CSRF_INVALID') {
        // CsrfFilter emits this exact response before any controller runs. The
        // cookie rotated (typically in another tab), so refresh the token and
        // replay the request at most once. Other 403s are never replayed.
        session = await requestSession();
        headers.set(session.csrfHeader, session.csrfToken);
        response = await send(url, options, headers);
        if (response.status === 401) return login();
      }
    }
    return response;
  }

  /**
   * XHR upload with real progress.
   *
   * fetch cannot report upload progress, so this mirrors authenticatedFetch with
   * XMLHttpRequest: same session wait, same CSRF header, same 401 redirect and
   * same single CSRF replay. The multipart Content-Type is deliberately left
   * unset so the browser adds it together with the boundary.
   */
  async function upload(url, body, onProgress = () => {}) {
    await ready;
    function send() {
      return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('POST', url);
        xhr.withCredentials = true;
        xhr.timeout = 360000;
        xhr.setRequestHeader(session.csrfHeader, session.csrfToken);
        xhr.upload.addEventListener('progress', (event) => {
          if (event.lengthComputable)
            onProgress({
              phase: 'uploading',
              percent: Math.min(100, (event.loaded / event.total) * 100),
            });
        });
        xhr.upload.addEventListener('load', () => onProgress({ phase: 'processing' }));
        xhr.addEventListener('load', () => {
          const text = xhr.responseText;
          resolve({
            status: xhr.status,
            ok: xhr.status >= 200 && xhr.status < 300,
            text: async () => text,
            json: async () => JSON.parse(text),
          });
        });
        xhr.addEventListener('error', () =>
          reject(new Error('Upload connection failed. Please check your connection.')),
        );
        xhr.addEventListener('timeout', () =>
          reject(new Error('Upload timed out. Check the folder before retrying.')),
        );
        xhr.addEventListener('abort', () => reject(new Error('Upload cancelled.')));
        xhr.send(body);
      });
    }
    let response = await send();
    if (response.status === 401) return login();
    if (response.status === 403) {
      let error;
      try {
        error = await response.json();
      } catch (_) {}
      if (error && error.error === 'CSRF_INVALID') {
        session = await requestSession();
        response = await send();
        if (response.status === 401) return login();
      }
    }
    return response;
  }

  /** Ends the session. Transfer clears the cookie; JavaScript never touches it. */
  async function logout() {
    const response = await authenticatedFetch(SESSION_URL, { method: 'DELETE' });
    if (!response.ok) throw new Error('Sign out failed. Please try again.');
    location.replace(LOGIN_PAGE);
  }

  function wireLogout() {
    const button = document.getElementById('logout');
    if (!button || logoutWired) return false;
    logoutWired = true;
    button.addEventListener('click', async () => {
      // Guard against a second click while the request is in flight.
      button.disabled = true;
      try {
        await logout();
      } catch (error) {
        button.disabled = false;
        report(error.message);
      }
    });
    return true;
  }

  // Wired up front, not from `ready`, so the button still reports a problem when
  // the session check itself failed. The handler waits for `ready` through
  // authenticatedFetch.
  if (!wireLogout()) document.addEventListener('DOMContentLoaded', wireLogout, { once: true });

  window.mmivAuth = {
    ready,
    fetch: authenticatedFetch,
    upload,
    logout,
    login,
    /** A copy of the session contract, or undefined until `ready` resolves. */
    current: () => (session ? { ...session } : undefined),
  };
})();
