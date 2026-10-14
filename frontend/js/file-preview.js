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
    content.replaceChildren();
  });
})();
