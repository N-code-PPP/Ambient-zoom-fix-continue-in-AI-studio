# Ambient Brain — Stealth Isolated Recorder Handoff Note (for Google AI Studio)

> Copy-paste this entire note into Google AI Studio (along with the referenced Kotlin files from `main` at commit `ab98f09`) to continue executing **Phase 1** and **Phase 2** seamlessly.

---

## 1. Repository & Branch State

- **Repo:** `https://github.com/N-code-PPP/ambient-brain`
- **Branch:** `main` (clean, pushed to `origin/main`)
- **Latest Commit:** `ab98f09` — `fix(stealth-recorder): shell AttributionSource, 48kHz loopback, parallel ambient filtering, safe RootTask move, AppOps crash recovery & interleaved MP4 mux`
- **Prior Feature Commits:**
  - `0e527de` — `feat(stealth-recorder): Stealth Virtual Screen + Per-UID Silent Audio + Virtual Silent Mic for Zoom/Webex/any app & configurable Download MP4 folder`
  - `9ceda3a` — `feat(meeting-recorder): Zoom/Webex audio & background video recorder (ADB Virtual Screen & MediaProjection) with Gemini internal audio exclusion`
- **Android SDK Targets:** `minSdk = 34`, `targetSdk = 35`, `compileSdk = 35` (Kotlin + Jetpack Compose)
- **Build Command (verified green on `ab98f09`):**
  ```bash
  sh ./gradlew :app:assembleDebug --no-daemon -Dkotlin.compiler.execution.strategy=in-process
  ```

---

## 2. Intended Product Goal & User Constraints

Build a **non-root** Android recording mode using **ADB / Shizuku (`uid = 2000`, `com.android.shell`) + AccessibilityService** that records **Zoom, Webex, or any user-selected app** (video + internal app audio) in the background with **3-way hardware isolation**:

1. **Virtual Display (physical screen untouched):** Target app runs off-screen on a headless 720p `VirtualDisplay` backed by H.264 `MediaCodec` + `MediaMuxer`.
2. **Silent Physical Speaker (per-UID audio isolation):** Target app's audio (`USAGE_VOICE_COMMUNICATION`, `USAGE_MEDIA`, etc.) is redirected silently off the physical speaker via a per-UID `AudioPolicy` (`ROUTE_FLAG_LOOP_BACK`) into a 16 kHz mono AAC track.
3. **Virtual Silent Mic + Parallel Ambient Brain Room Listening:** Target app receives digital silence from a per-UID `MIX_TYPE_RECORDERS` `AudioPolicy` + `AudioTrack` (with `cmd appops set --user 0 <pkg> RECORD_AUDIO ignore` fallback), leaving the **physical microphone free** so Ambient Brain (`CaptureEngine` + `AmbientCaptureAccessibilityService` using `VOICE_RECOGNITION`) keeps listening to the physical room in parallel.
4. **Gemini Exclusion:** Gemini **must ignore** internal app audio from the meeting/isolated app (handled by `SessionAnalyzeWorker` + `MeetingMetadataMarker.ignoreInternalAppAudioForGemini`).
5. **Public File Explorer MP4 Export:** Muxed `.mp4` is exported to a normal File-Explorer-accessible folder (default `Download/AmbientBrain`, customizable in Settings via relative path or SAF `OpenDocumentTree`).
6. **UI Preference (Do Not Break):** Keep the Zoom / Webex / Isolated App Recorder menu entry inside the **top-right `⋮` overflow dropdown menu** (never move it to the top-left).

---

## 3. What Was Already Fixed & Pushed in `ab98f09`

