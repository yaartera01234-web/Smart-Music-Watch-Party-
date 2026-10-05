package app.party.music

import org.json.JSONObject

/**
 * v111-FIX (build 3) — WEBVIEW <-> NATIVE BRIDGE
 *
 * Website (party-final1.html) ko HAATH NAHI LAGAYA JATA. Ye bridge runtime me WebView ke andar
 * inject hota hai. Sirf 4 kaam:
 *
 *   __wpSnap()      -> page ka current player state JSON me (kaun sa item, kahan tak, chal raha hai?)
 *   __wpPausePage() -> page ke player ko chup karao, MAGAR party ko pause ki khabar na jaye
 *                      (page ke apne suppressYT/suppressMP4 flags use hote hain)
 *   __wpResumeAt()  -> wapas app me aaye to page ke player ko native ki position par le ao
 *   __wpDiag()      -> debugging (kya chal raha hai, kaun sa id mila)
 *
 * Build 3 ka fix: YouTube video id ab 4 jagah se dhoondhi jati hai —
 *   getVideoData() -> getVideoUrl() -> page ka stateLocal.videoId -> nowPlaying DOM text
 * (pichli build sirf getVideoData() par thi, aur wo kai dafa khali wapas aata hai)
 */
object WebBridge {

    const val JS_BRIDGE: String = """
(function () {
  try {
    if (window.__wpBridgeReady) return 'ok';
    window.__wpBridgeReady = 1;

    /* ---- 1) asli media URL pakro (blob: ki jagah .m3u8 / .mp4) ---- */
    window.__wpLastMediaUrl = window.__wpLastMediaUrl || '';
    try {
      var oFetch = window.fetch;
      if (oFetch && !window.__wpFetchHooked) {
        window.__wpFetchHooked = 1;
        window.fetch = function (u) {
          try {
            var s = (typeof u === 'string') ? u : ((u && u.url) || '');
            if (/\.(m3u8|mp4|webm|mp3|m4a|ts)(\?|$)/i.test(s)) window.__wpLastMediaUrl = s;
          } catch (e) {}
          return oFetch.apply(this, arguments);
        };
      }
    } catch (e) {}
    try {
      var oOpen = XMLHttpRequest.prototype.open;
      if (oOpen && !window.__wpXhrHooked) {
        window.__wpXhrHooked = 1;
        XMLHttpRequest.prototype.open = function (m, u) {
          try {
            if (/\.(m3u8|mp4|webm|mp3|m4a)(\?|$)/i.test(u)) window.__wpLastMediaUrl = u;
          } catch (e) {}
          return oOpen.apply(this, arguments);
        };
      }
    } catch (e) {}

    /* ---- 2) YouTube video id — 4 sources ---- */
    window.__wpYtId = function (yt) {
      var id = '';
      try {
        if (yt && yt.getVideoData) {
          var d = yt.getVideoData() || {};
          if (d.video_id) id = d.video_id;
        }
      } catch (e) {}
      if (!id) {
        try {
          if (yt && yt.getVideoUrl) {
            var u = yt.getVideoUrl() || '';
            var m = /[?&]v=([A-Za-z0-9_-]{11})/.exec(u);
            if (m) id = m[1];
          }
        } catch (e) {}
      }
      if (!id) {
        try { if (typeof stateLocal !== 'undefined' && stateLocal && stateLocal.videoId) id = stateLocal.videoId; } catch (e) {}
      }
      if (!id) {
        try {
          var np = document.getElementById('now-playing');
          if (np && np.textContent) {
            var m2 = /([A-Za-z0-9_-]{11})\s*${'$'}/.exec(np.textContent.trim());
            if (m2) id = m2[1];
          }
        } catch (e) {}
      }
      if (!id) {
        try {
          var ed = document.getElementById('yt-player');
          if (ed && ed.src) {
            var m3 = /[?&]v=([A-Za-z0-9_-]{11})|\/embed\/([A-Za-z0-9_-]{11})/.exec(ed.src);
            if (m3) id = m3[1] || m3[2];
          }
        } catch (e) {}
      }
      return id;
    };

    /* ---- 3) snapshot ---- */
    window.__wpSnap = function () {
      try {
        var t = (typeof currentType !== 'undefined') ? currentType : 'none';
        var pos = 0, playing = false, id = '', title = '';
        var tp = document.getElementById('premium-video-title');
        if (tp && tp.textContent) title = tp.textContent;
        if (!title) { var mt = document.getElementById('mini-title'); if (mt && mt.textContent) title = mt.textContent; }

        if (t === 'youtube') {
          var yt = (typeof ytPlayer !== 'undefined') ? ytPlayer : null;
          id = window.__wpYtId(yt);
          try { pos = (yt && yt.getCurrentTime) ? (yt.getCurrentTime() || 0) : 0; } catch (e) {}
          try {
            if (yt && yt.getPlayerState) {
              var st = yt.getPlayerState();
              playing = (st === 1) || (st === 3);   /* playing ya buffering */
            }
          } catch (e) {}
          /* YouTube ka apna 'chal raha' flag bhi dekho */
          try { if (!playing && typeof ytPlaying !== 'undefined' && ytPlaying) playing = true; } catch (e) {}
          if (!title) { try { if (typeof pcTitle === 'function') title = pcTitle(); } catch (e) {} }
        } else if (t === 'mp4' || t === 'mp3' || t === 'hls') {
          var v = document.getElementById('mp4-player');
          if (v) {
            pos = v.currentTime || 0;
            playing = !v.paused && !v.ended;
            id = v.currentSrc || v.src || '';
            if (id.indexOf('blob:') === 0 && window.__wpLastMediaUrl) id = window.__wpLastMediaUrl;
          }
          if (!title) { var t2 = document.getElementById('mp3-title'); if (t2 && t2.textContent) title = t2.textContent; }
        }
        var qi = -1;
        try { if (typeof queueLocal !== 'undefined' && queueLocal && typeof queueLocal.index === 'number') qi = queueLocal.index; } catch (e) {}
        return JSON.stringify({ t: t, pos: pos, playing: playing, id: id, title: title, qi: qi });
      } catch (e) { return ''; }
    };

    /* ---- 3b) debugging ---- */
    window.__wpDiag = function () {
      try {
        var s = JSON.parse(window.__wpSnap() || '{}');
        var yt = (typeof ytPlayer !== 'undefined') ? ytPlayer : null;
        var st = -1;
        try { if (yt && yt.getPlayerState) st = yt.getPlayerState(); } catch (e) {}
        return JSON.stringify({
          t: s.t, id: s.id, playing: s.playing, pos: s.pos,
          ytState: st, bridge: 1, lastMedia: window.__wpLastMediaUrl || ''
        });
      } catch (e) { return '{}'; }
    };

    /* ---- 4) page player ko chup karao (party ko pause ki khabar NAHI jayegi) ---- */
    window.__wpPausePage = function () {
      try { if (typeof suppressYT !== 'undefined') suppressYT = true; } catch (e) {}
      try { if (typeof suppressMP4 !== 'undefined') suppressMP4 = true; } catch (e) {}
      try { if (typeof ytPlayer !== 'undefined' && ytPlayer && ytPlayer.pauseVideo) ytPlayer.pauseVideo(); } catch (e) {}
      try { var v = document.getElementById('mp4-player'); if (v && !v.paused) v.pause(); } catch (e) {}
      setTimeout(function () {
        try { suppressYT = false; } catch (e) {}
        try { suppressMP4 = false; } catch (e) {}
      }, 2500);
      return 'ok';
    };

    /* ---- 5) wapas page ko control do (native ki position par) ---- */
    window.__wpResumeAt = function (pos, play, ytId) {
      try {
        var t = (typeof currentType !== 'undefined') ? currentType : 'none';
        if (t === 'youtube' && typeof ytPlayer !== 'undefined' && ytPlayer) {
          try { if (typeof suppressYT !== 'undefined') suppressYT = true; } catch (e) {}
          var cur = '';
          try { cur = (ytPlayer.getVideoData() || {}).video_id || ''; } catch (e) {}
          if (ytId && cur !== ytId && ytPlayer.loadVideoById) {
            /* background me iframe ne video chhod di? -> wapas load karo usi second se */
            try { ytPlayer.loadVideoById(ytId, (pos > 0 ? pos : 0)); } catch (e) {}
          } else {
            try { if (pos > 0 && ytPlayer.seekTo) ytPlayer.seekTo(pos, true); } catch (e) {}
            try { if (play && ytPlayer.playVideo) ytPlayer.playVideo(); } catch (e) {}
          }
          setTimeout(function () { try { suppressYT = false; } catch (e) {} }, 2500);
        } else {
          var v = document.getElementById('mp4-player');
          if (v) {
            try { if (typeof suppressMP4 !== 'undefined') suppressMP4 = true; } catch (e) {}
            try { if (pos > 0) v.currentTime = pos; } catch (e) {}
            try { if (typeof markPlayingIntent === 'function') markPlayingIntent(4000); } catch (e) {}
            try { if (play) v.play(); } catch (e) {}
            setTimeout(function () { try { suppressMP4 = false; } catch (e) {} }, 2500);
          }
        }
        /* page ke apne play-logic se bhi ek dafa koshish (queue/UI/MQTT sync theek rahe) */
        if (play) {
          try { if (typeof markPlayingIntent === 'function') markPlayingIntent(4000); } catch (e) {}
          try { if (typeof userPlayEverywhere === 'function') userPlayEverywhere(); } catch (e) {}
        }
        return 'ok';
      } catch (e) { return 'fail'; }
    };

    /* ---- 6) page chal raha hai ya nahi (turant jawab) ---- */
    window.__wpIsPagePlaying = function () {
      try {
        var t = (typeof currentType !== 'undefined') ? currentType : 'none';
        if (t === 'youtube') {
          var yt = (typeof ytPlayer !== 'undefined') ? ytPlayer : null;
          if (!yt) return '0';
          try { var st = yt.getPlayerState ? yt.getPlayerState() : 0; return (st === 1 || st === 3) ? '1' : '0'; } catch (e) { return '0'; }
        }
        var v = document.getElementById('mp4-player');
        if (!v) return '0';
        return (!v.paused && !v.ended) ? '1' : '0';
      } catch (e) { return '0'; }
    };

    /* ---- 7) queue padho (background auto-next isi se hota hai) ---- */
    window.__wpQueue = function () {
      try {
        var q = (typeof queueLocal !== 'undefined') ? queueLocal : null;
        if (!q) return '{}';
        var items = (q.items || []).map(function (it) {
          it = it || {};
          return {
            type: it.type || '',
            url: it.url || '',
            videoId: it.videoId || '',
            label: it.label || it.title || ''
          };
        });
        var idx = (typeof q.index === 'number') ? q.index : 0;
        return JSON.stringify({ index: idx, items: items });
      } catch (e) { return '{}'; }
    };

    /* ---- 8) page ko item load karwao (auto-next ke baad sync) ---- */
    window.__wpPlayItem = function (itemJson) {
      try {
        var it = JSON.parse(itemJson);
        if (typeof loadVideoLocal === 'function') { loadVideoLocal(it, true); return 'ok'; }
        return 'fail';
      } catch (e) { return 'fail'; }
    };

    /* ---- 9) page ka queue index set karo (UI + MQTT sync theek rahe) ---- */
    window.__wpSetQueueIndex = function (i) {
      try {
        if (typeof queueLocal === 'undefined' || !queueLocal) return 'fail';
        queueLocal.index = i;
        queueLocal.lastAdvance = Date.now();
        if (typeof pubQueue === 'function') pubQueue();
        return 'ok';
      } catch (e) { return 'fail'; }
    };

    /* ---- 10) page ke saare media chup karao / wapas kholo (aawaz native MPV ki) ---- */
    window.__wpSetMute = function (m) {
      try {
        var els = document.querySelectorAll('video, audio');
        for (var i = 0; i < els.length; i++) {
          var el = els[i];
          if (!el) continue;
          try {
            if (m) {
              if (el.__wpVol0 === undefined) {
                el.__wpVol0 = (typeof el.volume === 'number') ? el.volume : 1;
                el.__wpMuted0 = !!el.muted;
              }
              el.muted = true;
              el.volume = 0;
            } else if (el.__wpVol0 !== undefined) {
              el.muted = !!el.__wpMuted0;
              el.volume = el.__wpVol0;
              delete el.__wpVol0; delete el.__wpMuted0;
            }
          } catch (e) {}
        }
        /* YouTube ka player IFRAME ke andar hota hai -> DOM se nahi milta, YT API se mute karo */
        try {
          var yt = (typeof ytPlayer !== 'undefined') ? ytPlayer : null;
          if (yt && yt.mute) {
            if (m) {
              if (window.__wpYtWasMuted === undefined) {
                var wm = false;
                try { wm = (yt.isMuted && yt.isMuted()) ? true : false; } catch (e) {}
                window.__wpYtWasMuted = wm;
              }
              try { yt.mute(); } catch (e) {}
            } else if (window.__wpYtWasMuted !== undefined) {
              if (!window.__wpYtWasMuted) { try { yt.unMute(); } catch (e) {} }
              delete window.__wpYtWasMuted;
            }
          }
        } catch (e) {}
        return 'ok';
      } catch (e) { return 'fail'; }
    };

    /* ---- 14) player area ka rect (MPV surface ke liye) ---- */
    window.__wpRect = function () {
      try {
        var wrap = document.querySelector('.player-wrap');
        if (!wrap || !wrap.getBoundingClientRect) return '{}';
        var r = wrap.getBoundingClientRect();
        var mini = !!(wrap.classList && wrap.classList.contains('mini'));
        return JSON.stringify({ x: r.left, y: r.top, w: r.width, h: r.height, mini: mini });
      } catch (e) { return '{}'; }
    };

    /* ---- 15) player area me "hole": page ka video chhupa do -> MPV ki video wahan dikhti hai ---- */
    window.__wpVideoHole = function (on) {
      try {
        var st = document.getElementById('wp-mpv-hole');
        if (!st) {
          st = document.createElement('style');
          st.id = 'wp-mpv-hole';
          (document.head || document.documentElement).appendChild(st);
        }
        var ids = ['wp-mpv-bt', 'wp-mpv-bl', 'wp-mpv-br', 'wp-mpv-bb'];
        var bars = [];
        for (var i = 0; i < 4; i++) {
          var b = document.getElementById(ids[i]);
          if (!b) {
            b = document.createElement('div');
            b.id = ids[i];
            b.style.cssText = 'position:fixed;z-index:-1;pointer-events:none;display:none;';
            (document.body || document.documentElement).appendChild(b);
          }
          bars.push(b);
        }
        if (!on) {
          st.textContent = '';
          for (var j = 0; j < 4; j++) bars[j].style.display = 'none';
          window.__wpMpvHole = 0;
          return 'ok';
        }
        /* page ka video chhupa do + body ka background hata do (asli hole) */
        st.textContent =
          'html,body{background:transparent !important;}' +
          '.player-wrap{background:transparent !important;}' +
          '.player-wrap:fullscreen,.player-wrap:-webkit-full-screen,.player-wrap.pp-fs{background:transparent !important;}' +
          'html:fullscreen,body:fullscreen{background:transparent !important;}' +
          '.player-wrap>#yt-player,.player-wrap>iframe{opacity:0 !important;pointer-events:none !important;}' +
          '.player-wrap video,.player-wrap>#mp4-player{visibility:hidden !important;}' +
          'body.wp-mpv-on .player-wrap:not(.mini) #premium-video-ui{display:flex !important;flex-direction:column !important;justify-content:space-between !important;opacity:1 !important;visibility:visible !important;}' +
          'body.wp-mpv-on #premium-video-ui.controls-hidden{opacity:1 !important;background:transparent !important;}' +
          'body.wp-mpv-on #premium-video-ui.controls-hidden .premium-video-top,' +
          'body.wp-mpv-on #premium-video-ui.controls-hidden .premium-video-center,' +
          'body.wp-mpv-on #premium-video-ui.controls-hidden .premium-video-bottom{opacity:1 !important;}' +
          /* MPV mode: quality button dikhao (page ne isay display:none kar rakha hai) */
          'body.wp-mpv-on #premium-video-quality{display:inline-block !important;max-width:74px !important;height:27px !important;}' +
          'body.wp-mpv-on #premium-video-quality.hidden{display:inline-block !important;}';
        var wrap = document.querySelector('.player-wrap');
        var r = (wrap && wrap.getBoundingClientRect) ? wrap.getBoundingClientRect() : null;
        var W = window.innerWidth || document.documentElement.clientWidth;
        var H = window.innerHeight || document.documentElement.clientHeight;
        var col = '#0f0c29';
        try { var v = getComputedStyle(document.documentElement).getPropertyValue('--wp-bg1'); if (v && v.trim()) col = v.trim(); } catch (e) {}
        var grad = 'linear-gradient(135deg,' + col + ',#302b63)';
        var base = 'position:fixed;z-index:-1;pointer-events:none;background:' + grad + ';';
        if (!r || r.width < 40 || r.height < 40) {
          /* rect nahi mila -> poora background wapas (video chhupane ka koi faida nahi) */
          bars[0].style.cssText = base + 'left:0;top:0;right:0;bottom:0;';
          bars[0].style.display = 'block';
          for (var k = 1; k < 4; k++) bars[k].style.display = 'none';
          window.__wpMpvHole = 1;
          return '{}';
        }
        var x = Math.max(0, Math.round(r.left));
        var y = Math.max(0, Math.round(r.top));
        var w = Math.round(r.width);
        var h = Math.round(r.height);
        var bot = Math.max(0, H - (y + h));
        bars[0].style.cssText = base + 'left:0;top:0;right:0;height:' + y + 'px;';
        bars[1].style.cssText = base + 'left:0;top:' + y + 'px;width:' + x + 'px;height:' + h + 'px;';
        bars[2].style.cssText = base + 'left:' + (x + w) + 'px;top:' + y + 'px;right:0;height:' + h + 'px;';
        bars[3].style.cssText = base + 'left:0;top:' + (y + h) + 'px;right:0;height:' + bot + 'px;';
        for (var m = 0; m < 4; m++) bars[m].style.display = 'block';
        window.__wpMpvHole = 1;
        return JSON.stringify({ x: x, y: y, w: w, h: h, mini: !!(wrap.classList && wrap.classList.contains('mini')) });
      } catch (e) { return 'fail'; }
    };

    /* ---- 15b) MPV FULLSCREEN: Android ka alag (kala) fullscreen WebView MPV ko dhak deta hai.
       Is liye apna fullscreen: page ka player poori screen + baaqi sab chhupa do -> MPV dikhti hai. ---- */
    window.__wpMpvFsCss = function () {
      try {
        if (document.getElementById('wp-mpv-fs')) return 'ok';
        var st = document.createElement('style');
        st.id = 'wp-mpv-fs';
        st.textContent =
          'body.wp-mpv-fs{background:transparent !important;}' +
          'body.wp-mpv-fs > *:not(.wpfs-keep){display:none !important;}' +
          'body.wp-mpv-fs .wpfs-keep > *:not(.wpfs-keep){display:none !important;}' +
          'body.wp-mpv-fs .player-wrap{position:fixed !important;top:0 !important;left:0 !important;right:0 !important;bottom:0 !important;' +
          'width:100vw !important;height:100vh !important;max-height:none !important;aspect-ratio:auto !important;' +
          'border-radius:0 !important;border:0 !important;margin:0 !important;z-index:99990 !important;background:transparent !important;}' +
          'body.wp-mpv-fs .pc-collapse{display:none !important;}' +
          'body.wp-mpv-fs .player-wrap video,body.wp-mpv-fs .player-wrap>#mp4-player,body.wp-mpv-fs .player-wrap>iframe{visibility:hidden !important;}' +
          'body.wp-mpv-fs #premium-video-ui{display:flex !important;flex-direction:column !important;justify-content:space-between !important;opacity:1 !important;}';
        (document.head || document.documentElement).appendChild(st);
        return 'ok';
      } catch (e) { return 'fail'; }
    };

    window.__wpMpvFsSet = function (on) {
      try {
        var w = document.querySelector('.player-wrap');
        if (!w) return 'no-wrap';
        var chain = [];
        var el = w;
        while (el && el !== document.body && el !== document.documentElement) { chain.push(el); el = el.parentElement; }
        for (var i = 0; i < chain.length; i++) {
          try { if (on) chain[i].classList.add('wpfs-keep'); else chain[i].classList.remove('wpfs-keep'); } catch (e) {}
        }
        try { if (on) document.body.classList.add('wp-mpv-fs'); else document.body.classList.remove('wp-mpv-fs'); } catch (e) {}
        window.__wpMpvFsOn = on ? 1 : 0;
        try { if (window.YaarNative && window.YaarNative.mpvCmd) window.YaarNative.mpvCmd(on ? 'fs:1' : 'fs:0'); } catch (e) {}
        try { window.__wpMpvAssert(); } catch (e) {}
        try { window.__wpVideoHole(true); } catch (e) {}
        return 'ok';
      } catch (e) { return 'fail'; }
    };

    window.__wpMpvFsToggle = function () { return window.__wpMpvFsSet(!window.__wpMpvFsOn); };

    /* ---- 16) MPV mode: page ke controls (play/pause/seek/mute) seedha MPV par bhejo ---- */
    window.__wpMpvCmd = function (c) {
      try { if (window.YaarNative && window.YaarNative.mpvCmd) window.YaarNative.mpvCmd(String(c)); } catch (e) {}
    };
    window.__wpMpvLink = function (on) {
      try {
        var ui = document.getElementById('premium-video-ui');
        var pb = document.getElementById('premium-video-progress');
        if (on) {
          if (!window.__wpMpvLinked) {
            window.__wpMpvLinked = 1;
            window.__wpMpvState = { t: 0, dur: 0, playing: false, muted: false, at: 0 };
            window.__wpMpvSeekGrace = 0;
            window.__wpMpvOrig = {
              togglePlay: window.premiumTogglePlay,
              seekBy: window.premiumSeekBy,
              toggleMute: window.premiumToggleMute,
              mediaState: window.premiumMediaState,
              playOnclick: null, bigOnclick: null, muteOnclick: null, pbOnclick: pb ? pb.onclick : null,
              /* party sync (MQTT se aane wali commands) — ye bhi MPV par lagengi */
              remotePlay: window.remotePlay,
              remotePause: window.remotePause,
              remoteSeek: window.remoteSeek,
              applyState: window.applyState
            };
            var ePlay = document.getElementById('premium-video-play');
            var eBig = document.getElementById('premium-video-big-play');
            var eMute = document.getElementById('premium-video-mute');
            var eFs = document.getElementById('premium-video-fullscreen');
            if (ePlay) window.__wpMpvOrig.playOnclick = ePlay.onclick;
            if (eBig) window.__wpMpvOrig.bigOnclick = eBig.onclick;
            if (eMute) window.__wpMpvOrig.muteOnclick = eMute.onclick;
            if (eFs) window.__wpMpvOrig.fsOnclick = eFs.onclick;
            try { window.__wpMpvFsCss(); } catch (e) {}
            var O = window.__wpMpvOrig;

            /* page ka apna kaam bhi ho (party sync/queue/auto-next) + MPV ko command bhi */
            window.premiumTogglePlay = function () {
              try { if (O.togglePlay) O.togglePlay.apply(this, arguments); } catch (e) {}
              window.__wpMpvCmd('toggle');
            };
            window.premiumSeekBy = function (d) {
              try { if (O.seekBy) O.seekBy.apply(this, arguments); } catch (e) {}
              window.__wpMpvCmd('seekrel:' + d);
            };
            window.premiumToggleMute = function () {
              window.__wpMpvCmd('mute');
              try { window.__wpSetMute(true); } catch (e) {}
            };
            /* time line / mini clock / icons ab MPV ki ASLI state se */
            window.premiumMediaState = function () {
              var base = {};
              try { if (O.mediaState) base = O.mediaState.apply(this, arguments) || {}; } catch (e) { base = {}; }
              try {
                if (!window.__wpMpvLinked) return base;
                if (typeof currentType !== 'undefined' && currentType !== 'youtube') return base;
                if (Date.now() < (window.__wpMpvSeekGrace || 0)) return base;
                var S = window.__wpMpvState || {};
                if (!S.at || Date.now() - S.at > 3000) return base;
                return { time: S.t, duration: (S.dur > 0 ? S.dur : (base.duration || 0)), playing: !!S.playing, muted: !!S.muted };
              } catch (e) { return base; }
            };
            var pbWrap = function (ev) {
              window.__wpMpvSeekGrace = Date.now() + 900;
              try { if (O.pbOnclick) O.pbOnclick.call(this, ev); } catch (e) {}
              setTimeout(function () {
                try { var m = window.premiumMediaState(); if (m && m.time >= 0) window.__wpMpvCmd('seekabs:' + m.time); } catch (e) {}
              }, 350);
            };
            /* ---- PARTY SYNC: doosron ki commands (play/pause/seek/sync/state) bhi MPV par lagao.
               Page (MQTT) apni rule waise hi chalatа hai (chhupa iframe update hota rehta hai),
               magar asli tasveer/aawaz MPV ki hai — is liye wahan bhi wahi command jaani chahiye. ---- */
            window.remotePlay = function (t, force) {
              var doApply = true;
              try { if (!force && typeof localHoldUntil !== 'undefined' && Date.now() < localHoldUntil) doApply = false; } catch (e) {}
              try { if (O.remotePlay) O.remotePlay.apply(this, arguments); } catch (e) {}
              try {
                if (doApply && window.__wpMpvLinked) {
                  if (typeof t === 'number' && isFinite(t) && t >= 0) window.__wpMpvCmd('seekabs:' + t);
                  window.__wpMpvCmd('play');
                }
              } catch (e) {}
            };
            window.remotePause = function (t, force) {
              var doApply = true;
              try {
                if (!force && typeof playGraceUntil !== 'undefined') {
                  var nt = 0;
                  try { nt = (typeof localNow === 'function') ? localNow() : 0; } catch (e2) {}
                  if (Date.now() < playGraceUntil && Math.abs(nt - (isFinite(t) ? t : 0)) < 6) doApply = false;
                }
              } catch (e) {}
              try { if (O.remotePause) O.remotePause.apply(this, arguments); } catch (e) {}
              try {
                if (doApply && window.__wpMpvLinked) {
                  if (typeof t === 'number' && isFinite(t) && t >= 0) window.__wpMpvCmd('seekabs:' + t);
                  window.__wpMpvCmd('pause');
                }
              } catch (e) {}
            };
            window.remoteSeek = function (t) {
              try { if (O.remoteSeek) O.remoteSeek.apply(this, arguments); } catch (e) {}
              try {
                if (window.__wpMpvLinked && typeof t === 'number' && isFinite(t) && t >= 0) {
                  window.__wpMpvCmd('seekabs:' + t);
                }
              } catch (e) {}
            };
            /* naya member aaya / state mili (retained) -> MPV bhi usi position par aa jaye */
            window.applyState = function (s) {
              try { if (O.applyState) O.applyState.apply(this, arguments); } catch (e) {}
              try {
                if (window.__wpMpvLinked && s && s.type === 'youtube') {
                  var st = (typeof s.time === 'number' && isFinite(s.time)) ? s.time : 0;
                  var sp = !!s.playing;
                  setTimeout(function () {
                    try {
                      if (!window.__wpMpvLinked) return;
                      window.__wpMpvCmd('seekabs:' + st);
                      window.__wpMpvCmd(sp ? 'play' : 'pause');
                    } catch (e) {}
                  }, 1500);
                }
              } catch (e) {}
            };

            window.__wpMpvWrap = {
              play: function () { window.premiumTogglePlay(); },
              seekBy: window.premiumSeekBy,
              mute: function () { window.premiumToggleMute(); },
              fs: function () { window.__wpMpvFsToggle(); },
              pb: pbWrap
            };
          }
          window.__wpMpvAssert();
          try {
            /* 🎚️ QUALITY BUTTON: 144p / 240p / 360p (MPV par lagta hai) */
            var q = document.getElementById('premium-video-quality');
            if (q) {
              if (!window.__wpMpvQBound) {
                window.__wpMpvQBound = 1;
                q.addEventListener('change', function () {
                  window.__wpMpvQVal = q.value;
                  window.__wpMpvCmd('quality:' + q.value);
                });
              }
              if (!q.options || q.options.length !== 3) {
                q.innerHTML = '<option value="144">144p</option><option value="240">240p</option><option value="360">360p</option>';
              }
              q.value = String(window.__wpMpvQVal || 144);
              q.classList.remove('hidden');
              q.style.display = 'inline-block';
            }
          } catch (e) {}
          try { updatePremiumVideoUI(); } catch (e) {}
          try { updateMiniInfo(); } catch (e) {}
          return 'ok';
        }
        window.__wpMpvLinked = 0;
        try { var status = document.getElementById('wp-mpv-test-status'); if(status)status.remove(); } catch(e) {}
        try { document.body.classList.remove('wp-mpv-on'); } catch (e) {}
        try { if (window.__wpMpvFsSet) window.__wpMpvFsSet(0); } catch (e) {}
        var Q = window.__wpMpvOrig || {};
        try {
          if (Q.togglePlay) window.premiumTogglePlay = Q.togglePlay;
          if (Q.seekBy) window.premiumSeekBy = Q.seekBy;
          if (Q.toggleMute) window.premiumToggleMute = Q.toggleMute;
          if (Q.mediaState) window.premiumMediaState = Q.mediaState;
          if (Q.remotePlay) window.remotePlay = Q.remotePlay;
          if (Q.remotePause) window.remotePause = Q.remotePause;
          if (Q.remoteSeek) window.remoteSeek = Q.remoteSeek;
          if (Q.applyState) window.applyState = Q.applyState;
          var pe = document.getElementById('premium-video-play');
          if (pe && Q.playOnclick) pe.onclick = Q.playOnclick;
          var be = document.getElementById('premium-video-big-play');
          if (be && Q.bigOnclick) be.onclick = Q.bigOnclick;
          var me = document.getElementById('premium-video-mute');
          if (me && Q.muteOnclick) me.onclick = Q.muteOnclick;
          var fe2 = document.getElementById('premium-video-fullscreen');
          if (fe2 && Q.fsOnclick) fe2.onclick = Q.fsOnclick;
          var q2 = document.getElementById('premium-video-quality');
          if (q2) { q2.style.display = ''; q2.classList.add('hidden'); }
          if (pb && Q.pbOnclick) pb.onclick = Q.pbOnclick;
          if (ui) {
            ui.style.display = ''; ui.style.opacity = ''; ui.style.pointerEvents = '';
            var ct = '';
            try { ct = currentType; } catch (e2) {}
            if (ct === 'youtube') { ui.classList.add('hidden'); ui.classList.add('youtube-native'); }
          }
        } catch (e) {}
        return 'ok';
      } catch (e) { return 'fail'; }
    };

    /* MPV mode ka UI theek rakho: control bar wapas dikhao (youtube-native usay chhupa deta hai) + handlers bandho */
    window.__wpMpvAssert = function () {
      try {
        var ui = document.getElementById('premium-video-ui');
        if (ui) {
          ui.classList.remove('hidden');
          ui.classList.remove('youtube-native');
          ui.classList.remove('controls-hidden');
          ui.style.opacity = '1';
          ui.style.pointerEvents = '';   /* container ka none rehne do — sirf controls tap len */
          try {
            var wp = document.querySelector('.player-wrap');
            var isMini = !!(wp && wp.classList && wp.classList.contains('mini'));
            /* asli layout: flex column + space-between => controls NEECHE, bada play CENTER me */
            ui.style.display = isMini ? '' : 'flex';
          } catch (e) { ui.style.display = 'flex'; }
        }
        var W = window.__wpMpvWrap;
        if (W) {
          var pe = document.getElementById('premium-video-play');
          if (pe && pe.onclick !== W.play) pe.onclick = W.play;
          var be = document.getElementById('premium-video-big-play');
          if (be && be.onclick !== W.play) be.onclick = W.play;
          var me = document.getElementById('premium-video-mute');
          if (me && me.onclick !== W.mute) me.onclick = W.mute;
          var pb = document.getElementById('premium-video-progress');
          if (pb && W.pb && pb.onclick !== W.pb) pb.onclick = W.pb;
          var fe = document.getElementById('premium-video-fullscreen');
          if (fe && W.fs && fe.onclick !== W.fs) fe.onclick = W.fs;
        }
        try { document.body.classList.add('wp-mpv-on'); } catch (e) {}
        return 'ok';
      } catch (e) { return 'fail'; }
    };

    /* ---- 16c) slow net: "⏳ Buffering" overlay ---- */
    window.__wpMpvBuf = function (on, pct) {
      try {
        var wrap = document.querySelector('.player-wrap');
        var d = document.getElementById('wp-mpv-buf');
        if (!d) {
          d = document.createElement('div');
          d.id = 'wp-mpv-buf';
          d.style.cssText = 'position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);z-index:30;' +
            'padding:10px 16px;border-radius:14px;background:rgba(10,8,25,.88);border:1px solid rgba(255,255,255,.22);' +
            'color:#fff;font:800 12px/1.3 system-ui,sans-serif;text-align:center;display:none;pointer-events:none;' +
            'box-shadow:0 10px 26px rgba(0,0,0,.45);';
          (wrap || document.body).appendChild(d);
        }
        if (on) {
          d.textContent = '⏳ Buffering…' + (pct > 0 ? ' ' + pct + '%' : '');
          d.style.display = 'block';
        } else {
          d.style.display = 'none';
        }
        return 'ok';
      } catch (e) { return 'fail'; }
    };

    /* ---- 16d) MPV ki chuni hui quality select par dikhao ---- */
    window.__wpMpvQ = function (sel) {
      try {
        if(window.__wpOnlyQuality)window.__wpOnlyQuality(sel);
        window.__wpMpvQVal = sel;
        var q = document.getElementById('premium-video-quality');
        if (q) {
          if (!q.options || q.options.length !== 3) {
            q.innerHTML = '<option value="144">144p</option><option value="240">240p</option><option value="360">360p</option>';
          }
          q.value = String(sel);
          q.classList.remove('hidden');
          if (window.__wpMpvLinked) q.style.display = 'inline-block';
        }
        return 'ok';
      } catch (e) { return 'fail'; }
    };

    /* ---- 17) MPV ki asli timing page par (time line + icons + mini clock) ---- */
    window.__wpMpvTime = function (t, playing, dur, muted) {
      try {
        window.__wpMpvState = { t: t, dur: dur || 0, playing: !!playing, muted: !!muted, at: Date.now() };
        if (!window.__wpMpvLinked) return 'off';
        window.__wpMpvAssert();
        var f = function (s) {
          s = Math.max(0, Math.floor(s || 0));
          var h = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
          var p = function (n) { return (n < 10 ? '0' : '') + n; };
          return (h > 0 ? h + ':' : '') + p(mm) + ':' + p(ss);
        };
        var d = (dur && dur > 0) ? dur : 0;
        if (!d) { try { var m0 = premiumMediaState(); d = (m0 && m0.duration) || 0; } catch (e) {} }
        var pct = (d > 0) ? Math.min(100, (t / d) * 100) : 0;
        var fill = document.getElementById('premium-video-fill');
        var dot = document.getElementById('premium-video-dot');
        var tt = document.getElementById('premium-video-time');
        var pbtn = document.getElementById('premium-video-play');
        var bbtn = document.getElementById('premium-video-big-play');
        if (fill) fill.style.width = pct + '%';
        if (dot) dot.style.left = pct + '%';
        if (tt) tt.textContent = f(t) + ' / ' + (d > 0 ? f(d) : '--:--');
        var ic = playing ? '❚❚' : '▶';
        if (pbtn) pbtn.textContent = ic;
        if (bbtn) bbtn.textContent = ic;
        return 'ok';
      } catch (e) { return 'fail'; }
    };

    /* ---- 18b) fullscreen on/off -> foran MPV surface ka rect dobara set karo ---- */
    try {
      var wpFsPing = function () {
        try { if (window.YaarNative && window.YaarNative.mpvCmd) window.YaarNative.mpvCmd('rect'); } catch (e) {}
        try { if (window.__wpMpvLinked && window.__wpMpvAssert) window.__wpMpvAssert(); } catch (e) {}
      };
      ['fullscreenchange', 'webkitfullscreenchange'].forEach(function (ev) {
        document.addEventListener(ev, wpFsPing);
        window.addEventListener(ev, function () { wpFsPing(); setTimeout(wpFsPing, 250); setTimeout(wpFsPing, 700); });
      });
      window.addEventListener('resize', function () { if (window.__wpMpvLinked) wpFsPing(); });
    } catch (e) {}

    /* ---- 18) page par naya YouTube item -> foran native ko batao (MPV der se shuru na ho) ---- */
    try {
      window.__wpYtSeenId = window.__wpYtSeenId || '';
      setInterval(function () {
        try {
          var ct = '';
          try { ct = currentType; } catch (e) {}
          if (ct !== 'youtube') { window.__wpYtSeenId = ''; return; }
          var id = window.__wpYtId ? window.__wpYtId() : '';
          if (id && id.length === 11 && id !== window.__wpYtSeenId) {
            window.__wpYtSeenId = id;
            if (window.YaarNative && window.YaarNative.ytSeen) window.YaarNative.ytSeen(id);
          }
        } catch (e) {}
      }, 500);
    } catch (e) {}

    return 'ok';
  } catch (e) { return 'fail'; }
})()
"""

