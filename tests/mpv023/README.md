# MPV023 FINAL — exact Synkplay Android v0.23.0 native engine

**Final promotion authorized by the user on 2026-10-06.**

## Final original-package release
- Original `app.party.music`, label **Music Watch Party**, same `party.jks` signing key.
- versionCode128 / versionName117-MPV023-FINAL; Android8+ / arm64-v8a.
- Install over the original; do not uninstall. TEST package/data remain separate.
- Built on the user's GitHub via `release/v117-mpv-final`, stable tag `v117-mpv023-final`.
- Same pinned nine native libraries and source-identical rebuilt JNI as TEST1.
- `app/src` remains byte-identical to original126; only identity/build packaging changes.
- User requested final promotion; no explicit handset playback result is recorded here.

The original TEST1 history below documents provenance, not the current final identity.

The user reported that Synkplay v0.23.0 plays on their older device while the newer
engine does not. This test changes the native engine, not the application's player
lifecycle, sync algorithm, resolver, WebView UI, GIF, calls or notification code.

## Identity / scope
- Base app source: `d33ade5919b205b8c457a1281c15d4715db8b39e` (117-GIF-AUTO).
- Separate applicationId: `app.party.music.mpv023test`, label **Party MPV023 TEST**.
- versionCode127 / versionName117-MPV023-TEST1; Android8+ (API26), arm64-v8a.
- Original app remains installed and keeps its data. The TEST app has separate local
  storage, so its room/name/chat identity/preferences do not automatically copy over.
- Test branch: `test/synkplay-023-mpv`. Optional CI publication is a prerelease tagged
  `v117-mpv023-test1`, `make_latest:false`; no stable workflow/tag is overwritten.
- Every existing file in `app/src` is unchanged from the base commit. Only build identity,
  native packaging/re-signing recipe, provenance and tests are changed.

## Exact native engine
`manifest.json` pins the reference release APK SHA256 and each of nine library hashes.
Reference: https://github.com/yuroyami/syncplay-mobile/releases/tag/v0.23.0
APK: synkplay-0.23.0-full-arm64-v8a.apk
SHA256: dfb29c5c68f1a84b436acd09f875b8e32dfa7ed0ae5a3688381ea8eac2ae3a7f

The APK receives BYTE-IDENTICAL copies of these reference files:
`libmpv.so`, `libavcodec.so`, `libavdevice.so`, `libavfilter.so`, `libavformat.so`,
`libavutil.so`, `libswresample.so`, `libswscale.so`, `libc++_shared.so`.

Embedded native version strings:
- MPV `v0.41.0-252-gc401ef9c3`.
- FFmpeg `N-123143-g0540b42657`; codec62.24.101 / format62.10.101.
- MPV client API is2.5 (both original and reference binaries' exported function returns
  0x00020005, inspected with NDK llvm-objdump).

This does NOT copy Synkplay's Kotlin MPVView, destroy/reinitialize behavior, options,
playlist, UI, sync/network protocol, VLC or ExoPlayer engine. Our wrapper/controls,
100MiB forward cache policy and song-transition implementation stay unchanged so this
is an engine-only comparison. Success on an old device is not assumed.

## Important JNI compatibility correction
The original libmpvKt0.3.0 bridge imports:
- av_jni_set_java_vm@LIBAVCODEC_63
- av_jni_set_android_app_ctx@LIBAVCODEC_63

Reference FFmpeg exports those at LIBAVCODEC_62. Comparing only function names would
incorrectly suggest the old bridge could be used. A plain library swap would risk a
loader failure. The exact upstream wrapper JNI SOURCE is therefore rebuilt against
the reference libraries, with matching MPV/FFmpeg headers. Kotlin wrapper remains0.3.0.

Vendored bridge source commit: b001dd97538e7d3e700df80029fd72be99ceba3f.
Headers: mpv c401ef9c3 and FFmpeg0540b42657. Source URLs/hashes and notices are retained.
NDK29.0.14206865, arm64 Android26, shared C++ runtime from the reference APK, 16KiB link
alignment. The bridge is NOT claimed to be a byte-identical Synkplay binary: it is our
existing source-identical connecting adapter rebuilt for the reference ABI.

## Reproduce the engine substitution
Use a separate temporary directory (not tracked). Requires Python3, readelf, NDK29,
Android build-tools35, JDK17 and the project's normal Gradle dependencies.

```sh
python tests/mpv023/engine_swap.py prepare --work /tmp/mpv023
python tests/mpv023/engine_swap.py build --work /tmp/mpv023 --ndk "$ANDROID_HOME/ndk/29.0.14206865"
./gradlew :app:testDebugUnitTest :app:assembleDebug
python tests/mpv023/engine_swap.py swap --work /tmp/mpv023 --base app/build/outputs/apk/debug/app-debug.apk --output /tmp/mpv023/unsigned.apk
# IMPORTANT: the raw Gradle APK still contains the original engine. Never distribute it.
# Zipalign and apksigner sign the substituted APK exactly as the TEST workflow specifies.
```

`verify` checks every unchanged APK entry byte-for-byte (dex/assets/resources included),
all nine reference native hashes and the rebuilt bridge. `check` validates versioned
symbols/dependency closure and Java JNI entry-point parity, and explicitly rejects the
old FFmpeg63 bridge as a negative control. These are static/link/build checks, NOT an
Android runtime/handset playback test. The APK must be signed/verified after substitution.

## Device test checklist
1. Pause/close original playback first to avoid two players competing for audio focus.
2. Launch **Party MPV023 TEST**; use the same room/network and same failing song URLs.
3. Play first, second and third songs without restarting the app; test another direct MP3
   or MP4 as well as YouTube to distinguish engine vs resolver failures.
4. Test play/pause/seek, next song after completion, fullscreen and return from background.
5. Record device model, Android version, exact URL and visible error if it fails. Do not
   uninstall/clear the original app or declare a permanent fix from one successful song.

## Upstream source / licenses
Reference project and its build scripts:
https://github.com/yuroyami/syncplay-mobile/tree/v0.23.0/buildscripts
Native MPV source: https://github.com/mpv-player/mpv/tree/c401ef9c3
Native FFmpeg source: https://github.com/FFmpeg/FFmpeg/tree/0540b42657
JNI source: https://github.com/yuroyami/libmpvKt/tree/b001dd97538e7d3e700df80029fd72be99ceba3f
Copyright/license notices remain in vendored source. The reference FFmpeg binary reports
GPLv3-or-later; its COPYING.GPLv3 and the wrapper MIT license are included in vendor/.
Do not remove upstream notices or treat this third-party bundle as proprietary code.
