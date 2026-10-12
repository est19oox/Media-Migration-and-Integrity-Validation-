'use strict';
(() => {
  const element = id => document.getElementById(id);
  let token = null, target = null, destination = null, busy = false;
  let invitation = new URLSearchParams(location.hash.slice(1)).get('invite');
  if (invitation) history.replaceState(null, '', location.pathname);
  element('invitationOrigin').value = location.origin;
  const message = text => { element('message').textContent = text; };
  if (invitation) message('Sign in with the account named in your administrator invitation.');
  function clearSession() {
    token = null; target = null; destination = null;
    element('password').value = '';
    element('userDetails').textContent = '';
    element('invitationLink').textContent = '';
    element('destination').value = '';
    element('loginPanel').hidden = false;
    element('management').hidden = true;
  }
  function renderUser(user) {
    target = user;
    element('userDetails').textContent = JSON.stringify(user, null, 2);
  }
  async function api(path, method = 'GET', body) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const headers = {'Accept': 'application/json'};
      if (token) headers.Authorization = 'Bearer ' + token;
      if (body) headers['Content-Type'] = 'application/json';
      const response = await fetch('/user-access' + path, {
        method, headers, body: body ? JSON.stringify(body) : undefined,
        credentials: 'omit', cache: 'no-store', signal: controller.signal
      });
      if (!response.ok) {
        if (response.status === 401 || response.status === 403) {
          clearSession();
          throw new Error('Credentials, administrator access, or invitation are invalid or expired.');
        }
        throw new Error(response.status === 404 ? 'User not found. Register the account first.'
          : response.status === 400 ? 'Check the username, destination, and permission.'
          : response.status === 409 ? 'The account is inactive or already an administrator.'
          : 'The account service is unavailable. Please retry.');
      }
      return await response.json();
    } finally { clearTimeout(timeout); }
  }
  async function run(operation) {
    if (busy) return;
    busy = true;
    document.querySelectorAll('button').forEach(button => { button.disabled = true; });
    try { await operation(); }
    catch (error) { message(error.name === 'AbortError' ? 'Request timed out. Please retry.'
      : error instanceof TypeError ? 'Could not reach the account service.' : error.message); }
    finally {
      busy = false;
      document.querySelectorAll('button').forEach(button => { button.disabled = false; });
      element('grant').disabled = !target || !target.active;
      element('revoke').disabled = !target;
    }
  }
  element('adminLogin').addEventListener('submit', event => {
    event.preventDefault();
    if (busy) return;
    const credentials = {username: element('username').value.trim(), password: element('password').value};
    element('password').value = '';
    run(async () => {
      token = null; target = null;
      const response = await api('/api/auth/login', 'POST', credentials);
      if (typeof response.accessToken !== 'string' || !response.accessToken
          || String(response.tokenType).toLowerCase() !== 'bearer') throw new Error('Invalid login response.');
      token = response.accessToken;
      try {
        const identity = invitation
          ? await api('/api/admin/invitations/accept', 'POST', {token: invitation})
          : await api('/api/admin/me');
        invitation = null;
        destination = identity.destination;
        element('destination').value = destination;
        element('loginPanel').hidden = true;
        element('management').hidden = false;
        message('Signed in. Manage permissions or invite another administrator below.');
      } catch (error) { clearSession(); throw error; }
    });
  });
  element('userLookup').addEventListener('submit', event => {
    event.preventDefault();
    run(async () => {
      target = null; element('userDetails').textContent = '';
      renderUser(await api('/api/admin/users?username=' + encodeURIComponent(element('targetUsername').value.trim())));
      message('User loaded.');
    });
  });
  element('grant').addEventListener('click', () => run(async () => {
    if (!target || !destination) return;
    renderUser(await api('/api/admin/users/' + encodeURIComponent(target.userId) + '/destinations', 'PUT',
      {destination, permission: element('permission').value}));
    message('Permission saved.');
  }));
  element('revoke').addEventListener('click', () => run(async () => {
    if (!target || !destination) return;
    renderUser(await api('/api/admin/users/' + encodeURIComponent(target.userId) + '/destinations?destination=' + encodeURIComponent(destination), 'DELETE'));
    message('Permission revoked.');
  }));
  element('inviteAdmin').addEventListener('submit', event => {
    event.preventDefault();
    run(async () => {
      element('invitationLink').textContent = '';
      const origin = new URL(element('invitationOrigin').value.trim());
      if (!['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.search || origin.hash || origin.pathname !== '/')
        throw new Error('Enter the website origin, for example https://your-website.example.');
      const response = await api('/api/admin/invitations', 'POST', {username: element('inviteUsername').value.trim()});
      const link = new URL('/admin.html', origin.origin);
      link.hash = 'invite=' + response.token;
      element('invitationLink').textContent = link.href;
      message('Invitation created. Copy the link and send it privately to that user. Expires: ' + new Date(response.expiresAt).toLocaleString());
    });
  });
  element('copyInvitation').addEventListener('click', () => run(async () => {
    const link = element('invitationLink').textContent;
    if (!link) { message('Create an invitation first.'); return; }
    try { await navigator.clipboard.writeText(link); message('Invitation link copied. Send it to the invited user by email or chat.'); }
    catch (_) { message('Select and copy the invitation link shown above.'); }
  }));
  element('logout').addEventListener('click', () => { clearSession(); message('Signed out.'); });
})();
