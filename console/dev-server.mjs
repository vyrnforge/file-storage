// Loopback-only development server. No deployment, database provisioning or auth bypass.
import http from 'node:http';
import { readFile, realpath, stat } from 'node:fs/promises';
import { resolve, dirname, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { allowedPath, TENANT } from './src/api.mjs';
const ROOT=dirname(fileURLToPath(import.meta.url));
export const UI_REVISION='90308f0be32e8263ac829b1909190990384ec3c3';
export const PREVIEW_TENANT='019abcde-0000-7000-8000-000000000001';
const BACKEND='019abcde-0000-7000-8000-000000000002', LOCATION='019abcde-0000-7000-8000-000000000003';
const MAX=1024*1024;
function json(res,status,value,headers={}) { res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store',...headers}); res.end(JSON.stringify(value)); }
export function fixture(path,tenant,query) {
  if(!path.startsWith('/v1/admin/backends') && tenant!==PREVIEW_TENANT) return [403,{code:'ACCESS_DENIED'}];
  if(path==='/v1/usage') return [200,{states:[{state:'AVAILABLE',fileCount:2,declaredBytes:'8192',availableBytes:'8192'},{state:'PENDING',fileCount:0,declaredBytes:'0',availableBytes:'0'},{state:'VERIFYING',fileCount:0,declaredBytes:'0',availableBytes:'0'},{state:'QUARANTINED',fileCount:0,declaredBytes:'0',availableBytes:'0'},{state:'FAILED',fileCount:0,declaredBytes:'0',availableBytes:'0'}]}];
  if(path==='/v1/files') return [200,{items:[{name:'example-invoice.pdf',versionState:'AVAILABLE',declaredSize:4096,declaredMimeType:'application/pdf',file:{fileId:'fixture-invoice'}},{name:'example-asset.png',versionState:'AVAILABLE',declaredSize:4096,declaredMimeType:'image/png',file:{fileId:'fixture-image'}}],nextAfterFileId:null}];
  if(path==='/v1/deleted-files') return [200,{items:[],nextAfterFileId:null}];
  if(path==='/v1/admin/backends') return [200,[{provider:'S3',backendId:BACKEND,latestRevision:1,activeRevision:1}]];
  if(path==='/v1/admin/backends/'+BACKEND+'/locations') return [200,[{locationId:LOCATION,backendId:BACKEND}]];
  if(path==='/v1/admin/policies/active' && query.get('application')==='demo' && query.get('purpose')==='attachments') return [200,{revision:1,rules:[{locationId:LOCATION,priority:0,eligible:true}]}];
  return [404,{code:'NOT_FOUND'}];
}

// Only fields used by the read-only console cross the local proxy boundary.
function scalar(value) {
  if (value===null || ['string','number','boolean'].includes(typeof value)) return value;
  throw new Error('Unexpected scalar');
}
function pick(value, keys) {
  if(!value || typeof value!=='object' || Array.isArray(value)) throw new Error('Invalid response');
  return Object.fromEntries(keys.filter(key=>Object.hasOwn(value,key)).map(key=>[key,scalar(value[key])]));
}
function rows(value, project) {
  if(!Array.isArray(value)||value.length>100)throw new Error('Invalid rows');
  return value.map(project);
}
function metadata(row) {
  return {...pick(row,['name','versionState','declaredSize','declaredMimeType']),file:pick(row.file,['fileId'])};
}
export function sanitize(path,value) {
  if(path==='/v1/usage')return {states:rows(value.states,r=>pick(r,['state','fileCount','declaredBytes','availableBytes']))};
  if(path==='/v1/files')return {items:rows(value.items,metadata),...pick(value,['nextAfterFileId'])};
  if(path==='/v1/deleted-files')return {items:rows(value.items,r=>({metadata:metadata(r.metadata),...pick(r,['deletedAt'])})),...pick(value,['nextAfterFileId'])};
  if(path==='/v1/admin/backends')return rows(value,r=>pick(r,['provider','backendId','latestRevision','activeRevision']));
  if(path.endsWith('/locations'))return rows(value,r=>pick(r,['locationId','backendId']));
  if(path==='/v1/admin/policies/active')return {...pick(value,['revision']),rules:rows(value.rules,r=>pick(r,['locationId','priority','eligible']))};
  throw new Error('Unsupported response');
}

export function validateOrigin(value) {
  const u=new URL(value);
  if(u.protocol!=='http:'||u.hostname!=='127.0.0.1'||u.username||u.password||u.pathname!=='/'||u.search||u.hash||!u.port)throw new Error('API origin must be explicit http://127.0.0.1:<port>');
  return u.origin;
}
async function sharedRoot(root) {
  if(!root)return null;
  const dir=await realpath(root);
  const head=execFileSync('git',['-C',dir,'rev-parse','HEAD'],{encoding:'utf8'}).trim();
  const dirty=execFileSync('git',['-C',dir,'status','--porcelain','--untracked-files=no'],{encoding:'utf8'}).trim();
  if(head!==UI_REVISION||dirty)throw new Error('Shared UI checkout must match the reviewed revision and have no tracked edits');
  const dist=await realpath(resolve(dir,'packages/web/dist'));
  await stat(resolve(dist,'styles.css'));return dist;
}
export async function createServer({mode='preview',apiOrigin,uiRoot}={}) {
  if(!['preview','api'].includes(mode))throw new Error('Unknown mode');
  if(mode==='api'&&!apiOrigin)throw new Error('API mode requires an explicit loopback origin');
  const origin=mode==='api'?validateOrigin(apiOrigin):null, shared=await sharedRoot(uiRoot);
  return http.createServer(async(req,res)=>{
    res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','no-referrer');res.setHeader('Cache-Control','no-store');
    res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    try{
      if(!/^127\.0\.0\.1(?::\d+)?$/.test(req.headers.host||'') || (req.headers.origin && req.headers.origin!=='http://'+req.headers.host))return json(res,403,{code:'DENIED'});
      if(req.method!=='GET')return json(res,405,{code:'METHOD_NOT_ALLOWED'});
      const u=new URL(req.url,'http://127.0.0.1');if(u.origin!=='http://127.0.0.1')return json(res,400,{code:'INVALID'});
      if(u.pathname==='/config')return json(res,200,{preview:mode==='preview',previewTenant:PREVIEW_TENANT,sharedUi:!!shared});
      if(u.pathname.startsWith('/api/')){
        const path=u.pathname.slice(4);if(!allowedPath(path))return json(res,404,{code:'NOT_FOUND'});
        if(mode==='preview'){const [status,data]=fixture(path,req.headers['x-tenant-id'],u.searchParams);return json(res,status,data);}
        const auth=req.headers.authorization;
        if(typeof auth!=='string'||!/^Bearer [^\s]{1,8192}$/.test(auth))return json(res,401,{code:'AUTHENTICATION_FAILED'});
        const headers={Accept:'application/json',Authorization:auth};
        if(!path.startsWith('/v1/admin/backends')){if(!TENANT.test(req.headers['x-tenant-id']||''))return json(res,400,{code:'INVALID'});headers['X-Tenant-Id']=req.headers['x-tenant-id'];}
        if(/^[0-9a-f-]{36}$/i.test(req.headers['x-correlation-id']||''))headers['X-Correlation-Id']=req.headers['x-correlation-id'];
        const upstream=await fetch(origin+path+u.search,{headers,redirect:'error',signal:AbortSignal.timeout(10000)});
        const corr=upstream.headers.get('X-Correlation-Id');const responseHeaders=corr&&/^[0-9a-f-]{36}$/i.test(corr)?{'X-Correlation-Id':corr}:{};
        if(!upstream.ok){await upstream.body?.cancel();return json(res,upstream.status,{code:'REQUEST_FAILED'},responseHeaders);}
        if(!upstream.headers.get('content-type')?.includes('application/json'))return json(res,502,{code:'UNAVAILABLE'});
        const chunks=[];let size=0;for await(const chunk of upstream.body){size+=chunk.length;if(size>MAX)throw new Error('response budget');chunks.push(chunk);}
        return json(res,200,sanitize(path,JSON.parse(Buffer.concat(chunks).toString('utf8'))),responseHeaders);
      }
      if(u.pathname==='/shared/styles.css'&&!shared){res.writeHead(200,{'Content-Type':'text/css'});return res.end('/* Local preview: shared UI build not supplied. */');}
      const rel=u.pathname==='/'?'index.html':u.pathname.slice(1);
      let file,base;
      if(rel.startsWith('shared/')&&shared){base=shared;file=resolve(base,rel.slice(7));if(extname(file)!=='.css')return json(res,404,{code:'NOT_FOUND'});}
      else {base=ROOT; if(!['index.html','styles.css','src/app.mjs','src/api.mjs'].includes(rel))return json(res,404,{code:'NOT_FOUND'});file=resolve(base,rel);}
      const real=await realpath(file);if(!real.startsWith(base+sep))return json(res,404,{code:'NOT_FOUND'});
      const type=extname(real)==='.html'?'text/html':extname(real)==='.css'?'text/css':'text/javascript';
      res.writeHead(200,{'Content-Type':type+'; charset=utf-8'});res.end(await readFile(real));
    }catch{if(!res.headersSent)json(res,503,{code:'UNAVAILABLE'});else res.end();}
  });
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const mode=process.env.FS_CONSOLE_MODE||'preview';
  const port=Number(process.env.FS_CONSOLE_PORT||4173);
  if(!Number.isInteger(port)||port<1024||port>65535)throw new Error('Invalid console port');
  const server=await createServer({mode,apiOrigin:process.env.FS_API_ORIGIN,uiRoot:process.env.VF_UI_ROOT});
  server.listen(port,'127.0.0.1',()=>console.log('File Storage console ('+mode+'): http://127.0.0.1:'+port));
}
