'use strict';

/**
 * File Manager read operations: folder selection, listing, search and paging.
 *
 * Verified contracts:
 *   GET /api/access/me
 *     -> { userId, username, role, destinations: [{ destination, permission }] }
 *
 *   GET /api/v1/portal/files?folder=<name>&cursor=<token>
 *     -> { files: [{ fileId, name, sizeBytes, modifiedAt, canDelete }], nextCursor }
 *     name is the storage key with the folder prefix and any migrations/<id>/
 *     segment removed. Only files are returned: the backend filters out every
 *     key ending in "/", so there are no folder entries to render.
 *     The server lists 100 keys per call and authorises READ on the folder,
 *     answering 403 when the account has no access.
 *
 * Uploads, downloads, deletion, preview and subfolder navigation are not part of
 * this phase, so the row actions are rendered but disabled.
 */
(() => {
  const PROFILE_URL = '/api/access/me';
  const FILES_URL = '/api/v1/portal/files';
  const TRANSFER_URL = '/api/v1/transfers';
  const FOLDER_TRANSFER_URL = '/api/v1/transfers/folder';

  // Limits enforced by TransferRequestValidator and FolderUploadService.
  const MAX_FILE_BYTES = 104857600;
  const MAX_FOLDER_FILES = 500;
  const UPLOAD_TYPES = /\.(png|jpe?g|mp4|mp3|txt|pdf|pptx?|docx?)$/i;

  const ARCHIVE = ['zip', 'rar', '7z', 'tar', 'gz', 'bz2'];
  const VIDEO = ['mp4', 'mov', 'avi', 'mkv', 'webm', 'm4v'];
  const IMAGE = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'];
  const TEXT = ['txt', 'csv', 'md', 'json', 'log', 'xml', 'yml', 'yaml'];

  const element = (id) => document.getElementById(id);
  const auth = window.mmivAuth;
  const status = element('status');

  if (!auth) {
    if (status) status.textContent = 'Unable to verify your login. Please reload the page.';
    return;
  }

  const folderSelect = element('folder');
  const search = element('fileSearch');
  const table = element('files');
  const emptyFolder = element('emptyFolder');
  const browseStatus = element('browseStatus');
  const fileCount = element('fileCount');
  const listStatus = element('listStatus');
  const breadcrumbs = element('breadcrumbs');
  const refresh = element('refreshFiles');
  const sentinel = element('loadSentinel');

  if (!folderSelect || !table) return;

  let files = [];
  let cursor = null;
  let loading = false;
  // Invalidates responses that arrive after the folder changed.
  let generation = 0;
  let query = '';
  let observer = null;
  let lastStatus = null;

  /** Page-level problems only; #browseStatus reports the listing itself. */
  function setStatus(text) {
    if (!status) return;
    lastStatus = text;
    status.textContent = text;
  }

  function clearStatus() {
    if (status && status.textContent === lastStatus) {
      status.textContent = '';
      lastStatus = '';
    }
  }

  function setBrowseStatus(text) {
    if (browseStatus) browseStatus.textContent = text;
  }

  function profileError(code) {
    if (code === 403 || code === 404)
      return 'Your account is not available. Contact your administrator.';
    if (code === 503) return 'The account service is unavailable. Please try again later.';
    return 'Your workspace could not be loaded. Please try again.';
  }

  function filesError(code) {
    if (code === 403) return 'No access to this folder.';
    if (code === 404) return 'This folder is no longer available.';
    if (code === 400) return 'That folder cannot be listed.';
    if (code === 503) return 'The file service is unavailable. Please try again later.';
    return 'Your files could not be loaded. Please try again.';
  }

  /** At most one decimal, and no trailing ".0", matching the table's existing style. */
  function formatSize(bytes) {
    if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) return '—';
    const round = (value) => String(Number(value.toFixed(1)));
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return round(bytes / 1024) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return round(bytes / (1024 * 1024)) + ' MB';
    return round(bytes / (1024 * 1024 * 1024)) + ' GB';
  }

  function formatDate(value) {
    if (!value) return '—';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '—';
    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  }

  /**
   * The listing API returns no content type, so the badge and label are derived
   * from the extension. Only pdf, archive, video and the base icon exist in
   * file-manager.css, so every other type uses the base .file-icon styling.
   */
  function describe(name) {
    const parts = String(name).split('.');
    const extension = parts.length > 1 ? parts.pop().toLowerCase() : '';
    const badge = extension ? extension.slice(0, 5).toUpperCase() : 'FILE';
    if (extension === 'pdf') return { badge, label: 'PDF document', variant: 'pdf-type' };
    if (ARCHIVE.includes(extension)) return { badge, label: 'Archive', variant: 'zip-type' };
    if (VIDEO.includes(extension)) return { badge, label: 'Video', variant: 'video-type' };
    if (IMAGE.includes(extension)) return { badge, label: 'Image', variant: '' };
    if (TEXT.includes(extension)) return { badge, label: 'Text document', variant: '' };
    return { badge, label: 'File', variant: '' };
  }

  function fileRow(file) {
    const type = describe(file.name);
    const row = document.createElement('tr');
    const nameCell = document.createElement('td');
    const wrapper = document.createElement('span');
    const icon = document.createElement('span');
    const label = document.createElement('span');
    const typeCell = document.createElement('td');
    const sizeCell = document.createElement('td');
    const dateCell = document.createElement('td');
    const actionsCell = document.createElement('td');
    const actions = document.createElement('div');

    wrapper.className = 'file-name';
    icon.className = 'file-icon' + (type.variant ? ' ' + type.variant : '');
    icon.textContent = type.badge;
    label.textContent = file.name;
    wrapper.append(icon, label);
    nameCell.append(wrapper);

    typeCell.textContent = type.label;
    sizeCell.textContent = formatSize(file.sizeBytes);
    dateCell.textContent = formatDate(file.modifiedAt);

    // Buttons keep the existing .table-action styling: an <a> would render as a
    // default underlined link because that class carries no anchor reset.
    actions.className = 'table-actions';
    const preview = document.createElement('button');
    preview.type = 'button';
    preview.className = 'table-action';
    preview.textContent = 'Preview';
    preview.addEventListener('click', () => openPreview(file));
    const download = document.createElement('button');
    download.type = 'button';
    download.className = 'table-action';
    download.textContent = 'Download';
    download.addEventListener('click', () => startDownload(file.fileId, download));
    actions.append(preview, download);
    // canDelete is the backend's own answer to "may this account remove this
    // file", so the control is omitted entirely when it is false.
    if (file.canDelete) {
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'table-action';
      remove.textContent = 'Delete';
      remove.addEventListener('click', () => deleteFile(file, remove));
      actions.append(remove);
    }
    actionsCell.append(actions);

    row.append(nameCell, typeCell, sizeCell, dateCell, actionsCell);
    return row;
  }

  function downloadUrl(fileId) {
    return '/api/v1/files/' + encodeURIComponent(fileId) + '/download';
  }

  function downloadError(code) {
    if (code === 403) return 'You do not have access to this file.';
    if (code === 404) return 'This file is no longer available.';
    if (code === 503) return 'The file service is unavailable. Please try again later.';
    return 'The file could not be downloaded. Please try again.';
  }

  /**
   * Starts a browser download.
   *
   * Transfer authenticates the request with the HttpOnly session cookie and
   * answers with Content-Disposition: attachment, so no token is ever placed in
   * the URL. A hidden anchor starts the transfer while the button keeps its
   * .table-action styling; nothing is buffered in memory, so large files stream.
   */
  function triggerDownload(url) {
    const link = document.createElement('a');
    link.href = url;
    link.rel = 'noopener';
    document.body.append(link);
    link.click();
    link.remove();
  }

  async function startDownload(fileId, button) {
    if (button.disabled) return;
    button.disabled = true;
    try {
      // HEAD returns the same status without the bytes, so a missing or denied
      // file is reported here instead of navigating the browser to an error.
      const probe = await auth.fetch(downloadUrl(fileId), { method: 'HEAD' });
      if (!probe.ok) throw new Error(downloadError(probe.status));
      triggerDownload(downloadUrl(fileId));
    } catch (error) {
      setBrowseStatus(error.message);
    } finally {
      // Browsers report no completion for a download, so the button is released
      // after a short window rather than being left permanently dead.
      setTimeout(() => {
        button.disabled = false;
      }, 1500);
    }
  }

  function openPreview(file) {
    if (!window.mmivPreview) return;
    window.mmivPreview.open(file.fileId, file.name);
  }

  function deleteError(code) {
    if (code === 401) return 'Your session has expired. Sign in again.';
    if (code === 403) return 'You can only delete files you uploaded.';
    if (code === 404) return 'This file is no longer available.';
    if (code === 409) return 'This file changed since the list loaded. Reload and try again.';
    if (code === 503) return 'The file service is unavailable. Please try again later.';
    return 'The file could not be deleted. Please try again.';
  }

  /**
   * Deletes a single stored file.
   *
   * The request carries the file's own id, which the server resolves to one
   * storage key, so deleting a file can never remove a folder. window.confirm is
   * used because this stylesheet defines no confirmation dialog and the CSS must
   * not change.
   */
  async function deleteFile(file, button) {
    if (button.disabled) return;
    const question = 'Permanently delete "' + file.name + '" and all stored versions?';
    if (!window.confirm(question)) return;
    button.disabled = true;
    try {
      const response = await auth.fetch('/api/v1/portal/files/' + encodeURIComponent(file.fileId), {
        method: 'DELETE',
      });
      if (!response.ok) throw new Error(deleteError(response.status));
      await loadFiles({ reset: true });
    } catch (error) {
      setBrowseStatus(error.message);
      // The row is still on screen, so the button is released for another try.
      button.disabled = false;
    }
  }

  function renderBreadcrumbs() {
    if (!breadcrumbs) return;
    const parts = [document.createElement('span')];
    parts[0].textContent = 'Workspace';
    const folder = folderSelect.value;
    if (folder) {
      const separator = document.createElement('span');
      separator.setAttribute('aria-hidden', 'true');
      separator.textContent = '›';
      const current = document.createElement('span');
      current.textContent = folder;
      parts.push(separator, current);
    }
    breadcrumbs.replaceChildren(...parts);
  }

  function render() {
    const visible = query ? files.filter((file) => file.name.toLowerCase().includes(query)) : files;

    table.replaceChildren();
    visible.forEach((file) => table.append(fileRow(file)));

    if (fileCount) fileCount.textContent = files.length + (files.length === 1 ? ' item' : ' items');

    if (listStatus) {
      // The search only sees the pages fetched so far, so the count names them
      // as loaded rather than implying the whole folder was searched.
      if (query)
        listStatus.textContent = visible.length + ' of ' + files.length + ' loaded files match';
      else if (cursor) listStatus.textContent = 'Scroll to load more';
      else listStatus.textContent = '';
    }

    if (emptyFolder) emptyFolder.hidden = files.length !== 0;
    if (files.length === 0) setBrowseStatus('');
    else if (query && visible.length === 0) setBrowseStatus('No loaded files match your search.');
    else setBrowseStatus('');

    observeSentinel();
  }

  /** Re-arms the observer after every render so paging continues while visible. */
  function observeSentinel() {
    if (observer) {
      observer.disconnect();
      observer = null;
    }
    if (!sentinel || typeof IntersectionObserver !== 'function' || !cursor) return;
    observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) loadFiles();
      },
      { rootMargin: '160px' },
    );
    observer.observe(sentinel);
  }

  function clearListing() {
    files = [];
    cursor = null;
    table.replaceChildren();
    if (fileCount) fileCount.textContent = '0 items';
    if (listStatus) listStatus.textContent = '';
    if (emptyFolder) emptyFolder.hidden = true;
    observeSentinel();
  }

  async function loadFiles(options = {}) {
    if (options.reset) {
      generation += 1;
      loading = false;
      clearListing();
      renderBreadcrumbs();
    }
    const folder = folderSelect.value;
    if (!folder) {
      clearListing();
      setBrowseStatus('Select a folder to see its files.');
      return;
    }
    if (loading) return;
    const current = generation;
    loading = true;
    setBrowseStatus('Loading files…');
    try {
      const response = await auth.fetch(
        FILES_URL +
          '?folder=' +
          encodeURIComponent(folder) +
          (cursor ? '&cursor=' + encodeURIComponent(cursor) : ''),
      );
      if (current !== generation) return;
      if (!response.ok) throw new Error(filesError(response.status));
      const data = await response.json();
      if (current !== generation) return;
      const page = data && Array.isArray(data.files) ? data.files : [];
      const known = new Set(files.map((file) => file.fileId));
      files.push(...page.filter((file) => !known.has(file.fileId)));
      cursor =
        data && typeof data.nextCursor === 'string' && data.nextCursor ? data.nextCursor : null;
      render();
    } catch (error) {
      if (current !== generation) return;
      // Never fall back to example rows: an error leaves the table empty.
      clearListing();
      setBrowseStatus(error.message);
    } finally {
      if (current === generation) loading = false;
    }
  }

  function populateFolders(destinations) {
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = 'Select a folder';
    folderSelect.replaceChildren(placeholder);
    destinations.forEach((folder) => {
      if (!folder || typeof folder.destination !== 'string') return;
      const option = document.createElement('option');
      option.value = folder.destination;
      option.textContent = folder.destination;
      folderSelect.append(option);
    });
  }

  function selectRequestedFolder(destinations) {
    const requested = new URLSearchParams(location.search).get('folder');
    if (!requested) return;
    if (destinations.some((folder) => folder && folder.destination === requested))
      folderSelect.value = requested;
  }

  /* ------------------------------------------------------------------ uploads */

  let uploading = false;

  /** Only folders the backend reports as WRITE may receive an upload. */
  function populateDestinations(destinations) {
    const writable = destinations.filter((folder) => folder && folder.permission === 'WRITE');
    [element('destination'), element('folderDestination')].forEach((select) => {
      if (!select) return;
      const placeholder = document.createElement('option');
      placeholder.value = '';
      placeholder.textContent = writable.length ? 'Select destination' : 'No upload destinations';
      select.replaceChildren(placeholder);
      writable.forEach((folder) => {
        const option = document.createElement('option');
        option.value = folder.destination;
        option.textContent = folder.destination;
        select.append(option);
      });
      select.disabled = writable.length === 0;
    });
    // A read-only account is not offered the upload menus at all, because every
    // upload they could start would end at a destination the server refuses.
    document.querySelectorAll('.upload-menu').forEach((menu) => {
      menu.hidden = writable.length === 0;
    });
  }

  function failUpload(result, label, message) {
    if (label) label.textContent = message;
    if (result) result.textContent = message;
  }

  function uploadProgressHandler(bar, label) {
    return (progress) => {
      if (!bar || !label) return;
      if (progress.phase === 'processing') {
        // Every byte is sent; the server is now storing and verifying.
        bar.removeAttribute('value');
        label.textContent = 'Files received. Transferring and verifying integrity…';
      } else {
        bar.value = progress.percent;
        label.textContent = 'Uploading: ' + Math.round(progress.percent) + '%';
      }
    };
  }

  /**
   * A 403 from Transfer is either a denied folder or a CSRF token the helper
   * could not refresh even after its single replay.
   */
  async function uploadForbidden(response) {
    let error;
    try {
      error = (await response.json()).error;
    } catch (_) {}
    if (error === 'CSRF_INVALID') return 'Your session token expired. Reload the page and retry.';
    return error || 'No access to this folder.';
  }

  async function readUploadResponse(response) {
    const text = await response.text();
    let data;
    try {
      data = JSON.parse(text);
    } catch (_) {
      throw new Error('The server returned an invalid response. HTTP ' + response.status);
    }
    if (!response.ok) throw new Error(data.error || 'Upload failed. HTTP ' + response.status);
    return data;
  }

  /** Reloads only when the upload landed in the folder currently on screen. */
  async function refreshIfCurrent(folder) {
    if (folderSelect.value === folder) await loadFiles({ reset: true });
  }

  async function submitFileUpload(event) {
    event.preventDefault();
    if (uploading) return;

    const input = element('file');
    const destination = element('destination');
    const result = element('result');
    const label = element('uploadProgressLabel');
    const bar = element('uploadProgress');
    const links = element('links');
    const feedback = document.querySelector('#uploadFileDialog .upload-feedback');
    const file = input && input.files ? input.files[0] : null;
    const folder = destination ? destination.value : '';

    if (links) links.hidden = true;
    if (!file) return failUpload(result, label, 'Choose a file to upload.');
    if (!file.size) return failUpload(result, label, 'That file is empty.');
    if (file.size > MAX_FILE_BYTES)
      return failUpload(result, label, 'Choose a file no larger than 100 MiB.');
    if (!UPLOAD_TYPES.test(file.name))
      return failUpload(result, label, 'That file type is not supported.');
    if (!folder) return failUpload(result, label, 'Choose a destination folder.');

    const body = new FormData();
    body.append('file', file);
    body.append('destinationId', folder);

    const button = event.submitter || element('submit');
    uploading = true;
    if (button) button.disabled = true;
    if (feedback) feedback.open = true;
    if (label) label.textContent = 'Uploading ' + file.name + '…';
    if (bar) bar.value = 0;
    try {
      const response = await auth.upload(TRANSFER_URL, body, uploadProgressHandler(bar, label));
      if (response.status === 403) throw new Error(await uploadForbidden(response));
      const data = await readUploadResponse(response);
      if (label) label.textContent = data.success ? 'Upload complete.' : 'Upload needs attention.';
      if (bar) bar.value = 100;
      if (result)
        result.textContent =
          (data.success
            ? 'Upload succeeded and integrity verification passed.'
            : data.cleanupStatus === 'DELETED'
              ? 'Integrity verification failed. The uploaded file was deleted.'
              : 'Integrity verification failed. Automatic deletion could not be confirmed; check the file manually.') +
          '\nFile: ' +
          data.fileName +
          '\nFolder: ' +
          folder;
      if (data.success && data.fileId) {
        const download = element('download');
        if (download) download.href = downloadUrl(data.fileId);
        if (links) links.hidden = false;
      }
      await refreshIfCurrent(folder);
    } catch (error) {
      failUpload(result, label, error.message);
    } finally {
      uploading = false;
      if (button) button.disabled = false;
    }
  }

  function renderFolderResults(table, results) {
    if (!table) return;
    table.replaceChildren();
    (Array.isArray(results) ? results : []).forEach((entry) => {
      const row = document.createElement('tr');
      const values = [
        entry.relativePath,
        entry.status,
        entry.upload ? entry.upload.validationResult : '—',
        entry.error || (entry.upload ? entry.upload.message : '') || '',
      ];
      values.forEach((value) => {
        const cell = document.createElement('td');
        cell.textContent = value == null ? '' : String(value);
        row.append(cell);
      });
      table.append(row);
    });
  }

  async function submitFolderUpload(event) {
    event.preventDefault();
    if (uploading) return;

    const input = element('folderPicker');
    const destination = element('folderDestination');
    const result = element('folderResult');
    const label = element('folderProgressLabel');
    const bar = element('folderProgressBar');
    const panel = element('folderProgress');
    const table = element('folderFileResults');
    const files = input && input.files ? Array.from(input.files) : [];
    const folder = destination ? destination.value : '';

    if (table) table.replaceChildren();
    if (files.length === 0) return failUpload(result, label, 'Choose a folder to upload.');
    if (files.length > MAX_FOLDER_FILES)
      return failUpload(result, label, 'Choose a folder with no more than 500 files.');
    if (!folder) return failUpload(result, label, 'Choose a destination folder.');

    let total = 0;
    for (const file of files) {
      const path = file.webkitRelativePath;
      if (!path) return failUpload(result, label, 'The browser did not report a folder path.');
      if (!file.size) return failUpload(result, label, 'This file is empty: ' + path);
      if (!UPLOAD_TYPES.test(file.name))
        return failUpload(result, label, 'Unsupported file type: ' + path);
      // Transfer pairs files[i] with relativePaths[i] and requires the path to
      // end with the uploaded file name, so webkitRelativePath is the right value.
      if (!path.endsWith('/' + file.name))
        return failUpload(result, label, 'The folder path does not match: ' + path);
      total += file.size;
      if (total > MAX_FILE_BYTES)
        return failUpload(result, label, 'The folder exceeds the 100 MiB upload limit.');
    }

    const body = new FormData();
    body.append('destinationId', folder);
    files.forEach((file) => {
      // The two lists are paired by index, so they are appended together.
      body.append('files', file, file.name);
      body.append('relativePaths', file.webkitRelativePath);
    });

    const button = event.submitter || element('folderSubmit');
    uploading = true;
    if (button) button.disabled = true;
    if (panel) panel.open = true;
    if (label) label.textContent = 'Preparing upload…';
    if (bar) bar.value = 0;
    try {
      const response = await auth.upload(
        FOLDER_TRANSFER_URL,
        body,
        uploadProgressHandler(bar, label),
      );
      if (response.status === 403) throw new Error(await uploadForbidden(response));
      const data = await readUploadResponse(response);
      if (bar) bar.value = 100;
      if (label)
        label.textContent = data.failed ? 'Finished with some failed files.' : 'Upload complete.';
      if (result)
        result.textContent =
          data.succeeded +
          ' of ' +
          (data.succeeded + data.failed) +
          ' files uploaded.' +
          (data.prefix ? '\nStored under: ' + data.prefix : '');
      renderFolderResults(table, data.files);
      await refreshIfCurrent(folder);
    } catch (error) {
      failUpload(result, label, error.message);
    } finally {
      uploading = false;
      if (button) button.disabled = false;
    }
  }

  async function start() {
    let profile;
    try {
      const response = await auth.fetch(PROFILE_URL);
      if (!response.ok) throw new Error(profileError(response.status));
      profile = await response.json();
      if (!profile || !Array.isArray(profile.destinations)) throw new Error(profileError(0));
    } catch (error) {
      // mmiv-auth.js owns #status when the session itself failed; this only runs
      // once the session resolved, so the message here is ours to report.
      setStatus(error.message);
      setBrowseStatus('');
      return;
    }
    clearStatus();
    populateFolders(profile.destinations);
    populateDestinations(profile.destinations);
    selectRequestedFolder(profile.destinations);

    // Administration is revealed only for the role the backend verified.
    document.querySelectorAll('[data-admin]').forEach((node) => {
      node.hidden = profile.role !== 'ADMIN';
    });

    await loadFiles({ reset: true });
  }

  folderSelect.addEventListener('change', () => loadFiles({ reset: true }));

  if (search) {
    search.addEventListener('input', () => {
      query = search.value.trim().toLowerCase();
      render();
    });
  }

  if (refresh) {
    refresh.addEventListener('click', () => loadFiles({ reset: true }));
  }

  const uploadForm = element('uploadForm');
  if (uploadForm) uploadForm.addEventListener('submit', submitFileUpload);
  const folderForm = element('folderForm');
  if (folderForm) folderForm.addEventListener('submit', submitFolderUpload);

  renderBreadcrumbs();
  setBrowseStatus('Select a folder to see its files.');

  // When the session cannot be verified, mmiv-auth.js owns the page: it is
  // already redirecting to the login page and its message must stand.
  auth.ready.then(start, () => {});
})();
