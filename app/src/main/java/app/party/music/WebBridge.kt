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

    return 'ok';
  } catch (e) { return 'fail'; }
})()
"""

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
