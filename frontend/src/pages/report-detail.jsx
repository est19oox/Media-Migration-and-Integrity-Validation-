import React, { useEffect } from 'react';
import { loadControllers } from '../controllers.js';
export default function Page() {
  useEffect(() => {
    loadControllers([
      '/js/particles.min.js',
      '/js/particles-effect.js',
      '/js/mmiv-auth.js',
      '/js/report-detail.js',
    ]);
  }, []);
  return (
    <>
      {'\n    '}
      <div id="particles-js" aria-hidden="true"></div>
      {'\n    '}
      <div className="fx fx-glow" aria-hidden="true"></div>
      {'\n\n    '}
      <div className="home-shell">
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
            <a href="reports.html" className="active">
              {'Migration Reports'}
            </a>
            {'\n          '}
            <a href="admin.html" data-admin="" hidden={true}>
              {'Administration'}
            </a>
            {'\n        '}
          </nav>
          {'\n        '}
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
                <span>{'Workspace member'}</span>
                {'\n            '}
              </div>
              {'\n            '}
              <button id="logout" className="sign-out-btn" type="button">
                {'Sign out'}
              </button>
              {'\n          '}
            </div>
            {'\n          '}
            <a href="home.html" className="mmiv-header" aria-label="MMIV Home">
              {'MMIV'}
              <span>{'.'}</span>
            </a>
            {'\n        '}
          </div>
          {'\n      '}
        </header>
        {'\n\n      '}
        <main className="home-main report-detail-main">
          {'\n        '}
          <p
            id="reportDetailStatus"
            className="report-detail-message"
            role="status"
            aria-live="polite"
          >
            {'\n          Loading migration report…\n        '}
          </p>
          {'\n\n        '}
          <section className="report-detail-card glass-panel" aria-labelledby="report-detail-title">
            {'\n          '}
            <div className="reports-heading">
              {'\n            '}
              <div>
                {'\n              '}
                <a className="report-back-link" href="reports.html">
                  {'← All migration reports'}
                </a>
                {'\n              '}
                <h1 id="report-detail-title">{'Migration Report'}</h1>
                {'\n              '}
                <p id="reportMigrationId">{'—'}</p>
                {'\n            '}
              </div>
              {'\n            '}
              <span id="reportOverallStatus" className="report-status">
                {'—'}
              </span>
              {'\n          '}
            </div>
            {'\n\n          '}
            <dl className="report-metadata">
              {'\n            '}
              <div>
                <dt>{'Initiated by'}</dt>
                <dd id="reportInitiatedBy">{'—'}</dd>
              </div>
              {'\n            '}
              <div>
                <dt>{'Source'}</dt>
                <dd id="reportSource">{'—'}</dd>
              </div>
              {'\n            '}
              <div>
                <dt>{'Started'}</dt>
                <dd id="reportStartedAt">{'—'}</dd>
              </div>
              {'\n            '}
              <div>
                <dt>{'Completed'}</dt>
                <dd id="reportCompletedAt">{'—'}</dd>
              </div>
              {'\n          '}
            </dl>
            {'\n\n          '}
            <div className="report-summary-grid" aria-label="Migration summary">
              {'\n            '}
              <div>
                <span>{'Total files'}</span>
                <strong id="summaryTotal">{'—'}</strong>
              </div>
              {'\n            '}
              <div>
                <span>{'Successful'}</span>
                <strong id="summarySuccessful">{'—'}</strong>
              </div>
              {'\n            '}
              <div>
                <span>{'Failed'}</span>
                <strong id="summaryFailed">{'—'}</strong>
              </div>
              {'\n            '}
              <div>
                <span>{'Success rate'}</span>
                <strong id="summarySuccessRate">{'—'}</strong>
              </div>
              {'\n          '}
            </div>
            {'\n\n          '}
            <div className="report-stage-grid" aria-label="Stage counters">
              {'\n            '}
              <div>
                <span>{'Discovery accepted'}</span>
                <strong id="discoveryAccepted">{'—'}</strong>
              </div>
              {'\n            '}
              <div>
                <span>{'Discovery failed'}</span>
                <strong id="discoveryFailed">{'—'}</strong>
              </div>
              {'\n            '}
              <div>
                <span>{'Transfer stored'}</span>
                <strong id="transferStored">{'—'}</strong>
              </div>
              {'\n            '}
              <div>
                <span>{'Transfer failed'}</span>
                <strong id="transferFailed">{'—'}</strong>
              </div>
              {'\n            '}
              <div>
                <span>{'Integrity passed'}</span>
                <strong id="integrityPassed">{'—'}</strong>
              </div>
              {'\n            '}
              <div>
                <span>{'Integrity failed'}</span>
                <strong id="integrityFailed">{'—'}</strong>
              </div>
              {'\n          '}
            </div>
            {'\n        '}
          </section>
          {'\n\n        '}
          <section className="report-detail-card glass-panel" aria-labelledby="report-files-title">
            {'\n          '}
            <div className="report-section-heading">
              {'\n            '}
              <h2 id="report-files-title">{'Files'}</h2>
              {'\n            '}
              <span id="reportFileCount">{'0 files'}</span>
              {'\n          '}
            </div>
            {'\n          '}
            <div className="reports-table-scroll" tabIndex="0" aria-label="Scrollable file results">
              {'\n            '}
              <table className="reports-table report-files-table">
                {'\n              '}
                <thead>
                  {'\n                '}
                  <tr>
                    {'\n                  '}
                    <th scope="col">{'File'}</th>
                    {'\n                  '}
                    <th scope="col">{'Relative path'}</th>
                    {'\n                  '}
                    <th scope="col">{'Discovery'}</th>
                    {'\n                  '}
                    <th scope="col">{'Transfer'}</th>
                    {'\n                  '}
                    <th scope="col">{'Integrity'}</th>
                    {'\n                  '}
                    <th scope="col">{'Final status'}</th>
                    {'\n                  '}
                    <th scope="col">{'Failure'}</th>
                    {'\n                '}
                  </tr>
                  {'\n              '}
                </thead>
                {'\n              '}
                <tbody id="reportFileRows"></tbody>
                {'\n            '}
              </table>
              {'\n          '}
            </div>
            {'\n          '}
            <div className="reports-footer">
              {'\n            '}
              <span id="reportFilePage">{'Page 1'}</span>
              {'\n            '}
              <div className="reports-pagination">
                {'\n              '}
                <button id="reportFilesPrevious" type="button" disabled={true}>
                  {'Previous'}
                </button>
                {'\n              '}
                <button id="reportFilesNext" type="button" disabled={true}>
                  {'Next'}
                </button>
                {'\n            '}
              </div>
              {'\n          '}
            </div>
            {'\n        '}
          </section>
          {'\n\n        '}
          <section
            className="report-detail-card glass-panel"
            aria-labelledby="report-timeline-title"
          >
            {'\n          '}
            <div className="report-section-heading">
              {'\n            '}
              <h2 id="report-timeline-title">{'Migration timeline'}</h2>
              {'\n          '}
            </div>
            {'\n          '}
            <ol id="reportTimeline" className="report-timeline"></ol>
            {'\n        '}
          </section>
          {'\n\n        '}
          <section
            id="adminReportEvents"
            className="report-detail-card glass-panel"
            aria-labelledby="admin-events-title"
            hidden={true}
          >
            {'\n          '}
            <div className="report-section-heading">
              {'\n            '}
              <h2 id="admin-events-title">{'Administrator event data'}</h2>
              {'\n            '}
              <span>{'Raw operational evidence'}</span>
              {'\n          '}
            </div>
            {'\n          '}
            <div className="reports-table-scroll" tabIndex="0" aria-label="Scrollable raw events">
              {'\n            '}
              <table className="reports-table report-events-table">
                {'\n              '}
                <thead>
                  {'\n                '}
                  <tr>
                    {'\n                  '}
                    <th scope="col">{'Occurred'}</th>
                    {'\n                  '}
                    <th scope="col">{'Event'}</th>
                    {'\n                  '}
                    <th scope="col">{'Service'}</th>
                    {'\n                  '}
                    <th scope="col">{'File ID'}</th>
                    {'\n                  '}
                    <th scope="col">{'Data'}</th>
                    {'\n                '}
                  </tr>
                  {'\n              '}
                </thead>
                {'\n              '}
                <tbody id="adminEventRows"></tbody>
                {'\n            '}
              </table>
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
          <span>{'Migration Report'}</span>
          {'\n      '}
        </footer>
        {'\n    '}
      </div>
      {'\n\n    \n    \n    \n    \n  '}
    </>
  );
}
