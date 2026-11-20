package com.ambientbrain.recorder.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import com.ambientbrain.recorder.data.CaptureStateBus
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams 16 kHz 16-bit mono PCM into a crash-safe ADTS `.aac` file (32 kbps AAC-LC).
 *
 * Architecture:
 *  - When paired with a [DirectPcmBufferPool], runs `MediaCodec` input/output queuing and
 *    disk I/O on a dedicated encoder thread (`AmbientBrain-AacEncoder`) so the real-time
 *    `AudioRecord` capture thread never blocks on codec timeouts or flash storage writes.
 *  - Uses bulk native `ByteBuffer.put(ByteBuffer)` transfer into `MediaCodec.getInputBuffer(idx)`
 *    and pre-allocated scratch arrays (`adtsHeaderScratch` + `encodedPayloadScratch`) for
 *    zero per-frame heap allocations.
 *  - Handles `MediaCodec.CodecException` (transient, recoverable, and fatal recreation).
 */
class AdtsFileWriter(
    val outputFile: File,
    private val bufferPool: DirectPcmBufferPool? = null,
    private val bitRate: Int = BIT_RATE
) {

    private var codec: MediaCodec? = null
    private var outputStream: BufferedOutputStream? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private val totalSamplesInput = AtomicLong(0L)
    private val isStarted = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private var encoderThread: Thread? = null

    // Pre-allocated scratch buffers to avoid per-frame ByteArray allocations
    private val adtsHeaderScratch = ByteArray(7)
    private var encodedPayloadScratch = ByteArray(8192)
    private var framesSinceLastFlush = 0

    var selectedCodecName: String = "AAC-LC"
        private set
    var isHardwareAccelerated: Boolean = false
        private set

    fun start() {
        if (isStarted.get()) return
        outputFile.parentFile?.mkdirs()
        outputStream = BufferedOutputStream(FileOutputStream(outputFile, true), 32 * 1024)

        createAndStartCodec()
        isStarted.set(true)
        stopRequested.set(false)
        bufferPool?.resetWakeState()

        if (bufferPool != null) {
            encoderThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                runDedicatedEncodeLoop(bufferPool)
            }, "AmbientBrain-AacEncoder").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun buildMediaFormat(): MediaFormat {
        return MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            SAMPLE_RATE,
            CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate.coerceIn(24_000, 128_000))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
    }

    private fun createAndStartCodec() {
        val format = buildMediaFormat()
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        codec = encoder

        runCatching {
            val info = encoder.codecInfo
            selectedCodecName = info.name
            isHardwareAccelerated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !info.name.startsWith("OMX.google.", ignoreCase = true) &&
                    !info.name.startsWith("c2.android.", ignoreCase = true)
            }
            Log.i(
                TAG,
                "Selected AAC encoder: name=$selectedCodecName, isHardwareAccelerated=$isHardwareAccelerated"
            )
            CaptureStateBus.update {
                it.copy(
                    activeEncoderName = selectedCodecName,
                    isEncoderHwAccelerated = isHardwareAccelerated
                )
            }
        }
    }

    private fun runDedicatedEncodeLoop(pool: DirectPcmBufferPool) {
        var pendingBuffer: ByteBuffer? = null
        while (!stopRequested.get() || pendingBuffer != null || pool.pollFilledBuffer().also { pendingBuffer = it } != null) {
            val buf = pendingBuffer ?: try {
                pool.awaitFilledBuffer(timeoutMs = 250L)
            } catch (_: InterruptedException) {
                if (stopRequested.get()) break
                null
            }
            if (buf == null) {
                // Drain any pending codec output once when interval/silence pause occurs
                val encoder = codec
                if (encoder != null) {
                    runCatching { drainOutput(encoder, endOfStream = false) }
                        .onFailure { handleCodecFailure(it) }
                }
                continue
            }

            pendingBuffer = buf
            val encoder = codec
            if (encoder == null) {
                pool.returnFreeBuffer(buf)
                pendingBuffer = null
                continue
            }

            try {
                while (buf.hasRemaining()) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(5_000L)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val bytesToCopy = minOf(inputBuffer.remaining(), buf.remaining())
                            val oldLimit = buf.limit()
                            buf.limit(buf.position() + bytesToCopy)
                            inputBuffer.put(buf)
                            buf.limit(oldLimit)

                            val samplesFed = bytesToCopy / 2
                            val totalSamplesBefore = totalSamplesInput.getAndAdd(samplesFed.toLong())
                            val ptsUs = (totalSamplesBefore * 1_000_000L) / SAMPLE_RATE
                            encoder.queueInputBuffer(inputBufferIndex, 0, bytesToCopy, ptsUs, 0)
                        }
                        drainOutput(encoder, endOfStream = false)
                    } else {
                        // Timeout waiting for an input buffer: drain output first without dropping buf
                        drainOutput(encoder, endOfStream = false)
                    }
                }
                pool.returnFreeBuffer(buf)
                pendingBuffer = null
            } catch (e: Exception) {
                handleCodecFailure(e)
                pool.returnFreeBuffer(buf)
                pendingBuffer = null
            }
        }
    }

    private fun handleCodecFailure(t: Throwable) {
        if (t is MediaCodec.CodecException) {
            Log.w(
                TAG,
                "MediaCodec.CodecException (transient=${t.isTransient}, recoverable=${t.isRecoverable}): ${t.diagnosticInfo}",
                t
            )
            if (t.isTransient) {
                runCatching { Thread.sleep(10L) }
                return
            }
            if (t.isRecoverable) {
                val encoder = codec
                if (encoder != null) {
                    val recovered = runCatching {
                        encoder.stop()
                        encoder.configure(buildMediaFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        encoder.start()
                        true
                    }.getOrDefault(false)
                    if (recovered) return
                }
            }
        } else {
            Log.w(TAG, "Unexpected encoder error; recreating MediaCodec", t)
        }

        // Fatal exception or unrecovered state: recreate codec
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { createAndStartCodec() }
    }

    /**
     * Synchronous helper for one-off UI voice profile enrollment (`VoiceProfileRecorder`).
     */
    fun writePcmShorts(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        if (!isStarted.get() || length <= 0) return
        if (bufferPool != null) {
            val slice = if (offset == 0 && length == samples.size) {
                samples
            } else {
                samples.copyOfRange(offset, offset + length)
            }
            bufferPool.submitShortArrayChunked(slice)
            return
        }

        val encoder = codec ?: return
        var idx = offset
        val end = offset + length

        while (idx < end) {
            try {
                val inputBufferIndex = encoder.dequeueInputBuffer(5_000L)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputBufferIndex) ?: continue
                    inputBuffer.clear()
                    inputBuffer.order(ByteOrder.nativeOrder())
                    val maxShorts = (inputBuffer.remaining() / 2).coerceAtMost(end - idx)
                    inputBuffer.asShortBuffer().put(samples, idx, maxShorts)
                    val totalBefore = totalSamplesInput.getAndAdd(maxShorts.toLong())
                    val ptsUs = (totalBefore * 1_000_000L) / SAMPLE_RATE
                    encoder.queueInputBuffer(inputBufferIndex, 0, maxShorts * 2, ptsUs, 0)
                    idx += maxShorts
                }
                drainOutput(encoder, endOfStream = false)
            } catch (e: Exception) {
                handleCodecFailure(e)
                break
            }
        }
    }

    fun finishAndClose() {
        if (!isStarted.getAndSet(false)) return
        stopRequested.set(true)
        bufferPool?.signalConsumerWake(isStop = true)

        encoderThread?.let { thread ->
            runCatching { thread.join(3_000L) }
        }
        encoderThread = null

        val encoder = codec
        if (encoder != null) {
            try {
                val inputBufferIndex = encoder.dequeueInputBuffer(10_000L)
                if (inputBufferIndex >= 0) {
                    val ptsUs = (totalSamplesInput.get() * 1_000_000L) / SAMPLE_RATE
                    encoder.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        0,
                        ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
                drainOutput(encoder, endOfStream = true)
            } catch (e: Exception) {
                Log.w(TAG, "Error draining encoder on close", e)
            } finally {
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
            }
        }
        codec = null
        runCatching { outputStream?.flush() }
        runCatching { outputStream?.close() }
        outputStream = null
    }

    val writtenDurationMs: Long
        get() = (totalSamplesInput.get() * 1000L) / SAMPLE_RATE

    private fun drainOutput(encoder: MediaCodec, endOfStream: Boolean) {
        val out = outputStream ?: return
        var retries = 0
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 5_000L else 0L)
            if (outIndex >= 0) {
                val encodedData = encoder.getOutputBuffer(outIndex)
                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (!isConfig && bufferInfo.size > 0 && encodedData != null) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    val payloadSize = bufferInfo.size
                    val frameLength = payloadSize + 7
                    fillAdtsHeader(frameLength, adtsHeaderScratch)
                    out.write(adtsHeaderScratch, 0, 7)

                    if (encodedPayloadScratch.size < payloadSize) {
                        encodedPayloadScratch = ByteArray(payloadSize * 2)
                    }
                    encodedData.get(encodedPayloadScratch, 0, payloadSize)
                    out.write(encodedPayloadScratch, 0, payloadSize)

                    if (++framesSinceLastFlush >= 64) {
                        out.flush()
                        framesSinceLastFlush = 0
                    }
                }
                encoder.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++retries > 10) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Encoder output format changed: ${encoder.outputFormat}")
            } else {
                break
            }
        }
        if (endOfStream) {
            out.flush()
        }
    }

    /**
     * Populates a 7-byte ADTS header in-place for AAC-LC (profile=2 -> index 1),
     * 16 kHz (freqIdx=8), Mono (chanCfg=1).
     */
    private fun fillAdtsHeader(packetLen: Int, target: ByteArray) {
        val profile = 2 // AAC LC
        val freqIdx = 8 // 16,000 Hz
        val chanCfg = 1 // 1 channel (mono)
        target[0] = 0xFF.toByte()
        target[1] = 0xF9.toByte() // MPEG-2 Layer 0, protection absent = 1
        target[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        target[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
        target[4] = ((packetLen and 0x7FF) shr 3).toByte()
        target[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        target[6] = 0xFC.toByte()
    }

    companion object {
        private const val TAG = "AdtsFileWriter"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_COUNT = 1
        const val BIT_RATE = 32_000
        const val VOICE_PROFILE_BIT_RATE = 64_000
    }
}
