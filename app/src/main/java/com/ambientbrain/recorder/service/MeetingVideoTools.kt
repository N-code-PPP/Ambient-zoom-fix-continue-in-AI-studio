package com.ambientbrain.recorder.service

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.ambientbrain.recorder.data.PreferencesRepo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class MeetingVideoMode(
    val title: String,
    val shortLabel: String,
    val subtitle: String,
    val requiresMediaProjection: Boolean,
    val requiresShizukuAdb: Boolean
) {
    AUDIO_ONLY(
        title = "Audio Only",
        shortLabel = "Audio",
        subtitle = "Record meeting audio only (smallest files & lowest battery).",
        requiresMediaProjection = false,
        requiresShizukuAdb = false
    ),
    MEDIA_PROJECTION_VIDEO(
        title = "Screen / Single-App Video (.mp4)",
        shortLabel = "Video 720p",
        subtitle = "Records 720p H.264 MP4 via MediaProjection. Pick 'Single app' in the Android prompt to record only the meeting window.",
        requiresMediaProjection = true,
        requiresShizukuAdb = false
    ),
    ADB_VIRTUAL_SCREEN(
        title = "ADB Virtual Screen (Background Video)",
        shortLabel = "ADB Virtual Screen",
        subtitle = "Uses Shizuku/ADB to run Zoom, Webex, or any selected app on an off-screen Virtual Display and record 720p MP4 in the background while your main screen stays free.",
        requiresMediaProjection = false,
        requiresShizukuAdb = true
    )
}

@Serializable
data class MeetingMetadataMarker(
    val type: String = "ONLINE_MEETING",
    val platform: String,
    val targetPackageName: String? = null,
    val captureMode: String,
    val videoMode: String = MeetingVideoMode.AUDIO_ONLY.name,
    val meetingTitle: String,
    val internalAudioCaptured: Boolean = false,
    val speakerphoneBoostUsed: Boolean = false,
    val ignoreInternalAppAudioForGemini: Boolean = true,
    val geminiAudioPath: String? = null,
    val videoPath: String? = null,
    val galleryUri: String? = null,
    val publicVideoDisplayPath: String? = null,
    val audioCaptureTier: String? = null,
    val virtualMicTier: String? = null
)

object MeetingMetadataHelper {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(marker: MeetingMetadataMarker): String {
        return json.encodeToString(listOf(marker))
    }

    fun parseFirst(callEventsJson: String?): MeetingMetadataMarker? {
        if (callEventsJson.isNullOrBlank() || !callEventsJson.contains("ONLINE_MEETING")) return null
        return runCatching {
            json.decodeFromString<List<MeetingMetadataMarker>>(callEventsJson)
                .firstOrNull { it.type == "ONLINE_MEETING" }
        }.getOrNull()
    }
}

data class InstalledAppInfo(
    val packageName: String,
    val appLabel: String,
    val uid: Int
)

object InstalledAppQueryHelper {
    private val PRIORITY_PACKAGES = listOf(
        "us.zoom.videomeetings",
        "com.cisco.wx2.android",
        "com.cisco.webex.meetings",
        "com.microsoft.teams",
        "com.google.android.apps.tachyon",
        "com.google.android.apps.meetings",
        "jp.naver.line.android",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.discord",
        "com.android.chrome",
        "com.google.android.youtube"
    )

    fun listLaunchableApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        val seen = HashSet<String>()
        val result = mutableListOf<InstalledAppInfo>()

        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == context.packageName || !seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm)?.toString()?.trim() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: pkg
            val uid = runCatching { pm.getApplicationInfo(pkg, 0).uid }.getOrDefault(-1)
            result.add(InstalledAppInfo(packageName = pkg, appLabel = label, uid = uid))
        }

        return result.sortedWith(
            compareBy<InstalledAppInfo> {
                val idx = PRIORITY_PACKAGES.indexOf(it.packageName)
                if (idx >= 0) idx else Int.MAX_VALUE
            }.thenBy { it.appLabel.lowercase(Locale.getDefault()) }
        )
    }

    fun resolveAppUid(context: Context, packageName: String?): Int {
        if (packageName.isNullOrBlank()) return -1
        return runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).uid
        }.getOrDefault(-1)
    }

    fun resolveAppLabel(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) return ""
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info)?.toString()?.trim().orEmpty()
        }.getOrDefault("")
    }
}

/**
 * Hardware H.264 video recorder fed directly by a [MediaProjection] [VirtualDisplay].
 */
