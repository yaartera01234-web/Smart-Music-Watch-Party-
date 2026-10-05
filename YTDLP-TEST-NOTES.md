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
