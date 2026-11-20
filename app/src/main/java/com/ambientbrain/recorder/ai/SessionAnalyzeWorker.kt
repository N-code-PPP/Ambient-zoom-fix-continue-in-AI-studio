package com.ambientbrain.recorder.ai

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ambientbrain.recorder.data.AnalysisRunEntity
import com.ambientbrain.recorder.data.AppDatabase
import com.ambientbrain.recorder.data.ClipSlicer
import com.ambientbrain.recorder.data.ItemEntity
import com.ambientbrain.recorder.data.ItemType
import com.ambientbrain.recorder.data.PatientBlockEntity
import com.ambientbrain.recorder.data.PatientPlanOrder
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SegmentMapEntity
import com.ambientbrain.recorder.data.SessionEntity
import com.ambientbrain.recorder.data.SessionMode
import com.ambientbrain.recorder.data.Status
import com.ambientbrain.recorder.data.TranscriptSentenceEntity
import com.ambientbrain.recorder.service.CaptureEngineController
import com.ambientbrain.recorder.service.MeetingAudioCaptureMode
import com.ambientbrain.recorder.service.MeetingMetadataHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Background worker executing:
 *  - Pass 1 (`INFO_EXTRACTION_EN`): Exhaustive info gathering + context-aware reorganization + speaker attribution
 *  - Pass 2 (`VERBATIM_ORIGINAL_TRANSCRIPT`): Dedicated 100% verbatim bilingual transcript (65k token output budget)
 *  - Multi-sample voice profile attachment + Auto Voice Profiles Recognition & Speaker Roster injection
 *  - Continuous-event rolling context chaining + manual multi-audio combined analysis
 *  - Clean ADTS frame-boundary chunking (>11 MB) + SegmentMap timestamp resolution
 *  - Direct Google AI Studio API Key Pool routing (random load-balancing + automatic failover across saved keys)
 *  - Automatic network-restore retry scheduling on offline/connectivity failures
 */
class SessionAnalyzeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val db = AppDatabase.get(applicationContext).dao()
    private val prefs = PreferencesRepo(applicationContext)
    private val client = GeminiInteractionsClient()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private data class IndexedAudioPart(
        val sessionId: String,
        val label: String,
        val file: File,
        val globalOffsetMs: Long,
        val durationMs: Long,
        val sourceStartMs: Long,
        val segments: List<SegmentMapEntity>,
        val isTemporaryChunk: Boolean
    )

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val modeStr = inputData.getString(KEY_ANALYSIS_MODE) ?: AnalysisMode.INFO_EXTRACTION_EN.name
        val analysisMode = runCatching { AnalysisMode.valueOf(modeStr) }
            .getOrDefault(AnalysisMode.INFO_EXTRACTION_EN)
        val session = db.getSessionById(sessionId) ?: return Result.failure()

        val combinedIds: List<String> = session.combinedSourceIdsJson?.let { raw ->
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
        }?.takeIf { it.isNotEmpty() } ?: emptyList()

        if (analysisMode == AnalysisMode.AI_REVIEW_EXTRACTED_ONLY) {
            return runExtractedInfoOnlyReview(session, combinedIds)
        }

        // Resolve all source audio files (single session or multi-audio combined)
        val sourceSessions: List<SessionEntity> = if (combinedIds.isNotEmpty()) {
            db.getSessionsByIds(combinedIds).sortedBy { it.startMs }
        } else {
            listOf(session)
        }

        // Enforce Gemini internal-app-audio exclusion for Zoom/Webex meeting recordings:
        // - INTERNAL_ONLY: never upload internal app audio to Gemini.
        // - INTERNAL_AND_MIC: upload ONLY the clean mic-only track (0% internal app audio).
        // - MIC_SPEAKERPHONE: inject prompt-level exclusion rule to ignore meeting playback audio.
        var excludedMeetingInternalAudioCount = 0
        var shouldIgnoreMeetingInternalAudio = false
        var excludedPlatformLabel = "Online Meeting"

        val validAudioSources = sourceSessions.mapNotNull { src ->
            val marker = MeetingMetadataHelper.parseFirst(src.callEventsJson)
            if (marker != null && marker.ignoreInternalAppAudioForGemini) {
                shouldIgnoreMeetingInternalAudio = true
                excludedPlatformLabel = marker.platform
                when (marker.captureMode) {
                    MeetingAudioCaptureMode.INTERNAL_ONLY.name,
                    MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED.name -> {
                        excludedMeetingInternalAudioCount++
                        null
                    }
                    MeetingAudioCaptureMode.INTERNAL_AND_MIC.name -> {
                        val micOnly = marker.geminiAudioPath
                            ?.let { File(it) }
                            ?.takeIf { it.exists() && it.length() >= 2048L }
                        if (micOnly != null) {
                            src to micOnly
                        } else {
                            excludedMeetingInternalAudioCount++
                            null
                        }
                    }
                    else -> {
                        val f = src.rawPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                        if (f != null) src to f else null
                    }
                }
            } else {
                val f = src.rawPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                if (f != null) src to f else null
            }
        }

        if (validAudioSources.isEmpty()) {
            if (excludedMeetingInternalAudioCount > 0) {
                db.upsertSession(
                    session.copy(
                        status = Status.DONE,
                        summary = session.summary?.takeIf { it.isNotBlank() }
                            ?: "$excludedPlatformLabel meeting saved locally. Internal app audio was excluded from Gemini.",
                        retryCount = 0,
                        nextRetryEpochMs = 0L,
                        lastError = null
                    )
                )
                return Result.success()
            }
            db.upsertSession(
                session.copy(
                    status = Status.FAILED_PERMANENT,
                    lastError = "Raw session audio file missing. Choose 'Previously Extracted Info Only' to review saved notes."
                )
            )
            return Result.failure()
        }

        db.upsertSession(session.copy(status = Status.ANALYZING, lastError = null))

        // Compute chronological offsets per source file for unified Task 1–4 Pre-Gemini acoustic scan
        var preScanOffsetMs = 0L
        val audioSourcesWithOffsets = validAudioSources.map { (_, srcFile) ->
            val pair = srcFile to preScanOffsetMs
            preScanOffsetMs += ClipSlicer.estimateAdtsDurationMs(srcFile).coerceAtLeast(1_000L)
            pair
        }
        val preGeminiVoiceAnalysis = VoiceProfileAutoManager.analyzeSessionAudioPreGemini(
            context = applicationContext,
            prefs = prefs,
            audioSourcesWithOffsets = audioSourcesWithOffsets,
            maxExtraSpeakers = 6,
            maxSamplesPerTopSpeaker = 2,
            maxTotalBytes = 3_200_000L
        )
        val attachableClips = preGeminiVoiceAnalysis.attachableClips
        val referenceVoices = preGeminiVoiceAnalysis.referenceVoices
        val attachedVoiceLabels = attachableClips
            .distinctBy { it.entry.id }
            .map { it.entry.effectiveNickname() to it.entry.effectiveRelatableTitle() }
        val referenceVoiceBytes = referenceVoices.sumOf { it.file.length() }
        val maxAudioBatchBytes = (PRIMARY_INLINE_TOTAL_LIMIT - referenceVoiceBytes).coerceAtLeast(6_000_000L)

        // Prepare chunked/indexed audio parts while preserving clean ADTS frame boundaries
        val tempChunkFiles = mutableListOf<File>()
        val indexedParts = mutableListOf<IndexedAudioPart>()
        var cumulativeOffsetMs = 0L

        return try {
            validAudioSources.forEachIndexed { clipIdx, (srcSession, srcFile) ->
                val srcSegments = db.getSegmentMapForSession(srcSession.id)
                val chunks = ClipSlicer.splitAdtsFileIntoChunks(
                    context = applicationContext,
                    adtsFile = srcFile,
                    maxChunkBytes = maxAudioBatchBytes,
                    workId = "${sessionId}_${clipIdx}"
                )
                val timeLabel = Instant.ofEpochMilli(srcSession.startMs)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm"))

                chunks.forEachIndexed { chIdx, chunk ->
                    if (chunk.isTemporary) tempChunkFiles.add(chunk.file)
                    val partLabel = buildString {
                        if (validAudioSources.size > 1) append("Recording #${clipIdx + 1} ($timeLabel)")
                        else append("Session Recording ($timeLabel)")
                        if (chunks.size > 1) append(" - Part ${chIdx + 1}/${chunks.size}")
                    }
                    indexedParts.add(
                        IndexedAudioPart(
                            sessionId = srcSession.id,
                            label = partLabel,
                            file = chunk.file,
                            globalOffsetMs = cumulativeOffsetMs,
                            durationMs = chunk.durationMs.coerceAtLeast(1_000L),
                            sourceStartMs = srcSession.startMs,
                            segments = srcSegments,
                            isTemporaryChunk = chunk.isTemporary
                        )
                    )
                    cumulativeOffsetMs += chunk.durationMs.coerceAtLeast(1_000L)
                }
            }

            // Ensure eventGroupId is linked for consecutive non-combined sessions within 15 minutes
            var resolvedEventGroupId = session.eventGroupId ?: session.roundGroupId
            if (combinedIds.isEmpty()) {
                if (resolvedEventGroupId == null) {
                    val fifteenMinAgo = session.startMs - TimeUnit.MINUTES.toMillis(15)
                    val recentEvent = db.findRecentSessionForEventGroup(fifteenMinAgo, sessionId)
                    resolvedEventGroupId = recentEvent?.eventGroupId ?: recentEvent?.roundGroupId ?: recentEvent?.id ?: sessionId
                    if (recentEvent != null && recentEvent.eventGroupId == null) {
                        db.updateSessionEventGroup(recentEvent.id, resolvedEventGroupId)
                    }
                }
            }

            // Build Chained Prior Event Context & track previously extracted tasks to avoid duplicates
            val priorItemsToAvoid = mutableListOf<Pair<String, String>>()
            val priorEventContextText = if (combinedIds.isEmpty() && !resolvedEventGroupId.isNullOrBlank()) {
                val priorSessions = db.findPriorContextSessionsInGroup(
                    groupId = resolvedEventGroupId,
                    beforeStartMs = session.startMs,
                    excludeSessionId = sessionId
                ).sortedBy { it.session.startMs }
                if (priorSessions.isNotEmpty()) {
                    buildString {
                        priorSessions.forEachIndexed { idx, prior ->
                            val timeStr = Instant.ofEpochMilli(prior.session.startMs)
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm"))
                            appendLine("Prior Segment ${idx + 1} ($timeStr • ${prior.session.title ?: "Session"}):")
                            if (prior.speakers.isNotEmpty()) {
                                val spkList = prior.speakers.joinToString(", ") { s ->
                                    if (!s.inferredRole.isNullOrBlank()) "${s.speakerLabel} (${s.inferredRole})" else s.speakerLabel
                                }
                                appendLine("  Speakers identified: $spkList")
                            }
                            if (prior.patients.isNotEmpty()) {
                                appendLine("  Patients discussed: ${prior.patients.joinToString(", ") { it.patientIdentifier }}")
                            }
                            if (prior.items.isNotEmpty()) {
                                prior.items.forEach { pi ->
                                    priorItemsToAvoid.add(pi.patientIdentifier.orEmpty() to pi.title)
                                }
                                val itemBriefs = prior.items.take(8).joinToString("; ") { pi ->
                                    val pat = pi.patientIdentifier?.let { "[$it] " }.orEmpty()
                                    "$pat${pi.title}"
                                }
                                appendLine("  Already Captured Action Items: $itemBriefs")
                            }
                            if (!prior.session.summary.isNullOrBlank()) {
                                appendLine("  Key Prior Context: ${prior.session.summary.take(700)}")
                            }
                        }
                    }.trim()
                } else ""
            } else if (analysisMode == AnalysisMode.AI_REVIEW_FROM_AUDIO) {
                val existingIds = if (combinedIds.isNotEmpty()) combinedIds else listOf(sessionId)
                val existingSources = db.getSessionsWithDetailsByIds(existingIds)
                if (existingSources.any { ContinuousMergePlanner.hasExtractedInfo(it) }) {
                    Prompts.buildExtractedInfoInputDocument(existingSources).take(5500)
                } else ""
            } else ""

            val glossaryText = Prompts.loadGlossaryPromptText(applicationContext, prefs)
            val systemInstruction = Prompts.buildStaticSystemInstruction(
                userDisplayName = prefs.userDisplayName,
                transcriptLangMode = prefs.transcriptLangMode,
                hasVoiceProfile = prefs.hasMyVoiceProfile(),
                speakerRoster = prefs.getSpeakerRoster(),
                attachedVoiceLabels = attachedVoiceLabels,
                glossaryText = glossaryText,
                attachedVoiceProfiles = attachableClips.map { it.entry }.distinctBy { it.id },
                attachedVoiceClips = attachableClips
            )

            // Group indexed parts into batches that fit safely within Priority 1 inline payload budget
            val batches = groupPartsIntoBatches(indexedParts, maxAudioBatchBytes)
            val batchResponses = mutableListOf<Pair<List<IndexedAudioPart>, GeminiCallResponse>>()
            var rollingChunkContext = priorEventContextText
            var totalInTokens = 0
            var totalOutTokens = 0
            var totalCachedTokens = 0
            var totalLatencyMs = 0L
            var usedRouteLabel = "${prefs.geminiModel}:key-pool"

            val isoTime = Instant.ofEpochMilli(sourceSessions.first().startMs)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

            val multiDescriptions = if (validAudioSources.size > 1) {
                validAudioSources.mapIndexed { idx, (s, _) ->
                    val t = Instant.ofEpochMilli(s.startMs).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    "Clip #${idx + 1}: started $t, title='${s.title ?: "Recording"}'"
                }
            } else emptyList()

            for ((batchIdx, batchParts) in batches.withIndex()) {
                val batchStartMs = batchParts.first().globalOffsetMs
                val batchEndMs = batchParts.last().globalOffsetMs + batchParts.last().durationMs
                val chunkHeader = if (batches.size > 1) {
                    val startFormatted = formatMmSs(batchStartMs)
                    val endFormatted = formatMmSs(batchEndMs)
                    "Batch ${batchIdx + 1} of ${batches.size} ($startFormatted - $endFormatted)"
                } else ""

                val batchDiarizationHint = preGeminiVoiceAnalysis.formatDiarizationHintForWindow(
                    windowStartMs = batchStartMs,
                    windowEndMs = batchEndMs
                )

                val sessionContextText = Prompts.buildDynamicSessionContextText(
                    sessionIsoTime = isoTime,
                    isManualRound = sourceSessions.any { it.mode == SessionMode.ROUND_MANUAL },
                    callEventsJson = sourceSessions.mapNotNull { it.callEventsJson }.firstOrNull(),
                    analysisMode = analysisMode,
                    priorEventContextText = rollingChunkContext,
                    multiAudioClipDescriptions = multiDescriptions,
                    chunkHeaderText = chunkHeader,
                    onDeviceDiarizationHint = batchDiarizationHint,
                    ignoreMeetingInternalAudio = shouldIgnoreMeetingInternalAudio
                )

                val audioPartInputs = batchParts.map { part ->
                    AudioPartInput(label = part.label, file = part.file, offsetMs = part.globalOffsetMs)
                }

                val callResponse = executeKeyPoolAudioCall(
                    sessionId = sessionId,
                    systemInstruction = systemInstruction,
                    sessionContextText = sessionContextText,
                    audioParts = audioPartInputs,
                    referenceVoices = referenceVoices
                ).let { (resp, route) ->
                    usedRouteLabel = route
                    resp
                }

                batchResponses.add(batchParts to callResponse)
                totalInTokens += callResponse.inputTokens
                totalOutTokens += callResponse.outputTokens
                totalCachedTokens += callResponse.cachedTokens
                totalLatencyMs += callResponse.latencyMs

                // Roll speaker & summary continuity forward into the next batch if there are multiple batches
                if (batchIdx < batches.size - 1) {
                    val res = callResponse.analysis
                    val batchSpeakers = res.speakers.joinToString(", ") { it.speakerLabel }
                    rollingChunkContext = buildString {
                        if (priorEventContextText.isNotBlank()) appendLine(priorEventContextText.take(400))
                        appendLine("Earlier in this same continuous session (Batch ${batchIdx + 1}/${batches.size}):")
                        if (batchSpeakers.isNotBlank()) appendLine("- Identified Speakers: $batchSpeakers")
                        if (res.summary.isNotBlank()) appendLine("- Earlier Context: ${res.summary.take(700)}")
                    }.trim()
                }
            }

            db.insertAnalysisRun(
                AnalysisRunEntity(
                    sessionId = sessionId,
                    model = usedRouteLabel,
                    promptVersion = "${Prompts.PROMPT_VERSION}:${analysisMode.name}",
                    inTokens = totalInTokens,
                    outTokens = totalOutTokens,
                    cachedTokens = totalCachedTokens,
                    latencyMs = totalLatencyMs,
                    ok = true
                )
            )

            // Merge batch responses into a unified result with corrected offsets,
            // plan-to-task reconciliation, and fuzzy deduplication of tasks & key points
            val isStructuredExtractionMode = analysisMode == AnalysisMode.INFO_EXTRACTION_EN ||
                analysisMode == AnalysisMode.AI_REVIEW_FROM_AUDIO
            val rawMerged = mergeBatchResponses(batchResponses, analysisMode)
            val mergedAnalysis = if (isStructuredExtractionMode) {
                val mergedPatients = ExtractionRefiner.mergePatients(rawMerged.patients)
                val reconciledItems = ExtractionRefiner.synthesizeMissingPlanTasks(mergedPatients, rawMerged.items)
                val refinedItems = ExtractionRefiner.deduplicateAndEnrichItems(
                    rawItems = reconciledItems,
                    userDisplayName = prefs.userDisplayName,
                    priorItemsToAvoid = priorItemsToAvoid
                )
                val refinedKeyPoints = ExtractionRefiner.refineKeyPoints(
                    rawKeyPoints = rawMerged.keyPoints,
                    patients = mergedPatients,
                    items = refinedItems
                )
                rawMerged.copy(
                    patients = mergedPatients,
                    items = refinedItems,
                    keyPoints = refinedKeyPoints
                )
            } else {
                rawMerged
            }

            if (!mergedAnalysis.hasMeaningfulContent &&
                mergedAnalysis.transcript.isEmpty() &&
                mergedAnalysis.items.isEmpty() &&
                mergedAnalysis.summary.isBlank()
            ) {
                db.upsertSession(
                    session.copy(
                        rawPath = session.rawPath?.takeIf { File(it).exists() } ?: validAudioSources.first().second.absolutePath,
                        rawBytes = session.rawPath?.let { File(it) }?.takeIf { it.exists() }?.length()
                            ?: validAudioSources.sumOf { it.second.length() },
                        isRawPurged = false,
                        eventGroupId = resolvedEventGroupId,
                        context = if (shouldIgnoreMeetingInternalAudio) {
                            session.context ?: "ONLINE_MEETING"
                        } else {
                            "NOISE_OR_TV"
                        },
                        title = if (shouldIgnoreMeetingInternalAudio) {
                            session.title?.takeIf { it.isNotBlank() } ?: "$excludedPlatformLabel Meeting"
                        } else {
                            "Ambient / Unclassified Audio"
                        },
                        summary = if (shouldIgnoreMeetingInternalAudio) {
                            "$excludedPlatformLabel meeting saved locally (internal app audio ignored by Gemini; no separate local speech detected)."
                        } else {
                            mergedAnalysis.summary.ifBlank { "Background audio or unclassified conversation." }
                        },
                        status = Status.DONE,
                        retryCount = 0,
                        nextRetryEpochMs = 0L,
                        lastError = null
                    )
                )
                return Result.success()
            }

            // Auto-detect clinical round & stitch roundGroupId
            val isRoundDetected = session.mode == SessionMode.ROUND_MANUAL ||
                session.mode == SessionMode.ROUND_AUTO ||
                (prefs.autoDetectRounds && (
                    mergedAnalysis.isClinicalRound ||
                        mergedAnalysis.context in setOf("NEURO_ROUND", "MEDICAL_ROUND", "CASE_CONFERENCE", "HANDOVER") ||
                        mergedAnalysis.patients.isNotEmpty()
                    ))

            var finalMode = session.mode
            var finalRoundGroupId = session.roundGroupId

            if (isRoundDetected) {
                if (finalMode == SessionMode.EVERYDAY) {
                    finalMode = SessionMode.ROUND_AUTO
                }
                if (finalRoundGroupId == null) {
                    val thirtyMinAgo = session.startMs - TimeUnit.MINUTES.toMillis(30)
                    val recentRound = db.findRecentRoundSessionSince(thirtyMinAgo, sessionId)
                    finalRoundGroupId = recentRound?.roundGroupId ?: recentRound?.id ?: resolvedEventGroupId ?: sessionId
                    if (recentRound != null && recentRound.roundGroupId == null) {
                        db.updateSessionRoundGroup(recentRound.id, recentRound.mode, finalRoundGroupId)
                        db.updatePatientsRoundGroup(recentRound.id, finalRoundGroupId)
                    }
                }
                if (combinedIds.isEmpty() && System.currentTimeMillis() - session.endMs < TimeUnit.MINUTES.toMillis(3)) {
                    CaptureEngineController.promoteActiveCaptureToAutoRound(finalRoundGroupId)
                }
            }

            // 1. Reconcile Voice Profiles, assign recognizing nicknames & relatable titles,
            //    and slice clean solo ADTS sample clips from raw audio.
            val sliceSources = indexedParts.map { part ->
                VoiceProfileAutoManager.SliceSourceSegment(
                    file = part.file,
                    globalOffsetMs = part.globalOffsetMs,
                    durationMs = part.durationMs,
                    segments = part.segments,
                    sourceStartMs = part.sourceStartMs
                )
            }
            val reconciled = VoiceProfileAutoManager.reconcileAndEnrollSpeakers(
                context = applicationContext,
                prefs = prefs,
                sessionId = sessionId,
                sessionContext = mergedAnalysis.context,
                sliceSources = sliceSources,
                rawSpeakers = mergedAnalysis.speakers,
                rawTranscript = mergedAnalysis.transcript,
                rawItems = mergedAnalysis.items,
                allowSlicing = analysisMode != AnalysisMode.AI_REVIEW_EXTRACTED_ONLY,
                sessionRecordedAtMs = sourceSessions.first().startMs
            )
            if (reconciled.speakers.isNotEmpty()) {
                db.deleteSpeakersForSession(sessionId)
                db.insertSpeakers(reconciled.speakers)
            }

            // 2. Save Full Verbatim Transcript (resolving compacted offsets via SegmentMap)
            val sentenceEntities = mergedAnalysis.transcript.mapIndexed { idx, sent ->
                val rawGlobalMs = ClipSlicer.parseApproxMmSsToMs(sent.t)
                val mappedPart = findPartForGlobalOffset(indexedParts, rawGlobalMs)
                val localOffsetMs = (rawGlobalMs - mappedPart.globalOffsetMs).coerceAtLeast(0L)
                val resolved = ClipSlicer.resolveCompactedTimestamp(
                    rawInputMs = localOffsetMs,
                    segments = mappedPart.segments,
                    sessionStartMs = mappedPart.sourceStartMs,
                    maxFileDurationMs = mappedPart.durationMs
                )
                val wallRelativeMs = (resolved.wallClockMs - sourceSessions.first().startMs)
                    .coerceAtLeast(rawGlobalMs)
                val resolvedSpeakerLabel = reconciled.speakerLabelById[sent.speakerId] ?: sent.speakerLabel
                TranscriptSentenceEntity(
                    sentenceUuid = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    ordinal = idx,
                    approxMs = if (indexedParts.size == 1 && mappedPart.segments.isEmpty()) resolved.rawFileMs else wallRelativeMs,
                    speakerId = sent.speakerId,
                    speakerLabel = resolvedSpeakerLabel,
                    textOriginal = sent.textOriginal,
                    textEn = sent.textEn
                )
            }
            if (analysisMode == AnalysisMode.VERBATIM_ORIGINAL_TRANSCRIPT || sentenceEntities.isNotEmpty()) {
                db.deleteSentencesForSession(sessionId)
                if (sentenceEntities.isNotEmpty()) {
                    db.insertSentences(sentenceEntities)
                }
            }

            // 3. Save Patient Round Blocks (ONLY in structured extraction/review modes, preserving on Verbatim-Only!)
            if (isStructuredExtractionMode) {
                db.deletePatientsForSession(sessionId)
                if (mergedAnalysis.patients.isNotEmpty()) {
                    val patientEntities = mergedAnalysis.patients.map { p ->
                        val plans = p.plan.map {
                            PatientPlanOrder(
                                category = it.category,
                                text = it.text,
                                needsVerification = it.needsVerification
                            )
                        }
                        PatientBlockEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            roundGroupId = finalRoundGroupId,
                            patientIdentifier = p.patientIdentifier,
                            approxStartMs = ClipSlicer.parseApproxMmSsToMs(p.approxStart),
                            approxEndMs = ClipSlicer.parseApproxMmSsToMs(p.approxEnd),
                            oneLiner = p.oneLiner,
                            neuroExam = p.neuroExam,
                            assessment = p.assessment,
                            problemsJson = json.encodeToString(p.problems),
                            resultsJson = json.encodeToString(p.resultsMentioned),
                            pendingJson = json.encodeToString(p.pending),
                            planJson = json.encodeToString(plans),
                            teachingJson = json.encodeToString(p.teachingPoints)
                        )
                    }
                    db.insertPatients(patientEntities)
                }
            }

            // 4. Slice Permanent Voice Proof Clips (+/- 5s) & Save Extracted Items (ONLY in structured extraction/review modes!)
            if (isStructuredExtractionMode) {
                db.upsertSession(session.copy(status = Status.CLIPPING))
                db.deleteItemsForSession(sessionId)
                val itemEntities = mergedAnalysis.items.map { dto ->
                    val itemId = UUID.randomUUID().toString()
                    val globalStartMs = ClipSlicer.parseApproxMmSsToMs(dto.approxStart)
                    val globalEndMs = ClipSlicer.parseApproxMmSsToMs(dto.approxEnd).coerceAtLeast(globalStartMs + 3_000L)

                    val mappedPart = findPartForGlobalOffset(indexedParts, globalStartMs)
                    val partRawFile = validAudioSources.firstOrNull { it.first.id == mappedPart.sessionId }?.second
                        ?: validAudioSources.first().second
                    val maxPartMs = mappedPart.durationMs.coerceAtLeast(3_000L)
                    val localStartMs = (globalStartMs - mappedPart.globalOffsetMs)
                        .coerceIn(0L, (maxPartMs - 3_000L).coerceAtLeast(0L))
                    val localEndMs = (globalEndMs - mappedPart.globalOffsetMs)
                        .coerceIn(localStartMs + 3_000L, maxPartMs)

                    val slicedClip = ClipSlicer.slicePermanentClip(
                        context = applicationContext,
                        rawAdtsFile = partRawFile,
                        itemId = itemId,
                        approxStartMs = localStartMs,
                        approxEndMs = localEndMs
                    )
                    val recordedWallMs = ClipSlicer.resolveCompactedTimestamp(
                        rawInputMs = localStartMs,
                        segments = mappedPart.segments,
                        sessionStartMs = mappedPart.sourceStartMs,
                        maxFileDurationMs = maxPartMs
                    ).wallClockMs
                    slicedClip?.let { runCatching { it.setLastModified(recordedWallMs) } }
                    val parsedType = runCatching { ItemType.valueOf(dto.itemType) }.getOrDefault(ItemType.TASK)
                    val resolvedSpeakerLabel = reconciled.speakerLabelById[dto.speakerId] ?: dto.speakerLabel
                    ItemEntity(
                        id = itemId,
                        sessionId = sessionId,
                        patientIdentifier = dto.patientIdentifier,
                        type = parsedType,
                        title = dto.title,
                        details = dto.details,
                        speakerId = dto.speakerId,
                        speakerLabel = resolvedSpeakerLabel,
                        assignee = dto.assignee,
                        isForUser = dto.isForUser,
                        due = dto.due,
                        priority = dto.priority,
                        quoteOriginal = dto.quoteOriginal,
                        quoteEn = dto.quoteEn,
                        needsVerification = dto.needsVerification,
                        clipStartMs = (localStartMs - 5_000L).coerceAtLeast(0L),
                        clipEndMs = localEndMs + 5_000L,
                        clipPath = slicedClip?.absolutePath,
                        createdMs = recordedWallMs
                    )
                }
                val finalItems = if (itemEntities.isEmpty() && (mergedAnalysis.summary.isNotBlank() || sentenceEntities.isNotEmpty())) {
                    val fallbackTitle = mergedAnalysis.title.ifBlank { "Conversation Info Note" }
                    val fallbackDetails = mergedAnalysis.summary.ifBlank {
                        sentenceEntities.take(5).joinToString(" ") { it.textOriginal }
                    }
                    val fallbackSpeaker = reconciled.speakers.firstOrNull()
                    listOf(
                        ItemEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            patientIdentifier = null,
                            type = ItemType.NOTE,
                            title = fallbackTitle,
                            details = fallbackDetails,
                            speakerId = fallbackSpeaker?.speakerId ?: mergedAnalysis.speakers.firstOrNull()?.speakerId ?: "SPK_1",
                            speakerLabel = fallbackSpeaker?.speakerLabel ?: mergedAnalysis.speakers.firstOrNull()?.speakerLabel ?: "Conversation Voice",
                            assignee = null,
                            isForUser = true,
                            due = null,
                            priority = "NORMAL",
                            quoteOriginal = fallbackTitle,
                            quoteEn = fallbackDetails.take(180),
                            needsVerification = false,
                            clipStartMs = 0L,
                            clipEndMs = (session.endMs - session.startMs).coerceAtLeast(3_000L),
                            clipPath = validAudioSources.first().second.absolutePath,
                            createdMs = sourceSessions.first().startMs
                        )
                    )
                } else {
                    itemEntities
                }
                db.insertItems(finalItems)
            }

            // 5. Mark Session DONE — strictly preserve Pass 1 summary/keyPoints when Pass 2 is Verbatim-Only
            val hasExistingRichInfo = !session.summary.isNullOrBlank() &&
                session.context != "NOISE_OR_TV" &&
                session.summary != "100% verbatim transcript completed"

            val effectiveTitle = when {
                analysisMode == AnalysisMode.VERBATIM_ORIGINAL_TRANSCRIPT &&
                    !session.title.isNullOrBlank() &&
                    session.title != "Ambient / Unclassified Audio" -> session.title
                mergedAnalysis.title.isNotBlank() -> mergedAnalysis.title
                !session.title.isNullOrBlank() -> session.title
                else -> "Conversation Session"
            }

            val effectiveSummary = when {
                analysisMode == AnalysisMode.VERBATIM_ORIGINAL_TRANSCRIPT && hasExistingRichInfo -> session.summary!!
                mergedAnalysis.summary.isNotBlank() -> mergedAnalysis.summary
                else -> session.summary ?: ""
            }

            val effectiveKeyPointsJson = when {
                analysisMode == AnalysisMode.VERBATIM_ORIGINAL_TRANSCRIPT &&
                    !session.keyPointsJson.isNullOrBlank() &&
                    session.keyPointsJson != "[]" -> session.keyPointsJson!!
                mergedAnalysis.keyPoints.isNotEmpty() -> json.encodeToString(mergedAnalysis.keyPoints)
                !session.keyPointsJson.isNullOrBlank() -> session.keyPointsJson!!
                else -> json.encodeToString(emptyList<String>())
            }

            val effectiveContext = when {
                analysisMode == AnalysisMode.VERBATIM_ORIGINAL_TRANSCRIPT &&
                    !session.context.isNullOrBlank() &&
                    session.context != "NOISE_OR_TV" -> session.context!!
                mergedAnalysis.context.isNotBlank() && mergedAnalysis.context != "NOISE_OR_TV" -> mergedAnalysis.context
                else -> session.context?.takeIf { it != "NOISE_OR_TV" } ?: mergedAnalysis.context
            }

            val effectiveReviewReport = if (analysisMode == AnalysisMode.AI_REVIEW_FROM_AUDIO) {
                mergedAnalysis.aiReviewReport?.takeIf { it.isNotBlank() } ?: effectiveSummary
            } else {
                session.aiReviewReport
            }
            val effectiveReviewedAt = if (analysisMode == AnalysisMode.AI_REVIEW_FROM_AUDIO) {
                System.currentTimeMillis()
            } else {
                session.reviewedAtMs
            }
            val effectiveReviewSourceMode = if (analysisMode == AnalysisMode.AI_REVIEW_FROM_AUDIO) {
                ReviewSourceMode.REANALYZE_AUDIO.name
            } else {
                session.reviewSourceMode
            }

            db.upsertSession(
                session.copy(
                    mode = finalMode,
                    roundGroupId = finalRoundGroupId,
                    eventGroupId = resolvedEventGroupId,
                    context = effectiveContext,
                    title = effectiveTitle,
                    summary = effectiveSummary,
                    keyPointsJson = effectiveKeyPointsJson,
                    status = Status.DONE,
                    retryCount = 0,
                    nextRetryEpochMs = 0L,
                    lastError = null,
                    aiReviewReport = effectiveReviewReport,
                    reviewedAtMs = effectiveReviewedAt,
                    reviewSourceMode = effectiveReviewSourceMode
                )
            )
            Log.i(
                TAG,
                "Session $sessionId analyzed (mode=${analysisMode.name}, route=$usedRouteLabel, batches=${batches.size}, sentences=${sentenceEntities.size})"
            )

            // Auto-ingest novel medical/neurology glossary terms discovered during structured analysis
            if (isStructuredExtractionMode && prefs.autoAddGlossaryTerms && mergedAnalysis.discoveredGlossaryTerms.isNotEmpty()) {
                runCatching {
                    val speakerNamesToIgnore = reconciled.speakers
                        .flatMap { listOfNotNull(it.nickname, it.speakerLabel) }
                        .toSet()
                    val ingest = MedicalGlossaryManager.autoIngestDiscoveredTerms(
                        context = applicationContext,
                        prefs = prefs,
                        sessionId = sessionId,
                        discoveredTerms = mergedAnalysis.discoveredGlossaryTerms,
                        speakerNamesToIgnore = speakerNamesToIgnore,
                        recordedAtMs = sourceSessions.first().startMs
                    )
                    if (ingest.addedCount > 0 || ingest.updatedCount > 0) {
                        Log.i(
                            TAG,
                            "Auto-glossary updated for $sessionId: +${ingest.addedCount} added, ${ingest.updatedCount} updated, ${ingest.skippedCount} skipped"
                        )
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Auto-glossary ingest failed for $sessionId: ${err.message}")
                }
            }

            // Auto-merge related continuous files once an individual session finishes its initial analysis
            if (analysisMode == AnalysisMode.INFO_EXTRACTION_EN &&
                combinedIds.isEmpty() &&
                prefs.autoMergeContinuousFiles
            ) {
                runCatching {
                    autoMergeContinuousClusterIfNeeded(sessionId)
                }.onFailure { err ->
                    Log.w(TAG, "Auto-merge continuous cluster check failed for $sessionId: ${err.message}")
                }
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed for session $sessionId", e)
            val currentAttempt = session.retryCount + 1

            db.insertAnalysisRun(
                AnalysisRunEntity(
                    sessionId = sessionId,
                    model = prefs.geminiModel,
                    promptVersion = "${Prompts.PROMPT_VERSION}:${analysisMode.name}",
                    inTokens = 0,
                    outTokens = 0,
                    latencyMs = 0L,
                    ok = false,
                    errorMsg = e.message
                )
            )

            val cls = GeminiInteractionsClient.classifyApiFailure(e)
            val missingApiKey = prefs.getAllApiKeys().isEmpty() ||
                e.message?.contains("No Gemini API key configured", ignoreCase = true) == true
            val isTransientNetworkOrServer = !missingApiKey && (
                cls.isNetworkTransient ||
                    e is UnknownHostException ||
                    e is SocketTimeoutException ||
                    e is ConnectException ||
                    e is IOException ||
                    e.message?.contains("429") == true ||
                    e.message?.contains("500") == true ||
                    e.message?.contains("502") == true ||
                    e.message?.contains("503") == true ||
                    e.message?.contains("timed out", ignoreCase = true) == true ||
                    e.message?.contains("network", ignoreCase = true) == true ||
                    e.message?.contains("Unable to resolve host", ignoreCase = true) == true
                )

            val friendlyError = when {
                missingApiKey -> "No Gemini API key configured. Open Settings -> Gemini API Key Pool and add a Google AI Studio key."
                e is UnknownHostException -> "Offline (waiting for internet connection)."
                e is SocketTimeoutException -> "Connection timed out."
                e is ConnectException -> "Could not connect to Gemini server."
                cls.shouldFailoverKey -> "${cls.category}: ${cls.remediationHint}"
                e.message?.contains("401") == true || e.message?.contains("403") == true -> "Gemini API key unauthorized or invalid. Check your key pool in Settings."
                e.message?.contains("429") == true -> "Gemini API rate limit reached across saved keys."
                e.message?.contains("500") == true || e.message?.contains("503") == true -> "Gemini service temporarily unavailable."
                else -> e.message?.take(180) ?: "Connection error"
            }

            if (missingApiKey) {
                db.upsertSession(
                    session.copy(
                        status = Status.FAILED_PERMANENT,
                        retryCount = currentAttempt,
                        nextRetryEpochMs = 0L,
                        lastError = "$friendlyError • Audio stored safely • Tap 'Try Again' after adding a key"
                    )
                )
            } else if (isTransientNetworkOrServer) {
                // Exponential backoff in minutes (2 -> 5 -> 15 -> 30 capped), AND constrained by NetworkType.CONNECTED
                // so WorkManager auto-resumes automatically when internet returns!
                val delayMinutes = when (currentAttempt) {
                    1 -> 2L
                    2 -> 5L
                    3 -> 15L
                    else -> 30L
                }
                val nextRetryEpoch = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes)
                val msg = "$friendlyError • Attempt $currentAttempt • Auto-resumes when connected (next check in ${delayMinutes}m)"
                db.upsertSession(
                    session.copy(
                        status = Status.FAILED_RETRYABLE,
                        retryCount = currentAttempt,
                        nextRetryEpochMs = nextRetryEpoch,
                        lastError = msg
                    )
                )
                scheduleRetry(applicationContext, sessionId, delayMinutes, analysisMode)
            } else if (currentAttempt < MAX_AUTO_RETRIES) {
                val nextRetryEpoch = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(RETRY_INTERVAL_MINUTES)
                val msg = "$friendlyError • Attempt $currentAttempt/$MAX_AUTO_RETRIES • Next auto-retry in ${RETRY_INTERVAL_MINUTES}m • Audio stored safely"
                db.upsertSession(
                    session.copy(
                        status = Status.FAILED_RETRYABLE,
                        retryCount = currentAttempt,
                        nextRetryEpochMs = nextRetryEpoch,
                        lastError = msg
                    )
                )
                scheduleRetry(applicationContext, sessionId, RETRY_INTERVAL_MINUTES, analysisMode)
            } else {
                val msg = "$friendlyError • Max $MAX_AUTO_RETRIES attempts reached • Audio stored safely • Tap 'Try Again' to retry"
                db.upsertSession(
                    session.copy(
                        status = Status.FAILED_PERMANENT,
                        retryCount = currentAttempt,
                        nextRetryEpochMs = 0L,
                        lastError = msg
                    )
                )
            }
            Result.failure()
        } finally {
            tempChunkFiles.forEach { runCatching { it.delete() } }
            runCatching {
                File(applicationContext.cacheDir, "audio_chunks").listFiles()?.forEach { dir ->
                    if (dir.name.startsWith(sessionId)) dir.deleteRecursively()
                }
            }
        }
    }

    private suspend fun runExtractedInfoOnlyReview(
        session: SessionEntity,
        combinedIds: List<String>
    ): Result {
        val sessionId = session.id
        val targetIds = if (combinedIds.isNotEmpty()) combinedIds else listOf(sessionId)
        val sourceDetails = db.getSessionsWithDetailsByIds(targetIds).sortedBy { it.session.startMs }
        val nonEmptySources = sourceDetails.filter { ContinuousMergePlanner.hasExtractedInfo(it) }

        if (nonEmptySources.isEmpty()) {
            db.upsertSession(
                session.copy(
                    status = Status.FAILED_PERMANENT,
                    lastError = "No previously extracted info found yet. Choose 'Re-analyze Audio Files Again' to transcribe from audio."
                )
            )
            return Result.failure()
        }

        db.upsertSession(session.copy(status = Status.ANALYZING, lastError = null))

        return try {
            val glossaryText = Prompts.loadGlossaryPromptText(applicationContext, prefs)
            val systemInstruction = Prompts.buildStaticSystemInstruction(
                userDisplayName = prefs.userDisplayName,
                transcriptLangMode = prefs.transcriptLangMode,
                hasVoiceProfile = prefs.hasMyVoiceProfile(),
                speakerRoster = prefs.getSpeakerRoster(),
                attachedVoiceLabels = emptyList(),
                glossaryText = glossaryText,
                attachedVoiceProfiles = emptyList()
            )

            val isoTime = Instant.ofEpochMilli(nonEmptySources.first().session.startMs)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

            val multiDescriptions = if (nonEmptySources.size > 1) {
                nonEmptySources.mapIndexed { idx, d ->
                    val t = Instant.ofEpochMilli(d.session.startMs).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    "Clip #${idx + 1}: started $t, title='${d.session.title ?: "Recording"}'"
                }
            } else emptyList()

            val sessionContextText = Prompts.buildDynamicSessionContextText(
                sessionIsoTime = isoTime,
                isManualRound = nonEmptySources.any { it.session.mode == SessionMode.ROUND_MANUAL },
                callEventsJson = nonEmptySources.mapNotNull { it.session.callEventsJson }.firstOrNull(),
                analysisMode = AnalysisMode.AI_REVIEW_EXTRACTED_ONLY,
                multiAudioClipDescriptions = multiDescriptions
            )

            val extractedDoc = Prompts.buildExtractedInfoInputDocument(nonEmptySources)
            val (callResponse, usedRouteLabel) = executeKeyPoolExtractedReviewCall(
                sessionId = sessionId,
                systemInstruction = systemInstruction,
                sessionContextText = sessionContextText,
                extractedDocument = extractedDoc
            )

            db.insertAnalysisRun(
                AnalysisRunEntity(
                    sessionId = sessionId,
                    model = usedRouteLabel,
                    promptVersion = "${Prompts.PROMPT_VERSION}:${AnalysisMode.AI_REVIEW_EXTRACTED_ONLY.name}",
                    inTokens = callResponse.inputTokens,
                    outTokens = callResponse.outputTokens,
                    cachedTokens = callResponse.cachedTokens,
                    latencyMs = callResponse.latencyMs,
                    ok = true
                )
            )

            val rawRes = callResponse.analysis
            val mergedPatients = ExtractionRefiner.mergePatients(rawRes.patients)
            val reconciledItems = ExtractionRefiner.synthesizeMissingPlanTasks(mergedPatients, rawRes.items)
            val refinedItems = ExtractionRefiner.deduplicateAndEnrichItems(
                rawItems = reconciledItems,
                userDisplayName = prefs.userDisplayName
            )
            val refinedKeyPoints = ExtractionRefiner.refineKeyPoints(
                rawKeyPoints = rawRes.keyPoints,
                patients = mergedPatients,
                items = refinedItems
            )

            val isRound = session.mode == SessionMode.ROUND_MANUAL ||
                session.mode == SessionMode.ROUND_AUTO ||
                nonEmptySources.any { it.session.mode != SessionMode.EVERYDAY } ||
                rawRes.isClinicalRound ||
                mergedPatients.isNotEmpty()
            val finalMode = if (isRound && session.mode == SessionMode.EVERYDAY) SessionMode.ROUND_AUTO else session.mode
            val finalRoundGroupId = session.roundGroupId ?: nonEmptySources.firstNotNullOfOrNull { it.session.roundGroupId }
            val resolvedEventGroupId = session.eventGroupId ?: nonEmptySources.firstNotNullOfOrNull { it.session.eventGroupId }
            val reviewSpeakerNamesToIgnore = mutableSetOf<String>()

            // Reconcile or merge Speakers cleanly (no audio slicing during extracted-info-only review)
            val reconciledSpeakerLabels = if (rawRes.speakers.isNotEmpty()) {
                val reconciled = VoiceProfileAutoManager.reconcileAndEnrollSpeakers(
                    context = applicationContext,
                    prefs = prefs,
                    sessionId = sessionId,
                    sessionContext = rawRes.context,
                    sliceSources = emptyList(),
                    rawSpeakers = rawRes.speakers,
                    rawTranscript = emptyList(),
                    rawItems = refinedItems,
                    allowSlicing = false,
                    sessionRecordedAtMs = nonEmptySources.first().session.startMs
                )
                if (reconciled.speakers.isNotEmpty()) {
                    db.deleteSpeakersForSession(sessionId)
                    db.insertSpeakers(reconciled.speakers)
                    reconciled.speakers.forEach { spk ->
                        spk.nickname?.let { reviewSpeakerNamesToIgnore.add(it) }
                        reviewSpeakerNamesToIgnore.add(spk.speakerLabel)
                    }
                }
                reconciled.speakerLabelById
            } else {
                if (combinedIds.isNotEmpty()) {
                    val seenIds = mutableSetOf<String>()
                    val unionSpeakers = nonEmptySources.flatMap { it.speakers }
                        .distinctBy { (it.matchedProfileId?.takeIf { pid -> pid.isNotBlank() } ?: it.speakerLabel).lowercase() }
                        .mapIndexed { idx, spk ->
                            val uniqueId = if (seenIds.add(spk.speakerId)) spk.speakerId else "SPK_U_${idx + 1}"
                            spk.copy(sessionId = sessionId, speakerId = uniqueId)
                        }
                    if (unionSpeakers.isNotEmpty()) {
                        db.deleteSpeakersForSession(sessionId)
                        db.insertSpeakers(unionSpeakers)
                        unionSpeakers.forEach { spk ->
                            spk.nickname?.let { reviewSpeakerNamesToIgnore.add(it) }
                            reviewSpeakerNamesToIgnore.add(spk.speakerLabel)
                        }
                    }
                }
                emptyMap()
            }

            // Preserve existing sentences; if combined virtual session has none yet, copy source transcripts chronologically
            val existingSentences = db.getSessionWithDetailsOnce(sessionId)?.sentences.orEmpty()
            if (existingSentences.isEmpty() && combinedIds.isNotEmpty()) {
                val baseStart = nonEmptySources.first().session.startMs
                var runningOrdinal = 0
                val stitchedSentences = nonEmptySources.flatMap { src ->
                    val deltaMs = (src.session.startMs - baseStart).coerceAtLeast(0L)
                    src.sentences.sortedBy { it.ordinal }.map { sent ->
                        sent.copy(
                            id = 0,
                            sentenceUuid = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            ordinal = runningOrdinal++,
                            approxMs = deltaMs + sent.approxMs
                        )
                    }
                }
                if (stitchedSentences.isNotEmpty()) {
                    db.insertSentences(stitchedSentences)
                }
            }

            // Save consolidated Patient Blocks
            db.deletePatientsForSession(sessionId)
            if (mergedPatients.isNotEmpty()) {
                db.insertPatients(
                    mergedPatients.map { p ->
                        val plans = p.plan.map {
                            PatientPlanOrder(category = it.category, text = it.text, needsVerification = it.needsVerification)
                        }
                        PatientBlockEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            roundGroupId = finalRoundGroupId,
                            patientIdentifier = p.patientIdentifier,
                            approxStartMs = ClipSlicer.parseApproxMmSsToMs(p.approxStart),
                            approxEndMs = ClipSlicer.parseApproxMmSsToMs(p.approxEnd),
                            oneLiner = p.oneLiner,
                            neuroExam = p.neuroExam,
                            assessment = p.assessment,
                            problemsJson = json.encodeToString(p.problems),
                            resultsJson = json.encodeToString(p.resultsMentioned),
                            pendingJson = json.encodeToString(p.pending),
                            planJson = json.encodeToString(plans),
                            teachingJson = json.encodeToString(p.teachingPoints)
                        )
                    }
                )
            }

            // Preserve existing voice proof clips and done status across extracted-info-only updates
            val priorSourceItems = nonEmptySources.flatMap { it.items }
            val sourcesStartMs = nonEmptySources.minOf { it.session.startMs }
            val sourcesEndMs = nonEmptySources.maxOf { it.session.endMs }
            db.deleteItemsForSession(sessionId)
            if (refinedItems.isNotEmpty()) {
                val mergedItemEntities = refinedItems.map { dto ->
                    val matchedPrior = priorSourceItems.firstOrNull { old ->
                        old.title.equals(dto.title, ignoreCase = true) ||
                            (dto.quoteOriginal.length >= 10 && old.quoteOriginal.contains(dto.quoteOriginal.take(20), ignoreCase = true)) ||
                            (old.quoteOriginal.length >= 10 && dto.quoteOriginal.contains(old.quoteOriginal.take(20), ignoreCase = true))
                    }
                    val resolvedSpeakerLabel = reconciledSpeakerLabels[dto.speakerId] ?: dto.speakerLabel
                    val itemClipStartMs = matchedPrior?.clipStartMs ?: ClipSlicer.parseApproxMmSsToMs(dto.approxStart)
                    val itemRecordedMs = matchedPrior?.createdMs
                        ?.takeIf { it in sourcesStartMs..(sourcesEndMs + 60_000L) }
                        ?: (sourcesStartMs + itemClipStartMs).coerceIn(sourcesStartMs, (sourcesEndMs + 60_000L).coerceAtLeast(sourcesStartMs))
                    ItemEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        patientIdentifier = dto.patientIdentifier,
                        type = runCatching { ItemType.valueOf(dto.itemType) }.getOrDefault(ItemType.TASK),
                        title = dto.title,
                        details = dto.details,
                        speakerId = dto.speakerId,
                        speakerLabel = resolvedSpeakerLabel,
                        assignee = dto.assignee,
                        isForUser = dto.isForUser,
                        due = dto.due,
                        priority = dto.priority,
                        quoteOriginal = dto.quoteOriginal,
                        quoteEn = dto.quoteEn,
                        needsVerification = dto.needsVerification,
                        clipStartMs = itemClipStartMs,
                        clipEndMs = matchedPrior?.clipEndMs ?: ClipSlicer.parseApproxMmSsToMs(dto.approxEnd),
                        clipPath = matchedPrior?.clipPath,
                        isDone = matchedPrior?.isDone ?: false,
                        createdMs = itemRecordedMs
                    )
                }
                db.insertItems(mergedItemEntities)
            }

            val reviewReportText = rawRes.aiReviewReport?.takeIf { it.isNotBlank() }
                ?: rawRes.summary.takeIf { it.isNotBlank() }
                ?: "AI Review completed using previously extracted information."

            db.upsertSession(
                session.copy(
                    mode = finalMode,
                    roundGroupId = finalRoundGroupId,
                    eventGroupId = resolvedEventGroupId,
                    context = rawRes.context.takeIf { it.isNotBlank() && it != "NOISE_OR_TV" } ?: session.context,
                    title = rawRes.title.takeIf { it.isNotBlank() } ?: session.title,
                    summary = rawRes.summary.takeIf { it.isNotBlank() } ?: session.summary,
                    keyPointsJson = if (refinedKeyPoints.isNotEmpty()) json.encodeToString(refinedKeyPoints) else session.keyPointsJson,
                    status = Status.DONE,
                    retryCount = 0,
                    nextRetryEpochMs = 0L,
                    lastError = null,
                    aiReviewReport = reviewReportText,
                    reviewedAtMs = System.currentTimeMillis(),
                    reviewSourceMode = ReviewSourceMode.EXTRACTED_ONLY.name
                )
            )

            if (prefs.autoAddGlossaryTerms && rawRes.discoveredGlossaryTerms.isNotEmpty()) {
                runCatching {
                    MedicalGlossaryManager.autoIngestDiscoveredTerms(
                        context = applicationContext,
                        prefs = prefs,
                        sessionId = sessionId,
                        discoveredTerms = rawRes.discoveredGlossaryTerms,
                        speakerNamesToIgnore = reviewSpeakerNamesToIgnore,
                        recordedAtMs = sourcesStartMs
                    )
                }.onFailure { err ->
                    Log.w(TAG, "Auto-glossary ingest failed during extracted review for $sessionId: ${err.message}")
                }
            }

            Log.i(
                TAG,
                "Extracted-info AI Review completed for $sessionId (sources=${nonEmptySources.size}, route=$usedRouteLabel)"
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Extracted-info AI Review failed for $sessionId", e)
            val currentAttempt = session.retryCount + 1
            db.insertAnalysisRun(
                AnalysisRunEntity(
                    sessionId = sessionId,
                    model = prefs.geminiModel,
                    promptVersion = "${Prompts.PROMPT_VERSION}:${AnalysisMode.AI_REVIEW_EXTRACTED_ONLY.name}",
                    inTokens = 0,
                    outTokens = 0,
                    latencyMs = 0L,
                    ok = false,
                    errorMsg = e.message
                )
            )
            val cls = GeminiInteractionsClient.classifyApiFailure(e)
            val missingApiKey = prefs.getAllApiKeys().isEmpty() ||
                e.message?.contains("No Gemini API key configured", ignoreCase = true) == true
            val isTransient = !missingApiKey && (
                cls.isNetworkTransient ||
                    e is UnknownHostException || e is SocketTimeoutException || e is ConnectException || e is IOException ||
                    e.message?.contains("429") == true || e.message?.contains("500") == true ||
                    e.message?.contains("503") == true || e.message?.contains("network", ignoreCase = true) == true
                )
            val friendlyMsg = when {
                missingApiKey -> "No Gemini API key configured. Open Settings -> Gemini API Key Pool to add a key."
                cls.shouldFailoverKey -> "${cls.category}: ${cls.remediationHint}"
                else -> e.message?.take(140) ?: "connection error"
            }
            if (missingApiKey) {
                db.upsertSession(
                    session.copy(
                        status = Status.FAILED_PERMANENT,
                        retryCount = currentAttempt,
                        nextRetryEpochMs = 0L,
                        lastError = friendlyMsg.take(180)
                    )
                )
            } else if (isTransient || currentAttempt < MAX_AUTO_RETRIES) {
                val delayMin = if (isTransient) 2L else RETRY_INTERVAL_MINUTES
                db.upsertSession(
                    session.copy(
                        status = Status.FAILED_RETRYABLE,
                        retryCount = currentAttempt,
                        nextRetryEpochMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMin),
                        lastError = "AI Review retry scheduled ($friendlyMsg)"
                    )
                )
                scheduleRetry(applicationContext, sessionId, delayMin, AnalysisMode.AI_REVIEW_EXTRACTED_ONLY)
            } else {
                db.upsertSession(
                    session.copy(
                        status = Status.FAILED_PERMANENT,
                        retryCount = currentAttempt,
                        nextRetryEpochMs = 0L,
                        lastError = friendlyMsg.take(180)
                    )
                )
            }
            Result.failure()
        }
    }

    /**
     * Routes Gemini API calls strictly through the user's saved Google AI Studio API Key Pool:
     *  - Prioritizes healthy/untested keys not in cooldown for the target model (cryptographically shuffled)
     *  - Records per-key & per-model health/cooldown telemetry after every attempt
     *  - Automatically fails over from Primary Model (`prefs.geminiModel`) to Fallback Model
     *    (`prefs.fallbackGeminiModel`) when the pool hits quota (429), Paid-only limits, or 404.
     */
    private suspend fun executeWithApiKeyPool(
        sessionId: String,
        callApiKey: suspend (apiKey: String, model: String, strictModelOnly: Boolean) -> GeminiCallResponse
    ): Pair<GeminiCallResponse, String> {
        val orderedPool = prefs.getAllApiKeys()
        if (orderedPool.isEmpty()) {
            throw IllegalStateException(
                "No Gemini API key configured. Open Settings -> Gemini API Key Pool and add at least one Google AI Studio API key."
            )
        }

        val primaryModel = prefs.geminiModel
        val fallbackModel = prefs.fallbackGeminiModel
        val hasFallback = fallbackModel != PreferencesRepo.FALLBACK_MODEL_NONE &&
            !fallbackModel.equals(primaryModel, ignoreCase = true)
        val modelsToTry = if (hasFallback) listOf(primaryModel, fallbackModel) else listOf(primaryModel)

        var lastKeyErr: Exception? = null

        for ((modelIdx, activeModel) in modelsToTry.withIndex()) {
            val keysToTry = prefs.getPrioritizedApiKeysForModel(activeModel)
            val strictModel = modelIdx == 0 && hasFallback
            var sawQuotaBillingOrUnavailable = false
            var sawNetworkOffline = false

            for ((attemptIdx, keyCandidate) in keysToTry.withIndex()) {
                val slot = orderedPool.indexOf(keyCandidate).let { if (it >= 0) it + 1 else attemptIdx + 1 }
                val mask = if (keyCandidate.length > 10) {
                    "${keyCandidate.take(6)}...${keyCandidate.takeLast(4)}"
                } else "Key #$slot"
                try {
                    val resp = callApiKey(keyCandidate, activeModel, strictModel)
                    val resolvedModel = resp.actualModel.ifBlank { activeModel }
                    prefs.recordApiKeySuccess(keyCandidate, resolvedModel)
                    val fallbackTag = if (modelIdx > 0) " (fallback)" else ""
                    Log.i(
                        TAG,
                        "API Key Pool pick #$slot/${keysToTry.size} ($mask) succeeded on $resolvedModel$fallbackTag for $sessionId"
                    )
                    return resp to "$resolvedModel$fallbackTag:key-pool#$slot"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastKeyErr = e
                    val cls = GeminiInteractionsClient.classifyApiFailure(e)
                    prefs.recordApiKeyFailure(
                        key = keyCandidate,
                        model = activeModel,
                        failureCategory = cls.category,
                        errorMsg = e.message ?: cls.category
                    )
                    Log.w(
                        TAG,
                        "API Key Pool candidate #$slot/${keysToTry.size} ($mask) failed on $activeModel for $sessionId " +
                            "[${cls.category}]: ${e.message}"
                    )
                    if (cls.isQuotaOrBilling || cls.isModelUnavailable) {
                        sawQuotaBillingOrUnavailable = true
                    }
                    if (cls.isNetworkTransient && !cls.shouldFailoverKey) {
                        sawNetworkOffline = true
                        break
                    }
                    if (cls.isModelUnavailable) {
                        break // No need to burn remaining keys on a 404 model
                    }
                }
            }

            if (modelIdx == 0 && hasFallback) {
                if (sawQuotaBillingOrUnavailable && !sawNetworkOffline) {
                    Log.w(
                        TAG,
                        "Primary model '$primaryModel' hit quota/billing/404 across pool; auto-failing over to fallback '$fallbackModel' for $sessionId"
                    )
                    continue
                } else {
                    break
                }
            }
        }

        throw lastKeyErr ?: IllegalStateException("All ${orderedPool.size} saved API keys in pool failed.")
    }

    private suspend fun executeKeyPoolExtractedReviewCall(
        sessionId: String,
        systemInstruction: String,
        sessionContextText: String,
        extractedDocument: String
    ): Pair<GeminiCallResponse, String> {
        return executeWithApiKeyPool(sessionId = sessionId) { apiKey, activeModel, strictModel ->
            client.analyzeExtractedInfo(
                apiKey = apiKey,
                model = activeModel,
                systemInstruction = systemInstruction,
                sessionContextText = sessionContextText,
                extractedInfoDocument = extractedDocument,
                strictModelOnly = strictModel
            )
        }
    }

    private suspend fun autoMergeContinuousClusterIfNeeded(completedSessionId: String) {
        val individual = db.getCompletedIndividualSessionsWithDetails()
        val combined = db.getCombinedSessions()
        val clusters = ContinuousMergePlanner.detectClusters(
            individualSessions = individual,
            combinedSessions = combined,
            gapMinutes = prefs.autoMergeGapMinutes
        )
        val targetCluster = clusters.firstOrNull { c ->
            c.needsMerge && c.sessionIds.contains(completedSessionId)
        } ?: return

        val preferAudio = prefs.defaultReviewSourceIsAudio && targetCluster.hasAnyRawAudio
        val mode = if (preferAudio) AnalysisMode.AI_REVIEW_FROM_AUDIO else AnalysisMode.AI_REVIEW_EXTRACTED_ONLY
        if (mode == AnalysisMode.AI_REVIEW_EXTRACTED_ONLY && !targetCluster.hasAnyExtractedInfo) return

        val sources = targetCluster.sessions.map { it.session }
        val combinedId = targetCluster.existingCombinedSessionId ?: "combined_${UUID.randomUUID().toString().take(8)}"
        val existingCombined = targetCluster.existingCombinedSessionId?.let { db.getSessionById(it) }
        val groupId = sources.firstNotNullOfOrNull { it.eventGroupId ?: it.roundGroupId } ?: combinedId

        val entity = (existingCombined ?: SessionEntity(
            id = combinedId,
            startMs = targetCluster.startMs,
            endMs = targetCluster.endMs,
            speechMs = targetCluster.totalSpeechMs
        )).copy(
            roundGroupId = sources.firstNotNullOfOrNull { it.roundGroupId },
            eventGroupId = groupId,
            combinedSourceIdsJson = json.encodeToString(targetCluster.sessionIds),
            mode = if (sources.any { it.mode != SessionMode.EVERYDAY }) SessionMode.ROUND_AUTO else SessionMode.EVERYDAY,
            startMs = targetCluster.startMs,
            endMs = targetCluster.endMs,
            speechMs = targetCluster.totalSpeechMs,
            hadPhoneCall = sources.any { it.hadPhoneCall },
            rawPath = sources.firstOrNull { ContinuousMergePlanner.hasRawAudio(it) }?.rawPath ?: sources.first().rawPath,
            rawBytes = sources.sumOf { it.rawBytes },
            isPinned = true,
            title = "Auto-Merged Event (${sources.size} Continuous Clips)",
            summary = "Auto-merging & reviewing ${sources.size} related continuous recordings...",
            status = Status.QUEUED,
            retryCount = 0,
            nextRetryEpochMs = 0L,
            lastError = null
        )
        db.upsertSession(entity)
        enqueueImmediate(applicationContext, combinedId, mode)
    }

    private suspend fun executeKeyPoolAudioCall(
        sessionId: String,
        systemInstruction: String,
        sessionContextText: String,
        audioParts: List<AudioPartInput>,
        referenceVoices: List<ReferenceVoiceInput>
    ): Pair<GeminiCallResponse, String> {
        return executeWithApiKeyPool(sessionId = sessionId) { apiKey, activeModel, strictModel ->
            client.analyzeMultiAudio(
                apiKey = apiKey,
                model = activeModel,
                systemInstruction = systemInstruction,
                sessionContextText = sessionContextText,
                audioParts = audioParts,
                referenceVoices = referenceVoices,
                strictModelOnly = strictModel
            )
        }
    }

    private fun groupPartsIntoBatches(
        parts: List<IndexedAudioPart>,
        maxBytesPerBatch: Long
    ): List<List<IndexedAudioPart>> {
        val batches = mutableListOf<List<IndexedAudioPart>>()
        var currentBatch = mutableListOf<IndexedAudioPart>()
        var currentBytes = 0L

        for (p in parts) {
            val len = p.file.length()
            if (currentBatch.isNotEmpty() && currentBytes + len > maxBytesPerBatch) {
                batches.add(currentBatch.toList())
                currentBatch = mutableListOf()
                currentBytes = 0L
            }
            currentBatch.add(p)
            currentBytes += len
        }
        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch.toList())
        }
        return batches
    }

    private fun findPartForGlobalOffset(parts: List<IndexedAudioPart>, globalOffsetMs: Long): IndexedAudioPart {
        return parts.lastOrNull { it.globalOffsetMs <= globalOffsetMs } ?: parts.first()
    }

    private fun mergeBatchResponses(
        batches: List<Pair<List<IndexedAudioPart>, GeminiCallResponse>>,
        mode: AnalysisMode
    ): GeminiSessionAnalysisResult {
        if (batches.size == 1) {
            val (parts, resp) = batches.first()
            val offset = parts.first().globalOffsetMs
            if (offset == 0L) return resp.analysis
            return shiftAnalysisTimestamps(resp.analysis, offset)
        }

        val shifted = batches.map { (parts, resp) ->
            shiftAnalysisTimestamps(resp.analysis, parts.first().globalOffsetMs)
        }

        val firstValidTitle = shifted.firstOrNull {
            it.title.isNotBlank() && !it.title.contains("Ambient / Unclassified", ignoreCase = true)
        }?.title.orEmpty()

        val firstValidContext = shifted.firstOrNull {
            it.context.isNotBlank() && it.context != "OTHER" && it.context != "NOISE_OR_TV"
        }?.context ?: shifted.first().context

        val isExtractionLike = mode == AnalysisMode.INFO_EXTRACTION_EN ||
            mode == AnalysisMode.AI_REVIEW_FROM_AUDIO ||
            mode == AnalysisMode.AI_REVIEW_EXTRACTED_ONLY
        val mergedSummary = if (isExtractionLike) {
            shifted.mapIndexedNotNull { idx, r ->
                r.summary.takeIf { it.isNotBlank() }?.let { "### Part ${idx + 1}\n$it" }
            }.joinToString("\n\n")
        } else {
            shifted.firstOrNull { it.summary.isNotBlank() }?.summary ?: "100% verbatim transcript completed"
        }

        val mergedReviewReport = shifted.mapIndexedNotNull { idx, r ->
            r.aiReviewReport?.takeIf { it.isNotBlank() }?.let {
                if (shifted.size > 1) "### Segment Batch ${idx + 1} Audit\n$it" else it
            }
        }.joinToString("\n\n").ifBlank { null }

        // Coalesce speakers across batches while merging their candidate sample_windows
        val speakersByKey = LinkedHashMap<String, GeminiSpeakerDto>()
        shifted.flatMap { it.speakers }.forEach { spk ->
            val key = (spk.matchedProfileId?.takeIf { pid -> pid.isNotBlank() } ?: spk.speakerLabel).lowercase()
            val existing = speakersByKey[key]
            if (existing == null) {
                speakersByKey[key] = spk
            } else {
                val combinedWindows = (existing.sampleWindows + spk.sampleWindows)
                    .distinctBy { "${it.start}_${it.end}" }
                    .take(6)
                speakersByKey[key] = existing.copy(
                    sampleStart = existing.sampleStart?.takeIf { it.isNotBlank() } ?: spk.sampleStart,
                    sampleEnd = existing.sampleEnd?.takeIf { it.isNotBlank() } ?: spk.sampleEnd,
                    voiceSignature = if (spk.voiceSignature.length > existing.voiceSignature.length) {
                        spk.voiceSignature
                    } else {
                        existing.voiceSignature
                    },
                    sampleWindows = combinedWindows
                )
            }
        }

        return GeminiSessionAnalysisResult(
            hasMeaningfulContent = shifted.any { it.hasMeaningfulContent },
            isClinicalRound = shifted.any { it.isClinicalRound },
            context = firstValidContext,
            title = firstValidTitle,
            summary = mergedSummary,
            keyPoints = shifted.flatMap { it.keyPoints }.distinct(),
            speakers = speakersByKey.values.toList(),
            transcript = shifted.flatMap { it.transcript },
            patients = shifted.flatMap { it.patients },
            items = shifted.flatMap { it.items },
            aiReviewReport = mergedReviewReport,
            discoveredGlossaryTerms = shifted
                .flatMap { it.discoveredGlossaryTerms }
                .filter { it.term.isNotBlank() }
                .distinctBy { MedicalGlossaryManager.normalizeToken(it.term) }
        )
    }

    private fun shiftAnalysisTimestamps(res: GeminiSessionAnalysisResult, offsetMs: Long): GeminiSessionAnalysisResult {
        if (offsetMs <= 0L) return res
        fun addOffset(mmSs: String): String {
            val base = ClipSlicer.parseApproxMmSsToMs(mmSs)
            return formatMmSs(base + offsetMs)
        }
        return res.copy(
            speakers = res.speakers.map { spk ->
                spk.copy(
                    sampleStart = spk.sampleStart?.takeIf { it.isNotBlank() }?.let { addOffset(it) },
                    sampleEnd = spk.sampleEnd?.takeIf { it.isNotBlank() }?.let { addOffset(it) },
                    sampleWindows = spk.sampleWindows.map { w ->
                        w.copy(
                            start = if (w.start.isNotBlank()) addOffset(w.start) else w.start,
                            end = if (w.end.isNotBlank()) addOffset(w.end) else w.end
                        )
                    }
                )
            },
            transcript = res.transcript.map { it.copy(t = addOffset(it.t)) },
            patients = res.patients.map {
                it.copy(
                    approxStart = addOffset(it.approxStart),
                    approxEnd = addOffset(it.approxEnd)
                )
            },
            items = res.items.map {
                it.copy(
                    approxStart = addOffset(it.approxStart),
                    approxEnd = addOffset(it.approxEnd)
                )
            }
        )
    }

    private fun formatMmSs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val m = totalSec / 60L
        val s = totalSec % 60L
        return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }

    companion object {
        private const val TAG = "SessionAnalyzeWorker"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_ANALYSIS_MODE = "analysis_mode"
        private const val PRIMARY_INLINE_TOTAL_LIMIT = 11_000_000L
        const val MAX_AUTO_RETRIES = 3
        const val RETRY_INTERVAL_MINUTES = 10L

        fun enqueue(
            context: Context,
            sessionId: String,
            mode: AnalysisMode = AnalysisMode.INFO_EXTRACTION_EN
        ) {
            enqueueImmediate(context, sessionId, mode)
        }

        fun enqueueImmediate(
            context: Context,
            sessionId: String,
            mode: AnalysisMode = AnalysisMode.INFO_EXTRACTION_EN
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SessionAnalyzeWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_SESSION_ID to sessionId,
                        KEY_ANALYSIS_MODE to mode.name
                    )
                )
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "analyze_session_$sessionId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun scheduleRetry(
            context: Context,
            sessionId: String,
            delayMinutes: Long = RETRY_INTERVAL_MINUTES,
            mode: AnalysisMode = AnalysisMode.INFO_EXTRACTION_EN
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SessionAnalyzeWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_SESSION_ID to sessionId,
                        KEY_ANALYSIS_MODE to mode.name
                    )
                )
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "analyze_session_$sessionId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun retryNow(
            context: Context,
            sessionId: String,
            mode: AnalysisMode = AnalysisMode.INFO_EXTRACTION_EN
        ) {
            val db = AppDatabase.get(context).dao()
            CoroutineScope(Dispatchers.IO).launch {
                val s = db.getSessionById(sessionId) ?: return@launch
                if (s.combinedSourceIdsJson.isNullOrBlank()) {
                    val rawFile = s.rawPath?.let { File(it) }
                    if (rawFile == null || !rawFile.exists()) {
                        db.upsertSession(
                            s.copy(
                                status = Status.FAILED_PERMANENT,
                                lastError = "Recorded audio file not found."
                            )
                        )
                        return@launch
                    }
                }
                db.upsertSession(
                    s.copy(
                        status = Status.QUEUED,
                        retryCount = 0,
                        nextRetryEpochMs = 0L,
                        lastError = null
                    )
                )
                enqueueImmediate(context, sessionId, mode)
            }
        }

        fun requestOriginalTranscriptNow(context: Context, sessionId: String) {
            retryNow(context, sessionId, AnalysisMode.VERBATIM_ORIGINAL_TRANSCRIPT)
        }

        /**
         * Triggers AI Review for an existing single or combined session, honoring the user's explicit
         * source choice ([ReviewSourceMode.EXTRACTED_ONLY] vs [ReviewSourceMode.REANALYZE_AUDIO]).
         */
        fun enqueueAiReview(
            context: Context,
            sessionId: String,
            sourceMode: ReviewSourceMode
        ) {
            val analysisMode = when (sourceMode) {
                ReviewSourceMode.EXTRACTED_ONLY -> AnalysisMode.AI_REVIEW_EXTRACTED_ONLY
                ReviewSourceMode.REANALYZE_AUDIO -> AnalysisMode.AI_REVIEW_FROM_AUDIO
            }
            val db = AppDatabase.get(context).dao()
            CoroutineScope(Dispatchers.IO).launch {
                val s = db.getSessionById(sessionId) ?: return@launch
                if (sourceMode == ReviewSourceMode.REANALYZE_AUDIO && s.combinedSourceIdsJson.isNullOrBlank()) {
                    val rawFile = s.rawPath?.let { File(it) }
                    if (rawFile == null || !rawFile.exists()) {
                        db.upsertSession(
                            s.copy(
                                status = Status.FAILED_PERMANENT,
                                lastError = "Raw audio file not found on device. Use 'Previously Extracted Info Only' instead."
                            )
                        )
                        return@launch
                    }
                }
                db.upsertSession(
                    s.copy(
                        status = Status.QUEUED,
                        retryCount = 0,
                        nextRetryEpochMs = 0L,
                        lastError = null
                    )
                )
                enqueueImmediate(context, sessionId, analysisMode)
            }
        }

        /**
         * Creates (or updates) a unified Combined/Merged session spanning user-selected or
         * continuous-cluster recordings using the chosen analysis mode and source choice.
         */
        fun enqueueCombinedAnalysis(
            context: Context,
            sourceSessionIds: List<String>,
            mode: AnalysisMode = AnalysisMode.INFO_EXTRACTION_EN,
            existingCombinedSessionId: String? = null,
            isAutoMerged: Boolean = false
        ) {
            val distinctIds = sourceSessionIds.distinct()
            if (distinctIds.size < 2) return
            val db = AppDatabase.get(context).dao()
            val serializer = Json { encodeDefaults = true }

            CoroutineScope(Dispatchers.IO).launch {
                val sources = db.getSessionsByIds(distinctIds).sortedBy { it.startMs }
                if (sources.isEmpty()) return@launch
                val combinedId = existingCombinedSessionId ?: "combined_${UUID.randomUUID().toString().take(8)}"
                val priorCombined = existingCombinedSessionId?.let { db.getSessionById(it) }
                val groupId = sources.firstNotNullOfOrNull { it.eventGroupId ?: it.roundGroupId } ?: combinedId
                val titlePrefix = if (isAutoMerged) "Auto-Merged Event" else "Combined Event"
                val entity = (priorCombined ?: SessionEntity(
                    id = combinedId,
                    startMs = sources.minOf { it.startMs },
                    endMs = sources.maxOf { it.endMs },
                    speechMs = sources.sumOf { it.speechMs }
                )).copy(
                    roundGroupId = sources.firstNotNullOfOrNull { it.roundGroupId },
                    eventGroupId = groupId,
                    combinedSourceIdsJson = serializer.encodeToString(sources.map { it.id }),
                    mode = if (sources.any { it.mode != SessionMode.EVERYDAY }) SessionMode.ROUND_MANUAL else SessionMode.EVERYDAY,
                    startMs = sources.minOf { it.startMs },
                    endMs = sources.maxOf { it.endMs },
                    speechMs = sources.sumOf { it.speechMs },
                    hadPhoneCall = sources.any { it.hadPhoneCall },
                    rawPath = sources.firstOrNull { ContinuousMergePlanner.hasRawAudio(it) }?.rawPath ?: sources.first().rawPath,
                    rawBytes = sources.sumOf { it.rawBytes },
                    isPinned = true,
                    title = "$titlePrefix (${sources.size} Recordings)",
                    summary = if (mode == AnalysisMode.AI_REVIEW_EXTRACTED_ONLY) {
                        "Synthesizing & reviewing previously extracted info across ${sources.size} recordings..."
                    } else {
                        "Queued Gemini analysis across ${sources.size} audio recordings..."
                    },
                    status = Status.QUEUED,
                    retryCount = 0,
                    nextRetryEpochMs = 0L,
                    lastError = null
                )
                db.upsertSession(entity)
                enqueueImmediate(context, combinedId, mode)
            }
        }

        fun enqueueCombinedWithSourceChoice(
            context: Context,
            sourceSessionIds: List<String>,
            sourceMode: ReviewSourceMode,
            wantVerbatimTranscript: Boolean = false,
            existingCombinedSessionId: String? = null,
            isAutoMerged: Boolean = false
        ) {
            val analysisMode = when {
                wantVerbatimTranscript && sourceMode == ReviewSourceMode.REANALYZE_AUDIO ->
                    AnalysisMode.VERBATIM_ORIGINAL_TRANSCRIPT
                sourceMode == ReviewSourceMode.EXTRACTED_ONLY ->
                    AnalysisMode.AI_REVIEW_EXTRACTED_ONLY
                else ->
                    AnalysisMode.AI_REVIEW_FROM_AUDIO
            }
            enqueueCombinedAnalysis(
                context = context,
                sourceSessionIds = sourceSessionIds,
                mode = analysisMode,
                existingCombinedSessionId = existingCombinedSessionId,
                isAutoMerged = isAutoMerged
            )
        }

        fun analyzeStandaloneAudioFile(
            context: Context,
            audioFile: File,
            existingSessionId: String?,
            mode: AnalysisMode
        ) {
            if (!audioFile.exists() || audioFile.length() == 0L) return
            if (!existingSessionId.isNullOrBlank()) {
                retryNow(context, existingSessionId, mode)
                return
            }
            val db = AppDatabase.get(context).dao()
            CoroutineScope(Dispatchers.IO).launch {
                val derivedId = audioFile.nameWithoutExtension
                    .removePrefix("session_")
                    .removePrefix("clip_")
                    .ifBlank { UUID.randomUUID().toString() }
                val existing = db.getSessionById(derivedId)
                if (existing != null) {
                    db.upsertSession(
                        existing.copy(
                            rawPath = audioFile.absolutePath,
                            rawBytes = audioFile.length(),
                            status = Status.QUEUED,
                            retryCount = 0,
                            nextRetryEpochMs = 0L,
                            lastError = null
                        )
                    )
                    enqueueImmediate(context, derivedId, mode)
                } else {
                    val estDurMs = ClipSlicer.estimateAdtsDurationMs(audioFile)
                        .takeIf { it > 0L }
                        ?: ((audioFile.length() * 1000L) / 4_110L).coerceAtLeast(3_000L)
                    val nowMs = System.currentTimeMillis()
                    val fileMtime = audioFile.lastModified().takeIf { it > 0L } ?: nowMs
                    // Finalized sessions & restored backups stamp lastModified = startMs;
                    // if fileMtime + estDurMs would extend into the future (e.g. mid-recording crash),
                    // fileMtime is the last write/end time so subtract estDurMs to recover startMs.
                    val startMs = if (fileMtime + estDurMs > nowMs + 5_000L) {
                        (fileMtime - estDurMs).coerceAtLeast(0L)
                    } else {
                        fileMtime
                    }
                    val newSession = SessionEntity(
                        id = derivedId,
                        mode = SessionMode.EVERYDAY,
                        startMs = startMs,
                        endMs = startMs + estDurMs,
                        speechMs = estDurMs,
                        rawPath = audioFile.absolutePath,
                        rawBytes = audioFile.length(),
                        status = Status.QUEUED
                    )
                    db.upsertSession(newSession)
                    enqueueImmediate(context, derivedId, mode)
                }
            }
        }

        fun retryAllFailed(context: Context) {
            val db = AppDatabase.get(context).dao()
            CoroutineScope(Dispatchers.IO).launch {
                val failedSessions = db.getFailedSessions()
                for (s in failedSessions) {
                    val rawFile = s.rawPath?.let { File(it) }
                    if (!s.combinedSourceIdsJson.isNullOrBlank() || (rawFile != null && rawFile.exists())) {
                        db.upsertSession(
                            s.copy(
                                status = Status.QUEUED,
                                retryCount = 0,
                                nextRetryEpochMs = 0L,
                                lastError = null
                            )
                        )
                        enqueueImmediate(context, s.id, AnalysisMode.INFO_EXTRACTION_EN)
                    }
                }
            }
        }
    }
}
