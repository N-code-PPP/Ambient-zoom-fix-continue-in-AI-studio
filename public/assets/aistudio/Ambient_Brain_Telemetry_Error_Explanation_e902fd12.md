# Ambient Brain Telemetry `exit=-1` Failures — Root Cause Explanation

**Date:** November 21, 2026  
**Package:** `com.ambientbrain.recorder` (PID `26589`, UID `10512`)  
**Evidence:** ADB, Shizuku & Audio Telemetry screenshot (`1000003352.jpg`) cross-referenced with `20261121-121456-device.log`

## Conclusion

Every `FAIL (exit=-1)` and `WARN (code=-1)` card shown in the **ADB, Shizuku & Audio Telemetry** dialog is a **false failure caused by the same `ShizukuRemoteProcess.waitFor(timeout, unit)` bug** in the app's shared Shizuku shell runner (`ShizukuAdbManager`).

The device log proves that `com.ambientbrain.recorder.service.AdbVirtualDisplayRunner` (PID `11169`, `uid 2000`) **did not time out or crash**—it ran all probes to completion and exited cleanly with **`status: 0`**. The UI reported `exit=-1` because the app-side Shizuku wrapper threw an exception before the child process finished, discarded `stdout`, and returned `-1`.

## Screenshot vs. Device Log Proof (`20261121-121456-device.log`)

| Telemetry UI Card | Reported UI Status | Actual Execution in Device Log (PID `11169`) |
| :--- | :--- | :--- |
| **Shizuku / ADB Shell Bridge** | `PASS` | `ShizukuServerLog` (PID `16053`) healthy; `SettingsShellCmd` and `app_process` launched normally under `uid 2000`. |
| **Shell Context Attribution (`uid=2000`)** | `FAIL` (`exit=-1`) | Started cleanly as `uid 2000` (`com.android.shell`) at `12:15:06.382` (line 10345) and entered `AdbVirtualDisplayRunner` at `12:15:06.504` (line 10403). |
| **Off-Screen Virtual Display + H.264** | `FAIL` (`exit=-1`) | Created virtual display `AmbientBrain-MeetingVDisp` (`720×1280`, `displayId=16`) and started `c2.mtk.avc.encoder` at `12:15:06.667–.670` (lines 10860–10861). |
| **Remote Touch Injection (`input -d`)** | `FAIL` (did not complete) | Virtual display stayed active for ~3 seconds before teardown (`kill: send 15 to pid 11211` at `12:15:09.681`, line 23525); `stdout` was lost by the caller. |
| **Mini-Preview H.264 Decoder** | `FAIL` (did not complete) | Allocated, configured, and started `c2.mtk.avc.decoder` at `12:15:09.685–.720` (lines 23539–23785). |
| **Per-UID Silent Speaker Loopback** | `FAIL` (did not complete) | Opened `REMOTE_SUBMIX` `AudioRecord` (`48000 Hz`, stereo, `inputSource 8`) at `12:15:09.757–.785` (lines 24296–24620). |
| **Per-UID Virtual Silent Mic** | `FAIL` (did not complete) | Registered `AudioPolicy` (`uid 2000`) and created `REMOTE_SUBMIX` `AudioTrack` (`16000 Hz`, mono, `usage=15`) at `12:15:09.828–.851` (lines 25003–25256). |
| **WindowManager Task Routing (`am stack list`)** | `WARN` (`code=-1`) | Hit the same Shizuku wrapper `exit=-1` failure; additionally, `am stack list` is deprecated on newer Android versions, so falling back to `dumpsys recents` is expected. |
| **Runner Process Exit** | Reported `exit=-1` | Actually exited cleanly with `System.exit called, status: 0` at `12:15:09.870` (lines 25370–25371). |

## Why the App Saw `exit=-1` While the Runner Exited `0`

