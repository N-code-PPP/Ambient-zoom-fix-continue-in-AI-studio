# Ambient Brain Log Analysis & Extracted Log Lines

**Date:** November 21, 2026  
**Target Package:** `com.ambientbrain.recorder` (PID `26589`, UID `10512`)  
**Analyzed Logs:**
- `20261121-120119-Ambient Brain.log` (PID-filtered app log, 145 lines)
- `20261121-120419-Ambient Brain.log` (PID-filtered app log, 207 lines)
- `20261121-121456-device.log` (unfiltered device log, 27,137 lines)

## Summary

| Component | Log / PID | Status | Key Finding |
| :--- | :--- | :--- | :--- |
| `ShizukuAdbManager` (`com.ambientbrain.recorder`) | `120119` & `121456` / PID `26589` | **Error** | Repeatedly throws `dumpsys audio inspection failed: process hasn't exited` before spawned `dumpsys` processes finish starting. |
| `MainActivity` (`com.ambientbrain.recorder`) | `120419` / PID `26589` | **No App Error** | Only harmless UI/framework noise (11 ms `RenderInspector` buffer timeout and Android 14+ WorkManager `onNetworkChanged()` warnings). |
| `AdbVirtualDisplayRunner` (`com.android.shell`) | `121456` / PID `11169` (`uid 2000`) | **Clean Exit (`0`)** | Ran virtual display + `REMOTE_SUBMIX` audio setup and exited cleanly (`status: 0`), with non-fatal Xiaomi SELinux/Binder telemetry denials and 0 encoded frames over 3,063 ms. |
| Shizuku (`moe.shizuku.privileged.api` / `ShizukuServerLog`) | `121456` / PIDs `22213`, `16053` | **Healthy** | Zero errors; successfully executed `SettingsShellCmd`, `dumpsys`, and `AdbVirtualDisplayRunner`. |

## 1. Root Cause: `ShizukuAdbManager: dumpsys audio inspection failed: process hasn't exited`

This error occurred in both `20261121-120119-Ambient Brain.log` (lines 97, 99) and `20261121-121456-device.log` (lines 9216, 9220).

