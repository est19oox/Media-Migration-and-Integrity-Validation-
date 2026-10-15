'use strict';

/**
 * Activity page: the signed-in account's own file history.
 *
 * Replaces the page's mock records with the real history behind the session
 * cookie. Every call goes through window.mmivAuth.fetch, which waits for the
 * session and redirects to the login page once it has expired.
 *
 * Verified contracts:
 *
 *   GET /api/v1/portal/me
 *     -> { userId, username, role, destinations: [{ destination, permission }] }
 *        Used for the account name in the header and to reveal Administration.
 *
 *   GET /api/v1/portal/history?page=<n>
 *     -> { entries: [{ time, action, fileName, folder, sizeBytes, status }],
 *          hasMore, page }
 *        Proxied to reporting-service /api/history, which reads the audit log
 *        with data.actorId = the token subject. It is therefore this account's
 *        own history and needs no administrator role, so an ordinary member
 *        sees their own records and nobody else's.
 *        50 records per page, newest first, with hasMore reporting whether a
 *        further page exists. page is zero-based; a page below 0 or above
 *        100000 is a 400.
 *        action is one of Upload, Download, Delete or Delete folder.
 *        status is Completed or Failed.
 *        fileName is empty for a folder deletion, which removes nothing but the
 *        folder itself; folder is the folder the record belongs to.
 *
 * Integrity verification is deliberately absent. The history query filters
 * data.operation to the five file operations, and integrity-service publishes a
 * validation result as a migration event rather than an audit log record, so a
 * verification result cannot reach this endpoint. The Verification filter
 * therefore reports that it has nothing to show instead of inventing records or
 * relabelling a delete or an upload as Verified.
 *
 * Nothing here writes to #status until the session has been verified, so the
 * login and logout messages owned by mmiv-auth.js are never overwritten.
 */
