// Source guardrails only, NOT Android runtime or network tests.
const fs=require('fs'),assert=require('assert'),path=require('path');
const root=path.resolve(__dirname,'..');
const s=fs.readFileSync(root+'/app/src/main/java/app/party/music/GifWebView.kt','utf8');
let n=0;function check(name,f){f();n++;console.log('PASS source guard: '+name)}
check('no dialog, retry buttons, or consent UI',()=>{for(const x of ['AlertDialog','backupDialog','setPositiveButton','setNeutralButton','offerBackup'])assert(!s.includes(x))});
check('initial upload uses primary',()=>assert(s.includes('upload(bytes,format,target,false)')));
check('worker chooses one host per attempt',()=>assert(s.includes('if(backup) KeyboardGifUpload.backup(bytes,format) else KeyboardGifUpload.primary(bytes,format)')));
check('first failure starts backup; backup failure ends with short error',()=>assert(/if\(backup\) finish\("GIF send nahi hui — dobara try karein"\)\s*else post \{ startBackup\(bytes,format,target\) \}/.test(s)));
check('backup requires same live destination and is attempted once',()=>{
 const body=s.slice(s.indexOf('private fun startBackup('));
 assert(body.includes('if (!alive()) { finish();return }'));
 assert(body.indexOf('if(current!=target || !alive())')<body.indexOf('thread(name="wp-keyboard-backup")'));
 assert(body.includes('upload(bytes,format,target,true)'));
 assert(!body.includes('target,false'));assert(!body.includes('startBackup(bytes,format'));
});
console.log(`${n} source guardrails passed. CI compilation and phone testing still required.`);