1. **Shell `AttributionSource` & 48 kHz Loopback (`AdbVirtualDisplayRunner.kt`):**
   - Replaced framework `createAudioRecordSink` / `createAudioTrackSource` primary paths with `AudioRecord.Builder.setContext(shellContext)` and `AudioTrack.Builder.setContext(shellContext)` using the registered `AudioMix.getRegistration()` submix address (`addr=<reg>`), avoiding the `ActivityThread.systemMain()` `uid=2000` / package `"android"` mismatch.
   - Restricted `allowPrivilegedPlaybackCapture(true)` to `ROUTE_FLAG_LOOP_BACK_RENDER` (`3`, 16 kHz mono only) so pure `ROUTE_FLAG_LOOP_BACK` (`2`) succeeds at native 48 kHz.
   - Reordered Per-UID `ROUTE_FLAG_LOOP_BACK_RENDER` ahead of global `REMOTE_SUBMIX` fallback (and restricted global `REMOTE_SUBMIX` to `targetUid <= 0`) so unrelated apps aren't muted.
   - Added a live `HandlerThread` (`AdbVDisp-Callbacks`) for `AudioPolicy` & `DisplayManager` callbacks, removed `FLAG_DESTROY_CONTENT_ON_REMOVAL` so live calls migrate back to Display 0 safely, emitted `AUDIO_SESSION_ID:<id>`, added IDR sync-frame requests on `RESUME`, and synchronized/bounded `ShellAdtsAacWriter`.
2. **Parallel Ambient Brain Filtering (`CallRouteGuard.kt`, `CaptureEngine.kt`, `MeetingRecorderService.kt`):**
   - `MeetingRecorderStateBus` now tracks `stealthAudioSessionId` and exposes `isStealthParallelAmbientActive()`, `isIsolatedStealthTargetPackage(pkg)`, and `activeStealthAudioSessionId()`.
   - `CallRouteGuard.processRecordingConfigs` filters out `REMOTE_SUBMIX`, Stealth's `audioSessionId`, and `TYPE_REMOTE_SUBMIX` virtual-mic sessions.
   - `CaptureEngine` skips foreground yield, `STRICT_YIELD` call pause (when AccessibilityService is bound), and `learnedYieldPackages` poisoning for the isolated Stealth target app and `com.android.shell`.
3. **Safe `RootTask` Display Moving (`MeetingVideoTools.kt`):**
   - Rewrote `AdbVirtualScreenController.moveAppToDisplay` and `findRunningRootTaskForPackage` to parse the enclosing `RootTask id=<id> ... displayId=<d>` header from `am stack list`, verify `am display move-stack` output without `|| true`, and **skip** `am start` on the launcher activity once moved (preventing active Zoom/Webex call screens from being overwritten).
   - Added `launchIfNotRunning = false` on Stop so closed apps aren't relaunched onto Display 0, and added `findRunningCandidatePackage` to resolve `MeetingPlatform.AUTO` to whichever meeting app is actually running.
4. **Crash-Safe AppOps Recovery & Android 15 `onTimeout()` (`PreferencesRepo.kt`, `MeetingRecorderService.kt`, `BootAndUpdateReceiver.kt`, `MainActivity.kt`):**
   - Added `PreferencesRepo.pendingAppOpsMicRestorePackage` backed by synchronous `.commit()` *before* setting `RECORD_AUDIO ignore`, auto-restored on service start, `onDestroy()`, `MainActivity.onCreate()`, Shizuku grant, and `BootAndUpdateReceiver`.
   - Added `MeetingRecorderService.onTimeout(startId, fgsType)` for Android 15's 6-hour `FOREGROUND_SERVICE_TYPE_DATA_SYNC` quota.
5. **Interleaved MP4 Muxing & Storage/UI Hardening (`MeetingVideoTools.kt`, `MeetingRecorderService.kt`, `SettingsScreen.kt`, `TimelineScreen.kt`, `MeetingRecorderDialog.kt`):**
   - Added `MeetingMp4Muxer.interleaveTracksByPts()` (PTS-ordered interleaving) and IDR sync-frame gating on `MediaProjectionVideoRecorder`.
   - Required shell-writable `externalCacheDir` (no broken `cacheDir` fallback), added `deleteTempFileBestEffort()` with Shizuku `rm -f` fallback, removed duplicate pinned `.aac` after Stealth MP4 export when Gemini ignores internal audio, validated `takePersistableUriPermission` in `SettingsScreen`, verified content URI readability via `isPlayableVideoAvailable()`, and auto-synced Stealth defaults when Shizuku is granted on resume.

