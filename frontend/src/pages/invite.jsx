import React, { useEffect } from 'react';
import { loadControllers } from '../controllers.js';
export default function Page() {
  useEffect(() => {
    loadControllers([
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
      <main className="login-wrap">
        {'\n      '}
        <section className="login-card" aria-labelledby="invite-title">
          {'\n        '}
          <h1 id="invite-title">{'Administrator invitation'}</h1>
          {'\n        '}
          <p className="sub">{'Confirm access for your signed-in account.'}</p>
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
