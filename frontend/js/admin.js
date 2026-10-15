'use strict';

/**
 * Administration read operations for the Admin workspace.
 *
 * Replaces the page's mock records with the real backend data behind the
 * session cookie. Every call goes through window.mmivAuth.fetch, which waits
 * for the session, attaches the CSRF header where one is required, and
 * redirects to the login page once the session has expired. No administrative
 * request is issued until the account has been confirmed as ADMIN.
 *
 * Verified contracts (transfer-service PortalController and the services it
 * proxies to):
 *
 *   GET /api/v1/portal/me
 *     -> { userId, username, role, destinations: [{ destination, permission }] }
 *        role is ADMIN or USER; permission is READ or WRITE.
 *
 *   GET /api/v1/portal/admin/users?q=<query>
 *     -> [{ userId, username, role, active, destinations: [{ destination, permission }] }]
 *        Proxied to user-access-service /api/admin/user-options, where q is a
 *        case-insensitive substring match on the username, ordered by username
 *        and capped at 100 rows. An empty q lists the first 100 accounts, so
 *        the search is server-side and there is no page parameter.
 *
 *   GET /api/v1/portal/admin/folders
 *     -> ["<folder name>", ...]
 *        Folder names only. The endpoint reports no path, status, owner or
 *        timestamp, so the Path column shows the storage prefix the name
 *        produces and the Status column states that no status is available
 *        rather than inventing one.
 *
 *   GET /api/v1/portal/logs?page=<n>
 *     -> { logs: [{ _id, eventId, sourceService, occurredAt, operation, data }], page }
 *        Proxied to reporting-service /api/dashboard: 100 records per page,
 *        newest first, with no total and no search, level or actor-name field.
 *        The Level column is therefore derived from each record's own outcome
 *        (see levelOf) and the User column shows the actor id the record
 *        carries, because no endpoint maps an actor id to a username.
 *
 * There is no GET endpoint that lists invitations: the backend only exposes
 * POST /api/v1/portal/admin/invitations and POST /api/v1/portal/invitations/accept.
 * The Invitations tab is preserved and reports that history is unavailable.
 *
 * A failed request always clears the table it belongs to: no record is ever
 * fabricated to fill a table, and no demo data ships with the page.
 *
 * The management actions below use the mutation contracts verified in
 * transfer-service PortalController and user-access-service AdminController:
 *
 *   POST   /api/v1/portal/admin/folders            { name }      -> ["<name>", ...]
 *          The whole folder list, so the table refreshes from the response.
 *          The name must match [A-Za-z0-9][A-Za-z0-9 _.-]{0,79} and may not end
 *          in a space or dot. Creating an existing folder is not an error.
 *
 *   DELETE /api/v1/portal/folders?folder=<name>                  -> { deletedVersions }
 *          Destructive: removes every object and stored version under the
 *          folder AND the folder record, plus the permissions granted on it.
 *          The optional `path` parameter, which would delete only one subtree
 *          and keep the record, is deliberately not used here.
 *
 *   PUT    /api/v1/portal/admin/users/{id}/permissions  { destination, permission }
 *                                                               -> UserDetails
 *   DELETE /api/v1/portal/admin/users/{id}/permissions?destination=<name>
 *                                                               -> UserDetails
 *          permission is READ or WRITE. An unknown destination is a 400 and an
 *          inactive account a 409. Both return the updated account, so the row
 *          is replaced from the response.
 *
 *   DELETE /api/v1/portal/admin/users/{id}                       -> 204, no body
 *          Deactivates the account; nothing is erased. Deactivating yourself is
 *          allowed and clears the session cookie server-side. The path variable
 *          is a UUID.
 *
 *   POST   /api/v1/portal/admin/users/{id}/password-reset        -> { token, expiresAt }
 *          404 for an unknown account, 409 when inactive, 403 for another
 *          administrator. The token is valid for one hour and is redeemed by
 *          /reset-password.html#reset=<token>.
 *
 *   POST   /api/v1/portal/admin/invitations  { username }        -> { token, expiresAt }
 *          404 for an unknown account, 409 when the account is inactive, is not
 *          an ordinary user or is a CI account. The token is valid for 24 hours
 *          and is redeemed by /invite.html#invite=<token>.
 *
 * There is no endpoint that revokes an invitation, so no revoke control is
 * offered. Tokens are only ever placed in the readonly link fields: nothing is
 * logged and nothing is written to browser storage.
 */
