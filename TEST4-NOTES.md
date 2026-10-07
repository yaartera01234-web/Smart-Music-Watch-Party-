# MPV SYNC TEST4 — implementation staged, Android build pending

Package: `app.party.music.test144` (original package is not changed).
Version: `117-MPV-SYNC-TEST4`, versionCode 122.
Workflow prepares a **new** `v117-mpv-sync-test4` prerelease; TEST3 must not be overwritten.
No TEST4 APK has been built or published in this implementation session.
Push/CI is awaiting fresh user-authorized GitHub access; do not reuse an old credential.

## Scope and reference

User approved replacing the previous no-auto-rewind rule for the isolated TEST app.
Normal-room sync rules follow the algorithm described in Yuroyami's Synkplay source at
`a7770b10a0d8769cce01e0b144b5957ba57d68c2`, with **8s instead of 4s** rewind threshold.
Reference: `shared/src/commonMain/kotlin/app/protocol/sync/SyncDecision.kt` and
`shared/src/commonMain/kotlin/app/server/model/ServerRoom.kt` in
https://github.com/yuroyami/syncplay-mobile .

The policy is independently implemented. This is an **MQTT adaptation**, NOT a port of the
Synkplay server/wire protocol and NOT a claim of identical end-to-end behavior or inherited
verification. No controlled room, King, readiness UI, file-offset UI, or new tower is added.

## Normal-room behavior

- Same-source, fresh foreground peers supply native playback positions in response to probes.
- Each client follows the slowest eligible position; no fixed leader. Probes use monotonic
  round-trip timing, not comparisons between phone wall clocks. Asymmetric latency remains
  an estimation error, as with any RTT/2 estimate.
- Ahead >8s: seek back. Ahead >1.5s: 0.95x. Restore under 0.1s.
- Filtered smaller drift >0.15s: 0.995x; stop below 0.03s, smoothing factor 0.3.
- Default normal-room lagging client is NOT forced forward. The pure policy includes the
  reference opt-in catch-up path (>5s after sustained-behind detection, +0.25s target), but
  this app has no controlled-room/Don't-slow-with-me UI and does not enable that path.
- First/return sync anchors the newly active client to existing peers, including forward
  catch-up and play/pause. Fresh clients are initially excluded from others' references.
- Native sample freshness 1.5s; probe response RTT cap 2.5s; peer expiry 3.5s. Buffering is
  reported as stalled position, not a shared pause. This transport-specific safety behavior
  is not a verbatim Synkplay server implementation.
- Lamport-stamped explicit commands reject reordered old commands; same-epoch response
  matching, one response per peer/probe, source identity checks and short seek barriers stop
  old telemetry from undoing a manual seek. Pending requests/peers are bounded.
- Automatic corrections never publish a new user seek. Native reports replace the old
  inferred YouTube jump baseline. Native raw position (not the smoothed display clock) is
  used, with actual MPV speed reporting and speed reassertion after a decoder reload.
- Leaving/disconnecting, stale native samples, calls or background state restore 1x and
  exclude that client from automatic correction. Existing background audio/handoff is kept;
  return to foreground triggers re-anchoring. Automatic background drift correction is not
  provided by this foreground adapter.
- Both phones need TEST4 for automatic correction. Legacy clients retain original explicit
  load/play/pause/seek handling and are not slowest-member candidates. Mixed-version testing
  is still required; do not call this full backward-compatible automatic sync.

## Display and cache

Fullscreen Aspect menu: Original / 16:9 / 16:10 / 4:3 / 2.35:1 / Pan & Scan.
Real MPV `video-aspect-override` and `panscan` properties; no Party publication or stream
reload. Original resets both overrides. Pan & Scan may crop; forced ratios may stretch.
Menu hidden for audio mode. Selection is session-local.

Cache: 100 MiB forward budget, 8 MiB backward, 24-hour maximum read-ahead window. Foreground
loads with separate YouTube audio split options to 50 MiB forward +4 MiB backward per
demuxer. Single-stream foreground and background use 100+8. These are demuxer packet-cache
options, not a strict total app-RAM cap. Decoder/surface/HTTP overhead is additional; measure
real Android memory and multi-demux behavior. Not a promise that an entire song is fetched.
Whole-song completion depends on size/source/server/network; long media uses a rolling cache.
Large prefetch can waste data when skipping. Existing prewarm initializes the background
core but does not load a second direct stream; handoff can still re-fetch cached data.

## Executed locally

- `node tests/sync-policy.cjs`: **21 passing** policy/simulated transport scenarios.
- `NODE_PATH=/tmp/mpv-browser/node_modules node tests/mpv-only-browser.cjs`: existing real-page
  adapter regressions pass (native player/transport mocked), including MP4/MKV/MP3/HLS,
  manual qualities, retained state, revision gates, EOF and no browser media requests.
- `NODE_PATH=/tmp/mpv-browser/node_modules node tests/sync-browser.cjs`: two actual Chromium
  pages using production page + new adapter, native clocks and MQTT relay simulated. Explicit
  seek/epoch, >8s rewind without command feedback, shared pause and expiry/speed restoration
  passed without page exceptions.
- `python tests/mpv-display-cache.py`: actual Linux MPV 0.40.0 accepts six aspect modes and
  Original reset without changing timeline; 0.995/0.95/1.005/1 speeds; single/split per-file cache
  options, separate audio track and 86400-second read-ahead. No Android visual/audible claim.
- `git diff --check`: pass.

## Release gates still pending

1. Fresh authorized push; CI Kotlin compile/JVM tests/APK packaging.
2. Verify NEW release commit, package/code122/signature, ZIP and embedded JS bytes/digest.
3. Two Android phones on the selected real tower: slow network, asymmetry/jitter, duplicate
   commands, seek races, long playback, reconnect, repeated joins and legacy peer interaction.
4. Audible pitch/speed behavior; native aspect rendering/cropping and fullscreen controls.
5. Calls, lock/unlock, handoff/return, low-memory behavior and measured actual cache usage.

Original Android package and `/home/user/wp-page/party-final1.html` are unchanged.
