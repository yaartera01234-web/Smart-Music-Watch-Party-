'use strict';
const assert=require('node:assert/strict');
const P=require('../app/src/main/assets/sync-policy.js');
const R=require('../app/src/main/assets/sync-room.js');
let count=0;
function check(name,fn){fn();count++;console.log('PASS '+name);}
const ctx=(diff,extra={})=>({ready:true,local:100+diff,target:100,playing:true,other:true,now:10,...extra});
check('8s threshold strict: 8 stays slowdown, >8 seeks',()=>{
 assert.equal(P.decide(P.initial(),ctx(8)).seek,null);
 assert.equal(P.decide(P.initial(),ctx(8.01)).seek,100);
 assert.equal(P.decide(P.initial(),ctx(4.5)).speed,.95);
});
check('1.5s slowdown and 0.1s restore',()=>{
 const a=P.decide(P.initial(),ctx(1.51));assert.equal(a.speed,.95);
 assert.equal(P.decide(a.state,ctx(.1001)).speed,.95);
 assert.equal(P.decide(a.state,ctx(.099)).speed,1);
});
check('gentle nudge 150ms start, filtered 30ms stop',()=>{
 let a=P.decide(P.initial(),ctx(.151));assert.equal(a.speed,.995);
 for(let i=0;i<12;i++)a=P.decide(a.state,ctx(0));assert.equal(a.speed,1);
});
check('behind normal-room member never forced fast-forward',()=>{
 let a=P.initial();for(let t=0;t<30;t++){
 const out=P.decide(a,ctx(-30,{now:t}));assert.equal(out.seek,null);assert.equal(out.speed,1);a=out.state;
 }
});
check('optional catch-up uses sustained >5s / 3.25s rule + .25s',()=>{
 let a=P.decide(P.initial(),ctx(-6,{now:0,catchUp:true}));
 assert.equal(P.decide(a.state,ctx(-6,{now:3,catchUp:true})).seek,null);
 assert.equal(P.decide(a.state,ctx(-6,{now:3.26,catchUp:true})).seek,100.25);
});
check('pause/background/pending restore speed',()=>{
 const a=P.decide(P.initial(),ctx(3)).state;
 for(const e of [{playing:false},{background:true},{pending:true},{ready:false}])assert.equal(P.decide(a,ctx(3,e)).speed,1);
});
check('first anchor can move forward; explicit seek resets speed',()=>{
 const a=P.decide(P.initial(),ctx(3)).state;
 for(const e of [{first:true},{explicit:true}]){const o=P.decide(a,ctx(-50,e));assert.equal(o.seek,100);assert.equal(o.speed,1);}
});
function rig(){
 let now=0,queue=[],clients={};
 function add(id,pos,anchored=true){
  const c={id,pos,playing:true,ready:true,buffering:false,speed:1,applied:[],enabled:true};clients[id]=c;
  c.r=new R({id:()=>id,session:id,now:()=>now,sample:()=>({media:'video',pos:c.pos,age:0,ready:c.ready,playing:c.playing,buffering:c.buffering}),send:m=>queue.push({from:id,m:structuredClone(m)}),apply:a=>{c.applied.push(a);c.speed=a.speed;if(a.seek!==null)c.pos=a.seek;if(typeof a.playing==='boolean')c.playing=a.playing;}});
  c.r.needAnchor=!anchored;return c;
 }
 function deliver(){let limit=10000;while(queue.length){assert(--limit>0);const {from,m}=queue.shift();for(const c of Object.values(clients))if(c.enabled&&c.id!==from)c.r.receive(from,structuredClone(m));}}
 function step(dt=1){now+=dt;for(const c of Object.values(clients))if(c.enabled){if(c.playing&&!c.buffering)c.pos+=dt*c.speed;c.r.tick();}deliver();}
 return {add,step,deliver,clients,setNow:t=>now=t,getNow:()=>now};
}
check('two clients >8s apart rewind ahead one, never chase slow one forward',()=>{
 const x=rig(),a=x.add('a',100),b=x.add('b',90);x.step();x.step();
 assert(a.applied.some(z=>z.reason==='rewind'));assert(!b.applied.some(z=>z.seek!==null));
 assert(Math.abs(a.pos-b.pos)<.1);
});
check('slowdown converges without repeat hard seeks',()=>{
 const x=rig(),a=x.add('a',100),b=x.add('b',98);for(let i=0;i<110;i++)x.step();
 assert(a.applied.some(z=>z.speed===.95));assert(!a.applied.some(z=>z.seek!==null));assert(Math.abs(a.pos-b.pos)<.12);
});
check('late join behind anchors to existing room, not room to newcomer',()=>{
 const x=rig(),a=x.add('a',100),b=x.add('b',0,false);x.step();x.step();
 assert(b.applied.some(z=>z.reason==='first'));assert(!a.applied.some(z=>z.seek!==null));assert(Math.abs(a.pos-b.pos)<.1);
});
check('first-sync seek does not turn into repeated seek loop',()=>{
 const x=rig(),a=x.add('a',100),b=x.add('b',0,false);for(let i=0;i<25;i++)x.step();assert.equal(b.applied.filter(z=>z.seek!==null).length,1);
});
check('buffering is position lag, never automatic room pause',()=>{
 const x=rig(),a=x.add('a',100),b=x.add('b',100);b.buffering=true;
 for(let i=0;i<15;i++)x.step();assert(a.applied.some(z=>z.reason==='rewind'));assert(a.playing);assert(b.playing);
});
check('disconnect ages out; local speed restores',()=>{
 const x=rig(),a=x.add('a',100),b=x.add('b',98);x.step();x.step();assert.equal(a.speed,.95);b.enabled=false;for(let i=0;i<6;i++)x.step();assert.equal(a.speed,1);assert.equal(a.r.peers.size,0);
});
check('background unavailable peer does not drag room back',()=>{
 const x=rig(),a=x.add('a',100),b=x.add('b',100);x.step();b.ready=false;b.pos=0;for(let i=0;i<6;i++)x.step();assert(!a.applied.some(z=>z.seek!==null));
});
check('epoch barrier rejects delayed samples and old commands',()=>{
 const x=rig(),a=x.add('a',100),b=x.add('b',98);x.step();const old=a.r.epoch.slice();const e=a.r.localCommand();assert(b.r.remoteCommand(e));assert(!a.r.remoteCommand(old));assert.equal(a.r.peers.size,0);assert.equal(a.r.pending.size,0);
});
check('malformed/unsolicited/stale response cannot become anchor',()=>{
 const x=rig(),a=x.add('a',100);a.r.receive('b',{v:4,kind:'sample',to:'a',token:'unknown',epoch:[0,''],sample:{pos:0}});assert.equal(a.r.peers.size,0);
 a.r.tick();x.setNow(4);a.r.receive('b',{v:4,kind:'sample',to:'a',token:'a:1',epoch:[0,''],sample:{media:'video',ready:true,pos:0,playing:true,rate:1}});assert.equal(a.r.peers.size,0);
});
check('suspend immediately restores rate and removes peers',()=>{
 const x=rig(),a=x.add('a',100),b=x.add('b',98);x.step();x.step();assert.equal(a.speed,.95);a.r.suspend();assert.equal(a.speed,1);assert.equal(a.r.peers.size,0);
});
check('native sample staleness fails closed',()=>{
 const r=new R({id:()=> 'a',now:()=>5,sample:()=>({media:'v',pos:0,ready:true,age:2}),send:()=>assert.fail('stale sample sent'),apply:()=>{}});r.tick();assert.equal(r.wasReady,false);
});
check('two fresh clients bootstrap without a King or anchor deadlock',()=>{
 const x=rig(),a=x.add('a',100,false),b=x.add('b',90,false);for(let i=0;i<12;i++)x.step();assert(Math.abs(a.pos-b.pos)<.2);
});
check('RTT compensation uses own monotonic timestamps only',()=>{
 let t=5000;
 const r=new R({id:()=> 'a',session:'a',now:()=>t,sample:()=>({media:'v',pos:101,playing:true,ready:true,age:0}),send:()=>{},apply:()=>{}});
 r.needAnchor=false;r.tick();t+=.2;
 r.receive('b',{v:4,kind:'sample',to:'a',token:'a:1',epoch:[0,''],sample:{media:'v',ready:true,anchor:false,pos:100,playing:true,buffering:false,rate:1}});
 assert(Math.abs(r.peers.get('b').pos-100.1)<1e-8);
 // Replayed QoS response must not overwrite a newer sample.
 t+=.2;r.receive('b',{v:4,kind:'sample',to:'a',token:'a:1',epoch:[0,''],sample:{media:'v',ready:true,pos:0,playing:true,rate:1}});
 assert(Math.abs(r.peers.get('b').pos-100.1)<1e-8);
});
check('wrong media is never a room reference',()=>{
 let t=0;const r=new R({id:()=> 'a',session:'a',now:()=>t,sample:()=>({media:'v',pos:101,playing:true,ready:true,age:0}),send:()=>{},apply:()=>{}});r.tick();
 r.receive('b',{v:4,kind:'sample',to:'a',token:'a:1',epoch:[0,''],sample:{media:'other',ready:true,pos:0,playing:true,rate:1}});assert.equal(r.peers.size,0);
});
console.log(count+' sync policy / simulated transport tests passed. Not device or real-broker validation.');
