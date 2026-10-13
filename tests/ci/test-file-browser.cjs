const {test}=require('node:test');const assert=require('node:assert/strict');const {readFileSync}=require('node:fs');const vm=require('node:vm');
const source=readFileSync('services/transfer-service/src/main/resources/static/file-browser.js','utf8');
const html=readFileSync('services/transfer-service/src/main/resources/static/files.html','utf8');
async function browser(destinations,denied=false){
 const nodes=new Map(),requests=[];function node(tag='DIV'){return {tagName:tag,children:[],options:[],value:'',hidden:false,disabled:false,textContent:'',listeners:{},setAttribute(){},addEventListener(event,fn){this.listeners[event]=fn;},append(...children){this.children.push(...children);},appendChild(child){this.children.push(child);},replaceChildren(){this.children=[];}};}
 const element=id=>{if(!nodes.has(id))nodes.set(id,node());return nodes.get(id);};
 vm.runInNewContext(source,{document:{getElementById:element,querySelector:s=>element(s.slice(1)),createElement:tag=>node(tag.toUpperCase())},location:{search:'?folder=forbidden'},URLSearchParams,encodeURIComponent,mmivPortal:{profile:Promise.resolve({role:"USER",destinations}),option(select,value,label){select.options.push({value,textContent:label});select.value ||= value;},async api(path){requests.push(path);if(denied)throw new Error('No access to folder');return {files:[{fileId:'safe-file-id',name:'docs/report.pdf',sizeBytes:4}],nextCursor:null};}},mmivPreview:{close(){},open(id,name){requests.push('preview:'+name);}},mmivAuth:{async fetch(path){requests.push(path);return {ok:true,status:200,headers:{get:key=>key==='Content-Type'?'application/pdf':'4'}};}}});
 const flush=async()=>{for(let i=0;i<8;i++)await new Promise(setImmediate);};await flush();return {element,requests,flush};
}
test('folder browser groups paths, displays basenames and uses separate preview buttons',async()=>{
 const app=await browser([{destination:'SW001',permission:'READ'}]);assert.deepEqual(app.requests,['/files?folder=SW001']);let row=app.element('files').children[0];assert.equal(row.children[0].children[0].textContent,'📁 docs');row.children[0].children[0].listeners.click();row=app.element('files').children[0];assert.equal(row.children[0].textContent,'report.pdf');assert.equal(row.children[2].children[1].href,'/api/v1/files/safe-file-id/download');row.children[2].children[0].listeners.click();assert.ok(app.requests.includes('preview:report.pdf'));assert.ok(!html.includes('id="fileId"'));
});
test('account without grants shows no access and does not list storage',async()=>{
 const app=await browser([]);assert.deepEqual(app.requests,[]);assert.equal(app.element('status').textContent,'No access to folder');
});
test('revoked folder permission clears the file list',async()=>{
 const app=await browser([{destination:'SW001',permission:'READ'}],true);assert.equal(app.element('files').children.length,0);assert.equal(app.element('status').textContent,'No access to folder');
});