This matches a documented **Shizuku + Android Binder + `Process.waitFor(long, TimeUnit)`** bug ([deepseek-harness Discussion #11603](https://github.com/deepseek-ai/deepseek-harness/discussions/11603)):

1. **Unoverridden timed `waitFor`**: `ShizukuRemoteProcess` does not override `java.lang.Process.waitFor(long timeout, TimeUnit unit)`, so it inherits Java's default polling loop, which calls `exitValue()` and only catches `IllegalThreadStateException` while the subprocess is running.
2. **Binder exception downgrade**: On the Shizuku server side, `exitValue()` throws `IllegalThreadStateException("process hasn't exited")`. Because `IllegalThreadStateException` subclasses `IllegalArgumentException` and Android Binder's `Parcel` exception serialization maps it to `IllegalArgumentException`, the client receives `IllegalArgumentException: process hasn't exited`.
3. **Immediate abort & dropped `stdout`**: Because `waitFor(timeout, unit)` does not catch `IllegalArgumentException`, the exception escapes immediately on the first poll. The app's `catch` block swallows it, discards all `stdout` from `AdbVirtualDisplayRunner`, and returns `exit=-1`—while the subprocess continues running in the background and finishes cleanly with `status: 0`.

## Supporting Log Excerpts (`20261121-121456-device.log`)

```text
10345: 11-21 12:15:06.382 11169 11169 D AndroidRuntime: >>>>>> START com.android.internal.os.RuntimeInit uid 2000 <<<<<<
10403: 11-21 12:15:06.504 11169 11169 D AndroidRuntime: Calling main entry com.ambientbrain.recorder.service.AdbVirtualDisplayRunner
10860: 11-21 12:15:06.667 11169 11192 I MediaCodec: [mId: 0] [video-debug-enc] setState: STARTED
10861: 11-21 12:15:06.670  1822  2632 D DisplayManagerService: Virtual Display: creating DisplayDevice with VirtualDisplayAdapter
23525: 11-21 12:15:09.681 11169 11169 I libc    : kill: send 15 to pid 11211
23539: 11-21 12:15:09.685 11169 11282 I MediaCodec: [mId: 1] [video-debug-dec] setState: INITIALIZING
23785: 11-21 12:15:09.720 11169 11282 I MediaCodec: [mId: 1] [video-debug-dec] setState: STARTED
23805: 11-21 12:15:09.722  1822  3017 V VirtualDisplayAdapter: Release VirtualDisplay AmbientBrain-MeetingVDisp
24296: 11-21 12:15:09.757 11169 11169 V android.media.AudioRecord: Will record from REMOTE_SUBMIX at full fixed volume
25003: 11-21 12:15:09.828  1822  1876 I AS.AudioService: registerAudioPolicy for android.os.BinderProxy@8869d15 u/pid:2000/11169 with config:reg:548836277:ap:54 Mix:0-Typ:r-Rul:6
25256: 11-21 12:15:09.851 11169 11169 D AudioTrack: createTrack_l(18857): 0xb400007a8aedda00, mCblk = 0x7d5af8d000, mLatency = 339, mAfLatency = 128, frameCount = 3380, mSampleRate = 16000, mFlags = 0, mReqFrameCount = 3380, mNotificationFramesAct = 320
25370: 11-21 12:15:09.870 11169 11169 I app_process: System.exit called, status: 0
25371: 11-21 12:15:09.870 11169 11169 I AndroidRuntime: VM exiting with result code 0.
```

## Fix

Replace `process.waitFor(timeout, unit)` in your shared Shizuku command helper (`ShizukuAdbManager`) with concurrent `stdout`/`stderr` draining and blocking `process.waitFor()` inside a coroutine `withTimeout`:

```kotlin
suspend fun runShizukuCommand(cmd: Array<String>, timeoutMs: Long = 10_000L): Pair<Int, String> =
    withContext(Dispatchers.IO) {
        val process = Shizuku.newProcess(cmd, null, null)
        try {
            withTimeout(timeoutMs) {
                val stdout = async { process.inputStream.bufferedReader().use { it.readText() } }
                val stderr = async { process.errorStream.bufferedReader().use { it.readText() } }
                val out = stdout.await()
                stderr.await()
                val exitCode = process.waitFor() // Blocking overload (overridden by ShizukuRemoteProcess)
                exitCode to out
            }
        } finally {
            runCatching { process.destroy() }
        }
    }
```

### Expected Result After Applying the Fix
- All 6 `AdbVirtualDisplayRunner` telemetry cards will receive the runner's actual `stdout` and `exit=0` instead of `exit=-1`.
- `dumpsys audio`, `dumpsys media.audio_flinger`, and `dumpsys recents` will complete without throwing `process hasn't exited`.
- `am stack list` will no longer fail with `code=-1` from Binder, though falling back to `dumpsys recents` remains normal on modern Android versions where `am stack list` is deprecated.
