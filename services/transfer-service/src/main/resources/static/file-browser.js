'use strict';
(() => {
  const element=id=>document.getElementById(id);
  let files=[],path=[],cursor=null,generation=0,loading=false,admin=false;
  async function remove(url,message){if(!await mmivPortal.confirm(message))return;try{await mmivPortal.api(url,{method:'DELETE'});await load(true);}catch(error){element('status').textContent=error.message;}}
  function deleteButton(action){const button=document.createElement('button');button.textContent='Delete';button.className='danger';button.addEventListener('click',action);return button;}
  function navigate(next){path=next;mmivPreview.close();render();}
  function render(){
    const table=element('files'),crumbs=element('breadcrumbs');table.replaceChildren();crumbs.replaceChildren();
    const root=document.createElement('button');root.textContent=element('folder').value;root.className='breadcrumb';root.addEventListener('click',()=>navigate([]));crumbs.append(root);
    path.forEach((name,index)=>{const button=document.createElement('button');button.textContent=name;button.className='breadcrumb';button.addEventListener('click',()=>navigate(path.slice(0,index+1)));crumbs.append(button);});
    const prefix=path.length?path.join('/')+'/':'',folders=new Set(),entries=[];
    for(const file of files){if(!file.name.startsWith(prefix))continue;const relative=file.name.slice(prefix.length),slash=relative.indexOf('/');if(slash>=0)folders.add(relative.slice(0,slash));else entries.push({...file,displayName:relative});}
    for(const name of Array.from(folders).sort((a,b)=>a.localeCompare(b,'en'))){const row=document.createElement('tr'),cell=document.createElement('td'),size=document.createElement('td'),actions=document.createElement('td'),button=document.createElement('button');button.textContent='📁 '+name;button.className='folder-link';button.setAttribute('aria-label','Open folder '+name);button.addEventListener('click',()=>navigate([...path,name]));cell.append(button);size.textContent='Folder';if(admin)actions.append(deleteButton(()=>remove('/folders?folder='+encodeURIComponent(element('folder').value)+'&path='+encodeURIComponent([...path,name].join('/')),'Permanently delete folder '+name+' and all files inside it?')));row.append(cell,size,actions);table.append(row);}
    for(const file of entries.sort((a,b)=>a.displayName.localeCompare(b.displayName,'en'))){const row=document.createElement('tr'),name=document.createElement('td'),size=document.createElement('td'),actions=document.createElement('td');name.textContent=file.displayName;size.textContent=(file.sizeBytes/1024).toLocaleString('en-US',{maximumFractionDigits:1})+' KiB';
      const preview=document.createElement('button');preview.textContent='Preview';preview.className='secondary';preview.addEventListener('click',()=>mmivPreview.open(file.fileId,file.displayName));
      const download=document.createElement('a');download.textContent='Download';download.className='button';download.href='/api/v1/files/'+encodeURIComponent(file.fileId)+'/download';actions.append(preview,download);if(file.canDelete)actions.append(deleteButton(()=>remove('/files/'+encodeURIComponent(file.fileId),'Permanently delete '+file.displayName+' and all stored versions?')));row.append(name,size,actions);table.append(row);}
    element('status').textContent=table.children.length?'':cursor?'Load more to find additional files.':'This folder is empty.';
  }
  async function load(reset=false){
    if(reset){generation++;loading=false;files=[];path=[];cursor=null;mmivPreview.close();element('files').replaceChildren();element('breadcrumbs').replaceChildren();}
    if(loading)return;const current=generation,folder=element('folder').value;
    if(!folder){element('status').textContent='No access to folder';element('more').hidden=true;return;}
    loading=true;element('more').disabled=true;
    try{const data=await mmivPortal.api('/files?folder='+encodeURIComponent(folder)+(cursor?'&cursor='+encodeURIComponent(cursor):''));if(current!==generation)return;
      const known=new Set(files.map(f=>f.fileId));files.push(...data.files.filter(f=>!known.has(f.fileId)));cursor=data.nextCursor;render();element('more').hidden=!cursor;
    }catch(error){if(current===generation){files=[];element('files').replaceChildren();element('status').textContent=error.message;element('more').hidden=true;}}
    finally{if(current===generation){loading=false;element('more').disabled=false;}}
  }
  mmivPortal.profile.then(user=>{admin=user.role==='ADMIN';const select=element('folder');for(const folder of user.destinations)mmivPortal.option(select,folder.destination,folder.destination);const requested=new URLSearchParams(location.search).get('folder');if(user.destinations.some(f=>f.destination===requested))select.value=requested;load(true);}).catch(()=>{});
  element('folder').addEventListener('change',()=>load(true));element('more').addEventListener('click',()=>load());
})();
