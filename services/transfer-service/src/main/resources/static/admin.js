'use strict';
(() => {
  const element=id=>document.getElementById(id);
  let users=[],folders=[],busy=false;
  const message=text=>{element('message').textContent=text;};
  const target=()=>users.find(user=>user.userId===element('users').value);
  function controls(){const user=target(),folder=element('folders').value;element('grant').disabled=busy||!user||!user.active||user.role==='ADMIN'||!folder;element('revoke').disabled=busy||!user||user.role==='ADMIN'||!folder;}
  function renderUser(){const user=target();const access=user?.destinations.find(a=>a.destination===element('folders').value);element('userDetails').textContent=user?user.username+' — '+(user.role==='ADMIN'?'Administrator: access to all folders':access?.permission==='WRITE'?'Upload, view and download':access?'View and download':'No access to folder'):'';controls();}
  function renderFolders(){const select=element('folders'),previous=select.value;select.replaceChildren();for(const folder of folders.filter(f=>f.toLowerCase().includes(element('folderQuery').value.toLowerCase())))mmivPortal.option(select,folder,folder);if(folders.includes(previous)&&Array.from(select.options).some(o=>o.value===previous))select.value=previous;renderUser();}
  async function search(){users=await mmivPortal.api('/admin/users?q='+encodeURIComponent(element('userQuery').value.trim()));const select=element('users');select.replaceChildren();element('userNames').replaceChildren();for(const user of users){mmivPortal.option(select,user.userId,user.username+(user.active?'':' (inactive)'));mmivPortal.option(element('userNames'),user.username,user.username);}renderUser();}
  async function run(operation){if(busy)return;busy=true;document.querySelectorAll('#management button').forEach(button=>button.disabled=true);try{await operation();}catch(error){message(error.message);}finally{busy=false;document.querySelectorAll('#management button').forEach(button=>button.disabled=false);controls();}}
  function write(path,method,body){return mmivPortal.api(path,{method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});}
  element('invitationOrigin').value=location.origin;
  const invitation=new URLSearchParams(location.hash.slice(1)).get('invite');
  // Keep the hash until the shared session is ready so login redirects can preserve invitations.
  run(async()=>{
    await mmivAuth.ready;
    if(invitation){history.replaceState(null,'',location.pathname);await write('/invitations/accept','POST',{token:invitation});}
    const profile=await mmivPortal.api('/me');
    if(profile.role!=='ADMIN'){message('Administrator access required.');return;}
    element('management').hidden=false;folders=await mmivPortal.api('/admin/folders');renderFolders();await search();message('Manage folders and permissions below.');
  });
  element('userSearch').addEventListener('submit',event=>{event.preventDefault();run(search);});
  element('users').addEventListener('change',renderUser);element('folders').addEventListener('change',renderUser);element('folderQuery').addEventListener('input',renderFolders);
  element('createFolder').addEventListener('submit',event=>{event.preventDefault();run(async()=>{folders=await write('/admin/folders','POST',{name:element('folderName').value.trim()});element('folderName').value='';element('folderQuery').value='';renderFolders();message('Folder created.');});});
  element('grant').addEventListener('click',()=>run(async()=>{const user=target(),destination=element('folders').value;if(!user||!destination)return;const updated=await write('/admin/users/'+encodeURIComponent(user.userId)+'/permissions','PUT',{destination,permission:element('permission').value});users=users.map(u=>u.userId===updated.userId?updated:u);renderUser();message('Permission saved.');}));
  element('revoke').addEventListener('click',()=>run(async()=>{const user=target(),destination=element('folders').value;if(!user||!destination)return;const updated=await mmivPortal.api('/admin/users/'+encodeURIComponent(user.userId)+'/permissions?destination='+encodeURIComponent(destination),{method:'DELETE'});users=users.map(u=>u.userId===updated.userId?updated:u);renderUser();message('Permission revoked.');}));
  element('inviteAdmin').addEventListener('submit',event=>{event.preventDefault();run(async()=>{element('invitationLink').textContent='';const origin=new URL(element('invitationOrigin').value.trim());if(!['http:','https:'].includes(origin.protocol)||origin.username||origin.password||origin.search||origin.hash||origin.pathname!=='/')throw new Error('Enter the website origin, for example https://your-website.example.');const result=await write('/admin/invitations','POST',{username:element('inviteUsername').value.trim()});const link=new URL('/invite.html',origin.origin);link.hash='invite='+result.token;element('invitationLink').textContent=link.href;message('Invitation created. Send the link privately to the named user.');});});
  element('copyInvitation').addEventListener('click',()=>run(async()=>{const link=element('invitationLink').textContent;if(!link)throw new Error('Create an invitation first.');try{await navigator.clipboard.writeText(link);message('Invitation link copied.');}catch(_){message('Select and copy the invitation link shown above.');}}));
})();
