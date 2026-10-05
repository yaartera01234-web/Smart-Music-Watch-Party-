/* TEST4 MQTT adaptation of normal-room slowest-member coordination.
 * No elected host/King. Peers answer short-lived probes with fresh native samples.
 * Monotonic RTT compensation avoids comparing the phones' wall clocks.
 * Not the Syncplay wire protocol: legacy clients retain explicit controls only. */
(function(root){
  'use strict';
  var Policy=root.WPSyncPolicy;
  if(typeof module==='object'&&module.exports)Policy=require('./sync-policy.js');
  function validEpoch(e){return Array.isArray(e)&&e.length===2&&Number.isSafeInteger(e[0])&&e[0]>=0&&e[0]<1e12&&typeof e[1]==='string'&&e[1].length<=160;}
  function compare(a,b){return a[0]-b[0]||(a[1]<b[1]?-1:a[1]>b[1]?1:0);}
  function Room(o){
    this.o=o;this.session=o.session||Math.random().toString(36).slice(2);this.seq=0;
    this.epoch=[0,''];this.peers=new Map();this.pending=new Map();this.state=Policy.initial();
    this.needAnchor=true;this.wasReady=false;this.readyAt=0;this.quiet=0;this.speed=1;this.lastSeek=-100;
  }
  Room.prototype.normal=function(){this.state=Policy.initial();if(this.speed!==1){this.speed=1;this.o.apply({speed:1,seek:null});}};
  Room.prototype.suspend=function(){this.normal();this.peers.clear();this.pending.clear();this.needAnchor=true;this.wasReady=false;};
  Room.prototype.localCommand=function(){
    this.epoch=[this.epoch[0]+1,this.o.id()];this.barrier(false);return this.epoch.slice();
  };
  Room.prototype.barrier=function(anchor){this.normal();this.peers.clear();this.pending.clear();this.needAnchor=anchor;this.quiet=this.o.now()+2.5;};
  Room.prototype.remoteCommand=function(e){
    if(!validEpoch(e))return false;
    if(compare(e,this.epoch)<0)return false;
    this.epoch=e.slice();this.barrier(false);return true;
  };
  Room.prototype.sample=function(){
    var a=this.o.sample(), now=this.o.now();
    if(!a||!a.ready||!a.media||!Number.isFinite(a.pos)||a.pos<0||!Number.isFinite(a.age)||a.age<0||a.age>1.5)return null;
    return {media:a.media,pos:a.pos,playing:!!a.playing,buffering:!!a.buffering,rate:Number.isFinite(a.rate)?a.rate:this.speed,ready:now>=this.quiet,anchor:this.needAnchor};
  };
  Room.prototype.packet=function(kind,extra){return Object.assign({v:4,kind:kind,session:this.session,epoch:this.epoch.slice()},extra);};
  Room.prototype.receive=function(from,m){
    var now=this.o.now();
    if(typeof from!=='string'||from===this.o.id()||from.length>160||!m||m.v!==4||!validEpoch(m.epoch))return;
    if(m.kind==='probe'){
      if(typeof m.token!=='string'||m.token.length>80)return;
      if(compare(m.epoch,this.epoch)>0){this.epoch=m.epoch.slice();this.barrier(true);}
      this.o.send(this.packet('sample',{to:from,token:m.token,sample:this.sample()}));return;
    }
    if(m.kind!=='sample'||m.to!==this.o.id()||!this.pending.has(m.token))return;
    var sent=this.pending.get(m.token),rtt=now-sent, a=m.sample;
    if(rtt<0||rtt>2.5)return;
    if(compare(m.epoch,this.epoch)>0){this.epoch=m.epoch.slice();this.barrier(true);return;}
    if(compare(m.epoch,this.epoch)!==0)return;
    var old=this.peers.get(from);
    if(old&&sent<=old.sent)return;
    if(!a||a.ready!==true||a.anchor===true){this.peers.delete(from);return;}
    var self=this.sample();
    if(!self||a.media!==self.media||typeof a.playing!=='boolean'||!Number.isFinite(a.pos)||a.pos<0||a.pos>1e8||![.95,.995,1,1.005].includes(a.rate))return;
    if(this.peers.size>=64&&!this.peers.has(from))return;
    // A buffering position must remain frozen, not extrapolate unplayed frames.
    var moving=a.playing&&!a.buffering;
    this.peers.set(from,{pos:a.pos+(moving?rtt*.5*a.rate:0),playing:a.playing,moving:moving,rate:a.rate,at:now,sent:sent,media:a.media});
  };
  Room.prototype.tick=function(){
    var now=this.o.now(),self=this.sample();
    if(!self){if(now-this.lastSeek<3)return;this.suspend();return;}
    if(!this.wasReady){this.wasReady=true;this.readyAt=now;}
    for(var item of this.pending)if(now-item[1]>3)this.pending.delete(item[0]);
    for(var item of this.peers)if(now-item[1].at>3.5||item[1].media!==self.media)this.peers.delete(item[0]);
    var token=this.session+':'+(++this.seq);this.pending.set(token,now);
    this.o.send(this.packet('probe',{token:token}));
    if(!self.ready){this.normal();return;}
    var target=null,who='',playing=self.playing;
    for(var item of this.peers){var p=item[1];if(!this.needAnchor&&p.playing!==self.playing)continue;
      var position=p.pos+(p.moving?(now-p.at)*p.rate:0);
      if(target===null||position<target){target=position;who=item[0];playing=p.playing;}}
    if(target===null){this.normal();if(now-this.readyAt>=4)this.needAnchor=false;return;}
    var first=this.needAnchor;this.needAnchor=false;
    if(!first&&self.pos<=target){target=self.pos;who=this.o.id();}
    var result=Policy.decide(this.state,{now:now,ready:true,background:false,pending:now-this.lastSeek<2.5,local:self.pos,target:target,playing:playing,first:first,other:who!==this.o.id(),catchUp:false});
    this.state=result.state;
    if(result.seek!==null){this.lastSeek=now;this.quiet=now+2.5;this.peers.clear();}
    if(result.seek!==null||result.speed!==this.speed){this.speed=result.speed;this.o.apply({seek:result.seek,speed:result.speed,playing:first?playing:undefined,reason:result.reason});}
  };
  root.WPSyncRoom=Room;
  if(typeof module==='object'&&module.exports)module.exports=Room;
})(typeof window==='object'?window:globalThis);