    /** MPV video: player area ka rect (CSS px me JSON). */
    const val JS_RECT: String = "window.__wpRect ? window.__wpRect() : '{}'"

    /** MPV video: page ke player area me "hole" on/off. */
    fun holeJs(on: Boolean): String = "window.__wpVideoHole ? window.__wpVideoHole($on) : 'fail'"

    /** Page ke saare media chup karao / wapas kholo (aawaz MPV ki rahe). */
    fun muteJs(m: Boolean): String = "window.__wpSetMute ? window.__wpSetMute($m) : 'fail'"

    /** Page ke controls (play/pause/seek/mute) -> MPV. */
    const val JS_MPV_ON: String = "window.__wpMpvLink ? window.__wpMpvLink(1) : 'fail'"
    const val JS_MPV_OFF: String = "window.__wpMpvLink ? window.__wpMpvLink(0) : 'fail'"

    /** MPV ki asli timing page par (time line + icons + mini clock). */
    fun mpvTimeJs(t: Double, playing: Boolean, dur: Double, muted: Boolean): String =
        "window.__wpMpvTime ? window.__wpMpvTime($t, $playing, $dur, $muted) : 'fail'"

    /** MPV ki chuni hui quality page ke select par dikhao. */
    fun mpvQualityJs(h: Int): String = "window.__wpMpvQ ? window.__wpMpvQ($h) : 'fail'"

