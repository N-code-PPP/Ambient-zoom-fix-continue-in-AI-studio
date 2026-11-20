package com.ambientbrain.recorder.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Losslessly slices approximate timestamp windows (`[startMs - 5000, endMs + 5000]`, capped at 45s)
 * from a crash-safe ADTS `.aac` session recording into a permanent playable `.m4a` voice-proof clip
 * (`/files/clips/clip_<itemId>.m4a`).
 *
 * Each 16 kHz mono AAC-LC ADTS frame contains 1,024 PCM samples = 64.0 ms.
 * We parse the 7/9-byte ADTS headers directly, strip the ADTS header, and mux the raw AAC-LC
 * access units into an MPEG-4 (`.m4a`) container using MediaMuxer with zero re-encoding.
 */
object ClipSlicer {
    private const val TAG = "ClipSlicer"
    private const val SAMPLE_RATE = 16000
    private const val SAMPLES_PER_AAC_FRAME = 1024
    private const val FRAME_DURATION_US = (SAMPLES_PER_AAC_FRAME * 1_000_000L) / SAMPLE_RATE // 64,000 us = 64 ms
    private const val PRE_BUFFER_MS = 5_000L
    private const val POST_BUFFER_MS = 5_000L
    private const val MIN_CLIP_DURATION_MS = 3_000L
    private const val MAX_CLIP_DURATION_MS = 45_000L

