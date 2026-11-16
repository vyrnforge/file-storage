import { client, routes, ApiError, projectFile, projectBackend, projectLocation, projectPolicy, TENANT } from './api.mjs';
const $ = id => document.getElementById(id);
const create = (tag, text, cls) => { const el = document.createElement(tag); if(text !== undefined) el.textContent = String(text); if(cls) el.className = cls; return el; };
const btn = (text, onClick) => { const b = create('button',text); b.dataset.vfButton=''; b.dataset.vfVariant='secondary'; b.type='button'; b.addEventListener('click',onClick); return b; };
let api = null, route = 'overview', preview = false, epoch = 0, pending = null, scopes = new Set(), currentQuery = {};
const titles = {overview:'Overview',files:'Files',deleted:'Deleted files',backends:'Storage backends',policies:'Routing policies'};
function disconnect() {
  epoch++; pending?.abort(); api = null; scopes = new Set(); currentQuery = {}; $('token').value = ''; $('tenant').value = '';
  $('content').replaceChildren(); $('status').textContent=''; $('workspace').hidden=true; $('nav').hidden=true; $('signout').hidden=true; $('connect').hidden=false;
}
function message(error) {
  if (error instanceof ApiError) {
    const map = {401:'Authentication failed. Reconnect with an authorized access token.',403:'Access denied by File Storage.',404:'Resource not found or not visible.',409:'Conflict. Refresh the current state.',503:'File Storage is not ready.'};
    return (map[error.status] || 'Request could not be completed.') + (error.correlation ? ' Correlation: '+error.correlation : '');
  }
  return 'Request could not be completed. Check the local service and try again.';
}
function panel(title, child) { const div=create('section',undefined,'panel'); div.append(create('h2',title),child); return div; }
function table(headers, rows) {
  const wrap=create('div',undefined,'table-scroll'), t=create('table'), head=create('thead'), h=create('tr');
  headers.forEach(x=>{ const th=create('th',x); th.scope='col'; h.append(th); }); head.append(h); t.append(head);
  const body=create('tbody'); rows.forEach(row=>{ const tr=create('tr'); row.forEach(value=>{ const td=create('td'); if(value instanceof Node) td.append(value); else td.textContent=value; tr.append(td); }); body.append(tr); });
  t.append(body); wrap.append(t); if(!rows.length) wrap.append(create('p','No visible records.','muted')); return wrap;
}
async function select(name, query={}) {
  if(!api || !scopes.has(name)) return;
  const mine=++epoch; pending?.abort(); pending=new AbortController();
  route=name; currentQuery=query; $('page-title').textContent=titles[name]; $('content').replaceChildren(); $('status').textContent='Loading…'; $('refresh').disabled=true;
  document.querySelectorAll('[data-route]').forEach(b=>{ if(b.dataset.route===name)b.setAttribute('aria-current','page'); else b.removeAttribute('aria-current'); });
  try {
    if(name==='policies') { renderPolicyForm(mine); return; }
    const result=await api.get(routes[name], query, pending.signal);
    if(mine!==epoch)return;
    if(name==='overview') {
      const states=result.states || []; const cards=create('div',undefined,'cards');
      for(const [label,value] of [['Visible files',states.reduce((n,r)=>n+BigInt(r.fileCount),0n)],['Available bytes',states.reduce((n,r)=>n+BigInt(r.availableBytes),0n)],['Quarantined',states.filter(r=>r.state==='QUARANTINED').reduce((n,r)=>n+BigInt(r.fileCount),0n)]]) {
        const card=create('div',undefined,'panel'); card.append(create('p',label,'muted'),create('p',value,'metric')); cards.append(card);
      }
      $('content').append(cards,panel('Visible usage by version state',table(['State','Files','Declared bytes','Available bytes'],states.map(r=>[r.state,r.fileCount,r.declaredBytes,r.availableBytes]))));
    } else if(name==='files'||name==='deleted') {
      const rows=result.items || []; const data=name==='deleted'?rows.map(r=>[...projectFile(r.metadata),r.deletedAt]):rows.map(projectFile);
      const headers=['Name','State','Bytes','MIME type','File ID']; if(name==='deleted')headers.push('Deleted at');
      $('content').append(panel(name==='deleted'?'Recoverable files':'Authorized file catalog',table(headers,data)));
      if(result.nextAfterFileId) $('content').append(btn('Next page',()=>select(name,{limit:50,afterFileId:result.nextAfterFileId})));
    } else {
      const rows=result; $('content').append(panel('Registered backends',table(['Provider','Backend ID','Latest revision','Active revision','Locations'],rows.map(r=>[...projectBackend(r),btn('View locations',()=>showLocations(r.backendId))]))));
    }
    $('status').textContent='Loaded from '+(preview?'isolated preview fixtures.':'File Storage API.');
  } catch(e) { if(mine===epoch && e.name!=='AbortError') { $('status').textContent=message(e); if(e.status===401) { api=null; scopes.clear(); $('content').replaceChildren(); $('nav').hidden=true; } } }
  finally { if(mine===epoch) $('refresh').disabled=false; }
}
async function showLocations(id) {
  const mine=++epoch; pending?.abort(); pending=new AbortController(); $('status').textContent='Loading locations…'; $('content').replaceChildren();
  try { const result=await api.get('/v1/admin/backends/'+encodeURIComponent(id)+'/locations',{limit:50},pending.signal); if(mine!==epoch)return; $('content').append(btn('Back to backends',()=>select('backends')),panel('Storage locations',table(['Location ID','Backend ID'],result.map(projectLocation)))); $('status').textContent='Loaded logical locations.'; }
  catch(e){if(mine===epoch)$('status').textContent=message(e);}
}
function renderPolicyForm(mine) {
  const form=create('form'); const app=create('input'), purpose=create('input'), result=create('div');
  app.id='policy-app';purpose.id='policy-purpose'; app.required=purpose.required=true; app.maxLength=purpose.maxLength=128;
  const labelA=create('label','Application');labelA.htmlFor=app.id; const labelP=create('label','Purpose');labelP.htmlFor=purpose.id;
  const submit=create('button','Read active policy');submit.type='submit';submit.dataset.vfButton='';submit.dataset.vfVariant='primary';
  form.append(labelA,app,labelP,purpose,submit); form.addEventListener('submit',async e=>{
    e.preventDefault(); if(mine!==epoch)return; result.replaceChildren(); $('status').textContent='Loading policy…'; submit.disabled=true;
    try { const p=await api.get(routes.policies,{application:app.value,purpose:purpose.value},pending.signal); if(mine!==epoch)return; result.append(panel('Active revision '+p.revision,table(['Location ID','Priority','Eligible'],projectPolicy(p)))); $('status').textContent='Loaded active policy.'; }
    catch(error){if(mine===epoch)$('status').textContent=message(error);}
    finally{submit.disabled=false;}
  });
  $('content').append(panel('Look up a tenant/application/purpose policy',form),result); $('status').textContent='Enter the exact policy scope. The API checks authorization.';
}
$('connect-form').addEventListener('submit',async e=>{
  e.preventDefault(); const tenant=$('tenant').value.trim(); if(!TENANT.test(tenant)){ $('tenant').setCustomValidity('Enter a UUIDv7 tenant ID.');$('tenant').reportValidity();return; }
  try{api=client({token:$('token').value.trim(),tenant,preview});}catch(error){$('token').setCustomValidity(error.message);$('token').reportValidity();return;}
  $('token').value=''; const mine=++epoch; pending?.abort(); pending=new AbortController();
  const submit=$('connect-form').querySelector('button');submit.disabled=true;
  // Successful server reads establish UI visibility; no JWT claim decoding or fabricated role grants.
  const names=['overview','files','deleted','backends'];
  const outcomes=await Promise.all(names.map(async name=>{try{await api.get(routes[name],{...(name==='overview'?{}:{limit:1})},pending.signal);return name;}catch{return null;}}));
  submit.disabled=false;if(mine!==epoch)return;
  scopes=new Set(outcomes.filter(Boolean)); if(scopes.has('backends')||scopes.has('overview')) scopes.add('policies'); // Scope lookup is separately server-authorized.
  if(!scopes.size){api=null;$('token').setCustomValidity('No authorized API reads succeeded. Check identity and service readiness.');$('token').reportValidity(); if(preview) $('tenant').reportValidity();return;}
  document.querySelectorAll('[data-route]').forEach(b=>b.hidden=!scopes.has(b.dataset.route));
  $('connect').hidden=true;$('workspace').hidden=false;$('nav').hidden=false;$('signout').hidden=false;select(scopes.has('overview')?'overview':[...scopes][0]);
});
$('tenant').addEventListener('input',()=>$('tenant').setCustomValidity(''));$('token').addEventListener('input',()=>$('token').setCustomValidity(''));
$('refresh').addEventListener('click',()=>select(route,currentQuery));$('signout').addEventListener('click',disconnect);
document.querySelectorAll('[data-route]').forEach(b=>b.addEventListener('click',()=>select(b.dataset.route)));
try { const res=await fetch('/config',{cache:'no-store'});if(!res.ok)throw new Error();const config=await res.json();preview=config.preview===true;$('mode').textContent=preview?'LOCAL PREVIEW · SYNTHETIC DATA':'LOCAL API · READ ONLY';$('token-field').hidden=preview;if(preview)$('tenant').value=config.previewTenant;$('token').required=!preview; }
catch { $('mode').textContent='LOCAL CONFIG UNAVAILABLE';$('connect-form').querySelector('button').disabled=true; }