class MediaProjectionVideoRecorder(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    val outputVideoFile: File,
    private val targetShortEdgePx: Int = 720,
    private val fps: Int = 15,
    private val bitrateBps: Int = 2_000_000
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var muxer: MediaMuxer? = null
    private var drainThread: Thread? = null

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val awaitingSyncFrameAfterResume = AtomicBoolean(false)
    private val muxerStarted = AtomicBoolean(false)
    private val framesWritten = AtomicLong(0L)

    var widthPx: Int = 720
        private set
    var heightPx: Int = 1280
        private set

    fun start() {
        if (isRunning.getAndSet(true)) return

        val (w, h, dpi) = computeRecordingDimensions(context, targetShortEdgePx)
        widthPx = w
        heightPx = h

        outputVideoFile.parentFile?.mkdirs()
        if (outputVideoFile.exists()) outputVideoFile.delete()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
        inputSurface = surface

        val mp4Muxer = MediaMuxer(outputVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = mp4Muxer

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AmbientBrain-MeetingVideo",
            w,
            h,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        drainThread = Thread({
            drainLoop(encoder, mp4Muxer)
        }, "MeetingVideo-Drain").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Started MediaProjection H.264 video capture: ${w}x${h}@${fps}fps (${bitrateBps / 1000} kbps)")
    }

    fun setPaused(paused: Boolean) {
        val prev = isPaused.getAndSet(paused)
        if (prev && !paused) {
            awaitingSyncFrameAfterResume.set(true)
            runCatching {
                codec?.setParameters(
                    Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    }
                )
            }
        }
    }

    fun stopAndFinalize(): Boolean {
        if (!isRunning.getAndSet(false)) {
            return outputVideoFile.exists() && outputVideoFile.length() > 1024L
        }

        runCatching { codec?.signalEndOfInputStream() }
        runCatching { drainThread?.join(3_500L) }
        drainThread = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null

        runCatching { inputSurface?.release() }
        inputSurface = null

        if (muxerStarted.get() && framesWritten.get() > 0L) {
            runCatching { muxer?.stop() }
        }
        runCatching { muxer?.release() }
        muxer = null

        val ok = outputVideoFile.exists() && outputVideoFile.length() > 1024L && framesWritten.get() > 0L
        if (!ok) {
            runCatching { outputVideoFile.delete() }
        }
        Log.i(TAG, "Stopped MediaProjection video capture: ok=$ok, frames=${framesWritten.get()}, bytes=${outputVideoFile.length()}")
        return ok
    }

    private fun drainLoop(encoder: MediaCodec, mp4Muxer: MediaMuxer) {
        val bufferInfo = MediaCodec.BufferInfo()
        var videoTrackIndex = -1
        var firstInputPtsUs = -1L
        var pausedAccumulatedUs = 0L
        var pauseStartPtsUs = -1L
        var lastWrittenPtsUs = -1L
        var eosWaitTicks = 0

        while (true) {
            val outIndex = try {
                encoder.dequeueOutputBuffer(bufferInfo, 25_000L)
            } catch (_: Throwable) {
                break
            }

            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!isRunning.get() && ++eosWaitTicks > 60) {
                    break
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted.get()) {
                    videoTrackIndex = mp4Muxer.addTrack(encoder.outputFormat)
                    mp4Muxer.start()
                    muxerStarted.set(true)
                }
            } else if (outIndex >= 0) {
                val encodedData = encoder.getOutputBuffer(outIndex)
                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                if (!isConfig && bufferInfo.size > 0 && encodedData != null && muxerStarted.get() && videoTrackIndex >= 0) {
                    val rawPtsUs = bufferInfo.presentationTimeUs
                    if (firstInputPtsUs < 0L) {
                        firstInputPtsUs = rawPtsUs
                    }

                    if (isPaused.get()) {
                        if (pauseStartPtsUs < 0L) {
                            pauseStartPtsUs = rawPtsUs
                        }
                    } else if (awaitingSyncFrameAfterResume.get() && !isKeyFrame) {
                        if (pauseStartPtsUs < 0L) {
                            pauseStartPtsUs = rawPtsUs
                        }
                    } else {
                        if (awaitingSyncFrameAfterResume.get() && isKeyFrame) {
                            awaitingSyncFrameAfterResume.set(false)
                        }
                        if (pauseStartPtsUs >= 0L) {
                            pausedAccumulatedUs += (rawPtsUs - pauseStartPtsUs).coerceAtLeast(0L)
                            pauseStartPtsUs = -1L
                        }
                        var adjustedPtsUs = (rawPtsUs - firstInputPtsUs - pausedAccumulatedUs).coerceAtLeast(0L)
                        if (lastWrittenPtsUs >= 0L && adjustedPtsUs <= lastWrittenPtsUs) {
                            adjustedPtsUs = lastWrittenPtsUs + 1_000L
                        }
                        lastWrittenPtsUs = adjustedPtsUs

                        val writeInfo = MediaCodec.BufferInfo().apply {
                            offset = bufferInfo.offset
                            size = bufferInfo.size
                            presentationTimeUs = adjustedPtsUs
                            flags = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                        }
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        runCatching {
                            mp4Muxer.writeSampleData(videoTrackIndex, encodedData, writeInfo)
                            framesWritten.incrementAndGet()
                        }
                    }
                }

                runCatching { encoder.releaseOutputBuffer(outIndex, false) }
                if (isEos) break
            }
        }
    }

    companion object {
        private const val TAG = "MediaProjVideoRec"

        fun computeRecordingDimensions(context: Context, targetShortEdgePx: Int = 720): Triple<Int, Int, Int> {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val bounds = wm?.currentWindowMetrics?.bounds
            val rawW = bounds?.width()?.takeIf { it > 0 } ?: 1080
            val rawH = bounds?.height()?.takeIf { it > 0 } ?: 2400
            val dpi = context.resources.displayMetrics.densityDpi.coerceIn(160, 480)

            val shortEdge = minOf(rawW, rawH).toFloat()
            val scale = (targetShortEdgePx.toFloat() / shortEdge).coerceAtMost(1.0f)
            val scaledW = (((rawW * scale).toInt() + 8) / 16) * 16
            val scaledH = (((rawH * scale).toInt() + 8) / 16) * 16
            return Triple(scaledW.coerceIn(320, 1280), scaledH.coerceIn(320, 1600), dpi)
        }
    }
}

data class AdbVirtualScreenSession(
    val process: Process,
    val displayId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val tempVideoFile: File,
    val tempAudioFile: File?,
    val previewFile: File?,
    val audioCaptureTier: String,
    val virtualMicTier: String,
    val audioSessionId: Int = 0,
    val stdinWriter: OutputStreamWriter,
    val stopLatch: CountDownLatch
)

data class AdbVirtualScreenStartResult(
    val session: AdbVirtualScreenSession? = null,
    val error: String? = null
)

enum class SelfTestStatus {
    PASS,
    WARN,
    FAIL
}

data class SelfTestCheckItem(
    val id: String,
    val title: String,
    val status: SelfTestStatus,
    val detail: String
)

data class StealthSelfTestReport(
    val ranAtMs: Long,
    val overallStatus: SelfTestStatus,
    val summary: String,
    val items: List<SelfTestCheckItem>,
    val rawLog: String
)

/**
 * Manages launching and stopping [AdbVirtualDisplayRunner] under Shizuku / ADB shell (`uid = 2000`),
 * including Per-UID silent speaker loopback, Per-UID virtual silent mic, AppOps fallback,
 * interactive preview/touch injection, Stealth readiness self-test, and moving target app tasks
 * between the background Virtual Display and Display 0.
 */
object AdbVirtualScreenController {
    private const val TAG = "AdbVirtualScreenCtrl"

