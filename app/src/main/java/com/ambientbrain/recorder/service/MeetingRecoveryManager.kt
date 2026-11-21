package com.ambientbrain.recorder.service

import android.content.Context
import android.util.Log
import com.ambientbrain.recorder.data.AppDatabase
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SessionEntity
import com.ambientbrain.recorder.data.SessionMode
import com.ambientbrain.recorder.data.Status
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class PendingMeetingCheckpoint(
    val sessionId: String,
    val appLabel: String,
    val targetPackageName: String = "",
    val meetingTitle: String = "",
    val captureModeName: String = "",
    val videoModeName: String = "",
    val isClinical: Boolean = false,
    val ignoreInternalForGemini: Boolean = true,
    val startWallMs: Long = 0L,
    val tempAudioPath: String = "",
    val tempVideoPath: String = "",
    val privateAudioPath: String = ""
)

/**
 * Persists a lightweight synchronous checkpoint while a Zoom/Webex meeting recording is in flight
 * and automatically recovers intact ADTS `.aac` audio (and valid `.mp4` video if finalized) after
 * an OOM-kill, crash, or battery exhaustion so long meetings are never silently lost.
 */
object MeetingRecoveryManager {
    private const val TAG = "MeetingRecoveryMgr"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun saveCheckpoint(context: Context, checkpoint: PendingMeetingCheckpoint) {
        runCatching {
            PreferencesRepo(context.applicationContext).pendingMeetingRecoveryJson =
                json.encodeToString(checkpoint)
        }.onFailure { err ->
            Log.w(TAG, "Failed saving meeting recovery checkpoint: ${err.message}")
        }
    }

    fun clearCheckpoint(context: Context) {
        runCatching {
            PreferencesRepo(context.applicationContext).pendingMeetingRecoveryJson = ""
        }
    }

