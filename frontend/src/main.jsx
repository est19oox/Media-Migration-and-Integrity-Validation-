import React from 'react';
import { createRoot } from 'react-dom/client';
const pages = import.meta.glob('./pages/*.jsx');
const name =
  location.pathname
    .split('/')
    .pop()
    .replace(/\.html$/, '') || 'home';
const entry = pages[`./pages/${name}.jsx`];
if (!entry) throw new Error('Unknown page');
entry().then(({ default: Page }) => createRoot(document.getElementById('root')).render(<Page />));
