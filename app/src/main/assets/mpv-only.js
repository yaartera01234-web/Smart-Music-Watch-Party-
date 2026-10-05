/* TEST2: local YouTube API-compatible controller. No iframe, fetch or media URL. */
(function () {
  'use strict';
  if (window.__wpMpvOnly) return;
  window.__wpMpvOnly = true;
  var player, readyCallback, notified=false;
  var s={id:'',t:0,d:0,playing:false,muted:false,ended:false,rev:0,quality:144};
  function native(name,args){try{if(window.YaarNative&&typeof window.YaarNative[name]==='function')window.YaarNative[name].apply(window.YaarNative,args);}catch(e){}}
  function refresh(){try{if(typeof updatePremiumVideoUI==='function')updatePremiumVideoUI();}catch(e){}}
  function command(c){s.rev++;native('mpvOnlyCommand',[c,s.rev]);refresh();}
  function notify(){if(!notified&&typeof readyCallback==='function'){notified=true;setTimeout(function(){readyCallback();},0);}}
  Object.defineProperty(window,'onYouTubeIframeAPIReady',{configurable:true,get:function(){return readyCallback;},set:function(fn){readyCallback=fn;notify();}});
  function Player(id,config){
    player=this;this.config=config||{};this.element=document.getElementById(id);
    setTimeout(function(){var fn=player.config.events&&player.config.events.onReady;if(fn)fn({target:player});},0);
  }
  Player.prototype.loadVideoById=function(value,start){
    var id=typeof value==='string'?value:value&&value.videoId;
    if(!/^[A-Za-z0-9_-]{11}$/.test(id||''))return;
    s.id=id;s.t=Math.max(0,Number((value&&value.startSeconds)||start)||0);s.d=0;s.playing=true;s.ended=false;s.rev++;
    native('mpvOnlyLoad',[id,s.t,true,s.rev]);refresh();
  };
  Player.prototype.cueVideoById=function(value,start){this.loadVideoById(value,start);this.pauseVideo();};
  Player.prototype.playVideo=function(){s.playing=true;s.ended=false;command('play');};
  Player.prototype.pauseVideo=function(){s.playing=false;command('pause');};
  Player.prototype.stopVideo=function(){s.playing=false;command('pause');};
  Player.prototype.seekTo=function(t){if(!Number.isFinite(t)||t<0)return;s.t=t;s.ended=false;command('seekabs:'+t);};
  Player.prototype.getCurrentTime=function(){return s.t;};
  Player.prototype.getDuration=function(){return s.d;};
  Player.prototype.getPlayerState=function(){return s.ended?0:(s.playing?1:2);};
  Player.prototype.getVideoData=function(){return{video_id:s.id,title:s.id};};
  Player.prototype.getVideoUrl=function(){return 'https://www.youtube.com/watch?v='+s.id;};
  Player.prototype.getIframe=function(){return this.element;};
  Player.prototype.isMuted=function(){return s.muted;};
  Player.prototype.mute=function(){s.muted=true;command('mute:1');};
  Player.prototype.unMute=function(){s.muted=false;command('mute:0');};
  Player.prototype.setVolume=function(v){if(v===0)this.mute();else this.unMute();};
  Player.prototype.getVolume=function(){return s.muted?0:100;};
  Player.prototype.getAvailableQualityLevels=function(){return [];};
  Player.prototype.getPlaybackQuality=function(){return String(s.quality);};
  Player.prototype.setPlaybackQuality=function(q){q=({tiny:144,small:240,medium:360})[q]||Number(q);if([144,240,360].indexOf(q)<0)return;s.quality=q;native('mpvCmd',['quality:'+q]);};
  Player.prototype.setSize=function(){};
  Player.prototype.destroy=function(){this.stopVideo();};
  window.YT={Player:Player,PlayerState:{UNSTARTED:-1,ENDED:0,PLAYING:1,PAUSED:2,BUFFERING:3,CUED:5}};
  window.__wpOnlyReport=function(r){
    if(!r||r.id!==s.id||r.rev<s.rev)return false;
    s.t=Math.max(0,Number(r.t)||0);s.d=Math.max(0,Number(r.d)||0);s.playing=!!r.playing;s.muted=!!r.muted;
    var ended=!!r.ended;
    if(ended&&!s.ended){s.ended=true;s.playing=false;var fn=player&&player.config.events&&player.config.events.onStateChange;if(fn)fn({target:player,data:0});}
    refresh();return true;
  };
  window.__wpOnlySuspend=function(){s.playing=false;refresh();};
  window.__wpOnlyQuality=function(q){s.quality=Number(q)||144;};
  window.__wpOnlyAttach=function(){
    window.updateYouTubeQualityOptions=function(){
      var q=document.getElementById('premium-video-quality');if(!q)return;
      if(typeof currentType!=='undefined'&&currentType!=='youtube'){q.classList.add('hidden');return;}
      if(q.options.length!==3||q.options[0].value!=='144')q.innerHTML='<option value="144">144p</option><option value="240">240p</option><option value="360">360p</option>';
      q.value=String(s.quality);q.classList.remove('hidden');
    };
    // No old iframe-state wrappers: existing page commands call this controller directly.
    window.__wpMpvLink=function(on){
      window.__wpMpvLinked=on?1:0;
      document.body.classList.toggle('wp-mpv-on',!!on);
      if(on){window.__wpMpvAssert();updateYouTubeQualityOptions();}
      else {var badge=document.getElementById('wp-mpv-test-status');if(badge)badge.remove();}
      return 'ok';
    };
    window.__wpSetMute=function(){return 'mpv-only-no-iframe';};
    window.__wpPausePage=function(){window.__wpOnlySuspend();return 'ok';};
    var fs=document.getElementById('premium-video-fullscreen');if(fs)fs.onclick=function(){window.__wpMpvFsToggle();};
    updateYouTubeQualityOptions();
    return 'mpv-only-ready';
  };
})();
