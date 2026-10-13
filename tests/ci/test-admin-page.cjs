const {test}=require('node:test');
const assert=require('node:assert/strict');
const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const source=readFileSync('services/transfer-service/src/main/resources/static/admin.js','utf8');
async function page(role='ADMIN',hash='',clipboard=true,targetRole='USER',self=false){
 const nodes=new Map(),requests=[],historyCalls=[],copied=[];
 const element=id=>{if(!nodes.has(id))nodes.set(id,{value:'',textContent:'',hidden:id==='management',disabled:false,options:[],listeners:{},focus(){},select(){},setSelectionRange(){},addEventListener(event,fn){this.listeners[event]=fn;},replaceChildren(){this.options=[];this.value='';}});return nodes.get(id);};
 const user={userId:'11111111-1111-4111-8111-111111111111',username:'alice',role:targetRole,active:true,destinations:[]};
 let folders=['SW001','semester2'],deleted=false;const confirmations=[],notices=[],navigations=[];
 const context={document:{getElementById:element,execCommand(){copied.push(element('invitationLink').value);return true;},querySelectorAll:()=>['grant','revoke','copyInvitation'].map(element)},location:{origin:'https://mmiv.example',hash,pathname:'/admin.html',assign(url){navigations.push(url);}},history:{replaceState(...args){historyCalls.push(args);}},navigator:{clipboard:clipboard?{async writeText(text){copied.push(text);}}:undefined},URL,URLSearchParams,Error,mmivAuth:{ready:Promise.resolve()},mmivFeedback:{notice(...args){notices.push(args);if(args[3])args[3]();}},mmivPortal:{async confirm(...args){confirmations.push(args);return true;},option(select,value,text){select.options.push({value,textContent:text});select.value ||= value;},async api(path,options){requests.push({path,options});if(path==='/me')return {role,userId:self?user.userId:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'};if(path==='/admin/folders'){if(options){folders.push(JSON.parse(options.body).name);}return folders;}if(path.startsWith('/admin/users?q=')){const q=decodeURIComponent(path.split('q=')[1]);return !deleted&&(!q||user.username.includes(q))?[user]:[];}if(path.endsWith('/password-reset'))return {token:'c'.repeat(64)};if(path==='/admin/users/'+user.userId&&options.method==='DELETE'){user.active=false;return null;}if(path.includes('/permissions')){user.destinations=options.method==='DELETE'?[]:[JSON.parse(options.body)];return {...user};}if(path==='/admin/invitations')return {token:'b'.repeat(64)};if(path==='/invitations/accept')return {};throw new Error('Unexpected request '+path);}}};
 vm.runInNewContext(source,context);const flush=async()=>{for(let i=0;i<10;i++)await new Promise(setImmediate);};await flush();
 return {element,requests,historyCalls,copied,confirmations,notices,navigations,async trigger(id,event='click'){element(id).listeners[event]({preventDefault(){}});await flush();}};
}
test('administration reuses the existing login and loads user and folder dropdowns',async()=>{
 const app=await page();assert.equal(app.element('management').hidden,false);assert.equal(app.element('users').options[0].textContent,'alice');assert.equal(app.element('folders').options.length,2);assert.ok(app.requests.every(r=>!r.path.includes('/auth/login')));
});
test('ordinary account has no administration controls',async()=>{
 const app=await page('USER');assert.equal(app.element('management').hidden,true);assert.match(app.element('message').textContent,/Administrator access required/);assert.equal(app.requests.length,1);
});
test('selected folder controls the permission grant and revoke',async()=>{
 const app=await page();app.element('folders').value='semester2';app.element('permission').value='READ';await app.trigger('grant');const request=app.requests.find(r=>r.options?.method==='PUT');assert.deepEqual(JSON.parse(request.options.body),{destination:'semester2',permission:'READ'});await app.trigger('revoke');assert.ok(app.requests.some(r=>r.path.endsWith('?destination=semester2')&&r.options.method==='DELETE'));
});
test('folder creation and filtering update the dropdown',async()=>{
 const app=await page();app.element('folderName').value='SW002';await app.trigger('createFolder','submit');assert.equal(app.element('folders').options.length,3);app.element('folderQuery').value='semester';await app.trigger('folderQuery','input');assert.deepEqual(app.element('folders').options.map(o=>o.value),['semester2']);
});
test('invitation uses the shared session and is removed from the address',async()=>{
 const app=await page('ADMIN','#invite='+'a'.repeat(64));assert.ok(app.requests.some(r=>r.path==='/invitations/accept'));assert.equal(app.historyCalls[0][2],'/admin.html');
});
test('invitation link can be delivered with a recipient-accessible origin',async()=>{
 const app=await page();app.element('invitationOrigin').value='https://website.example';app.element('inviteUsername').value='alice';await app.trigger('inviteAdmin','submit');assert.equal(app.element('invitationLink').value,'https://website.example/invite.html#invite='+'b'.repeat(64));await app.trigger('copyInvitation');assert.deepEqual(app.copied,[app.element('invitationLink').value]);
});

test('copy invitation falls back on HTTP when Clipboard API is unavailable',async()=>{const app=await page('ADMIN','',false);app.element('inviteUsername').value='alice';await app.trigger('inviteAdmin','submit');await app.trigger('copyInvitation');assert.deepEqual(app.copied,[app.element('invitationLink').value]);});

test('invitation has independent account lookup and an eligible-user dropdown',async()=>{const app=await page();app.element('inviteUsername').value='alice';await app.trigger('inviteSearch');assert.equal(app.element('inviteUsers').options[0].textContent,'alice');app.element('inviteUsername').value='nobody';await app.trigger('inviteSearch');assert.equal(app.element('inviteUsers').options.length,0);assert.match(app.notices.at(-1)[1],/create an account first/);});
test('reset links use a confirmation and the chosen account',async()=>{const app=await page();await app.trigger('resetPassword');assert.ok(app.requests.some(r=>r.path.endsWith('/password-reset')&&r.options.method==='POST'));assert.equal(app.element('resetLink').value,'https://mmiv.example/reset-password.html#reset='+'c'.repeat(64));await app.trigger('copyReset');assert.equal(app.copied.at(-1),app.element('resetLink').value);});
test('deactivating an ordinary account retains it in the dropdown',async()=>{const app=await page();await app.trigger('deleteUser');assert.ok(app.confirmations[0][0].includes('uploaded files and audit history will remain'));assert.equal(app.element('users').options.length,1);assert.equal(app.element('deleteUser').disabled,true);});
test('another administrator cannot be deleted or reset from controls',async()=>{const app=await page('ADMIN','',true,'ADMIN',false);assert.equal(app.element('deleteUser').disabled,true);assert.equal(app.element('resetPassword').disabled,true);});
test('administrator self-deletion explicitly warns and returns to sign in',async()=>{const app=await page('ADMIN','',true,'ADMIN',true);assert.equal(app.element('deleteUser').disabled,false);await app.trigger('deleteUser');assert.match(app.confirmations[0][0],/last administrator/);assert.deepEqual(app.navigations,['/login.html']);});
