# Silent fallback update — local preparation

User requested no popup, Retry button or intermediate fallback status:
original Litterbox once, then tmpfiles once automatically; only final failure is shown.
Backup public/unencrypted one-hour retention unchanged and previously explained.
Original app.party.music, versionCode126 / versionName117-GIF-AUTO.
Prepared workflow publishes v117-gif-auto on the original release branch.
Fresh GitHub build/push access required; no old token reused. No126 APK built yet.
Local:12 routing simulations,21 sync simulations,5 source guardrails passed.
These are not Android CI or phone tests. MPV/sync/HTML untouched.

Historical previous-release records follow:

# Publication authorization update — 2026-10-05

User explicitly requested original-app publication rather than a separate test release.
VersionCode125 / versionName117-GIF-FIX / app.party.music; same signing configuration.
Workflow now targets release/v117-mpv-final and publishes v117-gif-fix as latest stable,
without overwriting historical v117-mpv-final APK/tag. Fresh authorization received;
no earlier credential reused. Full CI/APK verification remains required before delivery.
Handset keyboard send/peer receive remains pending; authorization is not a device test.

The following is the historical local-candidate record before publication authorization:

# Keyboard GIF TEST1 — local candidate, 2026-10-05

## Status
Implementation and local checks complete. Full Android Gradle/APK build, signature verification of the new APK, installation, keyboard-provider permission behavior, backup consent UI, and peer receive/render remain UNTESTED. No push or release authorized/executed during this work. Fresh push authorization is required. Stable v117-mpv-final/code124 and live HTML v45 are unchanged.

Candidate branch: `fix/keyboard-gif-125`; original package `app.party.music`, versionCode125, versionName `117-GIF-TEST1`. Prepared CI publishes a distinct prerelease `v117-gif-test1`, never latest/stable. Same existing signing configuration. Install over original only after successful build/signature verification; do not uninstall. A normal downgrade to stable code124 will not install over125.

## Evidence and limits
The original GifWebView source is identical across v112 and final. There was NO missing-CRLF defect: the earlier handwritten reproduction was wrong and its diagnosis was explicitly retracted. This patch does not claim to correct a separator bug.

A generated 128×128 four-frame GIF (1,268 bytes; SHA256 `850fc9b306bd4357ffc09842f31c0f8132c4ada7e08a9a7b106ea894d0f283bc`) was uploaded from this sandbox. Correct curl requests, including browser UA/cache-busting variants, returned Litterbox HTTP412, `No file!`. This is NOT the observed handset response or proof of a global outage.

Tmpfiles API upload succeeded, but inventing `/dl/<id>/filename` failed HTTP403. Its current download page supplies a signed `/dl/<timestamp.signature>/<id>/filename` link. A browser-like UA allowed the page GET. The actual new Kotlin uploader was compiled and run against BOTH live hosts, twice (last after streaming-memory improvements): primary HTTP412; backup upload, signed-link extraction and full streamed byte-for-byte GET verification succeeded. Only the synthetic fixture was uploaded. This verifies the sandbox upload/download route, not cross-device rendering or its lifetime on every network.

## Changes
- Keyboard-only GIF/PNG/JPEG/WebP signature detection, matching MIME and filename extension; no image conversion or saved local copies.
- 12 MiB input cap; bounded API/HTML reads; streamed multipart upload and streamed download comparison avoid extra full-image copies.
- HTTPS host allowlists, redirects fail closed, connect10s/read25s timeouts, HTTP/DNS/TLS/timeout distinctions. No automatic repeated POSTs; retry is explicit.
- Acquire and release IME URI read permission; reject concurrent keyboard uploads. Renderer snapshot requests time out.
- Original Litterbox/72h route remains first. On failure, a native dialog offers Cancel, Retry original, or OPTIONAL backup with per-upload informed consent. Tmpfiles backup is PUBLIC/UNENCRYPTED and expires around ONE HOUR, including old chat history. The link must be shared with peers to render; this is not encrypted media storage.
- A page nonce and frozen DM/room target prevent late dispatch into a different chat. Final check and wpSendGif invocation run in one JS evaluation. Inbox/unjoined room do not qualify. Existing messaging/delivery semantics are unchanged; dispatch is not a delivery receipt.
- Full uploaded bytes must match before the native callback receives a URL.
- MainActivity changes only its keyboard-GIF callback. 23 other existing app/src/main files matched stable byte-for-byte; only GifWebView and that callback changed, plus one new helper. MPV, sync, media controls, calls/background/notification services, built-in GIF selector and HTML were not edited. The separately deferred unwanted-playback issue remains unfixed.

## Local validation
- 13 JUnit tests: format signatures, unsupported files, bounded reads, actual shared multipart builder framing and bytes, URL allowlists, signed-link parsing, error classification.
- 12 Node simulations using the actual native-embedded JavaScript: unchanged DM/room, friend/room changes, closing DM, leaving room, inbox, reload, missing hook and safe URL quoting. These are simulated page states, not a real browser/broker/device.
- 21 existing sync-policy/simulated transport tests passed.
- GifWebView + KeyboardGifUpload compiled using Kotlin1.9.24/JDK11 against Android14 API classes and AndroidX core1.15.0. Only the existing createWrapper deprecation warning. This is NOT a full Android app/Gradle build; CI will use the app's configured compiler/SDK/JDK17.
- Actual Kotlin network probe in `tests/keyboard-gif-network.kt`, opt-in only, uses `tests/fixtures/keyboard-upload.gif`; deliberately not an automatic CI test. Public hosts can change or fail.
- `git diff --check` passed. Live HTML repository remains clean at v45.

## Required handset checks before claiming fixed
1. Install signed candidate over original without uninstalling; retain local data.
2. Gboard GIF in joined room: observe precise primary error if any; consent to backup; peer opens and sees animation.
3. DM GIF: intended friend receives/animates; room receives nothing. Built-in GIF still works.
4. Switch friend/room or exit while upload pending: cancel, never route to new destination.
5. Cancel and Retry original controls; subsequent keyboard GIF still usable; repeated taps do not create parallel uploads.
6. PNG/JPEG/WebP stickers where offered; denied URI permission, offline/DNS/timeout, oversized/empty input.
7. Background/destroy/reopen while uploading; no leaked dialog or retained grant. Ordinary MPV/sync/notifications remain working.
8. Explain one-hour backup expiration. Do not treat this as a permanent hosting solution or silently promote to stable.
