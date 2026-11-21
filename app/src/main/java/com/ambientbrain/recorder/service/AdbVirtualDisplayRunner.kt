package com.ambientbrain.recorder.service

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Standalone `app_process` daemon executed under Shizuku / ADB shell (`uid = 2000`, `com.android.shell`).
 *
 * On Android 13–15, `com.android.shell` holds `CAPTURE_VIDEO_OUTPUT`, `CAPTURE_AUDIO_OUTPUT`,
 * `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, and `MODIFY_AUDIO_ROUTING`. This runner leverages those
 * shell privileges (zero root required) to perform full 3-way hardware isolation for Zoom, Webex,
 * or any user-selected app:
 *  1. Headless public [VirtualDisplay] backed by a hardware H.264 [MediaCodec] + [MediaMuxer]
 *     so the physical screen (Display 0) stays completely free or locked.
 *  2. Per-UID `AudioPolicy` (`ROUTE_FLAG_LOOP_BACK`) capturing the target app's audio output
 *     (`USAGE_VOICE_COMMUNICATION` + `USAGE_MEDIA`) silently off the physical speaker into a
 *     crash-safe 16 kHz 64 kbps ADTS `.aac` file (using shell-attributed `AudioRecord.Builder.setContext`).
 *  3. Per-UID Virtual Silent Microphone (`AudioPolicy` `MIX_TYPE_RECORDERS` + shell-attributed silent
 *     [AudioTrack]) so the target meeting app stays connected without touching the physical microphone
 *     hardware (leaving Ambient Brain free to listen to the real room in parallel).
 *
 * Protocol over stdout / stdin:
 *  - `VIRTUAL_DISPLAY_READY:<displayId>`
 *  - `AUDIO_CAPTURE_TIER:<tier>`
 *  - `AUDIO_SESSION_ID:<sessionId>`
 *  - `VIRTUAL_MIC_TIER:<tier>`
 *  - `AUDIO_LEVEL:<0..100>:<everDetected 0|1>`
 *  - Accepts `PAUSE`, `RESUME`, `STOP` on stdin
 *  - `VIRTUAL_DISPLAY_STOPPED:<videoBytes>:<audioBytes>`
 *  - `VIRTUAL_DISPLAY_ERROR:<message>`
 */
object AdbVirtualDisplayRunner {

    private const val FLAG_PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC // 1
    private const val FLAG_OWN_CONTENT_ONLY = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY // 8
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6 // 64
    private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7 // 128
    private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9 // 512
    private const val FLAG_TRUSTED = 1 shl 10 // 1024
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11 // 2048
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12 // 4096

    private const val USAGE_VIRTUAL_SOURCE = 15
    private const val SUBMIX_FIXED_VOLUME_TAG = "fixedVolume"

    private const val TARGET_AUDIO_RATE = 16_000
    private const val FRAME_SAMPLES_16K = 320 // 20 ms
    private const val MAX_CATCHUP_PAD_SAMPLES_PER_READ = TARGET_AUDIO_RATE / 2 // 500 ms max per read
    private const val AUDIO_ANCHOR_FALLBACK_WAIT_MS = 4_000L

    private data class ShellAudioCaptureHandle(
        val audioRecord: AudioRecord,
        val audioPolicy: Any?,
        val sampleRateHz: Int,
        val channelCount: Int,
        val tierLabel: String
    )

    private data class ShellVirtualMicHandle(
        val audioTrack: AudioTrack,
        val audioPolicy: Any?,
        val sampleRateHz: Int,
        val tierLabel: String
    )

    private data class VirtualDisplayCreateResult(
        val virtualDisplay: VirtualDisplay,
        val tierName: String
    )

    private data class KeyframeSnapshot(
        val seq: Long,
        val bytes: ByteArray
    )

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            emitLine("VIRTUAL_DISPLAY_ERROR:Missing output path argument")
            kotlin.system.exitProcess(1)
        }

        if (args[0] == "--self-test") {
            val probeUid = args.getOrNull(1)?.toIntOrNull() ?: -1
            runSelfTest(probeUid)
            kotlin.system.exitProcess(0)
        }

        val outputVideoPath = args[0]
        val rawWidth = args.getOrNull(1)?.toIntOrNull() ?: 720
        val rawHeight = args.getOrNull(2)?.toIntOrNull() ?: 1280
        val dpi = (args.getOrNull(3)?.toIntOrNull() ?: 240).coerceIn(120, 480)
        val bitrate = (args.getOrNull(4)?.toIntOrNull() ?: 2_000_000).coerceIn(600_000, 8_000_000)
        val fps = (args.getOrNull(5)?.toIntOrNull() ?: 15).coerceIn(10, 30)
        val outputAudioPath = args.getOrNull(6)?.trim()?.takeIf { it.isNotBlank() && it != "NONE" }
        val targetUid = args.getOrNull(7)?.toIntOrNull() ?: -1
        val wantVirtualMic = args.getOrNull(8)?.trim() == "1"
        val wantSilentSpeaker = args.getOrNull(9)?.trim() != "0"

        val width = ((rawWidth + 8) / 16) * 16
        val height = ((rawHeight + 8) / 16) * 16

        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var virtualDisplay: VirtualDisplay? = null
        var muxer: MediaMuxer? = null
        var looperThread: HandlerThread? = null

        var audioManager: AudioManager? = null
        val speakerCaptureHandleRef = AtomicReference<ShellAudioCaptureHandle?>(null)
        val rebindAudioRequested = AtomicBoolean(false)
        var virtualMicHandle: ShellVirtualMicHandle? = null
        var aacWriter: ShellAdtsAacWriter? = null
        var audioCaptureThread: Thread? = null
        var virtualMicThread: Thread? = null
        var previewThread: Thread? = null

        val running = AtomicBoolean(true)
        val paused = AtomicBoolean(false)
        val pauseStartedMonoMs = AtomicLong(-1L)
        val accumulatedPausedMs = AtomicLong(0L)
        val mediaTimelineAnchorMonoMs = AtomicLong(0L)
        val pausedAtAnchorMs = AtomicLong(0L)
        val awaitingSyncFrameAfterResume = AtomicBoolean(false)
        val muxerStarted = AtomicBoolean(false)
        val framesWritten = AtomicLong(0L)

        // Live preview state (0% overhead while previewOutputPathRef == null)
        val previewOutputPathRef = AtomicReference<String?>(null)
        val latestVideoFormatRef = AtomicReference<MediaFormat?>(null)
        val latestKeyframeRef = AtomicReference<KeyframeSnapshot?>(null)
        val keyframeSeqCounter = AtomicLong(0L)
        val previewWakeSignal = Object()

        try {
            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }

            // Dedicated live Looper thread so AudioPolicy & DisplayManager callbacks actually dispatch
            val callbackThread = HandlerThread("AdbVDisp-Callbacks").apply {
                isDaemon = true
                start()
            }
            looperThread = callbackThread
            val callbackLooper = callbackThread.looper
            val callbackHandler = Handler(callbackLooper)

            val shellContext = buildShellAttributedContext()
            val displayManager = shellContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: throw IllegalStateException("DisplayManager unavailable in shell context")
            val am = shellContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager = am

            val outVideoFile = File(outputVideoPath)
            outVideoFile.parentFile?.mkdirs()
            if (outVideoFile.exists()) outVideoFile.delete()

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
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

            val vdResult = createVirtualDisplayWithFallback(
                dm = displayManager,
                width = width,
                height = height,
                dpi = dpi,
                surface = surface,
                handler = callbackHandler
            )
            val vd = vdResult.virtualDisplay
            virtualDisplay = vd

            val displayId = vd.display?.displayId ?: -1
            if (displayId <= 0) {
                throw IllegalStateException("VirtualDisplay returned invalid displayId=$displayId")
            }

            val mp4Muxer = MediaMuxer(outVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mp4Muxer

            val sessionStartMonoMs = SystemClock.elapsedRealtime()

            val drainThread = Thread({
                drainEncodedVideoLoop(
                    encoder = encoder,
                    muxer = mp4Muxer,
                    running = running,
                    paused = paused,
                    awaitingSyncFrameAfterResume = awaitingSyncFrameAfterResume,
                    muxerStarted = muxerStarted,
                    framesWritten = framesWritten,
                    mediaTimelineAnchorMonoMs = mediaTimelineAnchorMonoMs,
                    accumulatedPausedMs = accumulatedPausedMs,
                    pausedAtAnchorMs = pausedAtAnchorMs,
                    previewOutputPathRef = previewOutputPathRef,
                    latestVideoFormatRef = latestVideoFormatRef,
                    latestKeyframeRef = latestKeyframeRef,
                    keyframeSeqCounter = keyframeSeqCounter,
                    previewWakeSignal = previewWakeSignal
                )
            }, "AdbVDisp-Drain").apply {
                isDaemon = true
                start()
            }

            // Optional Step A: Isolated Silent Speaker Audio Capture inside Shell UID 2000
            if (outputAudioPath != null && am != null) {
                val outAudioFile = File(outputAudioPath)
                outAudioFile.parentFile?.mkdirs()
                if (outAudioFile.exists()) outAudioFile.delete()

                val captureRes = setupIsolatedAudioCapture(
                    shellContext = shellContext,
                    audioManager = am,
                    callbackLooper = callbackLooper,
                    targetUid = targetUid,
                    silentSpeaker = wantSilentSpeaker
                )
                if (captureRes.first != null) {
                    val handle = captureRes.first!!
                    speakerCaptureHandleRef.set(handle)
                    val writer = ShellAdtsAacWriter(outAudioFile)
                    writer.start()
                    aacWriter = writer
                    handle.audioRecord.startRecording()

                    audioCaptureThread = Thread({
                        runIsolatedAudioLoop(
                            shellContext = shellContext,
                            audioManager = am,
                            callbackLooper = callbackLooper,
                            targetUid = targetUid,
                            wantSilentSpeaker = wantSilentSpeaker,
                            handleRef = speakerCaptureHandleRef,
                            rebindRequested = rebindAudioRequested,
                            writer = writer,
                            running = running,
                            paused = paused,
                            accumulatedPausedMs = accumulatedPausedMs,
                            mediaTimelineAnchorMonoMs = mediaTimelineAnchorMonoMs,
                            pausedAtAnchorMs = pausedAtAnchorMs,
                            sessionStartMonoMs = sessionStartMonoMs
                        )
                    }, "AdbVDisp-Audio").apply {
                        isDaemon = true
                        start()
                    }
                    emitLine("AUDIO_CAPTURE_TIER:${handle.tierLabel}")
                    emitLine("AUDIO_SESSION_ID:${handle.audioRecord.audioSessionId}")
                } else {
                    emitLine("AUDIO_CAPTURE_TIER:FAILED:${captureRes.second ?: "Unavailable"}")
                }
            } else {
                emitLine("AUDIO_CAPTURE_TIER:DISABLED")
            }

            // Optional Step B: Per-UID Virtual Silent Microphone inside Shell UID 2000
            if (wantVirtualMic && targetUid > 0 && am != null) {
                val micRes = setupVirtualSilentMicrophone(
                    shellContext = shellContext,
                    audioManager = am,
                    callbackLooper = callbackLooper,
                    targetUid = targetUid
                )
                if (micRes.first != null) {
                    val mHandle = micRes.first!!
                    virtualMicHandle = mHandle
                    mHandle.audioTrack.play()
                    virtualMicThread = Thread({
                        runVirtualMicSilenceLoop(mHandle, running)
                    }, "AdbVDisp-VirtMic").apply {
                        isDaemon = true
                        start()
                    }
                    emitLine("VIRTUAL_MIC_TIER:${mHandle.tierLabel}")
                } else {
                    emitLine("VIRTUAL_MIC_TIER:FALLBACK_APPOPS:${micRes.second ?: "Policy rejected"}")
                }
            } else {
                emitLine("VIRTUAL_MIC_TIER:DISABLED")
            }

            // On-demand Mini-Preview decoder thread (idles with 0% CPU until PREVIEW_ON <path>)
            previewThread = Thread({
                runPreviewDecodeLoop(
                    encoder = encoder,
                    running = running,
                    previewOutputPathRef = previewOutputPathRef,
                    latestVideoFormatRef = latestVideoFormatRef,
                    latestKeyframeRef = latestKeyframeRef,
                    previewWakeSignal = previewWakeSignal
                )
            }, "AdbVDisp-Preview").apply {
                isDaemon = true
                start()
            }

            emitLine("VIRTUAL_DISPLAY_READY:$displayId")

            // Command loop on stdin until STOP or parent closes stdin
            val reader = BufferedReader(InputStreamReader(System.`in`))
            while (running.get()) {
                val line = try {
                    reader.readLine()
                } catch (_: Throwable) {
                    null
                } ?: break

                val trimmed = line.trim()
                val upper = trimmed.uppercase()
                when {
                    upper == "PAUSE" -> {
                        if (paused.compareAndSet(false, true)) {
                            pauseStartedMonoMs.set(SystemClock.elapsedRealtime())
                        }
                    }
                    upper == "RESUME" -> {
                        if (paused.compareAndSet(true, false)) {
                            val startedAt = pauseStartedMonoMs.getAndSet(-1L)
                            if (startedAt > 0L) {
                                val delta = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                                accumulatedPausedMs.addAndGet(delta)
                            }
                            awaitingSyncFrameAfterResume.set(true)
                            requestEncoderSyncFrame(encoder)
                        }
                    }
                    upper.startsWith("PREVIEW_ON ") -> {
                        val path = trimmed.substring("PREVIEW_ON ".length).trim()
                        if (path.isNotBlank()) {
                            previewOutputPathRef.set(path)
                            requestEncoderSyncFrame(encoder)
                            synchronized(previewWakeSignal) { previewWakeSignal.notifyAll() }
                        }
                    }
                    upper == "PREVIEW_OFF" -> {
                        previewOutputPathRef.set(null)
                        synchronized(previewWakeSignal) { previewWakeSignal.notifyAll() }
                    }
                    upper == "PREVIEW_SNAP" -> {
                        if (previewOutputPathRef.get() != null) {
                            requestEncoderSyncFrame(encoder)
                            synchronized(previewWakeSignal) { previewWakeSignal.notifyAll() }
                        }
                    }
                    upper == "REBIND_AUDIO" -> {
                        if (speakerCaptureHandleRef.get() != null) {
                            rebindAudioRequested.set(true)
                        } else {
                            emitLine("AUDIO_REBOUND:FAILED:No active isolated audio pipeline")
                        }
                    }
                    upper == "STOP" -> break
                }
            }

            running.set(false)
            previewOutputPathRef.set(null)
            synchronized(previewWakeSignal) { previewWakeSignal.notifyAll() }

            // Stop audio capture & virtual mic first so blocking reads/writes unblock cleanly
            runCatching { speakerCaptureHandleRef.get()?.audioRecord?.stop() }
            runCatching {
                virtualMicHandle?.audioTrack?.let { tr ->
                    tr.pause()
                    tr.flush()
                    tr.stop()
                }
            }
            runCatching { audioCaptureThread?.join(2_000L) }
            runCatching { virtualMicThread?.join(1_500L) }
            runCatching { previewThread?.join(1_000L) }

            runCatching { aacWriter?.finishAndClose() }
            aacWriter = null

            val finalSpeakerHandle = speakerCaptureHandleRef.getAndSet(null)
            runCatching { finalSpeakerHandle?.audioRecord?.release() }
            runCatching { virtualMicHandle?.audioTrack?.release() }
            unregisterAudioPolicySafe(am, finalSpeakerHandle?.audioPolicy)
            unregisterAudioPolicySafe(am, virtualMicHandle?.audioPolicy)
            virtualMicHandle = null

            // Finish H.264 video stream cleanly
            runCatching { encoder.signalEndOfInputStream() }
            runCatching { drainThread.join(4_000L) }

            runCatching { vd.release() }
            virtualDisplay = null

            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            codec = null

            runCatching { surface.release() }
            inputSurface = null

            if (muxerStarted.get() && framesWritten.get() > 0L) {
                runCatching { mp4Muxer.stop() }
            }
            runCatching { mp4Muxer.release() }
            muxer = null

            runCatching { looperThread?.quitSafely() }
            looperThread = null

            val finalVideoSize = if (outVideoFile.exists()) outVideoFile.length() else 0L
            val finalAudioSize = outputAudioPath?.let { File(it) }?.takeIf { it.exists() }?.length() ?: 0L
            emitLine("VIRTUAL_DISPLAY_STOPPED:$finalVideoSize:$finalAudioSize")
            kotlin.system.exitProcess(0)
        } catch (t: Throwable) {
            running.set(false)
            val errSpeakerHandle = speakerCaptureHandleRef.getAndSet(null)
            runCatching { errSpeakerHandle?.audioRecord?.stop() }
            runCatching { errSpeakerHandle?.audioRecord?.release() }
            runCatching { virtualMicHandle?.audioTrack?.stop() }
            runCatching { virtualMicHandle?.audioTrack?.release() }
            unregisterAudioPolicySafe(audioManager, errSpeakerHandle?.audioPolicy)
            unregisterAudioPolicySafe(audioManager, virtualMicHandle?.audioPolicy)
            runCatching { aacWriter?.finishAndClose() }
            runCatching { virtualDisplay?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            if (muxerStarted.get() && framesWritten.get() > 0L) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            runCatching { looperThread?.quitSafely() }
            emitLine("VIRTUAL_DISPLAY_ERROR:${t.message ?: t.javaClass.simpleName}")
            kotlin.system.exitProcess(1)
        }
    }

    /**
     * Registers an AudioPolicy inside `com.android.shell` (uid 2000) to capture the target app's
     * audio silently off the physical speaker (Tier 1: Per-UID LOOP_BACK), falling back to
     * Per-UID shared LOOP_BACK_RENDER before ever falling back to system-wide REMOTE_SUBMIX
     * (so unrelated apps are never silently muted when a specific target UID is known).
     */
    @SuppressLint("PrivateApi", "MissingPermission")
    private fun setupIsolatedAudioCapture(
        shellContext: Context,
        audioManager: AudioManager,
        callbackLooper: Looper,
        targetUid: Int,
        silentSpeaker: Boolean
    ): Pair<ShellAudioCaptureHandle?, String?> {
        val errors = mutableListOf<String>()

        // Tier 1: Per-UID (or system) AudioPolicy with ROUTE_FLAG_LOOP_BACK (silences speaker for matched app!)
        val primaryRouteFlag = if (silentSpeaker) 2 /* ROUTE_FLAG_LOOP_BACK */ else 3 /* ROUTE_FLAG_LOOP_BACK_RENDER */
        val primaryLabel = when {
            targetUid > 0 && silentSpeaker -> "PER_UID_SILENT_LOOPBACK"
            targetUid > 0 -> "PER_UID_SHARED_LOOPBACK"
            silentSpeaker -> "SYSTEM_SILENT_LOOPBACK"
            else -> "SYSTEM_SHARED_LOOPBACK"
        }

        val tier1 = tryRegisterPlaybackAudioPolicy(
            shellContext = shellContext,
            audioManager = audioManager,
            callbackLooper = callbackLooper,
            targetUid = targetUid,
            routeFlags = primaryRouteFlag,
            tierLabel = primaryLabel
        )
        if (tier1.first != null) return tier1
        tier1.second?.let { errors.add("Tier1($primaryLabel): $it") }

        // Tier 2 (Per-UID preferred): Fallback to Per-UID ROUTE_FLAG_LOOP_BACK_RENDER instead of muting all apps
        if (silentSpeaker) {
            val tier2 = tryRegisterPlaybackAudioPolicy(
                shellContext = shellContext,
                audioManager = audioManager,
                callbackLooper = callbackLooper,
                targetUid = targetUid,
                routeFlags = 3, // ROUTE_FLAG_LOOP_BACK_RENDER
                tierLabel = if (targetUid > 0) "PER_UID_SHARED_LOOPBACK" else "SYSTEM_SHARED_LOOPBACK"
            )
            if (tier2.first != null) return tier2
            tier2.second?.let { errors.add("Tier2(SHARED_LOOPBACK): $it") }
        }

        // Tier 3 (Last resort when no specific target UID is isolated): Direct REMOTE_SUBMIX AudioRecord
        if (silentSpeaker && targetUid <= 0) {
            val tier3 = tryCreateRemoteSubmixAudioRecord(shellContext)
            if (tier3.first != null) return tier3
            tier3.second?.let { errors.add("Tier3(REMOTE_SUBMIX): $it") }
        }

        return null to errors.joinToString(" | ").ifBlank { "All isolated audio capture tiers failed" }
    }

    @SuppressLint("PrivateApi")
    private fun tryRegisterPlaybackAudioPolicy(
        shellContext: Context,
        audioManager: AudioManager,
        callbackLooper: Looper,
        targetUid: Int,
        routeFlags: Int,
        tierLabel: String
    ): Pair<ShellAudioCaptureHandle?, String?> {
        var registeredPolicy: Any? = null
        return try {
            val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")

            val ruleMatchUsage = runCatching { ruleCls.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null) }.getOrDefault(1)
            val ruleMatchUid = runCatching { ruleCls.getField("RULE_MATCH_UID").getInt(null) }.getOrDefault(4)

            val ruleBuilder = ruleBuilderCls.getConstructor().newInstance()
            val addRuleMethod = ruleBuilderCls.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
            val addMixRuleMethod = ruleBuilderCls.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)

            // Match VoIP voice communication + signalling + media + game + unknown + assistant
            val usages = intArrayOf(
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.USAGE_ASSISTANT
            )
            for (u in usages) {
                val attrs = AudioAttributes.Builder().setUsage(u).build()
                runCatching { addRuleMethod.invoke(ruleBuilder, attrs, ruleMatchUsage) }
            }

            if (targetUid > 0) {
                addMixRuleMethod.invoke(ruleBuilder, ruleMatchUid, Integer.valueOf(targetUid))
            }

            // In AOSP AudioMix.java, allowPrivilegedPlaybackCapture(true) is ONLY needed for
            // ROUTE_FLAG_LOOP_BACK_RENDER (3) and hard-restricts format to <= 16 kHz mono!
            // Pure ROUTE_FLAG_LOOP_BACK (2) already bypasses capture opt-out at 48 kHz via MODIFY_AUDIO_ROUTING.
            val isLoopbackRender = (routeFlags == 3)
            runCatching {
                val m = ruleBuilderCls.getMethod("voiceCommunicationCaptureAllowed", Boolean::class.javaPrimitiveType)
                m.invoke(ruleBuilder, true)
            }
            if (isLoopbackRender) {
                runCatching {
                    val m = ruleBuilderCls.getMethod("allowPrivilegedPlaybackCapture", Boolean::class.javaPrimitiveType)
                    m.invoke(ruleBuilder, true)
                }
            }

            val mixingRule = ruleBuilderCls.getMethod("build").invoke(ruleBuilder)

            val formatCandidates = if (isLoopbackRender) {
                // canBeUsedForPrivilegedMediaCapture enforces max 16 kHz mono
                listOf(Triple(16_000, AudioFormat.CHANNEL_OUT_MONO, 1))
            } else {
                // Pure LOOP_BACK supports native 48 kHz AudioFlinger mixer rate cleanly
                listOf(
                    Triple(48_000, AudioFormat.CHANNEL_OUT_STEREO, 2),
                    Triple(48_000, AudioFormat.CHANNEL_OUT_MONO, 1),
                    Triple(16_000, AudioFormat.CHANNEL_OUT_MONO, 1)
                )
            }

            var lastErr: Throwable? = null
            for ((rateHz, chMask, chCount) in formatCandidates) {
                try {
                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rateHz)
                        .setChannelMask(chMask)
                        .build()

                    val mixBuilder = mixBuilderCls.getConstructor(ruleCls).newInstance(mixingRule)
                    mixBuilderCls.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, audioFormat)
                    mixBuilderCls.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, routeFlags)
                    val audioMix = mixBuilderCls.getMethod("build").invoke(mixBuilder)
                        ?: throw IllegalStateException("AudioMix.Builder.build() returned null")

                    val policyBuilder = policyBuilderCls.getConstructor(Context::class.java).newInstance(shellContext)
                    policyBuilderCls.getMethod("addMix", mixCls).invoke(policyBuilder, audioMix)
                    runCatching {
                        policyBuilderCls.getMethod("setLooper", Looper::class.java).invoke(policyBuilder, callbackLooper)
                    }
                    val policy = policyBuilderCls.getMethod("build").invoke(policyBuilder)
                        ?: throw IllegalStateException("AudioPolicy.Builder.build() returned null")

                    val regMethod = AudioManager::class.java.getMethod("registerAudioPolicy", policyCls)
                    val regResult = regMethod.invoke(audioManager, policy) as Int
                    if (regResult != 0) {
                        throw IllegalStateException("registerAudioPolicy returned $regResult")
                    }
                    registeredPolicy = policy

                    // Build AudioRecord with explicit shellContext AttributionSource (avoids ActivityThread.systemMain
                    // attributing uid=2000 as package "android", which PermissionChecker rejects!).
                    val record = createShellAttributedAudioRecordSink(
                        shellContext = shellContext,
                        policyCls = policyCls,
                        mixCls = mixCls,
                        policy = policy,
                        audioMix = audioMix,
                        sampleRateHz = rateHz,
                        channelCount = chCount
                    ) ?: throw IllegalStateException("createShellAttributedAudioRecordSink returned null")

                    if (record.state != AudioRecord.STATE_INITIALIZED) {
                        runCatching { record.release() }
                        unregisterAudioPolicySafe(audioManager, policy)
                        registeredPolicy = null
                        continue
                    }

                    return ShellAudioCaptureHandle(
                        audioRecord = record,
                        audioPolicy = policy,
                        sampleRateHz = rateHz,
                        channelCount = chCount,
                        tierLabel = tierLabel
                    ) to null
                } catch (e: Throwable) {
                    lastErr = e.cause ?: e
                    unregisterAudioPolicySafe(audioManager, registeredPolicy)
                    registeredPolicy = null
                }
            }
            null to (lastErr?.message ?: lastErr?.javaClass?.simpleName ?: "AudioPolicy init failed")
        } catch (t: Throwable) {
            unregisterAudioPolicySafe(audioManager, registeredPolicy)
            val root = t.cause ?: t
            null to (root.message ?: root.javaClass.simpleName)
        }
    }

    /**
     * Constructs an [AudioRecord] connected to the registered [AudioPolicy] submix address using
     * `AudioRecord.Builder.setContext(shellContext)` so `AudioPolicyService` receives a valid
     * `AttributionSource(uid=2000, packageName="com.android.shell")` instead of `"android"`.
     */
    @SuppressLint("PrivateApi", "MissingPermission")
    private fun createShellAttributedAudioRecordSink(
        shellContext: Context,
        policyCls: Class<*>,
        mixCls: Class<*>,
        policy: Any,
        audioMix: Any,
        sampleRateHz: Int,
        channelCount: Int
    ): AudioRecord? {
        val directBuilt = runCatching {
            val regAddr = mixCls.getMethod("getRegistration").invoke(audioMix) as? String
            if (regAddr.isNullOrBlank()) return@runCatching null

            val inChMask = if (channelCount >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val recFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRateHz)
                .setChannelMask(inChMask)
                .build()

            val ab = AudioAttributes.Builder()
            AudioAttributes.Builder::class.java
                .getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
                .invoke(ab, MediaRecorder.AudioSource.REMOTE_SUBMIX)
            val addTag = AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
            addTag.invoke(ab, "addr=$regAddr")
            addTag.invoke(ab, SUBMIX_FIXED_VOLUME_TAG)
            val attrs = ab.build()

            val minBuf = AudioRecord.getMinBufferSize(
                sampleRateHz,
                inChMask,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRateHz / 25 * maxOf(1, channelCount) * 2)

            val builder = AudioRecord.Builder()
                .setContext(shellContext)
                .setAudioFormat(recFormat)
                .setBufferSizeInBytes(minBuf)
            AudioRecord.Builder::class.java
                .getMethod("setAudioAttributes", AudioAttributes::class.java)
                .invoke(builder, attrs)
            builder.build()
        }.getOrNull()

        if (directBuilt != null && directBuilt.state == AudioRecord.STATE_INITIALIZED) {
            return directBuilt
        }
        runCatching { directBuilt?.release() }

        // Fallback to framework AudioPolicy.createAudioRecordSink
        return policyCls.getMethod("createAudioRecordSink", mixCls).invoke(policy, audioMix) as? AudioRecord
    }

    @SuppressLint("PrivateApi", "MissingPermission")
    private fun tryCreateRemoteSubmixAudioRecord(shellContext: Context): Pair<ShellAudioCaptureHandle?, String?> {
        val candidates = listOf(
            Triple(48_000, AudioFormat.CHANNEL_IN_STEREO, 2),
            Triple(48_000, AudioFormat.CHANNEL_IN_MONO, 1),
            Triple(16_000, AudioFormat.CHANNEL_IN_MONO, 1)
        )
        var lastErr: Throwable? = null
        for ((rateHz, chMask, chCount) in candidates) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(rateHz, chMask, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) continue
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rateHz)
                    .setChannelMask(chMask)
                    .build()
                val rec = AudioRecord.Builder()
                    .setContext(shellContext)
                    .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuf * 2, rateHz / 25 * chCount * 2))
                    .build()

                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    return ShellAudioCaptureHandle(
                        audioRecord = rec,
                        audioPolicy = null,
                        sampleRateHz = rateHz,
                        channelCount = chCount,
                        tierLabel = "SYSTEM_REMOTE_SUBMIX_ALL_APPS"
                    ) to null
                } else {
                    runCatching { rec.release() }
                }
            } catch (t: Throwable) {
                lastErr = t.cause ?: t
            }
        }
        return null to (lastErr?.message ?: lastErr?.javaClass?.simpleName ?: "REMOTE_SUBMIX init failed")
    }

    /**
     * Registers a separate Per-UID `MIX_TYPE_RECORDERS` AudioPolicy feeding clean digital silence
     * strictly to the target app's UID so Zoom/Webex stays connected without opening the physical mic.
     */
    @SuppressLint("PrivateApi")
    private fun setupVirtualSilentMicrophone(
        shellContext: Context,
        audioManager: AudioManager,
        callbackLooper: Looper,
        targetUid: Int
    ): Pair<ShellVirtualMicHandle?, String?> {
        var registeredPolicy: Any? = null
        return try {
            val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")

            val ruleMatchCapturePreset = runCatching {
                ruleCls.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null)
            }.getOrDefault(2)
            val ruleMatchUid = runCatching {
                ruleCls.getField("RULE_MATCH_UID").getInt(null)
            }.getOrDefault(4)

            val setCapturePresetMethod = AudioAttributes.Builder::class.java.getMethod(
                "setCapturePreset",
                Int::class.javaPrimitiveType
            )
            val ruleBuilder = ruleBuilderCls.getConstructor().newInstance()
            val addRuleMethod = ruleBuilderCls.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
            val addMixRuleMethod = ruleBuilderCls.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)

            val presets = intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.DEFAULT,
                MediaRecorder.AudioSource.UNPROCESSED
            )
            for (preset in presets) {
                val ab = AudioAttributes.Builder()
                setCapturePresetMethod.invoke(ab, preset)
                addRuleMethod.invoke(ruleBuilder, ab.build(), ruleMatchCapturePreset)
            }
            addMixRuleMethod.invoke(ruleBuilder, ruleMatchUid, Integer.valueOf(targetUid))

            val mixingRule = ruleBuilderCls.getMethod("build").invoke(ruleBuilder)
            // Per AOSP AudioMix.java (b/194910301), AudioMix expects output channel masks first
            val formatCandidates = listOf(
                16_000 to AudioFormat.CHANNEL_OUT_MONO,
                48_000 to AudioFormat.CHANNEL_OUT_MONO,
                16_000 to AudioFormat.CHANNEL_IN_MONO,
                48_000 to AudioFormat.CHANNEL_IN_MONO
            )
            var lastErr: Throwable? = null

            for ((rateHz, chMask) in formatCandidates) {
                try {
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rateHz)
                        .setChannelMask(chMask)
                        .build()

                    val mixBuilder = mixBuilderCls.getConstructor(ruleCls).newInstance(mixingRule)
                    mixBuilderCls.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
                    mixBuilderCls.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, 2) // ROUTE_FLAG_LOOP_BACK
                    val audioMix = mixBuilderCls.getMethod("build").invoke(mixBuilder)
                        ?: throw IllegalStateException("AudioMix.Builder.build(virtualMic) returned null")

                    val policyBuilder = policyBuilderCls.getConstructor(Context::class.java).newInstance(shellContext)
                    policyBuilderCls.getMethod("addMix", mixCls).invoke(policyBuilder, audioMix)
                    runCatching {
                        policyBuilderCls.getMethod("setLooper", Looper::class.java).invoke(policyBuilder, callbackLooper)
                    }
                    val policy = policyBuilderCls.getMethod("build").invoke(policyBuilder)
                        ?: throw IllegalStateException("AudioPolicy.Builder.build(virtualMic) returned null")

                    val regResult = AudioManager::class.java
                        .getMethod("registerAudioPolicy", policyCls)
                        .invoke(audioManager, policy) as Int
                    if (regResult != 0) {
                        throw IllegalStateException("registerAudioPolicy(virtualMic) returned $regResult")
                    }
                    registeredPolicy = policy

                    val track = createShellAttributedAudioTrackSource(
                        shellContext = shellContext,
                        policyCls = policyCls,
                        mixCls = mixCls,
                        policy = policy,
                        audioMix = audioMix
                    ) ?: throw IllegalStateException("createShellAttributedAudioTrackSource returned null")

                    if (track.state != AudioTrack.STATE_INITIALIZED) {
                        runCatching { track.release() }
                        unregisterAudioPolicySafe(audioManager, policy)
                        registeredPolicy = null
                        continue
                    }

                    return ShellVirtualMicHandle(
                        audioTrack = track,
                        audioPolicy = policy,
                        sampleRateHz = rateHz,
                        tierLabel = "VIRTUAL_SILENT_MIC_ACTIVE"
                    ) to null
                } catch (e: Throwable) {
                    lastErr = e.cause ?: e
                    unregisterAudioPolicySafe(audioManager, registeredPolicy)
                    registeredPolicy = null
                }
            }
            null to (lastErr?.message ?: lastErr?.javaClass?.simpleName ?: "Virtual mic init failed")
        } catch (t: Throwable) {
            unregisterAudioPolicySafe(audioManager, registeredPolicy)
            val root = t.cause ?: t
            null to (root.message ?: root.javaClass.simpleName)
        }
    }

    /**
     * Constructs an [AudioTrack] feeding the registered `MIX_TYPE_RECORDERS` submix address using
     * `AudioTrack.Builder.setContext(shellContext)` and `PERFORMANCE_MODE_LOW_LATENCY` so AOSP's
     * `shouldEnablePowerSaving()` does not inject `FLAG_DEEP_BUFFER` (which breaks REMOTE_SUBMIX
     * output profile matching for `USAGE_VIRTUAL_SOURCE`).
     */
    @SuppressLint("PrivateApi")
    private fun createShellAttributedAudioTrackSource(
        shellContext: Context,
        policyCls: Class<*>,
        mixCls: Class<*>,
        policy: Any,
        audioMix: Any
    ): AudioTrack? {
        val directBuilt = runCatching {
            val regAddr = mixCls.getMethod("getRegistration").invoke(audioMix) as? String
            val mixFormat = mixCls.getMethod("getFormat").invoke(audioMix) as? AudioFormat
            if (regAddr.isNullOrBlank() || mixFormat == null) return@runCatching null

            val ab = AudioAttributes.Builder()
                .setUsage(USAGE_VIRTUAL_SOURCE)
            AudioAttributes.Builder::class.java
                .getMethod("addTag", String::class.java)
                .invoke(ab, "addr=$regAddr")
            val attrs = ab.build()

            val outMask = if (mixFormat.channelMask == AudioFormat.CHANNEL_IN_MONO) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                mixFormat.channelMask
            }
            val safeFormat = AudioFormat.Builder()
                .setEncoding(mixFormat.encoding)
                .setSampleRate(mixFormat.sampleRate)
                .setChannelMask(outMask)
                .build()

            val minBuf = AudioTrack.getMinBufferSize(
                safeFormat.sampleRate,
                outMask,
                safeFormat.encoding
            ).coerceAtLeast(safeFormat.sampleRate / 25 * 2)

            // Primary: LOW_LATENCY prevents AudioTrack.Builder from injecting FLAG_DEEP_BUFFER
            val lowLatencyTrack = runCatching {
                AudioTrack.Builder()
                    .setContext(shellContext)
                    .setAudioAttributes(attrs)
                    .setAudioFormat(safeFormat)
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            }.getOrNull()

            if (lowLatencyTrack != null && lowLatencyTrack.state == AudioTrack.STATE_INITIALIZED) {
                return@runCatching lowLatencyTrack
            }
            runCatching { lowLatencyTrack?.release() }

            // Secondary: standard Builder without power-saving offload
            AudioTrack.Builder()
                .setContext(shellContext)
                .setAudioAttributes(attrs)
                .setAudioFormat(safeFormat)
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()
        }.getOrNull()

        if (directBuilt != null && directBuilt.state == AudioTrack.STATE_INITIALIZED) {
            return directBuilt
        }
        runCatching { directBuilt?.release() }

        return policyCls.getMethod("createAudioTrackSource", mixCls).invoke(policy, audioMix) as? AudioTrack
    }

    private fun runVirtualMicSilenceLoop(handle: ShellVirtualMicHandle, running: AtomicBoolean) {
        val frameShorts = (handle.sampleRateHz / 50).coerceAtLeast(320) // 20 ms
        val silence = ShortArray(frameShorts)
        while (running.get()) {
            val written = try {
                handle.audioTrack.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
            } catch (_: Throwable) {
                break
            }
            if (written <= 0) {
                if (!running.get()) break
                runCatching { Thread.sleep(20L) }
            }
        }
    }

    private fun runIsolatedAudioLoop(
        shellContext: Context,
        audioManager: AudioManager,
        callbackLooper: Looper,
        targetUid: Int,
        wantSilentSpeaker: Boolean,
        handleRef: AtomicReference<ShellAudioCaptureHandle?>,
        rebindRequested: AtomicBoolean,
        writer: ShellAdtsAacWriter,
        running: AtomicBoolean,
        paused: AtomicBoolean,
        accumulatedPausedMs: AtomicLong,
        mediaTimelineAnchorMonoMs: AtomicLong,
        pausedAtAnchorMs: AtomicLong,
        sessionStartMonoMs: Long
    ) {
        var activeHandle = handleRef.get() ?: return
        var decimationFactor = (activeHandle.sampleRateHz / TARGET_AUDIO_RATE).coerceAtLeast(1)
        var chCount = activeHandle.channelCount.coerceIn(1, 2)
        var unitShorts = decimationFactor * chCount
        var readShortsCount = FRAME_SAMPLES_16K * unitShorts
        var rawBuf = ShortArray(readShortsCount + unitShorts)
        val mono16k = ShortArray(FRAME_SAMPLES_16K)
        val silence16k = ShortArray(FRAME_SAMPLES_16K)

        var rawCarryCount = 0
        var totalWrittenSamples16k = 0L
        var everDetectedSignal = false
        var lastLevelEmitMonoMs = 0L
        var lastAudioDataMonoMs = sessionStartMonoMs
        var useBlockingFallback = false
        var consecutiveReadErrors = 0
        var lastAutoRebindMonoMs = 0L

        fun tryRebindAudioCapture(manual: Boolean): Boolean {
            val nowMs = SystemClock.elapsedRealtime()
            if (!manual && nowMs - lastAutoRebindMonoMs < 4_000L) {
                return false
            }
            lastAutoRebindMonoMs = nowMs
            val old = handleRef.getAndSet(null)
            runCatching { old?.audioRecord?.stop() }
            runCatching { old?.audioRecord?.release() }
            unregisterAudioPolicySafe(audioManager, old?.audioPolicy)
            runCatching { Thread.sleep(120L) }

            val newRes = setupIsolatedAudioCapture(
                shellContext = shellContext,
                audioManager = audioManager,
                callbackLooper = callbackLooper,
                targetUid = targetUid,
                silentSpeaker = wantSilentSpeaker
            )
            val fresh = newRes.first
            return if (fresh != null) {
                handleRef.set(fresh)
                activeHandle = fresh
                decimationFactor = (fresh.sampleRateHz / TARGET_AUDIO_RATE).coerceAtLeast(1)
                chCount = fresh.channelCount.coerceIn(1, 2)
                unitShorts = decimationFactor * chCount
                readShortsCount = FRAME_SAMPLES_16K * unitShorts
                rawBuf = ShortArray(readShortsCount + unitShorts)
                rawCarryCount = 0
                consecutiveReadErrors = 0
                useBlockingFallback = false
                runCatching { fresh.audioRecord.startRecording() }
                emitLine("AUDIO_CAPTURE_TIER:${fresh.tierLabel}")
                emitLine("AUDIO_SESSION_ID:${fresh.audioRecord.audioSessionId}")
                emitLine("AUDIO_REBOUND:OK:${fresh.tierLabel}")
                true
            } else {
                val reason = newRes.second ?: "AudioPolicy re-registration failed"
                emitLine("AUDIO_REBOUND:FAILED:$reason")
                false
            }
        }

        fun resolveAnchorOrNull(nowMonoMs: Long): Long? {
            var anchorMono = mediaTimelineAnchorMonoMs.get()
            if (anchorMono == 0L) {
                if (nowMonoMs - sessionStartMonoMs < AUDIO_ANCHOR_FALLBACK_WAIT_MS) {
                    return null
                }
                if (mediaTimelineAnchorMonoMs.compareAndSet(0L, nowMonoMs)) {
                    pausedAtAnchorMs.set(accumulatedPausedMs.get())
                }
                anchorMono = mediaTimelineAnchorMonoMs.get()
            }
            return anchorMono
        }

        fun padSilenceToWallClock(
            nowMonoMs: Long,
            anchorMono: Long,
            upcomingSamples: Int,
            maxPadSamples: Long,
            idleCushionSamples: Long = 0L
        ) {
            val pausedSinceAnchorMs = (accumulatedPausedMs.get() - pausedAtAnchorMs.get()).coerceAtLeast(0L)
            val activeElapsedMs = (nowMonoMs - anchorMono - pausedSinceAnchorMs).coerceAtLeast(0L)
            val expectedSamples = ((activeElapsedMs * TARGET_AUDIO_RATE) / 1000L - idleCushionSamples).coerceAtLeast(0L)
            val missingSamples = expectedSamples - totalWrittenSamples16k - upcomingSamples
            // Bridge genuine stalls > 200ms during active audio; pad smoothly in 20ms frames during prolonged idle
            val threshold = if (upcomingSamples == 0) FRAME_SAMPLES_16K.toLong() else 3_200L
            if (missingSamples >= threshold) {
                var padRemaining = minOf(missingSamples, maxPadSamples)
                while (padRemaining >= FRAME_SAMPLES_16K && running.get() && !paused.get()) {
                    writer.writePcmShorts(silence16k, 0, FRAME_SAMPLES_16K)
                    totalWrittenSamples16k += FRAME_SAMPLES_16K
                    padRemaining -= FRAME_SAMPLES_16K
                }
            }
        }

        while (running.get()) {
            if (rebindRequested.compareAndSet(true, false)) {
                tryRebindAudioCapture(manual = true)
                continue
            }

            if (handleRef.get() == null) {
                // Previous rebind failed; keep A/V timeline alive with silence and retry every 4s
                val nowMonoMs = SystemClock.elapsedRealtime()
                if (!paused.get() && nowMonoMs - lastAudioDataMonoMs >= 160L) {
                    val anchorMono = resolveAnchorOrNull(nowMonoMs)
                    if (anchorMono != null) {
                        padSilenceToWallClock(
                            nowMonoMs = nowMonoMs,
                            anchorMono = anchorMono,
                            upcomingSamples = 0,
                            maxPadSamples = MAX_CATCHUP_PAD_SAMPLES_PER_READ.toLong(),
                            idleCushionSamples = 1_280L
                        )
                    }
                }
                if (!tryRebindAudioCapture(manual = false)) {
                    runCatching { Thread.sleep(100L) }
                }
                continue
            }

            val maxToRead = (readShortsCount - rawCarryCount).coerceAtLeast(unitShorts)
            val readMode = if (useBlockingFallback) AudioRecord.READ_BLOCKING else AudioRecord.READ_NON_BLOCKING
            val read = try {
                activeHandle.audioRecord.read(rawBuf, rawCarryCount, maxToRead, readMode)
            } catch (_: Throwable) {
                AudioRecord.ERROR_DEAD_OBJECT
            }

            if (read < 0) {
                if (!useBlockingFallback && read == AudioRecord.ERROR_BAD_VALUE) {
                    useBlockingFallback = true
                    continue
                }
                if (!running.get()) break
                consecutiveReadErrors++
                if (read == AudioRecord.ERROR_DEAD_OBJECT || consecutiveReadErrors >= 15) {
                    tryRebindAudioCapture(manual = false)
                }
                runCatching { Thread.sleep(20L) }
                continue
            }
            consecutiveReadErrors = 0

            val nowMonoMs = SystemClock.elapsedRealtime()
            if (paused.get()) {
                rawCarryCount = 0
                lastAudioDataMonoMs = nowMonoMs
                if (nowMonoMs - lastLevelEmitMonoMs >= 200L) {
                    lastLevelEmitMonoMs = nowMonoMs
                    emitLine("AUDIO_LEVEL:0:${if (everDetectedSignal) 1 else 0}")
                }
                if (read == 0) runCatching { Thread.sleep(20L) }
                continue
            }

            if (read == 0) {
                // Target app is silent (e.g., Zoom/Webex Waiting Room or muted) — wait 160ms of true
                // silence before padding behind an 80ms jitter cushion so normal AudioFlinger burst
                // jitter never injects clicks into live speech, while prolonged silence stays A/V locked.
                if (nowMonoMs - lastLevelEmitMonoMs >= 200L) {
                    lastLevelEmitMonoMs = nowMonoMs
                    emitLine("AUDIO_LEVEL:0:${if (everDetectedSignal) 1 else 0}")
                }
                if (nowMonoMs - lastAudioDataMonoMs >= 160L) {
                    val anchorMono = resolveAnchorOrNull(nowMonoMs)
                    if (anchorMono != null) {
                        padSilenceToWallClock(
                            nowMonoMs = nowMonoMs,
                            anchorMono = anchorMono,
                            upcomingSamples = 0,
                            maxPadSamples = MAX_CATCHUP_PAD_SAMPLES_PER_READ.toLong(),
                            idleCushionSamples = 1_280L
                        )
                    }
                }
                runCatching { Thread.sleep(10L) }
                continue
            }

            lastAudioDataMonoMs = nowMonoMs
            val totalAvail = rawCarryCount + read
            val usableShorts = (totalAvail / unitShorts) * unitShorts
            if (usableShorts <= 0) {
                rawCarryCount = totalAvail
                continue
            }

            // Downmix & decimate complete sample groups to 16 kHz mono PCM
            val outCount = convertToMono16k(rawBuf, usableShorts, chCount, decimationFactor, mono16k)
            val leftover = totalAvail - usableShorts
            if (leftover > 0) {
                System.arraycopy(rawBuf, usableShorts, rawBuf, 0, leftover)
            }
            rawCarryCount = leftover
            if (outCount <= 0) continue

            val rms = computeRms(mono16k, outCount)
            if (rms >= 45f) everDetectedSignal = true

            // Keep VU meter live even while awaiting shared t=0 anchor from first video frame
            if (nowMonoMs - lastLevelEmitMonoMs >= 180L) {
                lastLevelEmitMonoMs = nowMonoMs
                val normPct = (rmsToVisualNorm(rms) * 100f).toInt().coerceIn(0, 100)
                emitLine("AUDIO_LEVEL:$normPct:${if (everDetectedSignal) 1 else 0}")
            }

            // Shared t=0 anchor: wait until first video frame latches (or fallback after 4s) so
            // both video and audio tracks start cleanly at PTS = 0 for universal player AV sync.
            val anchorMono = resolveAnchorOrNull(nowMonoMs) ?: continue

            padSilenceToWallClock(
                nowMonoMs = nowMonoMs,
                anchorMono = anchorMono,
                upcomingSamples = outCount,
                maxPadSamples = MAX_CATCHUP_PAD_SAMPLES_PER_READ.toLong()
            )

            writer.writePcmShorts(mono16k, 0, outCount)
            totalWrittenSamples16k += outCount
        }
    }

    private fun convertToMono16k(
        src: ShortArray,
        sampleCount: Int,
        channels: Int,
        decimationFactor: Int,
        out: ShortArray
    ): Int {
        val framesTotal = sampleCount / channels
        val outFrames = minOf(out.size, framesTotal / decimationFactor)
        if (outFrames <= 0) return 0

        if (channels == 1 && decimationFactor == 1) {
            System.arraycopy(src, 0, out, 0, outFrames)
            return outFrames
        }

        if (decimationFactor == 3) {
            // 48 kHz -> 16 kHz [1, 2, 1] / 4 low-pass decimation with stereo-to-mono downmix
            for (i in 0 until outFrames) {
                val f0 = i * 3
                val s0 = readMonoFrame(src, f0, channels)
                val s1 = readMonoFrame(src, f0 + 1, channels)
                val s2 = readMonoFrame(src, f0 + 2, channels)
                val blended = (s0 + (s1 shl 1) + s2) shr 2
                out[i] = blended.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        } else {
            for (i in 0 until outFrames) {
                var acc = 0
                val base = i * decimationFactor
                for (k in 0 until decimationFactor) {
                    acc += readMonoFrame(src, base + k, channels)
                }
                out[i] = (acc / decimationFactor).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return outFrames
    }

    private fun readMonoFrame(src: ShortArray, frameIndex: Int, channels: Int): Int {
        return if (channels == 2) {
            val idx = frameIndex * 2
            (src[idx].toInt() + src[idx + 1].toInt()) shr 1
        } else {
            src[frameIndex].toInt()
        }
    }

    private fun unregisterAudioPolicySafe(am: AudioManager?, policy: Any?) {
        if (am == null || policy == null) return
        runCatching {
            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val unreg = runCatching { AudioManager::class.java.getMethod("unregisterAudioPolicy", policyCls) }.getOrNull()
                ?: AudioManager::class.java.getMethod("unregisterAudioPolicyAsync", policyCls)
            unreg.invoke(am, policy)
        }
    }

    @SuppressLint("PrivateApi")
    private fun buildShellAttributedContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = runCatching {
            activityThreadClass.getDeclaredMethod("currentActivityThread").apply {
                isAccessible = true
            }.invoke(null)
        }.getOrNull()
            ?: runCatching {
                activityThreadClass.getDeclaredMethod("systemMain").apply {
                    isAccessible = true
                }.invoke(null)
            }.getOrNull()
            ?: runCatching {
                val ctor = activityThreadClass.getDeclaredConstructor().apply { isAccessible = true }
                ctor.newInstance()
            }.getOrNull()
            ?: throw IllegalStateException("Unable to bootstrap ActivityThread in shell process")

        val systemContext = runCatching {
            activityThreadClass.getDeclaredMethod("getSystemContext").apply {
                isAccessible = true
            }.invoke(activityThread) as? Context
        }.getOrNull()
            ?: runCatching {
                activityThreadClass.getDeclaredMethod("getSystemUiContext").apply {
                    isAccessible = true
                }.invoke(activityThread) as? Context
            }.getOrNull()
            ?: throw IllegalStateException("ActivityThread returned null system context")

        val shellPkgCtx = runCatching {
            systemContext.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: runCatching {
            systemContext.createPackageContext("com.android.shell", 0)
        }.getOrNull()

        if (shellPkgCtx != null &&
            shellPkgCtx.opPackageName == "com.android.shell" &&
            shellPkgCtx.attributionSource.packageName == "com.android.shell"
        ) {
            return shellPkgCtx
        }

        val shellAttrSource = AttributionSource.Builder(Process.myUid())
            .setPackageName("com.android.shell")
            .build()

        return object : ContextWrapper(shellPkgCtx ?: systemContext) {
            override fun getPackageName(): String = "com.android.shell"
            override fun getOpPackageName(): String = "com.android.shell"
            override fun getAttributionSource(): AttributionSource = shellAttrSource
        }
    }

    private fun createVirtualDisplayWithFallback(
        dm: DisplayManager,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        handler: Handler
    ): VirtualDisplayCreateResult {
        // Deliberately omit FLAG_DESTROY_CONTENT_ON_REMOVAL so live Zoom/Webex activities migrate
        // safely back to Display 0 instead of being destroyed if the virtual display exits unexpectedly.
        val flagTiers = listOf(
            "FULL_TRUSTED_ALWAYS_UNLOCKED" to (
                FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                    FLAG_ROTATES_WITH_CONTENT or
                    FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS or FLAG_TRUSTED or
                    FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
                ),
            "PUBLIC_TOUCH_ROTATE" to (
                FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                    FLAG_ROTATES_WITH_CONTENT
                ),
            "PUBLIC_BASIC" to (FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY)
        )

        var lastErr: Throwable? = null
        for ((tierName, flags) in flagTiers) {
            try {
                val vd = dm.createVirtualDisplay(
                    "AmbientBrain-MeetingVDisp",
                    width,
                    height,
                    dpi,
                    surface,
                    flags,
                    null,
                    handler
                )
                if (vd != null) return VirtualDisplayCreateResult(vd, tierName)
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        throw IllegalStateException(
            "Unable to create ADB virtual display: ${lastErr?.message ?: "denied by OEM DisplayManager"}"
        )
    }

    private fun requestEncoderSyncFrame(encoder: MediaCodec) {
        runCatching {
            encoder.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
        }
    }

    private fun drainEncodedVideoLoop(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        running: AtomicBoolean,
        paused: AtomicBoolean,
        awaitingSyncFrameAfterResume: AtomicBoolean,
        muxerStarted: AtomicBoolean,
        framesWritten: AtomicLong,
        mediaTimelineAnchorMonoMs: AtomicLong,
        accumulatedPausedMs: AtomicLong,
        pausedAtAnchorMs: AtomicLong,
        previewOutputPathRef: AtomicReference<String?>,
        latestVideoFormatRef: AtomicReference<MediaFormat?>,
        latestKeyframeRef: AtomicReference<KeyframeSnapshot?>,
        keyframeSeqCounter: AtomicLong,
        previewWakeSignal: Object
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var videoTrackIndex = -1
        var firstInputPtsUs = -1L
        var videoInitialOffsetUs = 0L
        var lastWrittenPtsUs = -1L
        var eosWaitTicks = 0

        while (true) {
            val outIndex = try {
                encoder.dequeueOutputBuffer(bufferInfo, 25_000L)
            } catch (_: Throwable) {
                break
            }

            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!running.get() && ++eosWaitTicks > 60) {
                    break
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val outFmt = encoder.outputFormat
                latestVideoFormatRef.set(outFmt)
                if (!muxerStarted.get()) {
                    videoTrackIndex = muxer.addTrack(outFmt)
                    muxer.start()
                    muxerStarted.set(true)
                }
            } else if (outIndex >= 0) {
                val encodedData = encoder.getOutputBuffer(outIndex)
                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                if (!isConfig && bufferInfo.size > 0 && encodedData != null) {
                    // Snapshot IDR keyframe bytes only when live mini-preview is actively expanded
                    if (isKeyFrame && previewOutputPathRef.get() != null) {
                        runCatching {
                            val dup = encodedData.duplicate()
                            dup.position(bufferInfo.offset)
                            dup.limit(bufferInfo.offset + bufferInfo.size)
                            val copy = ByteArray(bufferInfo.size)
                            dup.get(copy)
                            latestKeyframeRef.set(KeyframeSnapshot(keyframeSeqCounter.incrementAndGet(), copy))
                            synchronized(previewWakeSignal) { previewWakeSignal.notifyAll() }
                        }
                    }

                    if (muxerStarted.get() && videoTrackIndex >= 0) {
                        val rawPtsUs = bufferInfo.presentationTimeUs
                        if (firstInputPtsUs < 0L) {
                            val nowMonoMs = SystemClock.elapsedRealtime()
                            firstInputPtsUs = rawPtsUs
                            // Anchor shared t=0 timeline on first video frame; if audio already latched after
                            // the 4s fallback (e.g. user moved app to Virtual Display later), offset video PTS
                            // so audio and video remain synchronized.
                            if (mediaTimelineAnchorMonoMs.compareAndSet(0L, nowMonoMs)) {
                                pausedAtAnchorMs.set(accumulatedPausedMs.get())
                                videoInitialOffsetUs = 0L
                            } else {
                                val anchorMono = mediaTimelineAnchorMonoMs.get()
                                val pausedSinceAnchorMs = (accumulatedPausedMs.get() - pausedAtAnchorMs.get()).coerceAtLeast(0L)
                                videoInitialOffsetUs = ((nowMonoMs - anchorMono - pausedSinceAnchorMs).coerceAtLeast(0L)) * 1_000L
                            }
                        }

                        if (paused.get()) {
                            // Drop frames during active pause; shared accumulatedPausedMs tracks exact pause duration
                        } else if (awaitingSyncFrameAfterResume.get() && !isKeyFrame) {
                            // Drop inter-predicted P-frames after Resume until a clean IDR keyframe arrives
                        } else {
                            if (awaitingSyncFrameAfterResume.get() && isKeyFrame) {
                                awaitingSyncFrameAfterResume.set(false)
                            }
                            // Use the exact same shared accumulatedPausedMs clock as audio so waiting for
                            // an IDR keyframe after Resume never introduces cumulative A/V drift.
                            val sharedPausedUs = ((accumulatedPausedMs.get() - pausedAtAnchorMs.get()).coerceAtLeast(0L)) * 1_000L
                            var adjustedPtsUs = (videoInitialOffsetUs + (rawPtsUs - firstInputPtsUs) - sharedPausedUs).coerceAtLeast(0L)
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
                                muxer.writeSampleData(videoTrackIndex, encodedData, writeInfo)
                                framesWritten.incrementAndGet()
                            }
                        }
                    }
                }

                runCatching { encoder.releaseOutputBuffer(outIndex, false) }
                if (isEos) break
            }
        }
    }

    /**
     * Decodes captured H.264 IDR keyframes to a 2x-downscaled JPEG thumbnail whenever the
     * floating Mini-Preview overlay is expanded (`PREVIEW_ON <path>`). Releases the decoder
     * immediately on `PREVIEW_OFF` for 0% background overhead.
     */
    private fun runPreviewDecodeLoop(
        encoder: MediaCodec,
        running: AtomicBoolean,
        previewOutputPathRef: AtomicReference<String?>,
        latestVideoFormatRef: AtomicReference<MediaFormat?>,
        latestKeyframeRef: AtomicReference<KeyframeSnapshot?>,
        previewWakeSignal: Object
    ) {
        var decoder: MediaCodec? = null
        var lastDecodedSeq = -1L
        var lastSyncReqMonoMs = 0L
        var previewFrameCounter = 0L
        var lastPreviewDir: File? = null
        var lastPreviewBaseName: String? = null

        fun releaseDecoder() {
            val dec = decoder ?: return
            decoder = null
            runCatching { dec.stop() }
            runCatching { dec.release() }
        }

        fun cleanupPreviewTmpFiles() {
            val dir = lastPreviewDir ?: return
            val prefix = lastPreviewBaseName ?: return
            runCatching {
                dir.listFiles { f -> f.name.startsWith("$prefix.") && f.name.endsWith(".tmp") }
                    ?.forEach { runCatching { it.delete() } }
            }
        }

        try {
            while (running.get()) {
                val outPath = previewOutputPathRef.get()
                if (outPath.isNullOrBlank()) {
                    releaseDecoder()
                    synchronized(previewWakeSignal) {
                        if (running.get() && previewOutputPathRef.get() == null) {
                            runCatching { previewWakeSignal.wait(1_000L) }
                        }
                    }
                    continue
                }

                val nowMono = SystemClock.elapsedRealtime()
                if (nowMono - lastSyncReqMonoMs >= 900L) {
                    lastSyncReqMonoMs = nowMono
                    requestEncoderSyncFrame(encoder)
                }

                val snap = latestKeyframeRef.get()
                val srcFmt = latestVideoFormatRef.get()
                if (snap == null || srcFmt == null || snap.seq == lastDecodedSeq) {
                    synchronized(previewWakeSignal) {
                        if (running.get() && previewOutputPathRef.get() != null) {
                            runCatching { previewWakeSignal.wait(250L) }
                        }
                    }
                    continue
                }

                if (decoder == null) {
                    decoder = configurePreviewDecoderWithFallback(srcFmt)?.first
                }

                val activeDec = decoder
                if (activeDec == null) {
                    synchronized(previewWakeSignal) { runCatching { previewWakeSignal.wait(500L) } }
                    continue
                }

                val outFile = File(outPath)
                lastPreviewDir = outFile.parentFile
                lastPreviewBaseName = outFile.name
                val ptsUs = (++previewFrameCounter) * 33_333L
                val ok = decodeKeyframeToJpeg(activeDec, srcFmt, snap.bytes, ptsUs, outFile)
                if (ok) {
                    lastDecodedSeq = snap.seq
                    emitLine("PREVIEW_FRAME:${System.currentTimeMillis()}")
                } else {
                    releaseDecoder()
                }

                synchronized(previewWakeSignal) {
                    if (running.get() && previewOutputPathRef.get() != null) {
                        runCatching { previewWakeSignal.wait(220L) }
                    }
                }
            }
        } finally {
            releaseDecoder()
            cleanupPreviewTmpFiles()
        }
    }

    private fun configurePreviewDecoderWithFallback(srcFmt: MediaFormat): Pair<MediaCodec, Boolean>? {
        val width = srcFmt.getInteger(MediaFormat.KEY_WIDTH)
        val height = srcFmt.getInteger(MediaFormat.KEY_HEIGHT)
        for (tryLowLatency in listOf(true, false)) {
            var dec: MediaCodec? = null
            try {
                val decFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    if (tryLowLatency) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    srcFmt.getByteBuffer("csd-0")?.let { csd0 ->
                        setByteBuffer("csd-0", cloneByteBuffer(csd0))
                    }
                    srcFmt.getByteBuffer("csd-1")?.let { csd1 ->
                        setByteBuffer("csd-1", cloneByteBuffer(csd1))
                    }
                }
                dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                dec.configure(decFmt, null, null, 0)
                dec.start()
                return dec to tryLowLatency
            } catch (_: Throwable) {
                runCatching { dec?.release() }
            }
        }
        return null
    }

    private fun cloneByteBuffer(src: ByteBuffer): ByteBuffer {
        val dup = src.duplicate()
        dup.rewind()
        val arr = ByteArray(dup.remaining())
        dup.get(arr)
        return ByteBuffer.wrap(arr)
    }

    private fun prependCodecConfigIfNeeded(srcFmt: MediaFormat, idrBytes: ByteArray): ByteArray {
        // Check if IDR frame already starts with SPS NAL unit (type 7)
        val hasLeadingSps = (idrBytes.size >= 5 &&
            idrBytes[0] == 0.toByte() && idrBytes[1] == 0.toByte() &&
            idrBytes[2] == 0.toByte() && idrBytes[3] == 1.toByte() &&
            (idrBytes[4].toInt() and 0x1F) == 7) ||
            (idrBytes.size >= 4 &&
                idrBytes[0] == 0.toByte() && idrBytes[1] == 0.toByte() &&
                idrBytes[2] == 1.toByte() && (idrBytes[3].toInt() and 0x1F) == 7)
        if (hasLeadingSps) return idrBytes

        val csd0 = srcFmt.getByteBuffer("csd-0")?.let { buf ->
            val dup = buf.duplicate()
            dup.rewind()
            ByteArray(dup.remaining()).also { dup.get(it) }
        }
        val csd1 = srcFmt.getByteBuffer("csd-1")?.let { buf ->
            val dup = buf.duplicate()
            dup.rewind()
            ByteArray(dup.remaining()).also { dup.get(it) }
        }
        val extraLen = (csd0?.size ?: 0) + (csd1?.size ?: 0)
        if (extraLen <= 0) return idrBytes

        val combined = ByteArray(extraLen + idrBytes.size)
        var pos = 0
        if (csd0 != null && csd0.isNotEmpty()) {
            System.arraycopy(csd0, 0, combined, pos, csd0.size)
            pos += csd0.size
        }
        if (csd1 != null && csd1.isNotEmpty()) {
            System.arraycopy(csd1, 0, combined, pos, csd1.size)
            pos += csd1.size
        }
        System.arraycopy(idrBytes, 0, combined, pos, idrBytes.size)
        return combined
    }

    private fun decodeKeyframeToJpeg(
        decoder: MediaCodec,
        srcFmt: MediaFormat,
        idrBytes: ByteArray,
        ptsUs: Long,
        outFile: File
    ): Boolean {
        return try {
            val payload = prependCodecConfigIfNeeded(srcFmt, idrBytes)
            val inIdx = decoder.dequeueInputBuffer(30_000L)
            if (inIdx < 0) return false
            val inBuf = decoder.getInputBuffer(inIdx) ?: return false
            inBuf.clear()
            if (inBuf.capacity() < payload.size) return false
            inBuf.put(payload)
            decoder.queueInputBuffer(inIdx, 0, payload.size, ptsUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)

            // Queue EOS to force hardware decoders with 1-frame reorder queues to emit immediately,
            // then drain through EOS cleanly before calling flush().
            val eosIdx = decoder.dequeueInputBuffer(30_000L)
            var queuedEos = false
            if (eosIdx >= 0) {
                decoder.queueInputBuffer(eosIdx, 0, 0, ptsUs + 1_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queuedEos = true
            }

            val info = MediaCodec.BufferInfo()
            var wrote = false
            var sawEos = false
            var attempts = 0
            while (attempts < 18) {
                val outIdx = decoder.dequeueOutputBuffer(info, 25_000L)
                if (outIdx >= 0) {
                    if (!wrote && info.size > 0) {
                        val image = runCatching { decoder.getOutputImage(outIdx) }.getOrNull()
                        if (image != null) {
                            try {
                                wrote = writeYuvImageAsDownscaledJpeg(image, outFile)
                            } finally {
                                runCatching { image.close() }
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawEos = true
                        break
                    }
                    if (wrote && !queuedEos) break
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    attempts++
                    if (wrote && attempts >= 3) break
                }
            }
            if (queuedEos || !sawEos) {
                decoder.flush()
            }
            wrote
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Converts a decoded YUV_420_888 [Image] into a 2x-downscaled NV21 JPEG thumbnail and writes
     * atomically to [outFile] so readers never observe a half-written JPEG.
     */
    private fun writeYuvImageAsDownscaledJpeg(image: Image, outFile: File): Boolean {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) return false
        val crop = image.cropRect
        val srcW = crop.width()
        val srcH = crop.height()
        if (srcW < 16 || srcH < 16) return false

        val step = if (srcW >= 480) 2 else 1
        val dstW = (srcW / step) and 1.inv() // even width for NV21
        val dstH = (srcH / step) and 1.inv() // even height for NV21
        if (dstW < 16 || dstH < 16) return false

        val nv21 = ByteArray(dstW * dstH * 3 / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        var outPos = 0
        for (y in 0 until dstH) {
            val srcY = crop.top + y * step
            val rowOff = srcY * yRowStride + crop.left * yPixStride
            val colStep = step * yPixStride
            var p = rowOff
            for (x in 0 until dstW) {
                nv21[outPos++] = yBuf.get(p)
                p += colStep
            }
        }

        val chromaH = dstH / 2
        val chromaW = dstW / 2
        for (cy in 0 until chromaH) {
            val srcCy = (crop.top / 2) + cy * step
            val uRowOff = srcCy * uRowStride + (crop.left / 2) * uPixStride
            val vRowOff = srcCy * vRowStride + (crop.left / 2) * vPixStride
            val uColStep = step * uPixStride
            val vColStep = step * vPixStride
            var up = uRowOff
            var vp = vRowOff
            for (cx in 0 until chromaW) {
                nv21[outPos++] = vBuf.get(vp)
                nv21[outPos++] = uBuf.get(up)
                up += uColStep
                vp += vColStep
            }
        }

        val baos = ByteArrayOutputStream(32 * 1024)
        val yuv = YuvImage(nv21, ImageFormat.NV21, dstW, dstH, null)
        if (!yuv.compressToJpeg(Rect(0, 0, dstW, dstH), 68, baos)) return false

        outFile.parentFile?.mkdirs()
        val tmpFile = File(outFile.parentFile, "${outFile.name}.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(tmpFile, false).use { fos ->
                fos.write(baos.toByteArray())
                fos.flush()
            }
            val movedAtomically = runCatching {
                Files.move(
                    tmpFile.toPath(),
                    outFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            }.getOrDefault(false)
            if (movedAtomically) {
                true
            } else if (tmpFile.renameTo(outFile)) {
                true
            } else {
                // Never truncate outFile in place (causes torn JPEGs); skip this frame cleanly
                runCatching { tmpFile.delete() }
                false
            }
        } catch (_: Throwable) {
            runCatching { tmpFile.delete() }
            false
        }
    }

    /**
     * Non-destructive ~2s dry-run verifying Stealth 3-Way Isolation capabilities on the exact
     * user device ROM inside `com.android.shell` (`uid = 2000`).
     */
    private fun runSelfTest(probeUid: Int) {
        var callbackThread: HandlerThread? = null
        var audioManager: AudioManager? = null
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var vd: VirtualDisplay? = null
        var speakerHandle: ShellAudioCaptureHandle? = null
        var micHandle: ShellVirtualMicHandle? = null

        try {
            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }
            val cbThread = HandlerThread("AdbVDisp-SelfTest").apply {
                isDaemon = true
                start()
            }
            callbackThread = cbThread
            val cbLooper = cbThread.looper
            val cbHandler = Handler(cbLooper)

            // 1. Shell Context & AttributionSource
            val shellContext = try {
                val ctx = buildShellAttributedContext()
                val uid = Process.myUid()
                val opPkg = ctx.opPackageName
                emitLine("SELFTEST_ITEM:SHELL_CONTEXT:PASS:uid=$uid, opPackage=$opPkg, attrPkg=${ctx.attributionSource.packageName}")
                ctx
            } catch (t: Throwable) {
                emitLine("SELFTEST_ITEM:SHELL_CONTEXT:FAIL:${t.message ?: t.javaClass.simpleName}")
                emitLine("SELFTEST_DONE")
                return
            }

            // 2. VirtualDisplay + H.264 Encoder
            val dm = shellContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            if (dm == null) {
                emitLine("SELFTEST_ITEM:VIRTUAL_DISPLAY:FAIL:DisplayManager unavailable in shell context")
            } else {
                try {
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 720, 1280).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    }
                    val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val surf = enc.createInputSurface()
                    enc.start()
                    codec = enc
                    surface = surf

                    val vdRes = createVirtualDisplayWithFallback(dm, 720, 1280, 240, surf, cbHandler)
                    vd = vdRes.virtualDisplay
                    val id = vdRes.virtualDisplay.display?.displayId ?: -1
                    val status = if (vdRes.tierName == "FULL_TRUSTED_ALWAYS_UNLOCKED") "PASS" else "WARN"
                    emitLine("SELFTEST_ITEM:VIRTUAL_DISPLAY:$status:Created display #$id (${vdRes.tierName}) + H.264 surface OK")

                    // 2b. Verify `input -d <id>` touch/key injection against the live virtual display
                    if (id > 0) {
                        var injectDetail = ""
                        val injectOk = try {
                            val proc = ProcessBuilder("input", "-d", id.toString(), "keyevent", "0")
                                .redirectErrorStream(true)
                                .start()
                            val outRef = AtomicReference("")
                            val drainThread = Thread({
                                runCatching {
                                    outRef.set(proc.inputStream.bufferedReader().use { it.readText().trim().take(240) })
                                }
                            }, "InputProbe-Drain").apply {
                                isDaemon = true
                                start()
                            }
                            val done = proc.waitFor(3, TimeUnit.SECONDS)
                            if (!done) {
                                runCatching { proc.destroyForcibly() }
                                runCatching { proc.inputStream.close() }
                                runCatching { drainThread.join(250L) }
                                injectDetail = "timed out after 3s"
                                false
                            } else {
                                runCatching { drainThread.join(300L) }
                                val code = proc.exitValue()
                                val outText = outRef.get()
                                if (code == 0) {
                                    true
                                } else {
                                    injectDetail = if (outText.isNotBlank()) "exit=$code: $outText" else "exit=$code"
                                    false
                                }
                            }
                        } catch (t: Throwable) {
                            injectDetail = t.message ?: t.javaClass.simpleName
                            false
                        }
                        if (injectOk) {
                            emitLine("SELFTEST_ITEM:TOUCH_INJECT:PASS:input -d #$id accepted remote input injection")
                        } else {
                            val reason = if (injectDetail.isNotBlank()) " ($injectDetail)" else ""
                            emitLine(
                                "SELFTEST_ITEM:TOUCH_INJECT:WARN:input -d #$id restricted$reason. On Xiaomi/HyperOS, enable Developer Options -> USB debugging (Security settings) for mini-preview touch forwarding; video & audio isolation still work."
                            )
                        }
                    }

                    // 2c. Verify AVC preview decoder configures cleanly (with automatic KEY_LOW_LATENCY fallback)
                    val probeFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 720, 1280)
                    val decRes = configurePreviewDecoderWithFallback(probeFmt)
                    if (decRes != null) {
                        val (probeDec, lowLatUsed) = decRes
                        runCatching { probeDec.stop() }
                        runCatching { probeDec.release() }
                        emitLine(
                            "SELFTEST_ITEM:PREVIEW_DECODER:PASS:H.264 preview decoder ready (lowLatency=$lowLatUsed)"
                        )
                    } else {
                        emitLine("SELFTEST_ITEM:PREVIEW_DECODER:WARN:AVC decoder probe failed — mini-preview image may not render")
                    }
                } catch (t: Throwable) {
                    emitLine("SELFTEST_ITEM:VIRTUAL_DISPLAY:FAIL:${t.message ?: t.javaClass.simpleName}")
                } finally {
                    runCatching { vd?.release() }; vd = null
                    runCatching { codec?.stop() }
                    runCatching { codec?.release() }; codec = null
                    runCatching { surface?.release() }; surface = null
                }
            }

            val am = shellContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager = am
            val effectiveUid = if (probeUid > 0) probeUid else 10000

            // 3. Per-UID Silent Speaker Loopback
            if (am == null) {
                emitLine("SELFTEST_ITEM:SPEAKER_LOOPBACK:FAIL:AudioManager unavailable in shell context")
            } else {
                val capRes = setupIsolatedAudioCapture(
                    shellContext = shellContext,
                    audioManager = am,
                    callbackLooper = cbLooper,
                    targetUid = effectiveUid,
                    silentSpeaker = true
                )
                val handle = capRes.first
                if (handle != null) {
                    speakerHandle = handle
                    val status = if (handle.tierLabel == "PER_UID_SILENT_LOOPBACK") "PASS" else "WARN"
                    val desc = if (status == "PASS") {
                        "Per-UID silent speaker loopback OK (${handle.sampleRateHz} Hz, ${handle.channelCount}ch, sid=${handle.audioRecord.audioSessionId})"
                    } else {
                        "Fallback tier ${handle.tierLabel} (${handle.sampleRateHz} Hz) — speaker may stay audible"
                    }
                    emitLine("SELFTEST_ITEM:SPEAKER_LOOPBACK:$status:$desc")
                    runCatching { handle.audioRecord.release() }
                    unregisterAudioPolicySafe(am, handle.audioPolicy)
                    speakerHandle = null
                } else {
                    emitLine("SELFTEST_ITEM:SPEAKER_LOOPBACK:FAIL:${capRes.second ?: "AudioPolicy rejected"}")
                }
            }

            // 4. Per-UID Virtual Silent Microphone
            if (am == null) {
                emitLine("SELFTEST_ITEM:VIRTUAL_MIC:FAIL:AudioManager unavailable in shell context")
            } else {
                val micRes = setupVirtualSilentMicrophone(
                    shellContext = shellContext,
                    audioManager = am,
                    callbackLooper = cbLooper,
                    targetUid = effectiveUid
                )
                val mHandle = micRes.first
                if (mHandle != null) {
                    micHandle = mHandle
                    emitLine("SELFTEST_ITEM:VIRTUAL_MIC:PASS:Per-UID MIX_TYPE_RECORDERS silent mic OK (${mHandle.sampleRateHz} Hz, LOW_LATENCY)")
                    runCatching { mHandle.audioTrack.release() }
                    unregisterAudioPolicySafe(am, mHandle.audioPolicy)
                    micHandle = null
                } else {
                    emitLine("SELFTEST_ITEM:VIRTUAL_MIC:WARN:MIX_TYPE_RECORDERS rejected (${micRes.second ?: "OEM policy"}); AppOps RECORD_AUDIO ignore fallback will be used")
                }
            }
        } catch (t: Throwable) {
            emitLine("SELFTEST_ITEM:RUNNER:FAIL:${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { speakerHandle?.audioRecord?.release() }
            runCatching { micHandle?.audioTrack?.release() }
            unregisterAudioPolicySafe(audioManager, speakerHandle?.audioPolicy)
            unregisterAudioPolicySafe(audioManager, micHandle?.audioPolicy)
            runCatching { vd?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { surface?.release() }
            runCatching { callbackThread?.quitSafely() }
            emitLine("SELFTEST_DONE")
        }
    }

    private fun computeRms(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until count) {
            val d = samples[i].toDouble()
            sumSq += d * d
        }
        return sqrt(sumSq / count).toFloat()
    }

    private fun rmsToVisualNorm(rms: Float): Float {
        if (rms <= 15f) return 0f
        val db = 20.0 * log10((rms / 32767.0).coerceAtLeast(1e-5))
        return ((db + 58.0) / 52.0).toFloat().coerceIn(0f, 1f)
    }

    private fun emitLine(line: String) {
        synchronized(System.out) {
            System.out.println(line)
            System.out.flush()
        }
    }

    /**
     * Self-contained 16 kHz mono 64 kbps AAC-LC ADTS writer for the isolated shell `app_process`.
     * Synchronized against concurrent shutdown calls and bounded against encoder stalls.
     */
    private class ShellAdtsAacWriter(private val outputFile: File) {
        private val writeLock = Any()
        private var closed = false
        private var codec: MediaCodec? = null
        private var outStream: BufferedOutputStream? = null
        private val info = MediaCodec.BufferInfo()
        private val adtsHeader = ByteArray(7)
        private var payloadBuf = ByteArray(4096)
        private var totalSamples = 0L
        private var framesSinceFlush = 0

        fun start() = synchronized(writeLock) {
            closed = false
            outputFile.parentFile?.mkdirs()
            outStream = BufferedOutputStream(FileOutputStream(outputFile, false), 16 * 1024)
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, TARGET_AUDIO_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            codec = enc
        }

        fun writePcmShorts(samples: ShortArray, offset: Int, length: Int) = synchronized(writeLock) {
            if (closed) return
            val enc = codec ?: return
            var idx = offset
            val end = offset + length
            var stalledRetries = 0
            while (idx < end && !closed) {
                val inIdx = enc.dequeueInputBuffer(5_000L)
                if (inIdx >= 0) {
                    stalledRetries = 0
                    val inBuf = enc.getInputBuffer(inIdx) ?: continue
                    inBuf.clear()
                    inBuf.order(ByteOrder.nativeOrder())
                    val maxShorts = (inBuf.remaining() / 2).coerceAtMost(end - idx)
                    inBuf.asShortBuffer().put(samples, idx, maxShorts)
                    val ptsUs = (totalSamples * 1_000_000L) / TARGET_AUDIO_RATE
                    totalSamples += maxShorts
                    enc.queueInputBuffer(inIdx, 0, maxShorts * 2, ptsUs, 0)
                    idx += maxShorts
                } else {
                    if (++stalledRetries > 25) break
                }
                drain(enc, endOfStream = false)
            }
        }

        fun finishAndClose() = synchronized(writeLock) {
            if (closed) return
            closed = true
            val enc = codec
            if (enc != null) {
                runCatching {
                    val inIdx = enc.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val ptsUs = (totalSamples * 1_000_000L) / TARGET_AUDIO_RATE
                        enc.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    drain(enc, endOfStream = true)
                }
                runCatching { enc.stop() }
                runCatching { enc.release() }
            }
            codec = null
            runCatching { outStream?.flush() }
            runCatching { outStream?.close() }
            outStream = null
        }

        private fun drain(enc: MediaCodec, endOfStream: Boolean) {
            val out = outStream ?: return
            var retries = 0
            while (true) {
                val outIdx = enc.dequeueOutputBuffer(info, if (endOfStream) 5_000L else 0L)
                if (outIdx >= 0) {
                    val data = enc.getOutputBuffer(outIdx)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && info.size > 0 && data != null) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        val packetLen = info.size + 7
                        fillAdtsHeader16kMono(packetLen, adtsHeader)
                        out.write(adtsHeader, 0, 7)
                        if (payloadBuf.size < info.size) payloadBuf = ByteArray(info.size * 2)
                        data.get(payloadBuf, 0, info.size)
                        out.write(payloadBuf, 0, info.size)
                        if (++framesSinceFlush >= 64) {
                            out.flush()
                            framesSinceFlush = 0
                        }
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream || ++retries > 10) break
                } else if (outIdx != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    break
                }
            }
            if (endOfStream) out.flush()
        }

        private fun fillAdtsHeader16kMono(packetLen: Int, target: ByteArray) {
            val profile = 2 // AAC-LC
            val freqIdx = 8 // 16 kHz
            val chanCfg = 1 // Mono
            target[0] = 0xFF.toByte()
            target[1] = 0xF9.toByte()
            target[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
            target[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
            target[4] = ((packetLen and 0x7FF) shr 3).toByte()
            target[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
            target[6] = 0xFC.toByte()
        }
    }
}
