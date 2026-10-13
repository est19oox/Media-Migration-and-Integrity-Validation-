'use strict';
/* PWA disabled. Original network-only worker retained for a future opt-in.
// Network only: never retain private pages, credentials, files or API responses.
self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));
self.addEventListener('fetch',event=>{event.respondWith(fetch(event.request,{cache:'no-store'}));});
*/
