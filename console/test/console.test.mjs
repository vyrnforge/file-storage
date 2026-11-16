import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { client, allowedPath, projectFile, projectBackend, TENANT } from '../src/api.mjs';
import { createServer, validateOrigin, PREVIEW_TENANT } from '../dev-server.mjs';
async function listen(server){await new Promise(r=>server.listen(0,'127.0.0.1',r));return 'http://127.0.0.1:'+server.address().port;}
test('client uses exact same-origin routes and tenant scope; no tenant header for platform backend admin',async()=>{
  const seen=[];const c=client({token:'test-access-token',tenant:PREVIEW_TENANT,fetcher:async(url,options)=>{seen.push({url,options});return new Response('{}',{headers:{'content-type':'application/json'}});}});
  await c.get('/v1/files',{limit:50});await c.get('/v1/admin/backends');
  assert.equal(seen[0].url,'/api/v1/files?limit=50');assert.equal(seen[0].options.headers['X-Tenant-Id'],PREVIEW_TENANT);
  assert.equal(seen[1].options.headers['X-Tenant-Id'],undefined);assert.equal(seen[0].options.headers.Authorization,'Bearer test-access-token');
  assert.equal(seen[0].options.redirect,'error');await assert.rejects(c.get('https://example.com/v1/files'));assert.equal(allowedPath('/v1/uploads'),false);
});
test('logical projections omit credentials and private placement',()=>{
  assert.deepEqual(projectFile({name:'sample',versionState:'AVAILABLE',declaredSize:5,declaredMimeType:'text/plain',file:{fileId:'id'},bucket:'private',key:'secret'}),['sample','AVAILABLE','5','text/plain','id']);
  assert.deepEqual(projectBackend({provider:'OSS',backendId:'id',latestRevision:1,activeRevision:null,secret:'no'}),['OSS','id','1','—']);
});
test('local origin validation and tenant validation',()=>{
  assert.equal(validateOrigin('http://127.0.0.1:8080'),'http://127.0.0.1:8080');assert.throws(()=>validateOrigin('https://example.com'));assert.throws(()=>validateOrigin('http://127.0.0.1:8080/path'));assert.ok(TENANT.test(PREVIEW_TENANT));assert.throws(()=>client({token:'x',tenant:'bad'}));
});
test('fixture is explicit read-only and rejects mismatched tenant, writes and private paths',async t=>{
  const s=await createServer();t.after(()=>s.close());const base=await listen(s);
  assert.equal((await (await fetch(base+'/config')).json()).preview,true);
  assert.equal((await fetch(base+'/api/v1/files',{headers:{'X-Tenant-Id':PREVIEW_TENANT}})).status,200);
  assert.equal((await fetch(base+'/api/v1/files',{headers:{'X-Tenant-Id':'wrong'}})).status,403);
  assert.equal((await fetch(base+'/api/v1/files',{method:'POST'})).status,405);
  assert.equal((await fetch(base+'/api/v1/uploads')).status,404);
  assert.equal((await fetch(base+'/package.json')).status,404);
});
test('API mode forwards only authorized GET to fixed loopback backend and preserves denial',async t=>{
  let seen;const upstream=http.createServer((req,res)=>{seen=req.headers;res.writeHead(403,{'Content-Type':'application/json'});res.end('{"secret":"not returned"}');});t.after(()=>upstream.close());const origin=await listen(upstream);
  const s=await createServer({mode:'api',apiOrigin:origin});t.after(()=>s.close());const base=await listen(s);
  assert.equal((await fetch(base+'/api/v1/files')).status,401);
  const res=await fetch(base+'/api/v1/files',{headers:{Authorization:'Bearer test-access-token','X-Tenant-Id':PREVIEW_TENANT,Cookie:'private=1','X-Other':'not forwarded'}});
  assert.equal(res.status,403);assert.deepEqual(await res.json(),{code:'REQUEST_FAILED'});assert.equal(seen.authorization,'Bearer test-access-token');assert.equal(seen.cookie,undefined);assert.equal(seen['x-other'],undefined);
});
