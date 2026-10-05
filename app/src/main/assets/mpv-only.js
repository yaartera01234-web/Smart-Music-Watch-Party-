/* TEST2: local YouTube API-compatible controller. No iframe, fetch or media URL. */
(function () {
  'use strict';
  if (window.__wpMpvOnly) return;
  window.__wpMpvOnly = true;
  var player, readyCallback, notified=false, incomingState=null;
  var s={type:'youtube',title:'',id:'',t:0,d:0,playing:false,muted:false,ended:false,rev:0,quality:144,qualities:[144,240,360,480,720,1080]};
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
    s.type='youtube';s.title=id;s.id=id;s.t=Math.max(0,Number(incomingState?incomingState.time:((value&&value.startSeconds)||start))||0);s.d=0;s.playing=incomingState?!!incomingState.playing:true;s.ended=false;s.rev++;
    native('mpvOnlyLoad',[id,s.t,s.playing,s.rev]);refresh();
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
  Player.prototype.setPlaybackQuality=function(q){q=({tiny:144,small:240,medium:360,large:480,hd720:720,hd1080:1080})[q]||Number(q);if([144,240,360,480,720,1080].indexOf(q)<0)return;s.quality=q;native('mpvCmd',['quality:'+q]);};
  Player.prototype.setSize=function(){};
  Player.prototype.destroy=function(){this.stopVideo();};
  window.YT={Player:Player,PlayerState:{UNSTARTED:-1,ENDED:0,PLAYING:1,PAUSED:2,BUFFERING:3,CUED:5}};
  window.__wpOnlyReport=function(r){
    if(!r||r.id!==s.id||r.rev<s.rev)return false;
    s.t=Math.max(0,Number(r.t)||0);s.d=Math.max(0,Number(r.d)||0);s.playing=!!r.playing;s.muted=!!r.muted;
    var ended=!!r.ended;
    if(ended&&!s.ended){s.ended=true;s.playing=false;if(s.type==='youtube'){var fn=player&&player.config.events&&player.config.events.onStateChange;if(fn)fn({target:player,data:0});}else{mp4.dispatchEvent(new Event('ended'));}}
    var art=document.getElementById('wp-audio-cover');if(art)art.style.display=s.type!=='youtube'&&(s.type==='mp3'||r.audioOnly)&&!r.art?'grid':'none';
    if(s.type!=='youtube'){mp4.dispatchEvent(new Event('timeupdate'));}
    refresh();return true;
  };
  window.__wpOnlySuspend=function(){s.playing=false;refresh();};
  window.__wpOnlyQuality=function(q){s.quality=Number(q)||144;};
  window.__wpOnlyQualities=function(qs){s.qualities=qs.filter(function(q){return [144,240,360,480,720,1080].includes(q);});if(window.__wpOnlyAttached)updateYouTubeQualityOptions();};
  window.__wpOnlyAttach=function(){
    if(window.__wpOnlyAttached)return 'mpv-only-ready';window.__wpOnlyAttached=true;
    // Direct files have no browser media source: proxy the existing media API only.
    var oldLoad=window.loadVideoLocal, browserPause=mp4.pause.bind(mp4);
    browserPause();mp4.removeAttribute('src');mp4.load();
    var props={currentTime:{get:function(){return s.t;},set:function(t){if(s.type==='youtube')return;player.seekTo(Number(t));mp4.dispatchEvent(new Event('seeked'));}},
      duration:{get:function(){return s.d;}},paused:{get:function(){return !s.playing;}},ended:{get:function(){return s.ended;}},
      currentSrc:{get:function(){return s.type==='youtube'?'':s.id;}},src:{get:function(){return s.type==='youtube'?'':s.id;},set:function(){}},
      muted:{get:function(){return s.muted;},set:function(m){if(s.type!=='youtube'){if(m)player.mute();else player.unMute();}}}};
    Object.keys(props).forEach(function(k){props[k].configurable=true;Object.defineProperty(mp4,k,props[k]);});
    mp4.play=function(){if(s.type!=='youtube'){player.playVideo();mp4.dispatchEvent(new Event('play'));}return Promise.resolve();};
    mp4.pause=function(){if(s.type!=='youtube'){player.pauseVideo();mp4.dispatchEvent(new Event('pause'));}};
    mp4.load=function(){};
    window.loadVideoLocal=function(data,autoplay){
      if(data.type==='youtube'){
        var art=document.getElementById('wp-audio-cover');if(art)art.style.display='none';
        return oldLoad.apply(this,arguments);
      }
      if(!/^https?:\/\//i.test(data.url||'')){toast('MPV ko direct HTTP/HTTPS media link chahiye');return;}
      if(!['mp4','mp3','hls'].includes(data.type)){data=Object.assign({},data,{type:/\.(mp3|m4a|aac|flac|wav|ogg)(?:[?#]|$)/i.test(data.url)?'mp3':/\.m3u8(?:[?#]|$)/i.test(data.url)?'hls':'mp4'});}
      destroyHLS();currentType=data.type;s.type=data.type;s.id=data.url;s.title=data.title||data.label||makeLabel(data);
      s.t=Math.max(0,Number(incomingState&&incomingState.time)||0);s.d=0;s.playing=!!autoplay;s.ended=false;s.rev++;
      hlsAudioMode=false;noVideo.classList.add('hidden');mp3Player.classList.add('hidden');mp4.classList.add('hidden');ytDiv.classList.add('hidden');
      premiumVideoUI.classList.remove('hidden','youtube-native','controls-hidden');premiumPlayerMenu.classList.add('hidden');
      setPremiumMedia(data);premiumVideoTitle.textContent=s.title;premiumVideoBadge.textContent=data.type==='mp3'?'♫ AUDIO':'▶ MPV';
      nowPlaying.textContent=s.title;
      native('mpvDirectLoad',[data.url,data.type,s.title,s.t,s.playing,s.rev]);
      var wrap=document.querySelector('.player-wrap'),art=document.getElementById('wp-audio-cover');
      if(!art){art=document.createElement('div');art.id='wp-audio-cover';art.style.cssText='position:absolute;inset:0;z-index:1;display:none;place-items:center;background:radial-gradient(ellipse at 20% 15%,#55317e,#151127 65%);pointer-events:none';
        art.innerHTML='<div style="width:130px;height:130px;border-radius:50%;background:repeating-radial-gradient(circle,#1d1634 0 3px,#3e2855 4px 5px);box-shadow:0 0 45px #b170e544;display:grid;place-items:center"><span style="display:grid;place-items:center;width:66px;height:66px;border-radius:50%;background:linear-gradient(135deg,#c185f0,#f79eae);font-size:36px;color:#291839">♫</span></div>';wrap.appendChild(art);}
      art.style.display=data.type==='mp3'?'grid':'none';updateYouTubeQualityOptions();refresh();
    };
    var baseUpdate=window.updatePremiumVideoUI;
    window.premiumMediaState=function(){return {time:s.t,duration:s.d,playing:s.playing,muted:s.muted};};
    window.updatePremiumVideoUI=function(){
      if(currentType!=='mp3')return baseUpdate();
      var pct=s.d?Math.min(100,s.t/s.d*100):0;
      premiumVideoFill.style.width=pct+'%';premiumVideoDot.style.left=pct+'%';
      premiumVideoTime.textContent=formatMediaTime(s.t)+' / '+formatMediaTime(s.d);
      premiumVideoPlay.textContent=s.playing?'❚❚':'▶';premiumVideoBigPlay.textContent=premiumVideoPlay.textContent;
      premiumVideoMute.textContent=s.muted?'🔇':'🔊';premiumVideoUI.classList.toggle('is-playing',s.playing);
    };
    // Use the same commands for inline and native fullscreen controls; publish Party intent.
    window.__wpUnifiedCommand=function(c){
      if(c==='toggle'){if(s.playing){player.pauseVideo();userPauseEverywhere();}else{player.playVideo();userPlayEverywhere();}refresh();return;}
      if(c==='mute'){if(s.muted)player.unMute();else player.mute();refresh();return;}
      if(c.indexOf('seekabs:')===0||c.indexOf('seekrel:')===0){var t=Number(c.split(':')[1]);if(c.indexOf('seekrel:')===0)t+=s.t;t=Math.max(0,Math.min(s.d||Infinity,t));player.seekTo(t);syncQuiet=Date.now()+1800;if(joined&&mqttUp)pubCmd('seek',{time:t});return;}
    };
    ['premium-video-play','premium-video-big-play','mp3-play'].forEach(function(id){var e=document.getElementById(id);if(e)e.onclick=function(){__wpUnifiedCommand('toggle');};});
    var controls={'premium-video-back':'seekrel:-10','premium-video-forward':'seekrel:10','premium-video-mute':'mute'};
    Object.keys(controls).forEach(function(id){var e=document.getElementById(id);if(e)e.onclick=function(){__wpUnifiedCommand(controls[id]);};});
    premiumVideoProgress.onclick=function(e){var r=this.getBoundingClientRect();if(s.d&&r.width)__wpUnifiedCommand('seekabs:'+(Math.max(0,Math.min(1,(e.clientX-r.left)/r.width))*s.d));};
    // Return native state for ALL sources; never consult a dormant browser decoder.
    window.__wpSnap=function(){return JSON.stringify({t:s.type,id:s.id,pos:s.t,playing:s.playing,title:s.title||document.getElementById('premium-video-title').textContent,qi:queueLocal.index});};
    window.__wpResumeAt=function(pos,play,id){if(id&&id!==s.id)player.loadVideoById(id,pos);else if(pos>=0)player.seekTo(pos);if(play)player.playVideo();else player.pauseVideo();return 'ok';};
    window.__wpMpvFsSet=function(on){window.__wpMpvFsOn=!!on;native('mpvCmd',[on?'fs:1':'fs:0']);return 'ok';};
    window.__wpMpvFsToggle=function(){return window.__wpMpvFsSet(!window.__wpMpvFsOn);};
    var originalApply=window.applyState;
    window.applyState=function(state){
      if(!state||!['youtube','mp4','mp3','hls'].includes(state.type))return originalApply.apply(this,arguments);
      if(state.type!=='youtube'){suppressMP4=true;syncQuiet=Date.now()+1800;if(s.id!==state.url||s.type!==state.type){incomingState=state;try{loadVideoLocal(state,!!state.playing);}finally{incomingState=null;}}else{mp4.currentTime=Math.max(0,Number(state.time)||0);if(state.playing)mp4.play();else mp4.pause();}setTimeout(function(){suppressMP4=false;},1800);return;}
      // Apply authoritative Party state atomically. Never reload an already-playing
      // stream at zero just to seek it again 1.2 seconds later on catch-up.
      if(!/^[A-Za-z0-9_-]{11}$/.test(state.videoId||''))return;
      suppressYT=true;syncQuiet=Date.now()+1800;
      if(s.id!==state.videoId||currentType!=='youtube'){
        incomingState=state;try{loadVideoLocal(state,false);}finally{incomingState=null;}
      }else{
        player.seekTo(Math.max(0,Number(state.time)||0));
        if(state.playing)player.playVideo();else player.pauseVideo();
      }
      setTimeout(function(){suppressYT=false;},1800);
    };
    window.updateYouTubeQualityOptions=function(){
      var q=document.getElementById('premium-video-quality');if(!q)return;
      if(typeof currentType!=='undefined'&&currentType!=='youtube'){q.classList.add('hidden');return;}
      if(q.dataset.mpvQualities!==s.qualities.join(',')){q.dataset.mpvQualities=s.qualities.join(',');q.innerHTML=s.qualities.map(function(h){return '<option value="'+h+'">'+h+'p</option>';}).join('');}
      q.value=String(s.quality);q.classList.remove('hidden');
    };
    window.__wpMpvQ=function(q){s.quality=Number(q)||144;updateYouTubeQualityOptions();return 'ok';};
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