    fun getProtectedTempPaths(context: Context): Set<String> {
        val raw = PreferencesRepo(context.applicationContext).pendingMeetingRecoveryJson
        if (raw.isBlank()) return emptySet()
        val cp = runCatching { json.decodeFromString<PendingMeetingCheckpoint>(raw) }.getOrNull()
            ?: return emptySet()
        return listOf(cp.tempAudioPath, cp.tempVideoPath, cp.privateAudioPath)
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() ?: it }
            .toSet()
    }

    /**
     * Checks whether a prior meeting recording was interrupted unexpectedly and recovers any
     * playable audio/video into Room + public Downloads/custom folder.
     *
     * Returns the recovered session title if a session was recovered, or null otherwise.
     */
    suspend fun recoverUnfinishedSessionIfAny(
        context: Context,
        allowWhileStarting: Boolean = false
    ): String? {
        val appCtx = context.applicationContext
        val liveState = MeetingRecorderStateBus.state.value
        if (!allowWhileStarting && (liveState.isRecording || liveState.isFinalizing)) return null

        // Always restore any stranded AppOps RECORD_AUDIO lock first
        AdbVirtualScreenController.restorePendingAppOpsMicLockIfAny(appCtx)

        val prefs = PreferencesRepo(appCtx)
        val rawJson = prefs.pendingMeetingRecoveryJson
        if (rawJson.isBlank()) return null

        val cp = runCatching { json.decodeFromString<PendingMeetingCheckpoint>(rawJson) }.getOrNull()
        if (cp == null || cp.sessionId.isBlank()) {
            prefs.pendingMeetingRecoveryJson = ""
            return null
        }
        if (cp.sessionId == liveState.sessionId) return null

        // If the session was already saved to Room before crash, just clear the checkpoint
        val dao = AppDatabase.get(appCtx).dao()
        val alreadySaved = runCatching { dao.getSessionById(cp.sessionId) != null }.getOrDefault(false)
        if (alreadySaved) {
            prefs.pendingMeetingRecoveryJson = ""
            return null
        }

        val tempAudioFile = cp.tempAudioPath.takeIf { it.isNotBlank() }?.let { File(it) }
        val privateAudioFile = cp.privateAudioPath.takeIf { it.isNotBlank() }?.let { File(it) }
        val tempVideoFile = cp.tempVideoPath.takeIf { it.isNotBlank() }?.let { File(it) }

        val sessionsDir = File(appCtx.filesDir, "sessions").apply { mkdirs() }
        var recoveredAudioFile: File? = null

        if (privateAudioFile != null && privateAudioFile.exists() && privateAudioFile.length() >= 2048L) {
            recoveredAudioFile = privateAudioFile
        } else if (tempAudioFile != null && tempAudioFile.exists() && tempAudioFile.length() >= 2048L) {
            val dest = File(sessionsDir, "${cp.sessionId}.aac")
            val copied = runCatching {
                tempAudioFile.copyTo(dest, overwrite = true)
                dest.exists() && dest.length() >= 2048L
            }.getOrDefault(false)
            if (copied) {
                recoveredAudioFile = dest
                AdbVirtualScreenController.deleteTempFileBestEffort(appCtx, tempAudioFile)
            }
        }

        val hasValidVideo = tempVideoFile != null &&
            tempVideoFile.exists() &&
            tempVideoFile.length() >= 4096L &&
            MeetingMp4Muxer.isValidMp4File(tempVideoFile)

        if (recoveredAudioFile == null && !hasValidVideo) {
            // Nothing usable survived the crash — clean up dead temp files & clear checkpoint
            AdbVirtualScreenController.deleteTempFileBestEffort(appCtx, tempAudioFile)
            AdbVirtualScreenController.deleteTempFileBestEffort(appCtx, tempVideoFile)
            prefs.pendingMeetingRecoveryJson = ""
            Log.i(TAG, "Cleared non-recoverable meeting checkpoint ${cp.sessionId}")
            return null
        }

        val startMs = if (cp.startWallMs > 0L) cp.startWallMs else System.currentTimeMillis()
        val cleanApp = cp.appLabel.ifBlank { "Meeting" }
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(startMs))
        val baseTitle = cp.meetingTitle.takeIf { it.isNotBlank() } ?: "$cleanApp Recording ($timeFmt)"
        val recoveredTitle = "[Recovered] $baseTitle"

        var savedPublicUri: String? = null
        var savedPublicDisplayPath: String? = null
        var fallbackPrivateVideoPath: String? = null

        if (hasValidVideo && tempVideoFile != null) {
            val meetingsDir = File(appCtx.filesDir, "meetings").apply { mkdirs() }
            val stagedMp4 = File(meetingsDir, "${cp.sessionId}.mp4")
            val muxedOk = MeetingMp4Muxer.muxVideoAndAdtsAudio(
                videoOnlyMp4 = tempVideoFile,
                adtsAacFile = recoveredAudioFile,
                outputMp4 = stagedMp4
            )
            if (muxedOk && stagedMp4.exists() && stagedMp4.length() > 1024L) {
                runCatching { stagedMp4.setLastModified(startMs) }
                val publicRes = MeetingMp4Muxer.saveVideoToConfiguredPublicFolder(
                    context = appCtx,
                    sourceMp4 = stagedMp4,
                    meetingTitle = recoveredTitle,
                    startWallMs = startMs,
                    prefs = prefs
                )
                if (publicRes.contentUri != null) {
                    savedPublicUri = publicRes.contentUri
                    savedPublicDisplayPath = publicRes.displayPath
                    runCatching { stagedMp4.delete() }
                } else {
                    fallbackPrivateVideoPath = stagedMp4.absolutePath
                }
            }
        }
        AdbVirtualScreenController.deleteTempFileBestEffort(appCtx, tempVideoFile)

        // Estimate duration from ADTS AAC bitrate (~8 KB/s at 64 kbps) or file modification timestamp
        val latestFileMtime = maxOf(
            recoveredAudioFile?.lastModified() ?: 0L,
            startMs + 5_000L
        )
        val estimatedDurationMs = when {
            recoveredAudioFile != null && recoveredAudioFile.length() > 0L ->
                ((recoveredAudioFile.length() * 1000L) / 8_000L).coerceAtLeast(1_000L)
            else -> (latestFileMtime - startMs).coerceAtLeast(1_000L)
        }
        val endMs = maxOf(startMs + estimatedDurationMs, latestFileMtime)

        val marker = MeetingMetadataMarker(
            platform = cleanApp,
            targetPackageName = cp.targetPackageName.takeIf { it.isNotBlank() },
            captureMode = cp.captureModeName.ifBlank { MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED.name },
            videoMode = cp.videoModeName.ifBlank { MeetingVideoMode.ADB_VIRTUAL_SCREEN.name },
            meetingTitle = recoveredTitle,
            internalAudioCaptured = recoveredAudioFile != null,
            ignoreInternalAppAudioForGemini = cp.ignoreInternalForGemini,
            videoPath = fallbackPrivateVideoPath,
            galleryUri = savedPublicUri,
            publicVideoDisplayPath = savedPublicDisplayPath
        )

        val videoSuffix = when {
            !savedPublicDisplayPath.isNullOrBlank() -> " + MP4 exported to $savedPublicDisplayPath"
            fallbackPrivateVideoPath != null -> " + MP4 video"
            tempVideoFile != null -> " (unfinished MP4 video could not be finalized after unexpected exit; full AAC audio recovered)"
            else -> ""
        }

        val entity = SessionEntity(
            id = cp.sessionId,
            roundGroupId = if (cp.isClinical) cp.sessionId else null,
            eventGroupId = cp.sessionId,
            mode = if (cp.isClinical) SessionMode.ROUND_MANUAL else SessionMode.EVERYDAY,
            startMs = startMs,
            endMs = endMs,
            speechMs = estimatedDurationMs,
            hadPhoneCall = false,
            callEventsJson = MeetingMetadataHelper.encode(marker),
            rawPath = recoveredAudioFile?.absolutePath,
            rawBytes = recoveredAudioFile?.length() ?: 0L,
            isRawPurged = recoveredAudioFile == null,
            isPinned = true,
            context = if (cp.isClinical) "CASE_CONFERENCE" else "ONLINE_MEETING",
            title = recoveredTitle,
            summary = "Automatically recovered interrupted $cleanApp recording$videoSuffix.",
            status = Status.DONE
        )

        dao.upsertSession(entity)
        prefs.pendingMeetingRecoveryJson = ""
        Log.i(TAG, "Recovered interrupted meeting session ${cp.sessionId} ($recoveredTitle, audio=${recoveredAudioFile != null}, video=${savedPublicUri != null || fallbackPrivateVideoPath != null})")
        return recoveredTitle
    }
}
