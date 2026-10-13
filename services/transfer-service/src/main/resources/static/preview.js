'use strict';
window.mmivPreview = (() => {
  const dialog = document.createElement('dialog');
  dialog.className = 'file-preview';
  const title = document.createElement('h2'),
    close = document.createElement('button'),
    status = document.createElement('p'),
    content = document.createElement('div'),
    download = document.createElement('a');
  title.id = 'previewTitle';
  dialog.setAttribute('aria-labelledby', title.id);
  status.setAttribute('role', 'status');
  close.type = 'button';
  close.textContent = 'Close';
  close.className = 'secondary';
  download.textContent = 'Download';
  download.className = 'button';
  dialog.append(title, close, status, content, download);
  document.body.append(dialog);
  let generation = 0;
  close.addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => {
    generation++;
    content.replaceChildren();
  });
  dialog.addEventListener('click', (event) => {
    if (event.target === dialog) {
      const rect = dialog.getBoundingClientRect();
      if (
        event.clientX < rect.left ||
        event.clientX > rect.right ||
        event.clientY < rect.top ||
        event.clientY > rect.bottom
      )
        dialog.close();
    }
  });
  async function open(id, name) {
    if (!/^[A-Za-z0-9_-]+$/.test(id)) return;
    const current = ++generation,
      url = '/api/v1/files/' + encodeURIComponent(id) + '/content';
    title.textContent = name || 'File preview';
    content.replaceChildren();
    status.textContent = 'Loading preview…';
    download.href = '/api/v1/files/' + encodeURIComponent(id) + '/download';
    if (!dialog.open) dialog.showModal();
    try {
      const response = await mmivAuth.fetch(url, { method: 'HEAD' });
      if (!response.ok)
        throw new Error(
          response.status === 403 ? 'No access to folder' : 'Unable to preview this file.',
        );
      if (current !== generation) return;
      const type = response.headers.get('Content-Type') || '',
        size = Number(response.headers.get('Content-Length'));
      let media;
      if (type.startsWith('image/')) {
        media = document.createElement('img');
        media.alt = name || 'File preview';
      } else if (type.startsWith('video/') || type.startsWith('audio/')) {
        media = document.createElement(type.startsWith('audio/') ? 'audio' : 'video');
        media.controls = true;
        media.preload = 'metadata';
      } else if (type.startsWith('application/pdf')) {
        media = document.createElement('iframe');
        media.title = 'PDF preview';
      } else if (type.startsWith('text/plain') && size <= 1048576) {
        const text = await mmivAuth.fetch(url);
        if (!text.ok) throw new Error('Unable to preview this file.');
        media = document.createElement('pre');
        media.textContent = await text.text();
        if (current !== generation) return;
      } else {
        status.textContent =
          'Preview is unavailable for this format or size. Download the file to view it.';
        return;
      }
      if (media.tagName !== 'PRE') media.src = url;
      content.append(media);
      status.textContent = '';
    } catch (error) {
      if (current === generation) status.textContent = error.message;
    }
  }
  return {
    open,
    close: () => {
      if (dialog.open) dialog.close();
    },
  };
})();
