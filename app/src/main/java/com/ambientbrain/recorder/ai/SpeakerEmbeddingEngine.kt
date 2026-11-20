package com.ambientbrain.recorder.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.ambientbrain.recorder.data.ClipSlicer
import com.ambientbrain.recorder.data.SpeakerProfileEntry
import com.ambientbrain.recorder.data.VoiceProfileSample
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class DuplicateProfileSuggestion(
    val primaryProfile: SpeakerProfileEntry,
    val duplicateProfile: SpeakerProfileEntry,
    val similarity: Float
)

data class VoiceprintMatchResult(
    val profileId: String,
    val isSelfUser: Boolean,
    val similarity: Float,
    val marginOverSecond: Float
)

data class AcousticVoiceDescriptor(
    val pitchCategory: String,
    val pitchRegisterLabel: String,
    val timbreLabel: String,
    val cadenceLabel: String,
    val recommendedAdjectivePrefix: String
) {
    fun contradictsNickname(nickname: String): Boolean {
        val lower = nickname.trim().lowercase()
        val hasLowAdjective = lower.startsWith("deep-tone") || lower.startsWith("low-pitch") || lower.contains("baritone")
        val hasHighAdjective = lower.startsWith("high-pitch") || lower.startsWith("bright-tone")
        return (hasLowAdjective && pitchCategory in setOf("MID_HIGH", "HIGH")) ||
            (hasHighAdjective && pitchCategory == "LOW")
    }

    fun buildAcousticSignatureSummary(snrDb: Float = 0f, speechRatio: Float = 0f): String {
        val snrPart = if (snrDb >= 10f) ", ${snrDb.toInt()} dB SNR clarity" else ""
        val speechPart = if (speechRatio >= 0.35f) ", ${(speechRatio * 100f).toInt()}% active speech" else ""
        return "$pitchRegisterLabel, $timbreLabel, $cadenceLabel$snrPart$speechPart"
    }
}

data class AcousticSpeakerSegment(
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val embedding: List<Float>,
    val medianF0Hz: Float = 0f,
    val snrDb: Float = 0f,
    val speechRatio: Float = 0f,
    val singleSpeakerPurity: Float = 1f,
    val clusterId: Int = -1
)

data class ClusteredSpeakerSummary(
    val clusterId: Int,
    val centroid: List<Float>,
    val segmentIndices: List<Int>,
    val totalDurationMs: Long,
    val medianF0Hz: Float,
    val descriptor: AcousticVoiceDescriptor?
)

data class SessionAcousticScanResult(
    val segments: List<AcousticSpeakerSegment>,
    val clusters: List<ClusteredSpeakerSummary>,
    val changePointCount: Int,
    val totalScannedMs: Long
)

data class SingleSpeakerPurityReport(
    val purityScore: Float,
    val hasSuspectedCrosstalk: Boolean,
    val subWindowMinSimilarity: Float,
    val pitchBimodalityScore: Float,
    val meanHnrDb: Float,
    val subWindowsEvaluated: Int
)

/**
 * On-device Speaker Voiceprint Engine (64-D v2 L2-normalized acoustic embedding with seamless
 * 48-D legacy compatibility, plus optional ONNX neural speaker encoder plug-in via `onnxruntime-android`).
 *
 * Capabilities:
 *  1. Computes deterministic 64-D speaker voiceprints from any `.wav` / `.aac` / `.m4a` voice sample using
 *     Minimum-Statistics Spectral Subtraction (room/AC noise removal), RASTA-style temporal CMVN on
 *     16-MFCC + Δ-MFCC dynamics, fundamental pitch (F0), vocal-tract Formant peaks (F1, F2, F3 & ratios),
 *     Harmonic-to-Noise Ratio (HNR), Pitch Jitter, Amplitude Shimmer, and vocal subband energy ratios.
 *  2. Automatically loads an optional `speaker_embedding.onnx` encoder from `assets/` or
 *     `filesDir/models/speaker_embedding.onnx` if present, transparently falling back to the
 *     deterministic acoustic voiceprint extractor otherwise.
 *  3. Pre-matches roster profiles via VAD-guided BIC/GLR change-point segmentation & complete-linkage
 *     speaker clustering BEFORE calling Gemini so every distinct speaker in a recording is captured.
 *  4. Anti-contamination consistency check: prevents crosstalk or misattributed auto-clips from
 *     polluting an established profile's top-10 samples, and guards against combining different speakers.
 *  5. On-device voiceprint cross-matching (`findBestVoiceprintMatch`) against My Voice and the
 *     Speaker Roster with runner-up margin checking, enabling automatic voice recognition even
 *     when Gemini omits `matched_profile_id` or when mining old unlabeled recordings offline.
 *  6. Duplicate profile detection: finds pairs of roster entries whose centroids strongly match
 *     so the user can merge them in 1 tap.
 */
object SpeakerEmbeddingEngine {

    private const val TAG = "SpeakerEmbedding"
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_LEN = 400           // 25 ms @ 16 kHz
    private const val FRAME_HOP = 160           // 10 ms @ 16 kHz
    private const val FFT_SIZE = 512            // Power-of-two radix-2 FFT
    private const val NUM_MEL_BINS = 32
    private const val NUM_MFCC = 16             // c1..c16 (c0 omitted for gain invariance)
    private const val MIN_VOICED_FRAMES = 18    // >= ~0.18s voiced speech minimum
    const val LEGACY_EMBEDDING_DIM = 48
    const val EMBEDDING_DIM = 64

    const val ONNX_MODEL_NAME = "speaker_embedding.onnx"
    const val CONSISTENCY_THRESHOLD = 0.64f
    const val INTRA_SESSION_CONSISTENCY_THRESHOLD = 0.66f
    const val DUPLICATE_MERGE_THRESHOLD = 0.83f
    const val PREMATCH_HIGH_THRESHOLD = 0.68f
    const val VOICEPRINT_SELF_MATCH_THRESHOLD = 0.79f
    const val VOICEPRINT_ROSTER_MATCH_THRESHOLD = 0.77f
    const val VOICEPRINT_MIN_MARGIN = 0.045f
    const val VOICEPRINT_CONTRADICTION_THRESHOLD = 0.58f
    const val UNLABELED_HIGH_CONFIDENCE_THRESHOLD = 0.81f

    @Volatile
    private var onnxChecked = false
    @Volatile
    private var onnxSession: OrtSession? = null
    @Volatile
    private var onnxInputName: String? = null
    @Volatile
    private var onnxInputRank: Int = 0

    // Precomputed DSP tables (initialized once, thread-safe read-only)
    private val hammingWindow: FloatArray by lazy {
        FloatArray(FRAME_LEN) { i ->
            (0.54 - 0.46 * cos(2.0 * PI * i / (FRAME_LEN - 1))).toFloat()
        }
    }

    private val melFilterBank: Array<FloatArray> by lazy {
        buildMelFilterBank(
            numFilters = NUM_MEL_BINS,
            fftSize = FFT_SIZE,
            sampleRate = SAMPLE_RATE,
            minFreqHz = 85.0,
            maxFreqHz = 7600.0
        )
    }

    private val dctMatrix: Array<FloatArray> by lazy {
        val scale = sqrt(2.0 / NUM_MEL_BINS)
        Array(NUM_MFCC) { k ->
            val coeffIdx = k + 1 // skip c0 (overall energy)
            FloatArray(NUM_MEL_BINS) { m ->
                (scale * cos(PI * coeffIdx * (m + 0.5) / NUM_MEL_BINS)).toFloat()
            }
        }
    }

    /**
     * Extracts a normalized voiceprint vector from an audio file (`.wav` / `.aac` / `.m4a`).
     * Returns an empty list if the clip has insufficient voiced speech.
     */
    fun extractEmbeddingFromFile(
        context: Context?,
        audioFile: File,
        maxSeconds: Int = 14
    ): List<Float> {
        if (!audioFile.exists() || audioFile.length() < 1024L) return emptyList()
        val pcm = ClipSlicer.decodeAudioFileToMono16kPcm(audioFile, maxSeconds = maxSeconds)
        return extractEmbeddingFromPcm(context, pcm)
    }

    /**
     * Extracts a normalized voiceprint vector directly from a 16 kHz mono PCM [ShortArray].
     */
    fun extractEmbeddingFromPcm(
        context: Context?,
        pcm: ShortArray
    ): List<Float> {
        if (pcm.size < SAMPLE_RATE / 2) return emptyList()
        if (context != null) {
            tryExtractOnnxEmbedding(context, pcm)?.let { return it }
        }
        return extractAcousticEmbedding(pcm)
    }

    private data class CoarseBlockFeature(
        val startMs: Long,
        val endMs: Long,
        val rmsDb: Float,
        val zcr: Float,
        val f0Hz: Float,
        val mel8: FloatArray,
        var isActiveSpeech: Boolean = false
    )

