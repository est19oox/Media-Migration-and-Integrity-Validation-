import React, { useEffect } from 'react';
import { loadControllers } from '../controllers.js';
export default function Page() {
  useEffect(() => {
    loadControllers([
      '/js/particles.min.js',
      '/js/particles-effect.js',
      '/js/mmiv-auth.js',
      '/js/reports.js',
    ]);
  }, []);
  return (
    <>
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
            <a href="reports.html" className="active" aria-current="page">
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
        <main className="home-main reports-main">
          {'\n        '}
          <section className="reports-workspace glass-panel" aria-labelledby="reports-title">
            {'\n          '}
            <div className="reports-heading">
              {'\n            '}
              <div>
                {'\n              '}
                <h1 id="reports-title">{'Migration Reports'}</h1>
                {'\n              '}
                <p>{'Review the outcome of your media migration jobs.'}</p>
                {'\n            '}
              </div>
              {'\n            '}
              <label>
                {'\n              '}
                <span>{'Filter by status'}</span>
                {'\n              '}
                <select id="reportStatusFilter">
                  {'\n                '}
                  <option value="">{'All statuses'}</option>
                  {'\n                '}
                  <option value="PROCESSING">{'Processing'}</option>
                  {'\n                '}
                  <option value="COMPLETED">{'Completed'}</option>
                  {'\n                '}
                  <option value="PARTIALLY_COMPLETED">{'Partially completed'}</option>
                  {'\n                '}
                  <option value="FAILED">{'Failed'}</option>
                  {'\n                '}
                  <option value="INCOMPLETE">{'Incomplete'}</option>
                  {'\n              '}
                </select>
                {'\n            '}
              </label>
              {'\n          '}
            </div>
            {'\n\n          '}
            <div
              className="reports-table-scroll"
              tabIndex="0"
              aria-label="Scrollable migration reports"
            >
              {'\n            '}
              <table className="reports-table">
                {'\n              '}
                <caption className="reports-visually-hidden">
                  {'Your media migration reports'}
                </caption>
                {'\n              '}
                <thead>
                  {'\n                '}
                  <tr>
                    {'\n                  '}
                    <th scope="col">{'Migration ID'}</th>
                    {'\n                  '}
                    <th scope="col">{'Started'}</th>
                    {'\n                  '}
                    <th scope="col">{'Status'}</th>
                    {'\n                  '}
                    <th scope="col">{'Total'}</th>
                    {'\n                  '}
                    <th scope="col">{'Successful'}</th>
                    {'\n                  '}
                    <th scope="col">{'Failed'}</th>
                    {'\n                  '}
                    <th scope="col">{'Success rate'}</th>
                    {'\n                  '}
                    <th scope="col">{'Details'}</th>
                    {'\n                '}
                  </tr>
                  {'\n              '}
                </thead>
                {'\n              '}
                <tbody id="reportRows"></tbody>
                {'\n            '}
              </table>
              {'\n          '}
            </div>
            {'\n\n          '}
            <div className="reports-footer">
              {'\n            '}
              <span id="reportCount">{'0 reports'}</span>
              {'\n            '}
              <span id="reportsStatus" role="status" aria-live="polite">
                {'Loading migration reports…'}
              </span>
              {'\n            '}
              <div className="reports-pagination">
                {'\n              '}
                <button id="reportsPrevious" type="button" disabled={true}>
                  {'Previous'}
                </button>
                {'\n              '}
                <button id="reportsNext" type="button" disabled={true}>
                  {'Next'}
                </button>
                {'\n            '}
              </div>
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
          <span>{'Migration Reports'}</span>
          {'\n      '}
        </footer>
        {'\n    '}
      </div>
      {'\n\n    \n    \n    \n    \n  '}
    </>
  );
}
