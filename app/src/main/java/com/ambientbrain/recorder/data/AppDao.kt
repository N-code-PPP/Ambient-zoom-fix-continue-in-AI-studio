package com.ambientbrain.recorder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class SpeakerProfileRename(
    val speakerId: String,
    val newLabel: String,
    val matchedProfileId: String?,
    val nickname: String?,
    val relatableTitle: String?,
    val voiceSignature: String?,
    val isAutoEnrolled: Boolean
)

enum class NoMeaningfulPersistOutcome {
    SESSION_DELETED,
    PRESERVED_PRIOR_DATA,
    MARKED_NO_MEANINGFUL
}

@Dao
interface AppDao {

    // --- Sessions ---
    /**
     * Inserts a new session or updates an existing session in-place via Room `@Upsert`
     * (`INSERT` with fallback to `UPDATE`), avoiding SQLite `INSERT OR REPLACE` which deletes
     * the parent row and triggers `ON DELETE CASCADE` on child tables.
     */
    @Upsert
    suspend fun upsertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    fun observeSessionWithDetails(sessionId: String): Flow<SessionWithDetails?>

    @Transaction
    @Query("SELECT * FROM sessions ORDER BY startMs DESC")
    fun observeAllSessionsWithDetails(): Flow<List<SessionWithDetails>>

    @Transaction
    @Query(
        """
        SELECT * FROM sessions
        WHERE mode IN ('ROUND_MANUAL', 'ROUND_AUTO')
           OR context IN ('NEURO_ROUND', 'MEDICAL_ROUND', 'CASE_CONFERENCE', 'HANDOVER')
        ORDER BY startMs DESC
        """
    )
    fun observeRoundSessions(): Flow<List<SessionWithDetails>>

    @Query(
        """
        SELECT * FROM sessions
        WHERE (mode IN ('ROUND_MANUAL', 'ROUND_AUTO') OR context IN ('NEURO_ROUND', 'MEDICAL_ROUND', 'CASE_CONFERENCE', 'HANDOVER'))
          AND endMs >= :sinceEpochMs
          AND id != :excludeSessionId
        ORDER BY endMs DESC
        LIMIT 1
        """
    )
    suspend fun findRecentRoundSessionSince(sinceEpochMs: Long, excludeSessionId: String): SessionEntity?

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionWithDetailsOnce(sessionId: String): SessionWithDetails?

    @Transaction
    @Query("SELECT * FROM sessions WHERE id IN (:sessionIds) ORDER BY startMs ASC")
    suspend fun getSessionsWithDetailsByIds(sessionIds: List<String>): List<SessionWithDetails>

    @Query("SELECT * FROM sessions WHERE id IN (:sessionIds) ORDER BY startMs ASC")
    suspend fun getSessionsByIds(sessionIds: List<String>): List<SessionEntity>

    @Query(
        """
        SELECT * FROM sessions
        WHERE endMs >= :sinceEpochMs
          AND id != :excludeSessionId
          AND (context IS NULL OR context != 'NOISE_OR_TV')
          AND combinedSourceIdsJson IS NULL
          AND status IN ('DONE', 'ANALYZING', 'CLIPPING', 'QUEUED')
        ORDER BY endMs DESC
        LIMIT 1
        """
    )
    suspend fun findRecentSessionForEventGroup(sinceEpochMs: Long, excludeSessionId: String): SessionEntity?

    @Transaction
    @Query(
        """
        SELECT * FROM sessions
        WHERE (eventGroupId = :groupId OR roundGroupId = :groupId)
          AND startMs < :beforeStartMs
          AND id != :excludeSessionId
          AND combinedSourceIdsJson IS NULL
          AND status = 'DONE'
          AND (context IS NULL OR context != 'NOISE_OR_TV')
        ORDER BY startMs DESC
        LIMIT 2
        """
    )
    suspend fun findPriorContextSessionsInGroup(
        groupId: String,
        beforeStartMs: Long,
        excludeSessionId: String
    ): List<SessionWithDetails>

    @Query("SELECT * FROM sessions WHERE combinedSourceIdsJson IS NOT NULL ORDER BY startMs DESC")
    suspend fun getCombinedSessions(): List<SessionEntity>

    @Transaction
    @Query(
        """
        SELECT * FROM sessions
        WHERE combinedSourceIdsJson IS NULL
          AND status = 'DONE'
          AND (context IS NULL OR context != 'NOISE_OR_TV')
        ORDER BY startMs ASC
        """
    )
    suspend fun getCompletedIndividualSessionsWithDetails(): List<SessionWithDetails>

