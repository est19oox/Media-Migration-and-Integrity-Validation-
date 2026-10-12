'use strict';
window.mmivPortal = (() => {
  async function api(path, options = {}) {
    const response = await mmivAuth.fetch('/api/v1/portal' + path, options);
    if (!response.ok) throw new Error(response.status === 403 ? 'No access to folder' : response.status === 404 ? 'Not found' : 'Request failed. Please try again.');
    return response.json();
  }
  const profile = api('/me');
  profile.then(user => {
    document.querySelectorAll('[data-admin]').forEach(node => {node.hidden = user.role !== 'ADMIN';});
    document.querySelectorAll('[data-upload]').forEach(node => {node.hidden = !user.destinations.some(folder => folder.permission === 'WRITE');});
    document.querySelectorAll('[data-read]').forEach(node => {node.hidden = user.destinations.length === 0;});
    const name = document.getElementById('account'); if (name) name.textContent = user.username;
  }).catch(error => {const status = document.getElementById('status') || document.getElementById('message'); if(status)status.textContent=error.message;});
  function option(select, value, label) {const node=document.createElement('option');node.value=value;node.textContent=label;select.append(node);}
  return {api,profile,option};
})();
