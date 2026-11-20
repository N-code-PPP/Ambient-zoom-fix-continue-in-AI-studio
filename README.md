# Ambient Brain — Hands-Free Android Memory & Neurology Round System

Built for **Modern Android Devices (Android 14+, Android 15 & 16 / AOSP & OEM Skins)**.
Continuously captures spoken conversations in **Thai + English medical terms** (including during phone/VoIP calls), automatically detects **Neurology & Medical Ward Rounds**, labels the **speaker on every sentence**, outputs **bilingual (Thai + English) transcripts** and **English summaries / patient round sheets / actionable tasks**, and slices **permanent `.m4a` voice-proof clips**.

---

## Key Features Implemented

1. **24/7 Zero-Notification Ambient & Call-Through Capture (`service/`)**
   - `AmbientCaptureAccessibilityService` + `BootAndUpdateReceiver`: operates **100% silently with zero status-bar notifications**, attaching a `1x1 px` alpha-1 `TYPE_ACCESSIBILITY_OVERLAY` (or `TYPE_APPLICATION_OVERLAY` fallback via `SYSTEM_ALERT_WINDOW`) while listening is active so Android's `AudioPolicyService` grants Accessibility concurrent mic priority during **phone/VoIP calls** (`MODE_IN_CALL`, `MODE_IN_COMMUNICATION`, `MODE_RINGTONE`). When `WRITE_SECURE_SETTINGS` is granted once via Shizuku/ADB, the app **autonomously self-heals and re-binds** `AmbientCaptureAccessibilityService` on launch, boot (`BOOT_COMPLETED`), APK upgrade (`MY_PACKAGE_REPLACED`), daily `AudioPurgeWorker`, and manual Diagnostics trigger with zero PC or Shizuku needed.
   - `CallRouteGuard` + `CaptureEngine`: captures primary `MediaRecorder.AudioSource.VOICE_RECOGNITION` (and automatically upgrades to `VOICE_CALL` during calls when privileged `CAPTURE_AUDIO_OUTPUT` is granted, avoiding `UNPROCESSED` HAL exclusive locks) and implements cooperative **Mic Sharing Policies** (`Smart Share`, `Auto-Yield`, `Force Concurrent`) — combining Accessibility window monitoring, `UsageStatsManager`, and live Shizuku **`dumpsys audio` / `dumpsys media.audio_flinger` mic-owner attribution** (pinpointing the exact UID/package/session/source even for background or PiP recorders) so external Camera/Recorder apps never hit *"microphone in use"*.
   - `SileroVadProcessor`: 16 kHz 512-sample (32 ms) Silero VAD v5 ONNX Runtime processor (with an automatic voiceband RMS+ZCR fallback if `silero_vad_v5.onnx` is not bundled yet) + **2-second circular PCM pre-roll buffer** + silence compaction (> 3 s dead air compacted to 0.8 s).
   - `AdtsFileWriter`: streams compressed AAC-LC (16 kHz mono, 32 kbps, ~14 MB/hour of speech) into crash-safe `.aac` (ADTS) session files.

2. **Automatic + Manual Medical/Neurology Round Mode (`ai/SessionAnalyzeWorker.kt`, `service/CaptureEngine.kt`)**
   - **Manual Start:** Tap `[+ Round]` in the top status bar (and use the `⋮` menu to manually pause/resume mic hardware anytime).
   - **Automatic Detection:** When `gemini-3.8-flash` detects a clinical/neurology round in an ambient session (`is_clinical_round = true`), it extracts per-patient Round Sheet blocks, extends the active capture silence threshold from 90 s to 5 minutes, and **auto-stitches** consecutive round sessions within 30 minutes into a unified Round.

3. **Bilingual Speaker-Labeled Transcripts & 1-Tap Speaker Rename (`ui/RoundsScreen.kt`)**
   - Every sentence is labeled with `speaker_id` and `speaker_label` (e.g., `Ajarn Somchai (Attending)`, `Dr. (Me)`, `Resident 1`).
   - Supports recording a **12-second "My Voice" profile** in Settings (with automatic live-capture pause/resume) so Gemini identifies your voice across sessions.
   - Tap any speaker pill in the transcript to **rename that speaker across the entire session**.
   - Default transcript mode is **Thai + English Bilingual**, adjustable in Settings or live via the `TH + EN | Thai Only | EN Only` filter chips.

