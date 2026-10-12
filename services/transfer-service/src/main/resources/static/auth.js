'use strict';
window.mmivAuth = (() => {
  let session;
  function login() {
    location.replace('/login.html');
    throw new Error('Please sign in again.');
  }
  async function requestSession() {
    const response = await fetch('/api/v1/session', {credentials: 'same-origin', cache: 'no-store'});
    if (response.status === 401) return login();
    if (!response.ok) throw new Error('Unable to verify your login. Please reload the page.');
    return response.json();
  }
  const ready = requestSession().then(value => {
    session = value;
    const expiresIn = Date.parse(value.expiresAt) - Date.now();
    if (Number.isFinite(expiresIn)) {
      setTimeout(() => location.replace('/login.html'), Math.max(0, expiresIn));
    }
    const logout = document.getElementById('logout');
    if (logout) logout.addEventListener('click', async () => {
      try {
        const response = await authenticatedFetch('/api/v1/session', {method: 'DELETE'});
        if (!response.ok) throw new Error('Sign out failed. Please try again.');
        location.replace('/login.html');
      } catch (error) { alert(error.message); }
    });
    return value;
  });
  // Attach a handler immediately while keeping rejection visible to callers.
  ready.catch(error => {
    const status = document.getElementById('status') || document.getElementById('result');
    if (status) status.textContent = error.message;
  });
  async function authenticatedFetch(url, options = {}) {
    await ready;
    const headers = new Headers(options.headers);
    const method = (options.method || 'GET').toUpperCase();
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set(session.csrfHeader, session.csrfToken);
    const response = await fetch(url, {...options, headers, credentials: 'same-origin', cache: 'no-store'});
    if (response.status === 401) return login();
    return response;
  }
  return {ready, fetch: authenticatedFetch};
})();
