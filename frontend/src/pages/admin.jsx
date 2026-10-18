import React, { useEffect } from 'react';
import { loadControllers } from '../controllers.js';
export default function Page() {
  useEffect(() => {
    loadControllers([
      '/js/particles.min.js',
      '/js/particles-effect.js',
      '/js/mmiv-auth.js',
      '/js/admin-inline-3.js',
      '/js/admin.js',
    ]);
  }, []);
  return (
    <>
      {'\n    '}
      {'\n    '}
      <div id="particles-js" aria-hidden="true"></div>
      {'\n    '}
      <div className="fx fx-glow" aria-hidden="true"></div>
      {'\n    '}
      <div className="fx fx-beam beam-one" aria-hidden="true"></div>
      {'\n    '}
      <div className="fx fx-beam beam-two" aria-hidden="true"></div>
      {'\n    '}
      <div className="fx fx-beam beam-three" aria-hidden="true"></div>
      {'\n    '}
      <div className="fx fx-bokeh bokeh-one" aria-hidden="true"></div>
      {'\n    '}
      <div className="fx fx-bokeh bokeh-two" aria-hidden="true"></div>
      {'\n    '}
      <div className="fx fx-bokeh bokeh-three" aria-hidden="true"></div>
      {'\n\n    '}
      <div className="home-shell">
        {'\n      '}
        {'\n      '}
        <header className="home-header glass-panel">
          {'\n        '}
          <nav className="home-nav" aria-label="Main navigation">
            {'\n          '}
            <a href="home.html">{'Home'}</a>
            {'\n          '}
            <a href="file-manager.html">{'File Manager'}</a>
            {'\n          '}
            <a href="activity.html">{'Activity'}</a>
            {'\n          '}
            <a href="reports.html">{'Migration Reports'}</a>
            {'\n          '}
            <a href="admin.html" className="active" aria-current="page" data-admin="">
              {' Administration '}
            </a>
            {'\n        '}
          </nav>
          {'\n\n        '}
          <div className="header-right">
            {'\n          '}
            <div className="home-account">
              {'\n            '}
              <div className="account-avatar" aria-hidden="true">
                {'MM'}
              </div>
              {'\n            '}
              <div className="account-copy">
                {'\n              '}
                <strong id="account">{'MMIV account'}</strong>
                {'\n              '}
                <span>{'Administrator'}</span>
                {'\n            '}
              </div>
              {'\n            '}
              <button id="logout" className="sign-out-btn" type="button">
                {'Sign out'}
              </button>
              {'\n          '}
            </div>
            {'\n\n          '}
            <a href="home.html" className="mmiv-header" aria-label="MMIV Home">
              {' MMIV'}
              <span>{'.'}</span>{' '}
            </a>
            {'\n        '}
          </div>
          {'\n      '}
        </header>
        {'\n\n      '}
        {'\n      '}
        <main className="home-main admin-main">
          {'\n        '}
          <p id="status" className="system-status" role="status" aria-live="polite"></p>
          {'\n\n        '}
          <section className="admin-workspace glass-panel" aria-labelledby="admin-title">
            {'\n          '}
            <div className="admin-heading">
              {'\n            '}
              <h1 id="admin-title">{'Administration'}</h1>
              {'\n            '}
              <span className="admin-environment">{'Admin workspace'}</span>
              {'\n          '}
            </div>
            {'\n\n          '}
            {'\n          '}
            <input
              className="admin-tab-input"
              type="radio"
              name="admin-tabs"
              id="tab-folders"
              defaultChecked={true}
            />
            {'\n          '}
            <input className="admin-tab-input" type="radio" name="admin-tabs" id="tab-users" />
            {'\n          '}
            <input
              className="admin-tab-input"
              type="radio"
              name="admin-tabs"
              id="tab-invitations"
            />
            {'\n          '}
            <input className="admin-tab-input" type="radio" name="admin-tabs" id="tab-logs" />
            {'\n          '}
            <input className="admin-tab-input" type="radio" name="admin-tabs" id="tab-reports" />
            {'\n\n          '}
            <div className="admin-tabs" aria-label="Administration sections">
              {'\n            '}
              <label htmlFor="tab-folders" className="admin-tab">
                {'Folders'}
              </label>
              {'\n            '}
              <label htmlFor="tab-users" className="admin-tab">
                {' Users & Permissions '}
              </label>
              {'\n            '}
              <label htmlFor="tab-invitations" className="admin-tab">
                {' Invitations '}
              </label>
              {'\n            '}
              <label htmlFor="tab-logs" className="admin-tab">
                {' System Logs '}
              </label>
              {'\n            '}
              <label htmlFor="tab-reports" className="admin-tab">
                {' Reports '}
              </label>
              {'\n          '}
            </div>
            {'\n\n          '}
            <div className="admin-content">
              {'\n            '}
              {'\n            '}
              <section id="admin-folders" className="admin-section" aria-labelledby="folders-title">
                {'\n              '}
                <div className="admin-section-heading">
                  {'\n                '}
                  <h2 id="folders-title">{'Folders'}</h2>
                  {'\n              '}
                </div>
                {'\n\n              '}
                <div className="admin-folder-toolbar">
                  {'\n                '}
                  <input
                    id="folderSearch"
                    type="search"
                    placeholder="Search folders..."
                    aria-label="Search folders"
                  />
                  {'\n                '}
                  <button
                    id="openCreateFolder"
                    className="admin-primary-button"
                    type="button"
                    onClick={() => {
                      document.getElementById('createFolderDialog').showModal();
                    }}
                  >
                    {'\n                  Create folder\n                '}
                  </button>
                  {'\n              '}
                </div>
                {'\n\n              '}
                <div className="admin-folder-table-scroll">
                  {'\n                '}
                  <table className="admin-table admin-folder-table">
                    {'\n                  '}
                    <thead>
                      {'\n                    '}
                      <tr>
                        {'\n                      '}
                        <th scope="col">{'Folder name'}</th>
                        {'\n                      '}
                        <th scope="col">{'Path'}</th>
                        {'\n                      '}
                        <th scope="col">{'Status'}</th>
                        {'\n                      '}
                        <th scope="col">{'Actions'}</th>
                        {'\n                    '}
                      </tr>
                      {'\n                  '}
                    </thead>
                    {'\n\n                  '}
                    {'\n                  '}
                    <tbody id="foldersTable"></tbody>
                    {'\n                '}
                  </table>
                  {'\n              '}
                </div>
                {'\n\n              '}
                <div className="admin-section-footer admin-folder-footer">
                  {'\n                '}
                  <span className="admin-section-meta" id="foldersMeta">
                    {'Loading…'}
                  </span>
                  {'\n                '}
                  <span>
                    {
                      '\n                  Folders share the application storage bucket. Existing files are preserved when\n                  creating folders.\n                '
                    }
                  </span>
                  {'\n              '}
                </div>
                {'\n            '}
              </section>
              {'\n\n            '}
              {'\n            '}
              <section id="admin-users" className="admin-section" aria-labelledby="users-title">
                {'\n              '}
                <div className="admin-section-heading">
                  {'\n                '}
                  <h2 id="users-title">{'Users & Permissions'}</h2>
                  {'\n              '}
                </div>
                {'\n\n              '}
                <div className="admin-user-toolbar">
                  {'\n                '}
                  <div className="admin-user-search">
                    {'\n                  '}
                    <input
                      id="userSearch"
                      type="search"
                      placeholder="Search users..."
                      aria-label="Search users"
                    />
                    {'\n                '}
                  </div>
                  {'\n                '}
                  <button id="searchUsers" type="button" className="admin-secondary-button">
                    {'\n                  Search\n                '}
                  </button>
                  {'\n              '}
                </div>
                {'\n\n              '}
                <div className="admin-user-table-scroll">
                  {'\n                '}
                  <table className="admin-table admin-user-table">
                    {'\n                  '}
                    <thead>
                      {'\n                    '}
                      <tr>
                        {'\n                      '}
                        <th scope="col">{'Username'}</th>
                        {'\n                      '}
                        <th scope="col">{'Role'}</th>
                        {'\n                      '}
                        <th scope="col">{'Status'}</th>
                        {'\n                      '}
                        <th scope="col">{'Actions'}</th>
                        {'\n                    '}
                      </tr>
                      {'\n                  '}
                    </thead>
                    {'\n\n                  '}
                    {'\n                  '}
                    <tbody id="usersTable"></tbody>
                    {'\n                '}
                  </table>
                  {'\n              '}
                </div>
                {'\n              '}
                <div className="admin-section-footer">
                  {'\n                '}
                  <span className="admin-section-meta" id="usersMeta">
                    {'Loading…'}
                  </span>
                  {'\n              '}
                </div>
                {'\n            '}
              </section>
              {'\n\n            '}
              {'\n            '}
              {'\n\n            '}
              <section
                id="admin-invitations"
                className="admin-section"
                aria-labelledby="invitations-title"
              >
                {'\n              '}
                <div className="admin-section-heading">
                  {'\n                '}
                  <h2 id="invitations-title">{'Invitations'}</h2>
                  {'\n              '}
                </div>
                {'\n\n              '}
                {'\n              '}
                <div className="admin-invitation-toolbar">
                  {'\n                '}
                  <div className="admin-invitation-search-group">
                    {'\n                  '}
                    <input
                      id="invitationSearch"
                      type="search"
                      placeholder="Search invitations..."
                      aria-label="Search invitations"
                    />
                    {'\n                  '}
                    <button
                      id="openInvitationDialog"
                      type="button"
                      className="admin-primary-button"
                      onClick={() => {
                        document.getElementById('invitationDialog').showModal();
                      }}
                    >
                      {'\n                    Create invitation\n                  '}
                    </button>
                    {'\n                '}
                  </div>
                  {'\n\n                '}
                  <select id="invitationStatus" aria-label="Filter invitation status">
                    {'\n                  '}
                    <option value="all">{'All statuses'}</option>
                    {'\n                  '}
                    <option value="Pending">{'Pending'}</option>
                    {'\n                  '}
                    <option value="Accepted">{'Accepted'}</option>
                    {'\n                  '}
                    <option value="Expired">{'Expired'}</option>
                    {'\n                '}
                  </select>
                  {'\n              '}
                </div>
                {'\n\n              '}
                {'\n              '}
                <div className="admin-invitation-table-scroll">
                  {'\n                '}
                  <table className="admin-table admin-invitation-table">
                    {'\n                  '}
                    <thead>
                      {'\n                    '}
                      <tr>
                        {'\n                      '}
                        <th scope="col">{'Account'}</th>
                        {'\n                      '}
                        <th scope="col">{'Created'}</th>
                        {'\n                      '}
                        <th scope="col">{'Expires'}</th>
                        {'\n                      '}
                        <th scope="col">{'Status'}</th>
                        {'\n                      '}
                        <th scope="col">{'Actions'}</th>
                        {'\n                    '}
                      </tr>
                      {'\n                  '}
                    </thead>
                    {'\n\n                  '}
                    {'\n                  '}
                    <tbody id="invitationsTable"></tbody>
                    {'\n                '}
                  </table>
                  {'\n              '}
                </div>
                {'\n\n              '}
                <div className="admin-section-footer admin-invitation-footer">
                  {'\n                '}
                  <span className="admin-section-meta" id="invitationsMeta">
                    {'Unavailable'}
                  </span>
                  {'\n                '}
                  <span>
                    {
                      '\n                  Invitation links are valid for 24 hours and can be redeemed once by the named\n                  account.\n                '
                    }
                  </span>
                  {'\n              '}
                </div>
                {'\n            '}
              </section>
              {'\n            '}
              {'\n\n            '}
              <dialog
                id="invitationDialog"
                className="admin-management-dialog admin-invitation-dialog"
              >
                {'\n              '}
                <div className="admin-management-dialog-inner">
                  {'\n                '}
                  <div className="admin-management-header">
                    {'\n                  '}
                    <h2>{'Create invitation'}</h2>
                    {'\n\n                  '}
                    <button
                      type="button"
                      className="admin-dialog-close"
                      aria-label="Close dialog"
                      onClick={() => {
                        document.getElementById('invitationDialog').close();
                      }}
                    >
                      {'\n                    ×\n                  '}
                    </button>
                    {'\n                '}
                  </div>
                  {'\n\n                '}
                  <div className="admin-management-body">
                    {'\n                  '}
                    <p className="admin-description">
                      {
                        '\n                    Invite a registered account to become an administrator. The invitation expires\n                    after 24 hours and can be redeemed once.\n                  '
                      }
                    </p>
                    {'\n\n                  '}
                    {'\n                  '}
                    <div className="admin-field">
                      {'\n                    '}
                      <label htmlFor="websiteAddress">
                        {' Website address the recipient can open '}
                      </label>
                      {'\n\n                    '}
                      <input id="websiteAddress" type="url" placeholder="https://example.com" />
                      {'\n                  '}
                    </div>
                    {'\n\n                  '}
                    {'\n                  '}
                    <div className="admin-inline-form">
                      {'\n                    '}
                      <div className="admin-field">
                        {'\n                      '}
                        <label htmlFor="invitationUsername">{' Registered username '}</label>
                        {'\n\n                      '}
                        <input id="invitationUsername" type="text" placeholder="Enter username" />
                        {'\n                    '}
                      </div>
                      {'\n\n                    '}
                      <button id="findAccount" type="button" className="admin-secondary-button">
                        {'\n                      Find account\n                    '}
                      </button>
                      {'\n                  '}
                    </div>
                    {'\n\n                  '}
                    {'\n                  '}
                    <div className="admin-field admin-invitation-select">
                      {'\n                    '}
                      <label htmlFor="invitationAccount">
                        {' Select an account for invitation '}
                      </label>
                      {'\n\n                    '}
                      {'\n                    '}
                      <select id="invitationAccount">
                        {'\n                      '}
                        <option value="">{'Select account'}</option>
                        {'\n                    '}
                      </select>
                      {'\n                  '}
                    </div>
                    {'\n\n                  '}
                    <div className="admin-actions">
                      {'\n                    '}
                      <button
                        id="createInvitation"
                        type="button"
                        className="admin-primary-button"
                        disabled={true}
                      >
                        {'\n                      Create invitation\n                    '}
                      </button>
                      {'\n                  '}
                    </div>
                    {'\n\n                  '}
                    <div className="admin-divider"></div>
                    {'\n\n                  '}
                    {'\n                  '}
                    <h3>{'Invitation link'}</h3>
                    {'\n\n                  '}
                    <div className="admin-field">
                      {'\n                    '}
                      <label htmlFor="invitationLink">{' Generated link '}</label>
                      {'\n\n                    '}
                      <input
                        id="invitationLink"
                        type="text"
                        readOnly={true}
                        placeholder="No invitation link generated"
                      />
                      {'\n                  '}
                    </div>
                    {'\n\n                  '}
                    <div className="admin-actions">
                      {'\n                    '}
                      <button
                        id="copyInvitationLink"
                        type="button"
                        className="admin-secondary-button"
                        disabled={true}
                      >
                        {'\n                      Copy invitation link\n                    '}
                      </button>
                      {'\n                  '}
                    </div>
                    {'\n                '}
                  </div>
                  {'\n              '}
                </div>
                {'\n            '}
              </dialog>
              {'\n\n            '}
              {'\n            '}
              <section id="admin-logs" className="admin-section" aria-labelledby="logs-title">
                {'\n              '}
                <div className="admin-section-heading">
                  {'\n                '}
                  <h2 id="logs-title">{'System Logs'}</h2>
                  {'\n              '}
                </div>
                {'\n\n              '}
                <div className="admin-section-scroll">
                  {'\n                '}
                  <div className="admin-log-controls">
                    {'\n                  '}
                    <div className="admin-field">
                      {'\n                    '}
                      <input
                        id="logsSearch"
                        type="search"
                        placeholder="Search events..."
                        aria-label="Search logs"
                      />
                      {'\n                  '}
                    </div>
                    {'\n\n                  '}
                    <div className="admin-field">
                      {'\n                    '}
                      <select id="logsFilter" aria-label="Filter log level">
                        {'\n                      '}
                        <option value="all">{'All levels'}</option>
                        {'\n                      '}
                        <option value="INFO">{'Info'}</option>
                        {'\n                      '}
                        <option value="WARNING">{'Warning'}</option>
                        {'\n                      '}
                        <option value="ERROR">{'Error'}</option>
                        {'\n                    '}
                      </select>
                      {'\n                  '}
                    </div>
                    {'\n\n                  '}
                    {'\n                  '}
                    <div>
                      {'\n                    '}
                      <button id="logsPrevious" type="button" className="admin-secondary-button">
                        {'\n                      Previous\n                    '}
                      </button>
                      {'\n                    '}
                      <button id="logsNext" type="button" className="admin-secondary-button">
                        {'Next'}
                      </button>
                      {'\n                    '}
                      <button id="refreshLogs" type="button" className="admin-secondary-button">
                        {'\n                      Refresh\n                    '}
                      </button>
                      {'\n                  '}
                    </div>
                    {'\n                '}
                  </div>
                  {'\n\n                '}
                  <div className="admin-table-scroll">
                    {'\n                  '}
                    <table className="admin-table">
                      {'\n                    '}
                      <thead>
                        {'\n                      '}
                        <tr>
                          {'\n                        '}
                          <th scope="col">{'Timestamp'}</th>
                          {'\n                        '}
                          <th scope="col">{'Level'}</th>
                          {'\n                        '}
                          <th scope="col">{'Event'}</th>
                          {'\n                        '}
                          <th scope="col">{'User'}</th>
                          {'\n                        '}
                          <th scope="col">{'Details'}</th>
                          {'\n                      '}
                        </tr>
                        {'\n                    '}
                      </thead>
                      {'\n                    '}
                      {'\n                    '}
                      <tbody id="logsTable"></tbody>
                      {'\n                  '}
                    </table>
                    {'\n                '}
                  </div>
                  {'\n              '}
                </div>
                {'\n              '}
                <div className="admin-section-footer">
                  {'\n                '}
                  <span className="admin-section-meta" id="logsMeta">
                    {'Loading…'}
                  </span>
                  {'\n                '}
                  <span id="logsHint">{'Newest first, 100 records per page.'}</span>
                  {'\n              '}
                </div>
                {'\n            '}
              </section>
              {'\n\n            '}
              <section
                id="admin-reports"
                className="admin-section"
                aria-labelledby="admin-reports-title"
              >
                {'\n              '}
                <div className="admin-section-heading">
                  {'\n                '}
                  <h2 id="admin-reports-title">{'Migration Reports'}</h2>
                  {'\n              '}
                </div>
                {'\n              '}
                <div className="admin-section-scroll">
                  {'\n                '}
                  <div className="admin-log-controls">
                    {'\n                  '}
                    <div className="admin-field">
                      {'\n                    '}
                      <label htmlFor="adminReportUser">{'Initiated by'}</label>
                      {'\n                    '}
                      <input id="adminReportUser" type="search" placeholder="Exact user ID" />
                      {'\n                  '}
                    </div>
                    {'\n                  '}
                    <div className="admin-field">
                      {'\n                    '}
                      <label htmlFor="adminReportStatus">{'Status'}</label>
                      {'\n                    '}
                      <select id="adminReportStatus">
                        {'\n                      '}
                        <option value="">{'All statuses'}</option>
                        {'\n                      '}
                        <option value="PROCESSING">{'Processing'}</option>
                        {'\n                      '}
                        <option value="COMPLETED">{'Completed'}</option>
                        {'\n                      '}
                        <option value="PARTIALLY_COMPLETED">{'Partially completed'}</option>
                        {'\n                      '}
                        <option value="FAILED">{'Failed'}</option>
                        {'\n                      '}
                        <option value="INCOMPLETE">{'Incomplete'}</option>
                        {'\n                    '}
                      </select>
                      {'\n                  '}
                    </div>
                    {'\n                  '}
                    <div className="admin-log-actions">
                      {'\n                    '}
                      <button
                        id="adminReportsPrevious"
                        type="button"
                        className="admin-secondary-button"
                      >
                        {'\n                      Previous\n                    '}
                      </button>
                      {'\n                    '}
                      <button
                        id="adminReportsNext"
                        type="button"
                        className="admin-secondary-button"
                      >
                        {'\n                      Next\n                    '}
                      </button>
                      {'\n                    '}
                      <button
                        id="refreshAdminReports"
                        type="button"
                        className="admin-secondary-button"
                      >
                        {'\n                      Refresh\n                    '}
                      </button>
                      {'\n                  '}
                    </div>
                    {'\n                '}
                  </div>
                  {'\n                '}
                  <div className="admin-table-scroll">
                    {'\n                  '}
                    <table className="admin-table">
                      {'\n                    '}
                      <thead>
                        {'\n                      '}
                        <tr>
                          {'\n                        '}
                          <th scope="col">{'Migration ID'}</th>
                          {'\n                        '}
                          <th scope="col">{'Initiated by'}</th>
                          {'\n                        '}
                          <th scope="col">{'Started'}</th>
                          {'\n                        '}
                          <th scope="col">{'Status'}</th>
                          {'\n                        '}
                          <th scope="col">{'Files'}</th>
                          {'\n                        '}
                          <th scope="col">{'Success rate'}</th>
                          {'\n                        '}
                          <th scope="col">{'Details'}</th>
                          {'\n                      '}
                        </tr>
                        {'\n                    '}
                      </thead>
                      {'\n                    '}
                      <tbody id="adminReportsTable"></tbody>
                      {'\n                  '}
                    </table>
                    {'\n                '}
                  </div>
                  {'\n              '}
                </div>
                {'\n              '}
                <div className="admin-section-footer">
                  {'\n                '}
                  <span className="admin-section-meta" id="adminReportsMeta">
                    {'Open this tab to load reports.'}
                  </span>
                  {'\n                '}
                  <span>
                    {'Report ownership and raw events are verified by the reporting service.'}
                  </span>
                  {'\n              '}
                </div>
                {'\n            '}
              </section>
              {'\n          '}
            </div>
            {'\n        '}
          </section>
          {'\n      '}
        </main>
        {'\n\n      '}
        <footer className="home-footer">
          {'\n        '}
          <span>{'MMIV · Media Migration and Integrity Validation'}</span>
          {'\n        '}
          <span>{'Administration'}</span>
          {'\n      '}
        </footer>
        {'\n    '}
      </div>
      {'\n\n    '}
      {'\n    '}
      <dialog id="createFolderDialog" className="admin-dialog">
        {'\n      '}
        <div className="admin-dialog-content">
          {'\n        '}
          <div className="admin-dialog-heading">
            {'\n          '}
            <h2>{'Create folder'}</h2>
            {'\n          '}
            <button
              type="button"
              className="admin-dialog-close"
              aria-label="Close"
              onClick={() => {
                document.getElementById('createFolderDialog').close();
              }}
            >
              {'\n            ×\n          '}
            </button>
            {'\n        '}
          </div>
          {'\n\n        '}
          <div className="admin-field">
            {'\n          '}
            <label htmlFor="folderName">{'Folder name'}</label>
            {'\n          '}
            <input
              id="folderName"
              name="folderName"
              type="text"
              placeholder="Enter folder name"
              required={true}
            />
            {'\n        '}
          </div>
          {'\n\n        '}
          <p className="admin-description">
            {
              '\n          The new folder will share the application storage bucket. Existing files will not be\n          modified.\n        '
            }
          </p>
          {'\n\n        '}
          <div className="admin-dialog-actions">
            {'\n          '}
            <button
              type="button"
              className="admin-secondary-button"
              onClick={() => {
                document.getElementById('createFolderDialog').close();
              }}
            >
              {'\n            Cancel\n          '}
            </button>
            {'\n          '}
            <button
              id="createFolder"
              type="button"
              className="admin-primary-button"
              disabled={true}
            >
              {'\n            Create folder\n          '}
            </button>
            {'\n        '}
          </div>
          {'\n      '}
        </div>
        {'\n    '}
      </dialog>
      {'\n\n    '}
      {'\n    '}
      <dialog id="userManagementDialog" className="admin-management-dialog">
        {'\n      '}
        <div className="admin-management-dialog-inner">
          {'\n        '}
          <div className="admin-management-header">
            {'\n          '}
            <h2>{'Manage user'}</h2>
            {'\n\n          '}
            <button
              type="button"
              className="admin-dialog-close"
              aria-label="Close dialog"
              onClick={() => {
                document.getElementById('userManagementDialog').close();
              }}
            >
              {'\n            ×\n          '}
            </button>
            {'\n        '}
          </div>
          {'\n\n        '}
          <div className="admin-management-body">
            {'\n          '}
            <div className="admin-field">
              {'\n            '}
              <label htmlFor="selectedUser">{'Selected user'}</label>
              {'\n            '}
              {'\n            '}
              <select id="selectedUser">
                {'\n              '}
                <option value="">{'Select user'}</option>
                {'\n            '}
              </select>
              {'\n          '}
            </div>
            {'\n\n          '}
            <div className="admin-divider"></div>
            {'\n\n          '}
            <h3>{'Folder permissions'}</h3>
            {'\n\n          '}
            <div className="admin-field">
              {'\n            '}
              <label htmlFor="permissionFolder">{'Filter folders'}</label>
              {'\n            '}
              {'\n            '}
              <select id="permissionFolder">
                {'\n              '}
                <option value="">{'Select folder'}</option>
                {'\n            '}
              </select>
              {'\n          '}
            </div>
            {'\n\n          '}
            <p className="admin-help">{'Administrators have access to all folders.'}</p>
            {'\n\n          '}
            <fieldset className="admin-permission-options">
              {'\n            '}
              <legend>{'Permission'}</legend>
              {'\n\n            '}
              <label>
                {'\n              '}
                <input type="radio" name="permission" defaultValue="READ" />
                {'\n              View and download\n            '}
              </label>
              {'\n\n            '}
              <label>
                {'\n              '}
                <input type="radio" name="permission" defaultValue="WRITE" />
                {'\n              Upload, view and download\n            '}
              </label>
              {'\n          '}
            </fieldset>
            {'\n\n          '}
            <div className="admin-actions">
              {'\n            '}
              <button
                id="savePermission"
                type="button"
                className="admin-primary-button"
                disabled={true}
              >
                {'\n              Save permission\n            '}
              </button>
              {'\n            '}
              <button
                id="revokePermission"
                type="button"
                className="admin-secondary-button"
                disabled={true}
              >
                {'\n              Revoke permission\n            '}
              </button>
              {'\n          '}
            </div>
            {'\n\n          '}
            <div className="admin-divider"></div>
            {'\n\n          '}
            <h3>{'Account actions'}</h3>
            {'\n          '}
            <p className="admin-help">
              {
                '\n            Other administrators are protected. Password-reset links require an active account.\n          '
              }
            </p>
            {'\n\n          '}
            <div className="admin-actions">
              {'\n            '}
              <button
                id="createResetLink"
                type="button"
                className="admin-secondary-button"
                disabled={true}
              >
                {'\n              Create password-reset link\n            '}
              </button>
              {'\n            '}
              <button
                id="deactivateUser"
                type="button"
                className="admin-danger-button"
                disabled={true}
              >
                {'\n              Deactivate selected user\n            '}
              </button>
              {'\n          '}
            </div>
            {'\n\n          '}
            <div className="admin-divider"></div>
            {'\n\n          '}
            <h3>{'Password-reset link'}</h3>
            {'\n          '}
            <p className="admin-description">
              {
                '\n            Choose a user, then create a reset link. Send it privately. Links expire after one hour\n            and can be used once.\n          '
              }
            </p>
            {'\n\n          '}
            <div className="admin-field">
              {'\n            '}
              <label htmlFor="resetLink">{'Generated link'}</label>
              {'\n            '}
              <input
                id="resetLink"
                type="text"
                readOnly={true}
                placeholder="No password-reset link generated"
              />
              {'\n          '}
            </div>
            {'\n\n          '}
            <button
              id="copyResetLink"
              type="button"
              className="admin-secondary-button"
              disabled={true}
            >
              {'\n            Copy password-reset link\n          '}
            </button>
            {'\n        '}
          </div>
          {'\n      '}
        </div>
        {'\n    '}
      </dialog>
      {'\n\n    '}
      {'\n    '}
      <dialog id="confirmDialog" className="admin-dialog">
        {'\n      '}
        <div className="admin-dialog-content">
          {'\n        '}
          <div className="admin-dialog-heading">
            {'\n          '}
            <h2 id="confirmTitle">{'Confirm'}</h2>
            {'\n        '}
          </div>
          {'\n\n        '}
          <p className="admin-description" id="confirmMessage"></p>
          {'\n\n        '}
          <div className="admin-dialog-actions">
            {'\n          '}
            <button id="confirmCancel" type="button" className="admin-secondary-button">
              {'Cancel'}
            </button>
            {'\n          '}
            <button id="confirmAccept" type="button" className="admin-danger-button">
              {'Confirm'}
            </button>
            {'\n        '}
          </div>
          {'\n      '}
        </div>
        {'\n    '}
      </dialog>
      {'\n\n    '}
      {'\n    \n    \n    \n\n    '}
      {'\n    \n\n    '}
      {'\n    \n  '}
    </>
  );
}
