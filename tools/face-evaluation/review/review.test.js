'use strict';
const test=require('node:test'), assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),{webcrypto,createHash}=require('node:crypto');
const C=require('./core.js');
function file(ms=1) {
 const data={schema:1,samples:[{id:'evaluation-0123456789abcdef',sha256:'a'.repeat(64),width:1,height:1,
  results:Object.fromEntries(C.detectors.map(d=>[d,{status:'SUCCESS',boxes:[],ms}]))}]};
 const bytes=new TextEncoder().encode(JSON.stringify(data));
 return {size:bytes.length,arrayBuffer:async()=>bytes.buffer,key:'face-review-v1-'+createHash('sha256').update(bytes).digest('hex')};
}
function deferred() {let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};}
function setup() {
 const els=new Map(),storage=new Map(),writes=[];
 const el=id=>{if(!els.has(id))els.set(id,{value:id==='detector'?'mlkit-accurate':'',textContent:'',disabled:false,replaceChildren(){}});return els.get(id);};
 const ctx=vm.createContext({document:{getElementById:el},FaceReview:C,crypto:webcrypto,TextDecoder,Uint8Array,Map,
  localStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>{writes.push(k);storage.set(k,v);}}});
 vm.runInContext(fs.readFileSync(require.resolve('./review.js'),'utf8')+'\nglobalThis.inspect=()=>({data,key,labels,verified});',ctx);
 return {ctx,el,storage,writes,change:f=>el('dataset').onchange({target:{files:f?[f]:[]}})};
}
function assertLatest(h,f,ms) {
 const state=h.ctx.inspect();assert.equal(state.key,f.key);
 assert.equal(state.data.samples[0].results['mlkit-accurate'].ms,ms);
}
function assertEmpty(h) {
 const state=h.ctx.inspect();assert.equal(state.data,null);assert.equal(state.key,'');
 assert.equal(Object.keys(state.labels).length,0);assert.equal(state.verified,false);
 for(const id of ['save','clear','prev','next','export']) assert.equal(h.el(id).disabled,true,id);
}
test('new JSON selection immediately disables old save clear and export without touching storage',async()=>{
 const h=setup(),a=file();await h.change(a);vm.runInContext('verified=true',h.ctx);
 const wait=deferred(),pending=h.change({size:1,arrayBuffer:()=>wait.promise});assertEmpty(h);
 h.el('save').onclick();h.el('clear').onclick();h.el('export').onclick();assert.equal(h.writes.length,0);
 wait.resolve(await file(2).arrayBuffer());await pending;
});
test('late old read success cannot replace same-image new run key or labels',async()=>{
 const h=setup(),wait=deferred(),b=file(2);
 const saved={'evaluation-0123456789abcdef':{'mlkit-accurate':{missed:3,falseIndices:[]}}};h.storage.set(b.key,JSON.stringify(saved));
 const old=h.change({size:1,arrayBuffer:()=>wait.promise});await h.change(b);
 wait.resolve(await file(1).arrayBuffer());await old;assertLatest(h,b,2);
 assert.equal(h.ctx.inspect().labels['evaluation-0123456789abcdef']['mlkit-accurate'].missed,3);assert.equal(h.writes.length,0);
});
test('late old read failure cannot clear new run or overwrite status',async()=>{
 const h=setup(),wait=deferred(),b=file(2);const old=h.change({size:1,arrayBuffer:()=>wait.promise});
 await h.change(b);const status=h.el('status').textContent;wait.reject(Error('old failure'));await old;
 assertLatest(h,b,2);assert.equal(h.el('status').textContent,status);
});
for(const outcome of ['success','failure']) test(`late old digest ${outcome} cannot replace new run`,async()=>{
 const h=setup(),wait=deferred(),a=file(1),b=file(2);let calls=0;
 h.ctx.crypto={subtle:{digest:(...args)=>++calls===1?wait.promise:webcrypto.subtle.digest(...args)}};
 const old=h.change(a);await new Promise(resolve=>setImmediate(resolve));assert.equal(calls,1);
 await h.change(b);const status=h.el('status').textContent;
 if(outcome==='success') wait.resolve(await webcrypto.subtle.digest('SHA-256',await a.arrayBuffer()));else wait.reject(Error('old digest failure'));
 await old;assertLatest(h,b,2);assert.equal(h.el('status').textContent,status);
});
test('empty selection invalidates pending read and preserves stored labels',async()=>{
 const h=setup(),wait=deferred(),a=file();h.storage.set(a.key,'{}');
 const old=h.change({size:1,arrayBuffer:()=>wait.promise});await h.change(null);const status=h.el('status').textContent;
 wait.resolve(await a.arrayBuffer());await old;assertEmpty(h);assert.equal(h.el('status').textContent,status);
 assert.equal(h.storage.get(a.key),'{}');assert.equal(h.writes.length,0);
});
test('empty selection invalidates pending digest',async()=>{
 const h=setup(),wait=deferred(),a=file();h.ctx.crypto={subtle:{digest:()=>wait.promise}};
 const old=h.change(a);await new Promise(resolve=>setImmediate(resolve));await h.change(null);
 wait.resolve(await webcrypto.subtle.digest('SHA-256',await a.arrayBuffer()));await old;assertEmpty(h);
});
test('latest invalid JSON clears old data and allows a fresh valid selection',async()=>{
 const h=setup(),b=file(2);await h.change(file());
 const bytes=new TextEncoder().encode('{}');await h.change({size:2,arrayBuffer:async()=>bytes.buffer});assertEmpty(h);
 await h.change(b);assertLatest(h,b,2);
});
