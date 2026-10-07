# Handoff → new Arena session on `yaartera01234-web/watch-party`

Copy the prompt below into a NEW Arena session with the **watch-party** repo selected.
(This session is bound to the APK repo branch `arena/8fffe9a4-smart-music-watch-party` and cannot push elsewhere.)

---

## Prompt (paste as-is)

Task: ship the "Fullscreen Player Activity Overlay" (v54) in this repo. Everything is already built and reviewed;
do NOT redesign anything. Keep all existing features untouched.

Source of truth (public, read-only):
`https://github.com/yaartera01234-web/Smart-Music-Watch-Party-/tree/arena/8fffe9a4-smart-music-watch-party/docs`
- `release/party-final1.html` — this repo's `party-final1.html` @ v53 (commit 6372767) + one inserted block + `WP_BUILD` bump to `2026-10-07-54`
- `release/wp-ver.txt` — `2026-10-07-54`
- `WP_ACTIVITY_OVERLAY.snippet.html` — the inserted block alone (CSS + JS, ~20 KB, marker comment `WP_ACTIVITY_OVERLAY_V54`)

Steps:
1. `git log -1 main`. If main is still `6372767` (v53): download `release/party-final1.html` and `release/wp-ver.txt`
   (raw URLs under `raw.githubusercontent.com/yaartera01234-web/Smart-Music-Watch-Party-/arena/8fffe9a4-smart-music-watch-party/docs/release/...`)
   and overwrite the two files in the repo root.
   Verify with `diff`: exactly ONE changed line (`const WP_BUILD = '2026-10-06-53'` → `'2026-10-07-54'`) and ONE inserted block of 479 lines
   immediately before the line `<!-- WP_BROWSER_SYNC_V44` (block lands at line 8702). Nothing else may differ.
2. If main has moved past v53: do NOT use the release file. Instead insert the full contents of `WP_ACTIVITY_OVERLAY.snippet.html`
   immediately before `<!-- WP_BROWSER_SYNC_V44` in the current `party-final1.html`, bump `WP_BUILD` to a new `YYYY-MM-DD-NN`, and write the
   same value to `wp-ver.txt`.
3. Sanity: every inline `<script>` in `party-final1.html` must still parse (`new Function(src)` over each block — 10 blocks expected);
   the marker order must be `WP_ACTIVITY_OVERLAY_V54` → `WP_BROWSER_SYNC_V44` → `WP_BROWSER_FULLSCREEN_V45`;
   the file must NOT contain `PREVIEW-ONLY` or `wp-demo` (that was the demo strip, preview only).
4. Optional, repo convention: add `tests/ACTIVITY-V54-CHECKS.md` (one-line checks: marker present once, placed before V44, scripts parse, no demo strip).
5. Commit on `main` with message `v54: fullscreen player activity overlay (Paused/Resumed/Seek/Joined/Left + room chat) — additive hooks only`
   and push. GitHub Pages serves `main` → live in ~1 minute for the browser AND the Android APK (its WebView loads this live page;
   no APK rebuild needed).

What the block does (for your understanding only — do not modify): fullscreen-only (browser `.wp-html-fs`/`.pp-fs`/Fullscreen API,
APK `body.wp-mpv-fs`) upper-left glass toasts inside `.player-wrap`; log-style column, oldest on top, max 5 rows × 5 s, 6th pushes the oldest out;
additive wrappers around `window.applyCmd` (friend play/pause/seek), `window.pubCmd` (own), `window.addSysMsg` (Joined/Left),
`window.addMsg` (room chat only — DM `appendMsg` untouched); originals are always called; no new sync logic; MPV time via `__wpMpvState`.

After push, reply with: the commit SHA, the `diff --stat`, and the live URL
`https://yaartera01234-web.github.io/watch-party/party-final1.html`.
