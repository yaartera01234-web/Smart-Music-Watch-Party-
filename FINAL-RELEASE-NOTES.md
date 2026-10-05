# v117 MPV FINAL — original-package promotion

Status at preparation: committed locally; fresh authorized push/CI/APK still pending.
Branch: release/v117-mpv-final. Expected new tag: v117-mpv-final.
Expected asset: Music-Watch-Party-117-FINAL.apk.

The user explicitly authorized final original-package release after reporting TEST5 and
HTML v44 passes, and explicitly deferred the intermittent unwanted-audio issue.

## Identity / safe upgrade checks
- applicationId: app.party.music (NOT app.party.music.test or app.party.music.test144).
- Label: Music Watch Party.
- versionName: 117-MPV-FINAL; versionCode: 124.
- minSdk24, arm64-v8a; same variant/signing configuration as the tested APK.
- Downloaded original published v115 APK and verified its manifest: app.party.music,
  code115, 115-MPV-YTVIDEO, label Music Watch Party.
- apksigner verified original v115 certificate SHA256:
  842e2d5518d28a1d0c3aff8a16646cead9c58a619884a21a6a41792c04bffb33
- keytool confirms the existing party.jks signing entry has exactly the same fingerprint.
- Version 124 is above the verified original v115 and previously used test codes.
- A same-signature in-place update should preserve original app data; do not uninstall
  the original app first. The separately installed TEST144 app/data are not migrated.

## Tested source preservation
All files under app/src and tests are byte-identical to TEST5 commit d67e9e8.
Only app identity/version/label, release workflow and this release note changed.
The TEST branch and TEST5 release are not overwritten. Source is on a dedicated release
branch; no force-push to existing main is planned. The workflow publishes a latest stable
original-package release only from that branch after tests and manifest/signature checks.
Live HTML v44 was already deployed separately; no HTML changes are part of this promotion.

## Reports / local checks
User reported APK/APK sync, HTML/APK MP4 and YouTube sync, fullscreen cutout resolution,
and lock-screen nonstop playback. These are observed passes, not an every-device guarantee.
Preparation reran 21 sync policy/simulated-transport tests, source identity check,
workflow YAML parsing, and git diff --check; all passed.

## Known issue intentionally NOT fixed
Intermittent playback after Room Left / Recents removal / incoming commands while unjoined
is deferred by the user. No membership/lifecycle/notification-service patch is included.
Existing background messages, replies, Party ON popup and background-audio behavior remain.
Do not describe this issue as fixed by the final build.

## Before delivery
Fresh authorized push; verify CI Kotlin/JVM/policy checks and signed APK build.
Verify new release/tag commit, app.party.music/code124, certificate matching original,
ZIP integrity and embedded JS byte equality. Save SHA256 in a small checks file.
No final APK was generated at the time this note was written.