    fun parseApproxMmSsToMs(mmSs: String?): Long {
        if (mmSs.isNullOrBlank()) return 0L
        val cleaned = mmSs.trim().removePrefix("~").trim()
        val parts = cleaned.split(":")
        return try {
            when (parts.size) {
                2 -> {
                    val m = parts[0].toLongOrNull() ?: 0L
                    val s = parts[1].substringBefore(".").toLongOrNull() ?: 0L
                    (m * 60L + s) * 1000L
                }
                3 -> {
                    val h = parts[0].toLongOrNull() ?: 0L
                    val m = parts[1].toLongOrNull() ?: 0L
                    val s = parts[2].substringBefore(".").toLongOrNull() ?: 0L
                    (h * 3600L + m * 60L + s) * 1000L
                }
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    data class ResolvedTimestamp(
        val rawFileMs: Long,
        val wallClockMs: Long
    )

    /**
     * Resolves a file-relative timestamp (in compacted audio) against recorded [SegmentMapEntity] spans
     * so compacted silence jumps map cleanly back to exact wall-clock timestamps.
     */
    fun resolveCompactedTimestamp(
        rawInputMs: Long,
        segments: List<SegmentMapEntity>,
        sessionStartMs: Long,
        maxFileDurationMs: Long = Long.MAX_VALUE
    ): ResolvedTimestamp {
        val clampedFileMs = rawInputMs.coerceIn(0L, maxFileDurationMs.coerceAtLeast(0L))
        if (segments.isEmpty()) {
            return ResolvedTimestamp(
                rawFileMs = clampedFileMs,
                wallClockMs = sessionStartMs + clampedFileMs
            )
        }
        val sorted = segments.sortedBy { it.fileOffsetMs }
        val matching = sorted.lastOrNull { it.fileOffsetMs <= clampedFileMs } ?: sorted.first()
        val delta = (clampedFileMs - matching.fileOffsetMs).coerceAtLeast(0L)
        return ResolvedTimestamp(
            rawFileMs = clampedFileMs,
            wallClockMs = matching.wallClockMs + delta
        )
    }

    data class AdtsChunk(
        val file: File,
        val startOffsetMs: Long,
        val durationMs: Long,
        val isTemporary: Boolean
    )

    /**
     * Splits a large ADTS `.aac` recording cleanly on ADTS frame boundaries into chunks strictly
     * smaller than [maxChunkBytes] (~10.5 MB default so long recordings stay safely within
     * Gemini API inline payload limits).
     */
    fun splitAdtsFileIntoChunks(
        context: Context,
        adtsFile: File,
        maxChunkBytes: Long = 10_500_000L,
        workId: String
    ): List<AdtsChunk> {
        if (!adtsFile.exists() || adtsFile.length() == 0L) return emptyList()
        if (adtsFile.length() <= maxChunkBytes) {
            val estMs = estimateAdtsDurationMs(adtsFile)
            return listOf(AdtsChunk(adtsFile, 0L, estMs, isTemporary = false))
        }

        val chunkDir = File(context.cacheDir, "audio_chunks/$workId").apply { mkdirs() }
        val chunks = mutableListOf<AdtsChunk>()

        try {
            RandomAccessFile(adtsFile, "r").use { raf ->
                val fileLen = raf.length()
                val header = ByteArray(9)
                var pos = 0L
                var chunkIndex = 0
                var chunkStartOffsetMs = 0L
                var chunkFrames = 0L
                var currentBytes = 0L
                var currentFile = File(chunkDir, "chunk_${chunkIndex}.aac")
                var fos = FileOutputStream(currentFile)

                while (pos + 7 <= fileLen) {
                    raf.seek(pos)
                    raf.readFully(header, 0, 7)

                    val isSync = (header[0].toInt() and 0xFF) == 0xFF &&
                        ((header[1].toInt() and 0xF0) == 0xF0)
                    if (!isSync) {
                        pos++
                        continue
                    }

                    val protectionAbsent = (header[1].toInt() and 0x01) != 0
                    val headerSize = if (protectionAbsent) 7 else 9
                    val frameLength = ((header[3].toInt() and 0x03) shl 11) or
                        ((header[4].toInt() and 0xFF) shl 3) or
                        ((header[5].toInt() and 0xE0) ushr 5)

                    if (frameLength <= headerSize || pos + frameLength > fileLen) {
                        break
                    }

                    if (currentBytes + frameLength > maxChunkBytes && chunkFrames > 0L) {
                        fos.flush()
                        fos.close()
                        val durMs = (chunkFrames * SAMPLES_PER_AAC_FRAME * 1000L) / SAMPLE_RATE
                        chunks.add(AdtsChunk(currentFile, chunkStartOffsetMs, durMs, isTemporary = true))
                        chunkStartOffsetMs += durMs
                        chunkIndex++
                        chunkFrames = 0L
                        currentBytes = 0L
                        currentFile = File(chunkDir, "chunk_${chunkIndex}.aac")
                        fos = FileOutputStream(currentFile)
                    }

                    val frameBytes = ByteArray(frameLength)
                    raf.seek(pos)
                    raf.readFully(frameBytes)
                    fos.write(frameBytes)
                    currentBytes += frameLength
                    chunkFrames++
                    pos += frameLength
                }

                fos.flush()
                fos.close()
                if (chunkFrames > 0L && currentFile.exists() && currentFile.length() > 0L) {
                    val durMs = (chunkFrames * SAMPLES_PER_AAC_FRAME * 1000L) / SAMPLE_RATE
                    chunks.add(AdtsChunk(currentFile, chunkStartOffsetMs, durMs, isTemporary = true))
                } else {
                    currentFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed splitting ADTS file into chunks: ${e.message}", e)
            chunks.forEach { if (it.isTemporary) it.file.delete() }
            return listOf(AdtsChunk(adtsFile, 0L, estimateAdtsDurationMs(adtsFile), isTemporary = false))
        }

        return chunks.ifEmpty {
            listOf(AdtsChunk(adtsFile, 0L, estimateAdtsDurationMs(adtsFile), isTemporary = false))
        }
    }

    fun isWavFile(file: File): Boolean {
        if (!file.exists() || file.length() < 44L) return false
        if (file.name.endsWith(".wav", ignoreCase = true)) return true
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val hdr = ByteArray(12)
                raf.readFully(hdr, 0, 12)
                hdr[0] == 'R'.code.toByte() &&
                    hdr[1] == 'I'.code.toByte() &&
                    hdr[2] == 'F'.code.toByte() &&
                    hdr[3] == 'F'.code.toByte() &&
                    hdr[8] == 'W'.code.toByte() &&
                    hdr[9] == 'A'.code.toByte() &&
                    hdr[10] == 'V'.code.toByte() &&
                    hdr[11] == 'E'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun estimateWavDurationMs(wavFile: File): Long {
        if (!wavFile.exists() || wavFile.length() < 44L) return 0L
        return try {
            RandomAccessFile(wavFile, "r").use { raf ->
                val hdr = ByteArray(44)
                raf.readFully(hdr, 0, 44)
                val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val channels = bb.getShort(22).toInt().coerceIn(1, 8)
                val sampleRate = bb.getInt(24).coerceIn(8000, 192000)
                val bitsPerSample = bb.getShort(34).toInt().coerceIn(8, 32)
                val bytesPerSec = (sampleRate.toLong() * channels * (bitsPerSample / 8)).coerceAtLeast(1L)
                val payloadBytes = (wavFile.length() - 44L).coerceAtLeast(0L)
                (payloadBytes * 1000L) / bytesPerSec
            }
        } catch (_: Exception) {
            ((wavFile.length() - 44L).coerceAtLeast(0L) * 1000L) / (SAMPLE_RATE * 2L)
        }
    }

    fun estimateAdtsDurationMs(adtsFile: File): Long {
        if (!adtsFile.exists() || adtsFile.length() < 7L) return 0L
        if (isWavFile(adtsFile)) {
            return estimateWavDurationMs(adtsFile)
        }
        var frames = 0L
        runCatching {
            RandomAccessFile(adtsFile, "r").use { raf ->
                val fileLen = raf.length()
                val header = ByteArray(7)
                var pos = 0L
                while (pos + 7 <= fileLen) {
                    raf.seek(pos)
                    raf.readFully(header, 0, 7)
                    val isSync = (header[0].toInt() and 0xFF) == 0xFF &&
                        ((header[1].toInt() and 0xF0) == 0xF0)
                    if (!isSync) {
                        pos++
                        continue
                    }
                    val frameLength = ((header[3].toInt() and 0x03) shl 11) or
                        ((header[4].toInt() and 0xFF) shl 3) or
                        ((header[5].toInt() and 0xE0) ushr 5)
                    if (frameLength <= 7 || pos + frameLength > fileLen) break
                    frames++
                    pos += frameLength
                }
            }
        }
        return (frames * SAMPLES_PER_AAC_FRAME * 1000L) / SAMPLE_RATE
    }

    data class VoiceSampleSliceResult(
        val file: File,
        val durationMs: Long,
        val frameCount: Int
    )

    /**
     * Atomically extracts a clean, playable 16 kHz mono ADTS `.aac` voice sample
     * from [rawAdtsFile] covering `[startMs, endMs]`, automatically expanding short
     * solo windows up to `6s..maxMs` within recording bounds and validating >=46 ADTS frames (~3s)
     * and >=4 KB size.
     */
    fun sliceAdtsVoiceSample(
        rawAdtsFile: File,
        outFile: File,
        startMs: Long,
        endMs: Long,
        minMs: Long = 4_000L,
        maxMs: Long = 10_000L
    ): VoiceSampleSliceResult? {
        if (!rawAdtsFile.exists() || rawAdtsFile.length() < 4096L) return null
        val targetMinMs = minMs.coerceAtLeast(3_000L)
        val targetMaxMs = maxMs.coerceAtLeast(targetMinMs)

        // Pass 1: Count valid ADTS frames to determine exact recording duration
        var totalFrames = 0L
        runCatching {
            RandomAccessFile(rawAdtsFile, "r").use { raf ->
                val fileLen = raf.length()
                val header = ByteArray(9)
                var pos = 0L
                while (pos + 7 <= fileLen) {
                    raf.seek(pos)
                    raf.readFully(header, 0, 7)
                    val isSync = (header[0].toInt() and 0xFF) == 0xFF &&
                        ((header[1].toInt() and 0xF0) == 0xF0)
                    if (!isSync) {
                        pos++
                        continue
                    }
                    val protectionAbsent = (header[1].toInt() and 0x01) != 0
                    val headerSize = if (protectionAbsent) 7 else 9
                    val frameLength = ((header[3].toInt() and 0x03) shl 11) or
                        ((header[4].toInt() and 0xFF) shl 3) or
                        ((header[5].toInt() and 0xE0) ushr 5)
                    if (frameLength <= headerSize || pos + frameLength > fileLen) break
                    totalFrames++
                    pos += frameLength
                }
            }
        }

        if (totalFrames < 46L) return null
        val totalDurationMs = (totalFrames * SAMPLES_PER_AAC_FRAME * 1000L) / SAMPLE_RATE
        if (totalDurationMs < 3_000L) return null

        val clampedReqStart = startMs.coerceIn(0L, totalDurationMs)
        val clampedReqEnd = endMs.coerceIn(clampedReqStart, totalDurationMs)
        val rawSpan = (clampedReqEnd - clampedReqStart).coerceAtLeast(0L)

        val idealSpan = rawSpan.coerceIn(minOf(6_500L, targetMaxMs).coerceAtLeast(targetMinMs), targetMaxMs)
        val midMs = (clampedReqStart + clampedReqEnd) / 2L
        var winStartMs = when {
            rawSpan >= targetMinMs -> clampedReqStart
            else -> (midMs - idealSpan / 2L)
        }
        var winEndMs = winStartMs + idealSpan
        if (winStartMs < 0L) {
            winStartMs = 0L
            winEndMs = minOf(totalDurationMs, idealSpan)
        }
        if (winEndMs > totalDurationMs) {
            winEndMs = totalDurationMs
            winStartMs = (totalDurationMs - idealSpan).coerceAtLeast(0L)
        }

        val startFrameIdx = ((winStartMs * 1000L) / FRAME_DURATION_US).coerceIn(0L, totalFrames - 1L)
        val endFrameIdx = ((winEndMs * 1000L) / FRAME_DURATION_US).coerceIn(startFrameIdx, totalFrames - 1L)

        outFile.parentFile?.mkdirs()
        val tmpFile = File(outFile.parentFile, "${outFile.name}.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        return try {
            var currentFrameIdx = 0L
            var writtenFrames = 0
            RandomAccessFile(rawAdtsFile, "r").use { raf ->
                FileOutputStream(tmpFile).use { fos ->
                    val fileLen = raf.length()
                    val header = ByteArray(9)
                    var pos = 0L
                    while (pos + 7 <= fileLen) {
                        raf.seek(pos)
                        raf.readFully(header, 0, 7)
                        val isSync = (header[0].toInt() and 0xFF) == 0xFF &&
                            ((header[1].toInt() and 0xF0) == 0xF0)
                        if (!isSync) {
                            pos++
                            continue
                        }
                        val protectionAbsent = (header[1].toInt() and 0x01) != 0
                        val headerSize = if (protectionAbsent) 7 else 9
                        val frameLength = ((header[3].toInt() and 0x03) shl 11) or
                            ((header[4].toInt() and 0xFF) shl 3) or
                            ((header[5].toInt() and 0xE0) ushr 5)
                        if (frameLength <= headerSize || pos + frameLength > fileLen) break

                        if (currentFrameIdx in startFrameIdx..endFrameIdx) {
                            val frameBytes = ByteArray(frameLength)
                            raf.seek(pos)
                            raf.readFully(frameBytes)
                            fos.write(frameBytes)
                            writtenFrames++
                        } else if (currentFrameIdx > endFrameIdx && writtenFrames > 0) {
                            break
                        }

                        pos += frameLength
                        currentFrameIdx++
                    }
                    fos.flush()
                }
            }

            if (writtenFrames >= 46 && tmpFile.exists() && tmpFile.length() >= 4096L) {
                if (outFile.exists()) outFile.delete()
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.copyTo(outFile, overwrite = true)
                    tmpFile.delete()
                }
                val actualDurMs = (writtenFrames.toLong() * SAMPLES_PER_AAC_FRAME * 1000L) / SAMPLE_RATE
                VoiceSampleSliceResult(
                    file = outFile,
                    durationMs = actualDurMs,
                    frameCount = writtenFrames
                )
            } else {
                tmpFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed slicing ADTS voice sample: ${e.message}", e)
            runCatching { tmpFile.delete() }
            null
        }
    }

    data class VoiceSampleQualityMetrics(
        val score: Float,
        val snrDb: Float,
        val speechRatio: Float,
        val rmsDb: Float,
        val clippingRatio: Float,
        val modulationScore: Float,
        val durationMs: Long,
        val isUsable: Boolean,
        val singleSpeakerPurity: Float = 1.0f,
        val hasSuspectedCrosstalk: Boolean = false
    ) {
        fun qualityTierLabel(): String = when {
            score >= 82f -> "Excellent"
            score >= 68f -> "Clear"
            score >= 52f -> "Good"
            score >= 38f -> "Fair"
            else -> "Weak"
        }
    }

    private val ADTS_SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    /**
     * Decodes any voice sample (`.wav`, `.aac`, `.m4a`, `.flac`) into 16 kHz mono PCM and computes
     * deterministic acoustic quality + single-speaker purity / cross-talk metrics:
     * - Estimated SNR (85th vs 15th percentile frame energy in dB)
     * - Voiceband active speech ratio (filtering DC rumble and broadband static via ZCR)
     * - Active loudness in dBFS and clipping ratio
     * - Syllabic modulation score (distinguishing articulated human speech from steady noise/hum)
     * - Optimal voiceprint duration score (ideal 5s–10s)
     * - Single-Speaker Purity & Cross-Talk Gate (sub-window biometric consistency, F0 bimodality, HNR)
     *
     * Falls back safely to file-level heuristics if the platform decoder is unavailable.
     */
    fun analyzeAdtsSampleQuality(adtsFile: File): VoiceSampleQualityMetrics {
        if (!adtsFile.exists() || adtsFile.length() < 2048L) {
            return VoiceSampleQualityMetrics(
                score = 0f,
                snrDb = 0f,
                speechRatio = 0f,
                rmsDb = -60f,
                clippingRatio = 0f,
                modulationScore = 0f,
                durationMs = 0L,
                isUsable = false,
                singleSpeakerPurity = 0f,
                hasSuspectedCrosstalk = false
            )
        }

        val estimatedDurationMs = estimateAdtsDurationMs(adtsFile)
        if (estimatedDurationMs in 1L..1_499L) {
            return VoiceSampleQualityMetrics(
                score = 10f,
                snrDb = 0f,
                speechRatio = 0f,
                rmsDb = -60f,
                clippingRatio = 0f,
                modulationScore = 0f,
                durationMs = estimatedDurationMs,
                isUsable = false,
                singleSpeakerPurity = 1f,
                hasSuspectedCrosstalk = false
            )
        }

        return try {
            val pcm = decodeAudioFileToMono16kPcm(adtsFile, maxSeconds = 16)
            if (pcm.size >= 1600) {
                val actualDurMs = estimatedDurationMs.takeIf { it > 0L } ?: ((pcm.size * 1000L) / SAMPLE_RATE)
                computePcmQualityMetrics(pcm, SAMPLE_RATE, actualDurMs)
            } else {
                fallbackQualityHeuristic(adtsFile, estimatedDurationMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio quality decode fallback for ${adtsFile.name}: ${e.message}")
            fallbackQualityHeuristic(adtsFile, estimatedDurationMs)
        }
    }

    private fun fallbackQualityHeuristic(adtsFile: File, durationMs: Long): VoiceSampleQualityMetrics {
        val bytesPerSec = if (durationMs > 0L) (adtsFile.length() * 1000L) / durationMs else 0L
        val durScore = when {
            durationMs in 5_000L..10_500L -> 56f
            durationMs in 3_000L..12_000L -> 46f
            else -> 28f
        }
        val bitrateBonus = if (bytesPerSec in 2_000L..12_000L) 6f else 0f
        val score = (durScore + bitrateBonus).coerceIn(0f, 100f)
        return VoiceSampleQualityMetrics(
            score = score,
            snrDb = 12f,
            speechRatio = 0.60f,
            rmsDb = -20f,
            clippingRatio = 0f,
            modulationScore = 0.5f,
            durationMs = durationMs,
            isUsable = durationMs >= 3_000L && adtsFile.length() >= 4096L
        )
    }

    /**
     * Losslessly remuxes an ADTS `.aac` voice sample into a standard playable `.m4a` (MPEG-4)
     * container with zero re-encoding so exported clips play natively across Android, iOS, macOS,
     * and Windows media players.
     */
    fun convertAdtsToM4a(adtsFile: File, outM4aFile: File): Boolean {
        if (!adtsFile.exists() || adtsFile.length() < 64L) return false
        outM4aFile.parentFile?.mkdirs()
        val tmpFile = File(outM4aFile.parentFile, "${outM4aFile.name}.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        var muxer: MediaMuxer? = null
        return try {
            var profileObjType = 2 // AAC-LC
            var freqIdx = 8        // 16 kHz
            var channelCfg = 1     // Mono

            RandomAccessFile(adtsFile, "r").use { raf ->
                val fileLen = raf.length()
                val hdr = ByteArray(7)
                var pos = 0L
                while (pos + 7 <= fileLen) {
                    raf.seek(pos)
                    raf.readFully(hdr, 0, 7)
                    val isSync = (hdr[0].toInt() and 0xFF) == 0xFF &&
                        ((hdr[1].toInt() and 0xF0) == 0xF0)
                    if (isSync) {
                        val parsedProfile = ((hdr[2].toInt() and 0xC0) ushr 6) + 1
                        val parsedFreqIdx = (hdr[2].toInt() and 0x3C) ushr 2
                        val parsedChan = ((hdr[2].toInt() and 0x01) shl 2) or ((hdr[3].toInt() and 0xC0) ushr 6)
                        if (parsedProfile in 1..4) profileObjType = parsedProfile
                        if (parsedFreqIdx in ADTS_SAMPLE_RATES.indices) freqIdx = parsedFreqIdx
                        if (parsedChan in 1..2) channelCfg = parsedChan
                        break
                    }
                    pos++
                }
            }

            val sampleRate = ADTS_SAMPLE_RATES.getOrElse(freqIdx) { SAMPLE_RATE }
            val frameDurUs = (SAMPLES_PER_AAC_FRAME * 1_000_000L) / sampleRate
            val csdByte0 = ((profileObjType shl 3) or (freqIdx ushr 1)).toByte()
            val csdByte1 = (((freqIdx and 0x01) shl 7) or (channelCfg shl 3)).toByte()

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCfg
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(csdByte0, csdByte1)))
            }

            muxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackIdx = muxer.addTrack(format)
            muxer.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var writtenFrames = 0L

            RandomAccessFile(adtsFile, "r").use { raf ->
                val fileLen = raf.length()
                val header = ByteArray(9)
                var pos = 0L
                while (pos + 7 <= fileLen) {
                    raf.seek(pos)
                    raf.readFully(header, 0, 7)
                    val isSync = (header[0].toInt() and 0xFF) == 0xFF &&
                        ((header[1].toInt() and 0xF0) == 0xF0)
                    if (!isSync) {
                        pos++
                        continue
                    }
                    val protectionAbsent = (header[1].toInt() and 0x01) != 0
                    val headerSize = if (protectionAbsent) 7 else 9
                    val frameLength = ((header[3].toInt() and 0x03) shl 11) or
                        ((header[4].toInt() and 0xFF) shl 3) or
                        ((header[5].toInt() and 0xE0) ushr 5)
                    if (frameLength <= headerSize || pos + frameLength > fileLen) break

                    val payloadSize = frameLength - headerSize
                    val payload = ByteArray(payloadSize)
                    raf.seek(pos + headerSize)
                    raf.readFully(payload)

                    bufferInfo.set(0, payloadSize, writtenFrames * frameDurUs, 0)
                    muxer.writeSampleData(trackIdx, ByteBuffer.wrap(payload), bufferInfo)
                    writtenFrames++
                    pos += frameLength
                }
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (writtenFrames > 0L && tmpFile.exists() && tmpFile.length() > 0L) {
                if (outM4aFile.exists()) outM4aFile.delete()
                if (!tmpFile.renameTo(outM4aFile)) {
                    tmpFile.copyTo(outM4aFile, overwrite = true)
                    tmpFile.delete()
                }
                true
            } else {
                tmpFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed converting ADTS to M4A (${adtsFile.name}): ${e.message}", e)
            runCatching { muxer?.release() }
            runCatching { tmpFile.delete() }
            false
        }
    }

    fun slicePermanentClip(
        context: Context,
        rawAdtsFile: File,
        itemId: String,
        approxStartMs: Long,
        approxEndMs: Long
    ): File? {
        if (!rawAdtsFile.exists() || rawAdtsFile.length() < 64L) return null

        val clipsDir = File(context.filesDir, "clips").apply { mkdirs() }
        val outClipFile = File(clipsDir, "clip_${itemId}.m4a")
        if (outClipFile.exists()) outClipFile.delete()

        val paddedStartMs = (approxStartMs - PRE_BUFFER_MS).coerceAtLeast(0L)
        val rawWindowEndMs = (approxEndMs.coerceAtLeast(approxStartMs) + POST_BUFFER_MS)
            .coerceAtLeast(paddedStartMs + MIN_CLIP_DURATION_MS)
        val clampedEndMs = rawWindowEndMs.coerceAtMost(paddedStartMs + MAX_CLIP_DURATION_MS)

        val startFrameIdx = (paddedStartMs * 1000L) / FRAME_DURATION_US
        val endFrameIdx = (clampedEndMs * 1000L) / FRAME_DURATION_US

        var muxer: MediaMuxer? = null
        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE,
                1
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
                // AudioSpecificConfig for AAC-LC (2), 16 kHz (index 8), Mono (1):
                // 00010 1000 0001 000 -> 0x14, 0x08
                val csd0 = ByteBuffer.wrap(byteArrayOf(0x14.toByte(), 0x08.toByte()))
                setByteBuffer("csd-0", csd0)
            }

            muxer = MediaMuxer(outClipFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackIndex = muxer.addTrack(format)
            muxer.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var currentFrameIdx = 0L
            var writtenFrames = 0L

            RandomAccessFile(rawAdtsFile, "r").use { raf ->
                val fileLen = raf.length()
                val header = ByteArray(9)
                var pos = 0L

                while (pos + 7 <= fileLen) {
                    raf.seek(pos)
                    raf.readFully(header, 0, 7)

                    // Check 12-bit syncword 0xFFF
                    val isSync = (header[0].toInt() and 0xFF) == 0xFF &&
                        ((header[1].toInt() and 0xF0) == 0xF0)
                    if (!isSync) {
                        pos++
                        continue
                    }

                    val protectionAbsent = (header[1].toInt() and 0x01) != 0
                    val headerSize = if (protectionAbsent) 7 else 9
                    val frameLength = ((header[3].toInt() and 0x03) shl 11) or
                        ((header[4].toInt() and 0xFF) shl 3) or
                        ((header[5].toInt() and 0xE0) ushr 5)

                    if (frameLength <= headerSize || pos + frameLength > fileLen) {
                        break
                    }

                    if (currentFrameIdx in startFrameIdx..endFrameIdx) {
                        val payloadSize = frameLength - headerSize
                        val payload = ByteArray(payloadSize)
                        raf.seek(pos + headerSize)
                        raf.readFully(payload)

                        val byteBuf = ByteBuffer.wrap(payload)
                        bufferInfo.set(
                            0,
                            payloadSize,
                            writtenFrames * FRAME_DURATION_US,
                            0
                        )
                        muxer.writeSampleData(trackIndex, byteBuf, bufferInfo)
                        writtenFrames++
                    } else if (currentFrameIdx > endFrameIdx && writtenFrames > 0) {
                        break
                    }

                    pos += frameLength
                    currentFrameIdx++
                }
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (writtenFrames > 0 && outClipFile.exists() && outClipFile.length() > 0) {
                outClipFile
            } else {
                outClipFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to slice clip for item $itemId", e)
            runCatching { muxer?.release() }
            outClipFile.delete()
            null
        }
    }

    data class AdtsValidationResult(
        val isValid: Boolean,
        val validFrames: Int,
        val corruptBytes: Long,
        val estimatedDurationMs: Long,
        val extractorMime: String?,
        val selectedEncoderName: String,
        val isHardwareAccelerated: Boolean,
        val summary: String
    )

    /**
     * Validates an ADTS `.aac` file by inspecting every 7-byte ADTS header syncword & frame length
     * and verifying decodability with Android's `MediaExtractor`.
     */
    fun validateAdtsFile(
        adtsFile: File,
        encoderName: String = "AAC-LC",
        isHw: Boolean = false
    ): AdtsValidationResult {
        if (!adtsFile.exists() || adtsFile.length() < 7L) {
            return AdtsValidationResult(
                isValid = false,
                validFrames = 0,
                corruptBytes = 0L,
                estimatedDurationMs = 0L,
                extractorMime = null,
                selectedEncoderName = encoderName,
                isHardwareAccelerated = isHw,
                summary = "File missing or empty (${adtsFile.length()} bytes)"
            )
        }

        var validFrames = 0
        var corruptBytes = 0L
        RandomAccessFile(adtsFile, "r").use { raf ->
            val fileLen = raf.length()
            val header = ByteArray(7)
            var pos = 0L
            while (pos + 7 <= fileLen) {
                raf.seek(pos)
                raf.readFully(header, 0, 7)
                val isSync = (header[0].toInt() and 0xFF) == 0xFF &&
                    ((header[1].toInt() and 0xF0) == 0xF0)
                if (!isSync) {
                    corruptBytes++
                    pos++
                    continue
                }
                val frameLength = ((header[3].toInt() and 0x03) shl 11) or
                    ((header[4].toInt() and 0xFF) shl 3) or
                    ((header[5].toInt() and 0xE0) ushr 5)
                if (frameLength <= 7 || pos + frameLength > fileLen) {
                    corruptBytes += (fileLen - pos)
                    break
                }
                validFrames++
                pos += frameLength
            }
        }

        var extractorMime: String? = null
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(adtsFile.absolutePath)
            if (extractor.trackCount > 0) {
                val trackFormat = extractor.getTrackFormat(0)
                extractorMime = trackFormat.getString(MediaFormat.KEY_MIME)
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor inspection warning: ${e.message}")
        } finally {
            runCatching { extractor.release() }
        }

        val durationMs = (validFrames * SAMPLES_PER_AAC_FRAME * 1000L) / SAMPLE_RATE
        val ok = validFrames > 0 && corruptBytes == 0L
        return AdtsValidationResult(
            isValid = ok,
            validFrames = validFrames,
            corruptBytes = corruptBytes,
            estimatedDurationMs = durationMs,
            extractorMime = extractorMime,
            selectedEncoderName = encoderName,
            isHardwareAccelerated = isHw,
            summary = "Frames: $validFrames (~${durationMs}ms) • Corrupt bytes: $corruptBytes • Extractor MIME: ${extractorMime ?: "raw-adts"} • Codec: $encoderName (HW=$isHw)"
        )
    }

    /**
     * Runs a 1-second synthetic 440Hz sine-wave test through [ com.ambientbrain.recorder.service.DirectPcmBufferPool ]
     * and [ com.ambientbrain.recorder.service.AdtsFileWriter ], then validates the output ADTS file.
     */
    fun runAdtsEncoderSelfTest(context: Context): AdtsValidationResult {
        val tempFile = File(context.cacheDir, "adts_selftest_${System.currentTimeMillis()}.aac")
        return try {
            val pool = com.ambientbrain.recorder.service.DirectPcmBufferPool()
            val writer = com.ambientbrain.recorder.service.AdtsFileWriter(tempFile, pool)
            writer.start()

            // Feed 32 frames of 512 samples (1.024 seconds @ 16 kHz) of a 440Hz tone
            val frame = ShortArray(512)
            var sampleIndex = 0
            repeat(32) {
                for (i in frame.indices) {
                    val angle = 2.0 * Math.PI * 440.0 * (sampleIndex++) / SAMPLE_RATE
                    frame[i] = (Math.sin(angle) * 12000).toInt().toShort()
                }
                pool.submitShortArrayChunked(frame)
            }
            writer.finishAndClose()
            validateAdtsFile(tempFile, writer.selectedCodecName, writer.isHardwareAccelerated)
        } catch (e: Exception) {
            AdtsValidationResult(
                isValid = false,
                validFrames = 0,
                corruptBytes = -1L,
                estimatedDurationMs = 0L,
                extractorMime = null,
                selectedEncoderName = "ERROR",
                isHardwareAccelerated = false,
                summary = "Self-test exception: ${e.message}"
            )
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    /**
     * Atomically writes 16 kHz 16-bit mono signed little-endian PCM samples into a lossless
     * RIFF/WAVE (`.wav`) file for uncompressed voice profile reference clips.
     */
    fun writeMono16kWavFile(
        pcm: ShortArray,
        outWavFile: File,
        sampleRate: Int = SAMPLE_RATE
    ): Boolean {
        if (pcm.isEmpty()) return false
        outWavFile.parentFile?.mkdirs()
        val tmpFile = File(outWavFile.parentFile, "${outWavFile.name}.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        return try {
            val dataBytes = pcm.size * 2
            val riffChunkSize = 36 + dataBytes
            val byteRate = sampleRate * 2 // mono * 16-bit (2 bytes)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put('R'.code.toByte()); put('I'.code.toByte()); put('F'.code.toByte()); put('F'.code.toByte())
                putInt(riffChunkSize)
                put('W'.code.toByte()); put('A'.code.toByte()); put('V'.code.toByte()); put('E'.code.toByte())
                put('f'.code.toByte()); put('m'.code.toByte()); put('t'.code.toByte()); put(' '.code.toByte())
                putInt(16)                      // PCM fmt chunk size
                putShort(1.toShort())           // AudioFormat = 1 (Linear PCM)
                putShort(1.toShort())           // NumChannels = 1 (Mono)
                putInt(sampleRate)              // SampleRate = 16000
                putInt(byteRate)                // ByteRate = 32000
                putShort(2.toShort())           // BlockAlign = 2
                putShort(16.toShort())          // BitsPerSample = 16
                put('d'.code.toByte()); put('a'.code.toByte()); put('t'.code.toByte()); put('a'.code.toByte())
                putInt(dataBytes)
            }.array()

            FileOutputStream(tmpFile).use { fos ->
                fos.write(header)
                val chunkShorts = 4096
                val byteBuf = ByteBuffer.allocate(chunkShorts * 2).order(ByteOrder.LITTLE_ENDIAN)
                var offset = 0
                while (offset < pcm.size) {
                    val count = minOf(chunkShorts, pcm.size - offset)
                    byteBuf.clear()
                    for (i in 0 until count) {
                        byteBuf.putShort(pcm[offset + i])
                    }
                    fos.write(byteBuf.array(), 0, count * 2)
                    offset += count
                }
                fos.flush()
            }

            if (tmpFile.exists() && tmpFile.length() >= 44L + 1024L) {
                if (outWavFile.exists()) outWavFile.delete()
                if (!tmpFile.renameTo(outWavFile)) {
                    tmpFile.copyTo(outWavFile, overwrite = true)
                    tmpFile.delete()
                }
                true
            } else {
                tmpFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeMono16kWavFile failed for ${outWavFile.name}: ${e.message}", e)
            runCatching { tmpFile.delete() }
            false
        }
    }

    /**
     * Reads a RIFF/WAVE `.wav` file directly into 16 kHz mono 16-bit PCM [ShortArray]
     * without requiring `MediaCodec` or `MediaExtractor`. Automatically downmixes stereo
     * and linearly resamples to 16 kHz if the WAV file uses a different sample rate.
     */
    fun readMono16kWavPcm(wavFile: File, maxSeconds: Int = 16): ShortArray {
        if (!wavFile.exists() || wavFile.length() < 44L) return ShortArray(0)
        return try {
            RandomAccessFile(wavFile, "r").use { raf ->
                val fileLen = raf.length()
                val riffHdr = ByteArray(12)
                raf.readFully(riffHdr, 0, 12)
                if (riffHdr[0] != 'R'.code.toByte() || riffHdr[8] != 'W'.code.toByte()) {
                    return ShortArray(0)
                }

                var channels = 1
                var srcSampleRate = SAMPLE_RATE
                var bitsPerSample = 16
                var dataOffset = -1L
                var dataSize = 0L

                var pos = 12L
                val chunkHdr = ByteArray(8)
                while (pos + 8 <= fileLen) {
                    raf.seek(pos)
                    raf.readFully(chunkHdr, 0, 8)
                    val chunkId = String(chunkHdr, 0, 4, Charsets.US_ASCII)
                    val chunkSize = ByteBuffer.wrap(chunkHdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    if (chunkId == "fmt " && chunkSize >= 16L && pos + 8 + 16 <= fileLen) {
                        val fmtBytes = ByteArray(16)
                        raf.readFully(fmtBytes, 0, 16)
                        val fmtBuf = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                        channels = fmtBuf.getShort(2).toInt().coerceIn(1, 8)
                        srcSampleRate = fmtBuf.getInt(4).coerceIn(8000, 192000)
                        bitsPerSample = fmtBuf.getShort(14).toInt().coerceIn(8, 32)
                    } else if (chunkId == "data") {
                        dataOffset = pos + 8L
                        dataSize = minOf(chunkSize, fileLen - dataOffset).coerceAtLeast(0L)
                        break
                    }
                    val paddedChunkSize = chunkSize + (chunkSize and 1L)
                    pos += 8L + paddedChunkSize
                }

                if (dataOffset < 0L) {
                    dataOffset = 44L
                    dataSize = (fileLen - 44L).coerceAtLeast(0L)
                }
                if (bitsPerSample != 16 || dataSize < 2L) return ShortArray(0)

                val maxSrcFrames = (srcSampleRate.toLong() * maxSeconds.coerceIn(2, 25)).toInt()
                val bytesPerFrame = channels * 2
                val totalFramesAvailable = (dataSize / bytesPerFrame).toInt()
                val framesToRead = minOf(totalFramesAvailable, maxSrcFrames)
                if (framesToRead <= 0) return ShortArray(0)

                val rawBytes = ByteArray(framesToRead * bytesPerFrame)
                raf.seek(dataOffset)
                raf.readFully(rawBytes)
                val shortBuf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                val monoSrc = ShortArray(framesToRead)
                if (channels == 1) {
                    shortBuf.get(monoSrc)
                } else {
                    for (f in 0 until framesToRead) {
                        var sum = 0
                        for (c in 0 until channels) {
                            sum += shortBuf.get(f * channels + c).toInt()
                        }
                        monoSrc[f] = (sum / channels).toShort()
                    }
                }

                if (srcSampleRate == SAMPLE_RATE) {
                    return monoSrc
                }

                val targetMaxSamples = SAMPLE_RATE * maxSeconds.coerceIn(2, 25)
                val ratio = srcSampleRate.toDouble() / SAMPLE_RATE.toDouble()
                val outLen = minOf((monoSrc.size / ratio).toInt(), targetMaxSamples).coerceAtLeast(0)
                val resampled = ShortArray(outLen)
                val lastIdx = monoSrc.size - 1
                for (i in 0 until outLen) {
                    val srcPos = i * ratio
                    val idx0 = srcPos.toInt().coerceIn(0, lastIdx)
                    val idx1 = (idx0 + 1).coerceIn(0, lastIdx)
                    val frac = srcPos - idx0
                    val sample = monoSrc[idx0] * (1.0 - frac) + monoSrc[idx1] * frac
                    resampled[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                resampled
            }
        } catch (e: Exception) {
            Log.w(TAG, "readMono16kWavPcm failed for ${wavFile.name}: ${e.message}")
            ShortArray(0)
        }
    }

    fun computePcmQualityMetrics(
        pcm: ShortArray,
        sampleRate: Int = SAMPLE_RATE,
        durationMs: Long = (pcm.size * 1000L) / sampleRate.coerceAtLeast(8000)
    ): VoiceSampleQualityMetrics {
        if (pcm.size < 1600 || durationMs < 1_500L) {
            return VoiceSampleQualityMetrics(
                score = 10f,
                snrDb = 0f,
                speechRatio = 0f,
                rmsDb = -60f,
                clippingRatio = 0f,
                modulationScore = 0f,
                durationMs = durationMs,
                isUsable = false
            )
        }
        val frameSize = ((sampleRate * 20) / 1000).coerceAtLeast(160)
        val frameDbs = ArrayList<Float>((pcm.size / frameSize) + 2)
        val frameZcrs = ArrayList<Float>((pcm.size / frameSize) + 2)
        var totalSamples = 0L
        var clippedSamples = 0L

        var offset = 0
        while (offset + 64 <= pcm.size) {
            val count = minOf(frameSize, pcm.size - offset)
            var sumSq = 0.0
            var crossings = 0
            var prev = pcm[offset].toInt()
            for (i in 0 until count) {
                val v = pcm[offset + i].toInt()
                val norm = v / 32768.0
                sumSq += norm * norm
                if (abs(v) >= 32100) clippedSamples++
                if (i > 0 && ((prev >= 0 && v < 0) || (prev < 0 && v >= 0))) {
                    crossings++
                }
                prev = v
            }
            totalSamples += count
            val rms = sqrt(sumSq / count.toDouble())
            val db = (20.0 * log10(rms + 1e-6)).toFloat().coerceIn(-70f, 0f)
            val zcr = crossings.toFloat() / (count - 1).coerceAtLeast(1).toFloat()
            frameDbs.add(db)
            frameZcrs.add(zcr)
            offset += count
        }

        if (frameDbs.size < 10) {
            return VoiceSampleQualityMetrics(10f, 0f, 0f, -60f, 0f, 0f, durationMs, false)
        }

        val sortedDbs = frameDbs.sorted()
        val noiseFloorDb = sortedDbs[((sortedDbs.size - 1) * 0.15f).toInt().coerceIn(0, sortedDbs.lastIndex)]
        val speechPeakDb = sortedDbs[((sortedDbs.size - 1) * 0.85f).toInt().coerceIn(0, sortedDbs.lastIndex)]
        val snrDb = (speechPeakDb - noiseFloorDb).coerceIn(0f, 45f)

        val speechThresholdDb = maxOf(noiseFloorDb + 5.5f, -44f)
        var activeFrames = 0
        var activeDbSum = 0.0
        for (idx in frameDbs.indices) {
            val db = frameDbs[idx]
            val zcr = frameZcrs[idx]
            if (db >= speechThresholdDb && zcr in 0.010f..0.44f) {
                activeFrames++
                activeDbSum += db
            }
        }
        val speechRatio = (activeFrames.toFloat() / frameDbs.size.toFloat()).coerceIn(0f, 1f)
        val activeRmsDb = if (activeFrames > 0) (activeDbSum / activeFrames).toFloat() else speechPeakDb
        val clippingRatio = (clippedSamples.toFloat() / totalSamples.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

        val meanDb = frameDbs.sum() / frameDbs.size.toFloat()
        var varSum = 0.0
        for (db in frameDbs) {
            val diff = db - meanDb
            varSum += diff * diff
        }
        val dbStd = sqrt(varSum / frameDbs.size.toDouble()).toFloat()
        val modulationScore = ((dbStd - 2.0f) / 10.5f).coerceIn(0f, 1f)

        val snrPts = ((snrDb - 4.0f) / 22.0f).coerceIn(0f, 1f) * 32f
        val speechRatioPts = when {
            speechRatio in 0.45f..0.90f -> 22f
            speechRatio < 0.45f -> (speechRatio / 0.45f).coerceIn(0f, 1f) * 22f
            else -> (22f - ((speechRatio - 0.90f) / 0.10f) * 6f).coerceIn(14f, 22f)
        }
        val loudnessPts = when {
            activeRmsDb in -26f..-8f -> 16f
            activeRmsDb < -26f -> ((activeRmsDb + 46f) / 20f).coerceIn(0f, 1f) * 16f
            else -> (16f - ((activeRmsDb + 8f) / 8f) * 6f).coerceIn(8f, 16f)
        }
        val modulationPts = modulationScore * 14f
        val durationPts = when {
            durationMs in 5_000L..10_500L -> 16f
            durationMs in 3_000L until 5_000L -> 9f + ((durationMs - 3_000f) / 2_000f) * 7f
            durationMs > 10_500L -> (16f - ((durationMs - 10_500f) / 10_000f) * 3f).coerceIn(12f, 16f)
            else -> (durationMs / 3_000f).coerceIn(0f, 1f) * 9f
        }
        val clipPenalty = (clippingRatio * 400f).coerceAtMost(28f)

        // Task 3: Single-Speaker Purity & Overlapping-Speech / Cross-Talk Gate
        val purityReport = if (durationMs >= 2_500L && speechRatio >= 0.20f) {
            runCatching {
                com.ambientbrain.recorder.ai.SpeakerEmbeddingEngine.analyzeSingleSpeakerPurity(pcm, sampleRate)
            }.getOrNull()
        } else null

        val purityScore = purityReport?.purityScore ?: 1.0f
        val hasCrosstalk = purityReport?.hasSuspectedCrosstalk ?: false
        val crosstalkPenalty = when {
            purityScore < 0.80f -> ((0.80f - purityScore) * 48f).coerceAtMost(38f)
            else -> 0f
        }

        val compositeScore = (
            snrPts + speechRatioPts + loudnessPts + modulationPts + durationPts -
                clipPenalty - crosstalkPenalty
            ).coerceIn(0f, 100f)

        val isUsable = durationMs >= 2_500L &&
            speechRatio >= 0.22f &&
            activeRmsDb >= -43f &&
            snrDb >= 4.5f &&
            clippingRatio < 0.12f &&
            purityScore >= 0.46f &&
            !(hasCrosstalk && purityScore < 0.52f) &&
            compositeScore >= 32f

        return VoiceSampleQualityMetrics(
            score = compositeScore,
            snrDb = snrDb,
            speechRatio = speechRatio,
            rmsDb = activeRmsDb,
            clippingRatio = clippingRatio,
            modulationScore = modulationScore,
            durationMs = durationMs,
            isUsable = isUsable,
            singleSpeakerPurity = purityScore,
            hasSuspectedCrosstalk = hasCrosstalk
        )
    }

    /**
     * Decodes any Android-supported audio file (`.wav`, `.aac`, `.m4a`, `.mp3`, `.flac`) into 16 kHz mono
     * PCM [ShortArray] (capped at [maxSeconds]) for on-device voiceprint extraction.
     * Uses direct zero-codec PCM reading when the file is a RIFF/WAVE `.wav`.
     */
    fun decodeAudioFileToMono16kPcm(audioFile: File, maxSeconds: Int = 14): ShortArray {
        if (!audioFile.exists() || audioFile.length() < 512L) return ShortArray(0)
        if (isWavFile(audioFile)) {
            val wavPcm = readMono16kWavPcm(audioFile, maxSeconds)
            if (wavPcm.isNotEmpty()) return wavPcm
        }
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(audioFile.absolutePath)
            decodeExtractorToMono16kPcm(extractor, maxSeconds)
        } catch (e: Exception) {
            Log.w(TAG, "decodeAudioFileToMono16kPcm failed for ${audioFile.name}: ${e.message}")
            ShortArray(0)
        } finally {
            runCatching { extractor?.release() }
        }
    }

    /**
     * Imports any user-selected audio document (`Uri`, e.g., `.wav`, `.m4a`, `.aac`, `.mp3`, `.flac`, `.ogg`)
     * into either a lossless 16 kHz 16-bit mono `.wav` voice sample file (when [outAdtsFile] ends with `.wav`)
     * or a 64 kbps 16 kHz mono ADTS `.aac` voice sample file (capped at [maxDurationMs], default 15s)
     * so users can enroll voice profiles from existing audio clips with maximum biometric fidelity.
     */
    fun importAudioUriToAdtsSample(
        context: Context,
        sourceUri: android.net.Uri,
        outAdtsFile: File,
        maxDurationMs: Long = 15_000L
    ): Boolean {
        val appContext = context.applicationContext
        outAdtsFile.parentFile?.mkdirs()
        val tmpInWav = File(appContext.cacheDir, "tmp_import_probe_${System.currentTimeMillis()}.wav")
        val tmpOut = File(outAdtsFile.parentFile, "${outAdtsFile.name}.import.tmp")
        if (tmpOut.exists()) tmpOut.delete()

        var extractor: MediaExtractor? = null
        return try {
            val maxSec = ((maxDurationMs / 1000L).toInt()).coerceIn(3, 20)

            // First check if the source URI itself is a RIFF/WAVE file for direct lossless read
            val directWavPcm: ShortArray = runCatching {
                appContext.contentResolver.openInputStream(sourceUri)?.use { ins ->
                    FileOutputStream(tmpInWav).use { fos ->
                        ins.copyTo(fos)
                    }
                }
                if (isWavFile(tmpInWav)) {
                    readMono16kWavPcm(tmpInWav, maxSeconds = maxSec)
                } else {
                    ShortArray(0)
                }
            }.getOrDefault(ShortArray(0))

            val pcm = if (directWavPcm.size >= SAMPLE_RATE) {
                directWavPcm
            } else {
                extractor = MediaExtractor()
                val afd = appContext.contentResolver.openAssetFileDescriptor(sourceUri, "r")
                if (afd != null) {
                    afd.use { fd ->
                        if (fd.declaredLength >= 0L) {
                            extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.declaredLength)
                        } else {
                            extractor.setDataSource(fd.fileDescriptor)
                        }
                    }
                } else {
                    extractor.setDataSource(appContext, sourceUri, null)
                }
                decodeExtractorToMono16kPcm(extractor, maxSeconds = maxSec)
            }

            if (pcm.size < SAMPLE_RATE) {
                return false
            }

            if (outAdtsFile.name.endsWith(".wav", ignoreCase = true)) {
                return writeMono16kWavFile(pcm, outAdtsFile, SAMPLE_RATE)
            }

            val writer = com.ambientbrain.recorder.service.AdtsFileWriter(
                outputFile = tmpOut,
                bufferPool = null,
                bitRate = com.ambientbrain.recorder.service.AdtsFileWriter.VOICE_PROFILE_BIT_RATE
            )
            try {
                writer.start()
                writer.writePcmShorts(pcm)
            } finally {
                writer.finishAndClose()
            }

            if (tmpOut.exists() && tmpOut.length() >= 1024L) {
                if (outAdtsFile.exists()) outAdtsFile.delete()
                if (!tmpOut.renameTo(outAdtsFile)) {
                    tmpOut.copyTo(outAdtsFile, overwrite = true)
                    tmpOut.delete()
                }
                true
            } else {
                tmpOut.delete()
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "importAudioUriToAdtsSample failed: ${e.message}")
            runCatching { tmpOut.delete() }
            false
        } finally {
            runCatching { if (tmpInWav.exists()) tmpInWav.delete() }
            runCatching { extractor?.release() }
        }
    }

    private fun decodeExtractorToMono16kPcm(extractor: MediaExtractor, maxSeconds: Int): ShortArray {
        var decoder: MediaCodec? = null
        return try {
            var audioTrackIdx = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIdx < 0 || trackFormat == null) return ShortArray(0)

            extractor.selectTrack(audioTrackIdx)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_AUDIO_AAC
            var srcSampleRate = runCatching { trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(SAMPLE_RATE).coerceAtLeast(8000)
            var srcChannels = runCatching { trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(1).coerceIn(1, 8)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val targetMaxSamples = SAMPLE_RATE * maxSeconds.coerceIn(2, 25)
            val rawMono = ArrayList<Short>(minOf(srcSampleRate * maxSeconds, 48_000 * 15))
            var srcMaxSamples = srcSampleRate * maxSeconds.coerceIn(2, 25)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var iterations = 0

            while (!outputDone && iterations < 3000 && rawMono.size < srcMaxSamples) {
                iterations++
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(8_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val readSize = extractor.readSampleData(inBuf, 0)
                            if (readSize < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inIdx, 0, readSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 8_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = decoder.outputFormat
                        srcSampleRate = runCatching { outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                            .getOrDefault(srcSampleRate).coerceAtLeast(8000)
                        srcChannels = runCatching { outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                            .getOrDefault(srcChannels).coerceIn(1, 8)
                        srcMaxSamples = srcSampleRate * maxSeconds.coerceIn(2, 25)
                    }
                    outIdx >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val count = shortBuf.remaining()
                            var i = 0
                            while (i + srcChannels - 1 < count && rawMono.size < srcMaxSamples) {
                                var acc = 0
                                for (c in 0 until srcChannels) {
                                    acc += shortBuf.get(i + c).toInt()
                                }
                                rawMono.add((acc / srcChannels).toShort())
                                i += srcChannels
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    else -> {
                        if (inputDone && iterations > 50) outputDone = true
                    }
                }
            }

            if (rawMono.isEmpty()) return ShortArray(0)
            if (srcSampleRate == SAMPLE_RATE) {
                val outLen = minOf(rawMono.size, targetMaxSamples)
                return ShortArray(outLen) { idx -> rawMono[idx] }
            }

            // Linear resample to 16 kHz mono
            val ratio = srcSampleRate.toDouble() / SAMPLE_RATE.toDouble()
            val outLen = minOf((rawMono.size / ratio).toInt(), targetMaxSamples).coerceAtLeast(0)
            val resampled = ShortArray(outLen)
            val lastIdx = rawMono.size - 1
            for (i in 0 until outLen) {
                val srcPos = i * ratio
                val idx0 = srcPos.toInt().coerceIn(0, lastIdx)
                val idx1 = (idx0 + 1).coerceIn(0, lastIdx)
                val frac = srcPos - idx0
                val sample = rawMono[idx0] * (1.0 - frac) + rawMono[idx1] * frac
                resampled[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            resampled
        } catch (e: Exception) {
            Log.w(TAG, "decodeExtractorToMono16kPcm failed: ${e.message}")
            ShortArray(0)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
        }
    }

    /**
     * Reads a specific `[startMs, endMs]` time window from a `.wav` file into 16 kHz mono PCM.
     */
    fun readWavWindowToMono16kPcm(
        wavFile: File,
        startMs: Long,
        endMs: Long
    ): ShortArray {
        if (!isWavFile(wavFile) || endMs <= startMs) return ShortArray(0)
        val maxSec = ((endMs / 1000L).toInt() + 2).coerceIn(2, 1200)
        val full = readMono16kWavPcm(wavFile, maxSeconds = maxSec)
        if (full.isEmpty()) return ShortArray(0)
        val startIdx = ((startMs.coerceAtLeast(0L) * SAMPLE_RATE) / 1000L).toInt().coerceIn(0, full.size)
        val endIdx = ((endMs.coerceAtLeast(startMs) * SAMPLE_RATE) / 1000L).toInt().coerceIn(startIdx, full.size)
        if (endIdx - startIdx < 1600) return ShortArray(0)
        return full.copyOfRange(startIdx, endIdx)
    }

    /**
     * Streams any audio file (`.wav`, `.aac`, `.m4a`, etc.) in fixed-size 16 kHz mono PCM chunks
     * (default `chunkSamples = 8000` = 0.5 seconds) using O(1) memory so long ward-round recordings
     * can be scanned on-device for VAD islands and speaker-turn change points before calling Gemini.
     *
     * Returns total milliseconds scanned. [onChunk] returns `true` to continue or `false` to stop early.
     */
    fun streamAudioFileAs16kChunks(
        audioFile: File,
        chunkSamples: Int = 8000,
        maxDurationMs: Long = 1_200_000L,
        onChunk: (chunkStartMs: Long, pcm: ShortArray, sampleCount: Int) -> Boolean
    ): Long {
        if (!audioFile.exists() || audioFile.length() < 1024L || chunkSamples < 400) return 0L
        val maxTargetSamples = ((maxDurationMs.coerceAtLeast(2_000L) * SAMPLE_RATE) / 1000L)

        // Fast O(1)-memory direct stream for RIFF/WAVE files
        if (isWavFile(audioFile)) {
            return runCatching {
                RandomAccessFile(audioFile, "r").use { raf ->
                    val fileLen = raf.length()
                    val riffHdr = ByteArray(12)
                    raf.readFully(riffHdr, 0, 12)
                    var channels = 1
                    var srcSampleRate = SAMPLE_RATE
                    var bitsPerSample = 16
                    var dataOffset = -1L
                    var dataSize = 0L

                    var pos = 12L
                    val chunkHdr = ByteArray(8)
                    while (pos + 8 <= fileLen) {
                        raf.seek(pos)
                        raf.readFully(chunkHdr, 0, 8)
                        val chunkId = String(chunkHdr, 0, 4, Charsets.US_ASCII)
                        val chunkSize = ByteBuffer.wrap(chunkHdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        if (chunkId == "fmt " && chunkSize >= 16L && pos + 8 + 16 <= fileLen) {
                            val fmtBytes = ByteArray(16)
                            raf.readFully(fmtBytes, 0, 16)
                            val fmtBuf = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                            channels = fmtBuf.getShort(2).toInt().coerceIn(1, 8)
                            srcSampleRate = fmtBuf.getInt(4).coerceIn(8000, 192000)
                            bitsPerSample = fmtBuf.getShort(14).toInt().coerceIn(8, 32)
                        } else if (chunkId == "data") {
                            dataOffset = pos + 8L
                            dataSize = minOf(chunkSize, fileLen - dataOffset).coerceAtLeast(0L)
                            break
                        }
                        pos += 8L + chunkSize + (chunkSize and 1L)
                    }
                    if (dataOffset < 0L) {
                        dataOffset = 44L
                        dataSize = (fileLen - 44L).coerceAtLeast(0L)
                    }
                    if (bitsPerSample != 16 || dataSize < 2L) return@use 0L

                    val bytesPerFrame = channels * 2
                    val srcFramesPerChunk = ((chunkSamples.toDouble() * srcSampleRate) / SAMPLE_RATE).toInt().coerceAtLeast(400)
                    val rawBuf = ByteArray(srcFramesPerChunk * bytesPerFrame)
                    val outChunk = ShortArray(chunkSamples)
                    var bytesReadTotal = 0L
                    var emittedSamples = 0L
                    raf.seek(dataOffset)

                    while (bytesReadTotal < dataSize && emittedSamples < maxTargetSamples) {
                        val toRead = minOf(rawBuf.size.toLong(), dataSize - bytesReadTotal).toInt()
                        val actual = raf.read(rawBuf, 0, toRead)
                        if (actual < bytesPerFrame) break
                        bytesReadTotal += actual
                        val framesRead = actual / bytesPerFrame
                        val shortBuf = ByteBuffer.wrap(rawBuf, 0, framesRead * bytesPerFrame)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()

                        val monoFrames = ShortArray(framesRead)
                        if (channels == 1) {
                            shortBuf.get(monoFrames)
                        } else {
                            for (f in 0 until framesRead) {
                                var acc = 0
                                for (c in 0 until channels) acc += shortBuf.get(f * channels + c).toInt()
                                monoFrames[f] = (acc / channels).toShort()
                            }
                        }

                        val outCount: Int
                        if (srcSampleRate == SAMPLE_RATE) {
                            outCount = minOf(framesRead, chunkSamples)
                            System.arraycopy(monoFrames, 0, outChunk, 0, outCount)
                        } else {
                            val ratio = srcSampleRate.toDouble() / SAMPLE_RATE.toDouble()
                            outCount = minOf((framesRead / ratio).toInt(), chunkSamples).coerceAtLeast(0)
                            val lastIdx = (framesRead - 1).coerceAtLeast(0)
                            for (i in 0 until outCount) {
                                val srcPos = i * ratio
                                val idx0 = srcPos.toInt().coerceIn(0, lastIdx)
                                val idx1 = (idx0 + 1).coerceIn(0, lastIdx)
                                val frac = srcPos - idx0
                                outChunk[i] = (monoFrames[idx0] * (1.0 - frac) + monoFrames[idx1] * frac)
                                    .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            }
                        }

                        if (outCount >= 400) {
                            val chunkStartMs = (emittedSamples * 1000L) / SAMPLE_RATE
                            emittedSamples += outCount
                            if (!onChunk(chunkStartMs, outChunk, outCount)) break
                        }
                    }
                    (emittedSamples * 1000L) / SAMPLE_RATE
                }
            }.getOrDefault(0L)
        }

        // Compressed audio streaming path (.aac / .m4a / .mp3 / .flac)
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(audioFile.absolutePath)
            var audioTrackIdx = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIdx < 0 || trackFormat == null) return 0L

            extractor.selectTrack(audioTrackIdx)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_AUDIO_AAC
            var srcSampleRate = runCatching { trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(SAMPLE_RATE).coerceAtLeast(8000)
            var srcChannels = runCatching { trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(1).coerceIn(1, 8)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val outChunk = ShortArray(chunkSamples)
            var outFill = 0
            var emittedSamples = 0L
            var resamplePhase = 0.0
            var prevMonoSample = 0
            var hasPrevSample = false

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var stopEarly = false
            var idleStalls = 0

            while (!outputDone && !stopEarly && emittedSamples < maxTargetSamples && idleStalls < 400) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(4_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val readSize = extractor.readSampleData(inBuf, 0)
                            if (readSize < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inIdx, 0, readSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 4_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        idleStalls = 0
                        val outFmt = decoder.outputFormat
                        srcSampleRate = runCatching { outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                            .getOrDefault(srcSampleRate).coerceAtLeast(8000)
                        srcChannels = runCatching { outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                            .getOrDefault(srcChannels).coerceIn(1, 8)
                    }
                    outIdx >= 0 -> {
                        idleStalls = 0
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val count = shortBuf.remaining()
                            val stepRatio = srcSampleRate.toDouble() / SAMPLE_RATE.toDouble()
                            var i = 0
                            while (i + srcChannels - 1 < count && !stopEarly && emittedSamples < maxTargetSamples) {
                                val monoVal = if (srcChannels == 1) {
                                    shortBuf.get(i).toInt()
                                } else {
                                    var acc = 0
                                    for (c in 0 until srcChannels) acc += shortBuf.get(i + c).toInt()
                                    acc / srcChannels
                                }
                                i += srcChannels

                                if (srcSampleRate == SAMPLE_RATE) {
                                    outChunk[outFill++] = monoVal.toShort()
                                    if (outFill >= chunkSamples) {
                                        val chunkStartMs = (emittedSamples * 1000L) / SAMPLE_RATE
                                        emittedSamples += outFill
                                        if (!onChunk(chunkStartMs, outChunk, outFill)) {
                                            stopEarly = true
                                        }
                                        outFill = 0
                                    }
                                } else {
                                    if (!hasPrevSample) {
                                        prevMonoSample = monoVal
                                        hasPrevSample = true
                                    } else {
                                        while (resamplePhase < 1.0 && !stopEarly && emittedSamples < maxTargetSamples) {
                                            val interp = (prevMonoSample * (1.0 - resamplePhase) + monoVal * resamplePhase)
                                                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                            outChunk[outFill++] = interp
                                            if (outFill >= chunkSamples) {
                                                val chunkStartMs = (emittedSamples * 1000L) / SAMPLE_RATE
                                                emittedSamples += outFill
                                                if (!onChunk(chunkStartMs, outChunk, outFill)) {
                                                    stopEarly = true
                                                }
                                                outFill = 0
                                            }
                                            resamplePhase += stepRatio
                                        }
                                        resamplePhase -= 1.0
                                        prevMonoSample = monoVal
                                    }
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    else -> {
                        if (inputDone) idleStalls++
                    }
                }
            }

            if (!stopEarly && outFill >= 1600 && emittedSamples < maxTargetSamples) {
                val chunkStartMs = (emittedSamples * 1000L) / SAMPLE_RATE
                emittedSamples += outFill
                onChunk(chunkStartMs, outChunk, outFill)
            }
            (emittedSamples * 1000L) / SAMPLE_RATE
        } catch (e: Exception) {
            Log.w(TAG, "streamAudioFileAs16kChunks failed for ${audioFile.name}: ${e.message}")
            0L
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor?.release() }
        }
    }
}
