const {test}=require('node:test');
const assert=require('node:assert/strict');
const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const source=readFileSync('services/transfer-service/src/main/resources/static/admin.js','utf8');
async function page(role='ADMIN',hash=''){
 const nodes=new Map(),requests=[],historyCalls=[],copied=[];
 const element=id=>{if(!nodes.has(id))nodes.set(id,{value:'',textContent:'',hidden:id==='management',disabled:false,options:[],listeners:{},addEventListener(event,fn){this.listeners[event]=fn;},replaceChildren(){this.options=[];this.value='';}});return nodes.get(id);};
 const user={userId:'11111111-1111-4111-8111-111111111111',username:'alice',active:true,destinations:[]};
 let folders=['SW001','semester2'];
 const context={document:{getElementById:element,querySelectorAll:()=>['grant','revoke','copyInvitation'].map(element)},location:{origin:'https://mmiv.example',hash,pathname:'/admin.html'},history:{replaceState(...args){historyCalls.push(args);}},navigator:{clipboard:{async writeText(text){copied.push(text);}}},URL,URLSearchParams,Error,mmivAuth:{ready:Promise.resolve()},mmivPortal:{option(select,value,text){select.options.push({value,textContent:text});select.value ||= value;},async api(path,options){requests.push({path,options});if(path==='/me')return {role};if(path==='/admin/folders'){if(options){folders.push(JSON.parse(options.body).name);}return folders;}if(path.startsWith('/admin/users?q='))return [user];if(path.includes('/permissions')){user.destinations=options.method==='DELETE'?[]:[JSON.parse(options.body)];return {...user};}if(path==='/admin/invitations')return {token:'b'.repeat(64)};if(path==='/invitations/accept')return {};throw new Error('Unexpected request '+path);}}};
 vm.runInNewContext(source,context);const flush=async()=>{for(let i=0;i<10;i++)await new Promise(setImmediate);};await flush();
 return {element,requests,historyCalls,copied,async trigger(id,event='click'){element(id).listeners[event]({preventDefault(){}});await flush();}};
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
 const app=await page();app.element('invitationOrigin').value='https://website.example';app.element('inviteUsername').value='alice';await app.trigger('inviteAdmin','submit');assert.equal(app.element('invitationLink').textContent,'https://website.example/invite.html#invite='+'b'.repeat(64));await app.trigger('copyInvitation');assert.deepEqual(app.copied,[app.element('invitationLink').textContent]);
});
