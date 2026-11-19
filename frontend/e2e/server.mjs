/** Controlled backend for the real session protocol. It never logs token/cookie values. */
import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
import { URL } from 'node:url';
const root=resolve('dist-e2e');
let sessions=new Map(),access=new Map(),active=0,maxWriters=0,counts={},control={};
let gates=[];
const users=Object.fromEntries(['alice','bob'].map(id=>[id,{id,email:id+'@example.com',displayName:id,roles:['ROLE_USER']}]));
function release(){for(const done of gates)done();gates=[];}
function json(res,status,payload){res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(payload));}
function error(res,status,code){json(res,status,{timestamp:new Date().toISOString(),status,code,message:'Fixture response'});}
function cookie(req){const found=(req.headers.cookie??'').split(';').map(v=>v.trim()).filter(v=>v.startsWith('vod_refresh='));
  return found.length===1?found[0].slice('vod_refresh='.length):null;}
function setCookie(res,value,age=604800){res.setHeader('Set-Cookie',`vod_refresh=${value}; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=${age}`);}
function issue(req,res,id){const old=cookie(req);if(old)sessions.delete(old);
  const refresh='refresh-cookie-test-'+randomUUID(), token='access-cookie-test-'+randomUUID();
  sessions.set(refresh,id);access.set(token,id);setCookie(res,refresh);
  json(res,200,{user:users[id],accessToken:token,expiresInSeconds:900});}
async function body(req){let raw='';for await(const chunk of req)raw+=chunk;return raw?JSON.parse(raw):{};}
async function waitGate(res){await new Promise(done=>{gates.push(done);res.once('close',done);});}
const server=http.createServer(async(req,res)=>{
  try {
    const path=new URL(req.url,'http://localhost').pathname;
    if(path==='/__test/state')return json(res,200,{active,maxWriters,counts});
    if(path==='/__test/reset'){release();sessions=new Map();access=new Map();active=0;maxWriters=0;counts={};control={};return json(res,200,{});}
    if(path==='/__test/control'){Object.assign(control,await body(req));if(control.release){release();control.hold=null;control.holdRead=false;control.release=false;}return json(res,200,{});}
    if(path==='/api/v1/auth/probe')return json(res,200,{userId:sessions.get(cookie(req))??null});
    if(path==='/api/v1/users/me'){
      const id=access.get((req.headers.authorization??'').replace('Bearer ',''));
      if(control.holdRead)await waitGate(res);
      return id?json(res,200,users[id]):error(res,401,'UNAUTHORIZED');
    }
    if(path.startsWith('/api/v1/auth/')){
      const name=path.split('/').at(-1); const payload=await body(req);
      if(name==='register')return json(res,201,users.alice);
      active++;maxWriters=Math.max(maxWriters,active);counts[name]=(counts[name]??0)+1;
      try {
        if(control.hold===name)await waitGate(res);
        if(res.destroyed)return;
        const failure=control[name+'Status'];
        if(failure==='drop'){res.destroy();return;}
        if(typeof failure==='number')return error(res,failure,failure===503?'AUTH_STATE_UNAVAILABLE':'INTERNAL_ERROR');
        if(name==='login')return issue(req,res,payload.email?.startsWith('bob')?'bob':'alice');
        if(name==='refresh'){
          const id=sessions.get(cookie(req));
          if(!id){setCookie(res,'',0);return error(res,401,'UNAUTHORIZED');}
          return issue(req,res,id);
        }
        if(name==='logout'){sessions.delete(cookie(req));setCookie(res,'',0);res.writeHead(204);res.end();return;}
        return error(res,404,'NOT_FOUND');
      }finally{active--;}
    }
    if(path.startsWith('/api/'))return error(res,404,'NOT_FOUND');
    let file=resolve(root,'.'+path);
    if(!file.startsWith(root))return error(res,404,'NOT_FOUND');
    let data;try{data=await readFile(file);}catch{file=resolve(root,'e2e/harness.html');data=await readFile(file);}
    res.writeHead(200,{'Content-Type':({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[extname(file)]??'application/octet-stream'});res.end(data);
  }catch{if(!res.destroyed)error(res,500,'INTERNAL_ERROR');}
});
server.listen(18130,'127.0.0.1');