    fun startVirtualScreen(
        context: Context,
        tempVideoFile: File,
        tempAudioFile: File? = null,
        targetUid: Int = -1,
        enableVirtualMic: Boolean = false,
        silentSpeaker: Boolean = true,
        width: Int = 720,
        height: Int = 1280,
        dpi: Int = 240,
        bitrateBps: Int = 2_000_000,
        fps: Int = 15,
        onAudioLevel: (Float, Boolean) -> Unit = { _, _ -> },
        onPreviewFrame: () -> Unit = {}
    ): AdbVirtualScreenStartResult {
        if (ShizukuAdbManager.getShizukuStatus(context) != ShizukuStatus.AUTHORIZED) {
            return AdbVirtualScreenStartResult(
                error = "Shizuku / ADB permission is not authorized. Authorize Shizuku in Diagnostics, or use Screen / Single-App Video mode."
            )
        }

        val apkPath = context.applicationInfo.sourceDir
        if (apkPath.isNullOrBlank()) {
            return AdbVirtualScreenStartResult(error = "Unable to locate APK path for app_process.")
        }

        tempVideoFile.parentFile?.mkdirs()
        if (tempVideoFile.exists()) deleteTempFileBestEffort(context, tempVideoFile)
        if (tempAudioFile != null) {
            tempAudioFile.parentFile?.mkdirs()
            if (tempAudioFile.exists()) deleteTempFileBestEffort(context, tempAudioFile)
        }

        val escapedApk = apkPath.replace("'", "'\\''")
        val escapedVideoOut = tempVideoFile.absolutePath.replace("'", "'\\''")
        val escapedAudioOut = (tempAudioFile?.absolutePath ?: "NONE").replace("'", "'\\''")
        val runnerClass = AdbVirtualDisplayRunner::class.java.name
        val virtMicArg = if (enableVirtualMic) "1" else "0"
        val silentSpeakerArg = if (silentSpeaker) "1" else "0"

        val cmd = "CLASSPATH='$escapedApk' app_process /system/bin $runnerClass '$escapedVideoOut' $width $height $dpi $bitrateBps $fps '$escapedAudioOut' $targetUid $virtMicArg $silentSpeakerArg"

        val process = try {
            ShizukuAdbManager.openShizukuShellProcess(cmd)
        } catch (t: Throwable) {
            return AdbVirtualScreenStartResult(
                error = "Failed to launch Shizuku virtual display runner: ${t.message ?: t.javaClass.simpleName}"
            )
        }

        val readyLatch = CountDownLatch(1)
        val stopLatch = CountDownLatch(1)
        val readyDisplayId = AtomicInteger(-1)
        val audioSessionIdRef = AtomicInteger(0)
        val audioTierRef = AtomicReference("DISABLED")
        val virtualMicTierRef = AtomicReference("DISABLED")
        val errorMessage = AtomicReference<String?>(null)

        Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("AUDIO_LEVEL:") -> {
                                val parts = line.removePrefix("AUDIO_LEVEL:").split(":")
                                val pct = parts.getOrNull(0)?.toIntOrNull() ?: 0
                                val detected = parts.getOrNull(1)?.trim() == "1"
                                onAudioLevel((pct / 100f).coerceIn(0f, 1f), detected)
                            }
                            line.startsWith("AUDIO_CAPTURE_TIER:") -> {
                                val tier = line.removePrefix("AUDIO_CAPTURE_TIER:").trim()
                                audioTierRef.set(tier)
                                Log.i(TAG, "[runner] Audio capture tier: $tier")
                            }
                            line.startsWith("AUDIO_SESSION_ID:") -> {
                                val sid = line.removePrefix("AUDIO_SESSION_ID:").trim().toIntOrNull() ?: 0
                                audioSessionIdRef.set(sid)
                                Log.i(TAG, "[runner] Audio session ID: $sid")
                            }
                            line.startsWith("VIRTUAL_MIC_TIER:") -> {
                                val tier = line.removePrefix("VIRTUAL_MIC_TIER:").trim()
                                virtualMicTierRef.set(tier)
                                Log.i(TAG, "[runner] Virtual mic tier: $tier")
                            }
                            line.startsWith("VIRTUAL_DISPLAY_READY:") -> {
                                val id = line.removePrefix("VIRTUAL_DISPLAY_READY:").trim().toIntOrNull() ?: -1
                                readyDisplayId.set(id)
                                readyLatch.countDown()
                            }
                            line.startsWith("VIRTUAL_DISPLAY_ERROR:") -> {
                                errorMessage.set(line.removePrefix("VIRTUAL_DISPLAY_ERROR:").trim())
                                readyLatch.countDown()
                            }
                            line.startsWith("PREVIEW_FRAME:") -> {
                                runCatching { onPreviewFrame() }
                            }
                            line.startsWith("VIRTUAL_DISPLAY_STOPPED:") -> {
                                Log.i(TAG, "[runner] $line")
                                stopLatch.countDown()
                            }
                            else -> Log.d(TAG, "[runner stdout] $line")
                        }
                    }
                }
            } catch (t: Throwable) {
                errorMessage.compareAndSet(null, t.message)
            } finally {
                readyLatch.countDown()
                stopLatch.countDown()
            }
        }, "AdbVDisp-Stdout").apply {
            isDaemon = true
            start()
        }

        val stderrBuffer = StringBuilder()
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (stderrBuffer.length < 1024) {
                            stderrBuffer.append(line).append('\n')
                        }
                        Log.w(TAG, "[runner stderr] $line")
                    }
                }
            }
        }, "AdbVDisp-Stderr").apply {
            isDaemon = true
            start()
        }

        val signaled = runCatching { readyLatch.await(10, TimeUnit.SECONDS) }.getOrDefault(false)
        val displayId = readyDisplayId.get()
        if (!signaled || displayId <= 0) {
            runCatching { process.destroyForcibly() }
            val reason = errorMessage.get()
                ?: stderrBuffer.toString().trim().takeIf { it.isNotBlank() }
                ?: "Timed out creating ADB virtual display on this device ROM."
            return AdbVirtualScreenStartResult(error = reason)
        }

        val previewFile = tempVideoFile.parentFile?.let { parent ->
            File(parent, "preview_${tempVideoFile.nameWithoutExtension}.jpg").also { existing ->
                if (existing.exists()) deleteTempFileBestEffort(context, existing)
            }
        }

        val session = AdbVirtualScreenSession(
            process = process,
            displayId = displayId,
            widthPx = width,
            heightPx = height,
            tempVideoFile = tempVideoFile,
            tempAudioFile = tempAudioFile,
            previewFile = previewFile,
            audioCaptureTier = audioTierRef.get(),
            virtualMicTier = virtualMicTierRef.get(),
            audioSessionId = audioSessionIdRef.get(),
            stdinWriter = OutputStreamWriter(process.outputStream),
            stopLatch = stopLatch
        )
        Log.i(
            TAG,
            "ADB Virtual Screen active: displayId=$displayId (${width}x${height}), audioTier=${session.audioCaptureTier}, audioSid=${session.audioSessionId}, virtualMicTier=${session.virtualMicTier}"
        )
        return AdbVirtualScreenStartResult(session = session)
    }

    fun injectTouchTap(context: Context, displayId: Int, x: Int, y: Int): Boolean {
        if (displayId <= 0 || ShizukuAdbManager.getShizukuStatus(context) != ShizukuStatus.AUTHORIZED) return false
        val safeX = x.coerceAtLeast(0)
        val safeY = y.coerceAtLeast(0)
        val (code, out) = ShizukuAdbManager.runShizukuShellCommand(
            "input -d $displayId tap $safeX $safeY",
            timeoutSec = 4L
        )
        if (code != 0) {
            Log.w(TAG, "injectTouchTap($displayId, $safeX, $safeY) failed code=$code: $out")
        }
        return code == 0
    }

    fun injectTouchSwipe(
        context: Context,
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 240
    ): Boolean {
        if (displayId <= 0 || ShizukuAdbManager.getShizukuStatus(context) != ShizukuStatus.AUTHORIZED) return false
        val safeDur = durationMs.coerceIn(60, 1500)
        val (code, out) = ShizukuAdbManager.runShizukuShellCommand(
            "input -d $displayId swipe ${x1.coerceAtLeast(0)} ${y1.coerceAtLeast(0)} ${x2.coerceAtLeast(0)} ${y2.coerceAtLeast(0)} $safeDur",
            timeoutSec = 4L
        )
        if (code != 0) {
            Log.w(TAG, "injectTouchSwipe($displayId) failed code=$code: $out")
        }
        return code == 0
    }

    fun injectKeyEvent(context: Context, displayId: Int, keyCode: Int): Boolean {
        if (displayId <= 0 || ShizukuAdbManager.getShizukuStatus(context) != ShizukuStatus.AUTHORIZED) return false
        val (code, out) = ShizukuAdbManager.runShizukuShellCommand(
            "input -d $displayId keyevent $keyCode",
            timeoutSec = 4L
        )
        if (code != 0) {
            Log.w(TAG, "injectKeyEvent($displayId, $keyCode) failed code=$code: $out")
        }
        return code == 0
    }

    /**
     * Runs a fast (~5s) on-device dry-run verifying every ROM subsystem required by Stealth Mode:
     * Shizuku authorization, Shell Context attribution, VirtualDisplay creation, Per-UID Silent
     * Speaker Loopback, Virtual Silent Mic / AppOps fallback, `am stack list` task discovery,
     * Accessibility concurrent mic sharing, and Floating Mini-Preview overlay readiness.
     */
    fun runStealthReadinessSelfTest(context: Context): StealthSelfTestReport {
        val nowMs = System.currentTimeMillis()
        val items = mutableListOf<SelfTestCheckItem>()
        val rawLog = StringBuilder()
        rawLog.appendLine("=== Stealth Isolation Readiness Self-Test ===")

        val shizukuStatus = ShizukuAdbManager.getShizukuStatus(context)
        if (shizukuStatus != ShizukuStatus.AUTHORIZED) {
            items.add(
                SelfTestCheckItem(
                    id = "SHIZUKU",
                    title = "Shizuku / ADB Shell Bridge",
                    status = SelfTestStatus.FAIL,
                    detail = "Status is ${shizukuStatus.name}. Start & authorize Shizuku first."
                )
            )
            rawLog.appendLine("SHIZUKU: FAIL (${shizukuStatus.name})")
            return StealthSelfTestReport(
                ranAtMs = nowMs,
                overallStatus = SelfTestStatus.FAIL,
                summary = "Shizuku is not authorized. Start & authorize Shizuku to run Stealth Isolation.",
                items = items,
                rawLog = rawLog.toString().trim()
            )
        }
        items.add(
            SelfTestCheckItem(
                id = "SHIZUKU",
                title = "Shizuku / ADB Shell Bridge",
                status = SelfTestStatus.PASS,
                detail = "Shizuku running & authorized (uid=2000 shell access ready)."
            )
        )
        rawLog.appendLine("SHIZUKU: PASS")

        // Pick a real installed meeting app UID if present, else our own UID as safe probe
        val probePkg = MeetingRecorderService.findInstalledPackage(context, MeetingPlatform.ZOOM)
            ?: MeetingRecorderService.findInstalledPackage(context, MeetingPlatform.WEBEX)
            ?: context.packageName
        val probeUid = InstalledAppQueryHelper.resolveAppUid(context, probePkg)
            .takeIf { it > 0 } ?: android.os.Process.myUid()
        rawLog.appendLine("Probe target: $probePkg (uid=$probeUid)")

        // 1. Spawn AdbVirtualDisplayRunner --self-test <probeUid>
        val apkPath = context.applicationInfo.sourceDir
        if (apkPath.isNullOrBlank()) {
            items.add(
                SelfTestCheckItem(
                    id = "RUNNER",
                    title = "Shizuku app_process Runner",
                    status = SelfTestStatus.FAIL,
                    detail = "Unable to resolve APK sourceDir for app_process."
                )
            )
        } else {
            val escapedApk = apkPath.replace("'", "'\\''")
            val runnerClass = AdbVirtualDisplayRunner::class.java.name
            val cmd = "CLASSPATH='$escapedApk' app_process /system/bin $runnerClass --self-test $probeUid"
            val (code, output) = ShizukuAdbManager.runShizukuShellCommand(cmd, timeoutSec = 14L)
            rawLog.appendLine("Runner exit=$code")
            if (output.isNotBlank()) {
                rawLog.appendLine(output.trim())
            }

            val parsedMap = LinkedHashMap<String, Pair<SelfTestStatus, String>>()
            for (line in output.lineSequence()) {
                if (line.startsWith("SELFTEST_ITEM:")) {
                    val payload = line.removePrefix("SELFTEST_ITEM:")
                    val parts = payload.split(":", limit = 3)
                    if (parts.size >= 3) {
                        val key = parts[0].trim()
                        val st = when (parts[1].trim().uppercase(Locale.US)) {
                            "PASS" -> SelfTestStatus.PASS
                            "WARN" -> SelfTestStatus.WARN
                            else -> SelfTestStatus.FAIL
                        }
                        parsedMap[key] = st to parts[2].trim()
                    }
                }
            }

            fun addParsedCheck(key: String, title: String, missingDetail: String) {
                val found = parsedMap[key]
                if (found != null) {
                    items.add(SelfTestCheckItem(id = key, title = title, status = found.first, detail = found.second))
                } else {
                    items.add(
                        SelfTestCheckItem(
                            id = key,
                            title = title,
                            status = SelfTestStatus.FAIL,
                            detail = missingDetail
                        )
                    )
                }
            }

            addParsedCheck(
                key = "SHELL_CONTEXT",
                title = "Shell Context Attribution (uid=2000)",
                missingDetail = "Runner did not report SHELL_CONTEXT (exit=$code)."
            )
            addParsedCheck(
                key = "VIRTUAL_DISPLAY",
                title = "Off-Screen Virtual Display + H.264",
                missingDetail = "VirtualDisplay probe timed out or crashed (exit=$code)."
            )
            addParsedCheck(
                key = "SPEAKER_LOOPBACK",
                title = "Per-UID Silent Speaker Loopback",
                missingDetail = "Speaker loopback AudioPolicy probe did not complete."
            )
            addParsedCheck(
                key = "VIRTUAL_MIC",
                title = "Per-UID Virtual Silent Mic",
                missingDetail = "Virtual mic probe did not complete (AppOps fallback will be used)."
            )
        }

        // 2. Verify task discovery & RootTask moving via `am stack list`
        val (stackCode, stackOut) = ShizukuAdbManager.runShizukuShellCommand(
            "am stack list 2>/dev/null | head -n 40",
            timeoutSec = 5L
        )
        val hasRootTask = stackCode == 0 && stackOut.contains("RootTask id=", ignoreCase = true)
        items.add(
            if (hasRootTask) {
                SelfTestCheckItem(
                    id = "TASK_MOVE",
                    title = "WindowManager Task Routing (am stack list)",
                    status = SelfTestStatus.PASS,
                    detail = "RootTask IDs readable — active calls can move seamlessly without relaunching."
                )
            } else {
                SelfTestCheckItem(
                    id = "TASK_MOVE",
                    title = "WindowManager Task Routing (am stack list)",
                    status = SelfTestStatus.WARN,
                    detail = "am stack list returned limited output (code=$stackCode); will fall back to dumpsys recents."
                )
            }
        )
        rawLog.appendLine("TASK_MOVE: hasRootTask=$hasRootTask (code=$stackCode)")

        // 3. Accessibility parallel room listening readiness
        val a11yBound = AmbientCaptureAccessibilityService.isSystemBound
        val a11yOverlay = AmbientCaptureAccessibilityService.isOverlayAttached
        items.add(
            if (a11yBound) {
                SelfTestCheckItem(
                    id = "A11Y_PARALLEL",
                    title = "Parallel Ambient Room Mic (Accessibility)",
                    status = SelfTestStatus.PASS,
                    detail = if (a11yOverlay) {
                        "Accessibility bound & 1x1 overlay attached — room mic stays active during VoIP calls."
                    } else {
                        "Accessibility bound — ready to protect parallel room mic capture."
                    }
                )
            } else {
                SelfTestCheckItem(
                    id = "A11Y_PARALLEL",
                    title = "Parallel Ambient Room Mic (Accessibility)",
                    status = SelfTestStatus.WARN,
                    detail = "Accessibility service is not bound; Android may silence parallel room listening during VoIP."
                )
            }
        )
        rawLog.appendLine("A11Y_PARALLEL: bound=$a11yBound overlay=$a11yOverlay")

        // 4. Floating Mini-Preview overlay readiness
        val canDrawOverlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        items.add(
            when {
                canDrawOverlay -> SelfTestCheckItem(
                    id = "MINI_PREVIEW",
                    title = "Interactive Floating Mini-Preview Overlay",
                    status = SelfTestStatus.PASS,
                    detail = "SYSTEM_ALERT_WINDOW granted — floating tap/swipe preview ready."
                )
                a11yBound -> SelfTestCheckItem(
                    id = "MINI_PREVIEW",
                    title = "Interactive Floating Mini-Preview Overlay",
                    status = SelfTestStatus.PASS,
                    detail = "Accessibility overlay ready (zero extra permission needed)."
                )
                else -> SelfTestCheckItem(
                    id = "MINI_PREVIEW",
                    title = "Interactive Floating Mini-Preview Overlay",
                    status = SelfTestStatus.WARN,
                    detail = "Grant 'Display over other apps' or bind Accessibility to enable the floating Stealth preview."
                )
            }
        )
        rawLog.appendLine("MINI_PREVIEW: canDrawOverlay=$canDrawOverlay a11yBound=$a11yBound")

        val failCount = items.count { it.status == SelfTestStatus.FAIL }
        val warnCount = items.count { it.status == SelfTestStatus.WARN }
        val overall = when {
            failCount > 0 -> SelfTestStatus.FAIL
            warnCount > 0 -> SelfTestStatus.WARN
            else -> SelfTestStatus.PASS
        }
        val summary = when (overall) {
            SelfTestStatus.PASS -> "All ${items.size} Stealth subsystems passed! Full 3-way isolation & interactive mini-preview are ready."
            SelfTestStatus.WARN -> "Stealth Mode is ready with $warnCount fallback/advisory item(s) (see matrix below)."
            SelfTestStatus.FAIL -> "$failCount critical check(s) failed on this ROM. Review items below for remediation."
        }

        return StealthSelfTestReport(
            ranAtMs = nowMs,
            overallStatus = overall,
            summary = summary,
            items = items,
            rawLog = rawLog.toString().trim()
        )
    }

    fun sendCommand(session: AdbVirtualScreenSession, command: String) {
        runCatching {
            synchronized(session.stdinWriter) {
                session.stdinWriter.write(command)
                session.stdinWriter.write("\n")
                session.stdinWriter.flush()
            }
        }.onFailure { err ->
            Log.w(TAG, "Failed sending '$command' to virtual screen runner: ${err.message}")
        }
    }

    fun stopAndFinalize(session: AdbVirtualScreenSession): Pair<Boolean, Boolean> {
        sendCommand(session, "STOP")
        runCatching { session.stdinWriter.close() }
        runCatching { session.stopLatch.await(6, TimeUnit.SECONDS) }
        val exited = runCatching { session.process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!exited) {
            runCatching { session.process.destroyForcibly() }
        }
        val hasVideo = session.tempVideoFile.exists() && session.tempVideoFile.length() > 1024L
        val hasAudio = session.tempAudioFile != null && session.tempAudioFile.exists() && session.tempAudioFile.length() > 1024L
        return hasVideo to hasAudio
    }

    fun deleteTempFileBestEffort(context: Context, file: File?) {
        if (file == null || !file.exists()) return
        val deletedByApp = runCatching { file.delete() }.getOrDefault(false)
        if (!deletedByApp && file.exists() && ShizukuAdbManager.getShizukuStatus(context) == ShizukuStatus.AUTHORIZED) {
            val escaped = file.absolutePath.replace("'", "'\\''")
            runCatching {
                ShizukuAdbManager.runShizukuShellCommand("rm -f '$escaped'", timeoutSec = 4L)
            }
        }
    }

    fun setAppRecordAudioAppOp(context: Context, packageName: String, allow: Boolean): Boolean {
        if (ShizukuAdbManager.getShizukuStatus(context) != ShizukuStatus.AUTHORIZED) return false
        val safePkg = packageName.replace(Regex("[^A-Za-z0-9._]"), "")
        if (safePkg.isBlank()) return false
        val mode = if (allow) "allow" else "ignore"
        val (code, out) = ShizukuAdbManager.runShizukuShellCommand(
            "cmd appops set --user 0 $safePkg RECORD_AUDIO $mode",
            timeoutSec = 5L
        )
        Log.i(TAG, "AppOps RECORD_AUDIO $mode for $safePkg -> code=$code ($out)")
        return code == 0
    }

    /**
     * Recovers any persisted `RECORD_AUDIO = ignore` AppOps lock left behind if a previous
     * Stealth session crashed, was OOM-killed, or survived a reboot.
     */
    fun restorePendingAppOpsMicLockIfAny(context: Context): Boolean {
        val prefs = PreferencesRepo(context.applicationContext)
        val pendingPkg = prefs.pendingAppOpsMicRestorePackage
        if (pendingPkg.isBlank()) return true

        // If package is no longer installed, clear the stale entry
        val installed = runCatching {
            context.packageManager.getPackageInfo(pendingPkg, 0)
            true
        }.getOrDefault(false)
        if (!installed) {
            prefs.pendingAppOpsMicRestorePackage = ""
            return true
        }

        val restored = setAppRecordAudioAppOp(context, pendingPkg, allow = true)
        if (restored) {
            prefs.pendingAppOpsMicRestorePackage = ""
            Log.i(TAG, "Recovered persisted AppOps RECORD_AUDIO allow for $pendingPkg")
        }
        return restored
    }

    /**
     * Moves a running app's enclosing RootTask onto [displayId] WITHOUT launching the app's
     * launcher activity on top (which would overwrite or PiP-minimize an active Zoom/Webex call).
     * Only falls back to `am start --display` if [launchIfNotRunning] is true and no running task
     * could be moved cleanly.
     */
    fun moveAppToDisplay(
        context: Context,
        packageName: String,
        displayId: Int,
        launchIfNotRunning: Boolean = true
    ): Boolean {
        if (ShizukuAdbManager.getShizukuStatus(context) != ShizukuStatus.AUTHORIZED) return false
        val safePkg = packageName.replace(Regex("[^A-Za-z0-9._]"), "")
        if (safePkg.isBlank()) return false

        val runningTask = findRunningRootTaskForPackage(safePkg)
        if (runningTask != null) {
            val (rootTaskId, currentDisplayId) = runningTask
            if (currentDisplayId == displayId) {
                Log.i(TAG, "$safePkg (rootTask=$rootTaskId) is already on display $displayId")
                return true
            }
            val (moveCode, moveOut) = ShizukuAdbManager.runShizukuShellCommand(
                "am display move-stack $rootTaskId $displayId",
                timeoutSec = 5L
            )
            val moveSucceeded = moveCode == 0 &&
                !moveOut.contains("Error", ignoreCase = true) &&
                !moveOut.contains("Exception", ignoreCase = true) &&
                !moveOut.contains("Unknown", ignoreCase = true)
            Log.i(TAG, "am display move-stack $rootTaskId -> display $displayId (code=$moveCode, ok=$moveSucceeded): $moveOut")
            if (moveSucceeded) {
                // Do NOT run am start on the launcher activity — keep the active call activity intact!
                return true
            }
        }

        if (!launchIfNotRunning) {
            return false
        }

        val launchComp = context.packageManager
            .getLaunchIntentForPackage(safePkg)
            ?.component
            ?.flattenToShortString()
            ?.replace(Regex("[^A-Za-z0-9._/$]"), "")

        val startCmd = if (!launchComp.isNullOrBlank()) {
            "am start --display $displayId --activity-reorder-to-front -n $launchComp"
        } else {
            "am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $safePkg"
        }
        val (code, out) = ShizukuAdbManager.runShizukuShellCommand(startCmd, timeoutSec = 6L)
        val startedOk = code == 0 &&
            !out.contains("Error", ignoreCase = true) &&
            !out.contains("Exception", ignoreCase = true)
        Log.i(TAG, "Launch $safePkg on display $displayId (code=$code, ok=$startedOk): $out")
        return startedOk
    }

    /**
     * Checks which package in [candidates] currently has an active running task in WindowManager.
     * Used to resolve `MeetingPlatform.AUTO` to the actually-running meeting app (e.g. Webex vs Zoom).
     */
    fun findRunningCandidatePackage(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        val (_, dump) = ShizukuAdbManager.runShizukuShellCommand("am stack list 2>/dev/null", timeoutSec = 5L)
        if (dump.isBlank()) return null
        for (pkg in candidates) {
            if (parseRootTaskFromStackList(dump, pkg) != null) {
                return pkg
            }
        }
        return null
    }

    /**
     * Returns `(rootTaskId, currentDisplayId)` for a running task belonging to [packageName].
     * Correctly tracks the enclosing `RootTask id=<id> ... displayId=<d>` header above child
     * `taskId=<child>: <packageName>/...` lines so split-screen/freeform/PiP tasks move reliably.
     */
    fun findRunningRootTaskForPackage(packageName: String): Pair<Int, Int>? {
        val (_, stackDump) = ShizukuAdbManager.runShizukuShellCommand(
            "am stack list 2>/dev/null",
            timeoutSec = 5L
        )
        if (stackDump.isNotBlank()) {
            val fromStackList = parseRootTaskFromStackList(stackDump, packageName)
            if (fromStackList != null) return fromStackList
        }

        // Fallback for ROMs where `am stack list` is restricted
        val (_, recentsDump) = ShizukuAdbManager.runShizukuShellCommand(
            "dumpsys activity recents 2>/dev/null | head -n 120",
            timeoutSec = 5L
        )
        if (recentsDump.isBlank()) return null

        val taskIdRegex = Regex("""taskId=(\d+)|#(\d+)\s+type=""")
        val displayIdRegex = Regex("""displayId=(\d+)""")
        for (line in recentsDump.lineSequence()) {
            if (!lineMatchesPackage(line, packageName)) continue
            val m = taskIdRegex.find(line) ?: continue
            val id = (m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() })?.toIntOrNull()
            if (id != null && id > 0) {
                val disp = displayIdRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
                return id to disp
            }
        }
        return null
    }

    private fun parseRootTaskFromStackList(dump: String, packageName: String): Pair<Int, Int>? {
        val rootHeaderRegex = Regex("""RootTask\s+id=(\d+).*?displayId=(\d+)""")
        val childTaskRegex = Regex("""taskId=(\d+)""")
        var currentRootId = -1
        var currentDisplayId = -1
        var fallbackMatch: Pair<Int, Int>? = null

        for (line in dump.lineSequence()) {
            val rootMatch = rootHeaderRegex.find(line)
            if (rootMatch != null) {
                currentRootId = rootMatch.groupValues[1].toIntOrNull() ?: -1
                currentDisplayId = rootMatch.groupValues[2].toIntOrNull() ?: -1
                continue
            }
            if (!lineMatchesPackage(line, packageName)) continue
            val childId = childTaskRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val resolvedId = if (currentRootId > 0) currentRootId else (childId ?: -1)
            if (resolvedId <= 0) continue

            val pair = resolvedId to currentDisplayId
            if (line.contains("visible=true", ignoreCase = true)) {
                return pair
            }
            if (fallbackMatch == null) {
                fallbackMatch = pair
            }
        }
        return fallbackMatch
    }

    private fun lineMatchesPackage(line: String, packageName: String): Boolean {
        val idx = line.indexOf(packageName, ignoreCase = true)
        if (idx < 0) return false
        val beforeOk = idx == 0 || !line[idx - 1].isLetterOrDigit() && line[idx - 1] != '.' && line[idx - 1] != '_'
        val endIdx = idx + packageName.length
        val afterOk = endIdx >= line.length || line[endIdx] == '/' || line[endIdx] == ' ' || line[endIdx] == ':' || line[endIdx] == '}' || line[endIdx] == ','
        return beforeOk && afterOk
    }
}

