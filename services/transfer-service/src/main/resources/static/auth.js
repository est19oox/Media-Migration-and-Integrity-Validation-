'use strict';
window.mmivAuth = (() => {
  let session;
  function login() {
    const invitation = new URLSearchParams(location.hash.slice(1)).get('invite');
    location.replace(
      '/login.html' +
        (invitation && /^[a-f0-9]{64}$/.test(invitation) ? '#invite=' + invitation : ''),
    );
    throw new Error('Please sign in again.');
  }
  async function requestSession() {
    const response = await fetch('/api/v1/session', {
      credentials: 'same-origin',
      cache: 'no-store',
    });
    if (response.status === 401) return login();
    if (!response.ok) throw new Error('Unable to verify your login. Please reload the page.');
    return response.json();
  }
  const ready = requestSession().then((value) => {
    session = value;
    const expiresIn = Number(value.expiresInSeconds) * 1000;
    if (Number.isFinite(expiresIn)) {
      setTimeout(() => location.replace('/login.html'), Math.max(0, expiresIn));
    }
    const logout = document.getElementById('logout');
    if (logout)
      logout.addEventListener('click', async () => {
        try {
          const response = await authenticatedFetch('/api/v1/session', { method: 'DELETE' });
          if (!response.ok) throw new Error('Sign out failed. Please try again.');
          location.replace('/login.html');
        } catch (error) {
          alert(error.message);
        }
      });
    return value;
  });
  // Attach a handler immediately while keeping rejection visible to callers.
  ready.catch((error) => {
    const status = document.getElementById('status') || document.getElementById('result');
    if (status) status.textContent = error.message;
  });
  async function authenticatedFetch(url, options = {}) {
    await ready;
    const headers = new Headers(options.headers);
    const method = (options.method || 'GET').toUpperCase();
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method))
      headers.set(session.csrfHeader, session.csrfToken);
    let response = await fetch(url, {
      ...options,
      headers,
      credentials: 'same-origin',
      cache: 'no-store',
    });
    if (response.status === 401) return login();
    if (response.status === 403 && !['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      let failure;
      try {
        failure = await response.clone().json();
      } catch (_) {}
      if (failure && failure.error === 'CSRF_INVALID') {
        // This exact response is emitted by CsrfFilter before a controller runs.
        // Refresh after another tab changes the cookie, and replay at most once.
        session = await requestSession();
        headers.set(session.csrfHeader, session.csrfToken);
        response = await fetch(url, {
          ...options,
          headers,
          credentials: 'same-origin',
          cache: 'no-store',
        });
        if (response.status === 401) return login();
      }
    }
    return response;
  }
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
          const response = {
            status: xhr.status,
            ok: xhr.status >= 200 && xhr.status < 300,
            text: async () => text,
            json: async () => JSON.parse(text),
          };
          resolve(response);
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
      if (error?.error === 'CSRF_INVALID') {
        session = await requestSession();
        response = await send();
        if (response.status === 401) return login();
      }
    }
    return response;
  }
  return { ready, fetch: authenticatedFetch, upload };
})();
