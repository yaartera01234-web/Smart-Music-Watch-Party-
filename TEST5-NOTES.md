# TEST5: fullscreen display-cutout / edge-to-edge correction

Status: implemented locally; Android compile/APK and handset verification pending fresh
push authorization. Original package/live page unchanged. TEST4 sync/cache/aspect policies
are unchanged.

Identity: app.party.music.test144, code123, 117-MPV-FULLSCREEN-TEST5.
New release target: v117-mpv-fullscreen-test5 (do not overwrite TEST4).

User supplied two screenshots of the same video in different players. Our screenshot
shows an asymmetric left strip outside BOTH video and controls. The code had no
layoutInDisplayCutoutMode override, and API30+ immersive mode only hid bars without
explicitly disabling fitted decor. This is consistent with a landscape cutout exclusion,
not just normal video letterboxing. Screenshots alone do not prove every OEM's behavior.

Change scoped to MpvFullscreenControls:
- API28–29: SHORT_EDGES display-cutout mode during fullscreen.
- API30+: ALWAYS display-cutout mode and setDecorFitsSystemWindows(false).
- Apply notch-safe horizontal padding to top/bottom controls ONLY. MPV and full overlay
  remain full-window; video is not artificially stretched/cropped to hide the strip.
- Reapply immersive/cutout policy on focus/dialog return via existing immerse() path.
- Restore original cutout mode, MainActivity's normal fitted-decor setting, saved flags,
  orientation and brightness on fullscreen exit.
- Older Android retains existing immersive-layout flags.

Checks run: git diff --check and all 21 existing sync policy/simulated transport tests pass.
These are NOT proof of native visual correctness. No Android APK compiled in this edit.

Phone gates: same video / same Original aspect; both landscape rotations; controls shown
and hidden; notch and system-edge swipe; open/close Aspect and Audio dialogs; exit back to
normal page without shifting keyboard/chat layout; repeat fullscreen and lock/unlock.
Natural aspect-ratio letterboxing may remain; eliminating it would require crop/stretch.