---

## 4. Verified AOSP Android 14 & 15 OS Constraints (Do Not Re-Litigate)

Verified directly against AOSP `android14-release` and `android15-release` source:
- **Shell permissions still granted on Android 14 & 15:** `MODIFY_AUDIO_ROUTING`, `CAPTURE_AUDIO_OUTPUT`, `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, `CAPTURE_MEDIA_OUTPUT`, `ADD_TRUSTED_DISPLAY`, `ADD_ALWAYS_UNLOCKED_DISPLAY`, `CAPTURE_VIDEO_OUTPUT`, `MANAGE_APP_OPS_MODES`, `MANAGE_ACTIVITY_TASKS`.
- **`am stack list` & `am display move-stack`** still exist in Android 15 `ActivityManagerShellCommand`.
- **Hard OS / OEM limits to design around (cannot be bypassed without root):**
  1. **Policy-before-audio timing:** Registering `ROUTE_FLAG_LOOP_BACK` *after* Zoom/Webex already opened its VoIP `AudioTrack` does not reliably migrate already-active tracks off the physical speaker. Stealth must start **before** tapping "Join/Connect Audio".
  2. **Explicit device routing override (`AudioPolicyMix.cpp`):** If a VoIP app calls `setPreferredDevice()` / explicit communication device routing (`requestedDevice != null`), AOSP prioritizes explicit routing over dynamic loopback.
  3. **AAudio `MMAP_NOIRQ`:** Rejected on loopback mixes with `INVALID_OPERATION` (apps must fall back to legacy/fast tracks).
  4. **Privacy-sensitive VoIP capture (`AudioPolicyService.cpp`):** Even on `MIX_TYPE_RECORDERS` (`REMOTE_SUBMIX`), `VOICE_COMMUNICATION` still marks privacy-sensitive VoIP capture; Ambient Brain stays unsilenced **only** when `AmbientCaptureAccessibilityService` is bound (`isA11yOnTop == true`) with `VOICE_RECOGNITION`. Strict OEM HALs (Samsung/Xiaomi/OPPO) may still enforce exclusive VoIP mic DSP during `MODE_IN_COMMUNICATION`.
  5. **`FLAG_SECURE` video:** Enterprise watermarked/protected calls render black without `CAPTURE_SECURE_VIDEO_OUTPUT`.
  6. **Shizuku after reboot:** Requires Wireless Debugging reactivation after reboot on non-rooted devices.

---

## 5. Remaining Bugs & Execution Plan (Continue From Here)

### Phase 1 — Fix Remaining Code Bugs (Start Here First)

1. **`CaptureEngine` still pauses when `noteExternalConflictForForegroundApp()` exempts Stealth (`CaptureEngine.kt:1076-1086`, `1138-1148`)**
   - **Bug:** When `callGuard.externalRecordingActive` is true (e.g., Zoom/Webex briefly opens a non-`REMOTE_SUBMIX` input under `APPOPS_MIC_LOCKED` outside `MODE_IN_COMMUNICATION`), `noteExternalConflictForForegroundApp(...)` returns `null` for `com.android.shell` / the isolated Stealth target — **but `computeActiveYieldReason` and `computePauseParkReason` still return `"Yielded to another app"`**, pausing Ambient Brain.
   - **Fix:** Return a sealed/typed result (or explicit boolean `isExemptedStealthClient`) from `noteExternalConflictForForegroundApp` (or check `callGuard.lastShizukuExternalClient` / `MeetingRecorderStateBus.isStealthParallelAmbientActive()`), and do **not** yield when the only external client is exempted.

2. **Pause-while-blocked audio sync drift & `r_submix` pipe overflow (`AdbVirtualDisplayRunner.kt:283-296`, `875-945`)**
   - **Bug:** `pausedTotalMs` is only accumulated inside `runIsolatedAudioLoop` *after* `audioRecord.read()` returns. If `audioRecord.read()` blocks across a `PAUSE` -> `RESUME` cycle while silent, the paused duration is encoded as fake silence. Also, removing the 10 s cap lets a long silent stall trigger a tight catch-up loop that blocks `audioRecord.read()` and overflows `r_submix`'s fixed `MonoPipe`, dropping live speech.
   - **Fix:** Track `pauseStartedMonoMs` and `accumulatedPausedMs` directly on stdin `PAUSE` / `RESUME` via `AtomicLong`, and cap catch-up silence padding per read cycle (e.g., max `500 ms` per iteration) so `audioRecord.read()` is never starved.

3. **`AudioTrack.Builder` `FLAG_DEEP_BUFFER` injection & `ActivityThread.systemMain()` hardening (`AdbVirtualDisplayRunner.kt:806-862`, `1010-1035`)**
   - **Bug:** In AOSP `AudioTrack.Builder.build()`, default `PERFORMANCE_MODE_NONE` can inject `AudioAttributes.FLAG_DEEP_BUFFER` via `shouldEnablePowerSaving(...)`, breaking `REMOTE_SUBMIX` output profile matching for `USAGE_VIRTUAL_SOURCE`. Also, if `directBuilt` fails on an OEM ROM, fallback to `policy.createAudioRecordSink` / `createAudioTrackSource` still hits `"android"` attribution from `ActivityThread.systemMain()`, and `systemMain()` itself can crash on hardened OEM ROMs.
   - **Fix:** Set `.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)` in `createShellAttributedAudioTrackSource` (or invoke the 6-arg `AudioTrack(Context, ...)` constructor via reflection without deep-buffer flags), and add a safe `Context` fallback if `ActivityThread.systemMain()` throws.

4. **Dynamic sample buffer in `MeetingMp4Muxer` (`MeetingVideoTools.kt:873`)**
   - **Bug:** `ByteBuffer.allocateDirect(512 * 1024)` throws `IllegalArgumentException` on H.264 IDR keyframes `> 512 KB` (common on detailed screen-shares), triggering the `catch` block that copies `videoOnlyMp4` and **silently drops the entire audio track**.
   - **Fix:** Size the buffer from `maxOf(1024 * 1024, videoFormat.getInteger(KEY_MAX_INPUT_SIZE), audioFormat.getInteger(KEY_MAX_INPUT_SIZE))` (with safe `containsKey` checks) and resize/retry on `IllegalArgumentException`.

5. **Non-zero leading video PTS compatibility (`AdbVirtualDisplayRunner.kt:1100-1115`)**
   - **Bug:** `videoStartOffsetUs` can offset the first video sample up to `+15 s` while audio starts at `0 s`. Many Android video players / WhatsApp / Telegram re-encoders snap leading non-zero video PTS to `0 s`, breaking AV sync.
   - **Fix:** Anchor audio and video on a shared `t = 0` timeline (e.g., start audio emission once the first video frame latches or render an immediate initial black/placeholder frame to the `VirtualDisplay` surface, and keep both tracks starting at `PTS = 0`).

6. **Stealth MP4 UI badges & async URI checks (`TimelineScreen.kt:500,565`, `MeetingRecorderDialog.kt:540,625`, `MeetingRecorderService.kt:1660`)**
   - **Bug 1:** Stealth MP4s with `rawPath = null` / `isRawPurged = true` show `"Raw Purged • Proofs Kept"` on Timeline and an `"Audio Vault"` button in the Saved banner even though no private `.aac` exists in Audio Vault.
   - **Bug 2:** `remember { MeetingMp4Muxer.isPlayableVideoAvailable(...) }` runs `ContentResolver.openAssetFileDescriptor()` synchronously on the main UI thread during Compose layout.
   - **Fix:** Show `"🎬 MP4 Saved"` badge when a meeting marker has a playable MP4 and `rawPath == null`, hide the `"Audio Vault"` button in the Saved banner when `rawPath == null`, and move `isPlayableVideoAvailable` checks off the main thread using `produceState(initialValue = ..., keys) { value = withContext(Dispatchers.IO) { ... } }`.

7. **Safer `handleMoveAppDisplay` ("Bring to Main") (`MeetingRecorderService.kt:988-1012`)**
   - **Bug:** Tapping *"Bring to Main"* calls `moveAppToDisplay(..., displayId = 0)` with default `launchIfNotRunning = true`. If `am stack list` fails to match the task on an OEM ROM, it falls back to launching the launcher activity on Display 0, overwriting an active call.
   - **Fix:** Pass `launchIfNotRunning = false` when `toVirtual == false` (and surface a clear status note/toast if the task wasn't found running).

---

### Phase 2 — High-Impact Reliability & UX Upgrades (After Phase 1)

8. **Interactive Floating Stealth Mini-Preview + Touch Forwarding:**
   - Add an optional collapsible overlay (`TYPE_APPLICATION_OVERLAY` / `TYPE_ACCESSIBILITY_OVERLAY`) displaying live thumbnails or a mirrored preview of the Virtual Display and forwarding taps via Shizuku (`input -d <displayId> tap <x> <y>` / `swipe`).
   - Solves the biggest real-world Zoom/Webex UX gap: lets the user start Stealth Isolation **before** connecting audio and still tap *"Join Audio"* / dismiss Zoom/Webex popups without ever moving the app back to Display 0.

9. **On-Device Stealth Readiness Self-Test (in `AdbDiagnosticsDialog.kt` / Settings):**
   - Add a 5-second dry-run diagnostic button that spawns `AdbVirtualDisplayRunner` in `--self-test` mode and verifies on the user's exact device ROM:
     - `VirtualDisplay` creation & supported flags
     - `Per-UID ROUTE_FLAG_LOOP_BACK` registration + `AudioRecord` init
     - `Per-UID MIX_TYPE_RECORDERS` Virtual Silent Mic + `AudioTrack` init
     - `am stack list` + `am display move-stack` availability
     - `AmbientCaptureAccessibilityService.isSystemBound` status
   - Reports a clear green/yellow/red capability matrix directly in the app.

---

## 6. Key Files to Provide / Inspect in AI Studio

| File | Role |
| :--- | :--- |
| `app/src/main/java/com/ambientbrain/recorder/service/AdbVirtualDisplayRunner.kt` | Shell `uid=2000` `app_process` daemon (VirtualDisplay + H.264 + Per-UID AudioPolicy + Virtual Silent Mic + ADTS AAC) |
| `app/src/main/java/com/ambientbrain/recorder/service/MeetingRecorderService.kt` | Foreground service orchestrator, Stealth pipeline, AppOps lock/recovery, finalization, Android 15 `onTimeout` |
| `app/src/main/java/com/ambientbrain/recorder/service/MeetingVideoTools.kt` | `AdbVirtualScreenController`, `MeetingMp4Muxer`, `MediaProjectionVideoRecorder`, Download/SAF export |
| `app/src/main/java/com/ambientbrain/recorder/service/CallRouteGuard.kt` | `AudioRecordingConfiguration` monitoring & Stealth/REMOTE_SUBMIX filtering |
| `app/src/main/java/com/ambientbrain/recorder/service/CaptureEngine.kt` | 24/7 Ambient Brain room recorder & cooperative mic-yield engine |
| `app/src/main/java/com/ambientbrain/recorder/data/PreferencesRepo.kt` | Settings persistence (`meetingVideoSaveFolder`, SAF URI, `pendingAppOpsMicRestorePackage`) |
| `app/src/main/java/com/ambientbrain/recorder/ui/MeetingRecorderDialog.kt` | Setup & live control UI for Zoom / Webex / Any App Stealth Recorder |
| `app/src/main/java/com/ambientbrain/recorder/ui/TimelineScreen.kt` | Session badges, MP4 Watch button, and public folder badge |
| `app/src/main/java/com/ambientbrain/recorder/ui/SettingsScreen.kt` | Configurable Download/SAF MP4 save folder UI |
| `app/src/main/java/com/ambientbrain/recorder/ui/AdbDiagnosticsDialog.kt` | Diagnostics screen (target home for Phase 2 Self-Test) |
