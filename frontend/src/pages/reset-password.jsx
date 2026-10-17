import React, { useEffect } from 'react';
import { loadControllers } from '../controllers.js';
export default function Page() {
  useEffect(() => {
    loadControllers([
      '/js/feedback.js',
      '/js/particles.min.js',
      '/js/particles-effect.js',
      '/js/parallax-move.js',
      '/js/reset-password.js',
    ]);
  }, []);
  return (
    <>
      {'\n    '}
      {'\n    '}
      <div id="particles-js"></div>
      {'\n\n    '}
      {'\n    '}
      <div className="fx fx-glow"></div>
      {'\n\n    '}
      <div className="fx fx-beam" style={{ top: '22%', transform: 'rotate(-4deg)' }}></div>
      {'\n\n    '}
      <div
        className="fx fx-beam"
        style={{ top: '58%', height: '3px', opacity: '0.7', transform: 'rotate(-4deg)' }}
      ></div>
      {'\n\n    '}
      <div
        className="fx fx-beam"
        style={{ top: '84%', height: '10px', opacity: '0.5', transform: 'rotate(-4deg)' }}
      ></div>
      {'\n\n    '}
      <div
        className="fx fx-bokeh"
        style={{
          left: '38%',
          top: '12%',
          width: '260px',
          height: '260px',
          background: 'rgba(0, 120, 212, 0.22)',
        }}
      ></div>
      {'\n\n    '}
      <div
        className="fx fx-bokeh"
        style={{
          left: '62%',
          top: '48%',
          width: '340px',
          height: '340px',
          background: 'rgba(120, 90, 255, 0.16)',
        }}
      ></div>
      {'\n\n    '}
      <div
        className="fx fx-bokeh"
        style={{
          left: '8%',
          top: '70%',
          width: '220px',
          height: '220px',
          background: 'rgba(0, 200, 200, 0.16)',
        }}
      ></div>
      {'\n\n    '}
      <div
        className="fx fx-fore"
        style={{
          left: '-90px',
          bottom: '-80px',
          width: '320px',
          height: '320px',
          background: 'rgba(0, 120, 212, 0.35)',
        }}
      ></div>
      {'\n\n    '}
      <div
        className="fx fx-fore"
        style={{
          right: '-70px',
          top: '-60px',
          width: '260px',
          height: '260px',
          background: 'rgba(120, 90, 255, 0.3)',
        }}
      ></div>
      {'\n\n    '}
      <div
        className="fx fx-fore"
        style={{
          left: '46%',
          bottom: '-120px',
          width: '380px',
          height: '200px',
          background: 'rgba(255, 255, 255, 0.9)',
        }}
      ></div>
      {'\n\n    '}
      {'\n    '}
      <div className="mmiv-header">
        {'MMIV'}
        <span>{'.'}</span>
      </div>
      {'\n\n    '}
      {'\n    '}
      <div className="login-wrap">
        {'\n      '}
        <main className="login-card reset-card">
          {'\n        '}
          <h1>{'CHANGE PASSWORD'}</h1>
          {'\n\n        '}
          <div className="sub">{'SET YOUR NEW PASSWORD'}</div>
          {'\n\n        '}
          <p className="reset-description">
            {
              '\n          Your administrator-issued link expires after one hour and can be used once.\n        '
            }
          </p>
          {'\n\n        '}
          <form id="resetForm">
            {'\n          '}
            <div className="field">
              {'\n            '}
              <label htmlFor="password">{' New password '}</label>
              {'\n\n            '}
              <input
                id="password"
                type="password"
                autoComplete="new-password"
                minLength="8"
                required={true}
              />
              {'\n          '}
            </div>
            {'\n\n          '}
            <div className="field">
              {'\n            '}
              <label htmlFor="confirm">{' Confirm new password '}</label>
              {'\n\n            '}
              <input id="confirm" type="password" autoComplete="new-password" required={true} />
              {'\n          '}
            </div>
            {'\n\n          '}
            <button id="submit" type="submit">
              {'Change password'}
            </button>
            {'\n        '}
          </form>
          {'\n\n        '}
          <p id="status" role="status"></p>
          {'\n\n        '}
          <div className="reset-back">
            {'\n          '}
            <a href="login.html">{' Back to sign in '}</a>
            {'\n        '}
          </div>
          {'\n      '}
        </main>
        {'\n    '}
      </div>
      {'\n\n    \n    \n    \n    \n  '}
    </>
  );
}