(() => {
  const PROFILE_URL = '/api/v1/portal/me';
  const HISTORY_URL = '/api/v1/portal/history';

  const element = (id) => document.getElementById(id);
  const auth = window.mmivAuth;
  const status = element('status');

  if (!auth) {
    if (status) status.textContent = 'Unable to verify your login. Please reload the page.';
    return;
  }

  const rows = element('activityRows');
  const search = element('activitySearch');
  const filter = element('activityFilter');
  const count = element('activityCount');
  const listStatus = element('activityListStatus');
  const refresh = element('refreshActivity');
  const sentinel = element('activitySentinel');

  if (!rows) return;

  /**
   * The report the backend writes for each action, in the two outcomes it
   * reports. An action this page has never seen keeps its own name.
   */
  const ACTIONS = {
    Upload: { type: 'upload', done: 'File uploaded', failed: 'Upload failed' },
    Download: { type: 'download', done: 'File downloaded', failed: 'Download failed' },
    Delete: { type: 'delete', done: 'File deleted', failed: 'Delete failed' },
    'Delete folder': { type: 'delete', done: 'Folder deleted', failed: 'Folder delete failed' },
  };

  // Only the two outcomes the endpoint reports have a badge in activity.css.
  const STATUS_CLASSES = { Completed: 'status-completed', Failed: 'status-failed' };

  let entries = [];
  let nextPage = 0;
  let hasMore = true;
  let loading = false;
  // Set when the history call failed, so the table reports it rather than
  // claiming there is no activity.
  let failure = null;
  // Invalidates responses that arrive after the list was reset.
  let generation = 0;
  let observer = null;
  let lastStatus = null;

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

  function setListStatus(text) {
    if (listStatus) listStatus.textContent = text;
  }

  /** "1 activity" / "2 activities", the wording the page already used. */
  function activities(count) {
    return count + (count === 1 ? ' activity' : ' activities');
  }

  function profileError(code) {
    if (code === 403 || code === 404)
      return 'Your account is not available. Contact your administrator.';
    if (code === 503) return 'The account service is unavailable. Please try again later.';
    return 'Your workspace could not be loaded. Please try again.';
  }

  function historyError(code) {
    if (code === 400) return 'That activity page does not exist.';
    if (code === 403) return 'Your account is not available. Contact your administrator.';
    if (code === 503) return 'The reporting service is unavailable. Please try again later.';
    return 'Your activity could not be loaded. Please try again.';
  }

  /** One full-width row, used for the loading, empty and error states. */
  function messageRow(text) {
    const row = document.createElement('tr');
    const cell = document.createElement('td');
    cell.colSpan = 5;
    cell.textContent = text;
    row.append(cell);
    rows.replaceChildren(row);
  }

  function describe(entry) {
    const known = ACTIONS[entry.action];
    if (known)
      return { type: known.type, title: entry.status === 'Failed' ? known.failed : known.done };
    const action = entry.action || 'Activity';
    return { type: 'other', title: entry.status === 'Failed' ? action + ' failed' : action };
  }

  /** "Today, 10:42 AM", "Yesterday, 10:42 AM" or "Oct 12, 2026". */
  function formatTime(value) {
    if (!value) return '—';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    const now = new Date();
    const time = date.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    if (date.toDateString() === now.toDateString()) return 'Today, ' + time;
    const yesterday = new Date(now);
    yesterday.setDate(now.getDate() - 1);
    if (date.toDateString() === yesterday.toDateString()) return 'Yesterday, ' + time;
    return date.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' });
  }

  /** The record's own values; a missing one is shown as a dash, never filled in. */
  function value(text) {
    return typeof text === 'string' && text !== '' ? text : '—';
  }

  function textCell(text) {
    const cell = document.createElement('td');
    cell.textContent = text;
    return cell;
  }

  function activityRow(entry) {
    const report = describe(entry);
    const row = document.createElement('tr');
    const titleCell = document.createElement('td');
    const title = document.createElement('span');
    const badge = document.createElement('span');
    const statusCell = document.createElement('td');

    title.className = 'activity-name';
    title.textContent = report.title;
    titleCell.append(title);

    const badgeClass = STATUS_CLASSES[entry.status];
    badge.className = 'activity-status' + (badgeClass ? ' ' + badgeClass : '');
    badge.textContent = value(entry.status);

    statusCell.append(badge);
    row.append(
      titleCell,
      textCell(value(entry.fileName)),
      textCell(value(entry.folder)),
      textCell(formatTime(entry.time)),
      statusCell,
    );
    return row;
  }

  function searchText(entry) {
    const report = describe(entry);
    return [
      report.title,
      entry.fileName,
      entry.folder,
      entry.action,
      entry.status,
      formatTime(entry.time),
    ]
      .filter((part) => typeof part === 'string' && part !== '')
      .join(' ')
      .toLowerCase();
  }

  /**
   * Why the table is empty. Verification is called out by name because it is a
   * filter option the history endpoint can never satisfy.
   */
  function emptyMessage() {
    if (failure) return failure;
    if (loading && entries.length === 0) return 'Loading activity…';
    if (filter && filter.value === 'verification')
      return 'Integrity verification events are not included in this history.';
    if (search && search.value.trim()) return 'No loaded activity matches your search.';
    if (filter && filter.value !== 'all') return 'No activity of this kind yet.';
    return 'No activity yet.';
  }

  function render() {
    const query = search ? search.value.trim().toLowerCase() : '';
    const type = filter ? filter.value : 'all';
    const visible = entries.filter((entry) => {
      // "Verification" is offered by the design but no such record exists.
      if (type === 'verification') return false;
      if (type !== 'all' && describe(entry).type !== type) return false;
      return query === '' || searchText(entry).includes(query);
    });

    if (visible.length === 0) messageRow(emptyMessage());
    else {
      rows.replaceChildren();
      visible.forEach((entry) => rows.append(activityRow(entry)));
    }

    if (count) count.textContent = activities(visible.length);
    if (visible.length === 0) setListStatus(emptyMessage());
    else if (visible.length === entries.length)
      setListStatus('Showing ' + activities(visible.length));
    // Search and filter only see the pages fetched so far, so the narrowed
    // count names them as loaded rather than implying the whole history.
    else
      setListStatus('Showing ' + visible.length + ' of ' + activities(entries.length) + ' loaded');

    observeSentinel();
  }

  /** Re-arms the observer after every render so paging continues while visible. */
  function observeSentinel() {
    if (observer) {
      observer.disconnect();
      observer = null;
    }
    if (!sentinel || typeof IntersectionObserver !== 'function' || !hasMore) return;
    observer = new IntersectionObserver(
      (records) => {
        if (records.some((record) => record.isIntersecting)) loadActivity();
      },
      { rootMargin: '160px' },
    );
    observer.observe(sentinel);
  }

  async function loadActivity(options = {}) {
    if (options.reset) {
      generation += 1;
      loading = false;
      entries = [];
      nextPage = 0;
      hasMore = true;
      failure = null;
    }
    if (loading || !hasMore) return;
    const request = generation;
    loading = true;
    render();
    try {
      const response = await auth.fetch(HISTORY_URL + '?page=' + nextPage);
      if (!response.ok) throw new Error(historyError(response.status));
      const data = await response.json();
      if (request !== generation) return;
      if (!data || !Array.isArray(data.entries))
        throw new Error('Your activity could not be read. Please reload the page.');
      entries = entries.concat(data.entries);
      hasMore = data.hasMore === true;
      nextPage += 1;
      failure = null;
      clearStatus();
    } catch (error) {
      if (request !== generation) return;
      // Stops the sentinel from retrying a failing page in a loop. Records
      // already on screen stay: nothing is invented to fill the gap.
      hasMore = false;
      failure = error.message;
      // With records on screen the table keeps them, so the problem is reported
      // above it; with none, the table itself carries the message.
      if (entries.length > 0) setStatus(error.message);
    } finally {
      if (request === generation) loading = false;
      render();
    }
  }

  async function start() {
    let profile;
    try {
      const response = await auth.fetch(PROFILE_URL);
      if (!response.ok) throw new Error(profileError(response.status));
      profile = await response.json();
      if (!profile || typeof profile.username !== 'string')
        throw new Error('Your account details could not be read. Please reload the page.');
    } catch (error) {
      setStatus(error.message);
      setListStatus('');
      if (count) count.textContent = activities(0);
      messageRow('Your activity is unavailable.');
      return;
    }

    const account = element('account');
    if (account) account.textContent = profile.username;

    // Administration is revealed only for the role the backend verified.
    document.querySelectorAll('[data-admin]').forEach((node) => {
      node.hidden = profile.role !== 'ADMIN';
    });

    await loadActivity({ reset: true });
  }

  if (search) search.addEventListener('input', render);
  if (filter) filter.addEventListener('change', render);
  if (refresh) refresh.addEventListener('click', () => loadActivity({ reset: true }));

  // When the session cannot be verified, mmiv-auth.js owns the page: it is
  // already redirecting to the login page and its message must stand.
  auth.ready.then(start, () => {});
})();
