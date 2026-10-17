import React, { useEffect } from 'react';
import { loadControllers } from '../controllers.js';
export default function Page() {
  useEffect(() => {
    loadControllers([
      '/js/particles.min.js',
      '/js/particles-effect.js',
      '/js/mmiv-auth.js',
      '/js/file-manager.js',
      '/js/upload-dialog.js',
      '/js/file-preview.js',
    ]);
  }, []);
  return (
    <>
      {'\n    '}
      {'\n    '}
      <div id="particles-js" aria-hidden="true"></div>
      {'\n\n    '}
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
            {'\n\n          '}
            <a href="file-manager.html" className="active" aria-current="page">
              {' File Manager '}
            </a>
            {'\n\n          '}
            <a href="activity.html">{'Activity'}</a>
            {'\n\n          '}
            <a href="reports.html">{'Migration Reports'}</a>
            {'\n\n          '}
            <a href="admin.html" data-admin="" hidden={true}>
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
              {'\n\n            '}
              <div className="account-copy">
                {'\n              '}
                <strong id="account">{'MMIV account'}</strong>
                {'\n              '}
                <span>{'Workspace member'}</span>
                {'\n            '}
              </div>
              {'\n\n            '}
              <button id="logout" className="sign-out-btn" type="button">
                {'Sign out'}
              </button>
              {'\n          '}
            </div>
            {'\n\n          '}
            <a className="mmiv-header" href="home.html" aria-label="MMIV Home">
              {' MMIV'}
              <span>{'.'}</span>{' '}
            </a>
            {'\n        '}
          </div>
          {'\n      '}
        </header>
        {'\n\n      '}
        <main className="home-main explorer-main">
          {'\n        '}
          <p id="status" className="system-status" role="status" aria-live="polite"></p>
          {'\n\n        '}
          {'\n        '}
          <section
            className="explorer glass-panel"
            id="browse-files"
            aria-labelledby="explorer-title"
          >
            {'\n          '}
            {'\n          '}
            <div className="explorer-toolbar">
              {'\n            '}
              <h1 id="explorer-title">{'Files'}</h1>
              {'\n\n            '}
              <div className="toolbar-actions">
                {'\n              '}
                {'\n              '}
                <button
                  id="refreshFiles"
                  className="refresh-button"
                  type="button"
                  aria-label="Refresh files"
                  title="Refresh files"
                >
                  {'\n                '}
                  <svg
                    width="17"
                    height="17"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.6"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    aria-hidden="true"
                  >
                    {'\n                  '}
                    <path d="M20 7v5h-5"></path>
                    {'\n                  '}
                    <path d="M4 17v-5h5"></path>
                    {'\n                  '}
                    <path d="M5.5 9A7 7 0 0 1 18 7l2 5"></path>
                    {'\n                  '}
                    <path d="M18.5 15A7 7 0 0 1 6 17l-2-5"></path>
                    {'\n                '}
                  </svg>
                  {'\n              '}
                </button>
                {'\n\n              '}
                {'\n              '}
                <details className="upload-menu">
                  {'\n                '}
                  <summary className="primary-button">
                    {'\n                  '}
                    <span>{'Upload'}</span>
                    {'\n                  '}
                    <svg
                      className="upload-chevron"
                      width="14"
                      height="14"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      aria-hidden="true"
                    >
                      {'\n                    '}
                      <path d="m6 9 6 6 6-6"></path>
                      {'\n                  '}
                    </svg>
                    {'\n                '}
                  </summary>
                  {'\n\n                '}
                  <div className="upload-dropdown">
                    {'\n                  '}
                    <button type="button" data-upload-dialog="uploadFileDialog">
                      {'Upload file'}
                    </button>
                    {'\n\n                  '}
                    <button type="button" data-upload-dialog="uploadFolderDialog">
                      {'\n                    Upload folder\n                  '}
                    </button>
                    {'\n                '}
                  </div>
                  {'\n              '}
                </details>
                {'\n            '}
              </div>
              {'\n          '}
            </div>
            {'\n\n          '}
            {'\n          '}
            {'\n          '}
            <div className="explorer-controls">
              {'\n            '}
              {'\n            '}
              <div className="location-field">
                {'\n              '}
                <select id="folder" name="folder" aria-label="Folder">
                  {'\n                '}
                  <option value="">{'Select a folder'}</option>
                  {'\n              '}
                </select>
                {'\n            '}
              </div>
              {'\n\n            '}
              {'\n            '}
              <nav id="breadcrumbs" className="breadcrumbs" aria-label="Folder path">
                {'\n              '}
                <span>{'Workspace'}</span>
                {'\n            '}
              </nav>
              {'\n\n            '}
              {'\n            '}
              <div className="search-field">
                {'\n              '}
                <svg
                  width="17"
                  height="17"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.6"
                  strokeLinecap="round"
                  aria-hidden="true"
                >
                  {'\n                '}
                  <circle cx="11" cy="11" r="7"></circle>
                  {'\n                '}
                  <path d="m20 20-4-4"></path>
                  {'\n              '}
                </svg>
                {'\n\n              '}
                <input
                  id="fileSearch"
                  type="search"
                  placeholder="Search files..."
                  aria-label="Search files"
                />
                {'\n            '}
              </div>
              {'\n          '}
            </div>
            {'\n\n          '}
            <p id="browseStatus" className="explorer-status" role="status" aria-live="polite"></p>
            {'\n\n          '}
            {'\n          '}
            <div className="file-table-wrap">
              {'\n            '}
              <table className="explorer-table">
                {'\n              '}
                <caption className="visually-hidden">
                  {'\n                Files and folders in the selected location\n              '}
                </caption>
                {'\n\n              '}
                <thead>
                  {'\n                '}
                  <tr>
                    {'\n                  '}
                    <th scope="col">{'Name'}</th>
                    {'\n                  '}
                    <th scope="col">{'Type'}</th>
                    {'\n                  '}
                    <th scope="col">{'Size'}</th>
                    {'\n                  '}
                    <th scope="col">{'Modified'}</th>
                    {'\n                  '}
                    <th scope="col">{'Actions'}</th>
                    {'\n                '}
                  </tr>
                  {'\n              '}
                </thead>
                {'\n\n              '}
                <tbody id="files"></tbody>
                {'\n            '}
              </table>
              {'\n\n            '}
              {'\n            '}
              <div id="loadSentinel" aria-hidden="true"></div>
              {'\n          '}
            </div>
            {'\n\n          '}
            <p id="emptyFolder" className="empty-folder" hidden={true}>
              {'This folder is empty.'}
            </p>
            {'\n\n          '}
            {'\n          '}
            <div className="explorer-bottom">
              {'\n            '}
              <span id="fileCount" className="explorer-count">
                {'0 items'}
              </span>
              {'\n            '}
              <span id="listStatus"></span>
              {'\n          '}
            </div>
            {'\n        '}
          </section>
          {'\n\n        '}
          <dialog
            id="uploadFileDialog"
            className="upload-dialog"
            aria-labelledby="upload-file-title"
          >
            {'\n          '}
            <section className="upload-panel">
              {'\n            '}
              <div className="panel-heading">
                {'\n              '}
                <h2 id="upload-file-title">{'Upload file'}</h2>
                {'\n              '}
                <button
                  type="button"
                  className="close-panel"
                  data-close-upload-dialog=""
                  aria-label="Close upload file dialog"
                >
                  {'\n                ×\n              '}
                </button>
                {'\n            '}
              </div>
              {'\n\n            '}
              <form id="uploadForm">
                {'\n              '}
                <div className="form-field">
                  {'\n                '}
                  <label htmlFor="destination">{'Destination folder'}</label>
                  {'\n                '}
                  <select id="destination" name="destination" required={true}>
                    {'\n                  '}
                    <option value="">{'Select destination'}</option>
                    {'\n                '}
                  </select>
                  {'\n              '}
                </div>
                {'\n\n              '}
                <div className="form-field">
                  {'\n                '}
                  <label htmlFor="file">{'Select file'}</label>
                  {'\n                '}
                  <div className="file-picker">
                    {'\n                  '}
                    <label className="file-picker-button" htmlFor="file">
                      {'Choose file'}
                    </label>
                    {'\n                  '}
                    <span
                      id="fileSelectionStatus"
                      className="file-picker-status"
                      aria-live="polite"
                    >
                      {'No file selected'}
                    </span>
                    {'\n                  '}
                    <input
                      id="file"
                      className="file-picker-input"
                      name="file"
                      type="file"
                      accept=".png,.jpg,.jpeg,.mp4,.mp3,.txt,.pdf,.ppt,.pptx,.doc,.docx"
                    />
                    {'\n                '}
                  </div>
                  {'\n              '}
                </div>
                {'\n\n              '}
                <button id="submit" type="submit" className="primary-button">
                  {'Upload file'}
                </button>
                {'\n            '}
              </form>
              {'\n\n            '}
              <details className="upload-feedback">
                {'\n              '}
                <summary>{'Progress'}</summary>
                {'\n              '}
                <div className="upload-progress-content">
                  {'\n                '}
                  <progress id="uploadProgress" max="100" defaultValue="0"></progress>
                  {'\n                '}
                  <p id="uploadProgressLabel" role="status">
                    {'No upload in progress.'}
                  </p>
                  {'\n                '}
                  <pre id="result"></pre>
                  {'\n\n                '}
                  <div id="links" hidden={true}>
                    {'\n                  '}
                    <button id="preview" type="button">
                      {'Preview'}
                    </button>
                    {'\n                  '}
                    <a id="download">{'Download'}</a>
                    {'\n                '}
                  </div>
                  {'\n              '}
                </div>
                {'\n            '}
              </details>
              {'\n          '}
            </section>
            {'\n        '}
          </dialog>
          {'\n\n        '}
          <dialog
            id="uploadFolderDialog"
            className="upload-dialog"
            aria-labelledby="upload-folder-title"
          >
            {'\n          '}
            <section className="upload-panel">
              {'\n            '}
              <div className="panel-heading">
                {'\n              '}
                <h2 id="upload-folder-title">{'Upload folder'}</h2>
                {'\n              '}
                <button
                  type="button"
                  className="close-panel"
                  data-close-upload-dialog=""
                  aria-label="Close upload folder dialog"
                >
                  {'\n                ×\n              '}
                </button>
                {'\n            '}
              </div>
              {'\n\n            '}
              <form id="folderForm">
                {'\n              '}
                <div className="form-field">
                  {'\n                '}
                  <label htmlFor="folderDestination">{'Destination folder'}</label>
                  {'\n                '}
                  <select id="folderDestination" name="folderDestination" required={true}>
                    {'\n                  '}
                    <option value="">{'Select destination'}</option>
                    {'\n                '}
                  </select>
                  {'\n              '}
                </div>
                {'\n\n              '}
                <div className="form-field">
                  {'\n                '}
                  <label htmlFor="folderPicker">{'Select folder'}</label>
                  {'\n                '}
                  <div className="file-picker">
                    {'\n                  '}
                    <label className="file-picker-button" htmlFor="folderPicker">
                      {'Choose folder'}
                    </label>
                    {'\n                  '}
                    <span
                      id="folderSelectionStatus"
                      className="file-picker-status"
                      aria-live="polite"
                    >
                      {'No folder selected'}
                    </span>
                    {'\n                  '}
                    <input
                      id="folderPicker"
                      className="file-picker-input"
                      name="folder"
                      type="file"
                      webkitdirectory=""
                      multiple={true}
                    />
                    {'\n                '}
                  </div>
                  {'\n              '}
                </div>
                {'\n\n              '}
                <button id="folderSubmit" type="submit" className="primary-button">
                  {'Upload folder'}
                </button>
                {'\n            '}
              </form>
              {'\n\n            '}
              <details id="folderProgress" className="upload-feedback">
                {'\n              '}
                <summary>{'Progress'}</summary>
                {'\n              '}
                <div className="upload-progress-content">
                  {'\n                '}
                  <progress id="folderProgressBar" max="100" defaultValue="0"></progress>
                  {'\n                '}
                  <p id="folderProgressLabel" role="status">
                    {'No upload in progress.'}
                  </p>
                  {'\n                '}
                  <pre id="folderResult"></pre>
                  {'\n\n                '}
                  <div className="file-table-wrap">
                    {'\n                  '}
                    <table className="explorer-table">
                      {'\n                    '}
                      <caption>
                        {'\n                      Per-file upload results\n                    '}
                      </caption>
                      {'\n                    '}
                      <thead>
                        {'\n                      '}
                        <tr>
                          {'\n                        '}
                          <th>{'Relative path'}</th>
                          {'\n                        '}
                          <th>{'Status'}</th>
                          {'\n                        '}
                          <th>{'Integrity'}</th>
                          {'\n                        '}
                          <th>{'Details'}</th>
                          {'\n                      '}
                        </tr>
                        {'\n                    '}
                      </thead>
                      {'\n                    '}
                      <tbody id="folderFileResults"></tbody>
                      {'\n                  '}
                    </table>
                    {'\n                '}
                  </div>
                  {'\n              '}
                </div>
                {'\n            '}
              </details>
              {'\n          '}
            </section>
            {'\n        '}
          </dialog>
          {'\n      '}
        </main>
        {'\n\n      '}
        <dialog
          id="filePreviewDialog"
          className="file-preview-dialog"
          aria-labelledby="filePreviewTitle"
        >
          {'\n        '}
          <section className="file-preview-window">
            {'\n          '}
            <header className="file-preview-header">
              {'\n            '}
              <div className="file-preview-heading">
                {'\n              '}
                <p className="file-preview-eyebrow">{'File preview'}</p>
                {'\n              '}
                <h2 id="filePreviewTitle">{'Preview'}</h2>
                {'\n            '}
              </div>
              {'\n            '}
              <button
                id="closeFilePreview"
                className="file-preview-close"
                type="button"
                aria-label="Close preview"
              >
                {'\n              '}
                <span aria-hidden="true">{'×'}</span>
                {'\n            '}
              </button>
              {'\n          '}
            </header>
            {'\n\n          '}
            <div
              id="filePreviewContent"
              className="file-preview-content"
              role="region"
              aria-label="File preview content"
            ></div>
            {'\n\n          '}
            <footer className="file-preview-footer">
              {'\n            '}
              <p id="filePreviewDetails" className="file-preview-details"></p>
              {'\n            '}
              <a id="downloadPreviewFile" className="secondary-button" hidden={true}>
                {'Download'}
              </a>
              {'\n          '}
            </footer>
            {'\n        '}
          </section>
          {'\n      '}
        </dialog>
        {'\n\n      '}
        <footer className="home-footer">
          {'\n        '}
          <span>{' MMIV · Media Migration and Integrity Validation '}</span>
          {'\n        '}
          <span>{'File Manager'}</span>
          {'\n      '}
        </footer>
        {'\n    '}
      </div>
      {'\n\n    \n    \n    \n    \n    \n    \n  '}
    </>
  );
}
