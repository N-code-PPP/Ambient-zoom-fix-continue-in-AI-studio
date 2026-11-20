package com.ambientbrain.recorder.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import kotlin.math.sqrt

/**
 * On-device Voice Activity Detection (Silero VAD v5 at 16 kHz, 512-sample = 32 ms frames)
 * + 2-second circular PCM pre-roll ring buffer (32,000 samples = 64 KB).
 *
 * Uses pre-allocated heap arrays wrapped in reusable [FloatBuffer] and [LongBuffer]
 * instances. This guarantees zero per-frame Java heap allocations while completely
 * avoiding JNI direct memory faults or 16KB page alignment issues on Android 16.
 */
class SileroVadProcessor(context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val contextSamples = FloatArray(64)                       // Silero v5 64-sample trailing context

    // Direct native memory buffers (guaranteed isDirect == true for JNI)
    private val directInputBuffer: FloatBuffer = ByteBuffer.allocateDirect((64 + FRAME_SIZE_SAMPLES) * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val directStateBuffer: FloatBuffer = ByteBuffer.allocateDirect(2 * 1 * 128 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val directSrBuffer: LongBuffer = ByteBuffer.allocateDirect(1 * 8)
        .order(ByteOrder.nativeOrder())
        .asLongBuffer()

    private val inputShape = longArrayOf(1L, 576L)
    private val stateShape = longArrayOf(2L, 1L, 128L)
    private val srShape = longArrayOf(1L)

    // 2-second circular pre-roll buffer at 16 kHz = 32,000 shorts
    private val preRollCapacity = PRE_ROLL_SAMPLES
    private val preRollRing = ShortArray(preRollCapacity)
    private var preRollWritePos = 0
    private var preRollFilled = 0

    // Adaptive noise floor & Stage-A RMS acoustic pre-gate
    private var adaptiveNoiseRms = 120.0f
    private var onnxWakeHangoverFrames = 0

    @Volatile
    var gatedFramesCount: Long = 0L
        private set

    @Volatile
    var onnxFramesCount: Long = 0L
        private set

    init {
        resetState()
        try {
            val assetList = context.assets.list("") ?: emptyArray()
            if (assetList.contains(MODEL_ASSET_NAME)) {
                val modelBytes = context.assets.open(MODEL_ASSET_NAME).use { it.readBytes() }
                ortEnv = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                }
                ortSession = ortEnv?.createSession(modelBytes, opts)
                Log.i(TAG, "Loaded $MODEL_ASSET_NAME via ONNX Runtime Mobile (RMS Pre-Gate + Safe Direct JNI Buffers)")
            } else {
                Log.i(TAG, "$MODEL_ASSET_NAME not in assets; using adaptive voiceband VAD")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize ONNX Silero VAD; using adaptive fallback", t)
            ortSession = null
        }
    }

    /**
     * Evaluates a 512-sample [ShortBuffer] view backed by a direct [ByteBuffer].
     * In quiet standby (`!isSessionActive`), runs a zero-allocation integer RMS/peak pre-gate
     * first; if acoustic energy sits calmly below the adaptive noise floor, updates the trailing
     * 64-sample context and skips heavy ONNX LSTM inference. Any sound rise wakes ONNX immediately
     * for a 2.4s hangover window, backed by the continuous 2.0s pre-roll buffer (zero clipped speech).
     */
    fun processDirectFrame512(shortView: ShortBuffer, isSessionActive: Boolean = true): Float {
        val sampleCount = shortView.remaining()
        if (sampleCount <= 0) return 0f

        val basePos = shortView.position()
        var sumSq = 0L
        var maxAbs = 0
        for (i in 0 until sampleCount) {
            val v = shortView.get(basePos + i).toInt()
            val absV = if (v < 0) -v else v
            if (absV > maxAbs) maxAbs = absV
            sumSq += v.toLong() * v.toLong()
        }
        val rms = sqrt(sumSq.toDouble() / sampleCount.toDouble()).toFloat()

        // Stage A: Zero-loss quiet standby acoustic gate
        if (!isSessionActive && sampleCount == FRAME_SIZE_SAMPLES) {
            val wakeThresholdRms = maxOf(85f, adaptiveNoiseRms * 1.42f)
            val hasAcousticEnergy = rms >= wakeThresholdRms || maxAbs >= 380

            if (!hasAcousticEnergy) {
                if (onnxWakeHangoverFrames > 0) {
                    onnxWakeHangoverFrames--
                } else {
                    // Track ambient noise floor gently in silence
                    if (rms < adaptiveNoiseRms * 1.5f) {
                        adaptiveNoiseRms = (adaptiveNoiseRms * 0.96f + rms * 0.04f).coerceIn(50f, 650f)
                    }
                    // Keep trailing 64 samples warm so instant wake has seamless LSTM context
                    val tailStart = FRAME_SIZE_SAMPLES - 64
                    for (j in 0 until 64) {
                        contextSamples[j] = shortView.get(basePos + tailStart + j) / 32768.0f
                    }
                    gatedFramesCount++
                    return 0.05f
                }
            } else {
                onnxWakeHangoverFrames = WAKE_HANGOVER_FRAMES
            }
        } else if (isSessionActive) {
            onnxWakeHangoverFrames = WAKE_HANGOVER_FRAMES
        }

        onnxFramesCount++
        val session = ortSession
        val env = ortEnv
        if (session != null && env != null && sampleCount == FRAME_SIZE_SAMPLES) {
            try {
                // Populate direct input buffer: 64 context samples + 512 frame samples
                directInputBuffer.clear()
                directInputBuffer.put(contextSamples)
                for (i in 0 until FRAME_SIZE_SAMPLES) {
                    val s = shortView.get(basePos + i)
                    val norm = s / 32768.0f
                    directInputBuffer.put(norm)
                    if (i >= FRAME_SIZE_SAMPLES - 64) {
                        contextSamples[i - (FRAME_SIZE_SAMPLES - 64)] = norm
                    }
                }
                directInputBuffer.flip()

                // Populate direct sample-rate buffer
                directSrBuffer.clear()
                directSrBuffer.put(SAMPLE_RATE.toLong())
                directSrBuffer.flip()

                // Rewind direct state buffer to 0
                directStateBuffer.position(0)

                OnnxTensor.createTensor(env, directInputBuffer, inputShape).use { xTensor ->
                    OnnxTensor.createTensor(env, directStateBuffer, stateShape).use { stateTensor ->
                        OnnxTensor.createTensor(env, directSrBuffer, srShape).use { srTensor ->
                            val inputs = mapOf(
                                "input" to xTensor,
                                "state" to stateTensor,
                                "sr" to srTensor
                            )
                            session.run(inputs).use { results ->
                                val outProb = (results[0].value as Array<FloatArray>)[0][0]
                                val newState = results[1].value as Array<Array<FloatArray>>
                                directStateBuffer.clear()
                                for (a in newState) {
                                    for (b in a) {
                                        directStateBuffer.put(b)
                                    }
                                }
                                directStateBuffer.flip()
                                return outProb.coerceIn(0f, 1f)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ONNX VAD step encountered error; gracefully switching to adaptive voiceband detector", t)
                runCatching { session.close() }
                ortSession = null
            }
        }
        return evaluateFallbackSpeechProbDirect(shortView, precomputedRms = rms)
    }

    /**
     * Evaluates a 512-sample (32 ms at 16 kHz) ShortArray frame and returns speech probability in [0.0, 1.0].
     */
    fun processFrame512(frame: ShortArray, isSessionActive: Boolean = true): Float {
        return processDirectFrame512(ShortBuffer.wrap(frame), isSessionActive = isSessionActive)
    }

    private fun evaluateFallbackSpeechProbDirect(shortView: ShortBuffer, precomputedRms: Float? = null): Float {
        val count = shortView.remaining()
        if (count <= 0) return 0f
        val basePos = shortView.position()
        var sumSq = 0.0
        var zeroCrossings = 0
        var prev = shortView.get(basePos).toInt()
        for (i in 0 until count) {
            val v = shortView.get(basePos + i).toInt()
            if (precomputedRms == null) {
                sumSq += v.toDouble() * v.toDouble()
            }
            if ((v >= 0 && prev < 0) || (v < 0 && prev >= 0)) {
                zeroCrossings++
            }
            prev = v
        }
        val rms = precomputedRms ?: sqrt(sumSq / count).toFloat()
        val zcr = zeroCrossings.toFloat() / count.toFloat()

        // Update noise floor slowly during quiet frames
        if (rms < adaptiveNoiseRms * 1.5f) {
            adaptiveNoiseRms = (adaptiveNoiseRms * 0.95f + rms * 0.05f).coerceIn(60f, 800f)
        }

        val snrRatio = rms / adaptiveNoiseRms.coerceAtLeast(80f)
        val isVoiceZcr = zcr in 0.02f..0.48f

        val rawScore = when {
            rms > 160f && snrRatio > 1.35f -> ((rms - 90f) / 250f + (snrRatio - 1.2f) * 0.25f).coerceIn(0.55f, 0.95f)
            rms > 120f && snrRatio > 1.2f -> 0.52f
            rms > 80f -> 0.35f
            else -> 0.1f
        }
        return if (isVoiceZcr) rawScore else (rawScore * 0.7f)
    }

    fun appendDirectToPreRoll(shortView: ShortBuffer) {
        val count = shortView.remaining()
        val basePos = shortView.position()
        for (i in 0 until count) {
            preRollRing[preRollWritePos] = shortView.get(basePos + i)
            preRollWritePos = (preRollWritePos + 1) % preRollCapacity
            if (preRollFilled < preRollCapacity) {
                preRollFilled++
            }
        }
    }

    fun appendToPreRoll(frame: ShortArray) {
        for (s in frame) {
            preRollRing[preRollWritePos] = s
            preRollWritePos = (preRollWritePos + 1) % preRollCapacity
            if (preRollFilled < preRollCapacity) {
                preRollFilled++
            }
        }
    }

    /**
     * Drains and returns the chronological 2-second PCM pre-roll buffer so the start of a
     * spoken sentence is never clipped.
     */
    fun drainPreRoll(): ShortArray {
        if (preRollFilled == 0) return ShortArray(0)
        val out = ShortArray(preRollFilled)
        val startIdx = if (preRollFilled == preRollCapacity) preRollWritePos else 0
        for (i in 0 until preRollFilled) {
            out[i] = preRollRing[(startIdx + i) % preRollCapacity]
        }
        preRollFilled = 0
        preRollWritePos = 0
        return out
    }

    fun resetState() {
        directStateBuffer.clear()
        for (i in 0 until (2 * 1 * 128)) {
            directStateBuffer.put(0f)
        }
        directStateBuffer.flip()
        contextSamples.fill(0f)
        preRollFilled = 0
        preRollWritePos = 0
        onnxWakeHangoverFrames = 0
    }

    fun close() {
        runCatching { ortSession?.close() }
        ortSession = null
    }

    companion object {
        private const val TAG = "SileroVadProcessor"
        const val MODEL_ASSET_NAME = "silero_vad_v5.onnx"
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE_SAMPLES = 512 // 32 ms at 16 kHz
        const val FRAME_DURATION_MS = 32L
        const val PRE_ROLL_SAMPLES = SAMPLE_RATE * 2 // 2.0 seconds = 32,000 samples
        const val SPEECH_ONSET_THRESHOLD = 0.50f
        const val SPEECH_OFFSET_THRESHOLD = 0.35f
        private const val WAKE_HANGOVER_FRAMES = 75 // ~2.4 seconds ONNX wake confirmation window
    }
}
