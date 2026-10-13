'use strict';
window.mmivPortal = (() => {
  async function api(path, options = {}) {
    const response = await mmivAuth.fetch('/api/v1/portal' + path, options);
    if (!response.ok) throw new Error(response.status === 403 ? 'No access to folder' : response.status === 404 ? (path.startsWith('/admin/users/') || path === '/admin/invitations' ? 'User not found. Ask the user to create an account first.' : 'Not found') : 'Request failed. Please try again.');
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
  function confirm(message){return new Promise(resolve=>{const dialog=document.createElement('dialog');dialog.className='confirmation';const title=document.createElement('h2');title.textContent='Confirm deletion';const text=document.createElement('p');text.textContent=message;const cancel=document.createElement('button');cancel.className='secondary';cancel.textContent='Cancel';const remove=document.createElement('button');remove.className='danger';remove.textContent='Delete';let accepted=false;cancel.addEventListener('click',()=>dialog.close());remove.addEventListener('click',()=>{accepted=true;dialog.close();});dialog.addEventListener('close',()=>{dialog.remove();resolve(accepted);},{once:true});dialog.append(title,text,cancel,remove);document.body.append(dialog);dialog.showModal();cancel.focus();});}
  return {api,profile,option,confirm};
})();
