/* ═══════════════════════════════════════════════════════════════════════
   WP ACTIVITY OVERLAY — JS (wahi module jo asli page me jayega)

   API:
     WPActivity.show(name, kind, a, b)
        kind = 'pause' | 'play' | 'seek' | 'join' | 'leave' | 'msg'
        pause/play : a = time (sec)
        seek       : a = from (sec), b = to (sec)
        join/leave : (time nahi)
        msg        : a = message text (room chat; 2 lines tak, 120 chars)
     WPActivity.fmt(sec)        -> "00:15:10"  (hamesha HH:MM:SS)
     WPActivity.isFullscreen()  -> browser (.wp-html-fs / .pp-fs / :fullscreen) ya APK (body.wp-mpv-fs)
     WPActivity.now()           -> current position: APK MPV (__wpMpvState.t) warna page ka localNow()
     WPActivity.config          -> { duration, dedupMs, max, newestOnTop, fullscreenOnly } (runtime tweak)

   Rules:
     - sirf fullscreen me dikhta hai (config.fullscreenOnly)
     - column (log style): PURANA upar, NAYA neeche; max 5 ek waqt me (activity + msg sab mila kar)
       6th aaye to sab se purana (upar wala) FORAN nikal jata hai (smooth collapse)
     - har toast 5s, phir fade-out + collapse + remove
     - dedup sirf EXACT duplicate publish ke liye: wahi banda + wahi action + wahi time
       (2.5s ke andar, aur beech me us bande ka koi aur action na ho) -> sirf pulse.
       Doosri seek (alag target) / pause->play->pause = alag events = alag toasts.
     - pointer-events none -> playback / controls kabhi block nahi
     - APK native MPV fullscreen (window.__wpMpvFsOn): WebView invisible hota hai, is liye
       toast DOM ki bajaye YaarNative.wpActivity(json) ko jata hai (app wahi design natively banata hai).
       Browser / purane APK me YaarNative.wpActivity nahi hota -> yeh raasta kabhi nahi chalta.
   ═══════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';
  if (window.WPActivity) return;

  var cfg = { duration: 5000, dedupMs: 2500, max: 5, newestOnTop: false, fullscreenOnly: true };
  var lastBy = {};          // name -> { kind, a, b, t, el }  (sirf us bande ka AAKHRI event)

  function wrap() { return document.querySelector('.player-wrap'); }

  function fmt(sec) {
    sec = Math.max(0, Math.floor(Number(sec) || 0));
    var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    var p = function (n) { return (n < 10 ? '0' : '') + n; };
    return p(h) + ':' + p(m) + ':' + p(s);
  }

  function isFullscreen() {
    try {
      var w = wrap();
      if (!w) return false;
      if (document.body && document.body.classList.contains('wp-mpv-fs')) return true;        // APK: MPV fullscreen
      if (w.classList.contains('wp-html-fs') || w.classList.contains('pp-fs')) return true;    // browser: v45 / v36
      var el = document.fullscreenElement || document.webkitFullscreenElement || document.webkitCurrentFullScreenElement;
      return !!el && (el === w || w.contains(el));
    } catch (e) { return false; }
  }

  function now() {
    try {   /* APK MPV mode: asli time MPV ka hai (bridge har 450ms __wpMpvState set karta hai) */
      var S = window.__wpMpvState;
      if (window.__wpMpvLinked && S && (Date.now() - (S.at || 0)) < 3000) return Number(S.t) || 0;
    } catch (e) {}
    try { if (typeof window.localNow === 'function') return Number(window.localNow()) || 0; } catch (e) {}
    return 0;
  }

  /* APK (v117-MPV023-ACT1+): native fullscreen me toast app ko do */
  function nativeSink() {
    try {
      var N = window.YaarNative;
      if (window.__wpMpvFsOn && N && typeof N.wpActivity === 'function') return N;
    } catch (e) {}
    return null;
  }

  function feed() {
    var w = wrap();
    if (!w) return null;
    var f = document.getElementById('wp-act-feed');
    if (!f) {
      f = document.createElement('div');
      f.id = 'wp-act-feed';
      f.className = 'wpfs-keep';            // APK ke MPV-fullscreen CSS se bachne ke liye
      f.setAttribute('aria-live', 'polite');
      w.appendChild(f);
    } else if (f.parentNode !== w) {
      w.appendChild(f);
    }
    return f;
  }

  var ICON = { pause: '❚❚', play: '▶', seek: '⏩', join: '🎉', leave: '👋', msg: '💬' };
  var WORD = { pause: 'Paused', play: 'Resumed', seek: 'Seek', join: 'Joined', leave: 'Left', msg: '' };
  var MSG_MAX = 120;

  /* fade + collapse (height -> 0) taake neeche/upar wale smooth sarkein; fast = overflow par foran */
  function remove(el, fast) {
    if (!el || el.__gone) return;
    el.__gone = true;
    clearTimeout(el.__t);
    try { el.style.height = el.offsetHeight + 'px'; void el.offsetWidth; } catch (e) {}
    el.classList.add('out');
    if (fast) el.classList.add('fast');
    setTimeout(function () { try { el.parentNode && el.parentNode.removeChild(el); } catch (e) {} }, fast ? 220 : 400);
  }

  function near(x, y, tol) {
    x = Number(x); y = Number(y);
    var fx = isFinite(x), fy = isFinite(y);
    if (!fx || !fy) return fx === fy;        // dono khali -> barabar
    return Math.abs(x - y) <= tol;
  }
  /* exact duplicate? (same system ka double publish: user:true + 800ms debounce / seek echo) */
  function isDup(prev, kind, a, b) {
    if (!prev || prev.kind !== kind) return false;
    if (Date.now() - prev.t >= cfg.dedupMs) return false;
    if (kind === 'seek') return near(prev.b, b, 1.5);               // wahi target
    if (kind === 'pause' || kind === 'play') return near(prev.a, a, 2);
    if (kind === 'msg') return prev.a === a;                          // wahi text
    return true;                                                      // join / leave
  }

  function show(name, kind, a, b) {
    try {
      kind = String(kind || '');
      if (kind === 'resume') kind = 'play';
      if (!(kind in WORD)) return false;
      if (kind === 'msg') { a = String(a == null ? '' : a).replace(/\s+/g, ' ').trim(); if (!a) return false; if (a.length > MSG_MAX) a = a.slice(0, MSG_MAX - 1) + '…'; }
      var sink = nativeSink();
      if (cfg.fullscreenOnly && !sink && !isFullscreen()) return false;
      name = String(name || 'Someone').trim().slice(0, 20) || 'Someone';

      var nk = name.toLowerCase(), t = Date.now();
      var prev = lastBy[nk];
      if (isDup(prev, kind, a, b)) {
        var pe = prev.el;
        if (pe && !pe.__gone) { pe.classList.remove('pulse'); void pe.offsetWidth; pe.classList.add('pulse'); }
        return false;
      }

      /* doosri line (plain text) */
      var plain = '';
      if (kind === 'seek') {
        var from = Number(a), to = Number(b);
        if (isFinite(from) && isFinite(to)) plain = fmt(from) + ' → ' + fmt(to);
        else if (isFinite(to)) plain = fmt(to);
        else if (isFinite(from)) plain = fmt(from);
      } else if (kind === 'pause' || kind === 'play') {
        if (a !== undefined && a !== null && isFinite(Number(a))) plain = fmt(a);
      } else if (kind === 'msg') {
        plain = a;
      }

      if (sink) {   /* APK native fullscreen: app draws it (same dedup/limits already applied above) */
        try { sink.wpActivity(JSON.stringify({ name: name, kind: kind, t1: WORD[kind], t2: plain, ms: cfg.duration })); } catch (e) {}
        lastBy[nk] = { kind: kind, a: a, b: b, t: t, el: null };
        return true;
      }

      var f = feed();
      if (!f) return false;

      var el = document.createElement('div');
      el.className = 'wp-act ' + kind;
      el.style.setProperty('--wp-act-ms', cfg.duration + 'ms');

      var line2 = (kind === 'seek') ? plain.replace(' → ', '<span class="arr">→</span>') : (kind === 'msg' ? '' : plain);

      el.innerHTML =
        '<div class="wp-act-ic">' + ICON[kind] + '</div>' +
        '<div class="wp-act-tx">' +
          '<div class="wp-act-t1"><b></b>' + (WORD[kind] ? ' <span>' + WORD[kind] + '</span>' : '') + '</div>' +
          (line2 || kind === 'msg' ? '<div class="wp-act-t2">' + line2 + '</div>' : '') +
        '</div>' +
        '<div class="wp-act-life"></div>';
      el.querySelector('.wp-act-t1 b').textContent = name;   // naam textContent se (HTML-safe)
      if (kind === 'msg') el.querySelector('.wp-act-t2').textContent = a;   // message text bhi textContent (HTML-safe)

      if (cfg.newestOnTop) f.insertBefore(el, f.firstChild);  // option: naya UPAR
      else f.appendChild(el);                                 // default: log style, naya NEECHE
      lastBy[nk] = { kind: kind, a: a, b: b, t: t, el: el };

      /* max se zyada -> sab se PURANE foran nikaalo (log style me upar wale) */
      var live = f.querySelectorAll('.wp-act:not(.out)');
      var extra = live.length - cfg.max;
      for (var i = 0; i < extra; i++) remove(cfg.newestOnTop ? live[live.length - 1 - i] : live[i], true);

      el.__t = setTimeout(function () { remove(el); }, cfg.duration);
      return true;
    } catch (e) { return false; }
  }

  function clear() {
    var f = document.getElementById('wp-act-feed');
    if (!f) return;
    var kids = f.querySelectorAll('.wp-act');
    for (var i = 0; i < kids.length; i++) remove(kids[i], true);
  }

  window.WPActivity = { show: show, fmt: fmt, isFullscreen: isFullscreen, now: now, clear: clear, config: cfg };
})();