(() => {
  const PROFILE_URL = '/api/v1/portal/me';
  const USERS_URL = '/api/v1/portal/admin/users';
  const FOLDERS_URL = '/api/v1/portal/admin/folders';
  const LOGS_URL = '/api/v1/portal/logs';
  const ADMIN_FOLDERS_URL = '/api/v1/portal/admin/folders';
  const DELETE_FOLDER_URL = '/api/v1/portal/folders';
  const INVITATIONS_URL = '/api/v1/portal/admin/invitations';
  const HOME_PAGE = '/pages/home.html';
  const LOGIN_PAGE = '/pages/login.html';

  // The pages that redeem the generated links, both served by the gateway.
  const RESET_PAGE = '/reset-password.html';
  const INVITE_PAGE = '/invite.html';

  // Transfer's own reason strings for upstream failures; they say less than the
  // status does, so a status-based message is used instead of repeating them.
  const GENERIC_REASONS = [
    'Request denied or resource unavailable',
    'Service unavailable; please retry',
  ];

  // Mirrors FolderRepository.valid so an obviously wrong name is reported
  // without a round trip; the server still validates and answers 400.
  const FOLDER_NAME = /^[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}$/;

  // AdminController.createInvitation rejects these accounts with a 409.
  const CI_ACCOUNT = /^mmiv_ci_[0-9a-f]{32}$/;

  // reporting-service pages 100 service_logs records at a time and reports no
  // total, so a full page is the only "there may be more" signal available.
  const LOG_PAGE_SIZE = 100;

  // The Level column and the level filter work from these, because the log
  // record has no level field of its own.
  const LEVEL_BADGES = { INFO: 'badge-info', WARNING: 'badge-warning', ERROR: 'badge-error' };

  // Shown in their own columns, so repeating them in Details adds nothing.
  const DETAIL_FIELDS_SKIPPED = ['operation', 'actorId', 'success'];

  const element = (id) => document.getElementById(id);
  const auth = window.mmivAuth;
  const status = element('status');

  if (!auth) {
    if (status) status.textContent = 'Unable to verify your login. Please reload the page.';
    return;
  }

  const foldersTable = element('foldersTable');
  const usersTable = element('usersTable');
  const invitationsTable = element('invitationsTable');
  const logsTable = element('logsTable');

  if (!foldersTable || !usersTable || !invitationsTable || !logsTable) return;

  const foldersMeta = element('foldersMeta');
  const usersMeta = element('usersMeta');
  const invitationsMeta = element('invitationsMeta');
  const logsMeta = element('logsMeta');
  const logsHint = element('logsHint');
  const folderSearch = element('folderSearch');
  const userSearch = element('userSearch');
  const searchUsersButton = element('searchUsers');
  const logsSearch = element('logsSearch');
  const logsFilter = element('logsFilter');
  const refreshLogs = element('refreshLogs');
  const logsPrevious = element('logsPrevious');
  const logsNext = element('logsNext');
  const invitationSearch = element('invitationSearch');
  const invitationStatus = element('invitationStatus');
  const selectedUser = element('selectedUser');
  const permissionFolder = element('permissionFolder');

  const userDialog = element('userManagementDialog');
  const folderName = element('folderName');
  const createFolderButton = element('createFolder');
  const createFolderDialog = element('createFolderDialog');
  const savePermission = element('savePermission');
  const revokePermission = element('revokePermission');
  const createResetLink = element('createResetLink');
  const deactivateUser = element('deactivateUser');
  const resetLink = element('resetLink');
  const copyResetLink = element('copyResetLink');
  const websiteAddress = element('websiteAddress');
  const invitationUsername = element('invitationUsername');
  const findAccount = element('findAccount');
  const invitationAccount = element('invitationAccount');
  const createInvitationButton = element('createInvitation');
  const invitationLink = element('invitationLink');
  const copyInvitationLink = element('copyInvitationLink');
  const confirmDialog = element('confirmDialog');
  const confirmTitle = element('confirmTitle');
  const confirmMessage = element('confirmMessage');
  const confirmAccept = element('confirmAccept');
  const confirmCancel = element('confirmCancel');

  let folders = [];
  let users = [];
  let usersByName = new Map();
  let inviteResults = [];
  // Delete buttons of the rendered folder rows, locked while a request runs.
  let folderDeleteButtons = [];
  let logs = [];
  let logPage = 0;
  let lastStatus = null;
  // The signed-in administrator's id, used for the self-service warnings.
  let actorId = null;
  // Blocks a second management request while one is in flight.
  let busy = false;

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

  function plural(count, noun) {
    return count + ' ' + noun + (count === 1 ? '' : 's');
  }

  /** "4 folders", or "2 of 4 folders" while a client-side filter hides rows. */
  function countLabel(shown, total, noun) {
    return shown === total ? plural(total, noun) : shown + ' of ' + plural(total, noun);
  }

  function profileError(code) {
    if (code === 403 || code === 404)
      return 'Your account is not available. Contact your administrator.';
    if (code === 503) return 'The account service is unavailable. Please try again later.';
    return 'Your workspace could not be loaded. Please try again.';
  }

  function usersError(code) {
    if (code === 403) return 'Administrator access required.';
    if (code === 503) return 'The account service is unavailable. Please try again later.';
    return 'Users could not be loaded. Please try again.';
  }

  function foldersError(code) {
    if (code === 403) return 'Administrator access required.';
    if (code === 503) return 'The folder service is unavailable. Please try again later.';
    return 'Folders could not be loaded. Please try again.';
  }

  function logsError(code) {
    if (code === 400) return 'That log page does not exist.';
    if (code === 403) return 'Administrator access required.';
    if (code === 503) return 'The reporting service is unavailable. Please try again later.';
    return 'System logs could not be loaded. Please try again.';
  }

  /** A single full-width message row, used for loading, empty and error states. */
  function messageRow(table, columns, text) {
    const row = document.createElement('tr');
    const cell = document.createElement('td');
    cell.colSpan = columns;
    cell.className = 'admin-muted';
    cell.textContent = text;
    row.append(cell);
    table.replaceChildren(row);
  }

  function badge(className, text) {
    const span = document.createElement('span');
    span.className = 'admin-badge ' + className;
    span.textContent = text;
    return span;
  }

  function textCell(text) {
    const cell = document.createElement('td');
    cell.textContent = text;
    return cell;
  }

  function initials(username) {
    const cleaned = String(username || '').replace(/[^A-Za-z0-9]/g, '');
    return cleaned ? cleaned.slice(0, 2).toUpperCase() : '';
  }

  function fillSelect(select, placeholder, entries) {
    if (!select) return;
    const previous = select.value;
    const first = document.createElement('option');
    first.value = '';
    first.textContent = placeholder;
    select.replaceChildren(first);
    entries.forEach((entry) => {
      const option = document.createElement('option');
      option.value = entry.value;
      option.textContent = entry.label;
      select.append(option);
    });
    // Keeps the current choice while it still exists, otherwise resets.
    select.value = entries.some((entry) => entry.value === previous) ? previous : '';
  }

  function renderAccount(profile) {
    const account = element('account');
    if (account) account.textContent = profile.username;

    const avatar = document.querySelector('.account-avatar');
    const initialsBadge = initials(profile.username);
    if (avatar && initialsBadge) avatar.textContent = initialsBadge;

    // Reached only after the backend confirmed the role, so this is accurate.
    const role = document.querySelector('.account-copy span');
    if (role) role.textContent = 'Administrator';
  }

  /* ------------------------------------------------------------------ *
   * Management actions
   * ------------------------------------------------------------------ */

  // Per-action wording for the statuses the server can answer with. PortalClient
  // rewrites an upstream failure as a generic reason, so these carry the meaning
  // the response body loses.
  const MESSAGES = {
    createFolder: { fallback: 'The folder could not be created.' },
    deleteFolder: { fallback: 'The folder could not be deleted.' },
    lookup: { fallback: 'That account could not be looked up. Please try again.' },
    grant: {
      fallback: 'The permission could not be saved.',
      400: 'That folder is not available for permissions.',
    },
    revoke: {
      fallback: 'The permission could not be revoked.',
      400: 'That folder is not available for permissions.',
    },
    deactivate: {
      fallback: 'The account could not be deactivated.',
      403: 'You cannot deactivate another administrator.',
      404: 'That account no longer exists. Refresh the list and try again.',
    },
    reset: {
      fallback: 'The password-reset link could not be created.',
      403: 'You cannot create a reset link for another administrator.',
      404: 'That account no longer exists. Refresh the list and try again.',
      409: 'The account is inactive. A reset link requires an active account.',
    },
    invite: {
      fallback: 'The invitation could not be created.',
      404: 'No account has that username. Ask the user to create an account first.',
      409: 'Invitations require an active ordinary user. An administrator does not need one.',
    },
  };

  /** Sends a JSON mutation through the shared session and CSRF wrapper. */
  function jsonRequest(url, method, body) {
    return auth.fetch(url, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  }

  /**
   * Turns a failed response into something worth showing. A reason the server
   * states itself is preferred; the two generic pass-through strings say less
   * than the status does, so the status wins over them.
   */
  async function failureMessage(response, action) {
    const messages = MESSAGES[action] || {};
    let reason = '';
    try {
      const body = await response.json();
      if (body && typeof body.error === 'string') reason = body.error;
    } catch (_) {
      // Not every rejection carries a JSON body.
    }
    if (reason && !GENERIC_REASONS.includes(reason)) return reason;
    if (messages[response.status]) return messages[response.status];
    if (response.status === 401) return 'Your session has expired. Please sign in again.';
    if (response.status === 403) return 'Administrator access required.';
    if (response.status === 404) return 'That item no longer exists. Please refresh.';
    if (response.status === 409) return 'The account cannot be changed in its current state.';
    if (response.status >= 500) return 'The service is unavailable. Please try again later.';
    return messages.fallback || 'The action could not be completed. Please try again.';
  }

  /**
   * Runs one management action at a time. Every control is locked while a
   * request is in flight, so a second click cannot submit twice.
   */
  async function run(action) {
    if (busy) return;
    busy = true;
    updateControls();
    try {
      await action();
    } catch (error) {
      setStatus(error.message);
    } finally {
      busy = false;
      updateControls();
    }
  }

  let confirmResolve = null;

  /**
   * The page's own confirmation dialog. Resolves true only when the operator
   * presses the accept button: Cancel, Escape and a close all resolve false.
   */
  function confirmAction(title, message, acceptLabel, danger = true) {
    if (!confirmDialog) return Promise.resolve(false);
    confirmTitle.textContent = title;
    confirmMessage.textContent = message;
    confirmAccept.textContent = acceptLabel;
    confirmAccept.className = danger ? 'admin-danger-button' : 'admin-primary-button';
    return new Promise((resolve) => {
      confirmResolve = resolve;
      confirmDialog.showModal();
    });
  }

  /** Settles the open confirmation once; later events find no resolver. */
  function settleConfirm(accepted) {
    const resolve = confirmResolve;
    confirmResolve = null;
    if (confirmDialog && typeof confirmDialog.close === 'function') confirmDialog.close();
    if (resolve) resolve(accepted);
  }

  /**
   * Copies a generated link. The Clipboard API needs a secure context, so over
   * plain HTTP the field is selected for a manual copy instead.
   */
  async function copyLink(field, label) {
    const value = field ? field.value : '';
    if (!value) throw new Error('Create a link first.');
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      try {
        await navigator.clipboard.writeText(value);
        setStatus(label + ' copied.');
        return;
      } catch (_) {
        // Falls through to the manual copy below.
      }
    }
    if (field.select) field.select();
    if (field.setSelectionRange) field.setSelectionRange(0, value.length);
    try {
      if (document.execCommand && document.execCommand('copy')) {
        setStatus(label + ' copied.');
        return;
      }
    } catch (_) {
      // Falls through to the message below.
    }
    setStatus(
      'Automatic copying is unavailable. The link is selected: press Ctrl+C (Cmd+C on Mac).',
    );
  }

  /** Builds a redemption link; the token only ever lives in the link itself. */
  function linkFor(base, page, parameter, token) {
    const link = new URL(page, base);
    link.hash = parameter + '=' + token;
    return link.href;
  }

  /**
   * The invitation is sent to someone else, so the link is built on the origin
   * the operator enters rather than the one they happen to be browsing.
   */
  function websiteOrigin() {
    let origin;
    try {
      origin = new URL(websiteAddress ? websiteAddress.value.trim() : '');
    } catch (_) {
      throw new Error('Enter the website origin, for example https://your-website.example.');
    }
    const invalid =
      !['http:', 'https:'].includes(origin.protocol) ||
      origin.username ||
      origin.password ||
      origin.search ||
      origin.hash ||
      origin.pathname !== '/';
    if (invalid)
      throw new Error('Enter the website origin, for example https://your-website.example.');
    return origin.origin;
  }

  /** Mirrors the server's folder-name rule so a typo costs no round trip. */
  function folderNameProblem(name) {
    if (!FOLDER_NAME.test(name) || name.endsWith('.') || name.endsWith(' '))
      return 'Use a folder name of up to 80 letters, numbers, spaces, dots, underscores or hyphens, starting with a letter or number.';
    if (name === 'migrations' || name === 'upload' || name.startsWith('mmiv-verification-'))
      return 'That folder name is reserved. Choose another name.';
    return null;
  }

  function selectedPermission() {
    const inputs = document.querySelectorAll('input[name="permission"]');
    for (const input of inputs) if (input.checked) return input.value;
    return null;
  }

  function currentUser() {
    return usersByName.get(selectedUser ? selectedUser.value : '');
  }

  /**
   * Enables exactly the actions the verified server rules will accept, so a
   * control is never offered for a request that would be refused.
   */
  function updateControls() {
    const user = currentUser();
    const folder = permissionFolder ? permissionFolder.value : '';
    const active = Boolean(user && user.active);
    const administrator = Boolean(user && user.role === 'ADMIN');
    const self = Boolean(user && user.userId === actorId);

    if (createFolderButton)
      createFolderButton.disabled = busy || !(folderName && folderName.value.trim());
    if (savePermission)
      savePermission.disabled =
        busy || !active || administrator || !folder || !selectedPermission();
    if (revokePermission) revokePermission.disabled = busy || !user || administrator || !folder;
    if (createResetLink) createResetLink.disabled = busy || !active || (administrator && !self);
    if (deactivateUser) deactivateUser.disabled = busy || !active || (administrator && !self);
    if (copyResetLink) copyResetLink.disabled = !(resetLink && resetLink.value);
    if (createInvitationButton)
      createInvitationButton.disabled =
        busy || !(invitationUsername && invitationUsername.value.trim());
    if (copyInvitationLink) copyInvitationLink.disabled = !(invitationLink && invitationLink.value);
    folderDeleteButtons.forEach((button) => {
      button.disabled = busy;
    });
  }

  /** Replaces one account from a mutation response and redraws what shows it. */
  function applyUser(updated) {
    if (!updated || typeof updated.userId !== 'string') return false;
    users = users.map((user) => (user.userId === updated.userId ? updated : user));
    usersByName = new Map(users.map((user) => [user.username, user]));
    renderUsers();
    fillSelect(
      selectedUser,
      'Select user',
      users.map((user) => ({
        value: user.username,
        label: user.username + (user.active ? '' : ' (inactive)'),
      })),
    );
    reflectPermissions();
    return true;
  }

  /* ------------------------------------------------------------------ *
   * Folders
   * ------------------------------------------------------------------ */

  function folderRow(name) {
    const row = document.createElement('tr');
    const nameCell = document.createElement('td');
    const wrapper = document.createElement('span');
    const icon = document.createElement('span');
    const pathCell = document.createElement('td');
    const path = document.createElement('code');
    const statusCell = document.createElement('td');
    const actionsCell = document.createElement('td');
    const remove = document.createElement('button');

    wrapper.className = 'admin-folder-name';
    icon.className = 'admin-folder-icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = '▣';
    wrapper.append(icon, name);

    pathCell.className = 'admin-folder-path';
    path.textContent = '/' + name + '/';
    pathCell.append(path);

    // The folders endpoint returns names only, so there is no status to report.
    const noStatus = document.createElement('span');
    noStatus.className = 'admin-muted';
    noStatus.textContent = '—';
    statusCell.append(noStatus);

    remove.type = 'button';
    remove.className = 'admin-row-delete';
    remove.textContent = 'Delete';
    remove.addEventListener('click', () => submitDeleteFolder(name));
    folderDeleteButtons.push(remove);
    actionsCell.append(remove);

    nameCell.append(wrapper);
    row.append(nameCell, pathCell, statusCell, actionsCell);
    return row;
  }

  function renderFolders() {
    const query = folderSearch ? folderSearch.value.trim().toLowerCase() : '';
    // Filtered here rather than server-side: the endpoint takes no query
    // parameter, but it does return the complete list, so this is exhaustive.
    const visible = query ? folders.filter((name) => name.toLowerCase().includes(query)) : folders;

    folderDeleteButtons = [];
    if (folders.length === 0) messageRow(foldersTable, 4, 'No folders are registered yet.');
    else if (visible.length === 0) messageRow(foldersTable, 4, 'No folders match this search.');
    else {
      foldersTable.replaceChildren();
      visible.forEach((name) => foldersTable.append(folderRow(name)));
    }
    if (foldersMeta) foldersMeta.textContent = countLabel(visible.length, folders.length, 'folder');
    updateControls();
  }

  async function loadFolders() {
    if (foldersMeta) foldersMeta.textContent = 'Loading…';
    let payload;
    try {
      const response = await auth.fetch(FOLDERS_URL);
      if (!response.ok) throw new Error(foldersError(response.status));
      payload = await response.json();
      if (!Array.isArray(payload)) throw new Error('Folders could not be read. Please reload.');
    } catch (error) {
      // Deliberately leaves the table showing only the problem, never demo rows.
      folders = [];
      messageRow(foldersTable, 4, error.message);
      if (foldersMeta) foldersMeta.textContent = 'Unavailable';
      return;
    }
    folders = payload.filter((name) => typeof name === 'string');
    fillSelect(
      permissionFolder,
      'Select folder',
      folders.map((name) => ({ value: name, label: name })),
    );
    renderFolders();
    reflectPermissions();
  }

  async function submitCreateFolder() {
    const name = folderName ? folderName.value.trim() : '';
    if (!name) return;
    const problem = folderNameProblem(name);
    if (problem) {
      setStatus(problem);
      return;
    }
    await run(async () => {
      const response = await jsonRequest(ADMIN_FOLDERS_URL, 'POST', { name });
      if (!response.ok) throw new Error(await failureMessage(response, 'createFolder'));
      const list = await response.json();
      // The endpoint answers with the whole folder list. The search box and the
      // folder selection are left as the operator set them.
      if (Array.isArray(list)) {
        folders = list.filter((entry) => typeof entry === 'string');
        fillSelect(
          permissionFolder,
          'Select folder',
          folders.map((folder) => ({ value: folder, label: folder })),
        );
        renderFolders();
      } else {
        await loadFolders();
      }
      if (folderName) folderName.value = '';
      if (createFolderDialog) createFolderDialog.close();
      setStatus('Folder ' + name + ' created.');
      updateControls();
    });
  }

  /**
   * Deleting without a `path` removes the whole tree, every stored version and
   * the folder record itself, so the confirmation names all three.
   */
  async function submitDeleteFolder(name) {
    const accepted = await confirmAction(
      'Delete folder',
      'Permanently delete ' +
        name +
        '? Every file and stored version inside it is deleted, all permissions on it are revoked, and the folder itself is removed from administration. This cannot be undone.',
      'Delete folder',
    );
    if (!accepted) return;
    await run(async () => {
      const response = await auth.fetch(DELETE_FOLDER_URL + '?folder=' + encodeURIComponent(name), {
        method: 'DELETE',
      });
      if (!response.ok) throw new Error(await failureMessage(response, 'deleteFolder'));
      await loadFolders();
      setStatus('Folder ' + name + ' deleted.');
    });
  }

  /* ------------------------------------------------------------------ *
   * Users and permissions
   * ------------------------------------------------------------------ */

  function userRow(user) {
    const row = document.createElement('tr');
    const nameCell = document.createElement('td');
    const name = document.createElement('span');
    const roleCell = document.createElement('td');
    const statusCell = document.createElement('td');
    const actionsCell = document.createElement('td');
    const manage = document.createElement('button');
    const admin = user.role === 'ADMIN';

    name.className = 'admin-user-name';
    name.textContent = user.username;
    nameCell.append(name);

    roleCell.append(badge(admin ? 'badge-admin' : 'badge-user', admin ? 'Administrator' : 'User'));
    statusCell.append(
      badge(user.active ? 'badge-active' : 'badge-disabled', user.active ? 'Active' : 'Disabled'),
    );

    // The dialog is the page's permission surface. It is read-only in this
    // phase, but it reflects the account's real stored grants.
    manage.type = 'button';
    manage.className = 'admin-user-manage';
    manage.dataset.user = user.username;
    manage.textContent = 'Manage';
    manage.addEventListener('click', () => openUser(user.username));
    actionsCell.append(manage);

    row.append(nameCell, roleCell, statusCell, actionsCell);
    return row;
  }

  function renderUsers() {
    usersTable.replaceChildren();
    if (users.length === 0) {
      messageRow(
        usersTable,
        4,
        userSearch && userSearch.value.trim()
          ? 'No users match this search.'
          : 'No user accounts were found.',
      );
    } else {
      users.forEach((user) => usersTable.append(userRow(user)));
    }
    if (usersMeta) usersMeta.textContent = plural(users.length, 'user');
    updateControls();
  }

  async function loadUsers(query) {
    if (usersMeta) usersMeta.textContent = 'Loading…';
    let payload;
    try {
      // q is the verified server-side search parameter.
      const response = await auth.fetch(USERS_URL + '?q=' + encodeURIComponent(query));
      if (!response.ok) throw new Error(usersError(response.status));
      payload = await response.json();
      if (!Array.isArray(payload)) throw new Error('Users could not be read. Please reload.');
    } catch (error) {
      // The table is cleared rather than left holding the previous result.
      users = [];
      usersByName = new Map();
      fillSelect(selectedUser, 'Select user', []);
      messageRow(usersTable, 4, error.message);
      if (usersMeta) usersMeta.textContent = 'Unavailable';
      return;
    }
    users = payload;
    usersByName = new Map(users.map((user) => [user.username, user]));
    fillSelect(
      selectedUser,
      'Select user',
      users.map((user) => ({
        value: user.username,
        label: user.username + (user.active ? '' : ' (inactive)'),
      })),
    );
    renderUsers();
    reflectPermissions();
  }

  /**
   * Mirrors the selected account's stored grant into the dialog, so the radios
   * always start from the state the server holds. Choosing a different radio is
   * what the operator then submits.
   */
  function reflectPermissions() {
    const user = usersByName.get(selectedUser ? selectedUser.value : '');
    const folder = permissionFolder ? permissionFolder.value : '';
    const accesses = user && Array.isArray(user.destinations) ? user.destinations : [];
    const access = folder ? accesses.find((entry) => entry.destination === folder) : undefined;
    const permission = access ? access.permission : null;

    document.querySelectorAll('input[name="permission"]').forEach((input) => {
      input.checked = permission === input.value;
    });
    updateControls();
  }

  /** Opens the page's own dialog, which owns the presentation. */
  function openUser(username) {
    if (selectedUser) selectedUser.value = username;
    reflectPermissions();
    if (typeof window.openUserManagement === 'function') window.openUserManagement(username);
    else if (userDialog) userDialog.showModal();
  }

  async function submitPermission() {
    const user = currentUser();
    const destination = permissionFolder ? permissionFolder.value : '';
    const permission = selectedPermission();
    if (!user || !destination || !permission) return;
    await run(async () => {
      const response = await jsonRequest(
        USERS_URL + '/' + encodeURIComponent(user.userId) + '/permissions',
        'PUT',
        { destination, permission },
      );
      if (!response.ok) throw new Error(await failureMessage(response, 'grant'));
      const updated = await response.json();
      if (!applyUser(updated))
        throw new Error('The permission was saved but the account could not be read. Refresh.');
      setStatus('Access to ' + destination + ' saved for ' + user.username + '.');
    });
  }

  async function submitRevoke() {
    const user = currentUser();
    const destination = permissionFolder ? permissionFolder.value : '';
    if (!user || !destination) return;
    await run(async () => {
      const response = await auth.fetch(
        USERS_URL +
          '/' +
          encodeURIComponent(user.userId) +
          '/permissions?destination=' +
          encodeURIComponent(destination),
        { method: 'DELETE' },
      );
      if (!response.ok) throw new Error(await failureMessage(response, 'revoke'));
      const updated = await response.json();
      if (!applyUser(updated))
        throw new Error('The permission was revoked but the account could not be read. Refresh.');
      setStatus('Access to ' + destination + ' revoked for ' + user.username + '.');
    });
  }

  async function submitResetLink() {
    const user = currentUser();
    if (!user) return;
    // The link is sent to someone else, so it is built on the origin the
    // operator names rather than the one this page happens to be served from:
    // through an SSM tunnel the browsed origin is a local address the recipient
    // cannot open.
    let origin;
    try {
      origin = websiteOrigin();
    } catch (error) {
      setStatus(error.message);
      return;
    }
    const accepted = await confirmAction(
      'Create password-reset link',
      'Create a one-use password-reset link for ' +
        user.username +
        '? Their current password changes only when they use the link, and it expires after one hour.',
      'Create link',
      false,
    );
    if (!accepted) return;
    await run(async () => {
      const response = await auth.fetch(
        USERS_URL + '/' + encodeURIComponent(user.userId) + '/password-reset',
        { method: 'POST' },
      );
      if (!response.ok) throw new Error(await failureMessage(response, 'reset'));
      const result = await response.json();
      if (!result || typeof result.token !== 'string' || !result.token)
        throw new Error('The reset link could not be read. Please try again.');
      resetLink.value = linkFor(origin, RESET_PAGE, 'reset', result.token);
      setStatus('Reset link created for ' + user.username + '. Send it privately.');
      updateControls();
    });
  }

  async function submitCopyResetLink() {
    await run(() => copyLink(resetLink, 'Reset link'));
  }

  async function submitDeactivate() {
    const user = currentUser();
    if (!user) return;
    const self = user.userId === actorId;
    const accepted = await confirmAction(
      'Deactivate account',
      self
        ? 'Deactivate your own administrator account? You are signed out immediately. If this is the last administrator, an authorized operator must restore administrator access.'
        : 'Deactivate ' +
            user.username +
            '? Sign-in and access are blocked. Account records, permissions, uploaded files and audit history are kept.',
      'Deactivate',
    );
    if (!accepted) return;
    await run(async () => {
      const response = await auth.fetch(USERS_URL + '/' + encodeURIComponent(user.userId), {
        method: 'DELETE',
      });
      if (!response.ok) throw new Error(await failureMessage(response, 'deactivate'));
      if (self) {
        // The server cleared the session cookie with this response.
        setStatus('Your account was deactivated. Returning to sign in…');
        location.assign(LOGIN_PAGE);
        return;
      }
      await loadUsers(userSearch ? userSearch.value.trim() : '');
      setStatus(
        'Account ' +
          user.username +
          ' deactivated. Records, permissions, files and audit history were kept.',
      );
    });
  }

  /* ------------------------------------------------------------------ *
   * System logs
   * ------------------------------------------------------------------ */

  /**
   * The log record carries no level, so it is derived from the outcome the
   * record itself reports: a failed operation or a 5xx request is an error, a
   * 4xx request is a warning, everything else is informational. The filter
   * offers exactly these three values.
   */
  function levelOf(entry) {
    const data = entry && entry.data && typeof entry.data === 'object' ? entry.data : {};
    if (data.success === false) return 'ERROR';
    const code = typeof data.status === 'number' ? data.status : NaN;
    if (code >= 500) return 'ERROR';
    if (code >= 400) return 'WARNING';
    return 'INFO';
  }

  /** "2026-10-14 10:42", in the viewer's local time. */
  function formatTimestamp(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value == null ? '—' : String(value);
    const pad = (part) => String(part).padStart(2, '0');
    return (
      date.getFullYear() +
      '-' +
      pad(date.getMonth() + 1) +
      '-' +
      pad(date.getDate()) +
      ' ' +
      pad(date.getHours()) +
      ':' +
      pad(date.getMinutes())
    );
  }

  /** A compact, faithful rendering of the record's own payload. */
  function summarize(entry) {
    const parts = [];
    if (entry.sourceService) parts.push('service: ' + entry.sourceService);
    const data = entry.data && typeof entry.data === 'object' ? entry.data : {};
    Object.keys(data).forEach((key) => {
      if (DETAIL_FIELDS_SKIPPED.includes(key)) return;
      const value = data[key];
      if (value === null || value === undefined || value === '') return;
      parts.push(key + ': ' + (typeof value === 'object' ? JSON.stringify(value) : String(value)));
    });
    return parts.length ? parts.join(' · ') : '—';
  }

  function searchText(entry) {
    const data = entry.data && typeof entry.data === 'object' ? entry.data : {};
    const parts = [entry.operation, entry.sourceService, data.actorId];
    Object.values(data).forEach((value) => {
      parts.push(typeof value === 'object' ? JSON.stringify(value) : String(value));
    });
    return parts.filter(Boolean).join(' ').toLowerCase();
  }

  function logRow(entry) {
    const level = levelOf(entry);
    const actor = entry.data && entry.data.actorId ? String(entry.data.actorId) : '—';
    const row = document.createElement('tr');
    const levelCell = document.createElement('td');

    levelCell.append(badge(LEVEL_BADGES[level], level));
    row.append(
      textCell(formatTimestamp(entry.occurredAt)),
      levelCell,
      textCell(entry.operation || '—'),
      textCell(actor),
      textCell(summarize(entry)),
    );
    return row;
  }

  function renderLogs() {
    const query = logsSearch ? logsSearch.value.trim().toLowerCase() : '';
    const level = logsFilter ? logsFilter.value : 'all';
    // The endpoint filters nothing but the page, so search and level are
    // applied to the records the current page returned.
    const visible = logs.filter(
      (entry) =>
        (level === 'all' || levelOf(entry) === level) &&
        (query === '' || searchText(entry).includes(query)),
    );

    logsTable.replaceChildren();
    if (visible.length === 0) {
      if (logs.length === 0) messageRow(logsTable, 5, 'No log records on this page.');
      else messageRow(logsTable, 5, 'No records on this page match the current filter.');
    } else {
      visible.forEach((entry) => logsTable.append(logRow(entry)));
    }

    if (logsMeta) {
      const label =
        visible.length === logs.length
          ? plural(logs.length, 'record')
          : visible.length + ' of ' + plural(logs.length, 'record');
      logsMeta.textContent = label + ' · page ' + (logPage + 1);
    }
    if (logsPrevious) logsPrevious.disabled = logPage === 0;
    // No total is reported, so a short page is the only end-of-list signal.
    if (logsNext) logsNext.disabled = logs.length < LOG_PAGE_SIZE;
  }

  async function loadLogs() {
    messageRow(logsTable, 5, 'Loading system logs…');
    if (logsMeta) logsMeta.textContent = 'Loading…';
    try {
      const response = await auth.fetch(LOGS_URL + '?page=' + logPage);
      if (!response.ok) throw new Error(logsError(response.status));
      const payload = await response.json();
      if (!payload || !Array.isArray(payload.logs))
        throw new Error('System logs could not be read. Please reload.');
      logs = payload.logs;
    } catch (error) {
      // The page is cleared: no example records are put back in their place.
      logs = [];
      if (logsPrevious) logsPrevious.disabled = logPage === 0;
      if (logsNext) logsNext.disabled = true;
      messageRow(logsTable, 5, error.message);
      if (logsMeta) logsMeta.textContent = 'Unavailable';
      return;
    }
    renderLogs();
  }

  /* ------------------------------------------------------------------ *
   * Invitations
   * ------------------------------------------------------------------ */

  /**
   * The backend exposes no endpoint that lists invitations, so the tab keeps
   * its table and explains that history cannot be shown. Nothing is invented,
   * and the create dialog and its controls are left exactly as they were.
   */
  function showInvitationAvailability() {
    messageRow(
      invitationsTable,
      5,
      'Invitation history is unavailable. The service provides no read endpoint for invitations.',
    );
    if (invitationsMeta) invitationsMeta.textContent = 'Unavailable';
    if (invitationSearch) invitationSearch.disabled = true;
    if (invitationStatus) invitationStatus.disabled = true;
  }

  /** Looks the named account up so an invitation can only target a real one. */
  async function submitFindAccount() {
    const query = invitationUsername ? invitationUsername.value.trim() : '';
    if (!query) return;
    await run(async () => {
      const response = await auth.fetch(USERS_URL + '?q=' + encodeURIComponent(query));
      if (!response.ok) throw new Error(await failureMessage(response, 'lookup'));
      const found = await response.json();
      if (!Array.isArray(found))
        throw new Error('That account could not be read. Please try again.');
      // The server refuses anything else with a 409, so only these are offered.
      inviteResults = found.filter(
        (user) => user.active && user.role !== 'ADMIN' && !CI_ACCOUNT.test(user.username),
      );
      fillSelect(
        invitationAccount,
        'Select account',
        inviteResults.map((user) => ({ value: user.username, label: user.username })),
      );
      if (inviteResults.length === 0) {
        setStatus(
          'No eligible account found. Ask the user to create an account first; an existing administrator does not need an invitation.',
        );
        return;
      }
      const exact =
        inviteResults.find((user) => user.username.toLowerCase() === query.toLowerCase()) ||
        inviteResults[0];
      if (invitationAccount) invitationAccount.value = exact.username;
      if (invitationUsername) invitationUsername.value = exact.username;
      setStatus(
        inviteResults.length === 1
          ? 'One account found.'
          : inviteResults.length + ' accounts found. Choose the intended one from the dropdown.',
      );
      updateControls();
    });
  }

  async function submitCreateInvitation() {
    const username = invitationUsername ? invitationUsername.value.trim() : '';
    if (!username) return;
    let origin;
    try {
      origin = websiteOrigin();
    } catch (error) {
      setStatus(error.message);
      return;
    }
    await run(async () => {
      const response = await jsonRequest(INVITATIONS_URL, 'POST', { username });
      if (!response.ok) throw new Error(await failureMessage(response, 'invite'));
      const result = await response.json();
      if (!result || typeof result.token !== 'string' || !result.token)
        throw new Error('The invitation link could not be read. Please try again.');
      invitationLink.value = linkFor(origin, INVITE_PAGE, 'invite', result.token);
      setStatus('Invitation created for ' + username + '. Send the link privately.');
      updateControls();
    });
  }

  async function submitCopyInvitationLink() {
    await run(() => copyLink(invitationLink, 'Invitation link'));
  }

  /* ------------------------------------------------------------------ *
   * Wiring
   * ------------------------------------------------------------------ */

  function wire() {
    if (folderSearch) folderSearch.addEventListener('input', renderFolders);

    if (searchUsersButton)
      searchUsersButton.addEventListener('click', () =>
        loadUsers(userSearch ? userSearch.value.trim() : ''),
      );
    if (userSearch)
      userSearch.addEventListener('keydown', (event) => {
        if (event.key !== 'Enter') return;
        event.preventDefault();
        loadUsers(userSearch.value.trim());
      });

    if (logsSearch) logsSearch.addEventListener('input', renderLogs);
    if (logsFilter) logsFilter.addEventListener('change', renderLogs);
    if (refreshLogs) refreshLogs.addEventListener('click', () => loadLogs());
    if (logsPrevious)
      logsPrevious.addEventListener('click', () => {
        if (logPage === 0) return;
        logPage -= 1;
        loadLogs();
      });
    if (logsNext)
      logsNext.addEventListener('click', () => {
        logPage += 1;
        loadLogs();
      });

    if (selectedUser) selectedUser.addEventListener('change', reflectPermissions);
    if (permissionFolder) permissionFolder.addEventListener('change', reflectPermissions);
    document
      .querySelectorAll('input[name="permission"]')
      .forEach((input) => input.addEventListener('change', updateControls));

    // Folder management
    if (folderName) folderName.addEventListener('input', updateControls);
    if (createFolderButton) createFolderButton.addEventListener('click', submitCreateFolder);
    if (savePermission) savePermission.addEventListener('click', submitPermission);
    if (revokePermission) revokePermission.addEventListener('click', submitRevoke);
    if (createResetLink) createResetLink.addEventListener('click', submitResetLink);
    if (deactivateUser) deactivateUser.addEventListener('click', submitDeactivate);
    if (copyResetLink) copyResetLink.addEventListener('click', submitCopyResetLink);

    // Invitations
    if (findAccount) findAccount.addEventListener('click', submitFindAccount);
    if (invitationUsername)
      invitationUsername.addEventListener('keydown', (event) => {
        if (event.key !== 'Enter') return;
        event.preventDefault();
        submitFindAccount();
      });
    if (invitationUsername)
      invitationUsername.addEventListener('input', () => {
        // A new query invalidates the account chosen from the previous lookup.
        inviteResults = [];
        fillSelect(invitationAccount, 'Select account', []);
        updateControls();
      });
    if (invitationAccount)
      invitationAccount.addEventListener('change', () => {
        const chosen = inviteResults.find((user) => user.username === invitationAccount.value);
        if (chosen && invitationUsername) invitationUsername.value = chosen.username;
        updateControls();
      });
    if (createInvitationButton)
      createInvitationButton.addEventListener('click', submitCreateInvitation);
    if (copyInvitationLink) copyInvitationLink.addEventListener('click', submitCopyInvitationLink);

    // Confirmation dialog: Cancel, Escape and a close all decline.
    if (confirmAccept) confirmAccept.addEventListener('click', () => settleConfirm(true));
    if (confirmCancel) confirmCancel.addEventListener('click', () => settleConfirm(false));
    if (confirmDialog) confirmDialog.addEventListener('close', () => settleConfirm(false));
  }

  /**
   * Reports a page-level failure in every surface it touched, without putting
   * any record on screen.
   */
  function failWorkspace(message) {
    setStatus(message);
    messageRow(foldersTable, 4, 'Folders are unavailable.');
    messageRow(usersTable, 4, 'Users are unavailable.');
    messageRow(logsTable, 5, 'System logs are unavailable.');
    if (foldersMeta) foldersMeta.textContent = 'Unavailable';
    if (usersMeta) usersMeta.textContent = 'Unavailable';
    if (logsMeta) logsMeta.textContent = 'Unavailable';
  }

  /**
   * Refuses the workspace without issuing a single administrative request.
   * The backend re-checks the current database role on every one of those
   * endpoints, so this only avoids showing a page the account cannot use.
   */
  function denyAccess() {
    const workspace = document.querySelector('.admin-workspace');
    if (workspace) workspace.hidden = true;
    // Clears the loading placeholders too, so the page holds no administrative
    // surface at all while the redirect is in flight.
    foldersTable.replaceChildren();
    usersTable.replaceChildren();
    invitationsTable.replaceChildren();
    logsTable.replaceChildren();
    setStatus('Administrator access required. Returning to your workspace…');
    location.replace(HOME_PAGE);
  }

  async function fetchProfile() {
    const response = await auth.fetch(PROFILE_URL);
    if (!response.ok) throw new Error(profileError(response.status));
    const profile = await response.json();
    if (!profile || typeof profile.username !== 'string' || typeof profile.role !== 'string') {
      throw new Error('Your account details could not be read. Please reload the page.');
    }
    return profile;
  }

  async function load() {
    setStatus('Loading administration…');
    let profile;
    try {
      profile = await fetchProfile();
    } catch (error) {
      failWorkspace(error.message);
      return;
    }
    if (profile.role !== 'ADMIN') {
      denyAccess();
      return;
    }

    actorId = typeof profile.userId === 'string' ? profile.userId : null;
    renderAccount(profile);
    clearStatus();
    showInvitationAvailability();
    await Promise.all([loadFolders(), loadUsers(''), loadLogs()]);
    updateControls();
  }

  // The page ships no records of its own; these placeholders are replaced by
  // backend data, or by the failure that stopped it.
  messageRow(foldersTable, 4, 'Loading folders…');
  messageRow(usersTable, 4, 'Loading users…');
  messageRow(logsTable, 5, 'Loading system logs…');
  showInvitationAvailability();
  if (logsHint)
    logsHint.textContent =
      'Newest first, 100 records per page. Levels are derived from each record’s outcome.';
  // The recipient of an invitation opens the site the operator names here, which
  // is normally the one this page is already served from.
  if (websiteAddress && !websiteAddress.value) websiteAddress.value = location.origin;
  wire();

  // When the session cannot be verified, mmiv-auth.js owns the page: it is
  // already redirecting to the login page and its message must stand.
  auth.ready.then(load, () => {});
})();