data class SavedPublicVideoResult(
    val contentUri: String? = null,
    val displayPath: String? = null
)

/**
 * Muxes H.264 video + ADTS AAC audio into a synchronized `.mp4`, saves directly to the user's
 * File-Explorer-accessible folder (default `Download/AmbientBrain`, adjustable in Settings via
 * relative path or SAF custom folder picker), and opens/shares MP4 videos cleanly.
 */
object MeetingMp4Muxer {
    private const val TAG = "MeetingMp4Muxer"

    fun muxVideoAndAdtsAudio(
        videoOnlyMp4: File,
        adtsAacFile: File?,
        outputMp4: File
    ): Boolean {
        if (!videoOnlyMp4.exists() || videoOnlyMp4.length() < 1024L) return false
        outputMp4.parentFile?.mkdirs()
        if (outputMp4.exists()) runCatching { outputMp4.delete() }

        val hasAudio = adtsAacFile != null && adtsAacFile.exists() && adtsAacFile.length() >= 1024L
        if (!hasAudio) {
            return runCatching {
                videoOnlyMp4.copyTo(outputMp4, overwrite = true)
                outputMp4.exists() && outputMp4.length() > 1024L
            }.getOrDefault(false)
        }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        return try {
            videoExtractor.setDataSource(videoOnlyMp4.absolutePath)
            val srcVideoTrack = findTrackIndex(videoExtractor, "video/")
            if (srcVideoTrack < 0) {
                return runCatching {
                    videoOnlyMp4.copyTo(outputMp4, overwrite = true)
                    outputMp4.exists() && outputMp4.length() > 1024L
                }.getOrDefault(false)
            }
            videoExtractor.selectTrack(srcVideoTrack)
            val videoFormat = videoExtractor.getTrackFormat(srcVideoTrack)

            var srcAudioTrack = -1
            var audioFormat: MediaFormat? = null
            runCatching {
                audioExtractor.setDataSource(adtsAacFile!!.absolutePath)
                srcAudioTrack = findTrackIndex(audioExtractor, "audio/")
                if (srcAudioTrack >= 0) {
                    audioExtractor.selectTrack(srcAudioTrack)
                    audioFormat = audioExtractor.getTrackFormat(srcAudioTrack)
                }
            }.onFailure { err ->
                Log.w(TAG, "Unable to parse ADTS audio for MP4 muxing; saving video-only: ${err.message}")
            }

            val mp4Muxer = MediaMuxer(outputMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mp4Muxer
            val dstVideoTrack = mp4Muxer.addTrack(videoFormat)
            val dstAudioTrack = audioFormat?.let { mp4Muxer.addTrack(it) } ?: -1
            mp4Muxer.start()

            val videoMaxInput = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                runCatching { videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
            } else 0
            val audioMaxInput = if (audioFormat != null && audioFormat!!.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                runCatching { audioFormat!!.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
            } else 0
            val initialBufBytes = maxOf(1024 * 1024, videoMaxInput, audioMaxInput)
                .coerceAtMost(8 * 1024 * 1024)
            val bufferRef = AtomicReference(ByteBuffer.allocateDirect(initialBufBytes))
            val info = MediaCodec.BufferInfo()

            if (dstAudioTrack >= 0 && srcAudioTrack >= 0) {
                interleaveTracksByPts(
                    videoExtractor = videoExtractor,
                    audioExtractor = audioExtractor,
                    muxer = mp4Muxer,
                    dstVideoTrack = dstVideoTrack,
                    dstAudioTrack = dstAudioTrack,
                    bufferRef = bufferRef,
                    info = info
                )
            } else {
                copyTrackSamples(videoExtractor, mp4Muxer, dstVideoTrack, bufferRef, info)
            }

            mp4Muxer.stop()
            mp4Muxer.release()
            muxer = null

            val ok = outputMp4.exists() && outputMp4.length() > 1024L
            Log.i(TAG, "Muxed meeting MP4: ${outputMp4.name} (${outputMp4.length()} bytes, audioMuxed=${dstAudioTrack >= 0}, buf=${bufferRef.get().capacity()}B, interleaved=true)")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "MP4 audio+video mux failed; falling back to video-only copy", t)
            runCatching { muxer?.release() }
            runCatching { outputMp4.delete() }
            runCatching {
                videoOnlyMp4.copyTo(outputMp4, overwrite = true)
                outputMp4.exists() && outputMp4.length() > 1024L
            }.getOrDefault(false)
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            runCatching { muxer?.release() }
        }
    }

    private fun readSampleWithResize(
        extractor: MediaExtractor,
        bufferRef: AtomicReference<ByteBuffer>
    ): Int {
        var buf = bufferRef.get()
        while (true) {
            buf.clear()
            try {
                return extractor.readSampleData(buf, 0)
            } catch (iae: IllegalArgumentException) {
                val curCap = buf.capacity()
                if (curCap >= 16 * 1024 * 1024) {
                    throw iae
                }
                val nextCap = (curCap * 2).coerceAtMost(16 * 1024 * 1024)
                Log.w(TAG, "Mux sample exceeded ${curCap}B buffer; resizing to ${nextCap}B and retrying")
                buf = ByteBuffer.allocateDirect(nextCap)
                bufferRef.set(buf)
            }
        }
    }

    private fun findTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun interleaveTracksByPts(
        videoExtractor: MediaExtractor,
        audioExtractor: MediaExtractor,
        muxer: MediaMuxer,
        dstVideoTrack: Int,
        dstAudioTrack: Int,
        bufferRef: AtomicReference<ByteBuffer>,
        info: MediaCodec.BufferInfo
    ) {
        var videoDone = videoExtractor.sampleTrackIndex < 0
        var audioDone = audioExtractor.sampleTrackIndex < 0
        var lastVideoPtsUs = -1L
        var lastAudioPtsUs = -1L

        while (!videoDone || !audioDone) {
            val vPts = if (!videoDone) videoExtractor.sampleTime.coerceAtLeast(0L) else Long.MAX_VALUE
            val aPts = if (!audioDone) audioExtractor.sampleTime.coerceAtLeast(0L) else Long.MAX_VALUE
            val writeVideo = !videoDone && (audioDone || vPts <= aPts)

            val activeExtractor = if (writeVideo) videoExtractor else audioExtractor
            val dstTrack = if (writeVideo) dstVideoTrack else dstAudioTrack

            val sampleSize = readSampleWithResize(activeExtractor, bufferRef)
            if (sampleSize < 0) {
                if (writeVideo) videoDone = true else audioDone = true
                continue
            }

            var ptsUs = activeExtractor.sampleTime.coerceAtLeast(0L)
            if (writeVideo) {
                if (lastVideoPtsUs >= 0L && ptsUs <= lastVideoPtsUs) {
                    ptsUs = lastVideoPtsUs + 1_000L
                }
                lastVideoPtsUs = ptsUs
            } else {
                if (lastAudioPtsUs >= 0L && ptsUs <= lastAudioPtsUs) {
                    ptsUs = lastAudioPtsUs + 1_000L
                }
                lastAudioPtsUs = ptsUs
            }

            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = ptsUs
            info.flags = mapExtractorFlags(activeExtractor.sampleFlags)

            muxer.writeSampleData(dstTrack, bufferRef.get(), info)
            if (!activeExtractor.advance()) {
                if (writeVideo) videoDone = true else audioDone = true
            }
        }
    }

    private fun copyTrackSamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        dstTrackIndex: Int,
        bufferRef: AtomicReference<ByteBuffer>,
        info: MediaCodec.BufferInfo
    ) {
        var lastPtsUs = -1L
        while (true) {
            val sampleSize = readSampleWithResize(extractor, bufferRef)
            if (sampleSize < 0) break

            var ptsUs = extractor.sampleTime.coerceAtLeast(0L)
            if (lastPtsUs >= 0L && ptsUs <= lastPtsUs) {
                ptsUs = lastPtsUs + 1_000L
            }
            lastPtsUs = ptsUs

            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = ptsUs
            info.flags = mapExtractorFlags(extractor.sampleFlags)

            muxer.writeSampleData(dstTrackIndex, bufferRef.get(), info)
            extractor.advance()
        }
    }