    private data class CandidateIsland(
        val startMs: Long,
        val endMs: Long,
        val meanRmsDb: Float,
        val medianF0Hz: Float,
        val meanMel8: FloatArray,
        val trimmedFromChangePointStart: Boolean,
        val trimmedFromChangePointEnd: Boolean
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    /**
     * Computes a compact 0.5-second acoustic summary (RMS dB, ZCR, coarse F0, 8-band log Mel)
     * from 4 evenly spaced 25 ms voiced sub-frames for fast streaming speaker-turn detection.
     */
    private fun extractCoarseBlockFeature(
        chunkStartMs: Long,
        pcm: ShortArray,
        sampleCount: Int
    ): CoarseBlockFeature {
        val durMs = (sampleCount.toLong() * 1000L) / SAMPLE_RATE
        var sumSq = 0.0
        var crossings = 0
        var prev = if (sampleCount > 0) pcm[0].toInt() else 0
        for (i in 0 until sampleCount) {
            val v = pcm[i].toInt()
            val norm = v / 32768.0
            sumSq += norm * norm
            if (i > 0 && ((prev >= 0 && v < 0) || (prev < 0 && v >= 0))) crossings++
            prev = v
        }
        val rms = sqrt(sumSq / sampleCount.coerceAtLeast(1).toDouble())
        val rmsDb = (20.0 * log10(rms + 1e-6)).toFloat().coerceIn(-70f, 0f)
        val zcr = crossings.toFloat() / (sampleCount - 1).coerceAtLeast(1).toFloat()

        val mel8 = FloatArray(8)
        if (sampleCount < FRAME_LEN || rmsDb < -52f) {
            return CoarseBlockFeature(chunkStartMs, chunkStartMs + durMs, rmsDb, zcr, 0f, mel8)
        }

        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val powerSpec = FloatArray(FFT_SIZE / 2 + 1)
        val rawFrame = FloatArray(FRAME_LEN)
        val minLag = SAMPLE_RATE / 420
        val maxLag = SAMPLE_RATE / 75
        val f0Samples = ArrayList<Float>(6)

        val numProbes = 5
        val maxOffset = (sampleCount - FRAME_LEN).coerceAtLeast(0)
        var validMelCount = 0

        for (p in 0 until numProbes) {
            val offset = if (numProbes <= 1) 0 else (p * maxOffset) / (numProbes - 1)
            var mean = 0f
            for (i in 0 until FRAME_LEN) mean += pcm[offset + i] / 32768f
            mean /= FRAME_LEN

            var r0 = 0.0
            var prevRaw = 0f
            for (i in 0 until FRAME_LEN) {
                val cur = (pcm[offset + i] / 32768f) - mean
                rawFrame[i] = cur
                r0 += cur * cur
                val pre = if (i == 0) cur else cur - 0.97f * prevRaw
                re[i] = pre * hammingWindow[i]
                im[i] = 0f
                prevRaw = cur
            }
            if (r0 < 1e-6) continue
            for (i in FRAME_LEN until FFT_SIZE) {
                re[i] = 0f
                im[i] = 0f
            }

            // Coarse pitch F0 estimate (stride 2 samples for speed)
            var bestLag = -1
            var bestCorr = 0.0
            var lag = minLag
            while (lag <= min(maxLag, FRAME_LEN - 1)) {
                var acc = 0.0
                val limit = FRAME_LEN - lag
                var idx = 0
                while (idx < limit) {
                    acc += rawFrame[idx] * rawFrame[idx + lag]
                    idx += 2
                }
                val normCorr = (acc * 2.0) / r0
                if (normCorr > bestCorr) {
                    bestCorr = normCorr
                    bestLag = lag
                }
                lag += 2
            }
            if (bestCorr >= 0.30 && bestLag > 0) {
                val hz = SAMPLE_RATE.toFloat() / bestLag.toFloat()
                if (hz in 75f..420f) f0Samples.add(hz)
            }

            fftInPlace(re, im)
            val bins = FFT_SIZE / 2 + 1
            for (b in 0 until bins) {
                powerSpec[b] = re[b] * re[b] + im[b] * im[b]
            }
            // Pool 32 Mel filters into 8 compact log-energy bands
            for (g in 0 until 8) {
                var bandEnergy = 1e-10
                val mStart = g * 4
                for (m in mStart until mStart + 4) {
                    val weights = melFilterBank[m]
                    for (b in 0 until bins) {
                        val w = weights[b]
                        if (w > 0f) bandEnergy += powerSpec[b] * w
                    }
                }
                mel8[g] += ln(bandEnergy).toFloat()
            }
            validMelCount++
        }

        if (validMelCount > 0) {
            var melMean = 0f
            for (g in 0 until 8) {
                mel8[g] /= validMelCount.toFloat()
                melMean += mel8[g]
            }
            // Remove frame gain offset so Mel-8 represents spectral shape rather than loudness
            melMean /= 8f
            for (g in 0 until 8) mel8[g] -= melMean
        }
        val medianF0 = if (f0Samples.isNotEmpty()) {
            f0Samples.sort()
            f0Samples[f0Samples.size / 2]
        } else 0f

        return CoarseBlockFeature(chunkStartMs, chunkStartMs + durMs, rmsDb, zcr, medianF0, mel8)
    }

    /**
     * Task 1 — VAD-Guided & Speaker-Turn Change-Point Segmentation + On-Device Speaker Clustering:
     *
     * Replaces uniform grid sampling with a 4-stage streaming on-device acoustic scan:
     *  1. Streams the recording in 0.5-second O(1)-memory chunks to build a speech-activity + Mel/F0 timeline.
     *  2. Detects silence breaks (>= 1.0s) and speaker-turn change points via sliding-window
     *     Diagonal-Covariance Gaussian GLR/BIC + Mel-cosine + log-F0 jump scoring (2s left vs 2s right).
     *  3. Extracts homogeneous single-speaker islands (>= 2.5s, trimming 250ms transition margins),
     *     choosing an acoustically & temporally diverse set of up to [maxSegments] clean windows.
     *  4. Computes full voiceprints and runs complete-linkage biometric clustering so every distinct
     *     speaker present in the recording (even brief 5–10s turns) is represented before calling Gemini.
     */
    fun extractHomogeneousSpeakerSegments(
        context: Context?,
        audioFile: File,
        maxSegments: Int = 18,
        maxScanDurationMs: Long = 1_200_000L
    ): SessionAcousticScanResult {
        if (!audioFile.exists() || audioFile.length() < 4_096L) {
            return SessionAcousticScanResult(emptyList(), emptyList(), 0, 0L)
        }

        val blocks = ArrayList<CoarseBlockFeature>(256)
        val totalScannedMs = ClipSlicer.streamAudioFileAs16kChunks(
            audioFile = audioFile,
            chunkSamples = 8000, // 0.5s per block @ 16 kHz
            maxDurationMs = maxScanDurationMs
        ) { chunkStartMs, pcm, count ->
            if (count >= 1600) {
                blocks.add(extractCoarseBlockFeature(chunkStartMs, pcm, count))
            }
            true
        }

        if (blocks.size < 4 || totalScannedMs < 2_000L) {
            return SessionAcousticScanResult(emptyList(), emptyList(), 0, totalScannedMs)
        }

        // Stage 1: Adaptive VAD noise floor across scanned recording
        val sortedDbs = blocks.map { it.rmsDb }.sorted()
        val noiseFloorDb = sortedDbs[((sortedDbs.size - 1) * 0.20f).toInt().coerceIn(0, sortedDbs.lastIndex)]
        val speechGateDb = max(noiseFloorDb + 5.5f, -45f)
        for (b in blocks) {
            b.isActiveSpeech = b.rmsDb >= speechGateDb && b.zcr in 0.008f..0.45f
        }
        // Bridge single isolated 0.5s pauses inside continuous speech so short breaths don't fragment a turn
        for (i in 1 until blocks.lastIndex) {
            if (!blocks[i].isActiveSpeech && blocks[i - 1].isActiveSpeech && blocks[i + 1].isActiveSpeech) {
                blocks[i].isActiveSpeech = true
            }
        }

        // Stage 2: Build contiguous active-speech runs (split on >= 1.0s silence)
        val speechRuns = mutableListOf<IntRange>()
        var runStart = -1
        for (i in blocks.indices) {
            if (blocks[i].isActiveSpeech) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                speechRuns.add(runStart until i)
                runStart = -1
            }
        }
        if (runStart >= 0) speechRuns.add(runStart until blocks.size)

        // Stage 3: Sliding-Window Diagonal-Covariance Gaussian GLR/BIC + Mel/F0 Change-Point Detection
        val candidateIslands = mutableListOf<CandidateIsland>()
        var totalChangePoints = 0
        val winHalf = 4 // 4 blocks = 2.0s left vs 2.0s right

        fun computeChangeDistance(leftRange: IntRange, rightRange: IntRange): Float {
            val lCount = (leftRange.last - leftRange.first + 1).coerceAtLeast(1)
            val rCount = (rightRange.last - rightRange.first + 1).coerceAtLeast(1)
            val muL = FloatArray(8)
            val muR = FloatArray(8)
            val muP = FloatArray(8)
            val f0L = ArrayList<Float>(lCount)
            val f0R = ArrayList<Float>(rCount)

            for (idx in leftRange) {
                val b = blocks[idx]
                for (d in 0 until 8) muL[d] += b.mel8[d]
                if (b.f0Hz in 75f..420f) f0L.add(ln(b.f0Hz))
            }
            for (idx in rightRange) {
                val b = blocks[idx]
                for (d in 0 until 8) muR[d] += b.mel8[d]
                if (b.f0Hz in 75f..420f) f0R.add(ln(b.f0Hz))
            }
            for (d in 0 until 8) {
                muL[d] /= lCount.toFloat()
                muR[d] /= rCount.toFloat()
                muP[d] = (muL[d] * lCount + muR[d] * rCount) / (lCount + rCount).toFloat()
            }

            var glr = 0.0
            var dot = 0.0
            var nL = 0.0
            var nR = 0.0
            for (d in 0 until 8) {
                var varL = 0.08
                var varR = 0.08
                var varP = 0.08
                for (idx in leftRange) {
                    val dl = blocks[idx].mel8[d] - muL[d]
                    val dp = blocks[idx].mel8[d] - muP[d]
                    varL += dl * dl
                    varP += dp * dp
                }
                for (idx in rightRange) {
                    val dr = blocks[idx].mel8[d] - muR[d]
                    val dp = blocks[idx].mel8[d] - muP[d]
                    varR += dr * dr
                    varP += dp * dp
                }
                varL /= lCount
                varR /= rCount
                varP /= (lCount + rCount)
                val bicTerm = (lCount + rCount) * ln(varP) - lCount * ln(varL) - rCount * ln(varR)
                if (bicTerm.isFinite() && bicTerm > 0.0) glr += bicTerm
                dot += muL[d] * muR[d]
                nL += muL[d] * muL[d]
                nR += muR[d] * muR[d]
            }
            val melCos = if (nL > 1e-6 && nR > 1e-6) (dot / sqrt(nL * nR)).toFloat().coerceIn(-1f, 1f) else 1f
            val f0Jump = if (f0L.isNotEmpty() && f0R.isNotEmpty()) {
                abs(f0L.average() - f0R.average()).toFloat()
            } else 0f

            // Combined BIC/GLR + Mel spectral divergence + Pitch-register jump score
            return (glr.toFloat() * 0.14f) + (1f - melCos) * 1.9f + (f0Jump * 3.2f)
        }

        fun appendIslandFromBlockRange(range: IntRange, trimStart: Boolean, trimEnd: Boolean) {
            val rawStartMs = blocks[range.first].startMs
            val rawEndMs = blocks[range.last].endMs
            val startMs = if (trimStart) (rawStartMs + 250L).coerceAtMost(rawEndMs) else rawStartMs
            val endMs = if (trimEnd) (rawEndMs - 250L).coerceAtLeast(startMs) else rawEndMs
            if (endMs - startMs < 2_400L) return

            val meanMel = FloatArray(8)
            val f0List = ArrayList<Float>()
            var rmsSum = 0f
            var cnt = 0
            for (idx in range) {
                val b = blocks[idx]
                rmsSum += b.rmsDb
                for (d in 0 until 8) meanMel[d] += b.mel8[d]
                if (b.f0Hz in 75f..420f) f0List.add(b.f0Hz)
                cnt++
            }
            if (cnt == 0) return
            for (d in 0 until 8) meanMel[d] /= cnt.toFloat()
            val medF0 = if (f0List.isNotEmpty()) {
                f0List.sort()
                f0List[f0List.size / 2]
            } else 0f

            // Split very long homogeneous runs (> 22s) so long presentations produce multiple timeline anchors
            val maxIslandSpanMs = 20_000L
            if (endMs - startMs > maxIslandSpanMs + 4_000L) {
                var subStart = startMs
                while (subStart + 2_500L <= endMs) {
                    val subEnd = min(subStart + maxIslandSpanMs, endMs)
                    candidateIslands.add(
                        CandidateIsland(
                            startMs = subStart,
                            endMs = subEnd,
                            meanRmsDb = rmsSum / cnt,
                            medianF0Hz = medF0,
                            meanMel8 = meanMel,
                            trimmedFromChangePointStart = trimStart && subStart == startMs,
                            trimmedFromChangePointEnd = trimEnd && subEnd == endMs
                        )
                    )
                    subStart = subEnd
                }
            } else {
                candidateIslands.add(
                    CandidateIsland(
                        startMs = startMs,
                        endMs = endMs,
                        meanRmsDb = rmsSum / cnt,
                        medianF0Hz = medF0,
                        meanMel8 = meanMel,
                        trimmedFromChangePointStart = trimStart,
                        trimmedFromChangePointEnd = trimEnd
                    )
                )
            }
        }

        for (run in speechRuns) {
            val runLen = run.last - run.first + 1
            if (runLen < 5) continue // < 2.5s
            if (runLen < winHalf * 2 + 2) {
                appendIslandFromBlockRange(run, trimStart = false, trimEnd = false)
                continue
            }

            val scores = FloatArray(runLen)
            val validIndices = ArrayList<Int>(runLen)
            for (localIdx in winHalf..(runLen - winHalf - 1)) {
                val globalIdx = run.first + localIdx
                scores[localIdx] = computeChangeDistance(
                    (globalIdx - winHalf) until globalIdx,
                    globalIdx until (globalIdx + winHalf)
                )
                validIndices.add(localIdx)
            }
            if (validIndices.isEmpty()) {
                appendIslandFromBlockRange(run, trimStart = false, trimEnd = false)
                continue
            }

            val meanSc = validIndices.map { scores[it] }.average().toFloat()
            val stdSc = sqrt(validIndices.map { (scores[it] - meanSc) * (scores[it] - meanSc) }.average()).toFloat()
            val peakThreshold = max(meanSc + 1.25f * stdSc, 1.55f)

            val changeLocalIndices = mutableListOf<Int>()
            for (localIdx in winHalf..(runLen - winHalf - 1)) {
                val s = scores[localIdx]
                if (s >= peakThreshold &&
                    s >= scores[localIdx - 1] &&
                    s >= scores[localIdx + 1]
                ) {
                    val lastCp = changeLocalIndices.lastOrNull()
                    if (lastCp == null || localIdx - lastCp >= 5) { // >= 2.5s apart
                        changeLocalIndices.add(localIdx)
                    } else if (s > scores[lastCp]) {
                        changeLocalIndices[changeLocalIndices.lastIndex] = localIdx
                    }
                }
            }

            totalChangePoints += changeLocalIndices.size
            var segStart = run.first
            for (cpLocal in changeLocalIndices) {
                val cpGlobal = run.first + cpLocal
                if (cpGlobal - segStart >= 5) {
                    appendIslandFromBlockRange(
                        segStart until cpGlobal,
                        trimStart = segStart != run.first,
                        trimEnd = true
                    )
                }
                segStart = cpGlobal
            }
            if (run.last - segStart + 1 >= 5) {
                appendIslandFromBlockRange(
                    segStart..run.last,
                    trimStart = segStart != run.first,
                    trimEnd = false
                )
            }
        }

        if (candidateIslands.isEmpty()) {
            return SessionAcousticScanResult(emptyList(), emptyList(), totalChangePoints, totalScannedMs)
        }

        // Select up to maxSegments acoustically & temporally diverse islands (Farthest-Point + Quality Hybrid)
        val targetCap = maxSegments.coerceIn(4, 24)
        val chosenIslands: List<CandidateIsland> = if (candidateIslands.size <= targetCap) {
            candidateIslands
        } else {
            val ranked = candidateIslands.sortedByDescending { isl ->
                val durBonus = min(isl.durationMs, 10_000L) / 1000f * 2.5f
                val snrProxy = (isl.meanRmsDb - noiseFloorDb).coerceIn(0f, 35f)
                durBonus + snrProxy
            }
            val selected = mutableListOf(ranked.first())
            val remaining = ranked.drop(1).toMutableList()
            while (selected.size < targetCap && remaining.isNotEmpty()) {
                // Farthest-point selection in (Mel8 + log-F0) space ensures even brief unique speakers are picked!
                var bestIdx = 0
                var bestScore = -1f
                for (i in remaining.indices) {
                    val cand = remaining[i]
                    var minAcousticDist = Float.MAX_VALUE
                    for (sel in selected) {
                        var melSq = 0f
                        for (d in 0 until 8) {
                            val diff = cand.meanMel8[d] - sel.meanMel8[d]
                            melSq += diff * diff
                        }
                        val f0Dist = if (cand.medianF0Hz > 0f && sel.medianF0Hz > 0f) {
                            abs(ln(cand.medianF0Hz) - ln(sel.medianF0Hz)) * 4.5f
                        } else 0.5f
                        val dist = sqrt(melSq) + f0Dist
                        if (dist < minAcousticDist) minAcousticDist = dist
                    }
                    val snrProxy = (cand.meanRmsDb - noiseFloorDb).coerceIn(0f, 30f) / 30f
                    val composite = minAcousticDist + snrProxy * 0.65f
                    if (composite > bestScore) {
                        bestScore = composite
                        bestIdx = i
                    }
                }
                selected.add(remaining.removeAt(bestIdx))
            }
            selected.sortedBy { it.startMs }
        }

        // Stage 4: Extract full biometric embeddings for each selected homogeneous island
        val rawSegments = mutableListOf<AcousticSpeakerSegment>()
        val isWav = ClipSlicer.isWavFile(audioFile)
        val cacheDir = context?.cacheDir ?: audioFile.parentFile ?: return SessionAcousticScanResult(emptyList(), emptyList(), totalChangePoints, totalScannedMs)
        val tmpIsland = File(cacheDir, "vp_island_${System.nanoTime()}.aac")

        try {
            for (island in chosenIslands) {
                val coreSpanMs = min(island.durationMs, 7_500L)
                val coreStartMs = island.startMs + ((island.durationMs - coreSpanMs) / 2L).coerceAtLeast(0L)
                val coreEndMs = coreStartMs + coreSpanMs

                val (emb, metrics) = if (isWav) {
                    val pcm = ClipSlicer.readWavWindowToMono16kPcm(audioFile, coreStartMs, coreEndMs)
                    if (pcm.size < SAMPLE_RATE) continue
                    extractEmbeddingFromPcm(context, pcm) to ClipSlicer.computePcmQualityMetrics(pcm, SAMPLE_RATE, coreSpanMs)
                } else {
                    val sliced = ClipSlicer.sliceAdtsVoiceSample(
                        rawAdtsFile = audioFile,
                        outFile = tmpIsland,
                        startMs = coreStartMs,
                        endMs = coreEndMs,
                        minMs = 2_500L,
                        maxMs = 7_500L
                    ) ?: continue
                    val e = extractEmbeddingFromFile(context, sliced.file, maxSeconds = 8)
                    val m = ClipSlicer.analyzeAdtsSampleQuality(sliced.file)
                    e to m
                }

                if (emb.isNotEmpty()) {
                    // Reject severely overlapping/crosstalk islands so speaker cluster centroids stay pure
                    if (metrics.hasSuspectedCrosstalk && metrics.singleSpeakerPurity < 0.48f && chosenIslands.size > 2) {
                        continue
                    }
                    rawSegments.add(
                        AcousticSpeakerSegment(
                            startMs = island.startMs,
                            endMs = island.endMs,
                            durationMs = island.durationMs,
                            embedding = emb,
                            medianF0Hz = island.medianF0Hz,
                            snrDb = metrics.snrDb,
                            speechRatio = metrics.speechRatio,
                            singleSpeakerPurity = metrics.singleSpeakerPurity,
                            clusterId = -1
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Homogeneous island embedding extraction warning: ${e.message}")
        } finally {
            runCatching { tmpIsland.delete() }
        }

        if (rawSegments.isEmpty()) {
            return SessionAcousticScanResult(emptyList(), emptyList(), totalChangePoints, totalScannedMs)
        }

        val (clusteredSegments, clusters) = clusterSpeakerSegments(rawSegments)
        Log.i(
            TAG,
            "VAD+ChangePoint scan (${totalScannedMs / 1000L}s): $totalChangePoints turn boundary(ies), " +
                "${clusteredSegments.size} homogeneous island(s) -> ${clusters.size} acoustic speaker cluster(s)"
        )
        return SessionAcousticScanResult(
            segments = clusteredSegments,
            clusters = clusters,
            changePointCount = totalChangePoints,
            totalScannedMs = totalScannedMs
        )
    }

    /**
     * Complete-linkage agglomerative clustering on biometric speaker embeddings:
     * Never merges two clusters unless EVERY pairwise member passes [areEmbeddingsBiometricallyCompatible]
     * and the centroid [biometricSimilarity] exceeds [mergeThreshold].
     */
    fun clusterSpeakerSegments(
        segments: List<AcousticSpeakerSegment>,
        mergeThreshold: Float = 0.72f
    ): Pair<List<AcousticSpeakerSegment>, List<ClusteredSpeakerSummary>> {
        if (segments.isEmpty()) return emptyList<AcousticSpeakerSegment>() to emptyList()

        val activeClusters = segments.indices.map { idx -> mutableListOf(idx) }.toMutableList()

        while (activeClusters.size > 1) {
            var bestI = -1
            var bestJ = -1
            var bestSim = -1f

            for (i in 0 until activeClusters.size) {
                val cI = computeCentroid(activeClusters[i].map { segments[it].embedding })
                if (cI.isEmpty()) continue
                for (j in i + 1 until activeClusters.size) {
                    val cJ = computeCentroid(activeClusters[j].map { segments[it].embedding })
                    if (cJ.isEmpty()) continue
                    if (!areEmbeddingsBiometricallyCompatible(cI, cJ)) continue
                    val sim = biometricSimilarity(cI, cJ)
                    if (sim < mergeThreshold || sim <= bestSim) continue

                    // Complete-linkage guard: verify no two individual members across clusters contradict each other
                    var allCompatible = true
                    outer@ for (mI in activeClusters[i]) {
                        for (mJ in activeClusters[j]) {
                            if (!areEmbeddingsBiometricallyCompatible(segments[mI].embedding, segments[mJ].embedding)) {
                                allCompatible = false
                                break@outer
                            }
                        }
                    }
                    if (allCompatible) {
                        bestSim = sim
                        bestI = i
                        bestJ = j
                    }
                }
            }

            if (bestI < 0 || bestJ < 0) break
            activeClusters[bestI].addAll(activeClusters[bestJ])
            activeClusters.removeAt(bestJ)
        }

        // Sort clusters by total speaking duration descending so Cluster #1 is the primary talker
        val sortedGroups = activeClusters.sortedByDescending { group ->
            group.sumOf { segments[it].durationMs }
        }

        val clusterIdBySegIdx = IntArray(segments.size) { -1 }
        val clusterSummaries = mutableListOf<ClusteredSpeakerSummary>()

        sortedGroups.forEachIndexed { clusterIdx, segIndices ->
            val sortedIndices = segIndices.sorted()
            sortedIndices.forEach { clusterIdBySegIdx[it] = clusterIdx }
            val centroid = computeCentroid(sortedIndices.map { segments[it].embedding })
            val totalDurMs = sortedIndices.sumOf { segments[it].durationMs }
            val validF0s = sortedIndices.map { segments[it].medianF0Hz }.filter { it in 75f..420f }.sorted()
            val medF0 = if (validF0s.isNotEmpty()) validF0s[validF0s.size / 2] else 0f
            clusterSummaries.add(
                ClusteredSpeakerSummary(
                    clusterId = clusterIdx,
                    centroid = centroid,
                    segmentIndices = sortedIndices,
                    totalDurationMs = totalDurMs,
                    medianF0Hz = medF0,
                    descriptor = describeAcousticVoiceFromEmbedding(centroid)
                )
            )
        }

        val taggedSegments = segments.mapIndexed { idx, seg ->
            seg.copy(clusterId = clusterIdBySegIdx[idx])
        }
        return taggedSegments to clusterSummaries
    }

    /**
     * Extracts representative voiceprint windows from a raw session recording so we can locally
     * shortlist which stored speaker profiles appear in the recording BEFORE calling Gemini.
     *
     * Uses VAD + Change-Point Homogeneous Island Segmentation + On-Device Clustering first,
     * falling back to uniform grid sampling only if the streaming scan produces no segments.
     */
    fun extractWindowEmbeddingsFromSession(
        context: Context?,
        adtsFile: File,
        maxWindows: Int = 8
    ): List<List<Float>> {
        if (!adtsFile.exists() || adtsFile.length() < 8_192L) return emptyList()

        val scan = runCatching {
            extractHomogeneousSpeakerSegments(
                context = context,
                audioFile = adtsFile,
                maxSegments = max(maxWindows, 14)
            )
        }.getOrNull()

        if (scan != null && scan.segments.isNotEmpty()) {
            val combined = mutableListOf<List<Float>>()
            // Include every discovered speaker cluster's clean centroid first
            scan.clusters.forEach { cluster ->
                if (cluster.centroid.isNotEmpty()) combined.add(cluster.centroid)
            }
            // Plus individual high-SNR homogeneous islands for intra-speaker diversity
            scan.segments
                .sortedByDescending { it.snrDb + (min(it.durationMs, 8_000L) / 1000f) * 2f }
                .take(maxWindows)
                .forEach { seg ->
                    if (seg.embedding.isNotEmpty()) combined.add(seg.embedding)
                }
            if (combined.isNotEmpty()) return combined
        }

        // Uniform-grid fallback for unusual/ultra-short clips
        val totalDurMs = ClipSlicer.estimateAdtsDurationMs(adtsFile)
        if (totalDurMs < 2_500L) return emptyList()

        val windowMs = 6_000L
        val count = when {
            totalDurMs <= 12_000L -> 1
            totalDurMs <= 30_000L -> 3
            totalDurMs <= 90_000L -> 5
            else -> maxWindows.coerceIn(4, 10)
        }

        val results = mutableListOf<List<Float>>()
        val cacheDir = context?.cacheDir ?: adtsFile.parentFile ?: return emptyList()
        val tmpWindow = File(cacheDir, "vp_scan_${System.nanoTime()}.aac")

        try {
            val stepMs = if (count <= 1) 0L else ((totalDurMs - windowMs).coerceAtLeast(1_000L) / (count - 1))
            for (i in 0 until count) {
                val startMs = (i * stepMs).coerceAtLeast(0L)
                val endMs = (startMs + windowMs).coerceAtMost(totalDurMs)
                if (endMs - startMs < 2_200L) continue

                val sliced = ClipSlicer.sliceAdtsVoiceSample(
                    rawAdtsFile = adtsFile,
                    outFile = tmpWindow,
                    startMs = startMs,
                    endMs = endMs,
                    minMs = 3_000L,
                    maxMs = 7_000L
                ) ?: continue

                val emb = extractEmbeddingFromFile(context, sliced.file, maxSeconds = 7)
                if (emb.isNotEmpty()) {
                    results.add(emb)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Session window voiceprint scan warning: ${e.message}")
        } finally {
            runCatching { tmpWindow.delete() }
        }
        return results
    }

    /**
     * Scores each roster speaker against a list of session window/cluster voiceprints using
     * [biometricSimilarity] (penalizing pitch-register & formant mismatches).
     * Returns `Map<profileId, maxBiometricSimilarity>` for speakers exceeding a mild floor.
     */
    fun scoreRosterAgainstSessionWindows(
        roster: List<SpeakerProfileEntry>,
        sessionWindowEmbeddings: List<List<Float>>
    ): Map<String, Float> {
        if (roster.isEmpty() || sessionWindowEmbeddings.isEmpty()) return emptyMap()
        val scores = HashMap<String, Float>()
        for (entry in roster) {
            val centroid = entry.voiceprintCentroid()
            if (centroid.isEmpty()) continue
            var bestSim = -1f
            for (winEmb in sessionWindowEmbeddings) {
                if (winEmb.isEmpty()) continue
                val sim = biometricSimilarity(centroid, winEmb)
                if (sim > bestSim) bestSim = sim
            }
            // Also check individual stored samples in case speaker has diverse emotional/phone samples
            for (s in entry.existingSamples()) {
                if (s.embedding.isEmpty()) continue
                for (winEmb in sessionWindowEmbeddings) {
                    if (winEmb.isEmpty()) continue
                    val sim = biometricSimilarity(s.embedding, winEmb) * 0.97f
                    if (sim > bestSim) bestSim = sim
                }
            }
            if (bestSim > 0.38f) {
                scores[entry.id] = bestSim
            }
        }
        return scores
    }

    private fun isAcousticEmbeddingDim(dim: Int): Boolean {
        return dim == EMBEDDING_DIM || dim == LEGACY_EMBEDDING_DIM
    }

    /**
     * Aligns two embeddings for comparison:
     * - If both have identical dimensions, returns them as-is.
     * - If one is a 64-D v2 acoustic voiceprint and the other is a legacy 48-D voiceprint,
     *   projects both onto their shared L2-normalized `[0..47]` acoustic core so legacy saved
     *   profiles remain 100% compatible with upgraded 64-D scans.
     */
    private fun alignAcousticPair(a: List<Float>, b: List<Float>): Pair<List<Float>, List<Float>>? {
        if (a.isEmpty() || b.isEmpty()) return null
        if (a.size == b.size) return a to b
        if (isAcousticEmbeddingDim(a.size) && isAcousticEmbeddingDim(b.size)) {
            val coreA = l2Normalize(FloatArray(LEGACY_EMBEDDING_DIM) { idx -> a[idx] })
            val coreB = l2Normalize(FloatArray(LEGACY_EMBEDDING_DIM) { idx -> b[idx] })
            if (coreA.size == LEGACY_EMBEDDING_DIM && coreB.size == LEGACY_EMBEDDING_DIM) {
                return coreA to coreB
            }
        }
        return null
    }

    /**
     * Computes speaker similarity while penalizing mismatches in:
     *  - Vocal-tract MFCC timbre (`[0..15]`)
     *  - Fundamental pitch register (`[40, 42, 43]`)
     *  - Vocal-tract Formant resonances F1/F2/F3 & ratios (`[48..55]` on 64-D v2 vectors)
     *  - Glottal Harmonic-to-Noise Ratio & Jitter/Shimmer (`[56..59]` on 64-D v2 vectors)
     * This prevents two different speakers in the same acoustic ward room from being falsely combined.
     */
    fun biometricSimilarity(a: List<Float>, b: List<Float>): Float {
        val (va, vb) = alignAcousticPair(a, b) ?: return 0f
        val fullSim = cosineSimilarity(va, vb)
        if (!isAcousticEmbeddingDim(va.size)) return fullSim

        val timbreSim = cosineSimilarity(va.subList(0, NUM_MFCC), vb.subList(0, NUM_MFCC))
        val pitchDiff = (abs(va[40] - vb[40]) + abs(va[42] - vb[42]) + abs(va[43] - vb[43])) / 3f
        val pitchPenalty = if (pitchDiff > 0.060f) (pitchDiff - 0.060f) * 1.70f else 0f
        val timbrePenalty = if (timbreSim < 0.48f) (0.48f - timbreSim) * 0.55f else 0f

        var formantPenalty = 0f
        var glottalPenalty = 0f
        if (va.size == EMBEDDING_DIM && vb.size == EMBEDDING_DIM) {
            val formantSim = cosineSimilarity(va.subList(48, 56), vb.subList(48, 56))
            if (formantSim < 0.62f) {
                formantPenalty = (0.62f - formantSim) * 0.42f
            }
            val hnrDiff = abs(va[56] - vb[56])
            if (hnrDiff > 0.085f) {
                glottalPenalty = (hnrDiff - 0.085f) * 0.75f
            }
        }
        return (fullSim - pitchPenalty - timbrePenalty - formantPenalty - glottalPenalty).coerceIn(-1f, 1f)
    }

    /**
     * Checks that two voiceprints do not exhibit a fundamental pitch register, vocal-tract
     * formant (F1/F2/F3), or MFCC timbre contradiction that would indicate two distinct human speakers.
     */
    fun areEmbeddingsBiometricallyCompatible(a: List<Float>, b: List<Float>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return true
        val (va, vb) = alignAcousticPair(a, b) ?: return true
        if (!isAcousticEmbeddingDim(va.size)) return cosineSimilarity(va, vb) >= VOICEPRINT_CONTRADICTION_THRESHOLD

        val timbreSim = cosineSimilarity(va.subList(0, NUM_MFCC), vb.subList(0, NUM_MFCC))
        val pitchDiff = (abs(va[40] - vb[40]) + abs(va[42] - vb[42]) + abs(va[43] - vb[43])) / 3f
        if (pitchDiff > 0.140f) return false
        if (timbreSim < 0.35f) return false
        if (va.size == EMBEDDING_DIM && vb.size == EMBEDDING_DIM) {
            val formantSim = cosineSimilarity(va.subList(48, 56), vb.subList(48, 56))
            if (formantSim < 0.42f) return false
        }
        return biometricSimilarity(va, vb) >= VOICEPRINT_CONTRADICTION_THRESHOLD
    }

    /**
     * Derives an objective acoustic voice descriptor (pitch register, vocal timbre, cadence, and
     * recommended nickname prefix) from a 64-D v2 or 48-D legacy voiceprint vector so we can recheck
     * whether a profile's Name, Title, and Description are suitable for its actual voice samples.
     */
    fun describeAcousticVoiceFromEmbedding(embedding: List<Float>): AcousticVoiceDescriptor? {
        if (!isAcousticEmbeddingDim(embedding.size)) return null
        // When 64-D, rescale the [0..47] prosodic slice to match the L2 energy normalization of 48-D thresholds
        val core = if (embedding.size == EMBEDDING_DIM) {
            l2Normalize(FloatArray(LEGACY_EMBEDDING_DIM) { idx -> embedding[idx] })
        } else {
            embedding
        }
        if (core.size != LEGACY_EMBEDDING_DIM) return null

        val pitchScore = ((core[40] / 1.8f) + (core[42] / 1.4f) + (core[43] / 1.4f)) / 3f
        val centroidScore = (core[44] / 1.3f)
        val cadenceScore = core.subList(32, 40).map { abs(it) }.average().toFloat()

        // Use HNR voice clarity marker when 64-D v2 embedding is available
        val hasWarmResonance = embedding.size == EMBEDDING_DIM && embedding[56] > 0.09f && centroidScore < 0.072f
        val hasCrispClarity = embedding.size == EMBEDDING_DIM && embedding[56] > 0.085f && centroidScore >= 0.088f

        val (pitchCat, pitchLabel, adjPrefix) = when {
            pitchScore < 0.068f -> Triple("LOW", "Low-baritone register", "Deep-Tone ")
            pitchScore < 0.095f -> Triple("MID_LOW", "Mid-low warm register", "Warm-Tone ")
            pitchScore < 0.125f -> Triple("MID", "Balanced mid-range register", "Clear-Tone ")
            pitchScore < 0.155f -> Triple("MID_HIGH", "Clear mid-high register", "Crisp-Tone ")
            else -> Triple("HIGH", "Bright higher-pitched register", "Crisp-Tone ")
        }
        val timbreLabel = when {
            hasWarmResonance -> "warm resonant harmonic timbre"
            hasCrispClarity -> "crisp clear harmonic timbre"
            centroidScore < 0.055f -> "warm resonant timbre"
            centroidScore < 0.088f -> "clear balanced timbre"
            else -> "crisp articulate timbre"
        }
        val (cadenceLabel, finalPrefix) = when {
            cadenceScore > 0.055f -> "brisk dynamic cadence" to (if (pitchCat == "MID") "Brisk-Voice " else adjPrefix)
            cadenceScore < 0.026f -> "calm deliberate pace" to (if (pitchCat == "MID") "Calm-Pace " else adjPrefix)
            else -> "steady measured cadence" to (if (pitchCat == "MID") "Steady-Voice " else adjPrefix)
        }
        return AcousticVoiceDescriptor(
            pitchCategory = pitchCat,
            pitchRegisterLabel = pitchLabel,
            timbreLabel = timbreLabel,
            cadenceLabel = cadenceLabel,
            recommendedAdjectivePrefix = finalPrefix
        )
    }

    /**
     * Scores a candidate embedding against a profile's existing embeddings by taking the better of
     * centroid biometric similarity and slightly damped max per-sample biometric similarity.
     * Supports comparing 64-D v2 and legacy 48-D vectors transparently.
     */
    fun scoreEmbeddingAgainstEmbeddings(
        candidateEmbedding: List<Float>,
        existingEmbeddings: List<List<Float>>
    ): Float? {
        val compatible = existingEmbeddings.filter { alignAcousticPair(candidateEmbedding, it) != null }
        if (candidateEmbedding.isEmpty() || compatible.isEmpty()) return null
        val centroid = computeCentroid(compatible)
        val centroidSim = if (alignAcousticPair(candidateEmbedding, centroid) != null) {
            biometricSimilarity(candidateEmbedding, centroid)
        } else 0f
        val maxSampleSim = compatible.maxOfOrNull { biometricSimilarity(candidateEmbedding, it) } ?: 0f
        return max(centroidSim, maxSampleSim * 0.96f)
    }

    /**
     * Anti-contamination gate: checks whether a newly auto-sliced sample's voiceprint is
     * consistent with an existing profile's stored sample voiceprints and does not exhibit
     * pitch/formant speaker contradiction.
     * Returns `Pair(isConsistent, similarityScore)`.
     */
    fun isConsistentWithProfile(
        candidateEmbedding: List<Float>,
        existingEmbeddings: List<List<Float>>,
        threshold: Float = CONSISTENCY_THRESHOLD
    ): Pair<Boolean, Float> {
        val compatible = existingEmbeddings.filter { alignAcousticPair(candidateEmbedding, it) != null }
        if (candidateEmbedding.isEmpty() || compatible.isEmpty()) return true to 1.0f
        val centroid = computeCentroid(compatible)
        if (centroid.isNotEmpty() && !areEmbeddingsBiometricallyCompatible(candidateEmbedding, centroid)) {
            val rawSim = biometricSimilarity(candidateEmbedding, centroid)
            return false to rawSim
        }
        val effectiveSim = scoreEmbeddingAgainstEmbeddings(candidateEmbedding, compatible)
            ?: return true to 1.0f
        return (effectiveSim >= threshold) to effectiveSim
    }

    /**
     * Matches [candidateEmbedding] against both "My Voice" ([myVoiceSamples]) and every profile in
     * [roster] (skipping [excludedProfileIds]), requiring BOTH:
     *  1) similarity >= threshold ([minSelfSimilarity] for "me", [minRosterSimilarity] for roster)
     *  2) clear separation over the second-best candidate (`margin >= minMargin`) so ambiguous voices
     *     are never falsely merged.
     */
    fun findBestVoiceprintMatch(
        candidateEmbedding: List<Float>,
        myVoiceSamples: List<VoiceProfileSample>,
        roster: List<SpeakerProfileEntry>,
        excludedProfileIds: Set<String> = emptySet(),
        minSelfSimilarity: Float = VOICEPRINT_SELF_MATCH_THRESHOLD,
        minRosterSimilarity: Float = VOICEPRINT_ROSTER_MATCH_THRESHOLD,
        minMargin: Float = VOICEPRINT_MIN_MARGIN
    ): VoiceprintMatchResult? {
        if (candidateEmbedding.isEmpty()) return null

        val scoredCandidates = mutableListOf<Triple<String, Boolean, Float>>()

        if ("me" !in excludedProfileIds) {
            val myEmbs = myVoiceSamples.map { it.embedding }.filter { it.isNotEmpty() }
            val myCentroid = computeCentroid(myEmbs)
            if (myCentroid.isNotEmpty() && areEmbeddingsBiometricallyCompatible(candidateEmbedding, myCentroid)) {
                val myScore = scoreEmbeddingAgainstEmbeddings(candidateEmbedding, myEmbs)
                if (myScore != null) {
                    scoredCandidates.add(Triple("me", true, myScore))
                }
            }
        }

        for (entry in roster) {
            if (entry.id in excludedProfileIds) continue
            val embs = entry.existingSamples().map { it.embedding }.filter { it.isNotEmpty() }
            val centroid = computeCentroid(embs)
            if (centroid.isEmpty() || !areEmbeddingsBiometricallyCompatible(candidateEmbedding, centroid)) continue
            val score = scoreEmbeddingAgainstEmbeddings(candidateEmbedding, embs) ?: continue
            scoredCandidates.add(Triple(entry.id, false, score))
        }

        if (scoredCandidates.isEmpty()) return null
        scoredCandidates.sortByDescending { it.third }

        val best = scoredCandidates[0]
        val secondScore = scoredCandidates.getOrNull(1)?.third ?: 0f
        val margin = best.third - secondScore
        val requiredThreshold = if (best.second) minSelfSimilarity else minRosterSimilarity

        if (best.third < requiredThreshold) return null
        if (scoredCandidates.size > 1 && margin < minMargin) return null

        return VoiceprintMatchResult(
            profileId = best.first,
            isSelfUser = best.second,
            similarity = best.third,
            marginOverSecond = margin
        )
    }

    /**
     * Detects pairs of distinct Speaker Roster profiles whose voiceprint centroids strongly match
     * AND are biometrically compatible in pitch register and vocal tract formants, allowing 1-tap merge.
     */
    fun findPossibleDuplicatePairs(
        roster: List<SpeakerProfileEntry>,
        threshold: Float = DUPLICATE_MERGE_THRESHOLD
    ): List<DuplicateProfileSuggestion> {
        val withCentroids = roster.mapNotNull { entry ->
            val c = entry.voiceprintCentroid()
            if (c.isNotEmpty()) entry to c else null
        }
        if (withCentroids.size < 2) return emptyList()

        val suggestions = mutableListOf<DuplicateProfileSuggestion>()
        for (i in withCentroids.indices) {
            for (j in (i + 1) until withCentroids.size) {
                val (a, ca) = withCentroids[i]
                val (b, cb) = withCentroids[j]
                if (alignAcousticPair(ca, cb) == null) continue
                if (!areEmbeddingsBiometricallyCompatible(ca, cb)) continue
                val sim = biometricSimilarity(ca, cb)
                if (sim >= threshold) {
                    // Pick stronger / pinned / manual / more-heard profile as primary
                    val aRank = (if (a.isPinned) 100 else 0) +
                        (if (!a.isAutoDiscovered) 40 else 0) +
                        a.sessionsHeardCount * 5 +
                        a.existingSamples().size * 3
                    val bRank = (if (b.isPinned) 100 else 0) +
                        (if (!b.isAutoDiscovered) 40 else 0) +
                        b.sessionsHeardCount * 5 +
                        b.existingSamples().size * 3
                    val (primary, dup) = if (aRank >= bRank) a to b else b to a
                    suggestions.add(DuplicateProfileSuggestion(primary, dup, sim))
                }
            }
        }
        return suggestions.sortedByDescending { it.similarity }.take(4)
    }

    /**
     * Partitions a profile's samples into `Pair(consistentSamples, outlierSamples)` using the
     * dominant/highest-quality medoid voiceprint as anchor. Any sample whose voiceprint contradicts
     * the anchor (different fundamental pitch register or vocal-tract formant timbre) is returned
     * in `outlierSamples` so different speakers are never combined in the same profile.
     */
    fun partitionSamplesByDominantSpeaker(
        samples: List<VoiceProfileSample>,
        minSimilarity: Float = VOICEPRINT_CONTRADICTION_THRESHOLD
    ): Pair<List<VoiceProfileSample>, List<VoiceProfileSample>> {
        val withEmb = samples.filter { it.embedding.isNotEmpty() }
        if (samples.size <= 1 || withEmb.size <= 1) {
            return samples to emptyList()
        }

        // Choose anchor: prioritize manual sample if present, otherwise medoid with highest quality + cluster agreement
        val anchor = withEmb.firstOrNull { it.isManual }
            ?: withEmb.maxByOrNull { cand ->
                val peerAgreement = withEmb.sumOf { other ->
                    if (other.id == cand.id || alignAcousticPair(cand.embedding, other.embedding) == null) 0.0
                    else max(0f, biometricSimilarity(cand.embedding, other.embedding)).toDouble() * 22.0
                }
                cand.qualityScore.toDouble() + peerAgreement
            }
            ?: return samples to emptyList()

        val consistent = mutableListOf<VoiceProfileSample>()
        val outliers = mutableListOf<VoiceProfileSample>()
        for (s in samples) {
            if (s.id == anchor.id || s.embedding.isEmpty() || alignAcousticPair(anchor.embedding, s.embedding) == null) {
                consistent.add(s)
                continue
            }
            val sim = biometricSimilarity(anchor.embedding, s.embedding)
            val compatible = areEmbeddingsBiometricallyCompatible(anchor.embedding, s.embedding)
            if (compatible && sim >= minSimilarity) {
                consistent.add(s)
            } else {
                outliers.add(s)
            }
        }
        return consistent to outliers
    }

    fun computeCentroid(embeddings: List<List<Float>>): List<Float> {
        val nonEmpty = embeddings.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return emptyList()
        // Prefer upgraded 64-D embeddings when present alongside legacy 48-D ones
        val dim = nonEmpty.maxOf { it.size }
        val compatible = nonEmpty.filter { it.size == dim }
        if (compatible.isEmpty()) return emptyList()

        val sum = FloatArray(dim)
        for (vec in compatible) {
            for (i in 0 until dim) {
                sum[i] += vec[i]
            }
        }
        return l2Normalize(sum)
    }

    fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom < 1e-9) return 0f
        return (dot / denom).toFloat().coerceIn(-1f, 1f)
    }

    /**
     * Optional ONNX speaker encoder hook (uses existing `onnxruntime-android` runtime if a user
     * or future release bundles `speaker_embedding.onnx` expecting raw `[1, N]` 16 kHz waveform).
     */
    private fun tryExtractOnnxEmbedding(context: Context, pcm: ShortArray): List<Float>? {
        ensureOnnxLoaded(context)
        val session = onnxSession ?: return null
        val inName = onnxInputName ?: return null
        if (onnxInputRank != 2) return null // Waveform encoder expects [1, N]

        return try {
            val env = OrtEnvironment.getEnvironment()
            val maxSamples = min(pcm.size, SAMPLE_RATE * 10)
            val floats = FloatArray(maxSamples) { i -> pcm[i] / 32768f }
            val tensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(floats),
                longArrayOf(1L, maxSamples.toLong())
            )
            tensor.use { inputTensor ->
                session.run(mapOf(inName to inputTensor)).use { result ->
                    val rawVal = result[0].value
                    val vec: FloatArray? = when (rawVal) {
                        is Array<*> -> (rawVal.firstOrNull() as? FloatArray)
                        is FloatArray -> rawVal
                        else -> null
                    }
                    if (vec != null && vec.size >= 32) l2Normalize(vec) else null
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ONNX speaker encoder inference failed; using acoustic voiceprint: ${t.message}")
            null
        }
    }

    private fun ensureOnnxLoaded(context: Context) {
        if (onnxChecked) return
        synchronized(this) {
            if (onnxChecked) return
            onnxChecked = true
            try {
                val customFile = File(File(context.filesDir, "models"), ONNX_MODEL_NAME)
                val modelBytes: ByteArray? = when {
                    customFile.exists() && customFile.length() > 100_000L -> customFile.readBytes()
                    context.assets.list("")?.contains(ONNX_MODEL_NAME) == true ->
                        context.assets.open(ONNX_MODEL_NAME).use { it.readBytes() }
                    else -> null
                }
                if (modelBytes != null) {
                    val env = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(2)
                        setInterOpNumThreads(1)
                    }
                    val sess = env.createSession(modelBytes, opts)
                    val firstInput = sess.inputInfo.entries.firstOrNull()
                    val shape = (firstInput?.value?.info as? ai.onnxruntime.TensorInfo)?.shape
                    onnxSession = sess
                    onnxInputName = firstInput?.key
                    onnxInputRank = shape?.size ?: 0
                    Log.i(TAG, "Loaded optional ONNX speaker encoder ($ONNX_MODEL_NAME, rank=$onnxInputRank)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Optional ONNX speaker encoder unavailable: ${t.message}")
                onnxSession = null
            }
        }
    }

    /**
     * Task 2 — Extracts a deterministic 64-D v2 L2-normalized acoustic speaker voiceprint from
     * 16 kHz mono PCM using room-robust DSP:
     *  - Step 1: Minimum-Statistics Noise PSD estimation across the quietest background frames.
     *  - Step 2: Oversubtracted Spectral Subtraction (`max(P - 1.35*N, 0.08*P)`) before Mel/MFCC.
     *  - Step 3: RASTA-style temporal bandpass / CMVN on dynamic MFCC variance & Δ-MFCCs.
     *  - Step 4: Vocal-tract Formant resonance peaks (F1, F2, F3 + F2/F1, F3/F2 ratios) via
     *            envelope-smoothed parabolic peak picking, plus Harmonic-to-Noise Ratio (HNR),
     *            cycle-to-cycle Pitch Jitter, Amplitude Shimmer, and vocal subband energy ratios.
     *
     * Vector Layout (64-D; `[0..47]` remains aligned with legacy 48-D voiceprints):
     *  - `[0..15]`:  Spectral-subtracted liftered Mean MFCCs c1..c16 (vocal-tract timbre)
     *  - `[16..31]`: RASTA-CMVN StdDev MFCCs c1..c16 (channel-invariant articulation variance)
     *  - `[32..39]`: RASTA-CMVN Mean abs Δ-MFCCs c1..c8 (speaking cadence & formant dynamics)
     *  - `[40..47]`: Pitch F0 (mean, std, Q25, Q75), spectral centroid, rolloff, flatness, ZCR
     *  - `[48..53]`: Formants F1 (mean, std), F2 (mean, std), F3 (mean, std)
     *  - `[54..55]`: Vocal-tract length ratios F2/F1 and F3/F2
     *  - `[56..59]`: Glottal quality: HNR mean, HNR std, Pitch Jitter, Amplitude Shimmer
     *  - `[60..63]`: Clean vocal subband energy ratios (85-500, 500-2000, 2000-4500 Hz) + voiced ratio
     */
    internal fun extractAcousticEmbedding(pcm: ShortArray): List<Float> {
        if (pcm.size < FRAME_LEN + FRAME_HOP * MIN_VOICED_FRAMES) return emptyList()

        val numFrames = ((pcm.size - FRAME_LEN) / FRAME_HOP) + 1
        if (numFrames < MIN_VOICED_FRAMES) return emptyList()

        val bins = FFT_SIZE / 2 + 1
        val binHz = SAMPLE_RATE.toDouble() / FFT_SIZE.toDouble() // 31.25 Hz per bin

        // Step 1: Compute frame log-energies and RMS amplitudes for adaptive VAD + Noise PSD estimation
        val frameRmsDb = FloatArray(numFrames)
        val frameRmsLinear = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            val offset = f * FRAME_HOP
            var sumSq = 0.0
            for (i in 0 until FRAME_LEN) {
                val s = pcm[offset + i] / 32768.0
                sumSq += s * s
            }
            val rms = sqrt(sumSq / FRAME_LEN)
            frameRmsLinear[f] = rms.toFloat()
            frameRmsDb[f] = (20.0 * log10(rms + 1e-6)).toFloat()
        }
        val sortedDb = frameRmsDb.sortedArray()
        val noiseFloorDb = sortedDb[(sortedDb.size * 0.20).toInt().coerceIn(0, sortedDb.lastIndex)]
        val speechPeakDb = sortedDb[(sortedDb.size * 0.90).toInt().coerceIn(0, sortedDb.lastIndex)]
        val energyGateDb = max(noiseFloorDb + 6.5f, min(speechPeakDb - 14f, -42f))

        // Estimate stationary background noise PSD (hospital AC/fan/monitor floor) from quietest 15-20% frames
        val noisePsd = FloatArray(bins)
        val noiseFrameIndices = (0 until numFrames)
            .filter { frameRmsDb[it] <= noiseFloorDb + 2.5f }
            .let { quiet ->
                if (quiet.size <= 40) quiet
                else {
                    val step = max(1, quiet.size / 40)
                    quiet.filterIndexed { idx, _ -> idx % step == 0 }.take(40)
                }
            }

        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        if (noiseFrameIndices.size >= 4 && (speechPeakDb - noiseFloorDb) >= 5.0f) {
            for (nf in noiseFrameIndices) {
                val offset = nf * FRAME_HOP
                var mean = 0f
                for (i in 0 until FRAME_LEN) mean += pcm[offset + i] / 32768f
                mean /= FRAME_LEN
                var prevRaw = 0f
                for (i in 0 until FRAME_LEN) {
                    val cur = (pcm[offset + i] / 32768f) - mean
                    val pre = if (i == 0) cur else cur - 0.97f * prevRaw
                    re[i] = pre * hammingWindow[i]
                    im[i] = 0f
                    prevRaw = cur
                }
                for (i in FRAME_LEN until FFT_SIZE) {
                    re[i] = 0f
                    im[i] = 0f
                }
                fftInPlace(re, im)
                for (b in 0 until bins) {
                    noisePsd[b] += re[b] * re[b] + im[b] * im[b]
                }
            }
            val invN = 1f / noiseFrameIndices.size.toFloat()
            for (b in 0 until bins) noisePsd[b] *= invN
        }

        // Step 2: Process active voiced speech frames with Spectral Subtraction + Formants + HNR/Jitter/Shimmer
        val mfccFrames = ArrayList<FloatArray>(numFrames / 2)
        val pitchValues = ArrayList<Float>(numFrames / 2)
        val hnrValues = ArrayList<Float>(numFrames / 2)
        val f1Values = ArrayList<Float>(numFrames / 2)
        val f2Values = ArrayList<Float>(numFrames / 2)
        val f3Values = ArrayList<Float>(numFrames / 2)
        val centroids = ArrayList<Float>(numFrames / 2)
        val rolloffs = ArrayList<Float>(numFrames / 2)
        val flatnesses = ArrayList<Float>(numFrames / 2)
        val zcrs = ArrayList<Float>(numFrames / 2)

        val powerSpec = FloatArray(bins)
        val logEnv = FloatArray(bins)
        val smoothEnv = FloatArray(bins)
        val melEnergies = FloatArray(NUM_MEL_BINS)
        val rawFrame = FloatArray(FRAME_LEN)

        val minLag = SAMPLE_RATE / 420 // ~38 samples (420 Hz upper F0)
        val maxLag = SAMPLE_RATE / 75  // ~213 samples (75 Hz lower F0)

        var activeSpeechFrameCount = 0
        var voicedFrameCount = 0
        var lowSubbandSum = 0.0
        var midSubbandSum = 0.0
        var highSubbandSum = 0.0

        // Cycle-to-cycle Jitter & Shimmer accumulators across consecutive voiced frames
        var prevVoicedFrameIdx = -10
        var prevPeriodSec = 0.0
        var prevVoicedRms = 0.0
        var jitterAbsDiffSum = 0.0
        var jitterPeriodSum = 0.0
        var jitterCount = 0
        var shimmerAbsDiffSum = 0.0
        var shimmerAmpSum = 0.0
        var shimmerCount = 0

        fun findParabolicPeakHz(env: FloatArray, minHz: Double, maxHz: Double): Float? {
            val lo = (minHz / binHz).toInt().coerceIn(2, bins - 3)
            val hi = (maxHz / binHz).toInt().coerceIn(lo, bins - 3)
            var bestBin = -1
            var bestVal = -Float.MAX_VALUE
            for (b in lo..hi) {
                val v = env[b]
                if (v >= env[b - 1] && v >= env[b + 1] && v > bestVal) {
                    bestVal = v
                    bestBin = b
                }
            }
            if (bestBin < 0) return null
            // Parabolic 3-point interpolation around peak bin for sub-bin formant precision
            val y0 = env[bestBin - 1].toDouble()
            val y1 = env[bestBin].toDouble()
            val y2 = env[bestBin + 1].toDouble()
            val denom = (y0 - 2.0 * y1 + y2)
            val delta = if (abs(denom) > 1e-6) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
            return ((bestBin + delta) * binHz).toFloat()
        }

        for (f in 0 until numFrames) {
            if (frameRmsDb[f] < energyGateDb) continue
            activeSpeechFrameCount++
            val offset = f * FRAME_HOP

            // Remove DC, apply pre-emphasis (0.97) for spectral analysis, keep raw for pitch autocorr
            var mean = 0f
            for (i in 0 until FRAME_LEN) mean += pcm[offset + i] / 32768f
            mean /= FRAME_LEN

            var zeroCross = 0
            var prevRaw = 0f
            for (i in 0 until FRAME_LEN) {
                val cur = (pcm[offset + i] / 32768f) - mean
                rawFrame[i] = cur
                if (i > 0 && ((prevRaw >= 0f && cur < 0f) || (prevRaw < 0f && cur >= 0f))) {
                    zeroCross++
                }
                val pre = if (i == 0) cur else cur - 0.97f * prevRaw
                re[i] = pre * hammingWindow[i]
                im[i] = 0f
                prevRaw = cur
            }
            for (i in FRAME_LEN until FFT_SIZE) {
                re[i] = 0f
                im[i] = 0f
            }

            // Autocorrelation voicing, pitch F0, HNR, Jitter, and Shimmer
            var r0 = 0.0
            for (i in 0 until FRAME_LEN) r0 += rawFrame[i] * rawFrame[i]
            if (r0 < 1e-7) continue

            var bestLag = -1
            var bestCorr = 0.0
            for (lag in minLag..min(maxLag, FRAME_LEN - 1)) {
                var acc = 0.0
                val limit = FRAME_LEN - lag
                var idx = 0
                while (idx < limit) {
                    acc += rawFrame[idx] * rawFrame[idx + lag]
                    idx++
                }
                val normCorr = acc / r0
                if (normCorr > bestCorr) {
                    bestCorr = normCorr
                    bestLag = lag
                }
            }

            val zcr = zeroCross.toFloat() / (FRAME_LEN - 1)
            val isVoiced = bestCorr >= 0.28 && zcr < 0.28f
            if (!isVoiced && zcr > 0.35f) continue
            if (isVoiced) voicedFrameCount++

            var isStronglyVoiced = false
            if (bestCorr >= 0.32 && bestLag > 0) {
                val f0Hz = SAMPLE_RATE.toFloat() / bestLag.toFloat()
                if (f0Hz in 75f..420f) {
                    isStronglyVoiced = true
                    pitchValues.add(f0Hz)

                    val clampedR = bestCorr.coerceIn(0.05, 0.992)
                    val hnrDb = (10.0 * log10(clampedR / (1.0 - clampedR))).toFloat().coerceIn(-5f, 28f)
                    hnrValues.add(hnrDb)

                    val periodSec = 1.0 / f0Hz.toDouble()
                    val curAmp = frameRmsLinear[f].toDouble()
                    if (f - prevVoicedFrameIdx <= 2 && prevPeriodSec > 0.0 && prevVoicedRms > 1e-5) {
                        val relPeriodJump = abs(periodSec - prevPeriodSec) / prevPeriodSec
                        if (relPeriodJump < 0.25) {
                            jitterAbsDiffSum += abs(periodSec - prevPeriodSec)
                            jitterPeriodSum += periodSec
                            jitterCount++
                        }
                        val relAmpJump = abs(curAmp - prevVoicedRms) / prevVoicedRms
                        if (relAmpJump < 0.50) {
                            shimmerAbsDiffSum += abs(curAmp - prevVoicedRms)
                            shimmerAmpSum += curAmp
                            shimmerCount++
                        }
                    }
                    prevVoicedFrameIdx = f
                    prevPeriodSec = periodSec
                    prevVoicedRms = curAmp
                }
            }

            // Radix-2 FFT -> Minimum-Statistics Spectral Subtraction -> Clean Power Spectrum
            fftInPlace(re, im)
            var totalPower = 1e-12
            var weightedFreqSum = 0.0
            var logPowerSum = 0.0
            var lowBandP = 0.0
            var midBandP = 0.0
            var highBandP = 0.0

            for (b in 0 until bins) {
                val rawP = (re[b] * re[b] + im[b] * im[b]).toDouble()
                val cleanP = max(rawP - 1.35 * noisePsd[b].toDouble(), 0.08 * rawP)
                powerSpec[b] = cleanP.toFloat()
                logEnv[b] = ln(cleanP + 1e-10).toFloat()
                totalPower += cleanP
                val freqHz = b * binHz
                weightedFreqSum += freqHz * cleanP
                logPowerSum += ln(cleanP + 1e-12)
                when {
                    freqHz in 85.0..500.0 -> lowBandP += cleanP
                    freqHz > 500.0 && freqHz <= 2000.0 -> midBandP += cleanP
                    freqHz > 2000.0 && freqHz <= 4500.0 -> highBandP += cleanP
                }
            }

            val vocalBandTotal = (lowBandP + midBandP + highBandP).coerceAtLeast(1e-12)
            lowSubbandSum += lowBandP / vocalBandTotal
            midSubbandSum += midBandP / vocalBandTotal
            highSubbandSum += highBandP / vocalBandTotal

            // Formant peak extraction (F1, F2, F3) on strongly voiced frames via 7-bin vocal-tract envelope
            if (isStronglyVoiced) {
                for (b in 0 until bins) {
                    var sum = 0f
                    var cnt = 0
                    val lo = max(0, b - 3)
                    val hi = min(bins - 1, b + 3)
                    for (j in lo..hi) {
                        sum += logEnv[j]
                        cnt++
                    }
                    smoothEnv[b] = sum / cnt.toFloat()
                }
                val f1 = findParabolicPeakHz(smoothEnv, 250.0, 900.0)
                if (f1 != null) {
                    f1Values.add(f1)
                    val f2Min = max(f1.toDouble() + 260.0, 880.0)
                    val f2 = findParabolicPeakHz(smoothEnv, f2Min, 2600.0)
                    if (f2 != null) {
                        f2Values.add(f2)
                        val f3Min = max(f2.toDouble() + 350.0, 2100.0)
                        val f3 = findParabolicPeakHz(smoothEnv, f3Min, 3900.0)
                        if (f3 != null) {
                            f3Values.add(f3)
                        }
                    }
                }
            }

            // Spectral centroid, 85% rolloff, flatness (from noise-reduced spectrum)
            val centroidNorm = ((weightedFreqSum / totalPower) / (SAMPLE_RATE / 2.0)).toFloat().coerceIn(0f, 1f)
            var cumPower = 0.0
            val rolloffTarget = totalPower * 0.85
            var rolloffBin = bins - 1
            for (b in 0 until bins) {
                cumPower += powerSpec[b]
                if (cumPower >= rolloffTarget) {
                    rolloffBin = b
                    break
                }
            }
            val rolloffNorm = (rolloffBin.toFloat() / bins.toFloat()).coerceIn(0f, 1f)
            val geoMean = Math.exp(logPowerSum / bins)
            val arithMean = totalPower / bins
            val flatness = (geoMean / (arithMean + 1e-12)).toFloat().coerceIn(0f, 1f)

            centroids.add(centroidNorm)
            rolloffs.add(rolloffNorm)
            flatnesses.add(flatness)
            zcrs.add(zcr)

            // Mel filterbank log energies -> DCT -> MFCC c1..c16 (noise-reduced)
            for (m in 0 until NUM_MEL_BINS) {
                val weights = melFilterBank[m]
                var e = 1e-10
                for (b in 0 until bins) {
                    val w = weights[b]
                    if (w > 0f) e += powerSpec[b] * w
                }
                melEnergies[m] = ln(e).toFloat()
            }
            val mfcc = FloatArray(NUM_MFCC)
            for (k in 0 until NUM_MFCC) {
                val row = dctMatrix[k]
                var acc = 0f
                for (m in 0 until NUM_MEL_BINS) {
                    acc += row[m] * melEnergies[m]
                }
                mfcc[k] = acc
            }
            mfccFrames.add(mfcc)
        }

        if (mfccFrames.size < MIN_VOICED_FRAMES) return emptyList()

        // Step 3: Aggregate Spectral-Subtracted MFCC means + RASTA-CMVN dynamic variance & delta MFCCs
        val n = mfccFrames.size.toFloat()
        val mfccMean = FloatArray(NUM_MFCC)
        for (vec in mfccFrames) {
            for (k in 0 until NUM_MFCC) mfccMean[k] += vec[k]
        }
        for (k in 0 until NUM_MFCC) mfccMean[k] /= n

        // RASTA-style 1st-order temporal high-pass filter removes static mic/pocket EQ before variance & delta
        val rastaFrames = Array(mfccFrames.size) { FloatArray(NUM_MFCC) }
        val rastaPole = 0.94f
        for (t in mfccFrames.indices) {
            val cur = mfccFrames[t]
            val prevX = if (t > 0) mfccFrames[t - 1] else cur
            val prevY = if (t > 0) rastaFrames[t - 1] else FloatArray(NUM_MFCC)
            val out = rastaFrames[t]
            for (k in 0 until NUM_MFCC) {
                out[k] = (cur[k] - prevX[k]) + rastaPole * prevY[k]
            }
        }

        val rastaMean = FloatArray(NUM_MFCC)
        for (vec in rastaFrames) {
            for (k in 0 until NUM_MFCC) rastaMean[k] += vec[k]
        }
        for (k in 0 until NUM_MFCC) rastaMean[k] /= n

        val mfccStd = FloatArray(NUM_MFCC)
        for (vec in rastaFrames) {
            for (k in 0 until NUM_MFCC) {
                val diff = vec[k] - rastaMean[k]
                mfccStd[k] += diff * diff
            }
        }
        for (k in 0 until NUM_MFCC) mfccStd[k] = sqrt(mfccStd[k] / n)

        val deltaAbsMean = FloatArray(8)
        if (rastaFrames.size >= 3) {
            val denom = (rastaFrames.size - 2).toFloat()
            for (t in 1 until rastaFrames.size - 1) {
                val prev = rastaFrames[t - 1]
                val next = rastaFrames[t + 1]
                for (k in 0 until 8) {
                    deltaAbsMean[k] += abs(next[k] - prev[k]) * 0.5f
                }
            }
            for (k in 0 until 8) deltaAbsMean[k] /= denom
        }

        // Pitch distribution (log-F0 normalized to ~[0, 1] around human voice range 75..420 Hz)
        val (f0MeanNorm, f0StdNorm, f0Q25Norm, f0Q75Norm) = if (pitchValues.size >= 5) {
            val logF0 = pitchValues.map { ln(it.toDouble() / 75.0) / ln(420.0 / 75.0) }.sorted()
            val meanF0 = logF0.average()
            val stdF0 = sqrt(logF0.sumOf { (it - meanF0) * (it - meanF0) } / logF0.size)
            val q25 = logF0[(logF0.size * 0.25).toInt().coerceIn(0, logF0.lastIndex)]
            val q75 = logF0[(logF0.size * 0.75).toInt().coerceIn(0, logF0.lastIndex)]
            listOf(meanF0.toFloat(), (stdF0 * 2.5).toFloat(), q25.toFloat(), q75.toFloat())
        } else {
            listOf(0.4f, 0.1f, 0.35f, 0.45f)
        }

        // Formant statistics (F1, F2, F3 normalized on physiological log scales + VTL ratios)
        fun summarizeFormant(values: List<Float>, minHz: Double, maxHz: Double, fallbackMean: Float): Pair<Float, Float> {
            if (values.size < 4) return fallbackMean to 0.12f
            val logSpan = ln(maxHz / minHz)
            val norm = values.map { (ln(it.toDouble().coerceIn(minHz, maxHz) / minHz) / logSpan).toFloat() }
            val m = norm.average().toFloat()
            val s = sqrt(norm.map { (it - m) * (it - m) }.average()).toFloat()
            return m to s
        }
        val (f1MeanNorm, f1StdNorm) = summarizeFormant(f1Values, 250.0, 900.0, 0.45f)
        val (f2MeanNorm, f2StdNorm) = summarizeFormant(f2Values, 880.0, 2600.0, 0.48f)
        val (f3MeanNorm, f3StdNorm) = summarizeFormant(f3Values, 2100.0, 3900.0, 0.50f)

        val f1MedianHz = f1Values.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else 500f }
        val f2MedianHz = f2Values.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else 1550f }
        val f3MedianHz = f3Values.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else 2650f }
        val f2OverF1Norm = ((f2MedianHz / f1MedianHz.coerceAtLeast(180f) - 1.5f) / 4.0f).coerceIn(0f, 1f)
        val f3OverF2Norm = ((f3MedianHz / f2MedianHz.coerceAtLeast(600f) - 1.1f) / 1.8f).coerceIn(0f, 1f)

        // Glottal voice quality: HNR, Jitter, Shimmer
        val (hnrMeanNorm, hnrStdNorm) = if (hnrValues.size >= 4) {
            val normHnr = hnrValues.map { ((it + 2f) / 26f).coerceIn(0f, 1f) }
            val m = normHnr.average().toFloat()
            val s = sqrt(normHnr.map { (it - m) * (it - m) }.average()).toFloat()
            m to s
        } else {
            0.45f to 0.12f
        }
        val jitterNorm = if (jitterCount >= 4 && jitterPeriodSum > 1e-6) {
            ((jitterAbsDiffSum / jitterPeriodSum).toFloat() * 12f).coerceIn(0f, 1f)
        } else 0.25f
        val shimmerNorm = if (shimmerCount >= 4 && shimmerAmpSum > 1e-6) {
            ((shimmerAbsDiffSum / shimmerAmpSum).toFloat() * 3.5f).coerceIn(0f, 1f)
        } else 0.30f

        val subLowRatio = (lowSubbandSum / n).toFloat().coerceIn(0f, 1f)
        val subMidRatio = (midSubbandSum / n).toFloat().coerceIn(0f, 1f)
        val subHighRatio = (highSubbandSum / n).toFloat().coerceIn(0f, 1f)
        val voicedRatio = (voicedFrameCount.toFloat() / activeSpeechFrameCount.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

        // Assemble 64-D v2 biometric voiceprint (with [0..47] core aligned to legacy 48-D vectors)
        val embedding = FloatArray(EMBEDDING_DIM)
        for (k in 0 until NUM_MFCC) {
            val lifter = 1f + 0.15f * sin(PI * (k + 1) / NUM_MFCC).toFloat()
            embedding[k] = (mfccMean[k] / 8.0f) * lifter
        }
        for (k in 0 until NUM_MFCC) {
            embedding[16 + k] = (mfccStd[k] / 6.0f) * 0.65f
        }
        for (k in 0 until 8) {
            embedding[32 + k] = (deltaAbsMean[k] / 4.0f) * 0.55f
        }
        embedding[40] = f0MeanNorm * 1.8f
        embedding[41] = f0StdNorm * 0.9f
        embedding[42] = f0Q25Norm * 1.4f
        embedding[43] = f0Q75Norm * 1.4f
        embedding[44] = centroids.average().toFloat() * 1.3f
        embedding[45] = rolloffs.average().toFloat() * 1.1f
        embedding[46] = flatnesses.average().toFloat() * 0.9f
        embedding[47] = zcrs.average().toFloat() * 1.0f

        // Extended vocal-tract Formant (F1, F2, F3) & Glottal (HNR, Jitter, Shimmer) features [48..63]
        embedding[48] = f1MeanNorm * 1.35f
        embedding[49] = f1StdNorm * 0.75f
        embedding[50] = f2MeanNorm * 1.45f
        embedding[51] = f2StdNorm * 0.75f
        embedding[52] = f3MeanNorm * 1.25f
        embedding[53] = f3StdNorm * 0.70f
        embedding[54] = f2OverF1Norm * 1.20f
        embedding[55] = f3OverF2Norm * 1.15f
        embedding[56] = hnrMeanNorm * 1.25f
        embedding[57] = hnrStdNorm * 0.70f
        embedding[58] = jitterNorm * 0.85f
        embedding[59] = shimmerNorm * 0.85f
        embedding[60] = subLowRatio * 1.10f
        embedding[61] = subMidRatio * 1.10f
        embedding[62] = subHighRatio * 1.05f
        embedding[63] = voicedRatio * 0.95f

        return l2Normalize(embedding)
    }

    /**
     * Task 3 — On-Device Overlapping-Speech / Cross-Talk & Multi-Speaker Purity Detector:
     * Evaluates whether a candidate voice sample clip represents ONE solo speaker (purity ~1.0)
     * or contains overlapping talkers / a mid-clip speaker change (purity < 0.55) using 3 signals:
     *  1. Sub-window (~1.6s) 64-D biometric self-consistency (detects speaker handoffs/turns).
     *  2. Voiced-frame log-F0 bimodality via 2-cluster Ashman's D statistic (detects dual-pitch overlap).
     *  3. Mean Harmonic-to-Noise Ratio (HNR) drop during active speech (detects simultaneous harmonic collision).
     */
    fun analyzeSingleSpeakerPurity(
        pcm: ShortArray,
        sampleRate: Int = SAMPLE_RATE
    ): SingleSpeakerPurityReport {
        val sr = sampleRate.coerceAtLeast(8000)
        if (pcm.size < sr * 2) {
            return SingleSpeakerPurityReport(
                purityScore = 1.0f,
                hasSuspectedCrosstalk = false,
                subWindowMinSimilarity = 1.0f,
                pitchBimodalityScore = 0f,
                meanHnrDb = 10f,
                subWindowsEvaluated = 0
            )
        }

        // Signal 1: Non-overlapping ~1.6s sub-window voiceprint self-consistency
        val subWinSamples = ((sr * 16) / 10).coerceAtLeast(sr) // 1.6s
        val maxSubWindows = 6
        val subEmbeddings = mutableListOf<List<Float>>()
        val totalSubSlots = (pcm.size / subWinSamples).coerceAtMost(maxSubWindows)
        if (totalSubSlots >= 2) {
            for (w in 0 until totalSubSlots) {
                val start = w * subWinSamples
                val end = min(start + subWinSamples, pcm.size)
                if (end - start < sr) continue
                val slice = pcm.copyOfRange(start, end)
                val emb = extractAcousticEmbedding(slice)
                if (emb.isNotEmpty()) {
                    subEmbeddings.add(emb)
                }
            }
        }

        var minPairSim = 1.0f
        var meanPairSim = 1.0f
        var incompatiblePairs = 0
        var totalPairs = 0
        if (subEmbeddings.size >= 2) {
            var simSum = 0.0
            for (i in 0 until subEmbeddings.size) {
                for (j in i + 1 until subEmbeddings.size) {
                    val sim = biometricSimilarity(subEmbeddings[i], subEmbeddings[j])
                    simSum += sim
                    totalPairs++
                    if (sim < minPairSim) minPairSim = sim
                    if (!areEmbeddingsBiometricallyCompatible(subEmbeddings[i], subEmbeddings[j])) {
                        incompatiblePairs++
                    }
                }
            }
            if (totalPairs > 0) {
                meanPairSim = (simSum / totalPairs).toFloat()
            }
        }

        // Signal 2 & 3: Voiced-frame log-F0 bimodality (Ashman's D) + Mean HNR across the clip
        val frameLen = ((sr * 25) / 1000).coerceAtLeast(200)
        val hop = ((sr * 20) / 1000).coerceAtLeast(160) // 20ms stride for fast scan
        val numFrames = ((pcm.size - frameLen) / hop) + 1
        val minLag = sr / 420
        val maxLag = min(sr / 75, frameLen - 1)
        val rawFrame = FloatArray(frameLen)

        val logF0s = ArrayList<Float>(numFrames / 2)
        val hnrList = ArrayList<Float>(numFrames / 2)

        // Compute adaptive energy threshold so silent pauses don't skew HNR
        val frameDbs = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            val offset = f * hop
            var sq = 0.0
            for (i in 0 until frameLen) {
                val s = pcm[offset + i] / 32768.0
                sq += s * s
            }
            frameDbs[f] = (20.0 * log10(sqrt(sq / frameLen) + 1e-6)).toFloat()
        }
        val sortedDbs = frameDbs.sortedArray()
        val floorDb = sortedDbs[(sortedDbs.size * 0.20).toInt().coerceIn(0, sortedDbs.lastIndex)]
        val peakDb = sortedDbs[(sortedDbs.size * 0.88).toInt().coerceIn(0, sortedDbs.lastIndex)]
        val activeGateDb = max(floorDb + 6.0f, min(peakDb - 14f, -42f))

        for (f in 0 until numFrames) {
            if (frameDbs[f] < activeGateDb) continue
            val offset = f * hop
            var mean = 0f
            for (i in 0 until frameLen) mean += pcm[offset + i] / 32768f
            mean /= frameLen

            var r0 = 0.0
            for (i in 0 until frameLen) {
                val v = (pcm[offset + i] / 32768f) - mean
                rawFrame[i] = v
                r0 += v * v
            }
            if (r0 < 1e-7) continue

            var bestLag = -1
            var bestCorr = 0.0
            var lag = minLag
            while (lag <= maxLag) {
                var acc = 0.0
                val limit = frameLen - lag
                var idx = 0
                while (idx < limit) {
                    acc += rawFrame[idx] * rawFrame[idx + lag]
                    idx += 2
                }
                val normCorr = (acc * 2.0) / r0
                if (normCorr > bestCorr) {
                    bestCorr = normCorr
                    bestLag = lag
                }
                lag += 2
            }
            if (bestCorr >= 0.26 && bestLag > 0) {
                val clamped = bestCorr.coerceIn(0.05, 0.992)
                hnrList.add((10.0 * log10(clamped / (1.0 - clamped))).toFloat().coerceIn(-5f, 26f))
                if (bestCorr >= 0.34) {
                    val hz = sr.toFloat() / bestLag.toFloat()
                    if (hz in 75f..420f) logF0s.add(ln(hz))
                }
            }
        }

        // Compute 1-D Otsu split + Ashman's D bimodality score on voiced log-F0
        var bimodalityScore = 0f
        if (logF0s.size >= 16) {
            logF0s.sort()
            val n = logF0s.size
            // Evaluate candidate split percentiles between 22% and 78%
            var bestAshman = 0.0
            var bestGap = 0.0
            var bestMinWeight = 0.0
            val loIdx = (n * 0.22).toInt().coerceAtLeast(3)
            val hiIdx = (n * 0.78).toInt().coerceAtMost(n - 3)
            var split = loIdx
            while (split <= hiIdx) {
                var sum1 = 0.0
                for (i in 0 until split) sum1 += logF0s[i]
                val m1 = sum1 / split
                var var1 = 0.0
                for (i in 0 until split) {
                    val d = logF0s[i] - m1
                    var1 += d * d
                }
                var1 /= split

                val n2 = n - split
                var sum2 = 0.0
                for (i in split until n) sum2 += logF0s[i]
                val m2 = sum2 / n2
                var var2 = 0.0
                for (i in split until n) {
                    val d = logF0s[i] - m2
                    var2 += d * d
                }
                var2 /= n2

                val gap = abs(m2 - m1)
                val ashmanD = (sqrt(2.0) * gap) / sqrt(var1 + var2 + 1e-4)
                val minWeight = min(split.toDouble() / n, n2.toDouble() / n)
                if (ashmanD > bestAshman && gap >= 0.34) { // >= ~40% frequency jump between modes
                    bestAshman = ashmanD
                    bestGap = gap
                    bestMinWeight = minWeight
                }
                split += max(1, (hiIdx - loIdx) / 14)
            }
            if (bestAshman >= 2.25 && bestGap >= 0.34 && bestMinWeight >= 0.22) {
                bimodalityScore = (((bestAshman - 2.1) / 2.2) * ((bestGap - 0.28) / 0.32))
                    .toFloat()
                    .coerceIn(0f, 1f)
            }
        }

        val meanHnrDb = if (hnrList.isNotEmpty()) hnrList.average().toFloat() else 8f

        // Combine all 3 signals into [0.0 .. 1.0] single-speaker purity score
        var purity = 1.0f
        if (subEmbeddings.size >= 2) {
            if (incompatiblePairs > 0) {
                val ratio = incompatiblePairs.toFloat() / totalPairs.coerceAtLeast(1).toFloat()
                purity -= (0.34f + ratio * 0.30f)
            }
            if (minPairSim < 0.64f) {
                purity -= ((0.64f - minPairSim) * 1.15f).coerceAtMost(0.42f)
            }
            if (meanPairSim < 0.70f) {
                purity -= ((0.70f - meanPairSim) * 0.55f).coerceAtMost(0.20f)
            }
        }
        if (bimodalityScore > 0.15f) {
            purity -= (bimodalityScore * 0.38f)
        }
        // Simultaneous double-talk destroys clean vocal periodicity (low HNR despite loud active speech)
        if (hnrList.size >= 12 && meanHnrDb < 2.2f) {
            purity -= ((2.2f - meanHnrDb) * 0.08f).coerceAtMost(0.22f)
        }

        val finalPurity = purity.coerceIn(0f, 1f)
        val suspectedCrosstalk = finalPurity < 0.54f ||
            (incompatiblePairs > 0 && minPairSim < 0.52f) ||
            bimodalityScore >= 0.72f

        return SingleSpeakerPurityReport(
            purityScore = finalPurity,
            hasSuspectedCrosstalk = suspectedCrosstalk,
            subWindowMinSimilarity = minPairSim,
            pitchBimodalityScore = bimodalityScore,
            meanHnrDb = meanHnrDb,
            subWindowsEvaluated = subEmbeddings.size
        )
    }

    private fun l2Normalize(vec: FloatArray): List<Float> {
        var sumSq = 0.0
        for (v in vec) {
            if (v.isFinite()) sumSq += v.toDouble() * v.toDouble()
        }
        val norm = sqrt(sumSq)
        if (norm < 1e-8) return emptyList()
        val out = ArrayList<Float>(vec.size)
        for (v in vec) {
            val scaled = if (v.isFinite()) (v / norm).toFloat() else 0f
            // Round to 4 decimals for compact JSON serialization
            out.add(kotlin.math.round(scaled * 10_000f) / 10_000f)
        }
        return out
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun buildMelFilterBank(
        numFilters: Int,
        fftSize: Int,
        sampleRate: Int,
        minFreqHz: Double,
        maxFreqHz: Double
    ): Array<FloatArray> {
        val bins = fftSize / 2 + 1
        val lowMel = hzToMel(minFreqHz)
        val highMel = hzToMel(maxFreqHz)
        val melPoints = DoubleArray(numFilters + 2) { i ->
            lowMel + (highMel - lowMel) * i / (numFilters + 1)
        }
        val binIndices = IntArray(numFilters + 2) { i ->
            val hz = melToHz(melPoints[i])
            ((fftSize + 1) * hz / sampleRate).toInt().coerceIn(0, bins - 1)
        }
        return Array(numFilters) { m ->
            val filter = FloatArray(bins)
            val left = binIndices[m]
            val center = binIndices[m + 1]
            val right = binIndices[m + 2]
            for (b in left until center) {
                if (center > left) {
                    filter[b] = ((b - left).toDouble() / (center - left)).toFloat()
                }
            }
            for (b in center..right) {
                if (right > center) {
                    filter[b] = ((right - b).toDouble() / (right - center)).toFloat()
                }
            }
            filter
        }
    }

    /** Standard in-place Cooley-Tukey radix-2 FFT (requires power-of-two array length). */
    private fun fftInPlace(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenRe = cos(ang).toFloat()
            val wLenIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                val half = len shr 1
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    val nextWIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                    wIm = nextWIm
                }
                i += len
            }
            len = len shl 1
        }
    }
}