4. **Permanent Voice-Proof Clips & Smart Storage Auto-Purge (`data/ClipSlicer.kt`, `data/AudioPurgeWorker.kt`)**
   - Losslessly slices each extracted task/note's approximate timestamp window (`±5 s`, max 45 s) from the `.aac` session into `/files/clips/clip_<itemId>.m4a`.
   - `AudioPurgeWorker` runs daily to delete raw full-session `.aac` files older than `retentionDays` (default 7 days) unless pinned (`📌`), while keeping all `clip_<itemId>.m4a` voice proofs forever.

5. **App Data Exportation, Settings Backup & Selective Restore (`data/AppDataExporter.kt`, `ui/DataExportDialog.kt`)**
   - Export **all app data** or **cherry-pick selected parts** (settings, custom glossary, speaker roster, optional opt-in API keys, enrolled `.aac` voice profiles, sessions/summaries, bilingual transcripts, clinical round sheets, tasks/notes, permanent `.m4a` proof clips, raw `.aac` audio) and filter by time range, Ward Rounds only, or individually picked sessions (including directly from Timeline multi-select).
   - Supports **4 formats/methods**: portable restorable **ZIP Archive (`.zip`)** containing JSON + readable Markdown + CSV + audio, **Settings Backup (`.json`)**, **Readable Handover Report (`.md`)**, and **Tasks/Notes Spreadsheet (`.csv` with UTF-8 BOM for Excel Thai rendering)** — delivered via **Save File (SAF)**, **Share (`FileProvider`)**, or **Clipboard Copy**.
   - **Restore / Import** tab inspects `.zip` and `.json` backups safely (Canonical Zip-Slip guard, non-destructive `ON DELETE CASCADE`-free merge, and FTS4 index sync).

---

## Setup & Battery Optimization Exemption

1. Build and install on your device (Android 14+ / 15 / 16).
2. The bundled `assets/silero_vad_v5.onnx` provides state-of-the-box Voice Activity Detection.
3. On your device, enable **Developer Options → USB debugging** (and on Xiaomi/Poco HyperOS also enable **USB debugging (Security settings)**), then tap **1-Tap Auto-Grant All via Shizuku** inside the in-app **Diagnostics** dialog, or run:
   ```bash
   chmod +x setup_adb.sh
   ./setup_adb.sh
   ```
   Both 1-Tap Shizuku and `setup_adb.sh` safely grant:
   - `RECORD_AUDIO` + AppOp `RECORD_AUDIO allow` (always share in background)
   - `WRITE_SECURE_SETTINGS` (Zero-Touch Accessibility Self-Healing without PC/Shizuku after reboot or update)
   - `ACCESS_RESTRICTED_SETTINGS allow` (unblocks Android 13–16 sideloaded Accessibility binding)
   - `SYSTEM_ALERT_WINDOW allow` & `GET_USAGE_STATS allow`
   - `PROJECT_MEDIA allow` (silent projection prompt bypass AppOp, with zero foreground service notifications)
   - **Android 14–16 Cached Freezer & Phantom Process Killer bypass** (`deviceidle whitelist`, `active` standby bucket, `WAKE_LOCK allow`, `AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore`, `settings_enable_monitor_phantom_procs false`, `max_phantom_processes 2147483647`)
   - Non-destructive `AmbientCaptureAccessibilityService` append & bind + live `dumpsys audio` / `dumpsys media.audio_flinger` active-client verification.
4. In System Settings for **Ambient Brain**:
   - **Battery saver:** `Unrestricted / No restrictions`
   - **Recent Apps:** Lock the app card to prevent system task killer eviction.
5. Open the app → tap **Diagnostics** in the top pill to confirm **RECORD_AUDIO = MODE_ALLOWED**, **WRITE_SECURE_SETTINGS = Granted**, **PROJECT_MEDIA = MODE_ALLOWED**, **Freezer & Phantom Shield**, **Accessibility = Bound**, and **1x1 Overlay = Attached**, or tap **Inspect Active Mic Owners (`dumpsys audio`)** anytime via Shizuku. Then configure your **Mic Sharing Policy**, **Gemini API Key**, and optional **12s "My Voice" Profile** in ⚙️ **Settings**.