    /** Slow net: "⏳ Buffering" overlay on/off. */
    fun mpvBufJs(on: Boolean, pct: Int): String =
        "window.__wpMpvBuf ? window.__wpMpvBuf($on, $pct) : 'fail'"

    /** Page ka snapshot maangne wali JS. */
    const val JS_SNAP: String = "window.__wpSnap ? window.__wpSnap() : ''"

    /** Page ke player ko chup karane wali JS. */
    const val JS_PAUSE_PAGE: String = "window.__wpPausePage ? window.__wpPausePage() : ''"

    /** Debug info. */
    const val JS_DIAG: String = "window.__wpDiag ? window.__wpDiag() : '{}'"

    /** Page chal raha hai? ('1' / '0') */
    const val JS_IS_PAGE_PLAYING: String = "window.__wpIsPagePlaying ? window.__wpIsPagePlaying() : '0'"

    /** Queue (items + index) JSON. */
    const val JS_QUEUE: String = "window.__wpQueue ? window.__wpQueue() : '{}'"

    /** Page me item load karo: __wpPlayItem(<json>) */
    fun playItemJs(itemJson: String): String =
        "window.__wpPlayItem ? window.__wpPlayItem(${quoteJson(itemJson)}) : ''"

    /** Page ka queue index set karo. */
    fun setQueueIndexJs(index: Int): String =
        "window.__wpSetQueueIndex ? window.__wpSetQueueIndex($index) : ''"

