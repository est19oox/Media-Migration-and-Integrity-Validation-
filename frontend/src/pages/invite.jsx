import React, { useEffect } from 'react';
import { loadControllers } from '../controllers.js';
export default function Page() {
  useEffect(() => {
    loadControllers([
      '/js/parallax-move.js',
      '/js/carousel.js',
      '/js/particles.min.js',
      '/js/particles-effect.js',
      '/js/mmiv-auth.js',
      '/js/invite.js',
    ]);
  }, []);
  return (
    <>
      {'\n    '}
      <div className="mmiv-header">
        {'MMIV'}
        <span>{'.'}</span>
      </div>
      {'\n    '}
      <div id="particles-js" aria-hidden="true"></div>
      {'\n    '}
      <div className="fx fx-glow" aria-hidden="true"></div>
      {'\n    '}
      <div
        className="fx fx-beam"
        style={{ top: '22%', transform: 'rotate(-4deg)' }}
        aria-hidden="true"
      ></div>
      {'\n    '}
      <div
        className="fx fx-beam"
        style={{ top: '58%', height: '3px', opacity: '0.7', transform: 'rotate(-4deg)' }}
        aria-hidden="true"
      ></div>
      {'\n    '}
      <div
        className="fx fx-beam"
        style={{ top: '84%', height: '10px', opacity: '0.5', transform: 'rotate(-4deg)' }}
        aria-hidden="true"
      ></div>
      {'\n    '}
      <div
        className="fx fx-bokeh"
        style={{
          left: '38%',
          top: '12%',
          width: '260px',
          height: '260px',
          background: 'rgba(0, 120, 212, 0.22)',
        }}
        aria-hidden="true"
      ></div>
      {'\n    '}
      <div
        className="fx fx-bokeh"
        style={{
          left: '62%',
          top: '48%',
          width: '340px',
          height: '340px',
          background: 'rgba(120, 90, 255, 0.16)',
        }}
        aria-hidden="true"
      ></div>
      {'\n    '}
      <div
        className="fx fx-bokeh"
        style={{
          left: '8%',
          top: '70%',
          width: '220px',
          height: '220px',
          background: 'rgba(0, 200, 200, 0.16)',
        }}
        aria-hidden="true"
      ></div>
      {'\n    '}
      <div
        className="fx fx-fore"
        style={{
          left: '-90px',
          bottom: '-80px',
          width: '320px',
          height: '320px',
          background: 'rgba(0, 120, 212, 0.35)',
        }}
        aria-hidden="true"
      ></div>
      {'\n    '}
      <div
        className="fx fx-fore"
        style={{
          right: '-70px',
          top: '-60px',
          width: '260px',
          height: '260px',
          background: 'rgba(120, 90, 255, 0.3)',
        }}
        aria-hidden="true"
      ></div>
      {'\n    '}
      <div
        className="fx fx-fore"
        style={{
          left: '46%',
          bottom: '-120px',
          width: '380px',
          height: '200px',
          background: 'rgba(255, 255, 255, 0.9)',
        }}
        aria-hidden="true"
      ></div>
      {'\n    '}
      <section id="section-media" aria-label="MMIV visual carousel">
        <div id="arrow-btn-prev" className="arrowBtn media-nav-btn prev" data-cursor="pointer">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 103 103" aria-hidden="true">
            <circle
              className="dot"
              r="4.5"
              cx="36"
              cy="51.5"
              transformOrigin="34% 50%"
              transform="scale(0, 0)"
            ></circle>
            <g transform="translate(35, 15)">
              <path
                className="top"
                transformOrigin="16.1% 15.9%"
                d="M31.274,7.155 L7.392,31.037 C5.870,32.559 3.402,32.559 1.880,31.037 C0.358,29.515 0.358,27.048 1.880,25.526 L25.762,1.644 C27.284,0.122 29.752,0.122 31.274,1.644 C32.795,3.166 32.795,5.633 31.274,7.155 Z"
              ></path>
              <path
                className="bot"
                transformOrigin="16% 55%"
                d="M1.880,41.954 C3.402,40.432 5.870,40.432 7.392,41.954 L31.274,65.836 C32.795,67.358 32.795,69.825 31.274,71.347 C29.752,72.869 27.284,72.869 25.762,71.347 L1.880,47.465 C0.358,45.943 0.358,43.476 1.880,41.954 Z"
              ></path>
            </g>
          </svg>
        </div>
        <div className="media-container-box" style={{ perspective: '5000px' }}>
          <div className="media-main-view" id="media-layer-view">
            <div className="media-current">
              <div className="media-main-pic">
                <a
                  target="_blank"
                  rel="noopener noreferrer"
                  className="media-img"
                  aria-label="Open featured media"
                ></a>
              </div>
              <div className="media-user-interactive" id="media-layer-front">
                <div className="media-current-info">
                  <div className="media-info-wrapper">
                    <div className="media-info-serial"></div>
                    <div className="media-info-title"></div>
                    <div className="media-info-detail">
                      <div className="media-detail-desc"></div>
                    </div>
                  </div>
                  <div className="media-nav-wrapper"></div>
                </div>
              </div>
            </div>
            <div className="media-list-wrapper" id="media-list"></div>
          </div>
        </div>
        <div id="arrow-btn-next" className="arrowBtn media-nav-btn next" data-cursor="pointer">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 103 103" aria-hidden="true">
            <circle
              className="dot"
              r="4.5"
              cx="67"
              cy="51.5"
              transformOrigin="66% 50%"
              transform="scale(0, 0)"
            ></circle>
            <g transform="translate(35, 15)">
              <path
                className="top"
                transformOrigin="16.1% 15.9%"
                d="M30.833,30.926 C29.311,32.448 26.844,32.448 25.322,30.926 L1.440,7.044 C-0.082,5.523 -0.082,3.055 1.440,1.533 C2.962,0.011 5.429,0.011 6.951,1.533 L30.833,25.415 C32.355,26.937 32.355,29.405 30.833,30.926 Z"
              ></path>
              <path
                className="bot"
                transformOrigin="16% 55%"
                d="M1.440,65.725 L25.322,41.843 C26.844,40.321 29.311,40.321 30.833,41.843 C32.355,43.365 32.355,45.832 30.833,47.354 L6.951,71.236 C5.429,72.758 2.962,72.758 1.440,71.236 C-0.082,69.714 -0.082,67.247 1.440,65.725 Z"
              ></path>
            </g>
          </svg>
        </div>
      </section>
      {'\n    '}
      <main className="login-wrap">
        {'\n      '}
        <section className="login-card invite-card" aria-labelledby="invite-title">
          {'\n        '}
          <h1 id="invite-title">{'INVITATION'}</h1>
          {'\n        '}
          <p className="sub">{'ADMINISTRATOR ACCESS CONFIRMATION'}</p>
          {'\n        '}
          <p id="status" className="msg" role="status" aria-live="polite">
            {'\n          Checking your invitation…\n        '}
          </p>
          {'\n        '}
          <button id="accept" type="button" disabled={true}>
            {'Accept invitation'}
          </button>
          {'\n        '}
          <button id="logout" type="button">
            {'Sign out'}
          </button>
          {'\n      '}
        </section>
        {'\n    '}
      </main>
      {'\n    \n    \n    \n    \n  '}
    </>
  );
}
