// Password reset behavior for the shared reset-password page.
(() => {
  let token = new URLSearchParams(location.hash.slice(1)).get('reset');
  history.replaceState(null, '', location.pathname);
  const form = document.getElementById('resetForm');
  const button = document.getElementById('submit');
  const status = document.getElementById('status');
  if (!token || !/^[0-9a-f]{64}$/.test(token)) {
    button.disabled = true;
    status.textContent = 'Invalid reset link. Ask your administrator for a new link.';
    return;
  }
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const password = document.getElementById('password').value;
    if (password !== document.getElementById('confirm').value) {
      mmivFeedback.notice(
        'Passwords do not match',
        'Enter the same password in both fields.',
        'error',
      );
      return;
    }
    if (password.length < 8 || new TextEncoder().encode(password).length > 72 || !password.trim()) {
      mmivFeedback.notice(
        'Check your password',
        'Use at least 8 characters and no more than 72 UTF-8 bytes.',
        'error',
      );
      return;
    }
    button.disabled = true;
    try {
      const response = await fetch('/user-access/api/auth/password-reset', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, password }),
        credentials: 'omit',
        signal: AbortSignal.timeout(20000),
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || 'Password reset failed. Ask for a new link.');
      token = null;
      form.reset();
      status.textContent = data.message || 'Password changed.';
      mmivFeedback.notice('Password changed', status.textContent, 'success', () =>
        location.assign('/pages/login.html'),
      );
    } catch (error) {
      status.textContent = error.message;
      mmivFeedback.notice('Password change unsuccessful', error.message, 'error');
      button.disabled = false;
    }
  });
})();
