package com.ambientbrain.recorder.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ambientbrain.recorder.service.AmbientCaptureAccessibilityService
import com.ambientbrain.recorder.service.MeetingMetadataHelper
import com.ambientbrain.recorder.service.MeetingRecorderStateBus
import com.ambientbrain.recorder.service.ShizukuAdbManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Daily background worker that deletes raw full-session `.aac` audio files older than
 * `retentionDays` (default 7 days) unless `session.isPinned == true`.
 *
 * Also performs periodic zero-touch AccessibilityService self-healing when
 * `WRITE_SECURE_SETTINGS` was granted, keeping 24/7 capture resilient without notifications.
 *
 * Permanent task/note voice proof clips (`/files/clips/clip_<itemId>.m4a`), transcripts,
 * round sheets, public gallery exports, and summaries are NEVER deleted by this worker.
 */
class AudioPurgeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private fun isInsideAppPrivateStorage(file: File): Boolean {
        val canonical = runCatching { file.canonicalPath }.getOrElse { return false }
        val allowedRoots = listOfNotNull(
            runCatching { applicationContext.filesDir.canonicalPath }.getOrNull(),
            runCatching { applicationContext.cacheDir.canonicalPath }.getOrNull(),
            runCatching { applicationContext.externalCacheDir?.canonicalPath }.getOrNull(),
            runCatching { applicationContext.getExternalFilesDir(null)?.canonicalPath }.getOrNull()
        )
        return allowedRoots.any { root ->
            canonical == root || canonical.startsWith("$root${File.separator}")
        }
    }

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext).dao()
        val prefs = PreferencesRepo(applicationContext)
        if (prefs.isListeningEnabled &&
            !AmbientCaptureAccessibilityService.isSystemBound &&
            ShizukuAdbManager.hasWriteSecureSettings(applicationContext)
        ) {
            val healResult = ShizukuAdbManager.selfHealAccessibilityService(
                context = applicationContext,
                forceRebindIfUnbound = true
            )
            Log.i(TAG, "Zero-touch A11y self-heal check: ${healResult.message}")
        }
        val retentionDays = prefs.retentionDays.coerceAtLeast(1)
        val cutoffEpochMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())

        val eligibleSessions = db.getSessionsEligibleForRawPurge(cutoffEpochMs)
        var purgedCount = 0

        for (session in eligibleSessions) {
            val rawPath = session.rawPath
            if (!rawPath.isNullOrBlank()) {
                val rawFile = File(rawPath)
                if (rawFile.exists()) {
                    rawFile.delete()
                }
            }
            // Also reclaim app-private internal meeting tracks (never touching public gallery/Downloads copies)
            val meetingMarker = MeetingMetadataHelper.parseFirst(session.callEventsJson)
            if (meetingMarker != null) {
                listOfNotNull(meetingMarker.geminiAudioPath, meetingMarker.videoPath)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != rawPath }
                    .distinct()
                    .forEach { extraPath ->
                        runCatching {
                            val f = File(extraPath)
                            if (f.exists() && isInsideAppPrivateStorage(f)) {
                                f.delete()
                            }
                        }
                    }
            }
            db.markSessionRawAudioPurged(session.id)
            purgedCount++
        }

        // Also sweep any orphaned temporary chunk directories or share exports older than 24 hours
        val nowMs = System.currentTimeMillis()
        val oneDayAgo = nowMs - TimeUnit.HOURS.toMillis(24)
        runCatching {
            File(applicationContext.cacheDir, "audio_chunks").listFiles()?.forEach { dir ->
                if (dir.lastModified() < oneDayAgo) {
                    dir.deleteRecursively()
                }
            }
        }
        runCatching {
            File(applicationContext.cacheDir, "exports").listFiles()?.forEach { file ->
                if (file.lastModified() < oneDayAgo) {
                    file.delete()
                }
            }
        }

        // Reclaim orphaned Stealth ADB temp media/preview files left behind by an OS kill/OOM
        val meetingState = MeetingRecorderStateBus.state.value
        if (!meetingState.isRecording && !meetingState.isFinalizing) {
            val sixHoursAgo = nowMs - TimeUnit.HOURS.toMillis(6)
            runCatching {
                applicationContext.externalCacheDir?.listFiles()?.forEach { file ->
                    val name = file.name
                    val isStealthTemp = name.startsWith("adb_vdisp_") ||
                        name.startsWith("adb_vaud_") ||
                        name.startsWith("preview_") ||
                        name.endsWith(".tmp")
                    if (isStealthTemp && file.lastModified() < sixHoursAgo) {
                        file.delete()
                    }
                }
            }
        }

        Log.i(TAG, "AudioPurgeWorker completed: purged $purgedCount raw session files (retention=${retentionDays}d)")
        return Result.success()
    }

    companion object {
        private const val TAG = "AudioPurgeWorker"
        private const val UNIQUE_WORK_NAME = "ambient_brain_daily_raw_audio_purge"

        fun scheduleDaily(context: Context) {
            val request = PeriodicWorkRequestBuilder<AudioPurgeWorker>(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<AudioPurgeWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