    private fun mapExtractorFlags(sampleFlags: Int): Int {
        var codecFlags = 0
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }

    /**
     * Saves the recorded MP4 into the user-configured File Explorer accessible folder:
     * 1) Custom SAF folder URI (if user picked one via Android folder picker in Settings), OR
     * 2) MediaStore Downloads / Movies relative folder (default `Download/AmbientBrain`).
     */
    fun saveVideoToConfiguredPublicFolder(
        context: Context,
        sourceMp4: File,
        meetingTitle: String,
        startWallMs: Long,
        prefs: PreferencesRepo = PreferencesRepo(context)
    ): SavedPublicVideoResult {
        if (!sourceMp4.exists() || sourceMp4.length() < 1024L) return SavedPublicVideoResult()

        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(startWallMs))
        val cleanTitle = meetingTitle
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .replace(Regex("\\s+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "Meeting" }
        val fileName = "${stamp}_${cleanTitle}.mp4"

        // 1. Try user-selected custom SAF folder URI if configured in Settings
        val customTreeStr = prefs.meetingVideoCustomTreeUri
        if (customTreeStr.isNotBlank()) {
            val safRes = saveViaSafTreeUri(
                context = context,
                sourceMp4 = sourceMp4,
                treeUriStr = customTreeStr,
                folderLabel = prefs.meetingVideoCustomFolderLabel.ifBlank { "Custom Folder" },
                fileName = fileName
            )
            if (safRes.contentUri != null) {
                return safRes
            }
            Log.w(TAG, "SAF custom folder save failed; falling back to MediaStore Download folder")
        }

        // 2. Save via MediaStore Downloads (default Download/AmbientBrain) or Movies/DCIM
        val relFolder = prefs.meetingVideoSaveFolder
        return saveViaMediaStore(
            context = context,
            sourceMp4 = sourceMp4,
            relativeFolder = relFolder,
            fileName = fileName
        )
    }

    private fun saveViaSafTreeUri(
        context: Context,
        sourceMp4: File,
        treeUriStr: String,
        folderLabel: String,
        fileName: String
    ): SavedPublicVideoResult {
        return try {
            val treeUri = Uri.parse(treeUriStr)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val resolver = context.contentResolver
            val createdUri = DocumentsContract.createDocument(resolver, parentDocUri, "video/mp4", fileName)
                ?: return SavedPublicVideoResult()
            resolver.openOutputStream(createdUri)?.use { out ->
                sourceMp4.inputStream().use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            } ?: return SavedPublicVideoResult()
            Log.i(TAG, "Saved meeting MP4 to custom SAF folder '$folderLabel/$fileName' -> $createdUri")
            SavedPublicVideoResult(
                contentUri = createdUri.toString(),
                displayPath = "$folderLabel/$fileName"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed writing meeting MP4 to SAF tree URI: ${t.message}")
            SavedPublicVideoResult()
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        sourceMp4: File,
        relativeFolder: String,
        fileName: String
    ): SavedPublicVideoResult {
        val normalized = PreferencesRepo.normalizePublicVideoFolder(relativeFolder)
        val lower = normalized.lowercase(Locale.US)
        val isVideoCollection = lower.startsWith("movies") || lower.startsWith("dcim")

        val collectionUri = if (isVideoCollection) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, normalized)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = runCatching { resolver.insert(collectionUri, values) }.getOrNull()
            ?: return SavedPublicVideoResult()

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                sourceMp4.inputStream().use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            } ?: throw IllegalStateException("Failed to open MediaStore output stream")

            val doneValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, doneValues, null, null)
            Log.i(TAG, "Saved meeting MP4 to public folder '$normalized/$fileName' -> $uri")
            SavedPublicVideoResult(
                contentUri = uri.toString(),
                displayPath = "$normalized/$fileName"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed writing meeting MP4 to MediaStore ($normalized): ${t.message}")
            runCatching { resolver.delete(uri, null, null) }
            SavedPublicVideoResult()
        }
    }

