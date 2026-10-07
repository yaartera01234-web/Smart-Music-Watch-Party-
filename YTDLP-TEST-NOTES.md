# v117 144p yt-dlp test (not final/original-package build)

- applicationId: `app.party.music.test144` (isolated from original and TEST2)
- versionName: `117-144-YTDLP-TEST1`, internal versionCode 119
- Android library: youtubedl-android 0.18.1, bundled Python + QuickJS; arm64 only, Android 7+.
- Pinned upstream yt-dlp executable: 2026.08.19.
- SHA256: `1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6` (verified against upstream SHA2-256SUMS).
- App raw resource overrides the older library-bundled yt-dlp. No runtime auto-update.
- yt-dlp project/license: https://github.com/yt-dlp/yt-dlp/tree/2026.08.19 (Unlicense; see bundled third-party licenses in executable). Android wrapper: https://github.com/yausername/youtubedl-android (GPL-3.0).
- Metadata/URL extraction only, no video downloads or server. MPV stays the player.
- Default 144p; exact-height selection. Missing audio or requested height fails clearly, never mislabeled 360p. Existing page fallback remains visibly labeled.
- 240/360 selector, original queue/Party protocol preserved. No live HTML changes.
- Separate streams are attached atomically in MPV loadfile options (no audio-add race).
- Device tests required: provided video, audible sound, pause/resume/seek, mixed-version party, quality switching, lock/unlock.

## TEST2 / MPV-only
- Same isolated package app.party.music.test144; versionName 117-MPV-ONLY-TEST2, code 120. Updates TEST1 only.
- Locally substitutes the iframe API with assets/mpv-only.js; no actual YouTube iframe exists.
- Foreground + notification WebViews block YouTube player/embed/googlevideo requests; metadata/thumbnails remain allowed. Native yt-dlp and MPV use their own network stack.
- Existing page controls/Party functions call the adapter. MPV timing is authoritative; native EOF triggers the existing queue end handler once.
- Revision tags reject stale native ticks after seeks/track changes. Polling no longer drives a second player or resends commands to MPV.
- Failed playback offers retry or explicitly opening YouTube externally (not silently in WebView).
- Video handback resolves video+audio rather than passing the background audio-only URL to a video surface. May have a reload gap; device lock/unlock test required.
- Browser test: tests/mpv-only-browser.cjs with Playwright installed and WP_HTML pointing to the production HTML. Native player and transport are mocked; real page functions exercised. Not a real-device or real-broker test.
- Phone tests required before an original-package build: controls, 144/240/360, mixed-version Party, queue next, lock/unlock and network/data observations.

## TEST3 / Unified MPV suite (not original package)
- Version 117-MPV-SUITE-TEST3, code 121. Updates existing app.party.music.test144.
- Direct HTTP/HTTPS MP4, MKV, MP3 and HLS are routed to MPV; browser media element has no src.
- Native fullscreen controller shares the existing MPV core: no duplicate player. Immersive system bars, sensor-landscape, back exits fullscreen; restores window brightness/orientation. System edge swipes can temporarily reveal bars.
- Left vertical gesture changes window brightness; right changes AudioManager STREAM_MUSIC volume. Controls/timeline excluded from gesture interception.
- Audio menu reads actual MPV track-list. Selection writes aid and verifies selected track; one-track files do not offer fake dual audio. Direct-file audio preference survives service handoff within the session.
- Embedded MP3 art rendered by MPV when present; otherwise vinyl/gradient cover in inline and native fullscreen views.
- Diagnostic line and YouTube fallback removed. Manual YouTube heights 144/240/360/480/720/1080, default 144, available choices filtered from extractor metadata. Direct files have no fake resolution dropdown.
- Tests must distinguish browser mock/control tests and Linux MPV codec tests from handset-only brightness, system volume, fullscreen bars, codec support and real-party sync. No original-package final until user confirms.

## TEST4 / Sync, cache and aspect — implementation pending Android build
- Version 117-MPV-SYNC-TEST4, code 122; same isolated package.
- See TEST4-NOTES.md for exact scope, MQTT adaptation differences, completed local tests,
  and pending Android/real-tower/device release gates. TEST3 release is not overwritten.
