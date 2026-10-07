// Production page + TEST4 adapter, two actual Chromium pages, simulated MQTT relay/native clock.
const {chromium}=require('playwright'),fs=require('fs'),assert=require('node:assert/strict');
(async()=>{
 const b=await chromium.launch({args:['--no-sandbox']}),pages=[],errors=[],events=[];
 const root=require('path').resolve(__dirname,'..')+'/',html=fs.readFileSync(process.env.WP_HTML||'/home/user/wp-page/party-final1.html','utf8');
 const adapter=['sync-policy.js','sync-room.js','mpv-only.js'].map(f=>fs.readFileSync(root+'app/src/main/assets/'+f,'utf8')).join('\n');
 let connected=true;
 try{
 for(let i=0;i<2;i++){
  const p=await b.newPage();pages.push(p);p.on('pageerror',e=>errors.push(e.message));
  await p.exposeFunction('relay',async(topic,obj)=>{
   events.push({i,topic,obj});if(!connected)return;
   for(let j=0;j<pages.length;j++)if(i!==j)await pages[j].evaluate(({topic,obj})=>{if(window.testReady)onMsg(topic,JSON.stringify(obj));},{topic,obj});
  });
  await p.addInitScript(()=>{
   window.calls=[];window.fake={id:'',t:0,raw:0,d:1200,playing:true,muted:false,rev:0,ready:true,foreground:true,buffering:false};window.rate=1;
   window.YaarNative={
    mpvOnlyLoad(id,t,playing,rev){Object.assign(fake,{id,t,raw:t,playing,rev});},
    mpvDirectLoad(id,type,title,t,playing,rev){Object.assign(fake,{id,t,raw:t,playing,rev});},
    mpvOnlyCommand(c,rev){calls.push(c);fake.rev=rev;if(c==='play')fake.playing=true;if(c==='pause')fake.playing=false;if(c.startsWith('seekabs:'))fake.t=fake.raw=Number(c.split(':')[1]);},
    mpvCmd(c){calls.push(c);if(c.startsWith('speed:'))rate=Number(c.split(':')[1]);},ytSeen(){},appVersion(){return 41}
   };
  });
  await p.route('**/*',r=>{const u=r.request().url();if(u==='https://test.local/')return r.fulfill({contentType:'text/html',body:html});if(u==='https://www.youtube.com/iframe_api')return r.fulfill({contentType:'application/javascript',body:adapter});return r.abort();});
  await p.goto('https://test.local/');await p.waitForFunction(()=>typeof ytReady!=='undefined'&&ytReady);
  await p.evaluate(i=>{
   __wpOnlyAttach();myId='peer'+i;ROOM='test/sync4';TP={cmd:ROOM+'/cmd'};joined=true;mqttUp=true;
   pub=(topic,obj)=>{relay(topic,obj).catch(()=>{});};pubState=()=>{};pubQueue=()=>{};
   loadVideoLocal({type:'youtube',videoId:'baYbQ4OOGM4'},true);fake.raw=fake.t=100;
   let last=performance.now();setInterval(()=>{const n=performance.now();if(fake.playing&&!fake.buffering)fake.raw+=(n-last)/1000*rate;fake.t=fake.raw;last=n;__wpOnlyReport({...fake,speed:rate});},100);
   window.testReady=true;
  },i);
 }
 const [a,c]=pages;await a.waitForTimeout(7000);
 // A real local button, not a direct call of the policy. Epoch rides on the original command.
 await a.evaluate(()=>__wpUnifiedCommand('seekabs:250'));await c.waitForFunction(()=>fake.t>=250&&fake.t<260);
 assert(events.some(e=>e.obj.action==='seek'&&e.obj._wp4));console.log('PASS explicit seek uses original command path with epoch; received by peer');
 await a.waitForTimeout(3500);
 const before=events.filter(e=>e.obj.action==='seek').length;
 await c.evaluate(()=>{fake.raw-=10;fake.t=fake.raw;lastYtTime=fake.t;__wpOnlyReport({...fake,speed:rate});});
 await a.waitForFunction(()=>calls.some(c=>c.startsWith('seekabs:')&&Number(c.split(':')[1])<249),null,{timeout:8000});
 assert.equal(events.filter(e=>e.obj.action==='seek').length,before,'automatic rewind must NOT rebroadcast as user seek');
 console.log('PASS >8s native drift rewinds ahead page without Party command feedback');
 await a.waitForTimeout(3500);
 await a.evaluate(()=>__wpUnifiedCommand('toggle'));await c.waitForFunction(()=>!fake.playing);
 assert.equal(await a.evaluate(()=>fake.playing),false);assert.equal(await c.evaluate(()=>fake.playing),false);
 console.log('PASS explicit fullscreen pause shared after automatic correction');
 await a.evaluate(()=>__wpUnifiedCommand('toggle'));await c.waitForFunction(()=>fake.playing);
 connected=false;await a.waitForTimeout(5000);assert.equal(await a.evaluate(()=>rate),1);assert.equal(await c.evaluate(()=>rate),1);
 console.log('PASS lost relay expires peers and restores normal speed');
 assert.deepEqual(errors,[]);console.log('PASS two-browser integration: no page exceptions (native/MQTT simulated)');
 }finally{await b.close();}
})().catch(e=>{console.error(e);process.exit(1);});
