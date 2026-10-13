const {test}=require('node:test');const assert=require('node:assert/strict');const {readFileSync,readdirSync}=require('node:fs');const vm=require('node:vm');
test('PWA registration and worker behavior are commented out',()=>{
 let registrations=0;
 vm.runInNewContext(readFileSync('services/transfer-service/src/main/resources/static/pwa.js','utf8'),{navigator:{serviceWorker:{register(){registrations++;}}},window:{isSecureContext:true}});
 assert.equal(registrations,0);
 let listeners=0;vm.runInNewContext(readFileSync('services/transfer-service/src/main/resources/static/sw.js','utf8'),{self:{addEventListener(){listeners++;}}});assert.equal(listeners,0);
});
test('pages do not load PWA registration or installation manifests',()=>{
 for(const service of ['transfer-service','user-access-service']){
  const dir=`services/${service}/src/main/resources/static`;
  for(const file of readdirSync(dir).filter(f=>f.endsWith('.html'))){const html=readFileSync(dir+'/'+file,'utf8').replace(/<!--[\s\S]*?-->/g,'');assert.doesNotMatch(html,/<link[^>]+rel="manifest"/);assert.doesNotMatch(html,/<script[^>]+src="\/pwa.js"/);}
 }
});