    fun resolveReadableSafLabel(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (!docId.isNullOrBlank()) {
            val cleaned = docId.substringAfter(":", docId).trim('/')
            if (cleaned.isNotBlank()) return cleaned
        }
        return treeUri.lastPathSegment?.substringAfter(":")?.trim('/')?.ifBlank { "Custom Folder" } ?: "Custom Folder"
    }

    fun isPlayableVideoAvailable(context: Context, contentUriStr: String?, fallbackFile: File?): Boolean {
        return resolvePlayableUri(context, contentUriStr, fallbackFile) != null
    }

    fun openVideo(context: Context, contentUriStr: String?, fallbackFile: File?): Boolean {
        val uri = resolvePlayableUri(context, contentUriStr, fallbackFile) ?: return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun shareVideo(context: Context, contentUriStr: String?, fallbackFile: File?): Boolean {
        val uri = resolvePlayableUri(context, contentUriStr, fallbackFile) ?: return false
        return runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share Meeting Video").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    private fun resolvePlayableUri(context: Context, contentUriStr: String?, fallbackFile: File?): Uri? {
        if (!contentUriStr.isNullOrBlank()) {
            val parsed = runCatching { Uri.parse(contentUriStr) }.getOrNull()
            if (parsed != null && isContentUriReadable(context, parsed)) {
                return parsed
            }
        }
        if (fallbackFile != null && fallbackFile.exists()) {
            return runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fallbackFile)
            }.getOrNull()
        }
        return null
    }

    private fun isContentUriReadable(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
}
