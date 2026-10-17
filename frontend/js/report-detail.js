'use strict';

(() => {
  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  const TERMINAL = new Set(['COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'INCOMPLETE']);
  const POLL_MS = 10_000;
  const FILE_SIZE = 20;
  const EVENT_SIZE = 50;
  const SAFE_EVENT_STATUSES = new Set([
    'STARTED',
    'ACCEPTED',
    'REJECTED',
    'ERROR',
    'COMPLETED',
    'FAILED',
    'STORED',
    'PASSED',
  ]);
  const id = new URLSearchParams(location.search).get('id') || '';
  const auth = window.mmivAuth;
  const element = (name) => document.getElementById(name);
  const statusMessage = element('reportDetailStatus');
  const fileRows = element('reportFileRows');
  const timeline = element('reportTimeline');
  const adminSection = element('adminReportEvents');
  const adminRows = element('adminEventRows');
  const fileScopeControl = element('reportFileScopeControl');
  const fileScope = element('reportFileScope');
  let profile = null;
  let currentStatus = null;
  let filePage = 0;
  let filePages = 0;
  let failedOnly = true;
  let timer = null;
  let rendered = false;

  adminSection.hidden = true;
  fileScopeControl.hidden = true;
  fileScope.value = 'failed';

  const text = (value, fallback = '—') =>
    value === null || value === undefined || value === '' ? fallback : String(value);

  const count = (value) =>
    typeof value === 'number' && Number.isFinite(value) && value >= 0 ? String(value) : '—';

  const date = (value) => {
    if (typeof value !== 'string' || !value) return '—';
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? '—' : parsed.toLocaleString();
  };

  const formatSource = (source) => {
    if (typeof source === 'string') return text(source);
    if (!source || typeof source !== 'object') return '—';
    const type = typeof source.type === 'string' ? source.type : '';
    const label = type
      ? type.charAt(0).toUpperCase() + type.slice(1).toLowerCase().replaceAll('_', ' ')
      : 'Migration source';
    const parts = [label];
    if (typeof source.rootId === 'string' && source.rootId) parts.push(`Root: ${source.rootId}`);
    if (typeof source.manifestName === 'string' && source.manifestName) {
      parts.push(`Manifest: ${source.manifestName}`);
    }
    return parts.join(' · ');
  };

  const cell = (value) => {
    const result = document.createElement('td');
    result.textContent = text(value);
    return result;
  };

  const failure = (value) => {
    if (!value || typeof value !== 'object') return '—';
    return text(value.message || value.reason || value.code);
  };

  const pagePayload = (payload) =>
    payload &&
    Array.isArray(payload.content) &&
    Number.isInteger(payload.number) &&
    payload.number >= 0 &&
    Number.isInteger(payload.totalPages) &&
    payload.totalPages >= 0 &&
    typeof payload.totalElements === 'number' &&
    Number.isFinite(payload.totalElements) &&
    payload.totalElements >= 0;

  async function read(url) {
    const response = await auth.fetch(url);
    if (!response.ok)
      throw new Error(
        response.status === 503 ? 'Reporting is unavailable.' : 'Report refresh failed.',
      );
    return response.json();
  }

  function renderProfile(value) {
    const account = element('account');
    if (account) account.textContent = value.username;
    const role = document.querySelector ? document.querySelector('.account-copy span') : null;
    if (role) role.textContent = value.role === 'ADMIN' ? 'Administrator' : 'Workspace member';
    if (document.querySelectorAll) {
      document.querySelectorAll('[data-admin]').forEach((link) => {
        link.hidden = value.role !== 'ADMIN';
      });
    }
    fileScopeControl.hidden = value.role !== 'ADMIN';
  }

  function renderSummary(report) {
    const summary = report.summary && typeof report.summary === 'object' ? report.summary : {};
    currentStatus = text(report.status, 'UNKNOWN');
    element('reportMigrationId').textContent = text(report.migrationId);
    element('reportInitiatedBy').textContent = text(report.initiatedBy);
    element('reportSource').textContent = formatSource(report.source);
    element('reportStartedAt').textContent = date(report.startedAt);
    element('reportCompletedAt').textContent = date(report.completedAt);
    const badge = element('reportOverallStatus');
    badge.textContent = currentStatus.replaceAll('_', ' ');
    badge.className = `report-status report-status-${currentStatus.toLowerCase().replaceAll('_', '-')}`;
    element('summaryTotal').textContent = count(summary.totalFiles);
    element('summarySuccessful').textContent = count(summary.successfulFiles);
    element('summaryFailed').textContent = count(summary.failedFiles);
    element('summarySuccessRate').textContent =
      typeof summary.successRate === 'number' && Number.isFinite(summary.successRate)
        ? `${summary.successRate.toFixed(1)}%`
        : '—';
    for (const key of [
      'discoveryAccepted',
      'discoveryFailed',
      'transferStored',
      'transferFailed',
      'integrityPassed',
      'integrityFailed',
    ]) {
      element(key).textContent = count(summary[key]);
    }
  }

  function renderFiles(payload) {
    if (!pagePayload(payload)) throw new Error('Invalid files response.');
    const result = payload.content.map((file) => {
      const row = document.createElement('tr');
      row.append(
        cell(file.fileName || file.fileId),
        cell(file.relativePath),
        cell(file.discoveryStatus),
        cell(file.transferStatus),
        cell(file.integrityStatus),
        cell(file.finalStatus),
        cell(failure(file.failure)),
      );
      return row;
    });
    fileRows.replaceChildren(...result);
    filePage = payload.number;
    filePages = payload.totalPages;
    element('reportFileCount').textContent =
      `${payload.totalElements} ${payload.totalElements === 1 ? 'file' : 'files'}`;
    element('reportFilePage').textContent = `Page ${filePage + 1}`;
    element('reportFilesPrevious').disabled = filePage === 0;
    element('reportFilesNext').disabled = filePages === 0 || filePage + 1 >= filePages;
  }

  function renderTimeline(payload) {
    if (!pagePayload(payload)) throw new Error('Invalid timeline response.');
    const items = payload.content.map((entry) => {
      const item = document.createElement('li');
      const heading = document.createElement('strong');
      const detail = document.createElement('span');
      const occurred = document.createElement('time');
      heading.textContent = `${text(entry.stage, 'MIGRATION')} · ${text(entry.status, 'UNKNOWN')}`;
      detail.textContent = `${text(entry.message)}${entry.fileId ? ` · ${entry.fileId}` : ''}`;
      occurred.textContent = date(entry.occurredAt);
      item.append(heading, detail, occurred);
      return item;
    });
    timeline.replaceChildren(...items);
  }

  function timelineFromAdminEvents(payload) {
    const presentations = {
      MIGRATION_STARTED: ['MIGRATION', 'Migration started'],
      FILE_DISCOVERY_COMPLETED: ['DISCOVERY', 'File discovery completed'],
      MIGRATION_DISCOVERY_COMPLETED: ['DISCOVERY', 'Discovery stage completed'],
      FILE_TRANSFER_COMPLETED: ['TRANSFER', 'File transfer completed'],
      INTEGRITY_VALIDATION_COMPLETED: ['INTEGRITY', 'File integrity validation completed'],
    };
    return {
      ...payload,
      content: payload.content.map((event) => {
        const presentation = presentations[event.eventType] || [
          'MIGRATION',
          'Migration status updated',
        ];
        const rawStatus = event.data && event.data.status;
        return {
          stage: presentation[0],
          status: SAFE_EVENT_STATUSES.has(rawStatus)
            ? rawStatus
            : event.eventType === 'MIGRATION_STARTED'
              ? 'STARTED'
              : 'UNKNOWN',
          fileId: event.fileId,
          occurredAt: event.occurredAt,
          message: presentation[1],
        };
      }),
    };
  }

  function renderAdminEvents(payload) {
    if (!pagePayload(payload)) throw new Error('Invalid administrator events response.');
    adminSection.hidden = false;
    const rows = payload.content.map((event) => {
      const row = document.createElement('tr');
      row.append(
        cell(date(event.occurredAt)),
        cell(event.eventType),
        cell(event.sourceService),
        cell(event.fileId),
        cell(
          event.data && typeof event.data === 'object' ? JSON.stringify(event.data) : event.data,
        ),
      );
      return row;
    });
    adminRows.replaceChildren(...rows);
  }

  function cancelPoll() {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  }

  function schedulePoll() {
    cancelPoll();
    if (currentStatus === 'PROCESSING' && document.visibilityState !== 'hidden') {
      timer = setTimeout(() => refresh(true), POLL_MS);
    }
  }

  function filesUrl(root, requestedPage) {
    return `${root}/${id}/files?page=${requestedPage}&size=${FILE_SIZE}&failedOnly=${failedOnly}`;
  }

  async function refresh(isPolling = false) {
    cancelPoll();
    try {
      const reportRoot = profile.role === 'ADMIN' ? '/api/admin/reports' : '/api/reports';
      const tasks = [read(`${reportRoot}/${id}`), read(filesUrl(reportRoot, filePage))];
      tasks.push(
        profile.role === 'ADMIN'
          ? read(`/api/admin/reports/${id}/events?page=0&size=${EVENT_SIZE}`)
          : read(`/api/reports/${id}/timeline?page=0&size=${EVENT_SIZE}`),
      );
      const [report, files, events] = await Promise.all(tasks);
      renderSummary(report);
      renderFiles(files);
      if (profile.role === 'ADMIN') {
        renderTimeline(timelineFromAdminEvents(events));
        renderAdminEvents(events);
      } else {
        renderTimeline(events);
        adminSection.hidden = true;
        adminRows.replaceChildren();
      }
      rendered = true;
      statusMessage.textContent =
        currentStatus === 'PROCESSING' ? 'Migration is still processing.' : '';
    } catch (error) {
      statusMessage.textContent =
        rendered || isPolling
          ? 'The latest report refresh failed. Previously loaded data is still shown.'
          : error.message;
    } finally {
      schedulePoll();
    }
  }

  async function loadFilePage(nextPage) {
    try {
      const root = profile.role === 'ADMIN' ? '/api/admin/reports' : '/api/reports';
      const payload = await read(filesUrl(root, nextPage));
      renderFiles(payload);
      statusMessage.textContent = '';
    } catch (_) {
      statusMessage.textContent =
        'The requested file page is unavailable. Previously loaded data is still shown.';
    }
  }

  element('reportFilesPrevious').addEventListener('click', () => {
    if (filePage > 0) loadFilePage(filePage - 1);
  });
  element('reportFilesNext').addEventListener('click', () => {
    if (filePage + 1 < filePages) loadFilePage(filePage + 1);
  });
  fileScope.addEventListener('change', () => {
    if (!profile || profile.role !== 'ADMIN') return;
    failedOnly = fileScope.value !== 'all';
    filePage = 0;
    loadFilePage(0);
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      cancelPoll();
    } else if (currentStatus === 'PROCESSING') {
      refresh(true);
    }
  });

  if (!UUID.test(id)) {
    statusMessage.textContent = 'This report link is invalid.';
    return;
  }
  if (!auth) {
    statusMessage.textContent = 'Unable to verify your login. Please reload the page.';
    return;
  }

  auth.ready
    .then(async () => {
      const response = await auth.fetch('/api/access/me');
      if (!response.ok) throw new Error('Your account could not be verified.');
      profile = await response.json();
      if (!profile || !['USER', 'ADMIN'].includes(profile.role))
        throw new Error('Your account could not be verified.');
      renderProfile(profile);
      await refresh();
    })
    .catch((error) => {
      statusMessage.textContent = error.message || 'This report could not be loaded.';
    });
})();