### Mechanism
This is a known **Shizuku + Android Binder + `Process.waitFor(long, TimeUnit)`** incompatibility ([deepseek-harness Discussion #11603](https://github.com/deepseek-ai/deepseek-harness/discussions/11603)):

1. `ShizukuRemoteProcess` does **not** override `java.lang.Process.waitFor(long timeout, TimeUnit unit)`, so it falls back to Java's default polling loop. That default loop repeatedly calls `exitValue()` and only catches `IllegalThreadStateException` while the subprocess is still alive.
2. On the Shizuku server side, `Process.exitValue()` throws `IllegalThreadStateException("process hasn't exited")`. Because `IllegalThreadStateException` subclasses `IllegalArgumentException` and Android Binder's `Parcel` exception serialization maps it to `IllegalArgumentException`, the client receives `IllegalArgumentException: process hasn't exited`.
3. `Process.waitFor(long, TimeUnit)` does not catch `IllegalArgumentException`, so the exception escapes immediately on the first poll regardless of timeout.

*(If `waitFor(timeout, unit)` is not used, the same exception occurs when calling `process.exitValue()` directly right after reading `inputStream` EOF without calling blocking `process.waitFor()` first.)*

### Timeline Proof in `20261121-121456-device.log`
- `12:15:03.776` (line 9216): `ShizukuAdbManager` fails with `process hasn't exited`.
- `12:15:03.782` (line 9220): Second inspection fails 6 ms later with `process hasn't exited`.
- `12:15:03.816` – `12:15:03.869` (lines 9253, 9289, 9387, 9398): **40–93 ms later**, the four spawned `dumpsys` processes (`11138`/`11141` for `audio` and `11146`/`11147` for `media.audio_flinger`) actually start up and connect to their system services. This confirms Shizuku launched them successfully and `ShizukuAdbManager` aborted prematurely.

### Recommended Fix in `ShizukuAdbManager`
- Do not call `process.waitFor(timeout, unit)` on a `ShizukuRemoteProcess`.
- Drain `inputStream` and `errorStream` concurrently on `Dispatchers.IO` so large `dumpsys audio` / `dumpsys media.audio_flinger` output cannot block the pipe buffer.
- Call blocking `process.waitFor()` (which `ShizukuRemoteProcess` overrides) inside a coroutine `withTimeout`, or poll `exitValue()` catching **both** `IllegalThreadStateException` and `IllegalArgumentException`.
- Include `${e::class.simpleName}` in your warning log to confirm `IllegalArgumentException` vs. `IllegalThreadStateException`.

```kotlin
suspend fun runShizukuCommand(cmd: Array<String>, timeoutMs: Long = 5_000L): String =
    withContext(Dispatchers.IO) {
        val process = Shizuku.newProcess(cmd, null, null)
        try {
            withTimeout(timeoutMs) {
                val stdout = async { process.inputStream.bufferedReader().use { it.readText() } }
                val stderr = async { process.errorStream.bufferedReader().use { it.readText() } }
                val out = stdout.await()
                stderr.await()
                process.waitFor() // Blocking overload (overridden by ShizukuRemoteProcess)
                out
            }
        } finally {
            runCatching { process.destroy() }
        }
    }
```

## 2. Shell Runner Analysis (`AdbVirtualDisplayRunner`, PID `11169`)

In `20261121-121456-device.log`, `com.ambientbrain.recorder.service.AdbVirtualDisplayRunner` launched under `uid 2000` (`com.android.shell`) via `app_process`:

- **Clean lifecycle**: Created virtual display `AmbientBrain-MeetingVDisp` (720×1280, `displayId=16`), initialized `c2.mtk.avc.encoder`, `c2.mtk.avc.decoder`, `REMOTE_SUBMIX` `AudioRecord` (48 kHz stereo, `inputSource 8`), and `REMOTE_SUBMIX` `AudioTrack`/`AudioPolicy` (16 kHz mono, `usage=15`), then released resources and exited cleanly with `System.exit called, status: 0` (line 25370).
- **Non-fatal SELinux / Binder denials**: Xiaomi OEM telemetry hooks (`vendor.xiaomi.hardware.videoservice.IVideoService` and `vendor.xiaomi.hardware.mediaeventgatherservice.IMediaGather`) returned `Operation not permitted` / `avc: denied` under `u:r:shell:s0`, alongside `idmap2` and vendor property read denials during `app_process` init. None of these blocked standard MediaCodec or Audio APIs.
- **Zero-frame encoder note**: `c2.mtk.avc.encoder` stayed `STARTED` for 3,063 ms (`12:15:06.667` to `12:15:09.724`) with `Qin:0, DQin:0/0, Render:0, encode:0, DQout:0/0` (line 23814). This is expected if it was a capability/self-test probe, but worth investigating if live frames were expected during that window.

## 3. Shizuku & Shell Health (`20261121-121456-device.log`)

- **No Shizuku errors**: `ShizukuServerLog` (PID `16053`) and Shizuku Manager (PID `22213`) logged no errors or crashes.
- **Verified shell writes**: `SettingsShellCmd` successfully applied `settings_enable_monitor_phantom_procs=false`, `enabled_accessibility_services` (including `AmbientCaptureAccessibilityService`), and `accessibility_enabled=1` (lines 9089, 9120, 9134).

## 4. Extracted Related Log Lines

### A. `20261121-120119-Ambient Brain.log` — `ShizukuAdbManager` Failures

```text
92: 11-21 12:01:27.281 26589 26589 I MIUIInput: [MotionEvent] ViewRootImpl windowName 'com.ambientbrain.recorder/com.ambientbrain.recorder.ui.MainActivity', { action=ACTION_DOWN, id[0]=0, pointerCount=1, eventTime=1633078274, downTime=1633078274, phoneEventTime=1795237287276 } moveCount:0
94: 11-21 12:01:27.357 26589 26589 I MIUIInput: [MotionEvent] ViewRootImpl windowName 'com.ambientbrain.recorder/com.ambientbrain.recorder.ui.MainActivity', { action=ACTION_UP, id[0]=0, pointerCount=1, eventTime=1633078350, downTime=1633078274, phoneEventTime=1795237287352 } moveCount:0
97: 11-21 12:01:27.922 26589 30187 W ShizukuAdbManager: dumpsys audio inspection failed: process hasn't exited
98: 11-21 12:01:27.922 26589 30187 D AudioManager: getMode: mode=0, callApp=com.ambientbrain.recorder
99: 11-21 12:01:27.931 26589 30187 W ShizukuAdbManager: dumpsys audio inspection failed: process hasn't exited
100: 11-21 12:01:27.931 26589 30187 D AudioManager: getMode: mode=0, callApp=com.ambientbrain.recorder
```

### B. `20261121-120419-Ambient Brain.log` — Notable Framework Noise (No App Error)

```text
93: 11-21 12:04:26.318 26589 26589 I MIUIInput: [MotionEvent] ViewRootImpl windowName 'com.ambientbrain.recorder/com.ambientbrain.recorder.ui.MainActivity', { action=ACTION_DOWN, id[0]=0, pointerCount=1, eventTime=1633257313, downTime=1633257313, phoneEventTime=1795237466315 } moveCount:0
95: 11-21 12:04:26.417 26589 26589 I MIUIInput: [MotionEvent] ViewRootImpl windowName 'com.ambientbrain.recorder/com.ambientbrain.recorder.ui.MainActivity', { action=ACTION_UP, id[0]=0, pointerCount=1, eventTime=1633257410, downTime=1633257313, phoneEventTime=1795237466413 } moveCount:1
114: 11-21 12:04:26.958 26589 30126 E BLASTBufferQueue: [VRI[MainActivity]#12](f:0,a:4) Faking releaseBufferCallback from transactionCompleteCallback 328
115: 11-21 12:04:26.984 26589 30146 W RenderInspector: DequeueBuffer time out on com.ambientbrain.recorder/com.ambientbrain.recorder.ui.MainActivity, count=1, avg=11 ms, max=11 ms.
141: 11-21 12:04:29.355 26589 26589 W JobService: onNetworkChanged() not implemented in androidx.work.impl.background.systemjob.SystemJobService. Must override in a subclass.
```

### C. `20261121-121456-device.log` — Shizuku Shell, `ShizukuAdbManager`, and Delayed `dumpsys` Processes

```text
9089: 11-21 12:15:03.328  1822  2632 V SettingsShellCmd: putForUser(userId=0, table=global, key=settings_enable_monitor_phantom_procs, value=false, tag=null, makeDefault=false, overrideableByRestore=false)
9120: 11-21 12:15:03.364  1822  2632 V SettingsShellCmd: putForUser(userId=0, table=secure, key=enabled_accessibility_services, value=com.sameerasw.essentials/.services.tiles.ScreenOffAccessibilityService:moe.shizuku.privileged.api/moe.shizuku.manager.adb.AdbPairingAccessibilityService:com.ambientbrain.recorder/com.ambientbrain.recorder.service.AmbientCaptureAccessibilityService, tag=null, makeDefault=false, overrideableByRestore=false)
9134: 11-21 12:15:03.387  1822  3017 V SettingsShellCmd: putForUser(userId=0, table=secure, key=accessibility_enabled, value=1, tag=null, makeDefault=false, overrideableByRestore=false)
9216: 11-21 12:15:03.776 26589 30187 W ShizukuAdbManager: dumpsys audio inspection failed: process hasn't exited
9219: 11-21 12:15:03.777 26589 30187 D AudioManager: getMode: mode=0, callApp=com.ambientbrain.recorder
9220: 11-21 12:15:03.782 26589 30187 W ShizukuAdbManager: dumpsys audio inspection failed: process hasn't exited
9223: 11-21 12:15:03.786 26589 30187 D AudioManager: getMode: mode=0, callApp=com.ambientbrain.recorder
9253: 11-21 12:15:03.816 11138 11138 W dumpsys : Thread Pool max thread count is 0. Cannot cache binder as linkToDeath cannot be implemented. serviceName: audio
9289: 11-21 12:15:03.821 11141 11141 W dumpsys : Thread Pool max thread count is 0. Cannot cache binder as linkToDeath cannot be implemented. serviceName: audio
9387: 11-21 12:15:03.860 11146 11146 W dumpsys : Thread Pool max thread count is 0. Cannot cache binder as linkToDeath cannot be implemented. serviceName: media.audio_flinger
9398: 11-21 12:15:03.869 11147 11147 W dumpsys : Thread Pool max thread count is 0. Cannot cache binder as linkToDeath cannot be implemented. serviceName: media.audio_flinger
```

### D. `20261121-121456-device.log` — `AdbVirtualDisplayRunner` (`uid 2000` / `shell`) Lifecycle & Non-Fatal Denials

```text
10345: 11-21 12:15:06.382 11169 11169 D AndroidRuntime: >>>>>> START com.android.internal.os.RuntimeInit uid 2000 <<<<<<
10393: 11-21 12:15:06.489 11169 11169 W main    : type=1400 audit(0.0:2793126): avc:  denied  { read } for  name="u:object_r:system_mtk_app32_boost_prop:s0" dev="tmpfs" ino=413 scontext=u:r:shell:s0 tcontext=u:object_r:system_mtk_app32_boost_prop:s0 tclass=file permissive=0
10403: 11-21 12:15:06.504 11169 11169 D AndroidRuntime: Calling main entry com.ambientbrain.recorder.service.AdbVirtualDisplayRunner
10430: 11-21 12:15:06.543 11169 11169 W app_process: unable to execute idmap2: Permission denied
10433: 11-21 12:15:06.541 11169 11169 W main    : type=1400 audit(0.0:2793132): avc:  denied  { execute } for  name="idmap2" dev="dm-12" ino=9619190 scontext=u:r:shell:s0 tcontext=u:object_r:idmap_exec:s0 tclass=file permissive=0
10689: 11-21 12:15:06.639 11169 11169 E IPCThreadState: Binder transaction failure. id: 927727878, BR_*: 29201, error: -1 (Operation not permitted)
10691: 11-21 12:15:06.639 11169 11169 E app_process: associateClass: Expecting binder to have class 'vendor.xiaomi.hardware.videoservice.IVideoService' but descriptor is actually ''.
10860: 11-21 12:15:06.667 11169 11192 I MediaCodec: [mId: 0] [video-debug-enc] setState: STARTED
10861: 11-21 12:15:06.670  1822  2632 D DisplayManagerService: Virtual Display: creating DisplayDevice with VirtualDisplayAdapter
13937: 11-21 12:15:07.829 11169 11218 E IPCThreadState: Binder transaction failure. id: 927732110, BR_*: 29201, error: -1 (Operation not permitted)
13940: 11-21 12:15:07.829 11169 11218 E app_process: associateClass: Expecting binder to have class 'vendor.xiaomi.hardware.videoservice.IVideoService' but descriptor is actually ''.
13951: 11-21 12:15:07.825 11169 11169 W binder:11169_3: type=1400 audit(0.0:2793145): avc:  denied  { call } for  scontext=u:r:shell:s0 tcontext=u:r:hal_videoservice_default:s0 tclass=binder permissive=0
23525: 11-21 12:15:09.681 11169 11169 I libc    : kill: send 15 to pid 11211
23660: 11-21 12:15:09.709 11169 11169 E IPCThreadState: Binder transaction failure. id: 927739888, BR_*: 29201, error: -1 (Operation not permitted)
23662: 11-21 12:15:09.709 11169 11169 E app_process: associateClass: Expecting binder to have class 'vendor.xiaomi.hardware.videoservice.IVideoService' but descriptor is actually ''.
23673: 11-21 12:15:09.705 11169 11169 W main    : type=1400 audit(0.0:2793170): avc:  denied  { call } for  scontext=u:r:shell:s0 tcontext=u:r:hal_videoservice_default:s0 tclass=binder permissive=0
23805: 11-21 12:15:09.722  1822  3017 V VirtualDisplayAdapter: Release VirtualDisplay AmbientBrain-MeetingVDisp
23814: 11-21 12:15:09.724 11169 11192 I MediaCodec: [mId: 0] [video-debug-enc] Stats 3063ms:Client:com.android.shell,Component:c2.mtk.avc.encoder,Qin:0,DQin:0/0,Render:0,encode:0,DQout:0/0
24296: 11-21 12:15:09.757 11169 11169 V android.media.AudioRecord: Will record from REMOTE_SUBMIX at full fixed volume
24650: 11-21 12:15:09.788 11169 11169 E IPCThreadState: Binder transaction failure. id: 927741173, BR_*: 29201, error: -1 (Operation not permitted)
24653: 11-21 12:15:09.788 11169 11169 E app_process: associateClass: Expecting binder to have class 'vendor.xiaomi.hardware.mediaeventgatherservice.IMediaGather' but descriptor is actually ''.
25003: 11-21 12:15:09.828  1822  1876 I AS.AudioService: registerAudioPolicy for android.os.BinderProxy@8869d15 u/pid:2000/11169 with config:reg:548836277:ap:54 Mix:0-Typ:r-Rul:6
25265: 11-21 12:15:09.852 11169 11169 E IPCThreadState: Binder transaction failure. id: 927742231, BR_*: 29201, error: -1 (Operation not permitted)
25267: 11-21 12:15:09.852 11169 11169 E app_process: associateClass: Expecting binder to have class 'vendor.xiaomi.hardware.mediaeventgatherservice.IMediaGather' but descriptor is actually ''.
25370: 11-21 12:15:09.870 11169 11169 I app_process: System.exit called, status: 0
25371: 11-21 12:15:09.870 11169 11169 I AndroidRuntime: VM exiting with result code 0.
```
