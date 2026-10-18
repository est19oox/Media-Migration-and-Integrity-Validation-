'use strict';

(() => {
  const REPORTS_URL = '/api/reports/mine';
  const PROFILE_URL = '/api/access/me';
  const PAGE_SIZE = 20;
  const statusFilter = document.getElementById('reportStatusFilter');
  const rows = document.getElementById('reportRows');
  const count = document.getElementById('reportCount');
  const previous = document.getElementById('reportsPrevious');
  const next = document.getElementById('reportsNext');
  const status = document.getElementById('reportsStatus');
  const pagePosition = document.getElementById('reportsPagePosition');
  const account = document.getElementById('account');
  const accountRole = document.getElementById('accountRole');
  const adminLinks = document.querySelectorAll('[data-admin]');
  const safeStatuses = new Set([
    'PROCESSING',
    'COMPLETED',
    'PARTIALLY_COMPLETED',
    'FAILED',
    'INCOMPLETE',
  ]);
  let page = 0;
  let totalPages = 0;

  const renderProfile = (profile) => {
    if (!profile || !['USER', 'ADMIN'].includes(profile.role)) {
      throw new Error('Invalid profile response');
    }
    account.textContent =
      typeof profile.username === 'string' && profile.username ? profile.username : 'MMIV account';
    accountRole.textContent = profile.role === 'ADMIN' ? 'Administrator' : 'Workspace member';
    adminLinks.forEach((link) => {
      link.hidden = profile.role !== 'ADMIN';
    });
  };

  const loadProfile = async () => {
    const response = await window.mmivAuth.fetch(PROFILE_URL);
    if (!response.ok) throw new Error('Profile unavailable');
    renderProfile(await response.json());
  };

  const cell = (value) => {
    const element = document.createElement('td');
    element.textContent = value;
    return element;
  };

  const number = (value) =>
    typeof value === 'number' && Number.isFinite(value) && value >= 0 ? String(value) : '—';

  const date = (value) => {
    if (typeof value !== 'string' || !value) return '—';
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? '—' : parsed.toLocaleString();
  };

  const render = (reports) => {
    const rendered = reports.map((report) => {
      const row = document.createElement('tr');
      const migrationId = typeof report.migrationId === 'string' ? report.migrationId : '';
      const summary = report.summary && typeof report.summary === 'object' ? report.summary : {};
      const normalizedStatus = safeStatuses.has(report.status) ? report.status : 'UNKNOWN';

      row.append(cell(migrationId || '—'));
      row.append(cell(date(report.startedAt)));

      const statusCell = document.createElement('td');
      const badge = document.createElement('span');
      badge.className = `report-status report-status-${normalizedStatus.toLowerCase().replaceAll('_', '-')}`;
      badge.textContent = normalizedStatus.replaceAll('_', ' ');
      statusCell.append(badge);
      row.append(statusCell);

      row.append(cell(number(summary.totalFiles)));
      row.append(cell(number(summary.successfulFiles)));
      row.append(cell(number(summary.failedFiles)));
      row.append(
        cell(
          typeof summary.successRate === 'number' && Number.isFinite(summary.successRate)
            ? `${summary.successRate.toFixed(1)}%`
            : '—',
        ),
      );

      const detailCell = document.createElement('td');
      if (migrationId) {
        const link = document.createElement('a');
        link.className = 'report-detail-link';
        link.href = `/pages/report-detail.html?id=${encodeURIComponent(migrationId)}`;
        link.textContent = 'View details';
        detailCell.append(link);
      } else {
        detailCell.textContent = '—';
      }
      row.append(detailCell);
      return row;
    });
    rows.replaceChildren(...rendered);
  };

  const endpoint = () => {
    let url = `${REPORTS_URL}?page=${page}&size=${PAGE_SIZE}`;
    if (statusFilter.value) url += `&status=${encodeURIComponent(statusFilter.value)}`;
    return url;
  };

  const validPayload = (payload) =>
    payload &&
    Array.isArray(payload.content) &&
    Number.isInteger(payload.number) &&
    payload.number >= 0 &&
    Number.isInteger(payload.totalPages) &&
    payload.totalPages >= 0 &&
    typeof payload.totalElements === 'number' &&
    Number.isFinite(payload.totalElements) &&
    payload.totalElements >= 0;

  const load = async () => {
    rows.replaceChildren();
    status.textContent = 'Loading migration reports…';
    previous.disabled = true;
    next.disabled = true;
    try {
      const response = await window.mmivAuth.fetch(endpoint());
      if (!response.ok) {
        count.textContent = '0 reports';
        status.textContent =
          response.status === 503
            ? 'The reporting service is unavailable. Please try again later.'
            : response.status === 401
              ? 'Your session has expired.'
              : 'Migration reports could not be loaded.';
        return;
      }
      const payload = await response.json();
      if (!validPayload(payload)) throw new Error('Invalid report response');

      page = payload.number;
      totalPages = payload.totalPages;
      pagePosition.textContent = totalPages === 0 ? 'Page 0 of 0' : `Page ${page + 1} of ${totalPages}`;
      render(payload.content);
      count.textContent = `${payload.totalElements} ${payload.totalElements === 1 ? 'report' : 'reports'}`;
      status.textContent = payload.content.length
        ? `Showing page ${page + 1}`
        : 'No migration reports found.';
      previous.disabled = page === 0;
      next.disabled = totalPages === 0 || page + 1 >= totalPages;
    } catch (_) {
      rows.replaceChildren();
      count.textContent = '0 reports';
      status.textContent = 'Migration reports could not be read. Please reload the page.';
    }
  };

  statusFilter.addEventListener('change', () => {
    page = 0;
    load();
  });
  previous.addEventListener('click', () => {
    if (page > 0) {
      page -= 1;
      load();
    }
  });
  next.addEventListener('click', () => {
    if (page + 1 < totalPages) {
      page += 1;
      load();
    }
  });

  window.mmivAuth.ready.then(loadProfile).then(load).catch(() => {
    rows.replaceChildren();
    count.textContent = '0 reports';
    status.textContent = 'Your session could not be verified.';
  });
})();
