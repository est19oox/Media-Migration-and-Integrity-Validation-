'use strict';

/**
 * Home page data.
 *
 * Replaces the page's mock user and folder data with the real account behind the
 * session cookie. Every call goes through window.mmivAuth.fetch, which waits for
 * the session and carries the CSRF header where one is required.
 *
 * Verified contracts:
 *   GET /api/access/me               -> { userId, username, role, destinations }
 *                                       destinations[]: { destination, permission }
 *                                       permission is READ or WRITE; role is USER or ADMIN
 *   GET /api/history?page=0          -> { entries, hasMore, page }
 *                                       entries[]: { time, action, fileName,
 *                                                    folder, sizeBytes, status }
 *
 * Nothing here writes to #status until the session has been verified, so the
 * login and logout messages owned by mmiv-auth.js are never overwritten.
 */
(() => {
  const PROFILE_URL = '/api/access/me';
  const HISTORY_URL = '/api/history?page=0';
  const RECENT_LIMIT = 3;

  // Presentation for the action names the history endpoint returns. There is no
  // delete variant in home.css, so the delete actions reuse the download icon
  // styling rather than inventing a class the stylesheet does not define.
  const ACTIONS = {
    Upload: { icon: 'upload-icon', glyph: '↑', done: 'File uploaded', failed: 'Upload failed' },
    Download: {
      icon: 'download-icon',
      glyph: '↓',
      done: 'File downloaded',
      failed: 'Download failed',
    },
    Delete: { icon: 'download-icon', glyph: '×', done: 'File deleted', failed: 'Delete failed' },
    'Delete folder': {
      icon: 'download-icon',
      glyph: '×',
      done: 'Folder deleted',
      failed: 'Folder delete failed',
    },
  };

  const auth = window.mmivAuth;
  const status = document.getElementById('status');
  let lastStatus = null;

  if (!auth) {
    if (status) status.textContent = 'Unable to verify your login. Please reload the page.';
    return;
  }

  /** Writes a data-loading message and remembers it as ours. */
  function setStatus(text) {
    if (!status) return;
    lastStatus = text;
    status.textContent = text;
  }

  /**
   * Clears #status only while it still holds the message this page wrote. The
   * session helper reports login and logout problems in the same element, and
   * those must stay readable.
   */
  function clearStatus() {
    if (status && status.textContent === lastStatus) {
      status.textContent = '';
      lastStatus = '';
    }
  }

  function profileError(statusCode) {
    if (statusCode === 403 || statusCode === 404)
      return 'Your account is not available. Contact your administrator.';
    if (statusCode === 503) return 'The account service is unavailable. Please try again later.';
    return 'Your workspace could not be loaded. Please try again.';
  }

  async function fetchProfile() {
    const response = await auth.fetch(PROFILE_URL);
    if (!response.ok) throw new Error(profileError(response.status));
    const profile = await response.json();
    if (!profile || typeof profile.username !== 'string' || !Array.isArray(profile.destinations)) {
      throw new Error('Your account details could not be read. Please reload the page.');
    }
    return profile;
  }

  function initials(username) {
    const cleaned = String(username || '').replace(/[^A-Za-z0-9]/g, '');
    return cleaned ? cleaned.slice(0, 2).toUpperCase() : '';
  }

  function renderAccount(profile) {
    const account = document.getElementById('account');
    if (account) account.textContent = profile.username;

    const avatar = document.querySelector('.account-avatar');
    const badge = initials(profile.username);
    if (avatar && badge) avatar.textContent = badge;

    const role = document.querySelector('.account-copy span');
    if (role) role.textContent = profile.role === 'ADMIN' ? 'Administrator' : 'Workspace member';

    // Administration is revealed only for the role the backend verified.
    document.querySelectorAll('[data-admin]').forEach((node) => {
      node.hidden = profile.role !== 'ADMIN';
    });
  }

  function folderCard(folder) {
    const item = document.createElement('li');
    const link = document.createElement('a');
    const icon = document.createElement('span');
    const details = document.createElement('span');
    const name = document.createElement('strong');
    const permission = document.createElement('small');

    icon.className = 'folder-icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = '▣';

    details.className = 'folder-details';
    name.textContent = folder.destination;

    permission.className = 'folder-permission';
    permission.textContent =
      folder.permission === 'WRITE' ? 'Upload and download' : 'View and download';

    link.href =
      'file-manager.html?folder=' + encodeURIComponent(folder.destination) + '#browse-files';

    details.append(name, permission);
    link.append(icon, details);
    item.append(link);
    return item;
  }

  function renderFolders(destinations) {
    const list = document.getElementById('folders');
    if (!list) return;
    const empty = document.getElementById('noAccess');
    const count = document.getElementById('folderCount');

    list.replaceChildren();
    if (count)
      count.textContent =
        destinations.length + (destinations.length === 1 ? ' folder' : ' folders');
    if (empty) empty.hidden = destinations.length > 0;
    if (destinations.length === 0) return;

    destinations.forEach((folder) => list.append(folderCard(folder)));
  }

  function formatTime(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value == null ? '' : String(value);
    const now = new Date();
    const time = date.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    if (date.toDateString() === now.toDateString()) return 'Today, ' + time;
    const yesterday = new Date(now);
    yesterday.setDate(now.getDate() - 1);
    if (date.toDateString() === yesterday.toDateString()) return 'Yesterday, ' + time;
    return date.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' });
  }

  function activityNote(list, text) {
    const note = document.createElement('p');
    note.className = 'activity-demo-note';
    note.textContent = text;
    list.replaceChildren(note);
  }

  function activityItem(entry) {
    const action = ACTIONS[entry.action] || {
      icon: 'upload-icon',
      glyph: '•',
      done: entry.action || 'Activity',
      failed: 'Activity failed',
    };
    const item = document.createElement('li');
    const icon = document.createElement('span');
    const details = document.createElement('div');
    const title = document.createElement('strong');
    const subject = document.createElement('span');
    const time = document.createElement('small');

    item.className = 'activity-item';
    icon.className = 'activity-icon ' + action.icon;
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = action.glyph;
    details.className = 'activity-details';

    // The history endpoint reports success per record, so a failed run is shown
    // as a failure rather than being counted as a completed action.
    title.textContent = entry.status === 'Failed' ? action.failed : action.done;
    time.textContent = formatTime(entry.time);

    details.append(title);
    const label = entry.fileName || entry.folder;
    if (label) {
      subject.textContent = label;
      details.append(subject);
    }
    details.append(time);
    item.append(icon, details);
    return item;
  }

  async function loadActivity() {
    const list = document.querySelector('.activity-list');
    if (!list) return;
    try {
      const response = await auth.fetch(HISTORY_URL);
      if (!response.ok) throw new Error('History unavailable');
      const data = await response.json();
      const entries = data && Array.isArray(data.entries) ? data.entries : [];
      if (entries.length === 0) {
        activityNote(list, 'No recent activity yet.');
        return;
      }
      list.replaceChildren();
      entries.slice(0, RECENT_LIMIT).forEach((entry) => list.append(activityItem(entry)));
    } catch (_) {
      // Reporting is a separate service; its absence must not affect the folders
      // already on screen, and no activity is invented to fill the gap.
      activityNote(list, 'Recent activity is unavailable right now.');
    }
  }

  async function load() {
    setStatus('Loading your workspace…');
    let profile;
    try {
      profile = await fetchProfile();
    } catch (error) {
      // Deliberately leaves the folder list empty rather than showing demo data.
      setStatus(error.message);
      return;
    }
    renderAccount(profile);
    renderFolders(profile.destinations);
    clearStatus();
    await loadActivity();
  }

  // When the session cannot be verified, mmiv-auth.js owns the page: it is
  // already redirecting to the login page and its message must stand.
  auth.ready.then(load, () => {});
})();