    private fun quoteJson(s: String): String = JSONObject.quote(s)

    /** evaluateJavascript ka quoted string -> saaf JSON text. */
    fun rawJson(res: String?): String? {
        if (res == null || res == "null") return null
        return try {
            JSONObject("{\"v\":$res}").getString("v")
        } catch (t: Throwable) {
            res.trim('"').ifBlank { null }
        }
    }

    /** Wapas page ko native ki position dene wali JS. id = YouTube video id (reload ke liye). */
    fun resumeJs(positionSeconds: Double, playAfterSeek: Boolean, ytId: String? = null): String {
        val idJson = if (ytId.isNullOrBlank()) "''" else "'" + ytId.replace("'", "") + "'"
        return "window.__wpResumeAt ? window.__wpResumeAt(${fmt(positionSeconds)}, $playAfterSeek, $idJson) : ''"
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)

    data class Snapshot(
        val type: String,
        val id: String,
        val position: Double,
        val playing: Boolean,
        val title: String,
        val queueIndex: Int = -1
    ) {
        val isYoutube: Boolean get() = type == "youtube"
        val isEmpty: Boolean get() = type == "none" || id.isBlank()
    }

    /** evaluateJavascript ka result quoted JSON string hota hai -> usay saaf karke parse karo. */
    fun parse(raw: String?): Snapshot? {
        val cleaned = unquote(raw) ?: return null
        return try {
            val o = JSONObject(cleaned)
            Snapshot(
                type = o.optString("t", "none"),
                id = o.optString("id", ""),
                position = o.optDouble("pos", 0.0),
                playing = o.optBoolean("playing", false),
                title = o.optString("title", ""),
                queueIndex = o.optInt("qi", -1)
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** Diag JSON ko readable string bana do (banner/log ke liye). */
    fun diagText(raw: String?): String = unquote(raw) ?: "(khali)"

    private fun unquote(res: String?): String? {
        if (res == null || res == "null") return null
        return try {
            JSONObject("{\"v\":$res}").getString("v")
        } catch (t: Throwable) {
            res.trim('"').ifBlank { null }
        }
    }
}
