(() => {
  const dialog = document.getElementById('filePreviewDialog');
  const title = document.getElementById('filePreviewTitle');
  const content = document.getElementById('filePreviewContent');
  const details = document.getElementById('filePreviewDetails');
  const downloadLink = document.getElementById('downloadPreviewFile');
  const fileInput = document.getElementById('file');
  const closeButton = document.getElementById('closeFilePreview');

  if (!dialog || !title || !content || !details || !downloadLink || !closeButton) {
    throw new Error('The file preview dialog is missing required elements.');
  }

  let previewUrl = '';
  let renderVersion = 0;

  function clearPreviewUrl() {
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl);
      previewUrl = '';
    }
  }

  function showMessage(badgeText, heading, message) {
    const wrapper = document.createElement('div');
    wrapper.className = 'file-preview-message';

    const badge = document.createElement('span');
    badge.className = 'file-preview-badge';
    badge.textContent = badgeText;

    const messageHeading = document.createElement('h3');
    messageHeading.textContent = heading;

    const messageText = document.createElement('p');
    messageText.textContent = message;

    wrapper.append(badge, messageHeading, messageText);
    content.replaceChildren(wrapper);
  }

  function getFileKind(file) {
    const extension = file.name.split('.').pop().toLowerCase();

    if (file.type.startsWith('image/')) return 'image';
    if (file.type === 'application/pdf' || extension === 'pdf') return 'pdf';
    if (file.type.startsWith('video/')) return 'video';
    if (file.type.startsWith('audio/')) return 'audio';
    if (file.type.startsWith('text/') || ['txt', 'csv', 'md', 'json', 'log'].includes(extension))
      return 'text';
    return 'unsupported';
  }

  function getBadgeText(name) {
    const extension = name.split('.').pop();
    return extension && extension !== name ? extension.slice(0, 5).toUpperCase() : 'FILE';
  }

  function showFilePreview(file) {
    clearPreviewUrl();
    const version = ++renderVersion;
    const kind = getFileKind(file);
    title.textContent = file.name;
    details.textContent = `${file.type || 'Unknown file type'} · ${formatFileSize(file.size)}`;
    previewUrl = URL.createObjectURL(file);
    downloadLink.href = previewUrl;
    downloadLink.download = file.name;
    downloadLink.hidden = false;

    if (kind === 'image') {
      const image = document.createElement('img');
      image.alt = file.name;
      image.src = previewUrl;
      image.addEventListener(
        'error',
        () => {
          showMessage(
            getBadgeText(file.name),
            'Could not display this image',
            'Try downloading the file to open it in another application.',
          );
        },
        { once: true },
      );
      content.replaceChildren(image);
    } else if (kind === 'pdf') {
      const frame = document.createElement('iframe');
      frame.title = `Preview of ${file.name}`;
      frame.src = previewUrl;
      content.replaceChildren(frame);
    } else if (kind === 'video') {
      const video = document.createElement('video');
      video.controls = true;
      video.preload = 'metadata';
      video.src = previewUrl;
      content.replaceChildren(video);
    } else if (kind === 'audio') {
      const audio = document.createElement('audio');
      audio.controls = true;
      audio.src = previewUrl;
      content.replaceChildren(audio);
    } else if (kind === 'text') {
      const text = document.createElement('pre');
      text.className = 'file-preview-text';
      text.textContent = 'Loading text preview…';
      content.replaceChildren(text);

      file
        .slice(0, 256 * 1024)
        .text()
        .then((value) => {
          if (version !== renderVersion) return;
          text.textContent = value || 'This file is empty.';
          if (file.size > 256 * 1024) {
            text.textContent += '\n\n[Preview limited to the first 256 KB.]';
          }
        })
        .catch(() => {
          if (version !== renderVersion) return;
          showMessage(
            getBadgeText(file.name),
            'Could not read this file',
            'Try downloading the file to open it in another application.',
          );
        });
    } else {
      showMessage(
        getBadgeText(file.name),
        'Preview is not available for this file type',
        'You can download the file and open it with a compatible application.',
      );
    }

    if (!dialog.open) dialog.showModal();
  }

  function formatFileSize(size) {
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
    return `${(size / (1024 * 1024)).toFixed(1)} MB`;
  }

  function showDemoPreview(button) {
    clearPreviewUrl();
    renderVersion += 1;
    const row = button.closest('tr');
    const cells = row ? [...row.cells] : [];
    const fileName = button.dataset.previewFile || 'Example file';
    const type = cells[1]?.textContent.trim() || 'Unknown type';
    const size = cells[2]?.textContent.trim() || 'Size unavailable';
    const modified = cells[3]?.textContent.trim() || 'Date unavailable';

    title.textContent = fileName;
    details.textContent = `${type} · ${size} · ${modified}`;
    downloadLink.removeAttribute('href');
    downloadLink.removeAttribute('download');
    downloadLink.hidden = true;
    showMessage(
      getBadgeText(fileName),
      'Preview is unavailable for this demo file',
      'This sample listing does not include the underlying file. Select a local file in the upload form to preview its actual contents.',
    );

    if (!dialog.open) dialog.showModal();
  }

  document.querySelectorAll('[data-preview-file]').forEach((button) => {
    button.addEventListener('click', () => showDemoPreview(button));
  });

  document.getElementById('preview')?.addEventListener('click', () => {
    const file = fileInput?.files?.[0];
    if (file) {
      showFilePreview(file);
      return;
    }

    clearPreviewUrl();
    renderVersion += 1;
    title.textContent = 'Preview';
    details.textContent = 'No file selected';
    downloadLink.removeAttribute('href');
    downloadLink.removeAttribute('download');
    downloadLink.hidden = true;
    showMessage(
      'FILE',
      'Select a file to preview',
      'Choose a file in the upload form first. Supported types include images, PDF, video, audio and text.',
    );
    if (!dialog.open) dialog.showModal();
  });

  closeButton.addEventListener('click', () => dialog.close());
  dialog.addEventListener('click', (event) => {
    if (event.target === dialog) dialog.close();
  });
  dialog.addEventListener('close', () => {
    renderVersion += 1;
    clearPreviewUrl();
    downloadLink.removeAttribute('href');
    downloadLink.hidden = true;
    // Removing the element also cancels an in-flight streaming preview.
    content.replaceChildren();
  });

  /** Text ceiling for a stored-file preview, matching the deployed preview. */
  const MAX_REMOTE_TEXT_BYTES = 1048576;

  function previewError(status) {
    if (status === 403) return 'You do not have access to this file.';
    if (status === 404) return 'This file is no longer available.';
    if (status === 503) return 'The file service is unavailable. Please try again later.';
    return 'Unable to preview this file.';
  }

  /**
   * Opens a stored file through the session cookie.
   *
   * A HEAD request reads the content type and size without transferring bytes,
   * and is also what reports a denied or missing file. Images, video, audio and
   * PDFs are then pointed straight at the content URL, so the browser streams
   * them with range requests instead of buffering the whole file in memory.
   * Only text is fetched, and only up to 1 MiB.
   */
  async function openRemote(fileId, name) {
    if (!fileId || !window.mmivAuth) return;
    const version = ++renderVersion;
    const url = '/api/v1/files/' + encodeURIComponent(fileId) + '/content';
    clearPreviewUrl();
    title.textContent = name || 'File preview';
    details.textContent = 'Loading preview…';
    content.replaceChildren();
    downloadLink.href = '/api/v1/files/' + encodeURIComponent(fileId) + '/download';
    downloadLink.hidden = false;
    if (!dialog.open) dialog.showModal();
    try {
      const response = await window.mmivAuth.fetch(url, { method: 'HEAD' });
      if (version !== renderVersion) return;
      if (!response.ok) throw new Error(previewError(response.status));
      const type = response.headers.get('Content-Type') || '';
      const size = Number(response.headers.get('Content-Length'));
      details.textContent =
        (type || 'Unknown file type') + (Number.isFinite(size) ? ' · ' + formatFileSize(size) : '');

      if (type.startsWith('text/')) {
        if (!Number.isFinite(size) || size > MAX_REMOTE_TEXT_BYTES) {
          showMessage(
            getBadgeText(name || ''),
            'Preview is not available for this file',
            'This file is too large to preview. Download it to view the contents.',
          );
          return;
        }
        const text = await window.mmivAuth.fetch(url);
        if (version !== renderVersion) return;
        if (!text.ok) throw new Error(previewError(text.status));
        const pre = document.createElement('pre');
        pre.className = 'file-preview-text';
        pre.textContent = (await text.text()) || 'This file is empty.';
        content.replaceChildren(pre);
        return;
      }

      let media = null;
      if (type.startsWith('image/')) {
        media = document.createElement('img');
        media.alt = name || 'File preview';
      } else if (type.startsWith('video/') || type.startsWith('audio/')) {
        media = document.createElement(type.startsWith('audio/') ? 'audio' : 'video');
        media.controls = true;
        media.preload = 'metadata';
      } else if (type.startsWith('application/pdf')) {
        media = document.createElement('iframe');
        media.title = 'Preview of ' + (name || 'file');
      }
      if (!media) {
        showMessage(
          getBadgeText(name || ''),
          'Preview is not available for this file type',
          'You can download the file and open it with a compatible application.',
        );
        return;
      }
      media.addEventListener(
        'error',
        () => {
          if (version !== renderVersion) return;
          showMessage(
            getBadgeText(name || ''),
            'Could not display this file',
            'Try downloading the file to open it in another application.',
          );
        },
        { once: true },
      );
      media.src = url;
      content.replaceChildren(media);
    } catch (error) {
      if (version === renderVersion)
        showMessage(getBadgeText(name || ''), 'Preview unavailable', error.message);
    }
  }

  // File Manager rows open stored files; the upload dialog keeps its local preview.
  window.mmivPreview = {
    open: openRemote,
    close: () => {
      if (dialog.open) dialog.close();
    },
  };
})();