    @Query("UPDATE sessions SET isPinned = :pinned WHERE id = :sessionId")
    suspend fun setSessionPinned(sessionId: String, pinned: Boolean)

    @Query("UPDATE sessions SET mode = :mode, roundGroupId = :roundGroupId WHERE id = :sessionId")
    suspend fun updateSessionRoundGroup(sessionId: String, mode: SessionMode, roundGroupId: String?)

    @Query("UPDATE sessions SET eventGroupId = :eventGroupId WHERE id = :sessionId")
    suspend fun updateSessionEventGroup(sessionId: String, eventGroupId: String?)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    // --- Segment Map ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegmentMaps(segments: List<SegmentMapEntity>)

    @Query("SELECT * FROM segment_map WHERE sessionId = :sessionId ORDER BY fileOffsetMs ASC")
    suspend fun getSegmentMapForSession(sessionId: String): List<SegmentMapEntity>

    // --- Speakers & Sentences ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpeakers(speakers: List<SessionSpeakerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSentences(sentences: List<TranscriptSentenceEntity>)

    @Query("DELETE FROM speakers WHERE sessionId = :sessionId")
    suspend fun deleteSpeakersForSession(sessionId: String)

    @Query("DELETE FROM transcript_sentences WHERE sessionId = :sessionId")
    suspend fun deleteSentencesForSession(sessionId: String)

    @Query("UPDATE speakers SET speakerLabel = :newLabel WHERE sessionId = :sessionId AND speakerId = :speakerId")
    suspend fun updateSpeakerTableLabel(sessionId: String, speakerId: String, newLabel: String)

    @Query("UPDATE transcript_sentences SET speakerLabel = :newLabel WHERE sessionId = :sessionId AND speakerId = :speakerId")
    suspend fun updateSentenceSpeakerLabels(sessionId: String, speakerId: String, newLabel: String)

    @Query("UPDATE items SET speakerLabel = :newLabel WHERE sessionId = :sessionId AND speakerId = :speakerId")
    suspend fun updateItemSpeakerLabels(sessionId: String, speakerId: String, newLabel: String)

    @Query(
        """
        UPDATE speakers
        SET matchedProfileId = :matchedProfileId,
            nickname = :nickname,
            relatableTitle = :relatableTitle,
            inferredRole = COALESCE(:relatableTitle, inferredRole),
            voiceSignature = COALESCE(:voiceSignature, voiceSignature),
            isAutoEnrolled = :isAutoEnrolled
        WHERE sessionId = :sessionId AND speakerId = :speakerId
        """
    )
    suspend fun updateSpeakerProfileMetadata(
        sessionId: String,
        speakerId: String,
        matchedProfileId: String?,
        nickname: String?,
        relatableTitle: String?,
        voiceSignature: String?,
        isAutoEnrolled: Boolean
    )

    @Transaction
    suspend fun renameSpeakerAcrossSession(sessionId: String, speakerId: String, newLabel: String) {
        updateSpeakerTableLabel(sessionId, speakerId, newLabel)
        updateSentenceSpeakerLabels(sessionId, speakerId, newLabel)
        updateItemSpeakerLabels(sessionId, speakerId, newLabel)
    }

    @Transaction
    suspend fun renameSpeakerProfileAcrossSession(
        sessionId: String,
        speakerId: String,
        newLabel: String,
        matchedProfileId: String?,
        nickname: String?,
        relatableTitle: String?,
        voiceSignature: String?,
        isAutoEnrolled: Boolean
    ) {
        updateSpeakerTableLabel(sessionId, speakerId, newLabel)
        updateSpeakerProfileMetadata(
            sessionId = sessionId,
            speakerId = speakerId,
            matchedProfileId = matchedProfileId,
            nickname = nickname,
            relatableTitle = relatableTitle,
            voiceSignature = voiceSignature,
            isAutoEnrolled = isAutoEnrolled
        )
        updateSentenceSpeakerLabels(sessionId, speakerId, newLabel)
        updateItemSpeakerLabels(sessionId, speakerId, newLabel)
    }

    // --- Full-Text Search across Bilingual Transcripts ---
    @Query(
        """
        SELECT s.* FROM transcript_sentences s
        JOIN transcript_fts fts ON s.id = fts.rowid
        WHERE transcript_fts MATCH :ftsQuery
        ORDER BY s.approxMs ASC
        """
    )
    suspend fun searchTranscriptSentences(ftsQuery: String): List<TranscriptSentenceEntity>

    // --- Patients ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatients(patients: List<PatientBlockEntity>)

    @Query("DELETE FROM patients WHERE sessionId = :sessionId")
    suspend fun deletePatientsForSession(sessionId: String)

