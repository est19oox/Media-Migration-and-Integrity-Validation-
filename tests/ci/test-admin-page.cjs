const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const source = readFileSync('services/user-access-service/src/main/resources/static/admin.js', 'utf8');
function page(responses, hash='') {
  const nodes = new Map();
  const element = id => {
    if (!nodes.has(id)) nodes.set(id, {value:'', textContent:'', hidden:false, disabled:false, listeners:{},
      addEventListener(event,callback){this.listeners[event]=callback;}});
    return nodes.get(id);
  };
  const requests=[]; const copied=[]; const historyCalls=[];
  const context={document:{getElementById:element,querySelectorAll:()=>['grant','revoke','logout','copyInvitation'].map(element)},
    location:{origin:'https://mmiv.example',hash,pathname:'/admin.html'},history:{replaceState(...args){historyCalls.push(args);}},
    navigator:{clipboard:{async writeText(value){copied.push(value);}}},URL,URLSearchParams,AbortController,setTimeout,clearTimeout,
    localStorage:{setItem(){throw new Error('Secrets must not enter persistent storage');}},
    sessionStorage:{setItem(){throw new Error('Secrets must not enter persistent storage');}},
    async fetch(path,options){requests.push({path,options});const response=responses.shift();assert.ok(response,'Unexpected request');return {ok:response.status===200,status:response.status,async json(){return response.body;}};}};
  vm.runInNewContext(source,context);
  element('username').value='admin';element('password').value='private-password';
  async function trigger(id,event='click') {
    element(id).listeners[event]({preventDefault(){}});
    for(let i=0;i<8;i++) await new Promise(resolve=>setImmediate(resolve));
  }
  return {element,requests,copied,historyCalls,trigger};
}
const login={status:200,body:{accessToken:'synthetic-jwt',tokenType:'Bearer'}};
const identity={status:200,body:{userId:'admin-id',destination:'landing'}};
test('administrator signs in with password, token stays in memory, logout hides data',async()=>{
 const app=page([login,identity]);await app.trigger('adminLogin','submit');
 assert.equal(app.requests[0].path,'/user-access/api/auth/login');
 assert.equal(JSON.parse(app.requests[0].options.body).password,'private-password');
 assert.equal(app.requests[1].options.headers.Authorization,'Bearer synthetic-jwt');
 assert.equal(app.requests[1].options.credentials,'omit');
 assert.equal(app.element('password').value,'');assert.equal(app.element('management').hidden,false);
 await app.trigger('logout');assert.equal(app.element('management').hidden,true);
});
test('ordinary users remain outside administration',async()=>{
 const app=page([login,{status:403}]);await app.trigger('adminLogin','submit');
 assert.equal(app.element('management').hidden,true);assert.match(app.element('message').textContent,/invalid or expired/);
});
test('invitation is removed from address and redeemed only after password login',async()=>{
 const token='a'.repeat(64);const app=page([login,identity],'#invite='+token);
 assert.equal(app.historyCalls[0][2],'/admin.html');await app.trigger('adminLogin','submit');
 assert.equal(app.requests[1].path,'/user-access/api/admin/invitations/accept');
 assert.equal(JSON.parse(app.requests[1].options.body).token,token);
 assert.equal(app.element('management').hidden,false);
 assert.ok(app.requests.every(request=>!request.path.includes(token)));
});
test('permission grant uses the looked-up account and configured destination',async()=>{
 const user={userId:'target-id',username:'outside',active:true,destinations:[]};
 const app=page([login,identity,{status:200,body:user},{status:200,body:user}]);
 await app.trigger('adminLogin','submit');app.element('targetUsername').value='outside';await app.trigger('userLookup','submit');
 app.element('permission').value='WRITE';await app.trigger('grant');
 assert.equal(app.requests[3].path,'/user-access/api/admin/users/target-id/destinations');
 assert.deepEqual(JSON.parse(app.requests[3].options.body),{destination:'landing',permission:'WRITE'});
});
test('invitation link can be copied for delivery without sending email automatically',async()=>{
 const token='b'.repeat(64);const app=page([login,identity,{status:200,body:{token,expiresAt:'2026-10-13T00:00:00Z'}}]);
 await app.trigger('adminLogin','submit');app.element('inviteUsername').value='outside';await app.trigger('inviteAdmin','submit');await app.trigger('copyInvitation');
 assert.equal(app.copied[0],'https://mmiv.example/admin.html#invite='+token);
 assert.equal(app.requests.length,3);
});

test('operator using a local tunnel can generate a recipient-accessible website link',async()=>{
 const token='c'.repeat(64);const app=page([login,identity,{status:200,body:{token,expiresAt:'2026-10-13T00:00:00Z'}}]);
 await app.trigger('adminLogin','submit');app.element('inviteUsername').value='outside';
 app.element('invitationOrigin').value='https://public.mmiv.example';await app.trigger('inviteAdmin','submit');
 assert.equal(app.element('invitationLink').textContent,'https://public.mmiv.example/admin.html#invite='+token);
});
