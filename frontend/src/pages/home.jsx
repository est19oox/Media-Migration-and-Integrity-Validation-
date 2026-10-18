import React, { useEffect } from 'react';
import { loadControllers } from '../controllers.js';
export default function Page() {
  useEffect(() => {
    loadControllers([
      '/js/particles.min.js',
      '/js/particles-effect.js',
      '/js/mmiv-auth.js',
      '/js/home.js',
    ]);
  }, []);
  return (
    <>
      {'\n    '}
      {'\n    '}
      <div id="particles-js" aria-hidden="true"></div>
      {'\n\n    '}
      {'\n    '}
      <div className="fx fx-glow" aria-hidden="true"></div>
      {'\n\n    '}
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
      {'\n    '}
      <div className="home-shell">
        {'\n      '}
        {'\n      '}
        <header className="home-header glass-panel">
          {'\n        '}
          {'\n        '}
          {'\n        '}
          <nav className="home-nav" aria-label="Main navigation">
            {'\n          '}
            <a href="home.html" className="active" aria-current="page">
              {' Home '}
            </a>
            {'\n\n          '}
            <a href="file-manager.html">{' File Manager '}</a>
            {'\n\n          '}
            <a href="activity.html">{' Activity '}</a>
            {'\n\n          '}
            <a href="reports.html">{' Migration Reports '}</a>
            {'\n\n          '}
            {'\n          '}
            <a href="admin.html" data-admin="" hidden={true}>
              {' Administration '}
            </a>
            {'\n        '}
          </nav>
          {'\n\n        '}
          {'\n        '}
          <div className="header-right">
            {'\n          '}
            {'\n          '}
            <div className="home-account">
              {'\n            '}
              <div className="account-avatar" aria-hidden="true">
                {'MM'}
              </div>
              {'\n\n            '}
              <div className="account-copy">
                {'\n              '}
                <strong id="account">{' MMIV account '}</strong>
                {'\n\n              '}
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
            {'\n          '}
            <a className="mmiv-header" href="home.html" aria-label="MMIV Home">
              {' MMIV'}
              <span>{'.'}</span>{' '}
            </a>
            {'\n        '}
          </div>
          {'\n      '}
        </header>
        {'\n\n      '}
        {'\n      '}
        <main className="home-main">
          {'\n        '}
          {'\n        '}
          <p id="status" className="system-status" role="status" aria-live="polite"></p>
          {'\n\n        '}
          {'\n        '}
          {'\n        '}
          <div className="workspace-panels">
            {'\n          '}
            {'\n          '}
            <section className="folders-card glass-panel" aria-labelledby="folders-title">
              {'\n            '}
              <div className="section-heading">
                {'\n              '}
                <div>
                  {'\n                '}
                  <h1 id="folders-title">{'Your folders'}</h1>
                  {'\n                '}
                  <p className="folders-description">{'Folders you have permission to access.'}</p>
                  {'\n              '}
                </div>
                {'\n\n              '}
                <span className="folder-count" id="folderCount">
                  {' 0 folders '}
                </span>
                {'\n            '}
              </div>
              {'\n\n            '}
              <ul id="folders" className="folder-list" aria-label="Accessible folders"></ul>
              {'\n\n            '}
              <p id="noAccess" className="empty-state" hidden={true}>
                {'No accessible folders found.'}
              </p>
              {'\n          '}
            </section>
            {'\n\n          '}
            {'\n          '}
            <section className="activity-card glass-panel" aria-labelledby="activity-title">
              {'\n            '}
              <div className="section-heading">
                {'\n              '}
                <div>
                  {'\n                '}
                  <h2 id="activity-title">{'Activity'}</h2>
                  {'\n                '}
                  <p className="folders-description">{'Your recent workspace activity.'}</p>
                  {'\n              '}
                </div>
                {'\n            '}
              </div>
              {'\n\n            '}
              <ul className="activity-list"></ul>
              {'\n\n            '}
              <a href="activity.html" className="activity-link">
                {' View all activity → '}
              </a>
              {'\n          '}
            </section>
            {'\n        '}
          </div>
          {'\n      '}
        </main>
        {'\n\n      '}
        {'\n      '}
        <footer className="home-footer">
          {'\n        '}
          <span>{' MMIV · Media Migration and Integrity Validation '}</span>
          {'\n\n        '}
          <span>{' Workspace '}</span>
          {'\n      '}
        </footer>
        {'\n    '}
      </div>
      {'\n\n    '}
      {'\n    \n    \n    \n\n    \n  '}
    </>
  );
}
