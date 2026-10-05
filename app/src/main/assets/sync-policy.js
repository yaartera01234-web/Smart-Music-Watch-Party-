/* Independently implemented Synkplay normal-room decision rules.
 * Reference: yuroyami/syncplay-mobile a7770b10, SyncDecision.kt.
 * User-approved difference: rewind above 8s instead of 4s.
 * Pure policy; no clocks, players, MQTT, or page state hidden inside it. */
(function(root){
  'use strict';
  function initial(){return {speed:1,filtered:null,behindSince:null,level:0};}
  function decide(previous,c){
    var s=Object.assign({},previous), out={state:s,speed:1,seek:null,reason:''};
    function reset(){s=initial();out.state=s;out.speed=1;return out;}
    if(!c.ready||c.background||c.pending||!Number.isFinite(c.local)||!Number.isFinite(c.target))return reset();
    if(c.first||c.explicit){reset();out.seek=Math.max(0,c.target);out.reason=c.first?'first':'explicit';return out;}
    var d=c.local-c.target;
    if(d>8){reset();out.seek=Math.max(0,c.target);out.reason='rewind';return out;}
    // Default normal rooms follow the slowest member. Catch-up is opt-in only.
    if(c.catchUp&&d < -1.75){
      if(s.behindSince===null)s.behindSince=c.now;
      else if(c.now-s.behindSince>3.25&&d < -5){reset();out.state.behindSince=c.now+3;out.seek=Math.max(0,c.target+.25);out.reason='fast-forward';return out;}
    }else s.behindSince=null;
    if(!c.playing)return reset();
    if(s.speed===.95&&d>=.1){out.speed=.95;return out;}
    if(d>1.5&&c.other){s.speed=.95;s.level=0;s.filtered=null;out.speed=.95;out.reason='slowdown';return out;}
    if(s.speed===.95){s.speed=1;s.filtered=null;s.level=0;return out;}
    s.filtered=s.filtered===null?d:s.filtered+.3*(d-s.filtered);
    if(!s.level){if(s.filtered>.15)s.level=-1;else if(c.catchUp&&s.filtered<-.15)s.level=1;}
    else if(s.level===-1&&s.filtered<.03)s.level=0;
    else if(s.level===1&&(s.filtered>-.03||!c.catchUp))s.level=0;
    s.speed=1+s.level*.005;out.speed=s.speed;return out;
  }
  root.WPSyncPolicy={initial:initial,decide:decide};
  if(typeof module==='object'&&module.exports)module.exports=root.WPSyncPolicy;
})(typeof window==='object'?window:globalThis);
