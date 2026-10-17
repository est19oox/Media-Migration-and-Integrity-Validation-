import React, { useEffect } from 'react';
import { loadControllers } from '../controllers.js';
export default function Page() {
  useEffect(() => {
    loadControllers([
      '/js/parallax-move.js',
      '/js/carousel.js',
      '/js/particles.min.js',
      '/js/particles-effect.js',
      '/js/login.js',
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
      <div id="particles-js"></div>
      {'\n    '}
      <div className="fx fx-glow"></div>
      {'\n    '}
      <div className="fx fx-beam" style={{ top: '22%', transform: 'rotate(-4deg)' }}></div>
      {'\n    '}
      <div
        className="fx fx-beam"
        style={{ top: '58%', height: '3px', opacity: '0.7', transform: 'rotate(-4deg)' }}
      ></div>
      {'\n    '}
      <div
        className="fx fx-beam"
        style={{ top: '84%', height: '10px', opacity: '0.5', transform: 'rotate(-4deg)' }}
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
      ></div>
      {'\n    '}
      <section id="section-media">
        {'\n      '}
        {'\n      '}
        <div id="arrow-btn-prev" className="arrowBtn media-nav-btn prev" data-cursor="pointer">
          {'\n        '}
          <svg
            xmlns="http://www.w3.org/2000/svg"
            xmlnsXlink="http://www.w3.org/1999/xlink"
            viewBox="0 0 103 103"
          >
            {'\n          '}
            <circle
              className="dot"
              r="4.5"
              cx="36"
              cy="51.5"
              transformOrigin="34% 50%"
              transform="scale(0, 0)"
            ></circle>
            {'\n          '}
            <g transform="translate(35, 15)">
              {'\n            '}
              <use className="top" xlinkHref="#arrow-btn-tr" transformOrigin="16.1% 15.9%">
                {'\n              '}
                <path
                  id="arrow-btn-tr"
                  transformOrigin="16.1% 15.9%"
                  d="M31.274,7.155 L7.392,31.037 C5.870,32.559 3.402,32.559 1.880,31.037 C0.358,29.515 0.358,27.048 1.880,25.526 L25.762,1.644 C27.284,0.122 29.752,0.122 31.274,1.644 C32.795,3.166 32.795,5.633 31.274,7.155 Z"
                ></path>
                {'\n            '}
              </use>
              {'\n            '}
              <use className="bot" xlinkHref="#arrow-btn-br" transformOrigin="16% 55%">
                {'\n              '}
                <path
                  id="arrow-btn-br"
                  transformOrigin="16% 55%"
                  d="M1.880,41.954 C3.402,40.432 5.870,40.432 7.392,41.954 L31.274,65.836 C32.795,67.358 32.795,69.825 31.274,71.347 C29.752,72.869 27.284,72.869 25.762,71.347 L1.880,47.465 C0.358,45.943 0.358,43.476 1.880,41.954 Z"
                ></path>
                {'\n            '}
              </use>
              {'\n          '}
            </g>
            {'\n        '}
          </svg>
          {'\n      '}
        </div>
        {'\n      '}
        {'\n\n      '}
        {'\n      '}
        <div className="media-container-box" style={{ perspective: '5000px' }}>
          {'\n        '}
          <div className="media-main-view" id="media-layer-view">
            {'\n          '}
            <div className="media-current">
              {'\n            '}
              <div className="media-main-pic">
                {'\n              '}
                <a target="_blank" className="media-img"></a>
                {'\n            '}
              </div>
              {'\n\n            '}
              <div className="media-user-interactive" id="media-layer-front">
                {'\n              '}
                <div className="media-current-info">
                  {'\n                '}
                  <div className="media-info-wrapper">
                    {'\n                  '}
                    <div className="media-info-serial"></div>
                    {'\n                  '}
                    <div className="media-info-title"></div>
                    {'\n                  '}
                    <div className="media-info-detail">
                      {'\n                    '}
                      <div className="media-detail-desc"></div>
                      {'\n                  '}
                    </div>
                    {'\n                '}
                  </div>
                  {'\n\n                '}
                  <div className="media-nav-wrapper"></div>
                  {'\n              '}
                </div>
                {'\n            '}
              </div>
              {'\n          '}
            </div>
            {'\n          '}
            <div className="media-list-wrapper" id="media-list"></div>
            {'\n        '}
          </div>
          {'\n      '}
        </div>
        {'\n      '}
        {'\n\n      '}
        {'\n      '}
        <div id="arrow-btn-next" className="arrowBtn media-nav-btn next" data-cursor="pointer">
          {'\n        '}
          <svg
            xmlns="http://www.w3.org/2000/svg"
            xmlnsXlink="http://www.w3.org/1999/xlink"
            viewBox="0 0 103 103"
          >
            {'\n          '}
            <circle
              className="dot"
              r="4.5"
              cx="67"
              cy="51.5"
              transformOrigin="66% 50%"
              transform="scale(0, 0)"
            ></circle>
            {'\n          '}
            <g transform="translate(35, 15)">
              {'\n            '}
              <use className="top" xlinkHref="#arrow-btn-tl" transformOrigin="16.1% 15.9%">
                {'\n              '}
                <path
                  id="arrow-btn-tl"
                  transformOrigin="16.1% 15.9%"
                  d="M30.833,30.926 C29.311,32.448 26.844,32.448 25.322,30.926 L1.440,7.044 C-0.082,5.523 -0.082,3.055 1.440,1.533 C2.962,0.011 5.429,0.011 6.951,1.533 L30.833,25.415 C32.355,26.937 32.355,29.405 30.833,30.926 Z"
                ></path>
                {'\n            '}
              </use>
              {'\n            '}
              <use className="bot" xlinkHref="#arrow-btn-bl" transformOrigin="16% 55%">
                {'\n              '}
                <path
                  id="arrow-btn-bl"
                  transformOrigin="16% 55%"
                  d="M1.440,65.725 L25.322,41.843 C26.844,40.321 29.311,40.321 30.833,41.843 C32.355,43.365 32.355,45.832 30.833,47.354 L6.951,71.236 C5.429,72.758 2.962,72.758 1.440,71.236 C-0.082,69.714 -0.082,67.247 1.440,65.725 Z"
                ></path>
                {'\n            '}
              </use>
              {'\n          '}
            </g>
            {'\n        '}
          </svg>
          {'\n      '}
        </div>
        {'\n      '}
        {'\n    '}
      </section>
      {'\n\n    '}
      {'\n    '}
      <div className="login-wrap">
        {'\n      '}
        <form className="login-card" id="login-form" autoComplete="on" noValidate={true}>
          {'\n        '}
          <h1>{'LOGIN'}</h1>
          {'\n        '}
          <div className="sub">{'PLEASE SIGN IN TO CONTINUE'}</div>
          {'\n        '}
          <div className="field">
            {'\n          '}
            <label htmlFor="username">{'Username'}</label>
            {'\n          '}
            <input
              type="text"
              id="username"
              name="username"
              autoComplete="username"
              required={true}
            />
            {'\n        '}
          </div>
          {'\n        '}
          <div className="field">
            {'\n          '}
            <label htmlFor="password">{'Password'}</label>
            {'\n          '}
            <input
              type="password"
              id="password"
              name="password"
              autoComplete="current-password"
              required={true}
            />
            {'\n        '}
          </div>
          {'\n        '}
          <div className="row">
            {'\n          '}
            <label>
              <input type="checkbox" id="remember" />
              {' Remember me'}
            </label>
            {'\n          '}
            <a href="reset-password.html">{'Forgot password?'}</a>
            {'\n        '}
          </div>
          {'\n        '}
          <button type="submit">{'Sign in'}</button>
          {'\n        '}
          <div className="msg" id="msg"></div>
          {'\n        '}
          <div className="signup-entry">
            {"Don't have an account? "}
            <a href="signup.html">{'Sign up'}</a>
          </div>
          {'\n      '}
        </form>
        {'\n    '}
      </div>
      {'\n    '}
      {'\n    \n    \n    \n    \n    \n  '}
    </>
  );
}
