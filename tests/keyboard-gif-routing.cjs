// Actual native-embedded JS, with simulated page/DM state. Not a handset or broker test.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const root=require('path').resolve(__dirname,'..');
const g=fs.readFileSync(root+'/app/src/main/java/app/party/music/GifWebView.kt','utf8');
const m=fs.readFileSync(root+'/app/src/main/java/app/party/music/MainActivity.kt','utf8');
const expression=g.match(/const val DESTINATION_JS = """([\s\S]*?)"""/)[1];
const template=m.match(/val script = """([\s\S]*?window\.wpSendGif[\s\S]*?)"""/)[1];
const url="https://tmpfiles.org/dl/token/id/wp-keyboard.gif";
function page(){
 const state={sheet:false,chat:false,friend:'a',joined:true,topic:'room/main',sent:[]};
 const c={Date,Math,JSON,TP:{get chat(){return state.topic}},document:{getElementById(id){return {classList:{contains(){return id==='dm-sheet'?state.sheet:state.chat}}}}}};
 c.window=c;c.DM={_curChat:()=>state.friend};c.wpRoomJoined=()=>state.joined;c.wpSendGif=u=>state.sent.push(u);
 return {s:state,c:vm.createContext(c)};
}
function capture(p){return vm.runInContext(expression,p.c)}
function send(p,target,link=url){return vm.runInContext(template.replace('${GifWebView.DESTINATION_JS}',expression).replace('${org.json.JSONObject.quote(destination)}',JSON.stringify(target)).replace('${org.json.JSONObject.quote(gifUrl)}',JSON.stringify(link)),p.c)}
let count=0;function test(name,fn){fn();console.log('PASS '+name);count++}
test('unchanged joined room dispatches',()=>{let p=page();assert.equal(send(p,capture(p)),'dispatched');assert.deepEqual(p.s.sent,[url])});
test('unchanged DM dispatches',()=>{let p=page();p.s.sheet=p.s.chat=true;assert.equal(send(p,capture(p)),'dispatched')});
test('DM to another friend cancels',()=>{let p=page();p.s.sheet=p.s.chat=true;let t=capture(p);p.s.friend='b';assert.equal(send(p,t),'chat-changed');assert.equal(p.s.sent.length,0)});
test('DM closed to room cancels',()=>{let p=page();p.s.sheet=p.s.chat=true;let t=capture(p);p.s.sheet=false;assert.equal(send(p,t),'chat-changed')});
test('room to DM cancels',()=>{let p=page();let t=capture(p);p.s.sheet=p.s.chat=true;assert.equal(send(p,t),'chat-changed')});
test('room change cancels',()=>{let p=page();let t=capture(p);p.s.topic='room/other';assert.equal(send(p,t),'chat-changed')});
test('leaving room cancels',()=>{let p=page();let t=capture(p);p.s.joined=false;assert.equal(send(p,t),'chat-changed')});
test('DM inbox does not fall through to room',()=>{let p=page();p.s.sheet=true;assert.equal(capture(p),'')});
test('unjoined room cannot be captured',()=>{let p=page();p.s.joined=false;assert.equal(capture(p),'')});
test('page reload cancels old upload',()=>{let p=page();let t=capture(p);assert.equal(send(page(),t),'chat-changed')});
test('missing send hook fails closed',()=>{let p=page();let t=capture(p);delete p.c.wpSendGif;assert.equal(send(p,t),'not-ready')});
test('JSON-quoted URL remains data, not JavaScript',()=>{let p=page();let u="https://tmpfiles.org/dl/a'b\\c.gif";assert.equal(send(p,capture(p),u),'dispatched');assert.equal(p.s.sent[0],u)});
console.log(`${count} keyboard GIF routing simulations passed. No physical-device validation.`);