    @Query("UPDATE patients SET roundGroupId = :roundGroupId WHERE sessionId = :sessionId")
    suspend fun updatePatientsRoundGroup(sessionId: String, roundGroupId: String)

    // --- Items (Tasks, Notes, Decisions, Events) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE sessionId = :sessionId")
    suspend fun deleteItemsForSession(sessionId: String)

    @Query("SELECT * FROM items WHERE isArchived = 0 ORDER BY isDone ASC, createdMs DESC")
    fun observeAllActiveItems(): Flow<List<ItemEntity>>

    @Query("UPDATE items SET isDone = :done WHERE id = :itemId")
    suspend fun setItemDone(itemId: String, done: Boolean)

    @Query("SELECT clipPath FROM items WHERE sessionId = :sessionId AND clipPath IS NOT NULL")
    suspend fun getClipPathsForSession(sessionId: String): List<String>

    @Query("UPDATE items SET clipPath = NULL, clipStartMs = 0, clipEndMs = 0 WHERE id = :itemId")
    suspend fun clearItemClipPath(itemId: String)

    @Query("UPDATE items SET isArchived = 1 WHERE id = :itemId")
    suspend fun archiveItem(itemId: String)

    @Query("UPDATE items SET isArchived = 0 WHERE id = :itemId")
    suspend fun unarchiveItem(itemId: String)

    @Query("UPDATE items SET isArchived = 1 WHERE isDone = 1 AND isArchived = 0")
    suspend fun archiveAllCompletedItems(): Int

    @Query("UPDATE items SET title = :title, details = :details, assignee = :assignee, due = :due, needsVerification = :needsVerification WHERE id = :itemId")
    suspend fun updateItemContent(
        itemId: String,
        title: String,
        details: String,
        assignee: String?,
        due: String?,
        needsVerification: Boolean
    )

    // --- Purge Queries ---
    @Query(
        """
        SELECT * FROM sessions
        WHERE isRawPurged = 0
          AND isPinned = 0
          AND rawPath IS NOT NULL
          AND endMs > 0
          AND endMs < :cutoffEpochMs
          AND status IN ('DONE', 'NOT_MEANINGFUL', 'SKIPPED_NO_SPEECH')
        """
    )
    suspend fun getSessionsEligibleForRawPurge(cutoffEpochMs: Long): List<SessionEntity>

    @Query("UPDATE sessions SET rawPath = NULL, rawBytes = 0, isRawPurged = 1 WHERE id = :sessionId")
    suspend fun markSessionRawAudioPurged(sessionId: String)

    // --- Retry & Failed Sessions Queries ---
    @Query(
        """
        SELECT * FROM sessions
        WHERE status IN ('FAILED_RETRYABLE', 'FAILED_PERMANENT')
          AND (rawPath IS NOT NULL OR combinedSourceIdsJson IS NOT NULL)
        ORDER BY startMs DESC
        """
    )
    fun observeFailedSessions(): Flow<List<SessionEntity>>

    @Query(
        """
        SELECT * FROM sessions
        WHERE status IN ('FAILED_RETRYABLE', 'FAILED_PERMANENT')
          AND (rawPath IS NOT NULL OR combinedSourceIdsJson IS NOT NULL)
        ORDER BY startMs DESC
        """
    )
    suspend fun getFailedSessions(): List<SessionEntity>

