import React, { useEffect } from 'react';
import { loadControllers } from '../controllers.js';
export default function Page() {
  useEffect(() => {
    loadControllers([
      '/js/particles.min.js',
      '/js/particles-effect.js',
      '/js/mmiv-auth.js',
      '/js/activity.js',
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
      {'\n\n    '}
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
            <a href="file-manager.html">{' File Manager '}</a>
            {'\n\n          '}
            <a href="activity.html" className="active" aria-current="page">
              {' Activity '}
            </a>
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
        <main className="home-main activity-main">
          {'\n        '}
          <p id="status" className="system-status" role="status" aria-live="polite"></p>
          {'\n\n        '}
          <section className="activity-workspace glass-panel" aria-labelledby="activity-title">
            {'\n          '}
            {'\n          '}
            <div className="activity-toolbar">
              {'\n            '}
              <h1 id="activity-title">{'Activity'}</h1>
              {'\n\n            '}
              <button
                id="refreshActivity"
                className="activity-refresh"
                type="button"
                title="Refresh activity"
                aria-label="Refresh activity"
              >
                {'\n              '}
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
                  {'\n                '}
                  <path d="M20 7v5h-5"></path>
                  {'\n                '}
                  <path d="M4 17v-5h5"></path>
                  {'\n                '}
                  <path d="M5.5 9A7 7 0 0 1 18 7l2 5"></path>
                  {'\n                '}
                  <path d="M18.5 15A7 7 0 0 1 6 17l-2-5"></path>
                  {'\n              '}
                </svg>
                {'\n            '}
              </button>
              {'\n          '}
            </div>
            {'\n\n          '}
            {'\n          '}
            <div className="activity-controls">
              {'\n            '}
              <div className="activity-search">
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
                  id="activitySearch"
                  type="search"
                  placeholder="Search activity..."
                  aria-label="Search activity"
                />
                {'\n            '}
              </div>
              {'\n\n            '}
              <select id="activityFilter" aria-label="Filter activity type">
                {'\n              '}
                <option value="all">{'All activity'}</option>
                {'\n              '}
                <option value="upload">{'Uploads'}</option>
                {'\n              '}
                <option value="download">{'Downloads'}</option>
                {'\n              '}
                <option value="verification">{'Verification'}</option>
                {'\n            '}
              </select>
              {'\n          '}
            </div>
            {'\n\n          '}
            {'\n          '}
            <div
              id="activityScroll"
              className="activity-table-scroll"
              tabIndex="0"
              aria-label="Scrollable activity records"
            >
              {'\n            '}
              <table className="activity-table">
                {'\n              '}
                <caption className="activity-visually-hidden">
                  {'\n                Recent workspace activity records\n              '}
                </caption>
                {'\n\n              '}
                <thead>
                  {'\n                '}
                  <tr>
                    {'\n                  '}
                    <th scope="col">{'Activity'}</th>
                    {'\n                  '}
                    <th scope="col">{'File / Folder'}</th>
                    {'\n                  '}
                    <th scope="col">{'Location'}</th>
                    {'\n                  '}
                    <th scope="col">{'Date'}</th>
                    {'\n                  '}
                    <th scope="col">{'Status'}</th>
                    {'\n                '}
                  </tr>
                  {'\n              '}
                </thead>
                {'\n\n              '}
                <tbody id="activityRows"></tbody>
                {'\n            '}
              </table>
              {'\n\n            '}
              <div id="activitySentinel" aria-hidden="true"></div>
              {'\n          '}
            </div>
            {'\n\n          '}
            {'\n          '}
            <div className="activity-bottom">
              {'\n            '}
              <span id="activityCount" className="activity-count">
                {' 0 activities '}
              </span>
              {'\n\n            '}
              <span
                id="activityListStatus"
                className="activity-visually-hidden"
                role="status"
                aria-live="polite"
              >
                {'\n              Loading activity\n            '}
              </span>
              {'\n          '}
            </div>
            {'\n        '}
          </section>
          {'\n      '}
        </main>
        {'\n\n      '}
        <footer className="home-footer">
          {'\n        '}
          <span>{' MMIV · Media Migration and Integrity Validation '}</span>
          {'\n        '}
          <span>{'Activity'}</span>
          {'\n      '}
        </footer>
        {'\n    '}
      </div>
      {'\n\n    '}
      {'\n    \n    \n    \n\n    '}
      {'\n    \n  '}
    </>
  );
}
