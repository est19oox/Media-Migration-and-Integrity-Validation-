'use strict';

(() => {
  const TOKEN_PATTERN = /^[a-f0-9]{64}$/;
  const status = document.getElementById('status');
  const accept = document.getElementById('accept');
  const invitation = new URLSearchParams((location.hash || '').slice(1)).get('invite');

  // The secret is retained only in this function closure and removed from the
  // address/history immediately. It is never persisted by browser storage.
  history.replaceState(null, '', location.pathname + location.search);

  if (!invitation || !TOKEN_PATTERN.test(invitation)) {
    status.textContent = 'This invitation link is invalid.';
    return;
  }

  mmivAuth.ready
    .then(() => {
      status.textContent = 'Your invitation is ready to accept.';
      accept.disabled = false;
    })
    .catch(() => {});

  accept.addEventListener('click', async () => {
    accept.disabled = true;
    status.textContent = 'Accepting invitation…';
    try {
      const response = await mmivAuth.fetch('/user-access/api/admin/invitations/accept', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: invitation }),
      });
      if (!response.ok) throw new Error('Invitation rejected');
      status.textContent = 'Administrator access granted. Opening Administration…';
      location.assign('/pages/admin.html');
    } catch (_) {
      status.textContent =
        'Invitation invalid, expired, already used, or issued to another account.';
      accept.disabled = false;
    }
  });
})();