    @Query(
        """
        SELECT COUNT(*) FROM sessions
        WHERE status IN ('FAILED_RETRYABLE', 'FAILED_PERMANENT')
          AND (rawPath IS NOT NULL OR combinedSourceIdsJson IS NOT NULL)
        """
    )
    fun observeFailedSessionsCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM sessions
        WHERE status = 'QUEUED'
          AND (rawPath IS NOT NULL OR combinedSourceIdsJson IS NOT NULL)
        ORDER BY startMs ASC
        """
    )
    suspend fun getQueuedSessions(): List<SessionEntity>

    @Query(
        """
        SELECT COUNT(*) FROM sessions
        WHERE status = 'QUEUED'
          AND (rawPath IS NOT NULL OR combinedSourceIdsJson IS NOT NULL)
        """
    )
    fun observeQueuedSessionsCount(): Flow<Int>

    @Query(
        """
        UPDATE sessions
        SET status = 'QUEUED', retryCount = 0, nextRetryEpochMs = 0, lastError = NULL
        WHERE id = :sessionId
        """
    )
    suspend fun resetSessionForRetry(sessionId: String)

    @Query(
        """
        UPDATE sessions
        SET summary = CASE WHEN summary = :checkpointMarker THEN NULL ELSE summary END,
            status = 'QUEUED',
            retryCount = 0,
            nextRetryEpochMs = 0,
            lastError = NULL
        WHERE id = :sessionId
        """
    )
    suspend fun resetSessionForFreshRetry(sessionId: String, checkpointMarker: String): Int

    @Query("UPDATE sessions SET status = :status WHERE id = :sessionId")
    suspend fun updateSessionStatus(sessionId: String, status: Status): Int

    @Query("UPDATE sessions SET status = :status, lastError = :lastError WHERE id = :sessionId")
    suspend fun updateSessionStatusAndError(sessionId: String, status: Status, lastError: String?): Int

    @Query(
        """
        UPDATE sessions
        SET status = 'QUEUED', nextRetryEpochMs = :nextRetryEpochMs, lastError = :lastError
        WHERE id = :sessionId
        """
    )
    suspend fun updateSessionPausedState(sessionId: String, nextRetryEpochMs: Long, lastError: String?): Int

    @Query(
        """
        UPDATE sessions
        SET summary = :summary, status = 'QUEUED', nextRetryEpochMs = :nextRetryEpochMs, lastError = :lastError
        WHERE id = :sessionId
        """
    )
    suspend fun updateSessionStage2CheckpointPaused(
        sessionId: String,
        summary: String,
        nextRetryEpochMs: Long,
        lastError: String?
    ): Int

    @Query(
        """
        UPDATE sessions
        SET status = :status, retryCount = :retryCount, nextRetryEpochMs = :nextRetryEpochMs, lastError = :lastError
        WHERE id = :sessionId
        """
    )
    suspend fun updateSessionQueueOrFailureState(
        sessionId: String,
        status: Status,
        retryCount: Int,
        nextRetryEpochMs: Long,
        lastError: String?
    ): Int

    @Query(
        """
        UPDATE sessions
        SET summary = :summary,
            status = :status,
            retryCount = :retryCount,
            nextRetryEpochMs = :nextRetryEpochMs,
            lastError = :lastError
        WHERE id = :sessionId
        """
    )
    suspend fun updateSessionStage2CheckpointFailure(
        sessionId: String,
        summary: String,
        status: Status,
        retryCount: Int,
        nextRetryEpochMs: Long,
        lastError: String?
    ): Int

    @Query("UPDATE sessions SET nextRetryEpochMs = 0, lastError = NULL WHERE id = :sessionId")
    suspend fun clearSessionRetryError(sessionId: String): Int

    @Query(
        """
        UPDATE sessions
        SET rawPath = :rawPath,
            rawBytes = :rawBytes,
            isRawPurged = 0,
            status = 'QUEUED',
            retryCount = 0,
            nextRetryEpochMs = 0,
            lastError = NULL
        WHERE id = :sessionId
        """
    )
    suspend fun updateSessionRawAudioAndQueue(sessionId: String, rawPath: String, rawBytes: Long): Int

    @Query(
        """
        UPDATE sessions
        SET status = 'DONE', summary = :summary, retryCount = 0, nextRetryEpochMs = 0, lastError = NULL
        WHERE id = :sessionId
        """
    )
    suspend fun completeSessionExcludedMeeting(sessionId: String, summary: String): Int

    @Query(
        """
        UPDATE sessions
        SET rawPath = :rawPath,
            rawBytes = :rawBytes,
            isRawPurged = CASE WHEN :rawPath IS NULL THEN 1 ELSE 0 END,
            eventGroupId = :eventGroupId,
            context = :context,
            title = :title,
            summary = :summary,
            keyPointsJson = NULL,
            status = 'DONE',
            retryCount = 0,
            nextRetryEpochMs = 0,
            lastError = NULL
        WHERE id = :sessionId
        """
    )
    suspend fun updateSessionNoMeaningfulFields(
        sessionId: String,
        rawPath: String?,
        rawBytes: Long,
        eventGroupId: String?,
        context: String?,
        title: String?,
        summary: String?
    ): Int

    @Query(
        """
        UPDATE sessions
        SET eventGroupId = :eventGroupId,
            title = :title,
            summary = :summary,
            status = 'ANALYZING',
            retryCount = 0,
            nextRetryEpochMs = 0,
            lastError = NULL
        WHERE id = :sessionId
        """
    )
    suspend fun updateSessionStage1CheckpointFields(
        sessionId: String,
        eventGroupId: String?,
        title: String?,
        summary: String
    ): Int

    @Query(
        """
        UPDATE sessions
        SET mode = :mode,
            roundGroupId = :roundGroupId,
            eventGroupId = :eventGroupId,
            context = :context,
            title = :title,
            summary = :summary,
            keyPointsJson = :keyPointsJson,
            status = 'DONE',
            retryCount = 0,
            nextRetryEpochMs = 0,
            lastError = NULL
        WHERE id = :sessionId
        """
    )
    suspend fun updateSessionCompletedFields(
        sessionId: String,
        mode: SessionMode,
        roundGroupId: String?,
        eventGroupId: String?,
        context: String?,
        title: String?,
        summary: String?,
        keyPointsJson: String?
    ): Int

    @Query(
        """
        UPDATE sessions
        SET mode = :mode,
            roundGroupId = :roundGroupId,
            eventGroupId = :eventGroupId,
            context = :context,
            title = :title,
            summary = :summary,
            keyPointsJson = :keyPointsJson,
            status = 'DONE',
            retryCount = 0,
            nextRetryEpochMs = 0,
            lastError = NULL,
            aiReviewReport = :aiReviewReport,
            reviewedAtMs = :reviewedAtMs,
            reviewSourceMode = :reviewSourceMode
        WHERE id = :sessionId
        """
    )
    suspend fun updateSessionCompletedWithReviewFields(
        sessionId: String,
        mode: SessionMode,
        roundGroupId: String?,
        eventGroupId: String?,
        context: String?,
        title: String?,
        summary: String?,
        keyPointsJson: String?,
        aiReviewReport: String?,
        reviewedAtMs: Long,
        reviewSourceMode: String?
    ): Int

    // --- Analysis Runs & Token Metrics ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalysisRun(run: AnalysisRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalysisRuns(runs: List<AnalysisRunEntity>)

    @Query("SELECT COALESCE(SUM(inTokens + outTokens), 0) FROM analysis_runs WHERE atMs >= :sinceMs")
    fun observeTotalTokensSince(sinceMs: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(cachedTokens), 0) FROM analysis_runs WHERE atMs >= :sinceMs")
    fun observeCachedTokensSince(sinceMs: Long): Flow<Long>

    @Query(
        """
        SELECT
            COUNT(*) AS totalCalls,
            COALESCE(SUM(CASE WHEN ok = 1 THEN 1 ELSE 0 END), 0) AS successCalls,
            COALESCE(SUM(CASE WHEN ok = 0 THEN 1 ELSE 0 END), 0) AS failedCalls,
            COALESCE(SUM(inTokens), 0) AS totalInTokens,
            COALESCE(SUM(outTokens), 0) AS totalOutTokens,
            COALESCE(SUM(cachedTokens), 0) AS totalCachedTokens,
            COALESCE(CAST(AVG(CASE WHEN ok = 1 AND latencyMs > 0 THEN latencyMs ELSE NULL END) AS INTEGER), 0) AS avgLatencyMs
        FROM analysis_runs
        WHERE atMs >= :sinceMs
        """
    )
    fun observeAnalysisUsageSince(sinceMs: Long): Flow<AnalysisUsageSummary>

    @Query(
        """
        SELECT
            model AS model,
            COUNT(*) AS calls,
            COALESCE(SUM(CASE WHEN ok = 1 THEN 1 ELSE 0 END), 0) AS successCalls,
            COALESCE(SUM(inTokens + outTokens), 0) AS totalTokens,
            COALESCE(CAST(AVG(CASE WHEN ok = 1 AND latencyMs > 0 THEN latencyMs ELSE NULL END) AS INTEGER), 0) AS avgLatencyMs
        FROM analysis_runs
        WHERE atMs >= :sinceMs
        GROUP BY model
        ORDER BY calls DESC
        LIMIT 8
        """
    )
    fun observeModelUsageBreakdownSince(sinceMs: Long): Flow<List<ModelUsageBreakdown>>

    @Query(
        """
        SELECT * FROM analysis_runs
        WHERE ok = 0 AND errorMsg IS NOT NULL
        ORDER BY atMs DESC
        LIMIT :limit
        """
    )
    fun observeRecentFailedAnalysisRuns(limit: Int = 3): Flow<List<AnalysisRunEntity>>

    // --- One-Shot Export & Safe Restore Queries ---
    @Query("SELECT * FROM sessions ORDER BY startMs ASC")
    suspend fun getAllSessionsOnce(): List<SessionEntity>

    @Transaction
    @Query("SELECT * FROM sessions ORDER BY startMs DESC")
    suspend fun getAllSessionsWithDetailsOnce(): List<SessionWithDetails>

    @Query("SELECT * FROM segment_map ORDER BY sessionId ASC, fileOffsetMs ASC")
    suspend fun getAllSegmentMapsOnce(): List<SegmentMapEntity>

    @Query("SELECT * FROM speakers ORDER BY sessionId ASC, speakerId ASC")
    suspend fun getAllSpeakersOnce(): List<SessionSpeakerEntity>

    @Query("SELECT * FROM transcript_sentences ORDER BY sessionId ASC, ordinal ASC")
    suspend fun getAllTranscriptSentencesOnce(): List<TranscriptSentenceEntity>

    @Query("SELECT * FROM patients ORDER BY sessionId ASC, approxStartMs ASC")
    suspend fun getAllPatientsOnce(): List<PatientBlockEntity>

    @Query("SELECT * FROM items ORDER BY createdMs DESC")
    suspend fun getAllItemsOnce(): List<ItemEntity>

    @Query("SELECT * FROM analysis_runs ORDER BY atMs ASC")
    suspend fun getAllAnalysisRunsOnce(): List<AnalysisRunEntity>

    @Query("SELECT sentenceUuid FROM transcript_sentences WHERE sessionId = :sessionId")
    suspend fun getSentenceUuidsForSession(sessionId: String): List<String>

    @Query("SELECT COUNT(*) FROM analysis_runs WHERE sessionId = :sessionId AND atMs = :atMs AND promptVersion = :promptVersion")
    suspend fun countMatchingAnalysisRun(sessionId: String, atMs: Long, promptVersion: String): Int

    /**
     * Inserts a new session or updates an existing one in-place via `@Upsert`
     * (never using `REPLACE` on an existing row, which would trigger `ON DELETE CASCADE`
     * on child tables in SQLite).
     */
    @Transaction
    suspend fun upsertSessionSafelyNoCascade(session: SessionEntity) {
        upsertSession(session)
    }

    @Transaction
    suspend fun insertSessionWithSegmentMaps(
        session: SessionEntity,
        segmentMaps: List<SegmentMapEntity>
    ) {
        upsertSession(session)
        if (segmentMaps.isNotEmpty()) {
            insertSegmentMaps(segmentMaps)
        }
    }

    @Transaction
    suspend fun insertSessionWithItem(
        session: SessionEntity,
        item: ItemEntity
    ) {
        upsertSession(session)
        insertItems(listOf(item))
    }

    /**
     * Safe Preserve for sessions classified as having no meaningful content:
     * - If the session already has real extracted notes/transcripts/patients/items, preserves all
     *   prior metadata and child rows (never downgrading to `NOISE_OR_TV`), restores `Status.DONE`,
     *   and records an informational notice in `lastError`.
     * - Otherwise (brand-new or empty session), marks it `NOISE_OR_TV` / `DONE` while preserving `segment_map`.
     */
    @Transaction
    suspend fun persistNoMeaningfulContentResult(
        sessionId: String,
        rawPath: String?,
        rawBytes: Long,
        eventGroupId: String?,
        context: String?,
        title: String?,
        summary: String?,
        checkpointMarker: String,
        preserveNotice: String
    ): NoMeaningfulPersistOutcome {
        val current = getSessionWithDetailsOnce(sessionId)
            ?: return NoMeaningfulPersistOutcome.SESSION_DELETED
        val s = current.session
        val rawSummary = s.summary?.trim().orEmpty()
        val isPlaceholderMeetingSummary =
            (rawSummary.startsWith("Recorded ", ignoreCase = true) ||
                rawSummary.startsWith("Saved ", ignoreCase = true)) &&
                (rawSummary.contains("recording (", ignoreCase = true) ||
                    rawSummary.contains("Queued for Gemini", ignoreCase = true) ||
                    rawSummary.contains("excluded from Gemini", ignoreCase = true) ||
                    rawSummary.contains("mic-only track for Gemini", ignoreCase = true))
        val isPlaceholderSummary = rawSummary.isBlank() ||
            isPlaceholderMeetingSummary ||
            rawSummary == "100% verbatim transcript completed" ||
            rawSummary == checkpointMarker ||
            rawSummary.equals("Filtered Background Audio", ignoreCase = true) ||
            rawSummary.equals("Background audio or unclassified conversation.", ignoreCase = true) ||
            rawSummary.startsWith("Automatically recovered interrupted ", ignoreCase = true) ||
            rawSummary.contains("meeting saved locally", ignoreCase = true) ||
            rawSummary.contains("no separate local speech detected", ignoreCase = true) ||
            rawSummary.startsWith("Queued Gemini analysis", ignoreCase = true) ||
            rawSummary.startsWith("Auto-merging & reviewing", ignoreCase = true) ||
            rawSummary.startsWith("Synthesizing & reviewing", ignoreCase = true)

        val isMarkedNonMeaningful = s.context == "NOISE_OR_TV" ||
            s.status == Status.NOT_MEANINGFUL ||
            s.status == Status.SKIPPED_NO_SPEECH
        val hasPriorSummary = !isPlaceholderSummary && !isMarkedNonMeaningful
        val hasPriorKeyPoints = !s.keyPointsJson.isNullOrBlank() && s.keyPointsJson.trim() != "[]"
        val hasPriorReview = !s.aiReviewReport.isNullOrBlank()
        val hasPriorExtractedData = hasPriorSummary ||
            hasPriorKeyPoints ||
            hasPriorReview ||
            current.sentences.isNotEmpty() ||
            current.patients.isNotEmpty() ||
            current.items.isNotEmpty()

        if (hasPriorExtractedData) {
            updateSessionQueueOrFailureState(
                sessionId = sessionId,
                status = Status.DONE,
                retryCount = 0,
                nextRetryEpochMs = 0L,
                lastError = preserveNotice
            )
            return NoMeaningfulPersistOutcome.PRESERVED_PRIOR_DATA
        }

        updateSessionNoMeaningfulFields(
            sessionId = sessionId,
            rawPath = rawPath,
            rawBytes = rawBytes,
            eventGroupId = eventGroupId,
            context = context,
            title = title,
            summary = summary
        )
        return NoMeaningfulPersistOutcome.MARKED_NO_MEANINGFUL
    }

    /**
     * Atomically saves the Two-Stage Stage 1 speakers, verbatim transcript, and checkpoint marker.
     * Returns `false` if the session was deleted before commit.
     */
    @Transaction
    suspend fun persistStage1Checkpoint(
        sessionId: String,
        speakers: List<SessionSpeakerEntity>,
        sentences: List<TranscriptSentenceEntity>,
        eventGroupId: String?,
        title: String?,
        checkpointMarker: String
    ): Boolean {
        if (getSessionById(sessionId) == null) return false
        if (speakers.isNotEmpty()) {
            deleteSpeakersForSession(sessionId)
            insertSpeakers(speakers)
        }
        deleteSentencesForSession(sessionId)
        if (sentences.isNotEmpty()) {
            insertSentences(sentences)
        }
        updateSessionStage1CheckpointFields(
            sessionId = sessionId,
            eventGroupId = eventGroupId,
            title = title,
            summary = checkpointMarker
        )
        return true
    }

    /**
     * Atomically persists Two-Stage Stage 2 retroactive speaker renames, patients, items,
     * and DONE session fields while preserving Stage 1 transcript rows and `segment_map`.
     * Returns `false` if the session was deleted before commit.
     */
    @Transaction
    suspend fun persistStage2Synthesis(
        sessionId: String,
        speakerRenames: List<SpeakerProfileRename>,
        patients: List<PatientBlockEntity>,
        items: List<ItemEntity>,
        mode: SessionMode,
        roundGroupId: String?,
        eventGroupId: String?,
        context: String?,
        title: String?,
        summary: String?,
        keyPointsJson: String?
    ): Boolean {
        if (getSessionById(sessionId) == null) return false
        for (rename in speakerRenames) {
            renameSpeakerProfileAcrossSession(
                sessionId = sessionId,
                speakerId = rename.speakerId,
                newLabel = rename.newLabel,
                matchedProfileId = rename.matchedProfileId,
                nickname = rename.nickname,
                relatableTitle = rename.relatableTitle,
                voiceSignature = rename.voiceSignature,
                isAutoEnrolled = rename.isAutoEnrolled
            )
        }
        deletePatientsForSession(sessionId)
        if (patients.isNotEmpty()) {
            insertPatients(patients)
        }
        deleteItemsForSession(sessionId)
        if (items.isNotEmpty()) {
            insertItems(items)
        }
        updateSessionCompletedFields(
            sessionId = sessionId,
            mode = mode,
            roundGroupId = roundGroupId,
            eventGroupId = eventGroupId,
            context = context,
            title = title,
            summary = summary,
            keyPointsJson = keyPointsJson
        )
        return true
    }

    /**
     * Atomically persists single-pass analysis results (including `INFO_EXTRACTION_EN`,
     * `AI_REVIEW_FROM_AUDIO`, and `VERBATIM_ORIGINAL_TRANSCRIPT`).
     * Returns `false` if the session was deleted before commit.
     */
    @Transaction
    suspend fun persistSinglePassAnalysis(
        sessionId: String,
        speakers: List<SessionSpeakerEntity>,
        replaceSentences: Boolean,
        sentences: List<TranscriptSentenceEntity>,
        replaceStructuredData: Boolean,
        patients: List<PatientBlockEntity>,
        items: List<ItemEntity>,
        mode: SessionMode,
        roundGroupId: String?,
        eventGroupId: String?,
        context: String?,
        title: String?,
        summary: String?,
        keyPointsJson: String?,
        aiReviewReport: String?,
        reviewedAtMs: Long,
        reviewSourceMode: String?
    ): Boolean {
        if (getSessionById(sessionId) == null) return false
        if (speakers.isNotEmpty()) {
            deleteSpeakersForSession(sessionId)
            insertSpeakers(speakers)
        }
        if (replaceSentences) {
            deleteSentencesForSession(sessionId)
            if (sentences.isNotEmpty()) {
                insertSentences(sentences)
            }
        }
        if (replaceStructuredData) {
            deletePatientsForSession(sessionId)
            if (patients.isNotEmpty()) {
                insertPatients(patients)
            }
            deleteItemsForSession(sessionId)
            if (items.isNotEmpty()) {
                insertItems(items)
            }
        }
        updateSessionCompletedWithReviewFields(
            sessionId = sessionId,
            mode = mode,
            roundGroupId = roundGroupId,
            eventGroupId = eventGroupId,
            context = context,
            title = title,
            summary = summary,
            keyPointsJson = keyPointsJson,
            aiReviewReport = aiReviewReport,
            reviewedAtMs = reviewedAtMs,
            reviewSourceMode = reviewSourceMode
        )
        return true
    }

    /**
     * Atomically persists `AI_REVIEW_EXTRACTED_ONLY` results while preserving existing single-session
     * transcripts and refreshing combined-session stitched transcripts when requested.
     * Returns `false` if the session was deleted before commit.
     */
    @Transaction
    suspend fun persistExtractedInfoReview(
        sessionId: String,
        speakers: List<SessionSpeakerEntity>,
        replaceSentences: Boolean,
        sentences: List<TranscriptSentenceEntity>,
        patients: List<PatientBlockEntity>,
        items: List<ItemEntity>,
        mode: SessionMode,
        roundGroupId: String?,
        eventGroupId: String?,
        context: String?,
        title: String?,
        summary: String?,
        keyPointsJson: String?,
        aiReviewReport: String?,
        reviewedAtMs: Long,
        reviewSourceMode: String?
    ): Boolean {
        if (getSessionById(sessionId) == null) return false
        if (speakers.isNotEmpty()) {
            deleteSpeakersForSession(sessionId)
            insertSpeakers(speakers)
        }
        if (replaceSentences) {
            deleteSentencesForSession(sessionId)
            if (sentences.isNotEmpty()) {
                insertSentences(sentences)
            }
        }
        deletePatientsForSession(sessionId)
        if (patients.isNotEmpty()) {
            insertPatients(patients)
        }
        deleteItemsForSession(sessionId)
        if (items.isNotEmpty()) {
            insertItems(items)
        }
        updateSessionCompletedWithReviewFields(
            sessionId = sessionId,
            mode = mode,
            roundGroupId = roundGroupId,
            eventGroupId = eventGroupId,
            context = context,
            title = title,
            summary = summary,
            keyPointsJson = keyPointsJson,
            aiReviewReport = aiReviewReport,
            reviewedAtMs = reviewedAtMs,
            reviewSourceMode = reviewSourceMode
        )
        return true
    }

    @Transaction
    suspend fun restoreSessionBundle(
        session: SessionEntity,
        segmentMaps: List<SegmentMapEntity>,
        speakers: List<SessionSpeakerEntity>,
        sentences: List<TranscriptSentenceEntity>,
        patients: List<PatientBlockEntity>,
        items: List<ItemEntity>,
        analysisRuns: List<AnalysisRunEntity>
    ) {
        upsertSessionSafelyNoCascade(session)

        if (segmentMaps.isNotEmpty()) {
            insertSegmentMaps(segmentMaps)
        }
        if (speakers.isNotEmpty()) {
            insertSpeakers(speakers)
        }
        if (sentences.isNotEmpty()) {
            val existingUuids = getSentenceUuidsForSession(session.id).toHashSet()
            val missingSentences = sentences
                .filter { it.sentenceUuid !in existingUuids }
                .map { it.copy(id = 0L) }
            if (missingSentences.isNotEmpty()) {
                insertSentences(missingSentences)
            }
        }
        if (patients.isNotEmpty()) {
            insertPatients(patients)
        }
        if (items.isNotEmpty()) {
            insertItems(items)
        }
        if (analysisRuns.isNotEmpty()) {
            val missingRuns = analysisRuns.filter { run ->
                countMatchingAnalysisRun(run.sessionId, run.atMs, run.promptVersion) == 0
            }.map { it.copy(id = 0L) }
            if (missingRuns.isNotEmpty()) {
                insertAnalysisRuns(missingRuns)
            }
        }
    }
}

