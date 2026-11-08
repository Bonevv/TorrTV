import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
const source=fs.readFileSync(new URL('../worker.mjs',import.meta.url),'utf8');
const context=vm.createContext({crypto:webcrypto,TextEncoder,Response,Request,URL,console});
vm.runInContext(source.replace('export default {','const worker = {')+';globalThis.api={cleanList,cleanSet,effective,worker,PAGE};',context,{timeout:2000});
const api=context.api;
const plain=x=>JSON.parse(JSON.stringify(x));
test('provider list accepts only supported IDs and preserves the all-off marker',()=>{
 assert.deepEqual(plain(api.cleanList(['NINE','NINE','TWO_K','https://evil.example','UNKNOWN','-'],'web_bg_providers')),['NINE','TWO_K','-']);
 assert.deepEqual(plain(api.cleanList(['https://site.example/movie/{tmdb}'],'web_movie')),['https://site.example/movie/{tmdb}']);
});
test('profile inheritance preserves existing credentials and provider overrides',()=>{
 const result=plain(api.effective({set:{tmdb_key:'fixture-key',web_bg_providers:['NINE']}},{set:{web_bg_providers:['-']}}));
 assert.equal(result.tmdb_key,'fixture-key');assert.deepEqual(result.web_bg_providers,['-']);
});
test('old settings retain their prior wire format with an optional new field',()=>{
 const value=plain(api.cleanSet({web_movie:['https://site.example/{tmdb}'],web_bg_providers:['ZENO'],unknown:'x'}));
 assert.deepEqual(value,{web_movie:['https://site.example/{tmdb}'],web_bg_providers:['ZENO']});
});
test('sync returns profile configuration and accepts its revision without changing login',async()=>{
 const db=new Map([['p:123',JSON.stringify({n:'123',pin:'1234',on:true,name:'Test',set:{web_bg_providers:['NINE']}})]]);
 const env={DB:{get:async (k,type)=>{const value=db.get(k)||null;return type==='json' || type?.type==='json' ? (value ? JSON.parse(value) : null) : value},put:async(k,v)=>db.set(k,v),delete:async k=>db.delete(k)}};
 const request=body=>new Request('https://worker.example/api/sync',{method:'POST',body:JSON.stringify(body),headers:{'Content-Type':'application/json'}});
 let response=await api.worker.fetch(request({n:'123',p:'1234',v:'fixture'}),env);assert.equal(response.status,200);
 let body=await response.json();assert.equal(body.ok,true);assert.deepEqual(body.set.web_bg_providers,['NINE']);
 response=await api.worker.fetch(request({n:'123',p:'1234',have:body.rev,v:'fixture'}),env);
 body=await response.json();assert.equal(body.changed,false);assert.equal(body.set,undefined);
});
test('admin page contains the new provider controls',()=>{
 assert.ok(api.PAGE.includes('bgProviderCtl'));assert.ok(api.PAGE.includes('web_bg_providers'));
});

test('all inline admin scripts have valid JavaScript syntax',()=>{
 const scripts=[...api.PAGE.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)];
 assert.ok(scripts.length>0);
 for(const match of scripts) new vm.Script(match[1]);
});
