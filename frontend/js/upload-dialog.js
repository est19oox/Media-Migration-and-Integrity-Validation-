(() => {
  const filePickers = [
    { inputId: 'file', statusId: 'fileSelectionStatus', emptyLabel: 'No file selected' },
    {
      inputId: 'folderPicker',
      statusId: 'folderSelectionStatus',
      emptyLabel: 'No folder selected',
    },
  ];

  filePickers.forEach(({ inputId, statusId, emptyLabel }) => {
    const input = document.getElementById(inputId);
    const status = document.getElementById(statusId);

    if (!(input instanceof HTMLInputElement) || !status) {
      throw new Error(`File picker "${inputId}" is missing required elements.`);
    }

    input.addEventListener('change', () => {
      const files = Array.from(input.files ?? []);
      if (files.length === 0) {
        status.textContent = emptyLabel;
        return;
      }

      const totalSize = files.reduce((size, file) => size + file.size, 0);
      status.textContent = `${files.length} ${files.length === 1 ? 'file' : 'files'} selected · ${formatFileSize(totalSize)}`;
    });
  });

  document.querySelectorAll('[data-upload-dialog]').forEach((trigger) => {
    const dialogId = trigger.dataset.uploadDialog;
    const dialog = dialogId ? document.getElementById(dialogId) : null;

    if (!(dialog instanceof HTMLDialogElement)) {
      throw new Error(`Upload dialog "${dialogId}" is missing.`);
    }

    trigger.addEventListener('click', () => {
      trigger.closest('.upload-menu')?.removeAttribute('open');
      if (!dialog.open) dialog.showModal();
    });
  });

  document.querySelectorAll('.upload-dialog').forEach((dialog) => {
    dialog.querySelector('[data-close-upload-dialog]')?.addEventListener('click', () => {
      dialog.close();
    });

    dialog.addEventListener('click', (event) => {
      if (event.target === dialog) dialog.close();
    });
  });

  function formatFileSize(size) {
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
    if (size < 1024 * 1024 * 1024) return `${(size / (1024 * 1024)).toFixed(1)} MB`;
    return `${(size / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  }
})();
