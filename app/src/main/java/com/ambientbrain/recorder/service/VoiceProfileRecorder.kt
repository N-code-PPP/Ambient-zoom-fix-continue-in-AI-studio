package com.ambientbrain.recorder.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.ambientbrain.recorder.data.ClipSlicer
import com.ambientbrain.recorder.data.PreferencesRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Records a reference voice sample ("My Voice Profile" or a Speaker Roster sample) into
 * `/files/profiles/` as either a lossless 16 kHz 16-bit mono `.wav` (when `outFile` ends with `.wav`)
 * or a 64 kbps ADTS `.aac` clip so Gemini and on-device voiceprint matching can consistently
 * identify speakers across ward rounds and everyday conversations.
 */
object VoiceProfileRecorder {

    @SuppressLint("MissingPermission")
    suspend fun recordFifteenSecondProfile(
        context: Context,
        durationSeconds: Int = 12,
        onProgressSec: (Int) -> Unit
    ): Boolean {
        val prefs = PreferencesRepo(context)
        return recordVoiceClipToFile(
            outFile = prefs.getMyVoiceProfileFile(),
            durationSeconds = durationSeconds,
            onProgressSec = onProgressSec
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun recordVoiceClipToFile(
        outFile: java.io.File,
        durationSeconds: Int = 10,
        onProgressSec: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        CaptureEngineController.acquireExternalMicPause(
            reason = "Voice Profile Enrollment",
            timeoutMs = 1_500L
        )
        var record: AudioRecord? = null
        var writer: AdtsFileWriter? = null
        try {
            outFile.parentFile?.mkdirs()
            if (outFile.exists()) outFile.delete()

            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate * 2)

            val created = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()

            if (created.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { created.release() }
                return@withContext false
            }
            record = created

            val writeWav = outFile.name.endsWith(".wav", ignoreCase = true)
            val totalSamplesTarget = sampleRate * durationSeconds
            val pcmAcc = if (writeWav) ShortArray(totalSamplesTarget + 2048) else null
            val adtsWriter = if (!writeWav) {
                AdtsFileWriter(
                    outputFile = outFile,
                    bufferPool = null,
                    bitRate = AdtsFileWriter.VOICE_PROFILE_BIT_RATE
                ).also {
                    writer = it
                    it.start()
                }
            } else {
                null
            }

            val buf = ShortArray(1024)
            var samplesRead = 0
            val deadlineMonoMs = SystemClock.elapsedRealtime() + (durationSeconds * 1000L) + 5_000L
            var consecutiveZeroOrErrorReads = 0

            created.startRecording()
            if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext false
            }

            while (samplesRead < totalSamplesTarget && currentCoroutineContext().isActive) {
                if (SystemClock.elapsedRealtime() > deadlineMonoMs) {
                    return@withContext false
                }
                val r = created.read(buf, 0, buf.size)
                if (r > 0) {
                    consecutiveZeroOrErrorReads = 0
                    if (pcmAcc != null) {
                        val copyLen = minOf(r, pcmAcc.size - samplesRead).coerceAtLeast(0)
                        if (copyLen > 0) {
                            System.arraycopy(buf, 0, pcmAcc, samplesRead, copyLen)
                        }
                    } else {
                        adtsWriter?.writePcmShorts(buf, 0, r)
                    }
                    samplesRead += r
                    onProgressSec((samplesRead / sampleRate).coerceAtMost(durationSeconds))
                } else {
                    if (++consecutiveZeroOrErrorReads > 100) {
                        return@withContext false
                    }
                    delay(20L)
                }
            }

            if (samplesRead < totalSamplesTarget) {
                return@withContext false
            }

            if (writeWav && pcmAcc != null) {
                val finalPcm = pcmAcc.copyOf(samplesRead.coerceAtMost(pcmAcc.size))
                ClipSlicer.writeMono16kWavFile(finalPcm, outFile, sampleRate)
            } else {
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { writer?.finishAndClose() }
            CaptureEngineController.releaseExternalMicPause()
        }
    }
}
