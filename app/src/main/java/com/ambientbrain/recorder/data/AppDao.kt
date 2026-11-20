package com.ambientbrain.recorder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    // --- Sessions ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
          AND context != 'NOISE_OR_TV'
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
          AND context != 'NOISE_OR_TV'
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
     * Inserts a new session or updates an existing one in-place via `@Update`
     * (never using `REPLACE` on an existing row, which would trigger `ON DELETE CASCADE`
     * on child tables in SQLite).
     */
    @Transaction
    suspend fun upsertSessionSafelyNoCascade(session: SessionEntity) {
        val existing = getSessionById(session.id)
        if (existing == null) {
            upsertSession(session)
        } else {
            updateSession(session)
        }
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

