package com.ambientbrain.recorder.ai

import android.content.Context
import android.util.Log
import com.ambientbrain.recorder.data.AnalysisRunEntity
import com.ambientbrain.recorder.data.AppDao
import com.ambientbrain.recorder.data.AppDatabase
import com.ambientbrain.recorder.data.AttachableVoiceClip
import com.ambientbrain.recorder.data.ClipSlicer
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SegmentMapEntity
import com.ambientbrain.recorder.data.SessionSpeakerEntity
import com.ambientbrain.recorder.data.Status
import com.ambientbrain.recorder.data.SessionWithDetails
import com.ambientbrain.recorder.data.SpeakerProfileEntry
import com.ambientbrain.recorder.data.VoiceProfileSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

enum class VoiceExtractionTimeFrame(val label: String) {
    ALL("All Recordings"),
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_7_DAYS("Last 7 Days"),
    LAST_30_DAYS("Last 30 Days"),
    CUSTOM_RANGE("Custom Range")
}

data class PreGeminiDiarizationAnchor(
    val globalStartMs: Long,
    val globalEndMs: Long,
    val clusterId: Int,
    val matchedProfileId: String?,
    val matchedLabel: String?,
    val isSelfUser: Boolean,
    val matchSimilarity: Float,
    val medianF0Hz: Float,
    val acousticBrief: String,
    val singleSpeakerPurity: Float
)

data class PreGeminiVoiceAnalysisResult(
    val attachableClips: List<AttachableVoiceClip>,
    val referenceVoices: List<ReferenceVoiceInput>,
    val preMatchScores: Map<String, Float>,
    val anchors: List<PreGeminiDiarizationAnchor>,
    val detectedClusterCount: Int,
    val changePointCount: Int
) {
    /**
     * Builds a concise, batch-relative On-Device Acoustic Diarization Timeline Hint for the
     * `[windowStartMs, windowEndMs]` slice sent to Gemini so timestamp diarization stays anchored
     * to verified on-device voiceprints without speaker-swap drift across long turns.
     */
    fun formatDiarizationHintForWindow(
        windowStartMs: Long = 0L,
        windowEndMs: Long = Long.MAX_VALUE,
        maxLines: Int = 22
    ): String {
        val relevant = anchors.filter { a ->
            a.globalEndMs > windowStartMs && a.globalStartMs < windowEndMs
        }
        if (relevant.isEmpty()) return ""

        fun fmtMmSs(ms: Long): String {
            val sec = (ms.coerceAtLeast(0L) / 1000L)
            return String.format(Locale.US, "%02d:%02d", sec / 60L, sec % 60L)
        }

        val capped = if (relevant.size <= maxLines) {
            relevant
        } else {
            // Keep evenly spread + longest/matched anchors so the full batch timeline is covered
            val step = relevant.size.toDouble() / maxLines.toDouble()
            List(maxLines) { i -> relevant[(i * step).toInt().coerceIn(0, relevant.lastIndex)] }
                .distinctBy { it.globalStartMs to it.globalEndMs }
        }

        val matchedCount = relevant.count { !it.matchedProfileId.isNullOrBlank() }
        return buildString {
            appendLine(
                "ON-DEVICE ACOUSTIC DIARIZATION PRE-SCAN (Advisory Anchors — ${detectedClusterCount} distinct acoustic cluster(s), " +
                    "${relevant.size} clean solo turn(s), $matchedCount matched to enrolled profiles):"
            )
            capped.forEach { anchor ->
                val relStart = (anchor.globalStartMs - windowStartMs).coerceAtLeast(0L)
                val relEnd = (minOf(anchor.globalEndMs, windowEndMs) - windowStartMs).coerceAtLeast(relStart + 1_000L)
                val tsRange = "[${fmtMmSs(relStart)}–${fmtMmSs(relEnd)}]"
                val f0Tag = if (anchor.medianF0Hz in 75f..420f) "~${anchor.medianF0Hz.toInt()} Hz" else ""
                val purityTag = "${(anchor.singleSpeakerPurity * 100f).toInt().coerceIn(50, 100)}% solo purity"
                val detailParts = listOfNotNull(
                    f0Tag.takeIf { it.isNotBlank() },
                    anchor.acousticBrief.takeIf { it.isNotBlank() },
                    purityTag
                ).joinToString(", ")

                if (!anchor.matchedProfileId.isNullOrBlank() && !anchor.matchedLabel.isNullOrBlank()) {
                    val simPct = (anchor.matchSimilarity * 100f).toInt().coerceIn(1, 99)
                    val idTag = if (anchor.isSelfUser) "is_user=true, profile_id=me" else "profile_id=${anchor.matchedProfileId}"
                    appendLine("- $tsRange Acoustic match: ${anchor.matchedLabel} [$idTag] (${simPct}% voiceprint sim; $detailParts)")
                } else {
                    appendLine("- $tsRange Unenrolled Acoustic Cluster #${anchor.clusterId + 1} ($detailParts)")
                }
            }
            append(
                "Guidance: Use these on-device acoustic anchors to stabilize speaker continuity across turns and avoid swapping similar voices. " +
                    "They are advisory—always verify with the actual audio if an anchor is uncertain or speakers overlap."
            )
        }.trim()
    }
}

/**
 * Coordinates Auto Voice Profiles Recognition (hybrid Gemini + on-device 64-D v2 spectral-subtracted
 * & formant/HNR voiceprint matching), VAD+BIC change-point speaker clustering, crosstalk purity gating,
 * pre-Gemini diarization timeline anchoring, memorable recognizing nicknames & relatable titles,
 * multi-candidate acoustic quality evaluation & solo sample slicing (retaining up to 10 highest-quality
 * samples per profile), time-framed retroactive extraction from old recordings, Name/Title/Description
 * suitability rechecks, and Gemini Top-Pick Voice Profile Audit & Creation.
 */
object VoiceProfileAutoManager {
    private const val TAG = "VoiceProfileAutoMgr"
    private const val MAX_AUTO_DISCOVERED_UNPINNED = 35
    private const val MIN_AUTO_SAMPLE_QUALITY_SCORE = 35f
    private const val MIN_SELF_AUTO_SAMPLE_QUALITY_SCORE = 52f
    private const val MIN_VOICEPRINT_MATCH_QUALITY_SCORE = 42f
    private const val MIN_UNLABELED_WINDOW_QUALITY_SCORE = 50f
    private const val MAX_CANDIDATE_WINDOWS_PER_SPEAKER = 10
    private const val MAX_COMMITTED_SAMPLES_PER_SESSION_SPEAKER = 4

    private val SYNTH_ADJECTIVE_PREFIXES = listOf(
        "Clear-Tone ", "Steady-Voice ", "Warm-Tone ", "Calm-Pace ", "Brisk-Voice ", "Crisp-Tone ",
        "Deep-Tone ", "Low-Pitch ", "High-Pitch ", "Bright-Tone ", "Gentle ", "Soft-Spoken ",
        "Fast-Paced ", "Measured ", "Resonant ", "Warm ", "Calm ", "Brisk ", "Crisp ", "Steady "
    )

    private val GENERIC_ROLE_ONLY_NAMES = setOf(
        "clinical team presenter", "care team colleague", "conversation partner", "call partner",
        "conversation voice", "attending", "senior attending", "neuro attending", "senior neuro attending",
        "supervising attending", "supervising physician", "attending physician", "resident", "ward resident",
        "presenting resident", "presenting ward resident", "case resident", "senior resident", "junior resident",
        "fellow", "icu fellow", "neuro fellow", "intern", "medical student", "nurse", "charge nurse",
        "ward nurse", "icu nurse", "neuro icu charge nurse", "pharmacist", "on-call pharmacist",
        "doctor", "physician", "clinician", "presenter", "colleague", "engineering colleague",
        "team member", "patient", "patient's daughter", "patient's son", "patient's wife",
        "patient's husband", "patient's mother", "patient's father", "patient's relative",
        "family member", "relative", "caregiver", "caller", "elder caller", "partner", "voice", "staff"
    )

    private val HONORIFIC_PREFIX_REGEX = Regex(
        "^(?:dr\\.?|prof\\.?|ajarn|aj\\.?|nurse|mr\\.?|mrs\\.?|ms\\.?|khun|p'|p\\s+|คุณ|หมอ|พญ\\.?|นพ\\.?|อ\\.?|อาจารย์|พี่|พยาบาล)\\s*",
        RegexOption.IGNORE_CASE
    )

    private val rosterLock = Any()

    @Volatile
    private var lastHarvestedTopPickCandidates: List<TopPickUnidentifiedCandidate> = emptyList()

    data class SliceSourceSegment(
        val file: File,
        val globalOffsetMs: Long,
        val durationMs: Long,
        val segments: List<SegmentMapEntity> = emptyList(),
        val sourceStartMs: Long = 0L
    )

    data class ReconciledSpeakersResult(
        val speakers: List<SessionSpeakerEntity>,
        val speakerLabelById: Map<String, String>,
        val autoEnrolledCount: Int,
        val matchedExistingCount: Int = 0,
        val samplesSavedOrUpgradedCount: Int = 0
    )

    data class SessionVoiceExtractionResult(
        val sessionId: String,
        val sessionTitle: String,
        val hadRawAudio: Boolean,
        val hadSpeakerLabels: Boolean,
        val profilesMatched: Int,
        val profilesCreated: Int,
        val samplesAddedOrUpgraded: Int,
        val needsAiAnalysis: Boolean,
        val summaryMessage: String
    )

    data class BatchVoiceExtractionSummary(
        val timeFrameLabel: String = "All Recordings",
        val sessionsScanned: Int,
        val standaloneFilesScanned: Int,
        val profilesMatched: Int,
        val profilesCreated: Int,
        val samplesAddedOrUpgraded: Int,
        val unlabeledMatched: Int,
        val sessionsNeedingAiAnalysis: Int,
        val skippedNoAudio: Int,
        val skippedOutOfTimeFrame: Int = 0,
        val contaminatedSamplesSeparated: Int = 0,
        val profilesMetadataRechecked: Int = 0,
        val topPickAudiosReadyCount: Int = 0,
        val topPickUnidentifiedCandidatesCount: Int = 0
    )

    data class TopPickUnidentifiedCandidate(
        val id: String,
        val filePath: String,
        val durationMs: Long,
        val qualityScore: Float,
        val snrDb: Float,
        val speechRatio: Float,
        val recordedAtMs: Long,
        val sourceSessionId: String?,
        val sourceTitle: String,
        val sourceContextHint: String,
        val embedding: List<Float>
    )

    data class GeminiProfileAuditItemSummary(
        val profileId: String,
        val previousName: String,
        val finalName: String,
        val previousTitle: String,
        val finalTitle: String,
        val previousSignature: String,
        val finalSignature: String,
        val wasNameRefined: Boolean,
        val wasTitleRefined: Boolean,
        val wasDescriptionRefined: Boolean,
        val wasSeparatedFromMixedSpeaker: Boolean,
        val isNewlyCreated: Boolean,
        val auditNotes: String
    )

    data class GeminiVoiceProfileAuditSummary(
        val profilesAudited: Int,
        val topPickAudiosSent: Int,
        val profilesRefined: Int,
        val newProfilesCreated: Int,
        val mixedProfilesSeparated: Int,
        val modelUsed: String,
        val overallAuditSummary: String,
        val items: List<GeminiProfileAuditItemSummary>
    )

    private data class CandidateWindow(
        val startMs: Long,
        val endMs: Long,
        val priorityBonus: Float
    )

    private data class EvaluatedCandidateClip(
        val tempFile: File,
        val evalDir: File,
        val durationMs: Long,
        val metrics: ClipSlicer.VoiceSampleQualityMetrics,
        val embedding: List<Float>,
        val recordedAtMs: Long
    ) {
        fun cleanup() {
            runCatching { evalDir.deleteRecursively() }
        }
    }

    fun resolveTimeFrameBounds(
        timeFrame: VoiceExtractionTimeFrame,
        customStartLocalDate: LocalDate? = null,
        customEndLocalDate: LocalDate? = null
    ): Triple<Long?, Long?, String> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val fmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
        return when (timeFrame) {
            VoiceExtractionTimeFrame.ALL -> Triple(null, null, "All Recordings")
            VoiceExtractionTimeFrame.TODAY -> {
                val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
                val endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
                Triple(startMs, endMs, "Today (${today.format(fmt)})")
            }
            VoiceExtractionTimeFrame.YESTERDAY -> {
                val yest = today.minusDays(1)
                val startMs = yest.atStartOfDay(zone).toInstant().toEpochMilli()
                val endMs = today.atStartOfDay(zone).toInstant().toEpochMilli() - 1L
                Triple(startMs, endMs, "Yesterday (${yest.format(fmt)})")
            }
            VoiceExtractionTimeFrame.LAST_7_DAYS -> {
                val startDay = today.minusDays(6)
                val startMs = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
                val endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
                Triple(startMs, endMs, "Last 7 Days")
            }
            VoiceExtractionTimeFrame.LAST_30_DAYS -> {
                val startDay = today.minusDays(29)
                val startMs = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
                val endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
                Triple(startMs, endMs, "Last 30 Days")
            }
            VoiceExtractionTimeFrame.CUSTOM_RANGE -> {
                val sDate = customStartLocalDate ?: customEndLocalDate ?: today.minusDays(7)
                val eDate = (customEndLocalDate ?: customStartLocalDate ?: today).let {
                    if (it.isBefore(sDate)) sDate else it
                }
                val startMs = sDate.atStartOfDay(zone).toInstant().toEpochMilli()
                val endMs = eDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
                val label = if (sDate == eDate) {
                    sDate.format(fmt)
                } else {
                    "${sDate.format(fmt)} – ${eDate.format(fmt)}"
                }
                Triple(startMs, endMs, label)
            }
        }
    }

    fun isTimestampInTimeFrame(
        startMs: Long,
        endMs: Long = startMs,
        minTimestampMs: Long?,
        maxTimestampMs: Long?
    ): Boolean {
        if (minTimestampMs == null && maxTimestampMs == null) return true
        val effectiveStart = startMs.takeIf { it > 0L } ?: endMs
        val effectiveEnd = endMs.takeIf { it >= effectiveStart } ?: effectiveStart
        if (effectiveStart <= 0L && effectiveEnd <= 0L) return false
        if (minTimestampMs != null && effectiveEnd < minTimestampMs) return false
        if (maxTimestampMs != null && effectiveStart > maxTimestampMs) return false
        return true
    }

    /**
     * Lazily backfills 64-D v2 acoustic voiceprints (and automatically upgrades legacy 48-D
     * voiceprints) for stored My Voice or Speaker Roster samples. Capped at [maxToCompute] per call
     * to keep execution fast.
     */
    fun ensureSampleEmbeddingsBackfilled(
        context: Context,
        prefs: PreferencesRepo,
        maxToCompute: Int = 12
    ): Int {
        val appContext = context.applicationContext
        var computed = 0
        fun needsUpgrade(emb: List<Float>): Boolean {
            return emb.isEmpty() || emb.size == SpeakerEmbeddingEngine.LEGACY_EMBEDDING_DIM
        }
        synchronized(rosterLock) {
            // 1. Backfill / upgrade My Voice samples
            val mySamples = prefs.getMyVoiceSamples()
            if (mySamples.any { needsUpgrade(it.embedding) } && computed < maxToCompute) {
                var changed = false
                val updatedMy = mySamples.map { s ->
                    if (needsUpgrade(s.embedding) && computed < maxToCompute) {
                        val emb = SpeakerEmbeddingEngine.extractEmbeddingFromFile(appContext, File(s.filePath))
                        computed++
                        if (emb.isNotEmpty()) {
                            changed = true
                            s.copy(embedding = emb)
                        } else s
                    } else s
                }
                if (changed) {
                    prefs.saveMyVoiceSamples(updatedMy)
                }
            }

            // 2. Backfill / upgrade Speaker Roster samples
            if (computed < maxToCompute) {
                val roster = prefs.getSpeakerRoster()
                var rosterChanged = false
                val updatedRoster = roster.map { entry ->
                    val existing = entry.existingSamples()
                    if (existing.any { needsUpgrade(it.embedding) } && computed < maxToCompute) {
                        var entryChanged = false
                        val updatedSamples = existing.map { s ->
                            if (needsUpgrade(s.embedding) && computed < maxToCompute) {
                                val emb = SpeakerEmbeddingEngine.extractEmbeddingFromFile(appContext, File(s.filePath))
                                computed++
                                if (emb.isNotEmpty()) {
                                    entryChanged = true
                                    s.copy(embedding = emb)
                                } else s
                            } else s
                        }
                        if (entryChanged) {
                            rosterChanged = true
                            entry.copy(samples = updatedSamples)
                        } else entry
                    } else entry
                }
                if (rosterChanged) {
                    prefs.saveSpeakerRoster(updatedRoster)
                }
            }
        }
        if (computed > 0) {
            Log.i(TAG, "Computed/upgraded 64-D v2 on-device voiceprints for $computed stored voice sample(s)")
        }
        return computed
    }

    /**
     * Task 4 — Unified Pre-Gemini On-Device Voice Analysis Pipeline:
     * Combines:
     *  1. Lazy backfill/upgrade of stored samples to 64-D v2 spectral-subtracted + formant/HNR voiceprints.
     *  2. VAD-guided BIC/GLR change-point segmentation + crosstalk purity gating + complete-linkage clustering
     *     across all recording file(s) (`audioSourcesWithOffsets`).
     *  3. Biometric matching of every discovered speaker cluster & clean island against "My Voice" and
     *     the Speaker Roster to shortlist the best attachable reference clips AND construct chronological
     *     on-device diarization timeline anchors injected into the Gemini prompt.
     */
    fun analyzeSessionAudioPreGemini(
        context: Context?,
        prefs: PreferencesRepo,
        audioSourcesWithOffsets: List<Pair<File, Long>>,
        maxExtraSpeakers: Int = 6,
        maxSamplesPerTopSpeaker: Int = 2,
        maxTotalBytes: Long = 3_200_000L
    ): PreGeminiVoiceAnalysisResult {
        var preMatchScores: Map<String, Float> = emptyMap()
        val anchors = mutableListOf<PreGeminiDiarizationAnchor>()
        var detectedClusters = 0
        var totalChangePoints = 0

        val validFiles = audioSourcesWithOffsets.filter { (f, _) -> f.exists() && f.length() >= 8_192L }
        if (context != null && validFiles.isNotEmpty()) {
            runCatching {
                ensureSampleEmbeddingsBackfilled(context, prefs, maxToCompute = 12)
                val mySamples = prefs.getMyVoiceSamples()
                val myDisplayName = prefs.userDisplayName.ifBlank { "Dr. (Me)" }
                val roster = prefs.getSpeakerRoster()
                val rosterById = roster.associateBy { it.id }
                val rosterWithAudio = roster.filter { it.existingSamples().isNotEmpty() }

                val allSegments = mutableListOf<AcousticSpeakerSegment>()
                val perFileCap = (18 / validFiles.size).coerceIn(6, 18)

                for ((file, globalOffsetMs) in validFiles) {
                    val scan = SpeakerEmbeddingEngine.extractHomogeneousSpeakerSegments(
                        context = context,
                        audioFile = file,
                        maxSegments = perFileCap
                    )
                    totalChangePoints += scan.changePointCount
                    scan.segments.forEach { seg ->
                        allSegments.add(
                            seg.copy(
                                startMs = seg.startMs + globalOffsetMs,
                                endMs = seg.endMs + globalOffsetMs
                            )
                        )
                    }
                }

                if (allSegments.isNotEmpty()) {
                    val (clusteredSegs, clusters) = if (validFiles.size > 1) {
                        SpeakerEmbeddingEngine.clusterSpeakerSegments(allSegments)
                    } else {
                        // Already clustered inside single-file scan; recompute summaries cleanly
                        SpeakerEmbeddingEngine.clusterSpeakerSegments(allSegments)
                    }
                    detectedClusters = clusters.size

                    // Score every roster speaker against cluster centroids + individual clean islands
                    val queryEmbeddings = buildList {
                        clusters.forEach { if (it.centroid.isNotEmpty()) add(it.centroid) }
                        clusteredSegs.forEach { if (it.embedding.isNotEmpty()) add(it.embedding) }
                    }
                    if (rosterWithAudio.isNotEmpty() && queryEmbeddings.isNotEmpty()) {
                        preMatchScores = SpeakerEmbeddingEngine.scoreRosterAgainstSessionWindows(
                            roster = rosterWithAudio,
                            sessionWindowEmbeddings = queryEmbeddings
                        )
                        if (preMatchScores.isNotEmpty()) {
                            val topStr = preMatchScores.entries
                                .sortedByDescending { it.value }
                                .take(5)
                                .joinToString(", ") { (pid, sc) ->
                                    val name = rosterById[pid]?.effectiveNickname() ?: pid
                                    "$name=${(sc * 100).toInt()}%"
                                }
                            Log.i(
                                TAG,
                                "Pre-Gemini voiceprint shortlist (${clusters.size} cluster(s), ${clusteredSegs.size} island(s)): $topStr"
                            )
                        }
                    }

                    // Match each discovered acoustic cluster against My Voice + Speaker Roster (1-to-1 claim)
                    val claimedProfileIds = mutableSetOf<String>()
                    val clusterMatches = HashMap<Int, VoiceprintMatchResult>()
                    val clusterOrdered = clusters.sortedByDescending { it.totalDurationMs }
                    for (cluster in clusterOrdered) {
                        if (cluster.centroid.isEmpty()) continue
                        val match = SpeakerEmbeddingEngine.findBestVoiceprintMatch(
                            candidateEmbedding = cluster.centroid,
                            myVoiceSamples = mySamples,
                            roster = rosterWithAudio,
                            excludedProfileIds = claimedProfileIds,
                            minSelfSimilarity = 0.75f,
                            minRosterSimilarity = 0.73f,
                            minMargin = 0.035f
                        )
                        if (match != null) {
                            clusterMatches[cluster.clusterId] = match
                            claimedProfileIds.add(if (match.isSelfUser) "me" else match.profileId)
                        }
                    }

                    // Build chronological diarization anchors & merge adjacent same-speaker islands (gap <= 4.0s)
                    val clusterById = clusters.associateBy { it.clusterId }
                    val rawAnchors = clusteredSegs.sortedBy { it.startMs }.map { seg ->
                        val cMatch = clusterMatches[seg.clusterId] ?: SpeakerEmbeddingEngine.findBestVoiceprintMatch(
                            candidateEmbedding = seg.embedding,
                            myVoiceSamples = mySamples,
                            roster = rosterWithAudio,
                            excludedProfileIds = emptySet(),
                            minSelfSimilarity = 0.79f,
                            minRosterSimilarity = 0.77f,
                            minMargin = 0.045f
                        )
                        val label = when {
                            cMatch == null -> null
                            cMatch.isSelfUser -> myDisplayName
                            else -> rosterById[cMatch.profileId]?.effectiveNickname()
                        }
                        val desc = clusterById[seg.clusterId]?.descriptor
                            ?: SpeakerEmbeddingEngine.describeAcousticVoiceFromEmbedding(seg.embedding)
                        val brief = desc?.let { "${it.pitchRegisterLabel.lowercase()}, ${it.timbreLabel}" }.orEmpty()

                        PreGeminiDiarizationAnchor(
                            globalStartMs = seg.startMs,
                            globalEndMs = seg.endMs,
                            clusterId = seg.clusterId.coerceAtLeast(0),
                            matchedProfileId = cMatch?.let { if (it.isSelfUser) "me" else it.profileId },
                            matchedLabel = label,
                            isSelfUser = cMatch?.isSelfUser == true,
                            matchSimilarity = cMatch?.similarity ?: 0f,
                            medianF0Hz = seg.medianF0Hz.takeIf { it in 75f..420f }
                                ?: clusterById[seg.clusterId]?.medianF0Hz ?: 0f,
                            acousticBrief = brief,
                            singleSpeakerPurity = seg.singleSpeakerPurity
                        )
                    }

                    for (a in rawAnchors) {
                        val prev = anchors.lastOrNull()
                        val sameIdentity = prev != null && (
                            (prev.matchedProfileId != null && prev.matchedProfileId == a.matchedProfileId) ||
                                (prev.matchedProfileId == null && a.matchedProfileId == null && prev.clusterId == a.clusterId)
                            )
                        if (prev != null && sameIdentity && (a.globalStartMs - prev.globalEndMs) <= 4_000L) {
                            anchors[anchors.lastIndex] = prev.copy(
                                globalEndMs = maxOf(prev.globalEndMs, a.globalEndMs),
                                matchSimilarity = maxOf(prev.matchSimilarity, a.matchSimilarity),
                                singleSpeakerPurity = (prev.singleSpeakerPurity + a.singleSpeakerPurity) * 0.5f
                            )
                        } else {
                            anchors.add(a)
                        }
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "Pre-Gemini on-device voice analysis skipped: ${e.message}")
            }
        }

        val clips = prefs.getAttachableVoiceClips(
            maxExtraSpeakers = maxExtraSpeakers,
            maxSamplesPerTopSpeaker = maxSamplesPerTopSpeaker,
            maxTotalBytes = maxTotalBytes,
            prioritizedSpeakerScores = preMatchScores
        )
        val refs = clips.map { clip ->
            ReferenceVoiceInput(
                speakerLabel = clip.entry.effectiveNickname(),
                roleHint = clip.entry.effectiveRelatableTitle(),
                file = clip.file,
                profileId = clip.entry.id,
                voiceSignature = clip.entry.voiceSignature.ifBlank { clip.entry.voiceHint },
                sampleIndex = clip.sampleIndex,
                sampleTotal = clip.sampleTotal,
                qualityScore = clip.qualityScore
            )
        }
        return PreGeminiVoiceAnalysisResult(
            attachableClips = clips,
            referenceVoices = refs,
            preMatchScores = preMatchScores,
            anchors = anchors,
            detectedClusterCount = detectedClusters,
            changePointCount = totalChangePoints
        )
    }

    fun selectAttachableVoiceClips(
        prefs: PreferencesRepo,
        maxExtraSpeakers: Int = 6,
        maxSamplesPerTopSpeaker: Int = 2,
        maxTotalBytes: Long = 3_200_000L,
        context: Context? = null,
        sessionAudioFile: File? = null
    ): Pair<List<AttachableVoiceClip>, List<ReferenceVoiceInput>> {
        val sources = if (sessionAudioFile != null) listOf(sessionAudioFile to 0L) else emptyList()
        val result = analyzeSessionAudioPreGemini(
            context = context,
            prefs = prefs,
            audioSourcesWithOffsets = sources,
            maxExtraSpeakers = maxExtraSpeakers,
            maxSamplesPerTopSpeaker = maxSamplesPerTopSpeaker,
            maxTotalBytes = maxTotalBytes
        )
        return result.attachableClips to result.referenceVoices
    }

    fun selectAttachableProfiles(
        prefs: PreferencesRepo,
        maxExtra: Int = 6,
        maxTotalBytes: Long = 3_200_000L
    ): List<ReferenceVoiceInput> {
        return selectAttachableVoiceClips(
            prefs = prefs,
            maxExtraSpeakers = maxExtra,
            maxSamplesPerTopSpeaker = 2,
            maxTotalBytes = maxTotalBytes
        ).second
    }

    fun buildFormattedSpeakerLabel(nickname: String, relatableTitle: String): String {
        val nick = nickname.trim()
        val title = relatableTitle.trim()
        return when {
            nick.isBlank() && title.isBlank() -> "Conversation Voice"
            nick.isBlank() -> title
            title.isBlank() -> nick
            nick.contains(title, ignoreCase = true) -> nick
            else -> "$nick ($title)"
        }
    }

    fun isGenericSpeakerPlaceholder(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return true
        val normalized = text.lowercase(Locale.US)
        if (normalized in setOf("unknown", "unknown speaker", "unidentified", "anonymous", "n/a", "none")) {
            return true
        }
        return Regex("^(?:speaker|spk|person|participant|voice|user|ผู้พูด|เสียงที่)[\\s_#-]*\\d*[a-z]?$")
            .matches(normalized)
    }

    private fun isSyntheticFallbackNickname(name: String): Boolean {
        val trimmed = name.trim()
        return SYNTH_ADJECTIVE_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }
    }

    /**
     * Returns true if [raw] is a generic placeholder, a synthetic adjective-role nickname
     * (e.g., "Warm Charge Nurse", "Deep-Tone Attending"), or a bare role/relationship noun
     * without a personal name (e.g., "Presenting Ward Resident", "Care Team Colleague").
     * Such labels must NEVER be matched across sessions by text alone without voiceprint confirmation,
     * preventing two different speakers with the same role from being combined into one profile.
     */
    fun isRoleOrSyntheticNickname(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (isGenericSpeakerPlaceholder(text)) return true
        if (isSyntheticFallbackNickname(text)) return true
        val norm = normalizeKey(text)
        if (norm in GENERIC_ROLE_ONLY_NAMES) return true
        val stripped = stripHonorificCore(text)
        if (stripped.isBlank() || stripped in GENERIC_ROLE_ONLY_NAMES) return true
        return false
    }

    private fun isRoleOnlyOrSyntheticName(raw: String?): Boolean = isRoleOrSyntheticNickname(raw)

    private fun normalizeKey(text: String): String {
        return text.trim()
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), " ")
    }

    private fun stripHonorificCore(text: String): String {
        var cleaned = normalizeKey(text)
        repeat(2) {
            cleaned = HONORIFIC_PREFIX_REGEX.replaceFirst(cleaned, "").trim()
        }
        return cleaned
    }

    private fun extractNicknameAndTitleFromLabel(rawLabel: String): Pair<String, String> {
        val trimmed = rawLabel.trim()
        val match = Regex("^(.+?)\\s*\\(([^)]+)\\)\\s*$").find(trimmed)
        return if (match != null) {
            match.groupValues[1].trim() to match.groupValues[2].trim()
        } else {
            trimmed to ""
        }
    }

    private fun synthesizeFallbackNicknameAndTitle(
        index: Int,
        speakerId: String,
        isUser: Boolean,
        userDisplayName: String,
        sessionContext: String,
        rawRole: String?,
        rawNickname: String?,
        rawTitle: String?,
        rawLabel: String?,
        acousticDescriptor: AcousticVoiceDescriptor? = null
    ): Pair<String, String> {
        val (labelNick, labelTitle) = extractNicknameAndTitleFromLabel(rawLabel.orEmpty())
        if (isUser) {
            val nick = userDisplayName.ifBlank { "Dr. (Me)" }
            val title = rawTitle?.trim()?.takeIf { it.isNotBlank() && !isGenericSpeakerPlaceholder(it) }
                ?: rawRole?.trim()?.takeIf { it.isNotBlank() && !isGenericSpeakerPlaceholder(it) }
                ?: "Self / Device Owner"
            return nick to title
        }

        val candidateTitle = listOf(rawTitle, rawRole, labelTitle)
            .firstOrNull { !it.isNullOrBlank() && !isGenericSpeakerPlaceholder(it) }
            ?.trim()
            ?: when {
                sessionContext in setOf("NEURO_ROUND", "MEDICAL_ROUND", "CASE_CONFERENCE", "HANDOVER") ->
                    if (index == 0) "Clinical Team Presenter" else "Care Team Colleague"
                sessionContext == "ONLINE_MEETING" -> "Meeting Participant"
                sessionContext == "PHONE_CALL" -> "Call Partner"
                else -> "Conversation Partner"
            }

        val rawCandidateNick = listOf(rawNickname, labelNick, rawLabel)
            .firstOrNull { !it.isNullOrBlank() && !isGenericSpeakerPlaceholder(it) }
            ?.trim()

        val candidateNick = if (rawCandidateNick != null) {
            // If synthetic nickname contradicts measured pitch register, fix its acoustic prefix
            if (acousticDescriptor != null &&
                isSyntheticFallbackNickname(rawCandidateNick) &&
                acousticDescriptor.contradictsNickname(rawCandidateNick)
            ) {
                val roleNoun = rawCandidateNick.split(Regex("\\s+")).drop(1).joinToString(" ").ifBlank { "Speaker" }
                "${acousticDescriptor.recommendedAdjectivePrefix.trim()} $roleNoun"
            } else {
                rawCandidateNick
            }
        } else {
            val adj = acousticDescriptor?.recommendedAdjectivePrefix?.trim()
                ?: SYNTH_ADJECTIVE_PREFIXES[index % 6].trim()
            val roleNoun = candidateTitle.split(Regex("\\s+"))
                .takeLast(2)
                .joinToString(" ")
                .ifBlank { "Voice" }
            "$adj $roleNoun"
        }

        return candidateNick to candidateTitle
    }

    /**
     * Reconciles Gemini-returned `speakers[]` with existing roster profiles using a hybrid algorithm:
     *  1. Backfills missing on-device 48-D embeddings for existing profiles.
     *  2. Evaluates up to 10 solo candidate audio windows per speaker, retaining up to 4 verified
     *     intra-speaker consistent solo clips and extracting their 48-D voiceprints + exact source
     *     recording wall-clock timestamps BEFORE final profile linking.
     *  3. Applies a strict biometric & acoustic contradiction guard on ALL profile matches so
     *     different speakers are NEVER combined into the same profile.
     *  4. Never matches across sessions by generic role or synthetic descriptive nickname alone.
     *  5. Falls back to on-device 48-D voiceprint cross-matching against My Voice and the Speaker
     *     Roster whenever Gemini omits `matched_profile_id` or uses a generic name.
     *  6. Prevents two distinct speakers in the same session from claiming the same profile ID.
     *  7. Persists up to 10 highest-quality ADTS voice samples per profile with true recording timestamps.
     */
    fun reconcileAndEnrollSpeakers(
        context: Context,
        prefs: PreferencesRepo,
        sessionId: String,
        sessionContext: String,
        sliceSources: List<SliceSourceSegment>,
        rawSpeakers: List<GeminiSpeakerDto>,
        rawTranscript: List<GeminiSentenceDto>,
        rawItems: List<GeminiExtractedItemDto>,
        allowSlicing: Boolean,
        allowCreateNewProfiles: Boolean = true,
        ignoreAutoRecognizeToggle: Boolean = false,
        sessionRecordedAtMs: Long = 0L
    ): ReconciledSpeakersResult {
        val userNick = prefs.userDisplayName.ifBlank { "Dr. (Me)" }
        val shouldAutoEnroll = ignoreAutoRecognizeToggle || prefs.autoRecognizeVoiceProfiles

        // Ensure every speaker seen in transcript or items has a candidate DTO
        val dtoById = LinkedHashMap<String, GeminiSpeakerDto>()
        rawSpeakers.forEach { dto ->
            val cleanId = dto.speakerId.trim().ifBlank { "SPK_${dtoById.size + 1}" }
            if (!dtoById.containsKey(cleanId)) {
                dtoById[cleanId] = dto.copy(speakerId = cleanId)
            }
        }
        rawTranscript.forEach { sent ->
            val cleanId = sent.speakerId.trim().ifBlank { "SPK_1" }
            if (!dtoById.containsKey(cleanId)) {
                dtoById[cleanId] = GeminiSpeakerDto(
                    speakerId = cleanId,
                    speakerLabel = sent.speakerLabel,
                    isUser = sent.speakerLabel.equals(userNick, ignoreCase = true) ||
                        sent.speakerLabel.contains("(Me)", ignoreCase = true)
                )
            }
        }
        rawItems.forEach { item ->
            val cleanId = item.speakerId.trim().ifBlank { "SPK_1" }
            if (!dtoById.containsKey(cleanId)) {
                dtoById[cleanId] = GeminiSpeakerDto(
                    speakerId = cleanId,
                    speakerLabel = item.speakerLabel,
                    isUser = item.speakerLabel.equals(userNick, ignoreCase = true) ||
                        item.speakerLabel.contains("(Me)", ignoreCase = true)
                )
            }
        }

        if (dtoById.isEmpty()) {
            return ReconciledSpeakersResult(emptyList(), emptyMap(), 0, 0, 0)
        }

        // Lazily backfill stored sample embeddings so on-device voiceprint matching & guards are active
        if (allowSlicing && sliceSources.isNotEmpty()) {
            ensureSampleEmbeddingsBackfilled(context, prefs, maxToCompute = 16)
        }

        val totalAudioDurationMs = sliceSources.sumOf { it.durationMs }
        val effectiveRecordedAtMs = sessionRecordedAtMs.takeIf { it > 0L }
            ?: sliceSources.firstOrNull { it.sourceStartMs > 0L }?.sourceStartMs
            ?: System.currentTimeMillis()
        var autoEnrolledCount = 0
        var matchedExistingCount = 0
        var samplesSavedOrUpgradedCount = 0
        val reconciledEntities = mutableListOf<SessionSpeakerEntity>()
        val labelMap = LinkedHashMap<String, String>()
        val claimedProfileIds = HashSet<String>()

        synchronized(rosterLock) {
            val roster = prefs.getSpeakerRoster().toMutableList()
            val myVoiceSamplesSnapshot = prefs.getMyVoiceSamples()
            val myVoiceEmbeddings = myVoiceSamplesSnapshot.map { it.embedding }.filter { it.isNotEmpty() }

            dtoById.values.forEachIndexed { idx, dto ->
                val evaluatedClips = if (allowSlicing && shouldAutoEnroll && sliceSources.isNotEmpty()) {
                    evaluateTopCandidateClips(
                        context = context,
                        sessionId = sessionId,
                        speakerTag = dto.speakerId,
                        dto = dto,
                        speakerCount = dtoById.size,
                        totalAudioDurationMs = totalAudioDurationMs,
                        sliceSources = sliceSources,
                        rawTranscript = rawTranscript,
                        rawItems = rawItems,
                        minQualityScore = MIN_AUTO_SAMPLE_QUALITY_SCORE,
                        fallbackRecordedAtMs = effectiveRecordedAtMs,
                        maxClipsToReturn = MAX_COMMITTED_SAMPLES_PER_SESSION_SPEAKER
                    )
                } else {
                    emptyList()
                }
                val primaryClip = evaluatedClips.firstOrNull()
                val primaryAcousticDesc = primaryClip?.embedding?.takeIf { it.isNotEmpty() }
                    ?.let { SpeakerEmbeddingEngine.describeAcousticVoiceFromEmbedding(it) }

                try {
                    val isLowConfidenceMatch = dto.matchConfidence.equals("LOW", ignoreCase = true)
                    val explicitSelfName = dto.speakerLabel.equals(userNick, ignoreCase = true) ||
                        dto.nickname.equals(userNick, ignoreCase = true)

                    var isSelfUser = "me" !in claimedProfileIds && (
                        (!isLowConfidenceMatch && (dto.isUser || dto.matchedProfileId == "me")) ||
                            explicitSelfName
                        )

                    // Strict biometric contradiction guard for self-user claims whenever voiceprints exist
                    if (isSelfUser && primaryClip != null && primaryClip.embedding.isNotEmpty() && myVoiceEmbeddings.isNotEmpty()) {
                        val (isConsistentWithMe, selfSim) = SpeakerEmbeddingEngine.isConsistentWithProfile(
                            candidateEmbedding = primaryClip.embedding,
                            existingEmbeddings = myVoiceEmbeddings,
                            threshold = SpeakerEmbeddingEngine.VOICEPRINT_CONTRADICTION_THRESHOLD
                        )
                        if (!isConsistentWithMe) {
                            Log.w(
                                TAG,
                                "Rejected contradicted 'me' match for ${dto.speakerId}: " +
                                    "voiceprint sim=${(selfSim * 100).toInt()}% < ${(SpeakerEmbeddingEngine.VOICEPRINT_CONTRADICTION_THRESHOLD * 100).toInt()}%"
                            )
                            isSelfUser = false
                        }
                    }

                    val (synthNick, synthTitle) = synthesizeFallbackNicknameAndTitle(
                        index = idx,
                        speakerId = dto.speakerId,
                        isUser = isSelfUser,
                        userDisplayName = userNick,
                        sessionContext = sessionContext,
                        rawRole = dto.inferredRole,
                        rawNickname = dto.nickname,
                        rawTitle = dto.relatableTitle,
                        rawLabel = dto.speakerLabel,
                        acousticDescriptor = primaryAcousticDesc
                    )
                    val cleanVoiceSig = dto.voiceSignature.trim().ifBlank {
                        primaryAcousticDesc?.buildAcousticSignatureSummary(
                            snrDb = primaryClip?.metrics?.snrDb ?: 0f,
                            speechRatio = primaryClip?.metrics?.speechRatio ?: 0f
                        ).orEmpty()
                    }

                    // Helper to verify that a candidate roster index does NOT acoustically contradict primaryClip
                    fun isRosterEntryBiometricallyCompatible(rosterIdx: Int): Boolean {
                        if (rosterIdx < 0 || primaryClip == null || primaryClip.embedding.isEmpty()) return true
                        val existingEmbs = roster[rosterIdx].existingSamples()
                            .map { it.embedding }
                            .filter { it.isNotEmpty() }
                        if (existingEmbs.isEmpty()) return true
                        val (consistent, sim) = SpeakerEmbeddingEngine.isConsistentWithProfile(
                            candidateEmbedding = primaryClip.embedding,
                            existingEmbeddings = existingEmbs,
                            threshold = SpeakerEmbeddingEngine.VOICEPRINT_CONTRADICTION_THRESHOLD
                        )
                        if (!consistent) {
                            Log.w(
                                TAG,
                                "Prevented combining different speaker '${dto.speakerId}' into '${roster[rosterIdx].effectiveNickname()}' " +
                                    "(${roster[rosterIdx].id}): voiceprint sim=${(sim * 100).toInt()}% < ${(SpeakerEmbeddingEngine.VOICEPRINT_CONTRADICTION_THRESHOLD * 100).toInt()}%"
                            )
                        }
                        return consistent
                    }

                    // Resolve roster match (if not already marked self-user)
                    var matchedIdx = -1
                    if (!isSelfUser) {
                        // 1. Confidence-gated match by Gemini matched_profile_id (ALWAYS verified against voiceprint)
                        if (!isLowConfidenceMatch) {
                            val pid = dto.matchedProfileId?.takeIf { it.isNotBlank() && it != "me" && it !in claimedProfileIds }
                            if (pid != null) {
                                val candIdx = roster.indexOfFirst { it.id == pid }
                                if (candIdx >= 0 && isRosterEntryBiometricallyCompatible(candIdx)) {
                                    matchedIdx = candIdx
                                }
                            }
                        }

                        // 2. Exact match against specific personal/proper names & aliases (NEVER generic role or synthetic names!)
                        val normNick = normalizeKey(synthNick)
                        val rawExtractedNick = extractNicknameAndTitleFromLabel(dto.speakerLabel).first
                        val normRawLabel = normalizeKey(rawExtractedNick)
                        val isSpecificNick = !isRoleOrSyntheticNickname(synthNick)
                        val isSpecificRawLabel = normRawLabel.isNotBlank() && !isRoleOrSyntheticNickname(rawExtractedNick)

                        if (matchedIdx < 0 && (isSpecificNick || isSpecificRawLabel)) {
                            val candIdx = roster.indexOfFirst { entry ->
                                if (entry.id in claimedProfileIds) return@indexOfFirst false
                                val knownNorm = entry.allKnownNames()
                                    .filter { !isRoleOrSyntheticNickname(it) }
                                    .map { normalizeKey(it) }
                                (isSpecificNick && normNick in knownNorm) ||
                                    (isSpecificRawLabel && normRawLabel in knownNorm)
                            }
                            if (candIdx >= 0 && isRosterEntryBiometricallyCompatible(candIdx)) {
                                matchedIdx = candIdx
                            }
                        }

                        // 3. Unique stripped-honorific core-name match for specific personal names only
                        if (matchedIdx < 0 && (isSpecificNick || isSpecificRawLabel)) {
                            val coreCandidates = buildList {
                                if (isSpecificNick) add(stripHonorificCore(synthNick))
                                if (isSpecificRawLabel) add(stripHonorificCore(rawExtractedNick))
                            }.filter { it.length >= 3 && !isRoleOrSyntheticNickname(it) }
                                .distinct()
                            if (coreCandidates.isNotEmpty()) {
                                val coreMatches = roster.indices.filter { rIdx ->
                                    if (roster[rIdx].id in claimedProfileIds) return@filter false
                                    val entryCores = roster[rIdx].allKnownNames()
                                        .filter { !isRoleOrSyntheticNickname(it) }
                                        .map { stripHonorificCore(it) }
                                        .filter { it.length >= 3 && !isRoleOrSyntheticNickname(it) }
                                    coreCandidates.any { it in entryCores }
                                }
                                if (coreMatches.size == 1 && isRosterEntryBiometricallyCompatible(coreMatches.first())) {
                                    matchedIdx = coreMatches.first()
                                }
                            }
                        }

                        // 4. Hybrid On-Device 48-D Voiceprint Cross-Match when Gemini/name did not match
                        if (matchedIdx < 0 &&
                            primaryClip != null &&
                            primaryClip.embedding.isNotEmpty() &&
                            primaryClip.metrics.score >= MIN_VOICEPRINT_MATCH_QUALITY_SCORE
                        ) {
                            val vpMatch = SpeakerEmbeddingEngine.findBestVoiceprintMatch(
                                candidateEmbedding = primaryClip.embedding,
                                myVoiceSamples = prefs.getMyVoiceSamples(),
                                roster = roster,
                                excludedProfileIds = claimedProfileIds
                            )
                            if (vpMatch != null) {
                                if (vpMatch.isSelfUser) {
                                    isSelfUser = true
                                    Log.i(
                                        TAG,
                                        "On-device voiceprint recognized '${dto.speakerId}' as My Voice " +
                                            "(sim=${(vpMatch.similarity * 100).toInt()}%, margin=+${(vpMatch.marginOverSecond * 100).toInt()}%)"
                                    )
                                } else {
                                    val vpIdx = roster.indexOfFirst { it.id == vpMatch.profileId }
                                    if (vpIdx >= 0) {
                                        matchedIdx = vpIdx
                                        Log.i(
                                            TAG,
                                            "On-device voiceprint matched '${dto.speakerId}' -> '${roster[vpIdx].effectiveNickname()}' (${vpMatch.profileId}, " +
                                                "sim=${(vpMatch.similarity * 100).toInt()}%, margin=+${(vpMatch.marginOverSecond * 100).toInt()}%)"
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isSelfUser) {
                        claimedProfileIds.add("me")
                        matchedExistingCount++
                        val selfTitle = dto.relatableTitle.trim()
                            .takeIf { it.isNotBlank() && !isGenericSpeakerPlaceholder(it) }
                            ?: dto.inferredRole?.trim()?.takeIf { it.isNotBlank() && !isGenericSpeakerPlaceholder(it) }
                            ?: "Self / Device Owner"

                        for (candClip in evaluatedClips) {
                            if (candClip.metrics.score < MIN_SELF_AUTO_SAMPLE_QUALITY_SCORE) continue
                            val existingMyEmbs = prefs.getMyVoiceSamples()
                                .map { it.embedding }
                                .filter { it.isNotEmpty() }
                            val (isConsistent, sim) = SpeakerEmbeddingEngine.isConsistentWithProfile(
                                candidateEmbedding = candClip.embedding,
                                existingEmbeddings = existingMyEmbs
                            )
                            if (!isConsistent) {
                                Log.w(
                                    TAG,
                                    "Rejected contaminated My Voice auto-sample (voiceprint sim=${(sim * 100).toInt()}% < " +
                                        "${(SpeakerEmbeddingEngine.CONSISTENCY_THRESHOLD * 100).toInt()}%)"
                                )
                            } else {
                                val committed = commitEvaluatedCandidateToProfile(
                                    prefs = prefs,
                                    candidate = candClip,
                                    profileId = "me",
                                    isSelfUser = true,
                                    sessionId = sessionId,
                                    isManual = false
                                )
                                if (committed != null) {
                                    val (accepted, list) = prefs.addMyVoiceSample(committed)
                                    if (accepted) samplesSavedOrUpgradedCount++
                                    Log.i(
                                        TAG,
                                        "My Voice auto-sample evaluated: Q${committed.qualityScore.toInt()} " +
                                            "(${committed.durationMs}ms, sim=${(sim * 100).toInt()}%), accepted=$accepted, " +
                                            "total=${list.size}/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE}"
                                    )
                                }
                            }
                        }

                        val formatted = buildFormattedSpeakerLabel(userNick, selfTitle)
                        labelMap[dto.speakerId] = formatted
                        reconciledEntities.add(
                            SessionSpeakerEntity(
                                sessionId = sessionId,
                                speakerId = dto.speakerId,
                                speakerLabel = formatted,
                                inferredRole = selfTitle,
                                isUser = true,
                                matchedProfileId = "me",
                                nickname = userNick,
                                relatableTitle = selfTitle,
                                voiceSignature = cleanVoiceSig.ifBlank { null },
                                isAutoEnrolled = false
                            )
                        )
                        return@forEachIndexed
                    }

                    val profileId: String
                    val finalNickname: String
                    val finalTitle: String
                    val finalSignature: String
                    var wasAutoEnrolled = false

                    if (matchedIdx >= 0) {
                        val existing = roster[matchedIdx]
                        profileId = existing.id
                        claimedProfileIds.add(profileId)
                        matchedExistingCount++

                        val preserveExistingName = !existing.isAutoDiscovered || existing.isPinned
                        val existingNick = existing.effectiveNickname()
                        finalNickname = when {
                            preserveExistingName && !isGenericSpeakerPlaceholder(existingNick) -> existingNick
                            isRoleOrSyntheticNickname(existingNick) &&
                                !isRoleOrSyntheticNickname(synthNick) -> synthNick
                            !isGenericSpeakerPlaceholder(existingNick) -> existingNick
                            else -> synthNick
                        }
                        finalTitle = when {
                            preserveExistingName && existing.effectiveRelatableTitle().isNotBlank() -> existing.effectiveRelatableTitle()
                            synthTitle.isNotBlank() && (
                                existing.effectiveRelatableTitle().isBlank() ||
                                    existing.effectiveRelatableTitle().equals("Conversation Partner", ignoreCase = true)
                                ) -> synthTitle
                            existing.effectiveRelatableTitle().isNotBlank() -> existing.effectiveRelatableTitle()
                            else -> synthTitle
                        }
                        finalSignature = when {
                            existing.voiceSignature.isNotBlank() && cleanVoiceSig.isBlank() -> existing.voiceSignature
                            cleanVoiceSig.length > existing.voiceSignature.length -> cleanVoiceSig
                            else -> existing.voiceSignature.ifBlank { existing.voiceHint }.ifBlank { cleanVoiceSig }
                        }
                        val newCount = if (existing.sourceSessionId != sessionId) {
                            existing.sessionsHeardCount + 1
                        } else {
                            existing.sessionsHeardCount
                        }

                        val updatedAliases = buildList {
                            addAll(existing.aliases)
                            if (!isRoleOrSyntheticNickname(existingNick) &&
                                !existingNick.equals(finalNickname, ignoreCase = true)
                            ) {
                                add(existingNick)
                            }
                            if (!isRoleOrSyntheticNickname(synthNick) &&
                                !synthNick.equals(finalNickname, ignoreCase = true)
                            ) {
                                add(synthNick)
                            }
                        }.map { it.trim() }
                            .filter { it.isNotBlank() && !it.equals(finalNickname, ignoreCase = true) }
                            .distinctBy { it.lowercase(Locale.US) }
                            .take(6)

                        var currentSamples = existing.existingSamples()
                        for (candClip in evaluatedClips) {
                            val existingEmbs = currentSamples
                                .map { it.embedding }
                                .filter { it.isNotEmpty() }
                            val (isConsistent, sim) = SpeakerEmbeddingEngine.isConsistentWithProfile(
                                candidateEmbedding = candClip.embedding,
                                existingEmbeddings = existingEmbs
                            )
                            if (!isConsistent) {
                                Log.w(
                                    TAG,
                                    "Rejected contaminated auto-sample for '$finalNickname' ($profileId): " +
                                        "voiceprint sim=${(sim * 100).toInt()}% < ${(SpeakerEmbeddingEngine.CONSISTENCY_THRESHOLD * 100).toInt()}%"
                                )
                            } else {
                                val committed = commitEvaluatedCandidateToProfile(
                                    prefs = prefs,
                                    candidate = candClip,
                                    profileId = profileId,
                                    isSelfUser = false,
                                    sessionId = sessionId,
                                    isManual = false
                                )
                                if (committed != null) {
                                    val (mergedSamples, accepted) = prefs.mergeAndTrimSamples(
                                        existing = currentSamples,
                                        candidate = committed,
                                        maxSamples = PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE
                                    )
                                    currentSamples = mergedSamples
                                    if (accepted) samplesSavedOrUpgradedCount++
                                    Log.i(
                                        TAG,
                                        "Profile '$finalNickname' ($profileId) sample evaluated: " +
                                            "Q${committed.qualityScore.toInt()} (${committed.durationMs}ms, sim=${(sim * 100).toInt()}%), " +
                                            "accepted=$accepted, kept=${currentSamples.size}/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE}"
                                    )
                                }
                            }
                        }

                        val bestSample = currentSamples.firstOrNull()
                        val updated = existing.copy(
                            displayName = finalNickname,
                            nickname = finalNickname,
                            role = finalTitle,
                            relatableTitle = finalTitle,
                            voiceHint = finalSignature,
                            voiceSignature = finalSignature,
                            samples = currentSamples,
                            audioPath = bestSample?.filePath ?: existing.audioPath,
                            sampleDurationMs = bestSample?.durationMs ?: existing.sampleDurationMs,
                            sessionsHeardCount = newCount,
                            lastHeardAtMs = maxOf(existing.lastHeardAtMs, effectiveRecordedAtMs),
                            sourceSessionId = sessionId,
                            aliases = updatedAliases
                        )
                        roster[matchedIdx] = updated
                        wasAutoEnrolled = existing.isAutoDiscovered
                    } else {
                        profileId = UUID.randomUUID().toString().take(8)
                        // Ensure distinct speakers never collide on the exact same nickname in the roster
                        val existingNicksLower = roster.map { it.effectiveNickname().lowercase(Locale.US) }.toHashSet()
                        var candidateNewNick = synthNick
                        if (candidateNewNick.lowercase(Locale.US) in existingNicksLower) {
                            val adj = primaryAcousticDesc?.recommendedAdjectivePrefix?.trim()
                                ?: SYNTH_ADJECTIVE_PREFIXES[(roster.size + idx) % SYNTH_ADJECTIVE_PREFIXES.size].trim()
                            val withAdj = if (candidateNewNick.startsWith(adj, ignoreCase = true)) {
                                "$candidateNewNick #${roster.size + 1}"
                            } else {
                                "$adj $candidateNewNick"
                            }
                            candidateNewNick = if (withAdj.lowercase(Locale.US) in existingNicksLower) {
                                "$candidateNewNick (${profileId.take(4)})"
                            } else {
                                withAdj
                            }
                        }
                        finalNickname = candidateNewNick
                        finalTitle = synthTitle
                        finalSignature = cleanVoiceSig

                        if (allowCreateNewProfiles && shouldAutoEnroll) {
                            var initialSamples = emptyList<VoiceProfileSample>()
                            for (candClip in evaluatedClips) {
                                val existingEmbs = initialSamples.map { it.embedding }.filter { it.isNotEmpty() }
                                val (isConsistent, _) = SpeakerEmbeddingEngine.isConsistentWithProfile(
                                    candidateEmbedding = candClip.embedding,
                                    existingEmbeddings = existingEmbs,
                                    threshold = SpeakerEmbeddingEngine.INTRA_SESSION_CONSISTENCY_THRESHOLD
                                )
                                if (!isConsistent) continue
                                val committed = commitEvaluatedCandidateToProfile(
                                    prefs = prefs,
                                    candidate = candClip,
                                    profileId = profileId,
                                    isSelfUser = false,
                                    sessionId = sessionId,
                                    isManual = false
                                )
                                if (committed != null) {
                                    val (merged, accepted) = prefs.mergeAndTrimSamples(
                                        existing = initialSamples,
                                        candidate = committed,
                                        maxSamples = PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE
                                    )
                                    initialSamples = merged
                                    if (accepted) samplesSavedOrUpgradedCount++
                                }
                            }
                            val best = initialSamples.firstOrNull()
                            roster.add(
                                SpeakerProfileEntry(
                                    id = profileId,
                                    displayName = finalNickname,
                                    role = finalTitle,
                                    voiceHint = finalSignature,
                                    audioPath = best?.filePath,
                                    isUser = false,
                                    nickname = finalNickname,
                                    relatableTitle = finalTitle,
                                    voiceSignature = finalSignature,
                                    isAutoDiscovered = true,
                                    isPinned = false,
                                    sessionsHeardCount = 1,
                                    lastHeardAtMs = effectiveRecordedAtMs,
                                    sampleDurationMs = best?.durationMs ?: 0L,
                                    sourceSessionId = sessionId,
                                    samples = initialSamples
                                )
                            )
                            claimedProfileIds.add(profileId)
                            wasAutoEnrolled = true
                            autoEnrolledCount++
                        }
                    }

                    val formatted = buildFormattedSpeakerLabel(finalNickname, finalTitle)
                    labelMap[dto.speakerId] = formatted
                    reconciledEntities.add(
                        SessionSpeakerEntity(
                            sessionId = sessionId,
                            speakerId = dto.speakerId,
                            speakerLabel = formatted,
                            inferredRole = finalTitle,
                            isUser = false,
                            matchedProfileId = if (wasAutoEnrolled || matchedIdx >= 0) profileId else null,
                            nickname = finalNickname,
                            relatableTitle = finalTitle,
                            voiceSignature = finalSignature.ifBlank { null },
                            isAutoEnrolled = wasAutoEnrolled
                        )
                    )
                } finally {
                    evaluatedClips.forEach { it.cleanup() }
                }
            }

            if (shouldAutoEnroll) {
                pruneExcessAutoProfiles(prefs, roster)
                prefs.saveSpeakerRoster(roster)
            } else if (reconciledEntities.any { it.matchedProfileId != null && it.matchedProfileId != "me" }) {
                prefs.saveSpeakerRoster(roster)
            }
        }

        return ReconciledSpeakersResult(
            speakers = reconciledEntities,
            speakerLabelById = labelMap,
            autoEnrolledCount = autoEnrolledCount,
            matchedExistingCount = matchedExistingCount,
            samplesSavedOrUpgradedCount = samplesSavedOrUpgradedCount
        )
    }

    /**
     * Step 1 of voice sample extraction: evaluates up to [MAX_CANDIDATE_WINDOWS_PER_SPEAKER]
     * solo windows, retains up to [maxClipsToReturn] highest-scoring non-overlapping temporary
     * ADTS clips that are biometrically consistent with the speaker's #1 best clip, extracts
     * their 48-D voiceprints, and resolves their exact wall-clock recording timestamps.
     * Caller MUST invoke [EvaluatedCandidateClip.cleanup] on all returned items in a `finally` block.
     */
    private fun evaluateTopCandidateClips(
        context: Context,
        sessionId: String,
        speakerTag: String,
        dto: GeminiSpeakerDto,
        speakerCount: Int,
        totalAudioDurationMs: Long,
        sliceSources: List<SliceSourceSegment>,
        rawTranscript: List<GeminiSentenceDto>,
        rawItems: List<GeminiExtractedItemDto>,
        minQualityScore: Float,
        fallbackRecordedAtMs: Long,
        maxClipsToReturn: Int = MAX_COMMITTED_SAMPLES_PER_SESSION_SPEAKER
    ): List<EvaluatedCandidateClip> {
        if (sliceSources.isEmpty() || totalAudioDurationMs <= 2_500L) return emptyList()

        val candidates = collectCandidateWindows(
            dto = dto,
            speakerCount = speakerCount,
            totalAudioDurationMs = totalAudioDurationMs,
            rawTranscript = rawTranscript,
            rawItems = rawItems
        )
        if (candidates.isEmpty()) return emptyList()

        val safeTag = speakerTag.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(16)
        val evalDir = File(
            context.cacheDir,
            "voice_sample_eval/${sessionId}_${safeTag}_${UUID.randomUUID().toString().take(6)}"
        ).apply { mkdirs() }

        return try {
            data class RawSlicedCandidate(
                val file: File,
                val durationMs: Long,
                val metrics: ClipSlicer.VoiceSampleQualityMetrics,
                val effectiveScore: Float,
                val recordedAtMs: Long
            )

            val usableSlices = mutableListOf<RawSlicedCandidate>()
            candidates.forEachIndexed { cIdx, win ->
                val seg = sliceSources.lastOrNull { it.globalOffsetMs <= win.startMs } ?: sliceSources.first()
                if (!seg.file.exists() || seg.file.length() < 4096L) return@forEachIndexed

                val localStartMs = (win.startMs - seg.globalOffsetMs)
                    .coerceIn(0L, (seg.durationMs - 2_000L).coerceAtLeast(0L))
                val localEndMs = (win.endMs - seg.globalOffsetMs)
                    .coerceAtMost(seg.durationMs)
                    .coerceAtLeast(localStartMs + 2_500L)

                val tmpFile = File(evalDir, "cand_${cIdx}.aac")
                val sliced = ClipSlicer.sliceAdtsVoiceSample(
                    rawAdtsFile = seg.file,
                    outFile = tmpFile,
                    startMs = localStartMs,
                    endMs = localEndMs
                ) ?: return@forEachIndexed

                val metrics = ClipSlicer.analyzeAdtsSampleQuality(sliced.file)
                val purityBonus = ((metrics.singleSpeakerPurity - 0.72f) * 14f).coerceIn(-12f, 5f)
                val effectiveScore = metrics.score + win.priorityBonus + purityBonus
                if (metrics.isUsable && !metrics.hasSuspectedCrosstalk && metrics.score >= minQualityScore) {
                    val recMs = resolveSegmentWallClockMs(seg, localStartMs, fallbackRecordedAtMs)
                    usableSlices.add(
                        RawSlicedCandidate(
                            file = sliced.file,
                            durationMs = sliced.durationMs,
                            metrics = metrics,
                            effectiveScore = effectiveScore,
                            recordedAtMs = recMs
                        )
                    )
                } else {
                    runCatching { tmpFile.delete() }
                }
            }

            if (usableSlices.isEmpty()) {
                runCatching { evalDir.deleteRecursively() }
                return emptyList()
            }

            val sortedSlices = usableSlices.sortedByDescending { it.effectiveScore }
            val primaryRaw = sortedSlices.first()
            val primaryEmb = SpeakerEmbeddingEngine.extractEmbeddingFromFile(context, primaryRaw.file)
            val primaryClip = EvaluatedCandidateClip(
                tempFile = primaryRaw.file,
                evalDir = evalDir,
                durationMs = primaryRaw.durationMs,
                metrics = primaryRaw.metrics,
                embedding = primaryEmb,
                recordedAtMs = primaryRaw.recordedAtMs
            )

            val selected = mutableListOf(primaryClip)
            for (extra in sortedSlices.drop(1)) {
                if (selected.size >= maxClipsToReturn) {
                    runCatching { extra.file.delete() }
                    continue
                }
                // Ensure distinct timestamp separation (>= 2.5s) from already selected clips
                val overlapsSelected = selected.any { abs(it.recordedAtMs - extra.recordedAtMs) < 2_500L }
                if (overlapsSelected) {
                    runCatching { extra.file.delete() }
                    continue
                }
                val extraEmb = SpeakerEmbeddingEngine.extractEmbeddingFromFile(context, extra.file)
                // Require strict intra-session speaker consistency so another speaker's voice is never included
                val isSameSpeakerInSession = if (primaryEmb.isNotEmpty() && extraEmb.isNotEmpty()) {
                    SpeakerEmbeddingEngine.areEmbeddingsBiometricallyCompatible(extraEmb, primaryEmb) &&
                        SpeakerEmbeddingEngine.biometricSimilarity(extraEmb, primaryEmb) >=
                        SpeakerEmbeddingEngine.INTRA_SESSION_CONSISTENCY_THRESHOLD
                } else {
                    primaryEmb.isEmpty() && extraEmb.isNotEmpty()
                }
                if (isSameSpeakerInSession) {
                    selected.add(
                        EvaluatedCandidateClip(
                            tempFile = extra.file,
                            evalDir = evalDir,
                            durationMs = extra.durationMs,
                            metrics = extra.metrics,
                            embedding = extraEmb,
                            recordedAtMs = extra.recordedAtMs
                        )
                    )
                } else {
                    runCatching { extra.file.delete() }
                }
            }
            selected
        } catch (e: Exception) {
            Log.w(TAG, "Candidate voice sample evaluation failed for $speakerTag: ${e.message}")
            runCatching { evalDir.deleteRecursively() }
            emptyList()
        }
    }

    private fun resolveSegmentWallClockMs(
        seg: SliceSourceSegment,
        localStartMs: Long,
        fallbackRecordedAtMs: Long
    ): Long {
        if (seg.sourceStartMs > 0L) {
            return ClipSlicer.resolveCompactedTimestamp(
                rawInputMs = localStartMs,
                segments = seg.segments,
                sessionStartMs = seg.sourceStartMs,
                maxFileDurationMs = seg.durationMs.coerceAtLeast(localStartMs)
            ).wallClockMs
        }
        val mtime = seg.file.lastModified()
        if (mtime > 0L) {
            val estimatedFileStart = (mtime - seg.durationMs).coerceAtLeast(0L)
            return estimatedFileStart + localStartMs
        }
        return fallbackRecordedAtMs
    }

    /**
     * Step 2 of voice sample extraction: atomically persists an [EvaluatedCandidateClip] into
     * `filesDir/profiles/`, stamping both OS file `lastModified` and [VoiceProfileSample.createdAtMs]
     * with the true source recording timestamp.
     */
    private fun commitEvaluatedCandidateToProfile(
        prefs: PreferencesRepo,
        candidate: EvaluatedCandidateClip,
        profileId: String,
        isSelfUser: Boolean,
        sessionId: String?,
        isManual: Boolean = false
    ): VoiceProfileSample? {
        if (!candidate.tempFile.exists() || candidate.tempFile.length() < 1024L) return null
        val sampleId = UUID.randomUUID().toString().take(8)
        val destFile = if (isSelfUser) {
            prefs.getMyVoiceSampleAudioFile(sampleId)
        } else {
            prefs.getSpeakerSampleAudioFile(profileId, sampleId)
        }
        destFile.parentFile?.mkdirs()
        if (destFile.exists()) destFile.delete()
        val moved = candidate.tempFile.renameTo(destFile) || runCatching {
            candidate.tempFile.copyTo(destFile, overwrite = true)
            true
        }.getOrDefault(false)

        if (!moved || !destFile.exists() || destFile.length() < 1024L) {
            return null
        }

        val recordedMs = candidate.recordedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        runCatching { destFile.setLastModified(recordedMs) }

        return VoiceProfileSample(
            id = sampleId,
            filePath = destFile.absolutePath,
            durationMs = candidate.durationMs,
            qualityScore = candidate.metrics.score,
            snrDb = candidate.metrics.snrDb,
            speechRatio = candidate.metrics.speechRatio,
            createdAtMs = recordedMs,
            sourceSessionId = sessionId,
            isManual = isManual,
            embedding = candidate.embedding
        )
    }

    private fun collectCandidateWindows(
        dto: GeminiSpeakerDto,
        speakerCount: Int,
        totalAudioDurationMs: Long,
        rawTranscript: List<GeminiSentenceDto>,
        rawItems: List<GeminiExtractedItemDto>
    ): List<CandidateWindow> {
        val rawCandidates = mutableListOf<CandidateWindow>()

        fun addWindow(startMs: Long, endMs: Long, bonus: Float) {
            if (startMs < 0L || startMs >= totalAudioDurationMs) return
            val clampedEnd = endMs.coerceIn(startMs + 3_000L, minOf(startMs + 11_000L, totalAudioDurationMs))
            if (clampedEnd - startMs < 2_500L) return
            rawCandidates.add(CandidateWindow(startMs = startMs, endMs = clampedEnd, priorityBonus = bonus))
        }

        // 1. Gemini multi-window solo timestamps (`sample_windows[]`)
        dto.sampleWindows.forEach { w ->
            val sStr = w.start.trim()
            if (sStr.isBlank()) return@forEach
            val sMs = ClipSlicer.parseApproxMmSsToMs(sStr)
            val parsedEnd = if (w.end.isNotBlank()) ClipSlicer.parseApproxMmSsToMs(w.end.trim()) else sMs + 7_500L
            val bonus = when {
                w.quality.equals("HIGH", ignoreCase = true) -> 2.5f
                w.quality.equals("MEDIUM", ignoreCase = true) -> 1.0f
                else -> 0.5f
            }
            addWindow(sMs, parsedEnd.coerceAtLeast(sMs + 4_500L), bonus)
        }

        // 2. Primary Gemini solo timestamp (`sample_start`..`sample_end`)
        val rawStartStr = dto.sampleStart?.trim()
        val rawEndStr = dto.sampleEnd?.trim()
        if (!rawStartStr.isNullOrBlank()) {
            val sMs = ClipSlicer.parseApproxMmSsToMs(rawStartStr)
            val parsedEnd = if (!rawEndStr.isNullOrBlank()) ClipSlicer.parseApproxMmSsToMs(rawEndStr) else sMs + 7_500L
            addWindow(sMs, parsedEnd.coerceAtLeast(sMs + 4_500L), 1.5f)
        }

        // 3. Coalesced non-overlapping solo transcript turns by this speaker (clamped 250ms before next speaker)
        val soloTurnCandidates = mutableListOf<Pair<CandidateWindow, Int>>()
        var i = 0
        while (i < rawTranscript.size) {
            val sent = rawTranscript[i]
            if (!sent.speakerId.equals(dto.speakerId, ignoreCase = true)) {
                i++
                continue
            }
            val blockStartMs = ClipSlicer.parseApproxMmSsToMs(sent.t)
            var totalChars = sent.textOriginal.length
            var j = i + 1
            while (j < rawTranscript.size && rawTranscript[j].speakerId.equals(dto.speakerId, ignoreCase = true)) {
                val nextSameMs = ClipSlicer.parseApproxMmSsToMs(rawTranscript[j].t)
                if (nextSameMs - blockStartMs > 10_000L) break
                totalChars += rawTranscript[j].textOriginal.length
                j++
            }
            val nextOtherMs = rawTranscript.getOrNull(j)?.let { ClipSlicer.parseApproxMmSsToMs(it.t) }
            val estEndMs = when {
                nextOtherMs != null && nextOtherMs > blockStartMs + 2_750L ->
                    minOf(nextOtherMs - 250L, blockStartMs + 9_500L)
                nextOtherMs == null ->
                    minOf(totalAudioDurationMs, blockStartMs + 8_500L)
                else -> blockStartMs
            }
            if (estEndMs - blockStartMs >= 2_500L && totalChars >= 12) {
                val win = CandidateWindow(
                    startMs = blockStartMs,
                    endMs = estEndMs.coerceAtMost(totalAudioDurationMs),
                    priorityBonus = 0.5f
                )
                soloTurnCandidates.add(win to totalChars)
            }
            i = j
        }
        soloTurnCandidates
            .sortedByDescending { it.second }
            .take(6)
            .forEach { (win, _) -> rawCandidates.add(win) }

        // 4. Item spoken quote windows by this speaker
        rawItems.filter {
            it.speakerId.equals(dto.speakerId, ignoreCase = true) && it.approxStart.isNotBlank()
        }.take(4).forEach { spkItem ->
            val sMs = ClipSlicer.parseApproxMmSsToMs(spkItem.approxStart)
            val eMs = ClipSlicer.parseApproxMmSsToMs(spkItem.approxEnd).coerceAtLeast(sMs + 5_500L)
            addWindow(sMs, eMs, 0f)
        }

        // 5. Single-speaker session windows (up to 4 distributed windows across the recording)
        if (speakerCount == 1 && totalAudioDurationMs >= 4_500L) {
            addWindow(0L, minOf(9_000L, totalAudioDurationMs), 0f)
            if (totalAudioDurationMs >= 20_000L) {
                val midStart = (totalAudioDurationMs / 2L - 4_500L).coerceAtLeast(0L)
                addWindow(midStart, minOf(midStart + 9_000L, totalAudioDurationMs), 0f)
            }
            if (totalAudioDurationMs >= 45_000L) {
                val q1Start = (totalAudioDurationMs / 4L - 4_000L).coerceAtLeast(0L)
                val q3Start = ((totalAudioDurationMs * 3L) / 4L - 4_000L).coerceAtLeast(0L)
                addWindow(q1Start, minOf(q1Start + 8_500L, totalAudioDurationMs), 0f)
                addWindow(q3Start, minOf(q3Start + 8_500L, totalAudioDurationMs), 0f)
            }
        }

        // Deduplicate overlapping windows (starts within 2.5s) keeping higher priority / closer to ideal 7.5s span
        val deduped = mutableListOf<CandidateWindow>()
        for (cand in rawCandidates.sortedWith(
            compareByDescending<CandidateWindow> { it.priorityBonus }
                .thenBy { abs((it.endMs - it.startMs) - 7_500L) }
        )) {
            val overlapsExisting = deduped.any { abs(it.startMs - cand.startMs) < 2_500L }
            if (!overlapsExisting) {
                deduped.add(cand)
                if (deduped.size >= MAX_CANDIDATE_WINDOWS_PER_SPEAKER) break
            }
        }
        return deduped
    }

    private fun pruneExcessAutoProfiles(
        prefs: PreferencesRepo,
        roster: MutableList<SpeakerProfileEntry>
    ) {
        val autoUnpinned = roster.filter { it.isAutoDiscovered && !it.isPinned }
        if (autoUnpinned.size <= MAX_AUTO_DISCOVERED_UNPINNED) return

        val excessCount = autoUnpinned.size - MAX_AUTO_DISCOVERED_UNPINNED
        val toRemove = autoUnpinned
            .sortedWith(
                compareBy<SpeakerProfileEntry> { it.sessionsHeardCount }
                    .thenBy { it.bestQualityScore() }
                    .thenBy { it.lastHeardAtMs }
            )
            .take(excessCount)

        val removeIds = toRemove.map { it.id }.toSet()
        toRemove.forEach { entry ->
            entry.samples.forEach { sample ->
                runCatching { File(sample.filePath).delete() }
            }
            entry.audioPath?.let { p -> runCatching { File(p).delete() } }
            runCatching { prefs.getSpeakerProfileAudioFile(entry.id).delete() }
        }
        roster.removeAll { it.id in removeIds }
    }

    /**
     * Renames or updates a speaker's recognizing nickname, relatable title, and voice signature
     * across both the current Room session (`speakers`, `transcript_sentences`, `items`) AND
     * optionally enrolls / updates their saved profile in the Voice Roster.
     */
    suspend fun renameOrUpdateSpeakerProfile(
        context: Context,
        sessionId: String,
        speakerId: String,
        newNickname: String,
        newRelatableTitle: String = "",
        voiceSignature: String = "",
        saveToRoster: Boolean = true,
        pinProfile: Boolean = false
    ) {
        val cleanNick = newNickname.trim()
        if (cleanNick.isBlank()) return
        val cleanTitle = newRelatableTitle.trim()
        val cleanSig = voiceSignature.trim()
        val formattedLabel = buildFormattedSpeakerLabel(cleanNick, cleanTitle)

        val appContext = context.applicationContext
        val dao: AppDao = AppDatabase.get(appContext).dao()
        val prefs = PreferencesRepo(appContext)
        val details = dao.getSessionWithDetailsOnce(sessionId)
        val currentSpeaker = details?.speakers?.firstOrNull {
            it.speakerId == speakerId
        }
        val effectiveSig = cleanSig.ifBlank { currentSpeaker?.voiceSignature.orEmpty() }
        val existingProfileId = currentSpeaker?.matchedProfileId?.takeIf { it.isNotBlank() }

        var savedProfileId: String? = existingProfileId

        if (saveToRoster && currentSpeaker?.isUser != true && existingProfileId != "me") {
            synchronized(rosterLock) {
                val roster = prefs.getSpeakerRoster().toMutableList()
                val idx = existingProfileId?.let { pid -> roster.indexOfFirst { it.id == pid } } ?: -1
                val now = System.currentTimeMillis()

                if (idx >= 0) {
                    val prev = roster[idx]
                    savedProfileId = prev.id
                    var samples = prev.existingSamples()
                    if (samples.isEmpty() && details != null) {
                        val sliced = trySliceFromExistingSession(appContext, prefs, prev.id, sessionId, speakerId, details)
                        if (sliced != null) {
                            samples = listOf(sliced)
                        }
                    }
                    val best = samples.firstOrNull()
                    val updatedAliases = buildList {
                        addAll(prev.aliases)
                        val oldNick = prev.effectiveNickname()
                        if (!isGenericSpeakerPlaceholder(oldNick) &&
                            !isSyntheticFallbackNickname(oldNick) &&
                            !oldNick.equals(cleanNick, ignoreCase = true)
                        ) {
                            add(oldNick)
                        }
                    }.distinctBy { it.lowercase(Locale.US) }.take(6)

                    roster[idx] = prev.copy(
                        displayName = cleanNick,
                        nickname = cleanNick,
                        role = cleanTitle,
                        relatableTitle = cleanTitle,
                        voiceHint = effectiveSig.ifBlank { prev.voiceHint },
                        voiceSignature = effectiveSig.ifBlank { prev.voiceSignature },
                        samples = samples,
                        audioPath = best?.filePath ?: prev.audioPath,
                        sampleDurationMs = best?.durationMs ?: prev.sampleDurationMs,
                        isAutoDiscovered = false,
                        isPinned = pinProfile || prev.isPinned,
                        lastHeardAtMs = now,
                        aliases = updatedAliases
                    )
                } else {
                    val newId = existingProfileId ?: UUID.randomUUID().toString().take(8)
                    savedProfileId = newId
                    val initialSamples = if (details != null) {
                        listOfNotNull(trySliceFromExistingSession(appContext, prefs, newId, sessionId, speakerId, details))
                    } else {
                        emptyList()
                    }
                    val best = initialSamples.firstOrNull()
                    roster.add(
                        SpeakerProfileEntry(
                            id = newId,
                            displayName = cleanNick,
                            role = cleanTitle,
                            voiceHint = effectiveSig,
                            audioPath = best?.filePath,
                            isUser = false,
                            nickname = cleanNick,
                            relatableTitle = cleanTitle,
                            voiceSignature = effectiveSig,
                            isAutoDiscovered = false,
                            isPinned = pinProfile,
                            sessionsHeardCount = 1,
                            lastHeardAtMs = now,
                            sampleDurationMs = best?.durationMs ?: 0L,
                            sourceSessionId = sessionId,
                            samples = initialSamples
                        )
                    )
                }
                prefs.saveSpeakerRoster(roster)
            }
        }

        dao.renameSpeakerProfileAcrossSession(
            sessionId = sessionId,
            speakerId = speakerId,
            newLabel = formattedLabel,
            matchedProfileId = savedProfileId,
            nickname = cleanNick,
            relatableTitle = cleanTitle.ifBlank { null },
            voiceSignature = effectiveSig.ifBlank { null },
            isAutoEnrolled = false
        )
    }

    private fun trySliceFromExistingSession(
        context: Context,
        prefs: PreferencesRepo,
        profileId: String,
        sessionId: String,
        speakerId: String,
        details: com.ambientbrain.recorder.data.SessionWithDetails
    ): VoiceProfileSample? {
        val rawPath = details.session.rawPath ?: return null
        val rawFile = File(rawPath)
        if (!rawFile.exists() || rawFile.length() < 4096L) return null

        val bestSentence = details.sentences
            .filter { it.speakerId == speakerId }
            .maxByOrNull { it.textOriginal.length }
        val spkItem = details.items.firstOrNull { it.speakerId == speakerId }

        val startMs: Long
        val endMs: Long
        when {
            bestSentence != null -> {
                startMs = bestSentence.approxMs
                endMs = startMs + 7_500L
            }
            spkItem != null && spkItem.clipEndMs > spkItem.clipStartMs -> {
                startMs = spkItem.clipStartMs
                endMs = spkItem.clipEndMs
            }
            else -> {
                startMs = 0L
                endMs = 7_500L
            }
        }

        val sampleId = UUID.randomUUID().toString().take(8)
        val outFile = prefs.getSpeakerSampleAudioFile(profileId, sampleId)
        val res = ClipSlicer.sliceAdtsVoiceSample(
            rawAdtsFile = rawFile,
            outFile = outFile,
            startMs = startMs,
            endMs = endMs
        ) ?: return null

        val metrics = ClipSlicer.analyzeAdtsSampleQuality(res.file)
        val voiceprint = SpeakerEmbeddingEngine.extractEmbeddingFromFile(context, res.file)
        val recordedAtMs = if (details.session.startMs > 0L) {
            details.session.startMs + startMs
        } else {
            rawFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        }
        runCatching { res.file.setLastModified(recordedAtMs) }
        return VoiceProfileSample(
            id = sampleId,
            filePath = res.file.absolutePath,
            durationMs = res.durationMs,
            qualityScore = metrics.score,
            snrDb = metrics.snrDb,
            speechRatio = metrics.speechRatio,
            createdAtMs = recordedAtMs,
            sourceSessionId = sessionId,
            isManual = false,
            embedding = voiceprint
        )
    }

    /**
     * 1-tap manual voice sample slicing from any transcript sentence timestamp.
     * Slices a clean ~9s window centered on [centerMs], scores acoustic quality (+10 manual bonus),
     * extracts a 48-D speaker voiceprint, and stores it in My Voice or the speaker's top-5 samples.
     */
    suspend fun addSampleFromSessionSegment(
        context: Context,
        sessionId: String,
        speakerId: String,
        centerMs: Long
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.get(appContext).dao()
        val prefs = PreferencesRepo(appContext)
        val details = dao.getSessionWithDetailsOnce(sessionId)
            ?: return@withContext false to "Session not found."

        val rawFile = details.session.rawPath?.let { File(it) }
            ?.takeIf { it.exists() && it.length() >= 4096L }
            ?: return@withContext false to "Raw session audio is no longer on device."

        val spk = details.speakers.firstOrNull { it.speakerId == speakerId }
        val isSelfUser = spk?.isUser == true || spk?.matchedProfileId == "me"

        val startMs = (centerMs - 3_000L).coerceAtLeast(0L)
        val endMs = startMs + 9_000L
        val sampleId = UUID.randomUUID().toString().take(8)

        val targetProfileId: String
        val targetLabel: String
        val outFile: File

        if (isSelfUser) {
            targetProfileId = "me"
            targetLabel = prefs.userDisplayName.ifBlank { "My Voice" }
            outFile = prefs.getMyVoiceSampleAudioFile(sampleId)
        } else {
            val resolvedId = spk?.matchedProfileId?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().take(8)
            targetProfileId = resolvedId
            targetLabel = spk?.nickname?.takeIf { it.isNotBlank() }
                ?: spk?.speakerLabel?.takeIf { it.isNotBlank() }
                ?: speakerId
            outFile = prefs.getSpeakerSampleAudioFile(resolvedId, sampleId)
        }

        val sliced = ClipSlicer.sliceAdtsVoiceSample(
            rawAdtsFile = rawFile,
            outFile = outFile,
            startMs = startMs,
            endMs = endMs,
            minMs = 3_000L,
            maxMs = 10_000L
        ) ?: return@withContext false to "Could not slice a clean audio clip at that timestamp."

        val segMaps = dao.getSegmentMapForSession(sessionId)
        val recordedAtMs = if (details.session.startMs > 0L) {
            ClipSlicer.resolveCompactedTimestamp(
                rawInputMs = startMs,
                segments = segMaps,
                sessionStartMs = details.session.startMs
            ).wallClockMs
        } else {
            System.currentTimeMillis()
        }
        runCatching { sliced.file.setLastModified(recordedAtMs) }

        val metrics = ClipSlicer.analyzeAdtsSampleQuality(sliced.file)
        val voiceprint = SpeakerEmbeddingEngine.extractEmbeddingFromFile(appContext, sliced.file)
        val boostedScore = (metrics.score + 10f).coerceIn(1f, 100f)
        val sample = VoiceProfileSample(
            id = sampleId,
            filePath = sliced.file.absolutePath,
            durationMs = sliced.durationMs,
            qualityScore = boostedScore,
            snrDb = metrics.snrDb,
            speechRatio = metrics.speechRatio,
            createdAtMs = recordedAtMs,
            sourceSessionId = sessionId,
            isManual = true,
            embedding = voiceprint
        )

        val durSec = String.format(Locale.US, "%.1fs", sliced.durationMs / 1000f)
        if (isSelfUser) {
            val (accepted, updatedList) = prefs.addMyVoiceSample(sample)
            return@withContext if (accepted) {
                true to "Added clip to $targetLabel (Q${boostedScore.toInt()} • $durSec • ${updatedList.size}/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE})"
            } else {
                false to "$targetLabel already has ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} higher-quality samples."
            }
        }

        // Ensure roster profile exists and add sample into its top 10
        var acceptedResult = false
        var totalSamples = 0
        var resolvedRosterId = targetProfileId
        synchronized(rosterLock) {
            val roster = prefs.getSpeakerRoster().toMutableList()
            val existingIdx = roster.indexOfFirst { it.id == targetProfileId }
            if (existingIdx >= 0) {
                val (acc, updatedEntry) = prefs.addSpeakerVoiceSample(targetProfileId, sample)
                acceptedResult = acc
                totalSamples = updatedEntry?.existingSamples()?.size ?: 0
            } else {
                val (cleanNick, cleanTitle) = extractNicknameAndTitleFromLabel(targetLabel)
                val finalNick = spk?.nickname?.takeIf { it.isNotBlank() } ?: cleanNick.ifBlank { speakerId }
                val finalTitle = spk?.relatableTitle?.takeIf { it.isNotBlank() }
                    ?: spk?.inferredRole?.takeIf { it.isNotBlank() }
                    ?: cleanTitle
                val newEntry = SpeakerProfileEntry(
                    id = targetProfileId,
                    displayName = finalNick,
                    nickname = finalNick,
                    role = finalTitle,
                    relatableTitle = finalTitle,
                    voiceHint = spk?.voiceSignature.orEmpty(),
                    voiceSignature = spk?.voiceSignature.orEmpty(),
                    audioPath = sample.filePath,
                    isUser = false,
                    isAutoDiscovered = false,
                    isPinned = false,
                    sessionsHeardCount = 1,
                    lastHeardAtMs = details.session.startMs.takeIf { it > 0L } ?: recordedAtMs,
                    sampleDurationMs = sample.durationMs,
                    sourceSessionId = sessionId,
                    samples = listOf(sample)
                )
                roster.add(newEntry)
                prefs.saveSpeakerRoster(roster)
                acceptedResult = true
                totalSamples = 1
                resolvedRosterId = newEntry.id
            }
        }

        // Link newly created profile ID back to the session if needed
        if (spk != null && spk.matchedProfileId.isNullOrBlank()) {
            dao.renameSpeakerProfileAcrossSession(
                sessionId = sessionId,
                speakerId = speakerId,
                newLabel = spk.speakerLabel,
                matchedProfileId = resolvedRosterId,
                nickname = spk.nickname ?: targetLabel,
                relatableTitle = spk.relatableTitle ?: spk.inferredRole,
                voiceSignature = spk.voiceSignature,
                isAutoEnrolled = false
            )
        }

        if (acceptedResult) {
            true to "Saved clip to $targetLabel (Q${boostedScore.toInt()} • $durSec • $totalSamples/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE})"
        } else {
            false to "$targetLabel already has ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} higher-quality samples."
        }
    }

    /**
     * Merges a duplicate voice profile [sourceProfileId] into [targetProfileId], keeping the
     * target's identity while summing encounter counts, combining aliases, combining all voice
     * samples and retaining the top 10 highest-quality clips, and cleaning up the duplicate files.
     */
    fun mergeProfiles(
        context: Context,
        sourceProfileId: String,
        targetProfileId: String
    ): List<SpeakerProfileEntry> {
        if (sourceProfileId == targetProfileId) {
            return PreferencesRepo(context).getSpeakerRoster()
        }
        val prefs = PreferencesRepo(context.applicationContext)
        return synchronized(rosterLock) {
            val roster = prefs.getSpeakerRoster().toMutableList()
            val srcIdx = roster.indexOfFirst { it.id == sourceProfileId }
            val tgtIdx = roster.indexOfFirst { it.id == targetProfileId }
            if (srcIdx < 0 || tgtIdx < 0) return@synchronized roster

            val src = roster[srcIdx]
            val tgt = roster[tgtIdx]

            val combinedSamples = mutableListOf<VoiceProfileSample>()
            combinedSamples.addAll(tgt.existingSamples())

            // Copy source samples into fresh target-owned files so deleting source never orphans paths
            src.existingSamples().forEach { sSample ->
                val srcFile = File(sSample.filePath)
                if (srcFile.exists() && srcFile.length() >= 1024L) {
                    val newSampleId = UUID.randomUUID().toString().take(8)
                    val destFile = prefs.getSpeakerSampleAudioFile(tgt.id, newSampleId)
                    val copied = runCatching {
                        srcFile.copyTo(destFile, overwrite = true)
                        if (sSample.createdAtMs > 0L) {
                            runCatching { destFile.setLastModified(sSample.createdAtMs) }
                        }
                        destFile
                    }.getOrNull()
                    if (copied != null && copied.exists()) {
                        combinedSamples.add(
                            sSample.copy(
                                id = newSampleId,
                                filePath = copied.absolutePath
                            )
                        )
                    }
                }
            }

            val sortedSamples = combinedSamples
                .distinctBy { it.filePath }
                .sortedWith(
                    compareByDescending<VoiceProfileSample> { it.qualityScore }
                        .thenByDescending { it.isManual }
                        .thenByDescending { it.createdAtMs }
                )
            val keptSamples = sortedSamples.take(PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE)
            val keptPaths = keptSamples.map { it.filePath }.toSet()
            sortedSamples.drop(PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE).forEach { evicted ->
                if (evicted.filePath !in keptPaths) {
                    runCatching { File(evicted.filePath).delete() }
                }
            }

            val bestSample = keptSamples.firstOrNull()
            val mergedSig = listOf(tgt.voiceSignature, tgt.voiceHint, src.voiceSignature, src.voiceHint)
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            val mergedAliases = buildList {
                addAll(tgt.aliases)
                addAll(src.aliases)
                val srcNick = src.effectiveNickname()
                if (!isGenericSpeakerPlaceholder(srcNick) &&
                    !isSyntheticFallbackNickname(srcNick) &&
                    !srcNick.equals(tgt.effectiveNickname(), ignoreCase = true)
                ) {
                    add(srcNick)
                }
            }.map { it.trim() }
                .filter { it.isNotBlank() && !it.equals(tgt.effectiveNickname(), ignoreCase = true) }
                .distinctBy { it.lowercase(Locale.US) }
                .take(6)

            roster[tgtIdx] = tgt.copy(
                relatableTitle = tgt.effectiveRelatableTitle().ifBlank { src.effectiveRelatableTitle() },
                role = tgt.effectiveRelatableTitle().ifBlank { src.effectiveRelatableTitle() },
                voiceSignature = mergedSig,
                voiceHint = mergedSig,
                samples = keptSamples,
                audioPath = bestSample?.filePath,
                sampleDurationMs = bestSample?.durationMs ?: 0L,
                isPinned = tgt.isPinned || src.isPinned,
                isAutoDiscovered = tgt.isAutoDiscovered && src.isAutoDiscovered,
                sessionsHeardCount = tgt.sessionsHeardCount + src.sessionsHeardCount,
                lastHeardAtMs = maxOf(tgt.lastHeardAtMs, src.lastHeardAtMs),
                aliases = mergedAliases
            )

            roster.removeAll { it.id == sourceProfileId }
            src.samples.forEach { sample ->
                runCatching { File(sample.filePath).delete() }
            }
            src.audioPath?.let { path -> runCatching { File(path).delete() } }
            runCatching { prefs.getSpeakerProfileAudioFile(sourceProfileId).delete() }
            prefs.saveSpeakerRoster(roster)
            prefs.getSpeakerRoster()
        }
    }

    // ---- Retroactive Voice Profile Extraction from Old Recorded Audio ----

    /**
     * Extracts and/or upgrades voice profiles from an already-recorded Room session on device:
     *  - If the session already has speakers/sentences/items from prior AI analysis, synthesizes
     *    speaker & timing descriptors and runs the full hybrid voiceprint + multi-window quality
     *    extraction algorithm (zero API calls), updating Room links if speakers match profiles.
     *  - If the session has NO speaker labels yet (unanalyzed/raw recording), runs an offline
     *    on-device 48-D voiceprint scan across clean speech windows to recognize known My Voice
     *    and Roster speakers and upgrade their top-5 samples, flagging `needsAiAnalysis = true`
     *    if new/unidentified speakers still require Gemini diarization.
     */
    suspend fun extractVoiceProfilesFromSession(
        context: Context,
        sessionId: String,
        allowCreateNewProfiles: Boolean = true
    ): SessionVoiceExtractionResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.get(appContext).dao()
        val prefs = PreferencesRepo(appContext)
        val details = dao.getSessionWithDetailsOnce(sessionId)
            ?: return@withContext SessionVoiceExtractionResult(
                sessionId = sessionId,
                sessionTitle = "Missing session",
                hadRawAudio = false,
                hadSpeakerLabels = false,
                profilesMatched = 0,
                profilesCreated = 0,
                samplesAddedOrUpgraded = 0,
                needsAiAnalysis = false,
                summaryMessage = "Recording not found in database."
            )

        val title = details.session.title?.takeIf { it.isNotBlank() } ?: "Recording ${sessionId.take(6)}"
        val rawFile = details.session.rawPath?.let { File(it) }
            ?.takeIf { it.exists() && it.length() >= 4096L }
            ?: return@withContext SessionVoiceExtractionResult(
                sessionId = sessionId,
                sessionTitle = title,
                hadRawAudio = false,
                hadSpeakerLabels = details.speakers.isNotEmpty(),
                profilesMatched = 0,
                profilesCreated = 0,
                samplesAddedOrUpgraded = 0,
                needsAiAnalysis = false,
                summaryMessage = "Raw audio file is no longer on device."
            )

        val durMs = ClipSlicer.estimateAdtsDurationMs(rawFile).takeIf { it > 0L }
            ?: (details.session.endMs - details.session.startMs).coerceAtLeast(0L)
        if (durMs < 2_500L) {
            return@withContext SessionVoiceExtractionResult(
                sessionId = sessionId,
                sessionTitle = title,
                hadRawAudio = true,
                hadSpeakerLabels = details.speakers.isNotEmpty(),
                profilesMatched = 0,
                profilesCreated = 0,
                samplesAddedOrUpgraded = 0,
                needsAiAnalysis = false,
                summaryMessage = "Recording is too short (<2.5s) to extract voice samples."
            )
        }

        val segMaps = dao.getSegmentMapForSession(sessionId)
        val hasLabels = details.speakers.isNotEmpty() || details.sentences.isNotEmpty() || details.items.isNotEmpty()

        if (hasLabels) {
            val rawSpeakers = details.speakers.map { spk ->
                GeminiSpeakerDto(
                    speakerId = spk.speakerId,
                    speakerLabel = spk.speakerLabel,
                    inferredRole = spk.inferredRole,
                    isUser = spk.isUser,
                    matchedProfileId = spk.matchedProfileId,
                    // Use MEDIUM so biometric & acoustic contradiction guards always verify stored links
                    matchConfidence = if (!spk.matchedProfileId.isNullOrBlank()) "MEDIUM" else null,
                    nickname = spk.nickname.orEmpty(),
                    relatableTitle = spk.relatableTitle.orEmpty(),
                    voiceSignature = spk.voiceSignature.orEmpty()
                )
            }
            val rawTranscript = details.sentences.sortedBy { it.ordinal }.map { sent ->
                GeminiSentenceDto(
                    t = formatMsToMmSs(sent.approxMs),
                    speakerId = sent.speakerId,
                    speakerLabel = sent.speakerLabel,
                    textOriginal = sent.textOriginal,
                    textEn = sent.textEn
                )
            }
            val rawItems = details.items.map { item ->
                GeminiExtractedItemDto(
                    itemType = item.type.name,
                    title = item.title,
                    details = item.details,
                    patientIdentifier = item.patientIdentifier,
                    speakerId = item.speakerId.orEmpty(),
                    speakerLabel = item.speakerLabel,
                    assignee = item.assignee,
                    isForUser = item.isForUser,
                    due = item.due,
                    priority = item.priority,
                    quoteOriginal = item.quoteOriginal,
                    quoteEn = item.quoteEn,
                    approxStart = formatMsToMmSs(item.clipStartMs),
                    approxEnd = formatMsToMmSs(item.clipEndMs.coerceAtLeast(item.clipStartMs + 5_000L)),
                    needsVerification = item.needsVerification
                )
            }

            val sliceSources = listOf(
                SliceSourceSegment(
                    file = rawFile,
                    globalOffsetMs = 0L,
                    durationMs = durMs,
                    segments = segMaps,
                    sourceStartMs = details.session.startMs
                )
            )
            val reconciled = reconcileAndEnrollSpeakers(
                context = appContext,
                prefs = prefs,
                sessionId = sessionId,
                sessionContext = details.session.context ?: "OTHER",
                sliceSources = sliceSources,
                rawSpeakers = rawSpeakers,
                rawTranscript = rawTranscript,
                rawItems = rawItems,
                allowSlicing = true,
                allowCreateNewProfiles = allowCreateNewProfiles,
                ignoreAutoRecognizeToggle = true,
                sessionRecordedAtMs = details.session.startMs
            )

            // Persist any newly linked profile IDs / upgraded labels back into Room
            val existingById = details.speakers.associateBy { it.speakerId }
            val brandNewEntities = mutableListOf<SessionSpeakerEntity>()
            reconciled.speakers.forEach { updated ->
                val prev = existingById[updated.speakerId]
                if (prev == null) {
                    brandNewEntities.add(updated)
                } else if (prev.matchedProfileId != updated.matchedProfileId ||
                    prev.speakerLabel != updated.speakerLabel ||
                    prev.nickname != updated.nickname ||
                    prev.relatableTitle != updated.relatableTitle ||
                    prev.voiceSignature != updated.voiceSignature
                ) {
                    dao.renameSpeakerProfileAcrossSession(
                        sessionId = sessionId,
                        speakerId = updated.speakerId,
                        newLabel = updated.speakerLabel,
                        matchedProfileId = updated.matchedProfileId,
                        nickname = updated.nickname,
                        relatableTitle = updated.relatableTitle ?: updated.inferredRole,
                        voiceSignature = updated.voiceSignature,
                        isAutoEnrolled = updated.isAutoEnrolled
                    )
                }
            }
            if (brandNewEntities.isNotEmpty()) {
                dao.insertSpeakers(brandNewEntities)
            }

            val summary = buildString {
                append("Matched ${reconciled.matchedExistingCount}")
                if (reconciled.autoEnrolledCount > 0) append(", created ${reconciled.autoEnrolledCount} new")
                append(", saved/upgraded ${reconciled.samplesSavedOrUpgradedCount} sample(s)")
            }
            return@withContext SessionVoiceExtractionResult(
                sessionId = sessionId,
                sessionTitle = title,
                hadRawAudio = true,
                hadSpeakerLabels = true,
                profilesMatched = reconciled.matchedExistingCount,
                profilesCreated = reconciled.autoEnrolledCount,
                samplesAddedOrUpgraded = reconciled.samplesSavedOrUpgradedCount,
                needsAiAnalysis = false,
                summaryMessage = summary
            )
        }

        // Unlabeled / unanalyzed session: run offline on-device 48-D voiceprint scan against known profiles
        val unlabeledRes = extractKnownVoicesFromUnlabeledAudio(
            context = appContext,
            prefs = prefs,
            audioFile = rawFile,
            totalDurationMs = durMs,
            segments = segMaps,
            sessionId = sessionId,
            sourceStartMs = details.session.startMs,
            sourceTitle = title,
            sourceContextHint = details.session.context ?: "OTHER"
        )
        if (unlabeledRes.matchedEntities.isNotEmpty() && details.speakers.isEmpty()) {
            dao.insertSpeakers(unlabeledRes.matchedEntities)
        }
        val needsAi = details.session.status != Status.DONE || unlabeledRes.matchedEntities.isEmpty()
        val msg = if (unlabeledRes.matchedEntities.isNotEmpty()) {
            "Offline voiceprint scan matched ${unlabeledRes.matchedEntities.size} known voice(s), " +
                "saved/upgraded ${unlabeledRes.samplesAddedOrUpgraded} sample(s)" +
                if (details.session.status != Status.DONE) " (use Gemini Audit or AI Analysis to discover new speakers)" else ""
        } else {
            "Harvested top-pick voice clips offline — tap 'Send Top-Pick Audios to Gemini' to analyze, audit & create Voice Profiles."
        }
        return@withContext SessionVoiceExtractionResult(
            sessionId = sessionId,
            sessionTitle = title,
            hadRawAudio = true,
            hadSpeakerLabels = false,
            profilesMatched = unlabeledRes.matchedEntities.size,
            profilesCreated = 0,
            samplesAddedOrUpgraded = unlabeledRes.samplesAddedOrUpgraded,
            needsAiAnalysis = needsAi,
            summaryMessage = msg
        )
    }

    /**
     * Scans a standalone/unindexed `.aac` recording file on device against known My Voice and
     * Speaker Roster 48-D voiceprints, upgrading top-10 samples with true estimated recording time.
     */
    suspend fun extractVoiceProfilesFromStandaloneFile(
        context: Context,
        audioFile: File
    ): SessionVoiceExtractionResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = PreferencesRepo(appContext)
        if (!audioFile.exists() || audioFile.length() < 4096L) {
            return@withContext SessionVoiceExtractionResult(
                sessionId = audioFile.name,
                sessionTitle = audioFile.name,
                hadRawAudio = false,
                hadSpeakerLabels = false,
                profilesMatched = 0,
                profilesCreated = 0,
                samplesAddedOrUpgraded = 0,
                needsAiAnalysis = false,
                summaryMessage = "Audio file is missing or too small."
            )
        }

        val durMs = ClipSlicer.estimateAdtsDurationMs(audioFile)
        if (durMs < 2_500L) {
            return@withContext SessionVoiceExtractionResult(
                sessionId = audioFile.name,
                sessionTitle = audioFile.name,
                hadRawAudio = true,
                hadSpeakerLabels = false,
                profilesMatched = 0,
                profilesCreated = 0,
                samplesAddedOrUpgraded = 0,
                needsAiAnalysis = false,
                summaryMessage = "Audio file is too short (<2.5s)."
            )
        }

        val nowMs = System.currentTimeMillis()
        val fileMtime = audioFile.lastModified().takeIf { it > 0L } ?: nowMs
        val estimatedStartMs = if (fileMtime + durMs > nowMs + 5_000L) {
            (fileMtime - durMs).coerceAtLeast(0L)
        } else {
            fileMtime
        }
        val res = extractKnownVoicesFromUnlabeledAudio(
            context = appContext,
            prefs = prefs,
            audioFile = audioFile,
            totalDurationMs = durMs,
            segments = emptyList(),
            sessionId = null,
            sourceStartMs = estimatedStartMs,
            sourceTitle = audioFile.name,
            sourceContextHint = "STANDALONE_FILE"
        )
        val msg = if (res.matchedEntities.isNotEmpty()) {
            "Matched ${res.matchedEntities.size} known voice(s) offline, saved/upgraded ${res.samplesAddedOrUpgraded} sample(s)."
        } else {
            "Harvested top-pick audio candidate — send to Gemini to analyze, audit & create Voice Profiles."
        }
        SessionVoiceExtractionResult(
            sessionId = audioFile.name,
            sessionTitle = audioFile.name,
            hadRawAudio = true,
            hadSpeakerLabels = false,
            profilesMatched = res.matchedEntities.size,
            profilesCreated = 0,
            samplesAddedOrUpgraded = res.samplesAddedOrUpgraded,
            needsAiAnalysis = true,
            summaryMessage = msg
        )
    }

    /**
     * Batch-mines voice profiles across old recordings stored on device within the selected time frame
     * (defaults to [VoiceExtractionTimeFrame.ALL] — all recordings):
     *  - Step 0: Backfills missing 48-D voiceprints for all currently saved samples.
     *  - Phase 1: Processes all analyzed/labeled Room sessions matching the time frame chronologically,
     *    retaining up to 10 highest-quality voice signature samples per profile.
     *  - Phase 2 (optional): Scans remaining unlabeled/unanalyzed Room sessions and standalone
     *    `.aac` files matching the time frame against the enriched voiceprint bank offline, while
     *    staging top-pick high-quality unidentified candidate clips for Gemini Voice Profile creation.
     *  - Phase 3: Runs an on-device speaker-purity & metadata-suitability pass that separates any
     *    mixed-speaker outlier samples and rechecks that Title, Name, and Description are suitable.
     */
    suspend fun extractVoiceProfilesFromAllOldRecordings(
        context: Context,
        includeUnlabeledScan: Boolean = true,
        timeFrame: VoiceExtractionTimeFrame = VoiceExtractionTimeFrame.ALL,
        customStartLocalDate: LocalDate? = null,
        customEndLocalDate: LocalDate? = null,
        minTimestampMsOverride: Long? = null,
        maxTimestampMsOverride: Long? = null,
        onProgress: (current: Int, total: Int, label: String) -> Unit = { _, _, _ -> }
    ): BatchVoiceExtractionSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.get(appContext).dao()
        val prefs = PreferencesRepo(appContext)

        val (resolvedMinMs, resolvedMaxMs, timeFrameLabel) = resolveTimeFrameBounds(
            timeFrame = timeFrame,
            customStartLocalDate = customStartLocalDate,
            customEndLocalDate = customEndLocalDate
        )
        val minTs = minTimestampMsOverride ?: resolvedMinMs
        val maxTs = maxTimestampMsOverride ?: resolvedMaxMs

        onProgress(0, 1, "Indexing existing voiceprints...")
        ensureSampleEmbeddingsBackfilled(appContext, prefs, maxToCompute = 80)

        val allSessions = dao.getAllSessionsWithDetailsOnce()
            .filter { it.session.combinedSourceIdsJson.isNullOrBlank() }
            .sortedBy { it.session.startMs }

        val trackedPaths = HashSet<String>()
        val labeledWithAudio = mutableListOf<SessionWithDetails>()
        val unlabeledWithAudio = mutableListOf<SessionWithDetails>()
        var skippedNoAudio = 0
        var skippedOutOfTimeFrame = 0

        for (details in allSessions) {
            val rawPath = details.session.rawPath
            if (!rawPath.isNullOrBlank()) trackedPaths.add(rawPath)
            val rawFile = rawPath?.let { File(it) }
            val fallbackFileMs = rawFile?.takeIf { it.exists() }?.lastModified() ?: 0L
            val sStart = details.session.startMs.takeIf { it > 0L } ?: fallbackFileMs
            val sEnd = details.session.endMs.takeIf { it >= sStart } ?: sStart

            if (!isTimestampInTimeFrame(sStart, sEnd, minTs, maxTs)) {
                skippedOutOfTimeFrame++
                continue
            }
            if (rawFile == null || !rawFile.exists() || rawFile.length() < 4096L) {
                skippedNoAudio++
                continue
            }
            val hasLabels = details.speakers.isNotEmpty() || details.sentences.isNotEmpty() || details.items.isNotEmpty()
            if (hasLabels) {
                labeledWithAudio.add(details)
            } else {
                unlabeledWithAudio.add(details)
            }
        }

        val nowMs = System.currentTimeMillis()
        val standaloneFiles = if (includeUnlabeledScan) {
            val sessionsDir = File(appContext.filesDir, "sessions")
            sessionsDir.listFiles { f ->
                f.isFile && f.length() >= 4096L && f.extension.equals("aac", ignoreCase = true)
            }?.filter { it.absolutePath !in trackedPaths }
                ?.filter { f ->
                    val dur = ClipSlicer.estimateAdtsDurationMs(f)
                    val mtime = f.lastModified().takeIf { it > 0L } ?: nowMs
                    val estStart = if (mtime + dur > nowMs + 5_000L) (mtime - dur).coerceAtLeast(0L) else mtime
                    val inRange = isTimestampInTimeFrame(estStart, mtime, minTs, maxTs)
                    if (!inRange) skippedOutOfTimeFrame++
                    inRange
                }
                ?.sortedBy { it.lastModified() }
                .orEmpty()
        } else {
            emptyList()
        }

        // Clear prior staged candidate clips before fresh scan
        clearStagedTopPickCandidates(appContext)

        val totalWork = labeledWithAudio.size +
            (if (includeUnlabeledScan) unlabeledWithAudio.size + standaloneFiles.size else 0)
        if (totalWork == 0) {
            val (separatedCount, recheckedCount) = auditAndSanitizeProfilesLocally(appContext, prefs, allSessions)
            val readyClipsCount = countTopPickAudiosAvailable(prefs)
            return@withContext BatchVoiceExtractionSummary(
                timeFrameLabel = timeFrameLabel,
                sessionsScanned = 0,
                standaloneFilesScanned = 0,
                profilesMatched = 0,
                profilesCreated = 0,
                samplesAddedOrUpgraded = 0,
                unlabeledMatched = 0,
                sessionsNeedingAiAnalysis = unlabeledWithAudio.size,
                skippedNoAudio = skippedNoAudio,
                skippedOutOfTimeFrame = skippedOutOfTimeFrame,
                contaminatedSamplesSeparated = separatedCount,
                profilesMetadataRechecked = recheckedCount,
                topPickAudiosReadyCount = readyClipsCount,
                topPickUnidentifiedCandidatesCount = lastHarvestedTopPickCandidates.size
            )
        }

        var done = 0
        var sessionsScanned = 0
        var standaloneScanned = 0
        var totalMatched = 0
        var totalCreated = 0
        var totalSamples = 0
        var totalUnlabeledMatched = 0
        var totalNeedingAi = 0

        // Phase 1: Labeled / previously analyzed recordings first (chronological)
        for (details in labeledWithAudio) {
            done++
            val label = details.session.title?.takeIf { it.isNotBlank() } ?: "Session ${details.session.id.take(6)}"
            onProgress(done, totalWork, "Extracting voices ($timeFrameLabel): $label")
            val res = extractVoiceProfilesFromSession(appContext, details.session.id, allowCreateNewProfiles = true)
            sessionsScanned++
            totalMatched += res.profilesMatched
            totalCreated += res.profilesCreated
            totalSamples += res.samplesAddedOrUpgraded
        }

        // Phase 2: Unlabeled / unanalyzed sessions + standalone .aac files offline voiceprint scan
        if (includeUnlabeledScan) {
            for (details in unlabeledWithAudio) {
                done++
                val label = details.session.title?.takeIf { it.isNotBlank() } ?: "Unanalyzed ${details.session.id.take(6)}"
                onProgress(done, totalWork, "Scanning voiceprints: $label")
                val res = extractVoiceProfilesFromSession(appContext, details.session.id, allowCreateNewProfiles = false)
                sessionsScanned++
                totalMatched += res.profilesMatched
                totalUnlabeledMatched += res.profilesMatched
                totalSamples += res.samplesAddedOrUpgraded
                if (res.needsAiAnalysis) totalNeedingAi++
            }
            for (file in standaloneFiles) {
                done++
                onProgress(done, totalWork, "Scanning file: ${file.name}")
                val res = extractVoiceProfilesFromStandaloneFile(appContext, file)
                standaloneScanned++
                totalMatched += res.profilesMatched
                totalUnlabeledMatched += res.profilesMatched
                totalSamples += res.samplesAddedOrUpgraded
                if (res.needsAiAnalysis) totalNeedingAi++
            }
        } else {
            totalNeedingAi = unlabeledWithAudio.size
        }

        // Phase 3: Post-extraction speaker-purity separation & Title/Name/Description suitability recheck
        onProgress(totalWork, totalWork, "Verifying speaker purity & Title/Name/Description suitability...")
        val refreshedSessions = dao.getAllSessionsWithDetailsOnce()
        val (separatedCount, recheckedCount) = auditAndSanitizeProfilesLocally(appContext, prefs, refreshedSessions)
        val readyClipsCount = countTopPickAudiosAvailable(prefs) + lastHarvestedTopPickCandidates.size

        BatchVoiceExtractionSummary(
            timeFrameLabel = timeFrameLabel,
            sessionsScanned = sessionsScanned,
            standaloneFilesScanned = standaloneScanned,
            profilesMatched = totalMatched,
            profilesCreated = totalCreated,
            samplesAddedOrUpgraded = totalSamples,
            unlabeledMatched = totalUnlabeledMatched,
            sessionsNeedingAiAnalysis = totalNeedingAi,
            skippedNoAudio = skippedNoAudio,
            skippedOutOfTimeFrame = skippedOutOfTimeFrame,
            contaminatedSamplesSeparated = separatedCount,
            profilesMetadataRechecked = recheckedCount,
            topPickAudiosReadyCount = readyClipsCount,
            topPickUnidentifiedCandidatesCount = lastHarvestedTopPickCandidates.size
        )
    }

    fun countTopPickAudiosAvailable(prefs: PreferencesRepo): Int {
        val myTop = prefs.getMyVoiceSamples().take(2).size
        val rosterTop = prefs.getSpeakerRoster().sumOf { it.existingSamples().take(2).size }
        return myTop + rosterTop
    }

    private fun getCandidateStageDir(context: Context): File {
        return File(context.applicationContext.filesDir, "profiles/top_pick_candidates").apply { mkdirs() }
    }

    private fun clearStagedTopPickCandidates(context: Context) {
        lastHarvestedTopPickCandidates = emptyList()
        runCatching {
            getCandidateStageDir(context).listFiles()?.forEach { it.delete() }
        }
    }

    private fun stageUnidentifiedTopPickCandidate(
        context: Context,
        candidate: EvaluatedCandidateClip,
        sessionId: String?,
        sourceTitle: String,
        sourceContextHint: String
    ) {
        if (candidate.metrics.score < 54f || candidate.embedding.isEmpty()) return
        synchronized(rosterLock) {
            val current = lastHarvestedTopPickCandidates.toMutableList()
            // Check if an existing staged candidate already matches this speaker voiceprint
            val sameSpeakerIdx = current.indexOfFirst { existing ->
                SpeakerEmbeddingEngine.areEmbeddingsBiometricallyCompatible(existing.embedding, candidate.embedding) &&
                    SpeakerEmbeddingEngine.biometricSimilarity(existing.embedding, candidate.embedding) >=
                    SpeakerEmbeddingEngine.VOICEPRINT_ROSTER_MATCH_THRESHOLD
            }
            if (sameSpeakerIdx >= 0) {
                val prev = current[sameSpeakerIdx]
                if (candidate.metrics.score <= prev.qualityScore + 1.0f) return
                runCatching { File(prev.filePath).delete() }
                current.removeAt(sameSpeakerIdx)
            } else if (current.size >= 6) {
                val worst = current.minByOrNull { it.qualityScore } ?: return
                if (candidate.metrics.score <= worst.qualityScore + 1.5f) return
                runCatching { File(worst.filePath).delete() }
                current.remove(worst)
            }

            val candId = UUID.randomUUID().toString().take(8)
            val destFile = File(getCandidateStageDir(context), "cand_${candId}.aac")
            val copied = runCatching {
                candidate.tempFile.copyTo(destFile, overwrite = true)
                destFile.setLastModified(candidate.recordedAtMs)
                true
            }.getOrDefault(false)
            if (!copied || !destFile.exists() || destFile.length() < 1024L) return

            current.add(
                TopPickUnidentifiedCandidate(
                    id = candId,
                    filePath = destFile.absolutePath,
                    durationMs = candidate.durationMs,
                    qualityScore = candidate.metrics.score,
                    snrDb = candidate.metrics.snrDb,
                    speechRatio = candidate.metrics.speechRatio,
                    recordedAtMs = candidate.recordedAtMs,
                    sourceSessionId = sessionId,
                    sourceTitle = sourceTitle,
                    sourceContextHint = sourceContextHint,
                    embedding = candidate.embedding
                )
            )
            lastHarvestedTopPickCandidates = current.sortedByDescending { it.qualityScore }
        }
    }

    /**
     * Local post-extraction speaker-purity & metadata-suitability pass:
     *  1. Ensures no voice profile contains mixed samples from different speakers
     *     (`SpeakerEmbeddingEngine.partitionSamplesByDominantSpeaker`), separating any outlier
     *     clips into their own clean profile or matching profile.
     *  2. Rechecks every profile's Name (`nickname`), Relatable Title (`relatableTitle`), and
     *     Description (`voiceSignature`) against both its measured acoustic voiceprint and
     *     linked Room session transcripts/items, repairing generic or contradictory metadata.
     */
    private suspend fun auditAndSanitizeProfilesLocally(
        context: Context,
        prefs: PreferencesRepo,
        allSessions: List<SessionWithDetails>
    ): Pair<Int, Int> {
        val dao = AppDatabase.get(context.applicationContext).dao()
        var outliersSeparated = 0
        var metadataUpdatedCount = 0
        val pendingSessionRenames = mutableListOf<Triple<String, String, SpeakerProfileEntry>>()

        synchronized(rosterLock) {
            // 1. Purify My Voice samples
            val mySamples = prefs.getMyVoiceSamples()
            if (mySamples.size >= 2) {
                val (consistentMy, outlierMy) = SpeakerEmbeddingEngine.partitionSamplesByDominantSpeaker(mySamples)
                if (outlierMy.isNotEmpty() && consistentMy.isNotEmpty()) {
                    outlierMy.forEach { evicted ->
                        runCatching { File(evicted.filePath).delete() }
                    }
                    prefs.saveMyVoiceSamples(consistentMy)
                    outliersSeparated += outlierMy.size
                    Log.i(TAG, "Separated ${outlierMy.size} inconsistent sample(s) from My Voice profile")
                }
            }

            // 2. Purify Speaker Roster profiles & split high-quality distinct-speaker outliers
            val roster = prefs.getSpeakerRoster().toMutableList()
            val newlySplitProfiles = mutableListOf<SpeakerProfileEntry>()

            for (idx in roster.indices) {
                val entry = roster[idx]
                val samples = entry.existingSamples()
                if (samples.size >= 2) {
                    val (consistent, outliers) = SpeakerEmbeddingEngine.partitionSamplesByDominantSpeaker(samples)
                    if (outliers.isNotEmpty() && consistent.isNotEmpty()) {
                        outliersSeparated += outliers.size
                        val bestKept = consistent.first()
                        roster[idx] = entry.copy(
                            samples = consistent,
                            audioPath = bestKept.filePath,
                            sampleDurationMs = bestKept.durationMs
                        )

                        // If an outlier sample is high quality (>= 58), separate it into its own clean profile
                        val bestOutlier = outliers.maxByOrNull { it.qualityScore }
                        if (bestOutlier != null && bestOutlier.qualityScore >= 58f && bestOutlier.embedding.isNotEmpty()) {
                            val newProfileId = UUID.randomUUID().toString().take(8)
                            val newSampleId = UUID.randomUUID().toString().take(8)
                            val newFile = prefs.getSpeakerSampleAudioFile(newProfileId, newSampleId)
                            val movedFile = runCatching {
                                File(bestOutlier.filePath).copyTo(newFile, overwrite = true)
                                newFile.setLastModified(bestOutlier.createdAtMs)
                                newFile
                            }.getOrNull()

                            if (movedFile != null && movedFile.exists() && movedFile.length() >= 1024L) {
                                val desc = SpeakerEmbeddingEngine.describeAcousticVoiceFromEmbedding(bestOutlier.embedding)
                                val title = entry.effectiveRelatableTitle().ifBlank { "Conversation Partner" }
                                val roleNoun = title.split(Regex("\\s+")).takeLast(2).joinToString(" ").ifBlank { "Voice" }
                                val nick = "${(desc?.recommendedAdjectivePrefix ?: "Clear-Tone ").trim()} $roleNoun"
                                val sig = desc?.buildAcousticSignatureSummary(bestOutlier.snrDb, bestOutlier.speechRatio)
                                    ?: "Distinct separated speaker voiceprint"
                                val splitSample = bestOutlier.copy(
                                    id = newSampleId,
                                    filePath = movedFile.absolutePath
                                )
                                newlySplitProfiles.add(
                                    SpeakerProfileEntry(
                                        id = newProfileId,
                                        displayName = nick,
                                        nickname = nick,
                                        role = title,
                                        relatableTitle = title,
                                        voiceHint = sig,
                                        voiceSignature = sig,
                                        audioPath = splitSample.filePath,
                                        isUser = false,
                                        isAutoDiscovered = true,
                                        isPinned = false,
                                        sessionsHeardCount = 1,
                                        lastHeardAtMs = splitSample.createdAtMs,
                                        sampleDurationMs = splitSample.durationMs,
                                        sourceSessionId = splitSample.sourceSessionId,
                                        samples = listOf(splitSample)
                                    )
                                )
                            }
                        }

                        outliers.forEach { oldOutlier ->
                            runCatching { File(oldOutlier.filePath).delete() }
                        }
                    }
                }
            }
            if (newlySplitProfiles.isNotEmpty()) {
                roster.addAll(newlySplitProfiles)
            }

            // 3. Recheck suitability of Name, Title, and Description for every roster profile
            for (idx in roster.indices) {
                val entry = roster[idx]
                val bestSample = entry.existingSamples().firstOrNull()
                val descriptor = bestSample?.embedding
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { SpeakerEmbeddingEngine.describeAcousticVoiceFromEmbedding(it) }

                // Inspect linked sessions for richer role/name/context evidence
                val linkedSessions = allSessions.filter { sess ->
                    sess.speakers.any { it.matchedProfileId == entry.id }
                }
                val bestLinkedSpeaker = linkedSessions
                    .flatMap { it.speakers }
                    .filter { it.matchedProfileId == entry.id }
                    .firstOrNull { !isRoleOnlyOrSyntheticName(it.nickname) }
                val dominantContext = linkedSessions
                    .mapNotNull { it.session.context }
                    .firstOrNull { it != "OTHER" && it != "NOISE_OR_TV" }
                    ?: "OTHER"

                val currentNick = entry.effectiveNickname()
                val currentTitle = entry.effectiveRelatableTitle()
                val currentSig = entry.voiceSignature.ifBlank { entry.voiceHint }

                var newTitle = currentTitle
                if (newTitle.isBlank() || isGenericSpeakerPlaceholder(newTitle) ||
                    (newTitle.equals("Conversation Partner", ignoreCase = true) &&
                        dominantContext in setOf("NEURO_ROUND", "MEDICAL_ROUND", "CASE_CONFERENCE", "HANDOVER"))
                ) {
                    val linkedRole = linkedSessions.flatMap { it.speakers }
                        .filter { it.matchedProfileId == entry.id }
                        .mapNotNull { it.relatableTitle?.takeIf { t -> t.isNotBlank() } ?: it.inferredRole }
                        .firstOrNull { !isGenericSpeakerPlaceholder(it) && !it.equals("Conversation Partner", ignoreCase = true) }
                    newTitle = linkedRole ?: when {
                        dominantContext in setOf("NEURO_ROUND", "MEDICAL_ROUND", "CASE_CONFERENCE", "HANDOVER") ->
                            "Clinical Care Team Member"
                        dominantContext == "ONLINE_MEETING" -> "Meeting Participant"
                        dominantContext == "PHONE_CALL" -> "Call Partner"
                        else -> currentTitle.ifBlank { "Conversation Partner" }
                    }
                }

                var newNick = currentNick
                val roleNoun = newTitle.split(Regex("\\s+")).takeLast(2).joinToString(" ").ifBlank { "Voice" }
                if (entry.isAutoDiscovered && !entry.isPinned) {
                    when {
                        isRoleOnlyOrSyntheticName(newNick) && bestLinkedSpeaker?.nickname != null &&
                            !isRoleOnlyOrSyntheticName(bestLinkedSpeaker.nickname) -> {
                            newNick = bestLinkedSpeaker.nickname.trim()
                        }
                        isGenericSpeakerPlaceholder(newNick) ||
                            (newNick.equals(newTitle, ignoreCase = true) && isRoleOnlyOrSyntheticName(newNick)) -> {
                            val adj = (descriptor?.recommendedAdjectivePrefix ?: "Clear-Tone ").trim()
                            newNick = "$adj $roleNoun"
                        }
                        descriptor != null && isSyntheticFallbackNickname(newNick) && descriptor.contradictsNickname(newNick) -> {
                            val matchedPrefix = SYNTH_ADJECTIVE_PREFIXES.firstOrNull {
                                newNick.startsWith(it, ignoreCase = true)
                            }
                            val suffix = if (matchedPrefix != null) {
                                newNick.substring(matchedPrefix.length).trim().ifBlank { roleNoun }
                            } else roleNoun
                            newNick = "${descriptor.recommendedAdjectivePrefix.trim()} $suffix"
                        }
                    }
                }

                var newSig = currentSig
                if (newSig.length < 15 && descriptor != null) {
                    val measuredSig = descriptor.buildAcousticSignatureSummary(
                        snrDb = bestSample.snrDb,
                        speechRatio = bestSample.speechRatio
                    )
                    newSig = if (newSig.isNotBlank()) "$measuredSig • $newSig" else measuredSig
                }

                if (newNick != currentNick || newTitle != currentTitle || newSig != currentSig) {
                    metadataUpdatedCount++
                    val updatedEntry = entry.copy(
                        displayName = newNick,
                        nickname = newNick,
                        role = newTitle,
                        relatableTitle = newTitle,
                        voiceHint = newSig,
                        voiceSignature = newSig
                    )
                    roster[idx] = updatedEntry
                    linkedSessions.forEach { sess ->
                        sess.speakers.filter { it.matchedProfileId == entry.id }.forEach { spk ->
                            pendingSessionRenames.add(Triple(sess.session.id, spk.speakerId, updatedEntry))
                        }
                    }
                }
            }

            if (outliersSeparated > 0 || metadataUpdatedCount > 0 || newlySplitProfiles.isNotEmpty()) {
                prefs.saveSpeakerRoster(roster)
            }
        }

        // Propagate any locally repaired nicknames/titles back to Room sessions
        for ((sessId, spkId, updatedEntry) in pendingSessionRenames) {
            runCatching {
                dao.renameSpeakerProfileAcrossSession(
                    sessionId = sessId,
                    speakerId = spkId,
                    newLabel = updatedEntry.formattedLabel(),
                    matchedProfileId = updatedEntry.id,
                    nickname = updatedEntry.effectiveNickname(),
                    relatableTitle = updatedEntry.effectiveRelatableTitle().ifBlank { null },
                    voiceSignature = updatedEntry.voiceSignature.ifBlank { null },
                    isAutoEnrolled = updatedEntry.isAutoDiscovered
                )
            }
        }

        return outliersSeparated to metadataUpdatedCount
    }

    /**
     * Sends top-pick highest-quality voice sample audio clips (`.aac`) from extracted Voice Profiles
     * (plus any top-pick unidentified candidate clips from old recordings) to Gemini to:
     *  1) Analyze & audit speaker purity so different speakers are NEVER combined into the same profile
     *     (automatically splitting mismatched samples into a separate clean Voice Profile).
     *  2) Recheck whether each profile's Name (`nickname`), Relatable Title (`relatableTitle`), and
     *     Description (`voiceSignature`) are genuinely suitable for that voice profile and spoken context.
     *  3) Create accurate new Voice Profiles for top-pick candidate clips from old recordings.
     */
    suspend fun sendTopPickAudiosToGeminiForVoiceProfileAudit(
        context: Context,
        onProgress: (String) -> Unit = {}
    ): GeminiVoiceProfileAuditSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = PreferencesRepo(appContext)
        val dao = AppDatabase.get(appContext).dao()
        val client = GeminiInteractionsClient()

        val allKeys = prefs.getAllApiKeys()
        require(allKeys.isNotEmpty()) {
            "No Gemini API key configured. Please add a Google AI Studio API key in Settings -> Gemini API Key Pool."
        }

        onProgress("Preparing top-pick voice sample audios & voiceprints...")
        ensureSampleEmbeddingsBackfilled(appContext, prefs, maxToCompute = 60)

        val allSessions = dao.getAllSessionsWithDetailsOnce()
        // First run local purity & suitability pass so obvious acoustic outliers are pre-separated
        val (localOutliersSplit, _) = auditAndSanitizeProfilesLocally(appContext, prefs, allSessions)

        val rosterSnapshot = prefs.getSpeakerRoster()
        val profilesWithAudio = rosterSnapshot.filter { it.existingSamples().isNotEmpty() }
        val candidatesSnapshot = lastHarvestedTopPickCandidates.filter {
            File(it.filePath).exists() && File(it.filePath).length() >= 1024L
        }

        require(profilesWithAudio.isNotEmpty() || candidatesSnapshot.isNotEmpty()) {
            "No voice profile audio samples found to send to Gemini. Run 'Extract Voices from Old Recordings' first."
        }

        val userNick = prefs.userDisplayName.ifBlank { "Dr. (Me)" }
        val rosterOverview = buildString {
            appendLine("CURRENT SAVED VOICE PROFILE ROSTER (${rosterSnapshot.size} profiles):")
            rosterSnapshot.forEach { spk ->
                val samples = spk.existingSamples()
                val bestQ = samples.firstOrNull()?.qualityScore?.toInt() ?: 0
                appendLine(
                    "- [profile_id=${spk.id}] Name: '${spk.effectiveNickname()}' | " +
                        "Title: '${spk.effectiveRelatableTitle()}' | " +
                        "Description: '${spk.voiceSignature.ifBlank { spk.voiceHint }}' | " +
                        "Saved Samples: ${samples.size}/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} (Best Q$bestQ) | " +
                        "Heard: ${spk.sessionsHeardCount}x"
                )
            }
        }.trimEnd()

        val systemInstruction = Prompts.buildVoiceProfileAuditSystemInstruction(
            userDisplayName = userNick,
            existingRosterOverview = rosterOverview
        )

        // Group profiles into batches of up to 4 profiles (~8 top-pick audio clips per batch)
        val profileBatches = if (profilesWithAudio.isEmpty()) {
            listOf(emptyList())
        } else {
            profilesWithAudio.chunked(4)
        }

        val allAuditedDtos = mutableListOf<GeminiAuditedProfileDto>()
        val allCreatedDtos = mutableListOf<GeminiCreatedVoiceProfileDto>()
        val overallSummaries = mutableListOf<String>()
        var totalClipsSent = 0
        var totalInTokens = 0
        var totalOutTokens = 0
        var totalCachedTokens = 0
        var totalLatencyMs = 0L
        var usedModelLabel = prefs.geminiModel

        for ((batchIdx, profileBatch) in profileBatches.withIndex()) {
            val includeCandidatesInThisBatch = (batchIdx == profileBatches.lastIndex) && candidatesSnapshot.isNotEmpty()
            val batchClips = mutableListOf<VoiceProfileAuditAudioClip>()
            val promptBuilder = StringBuilder()
            promptBuilder.appendLine("=== VOICE PROFILE TOP-PICK AUDIO AUDIT BATCH ${batchIdx + 1}/${profileBatches.size} ===")
            promptBuilder.appendLine("Audit every attached profile's top-pick voice sample audio clip(s):")
            promptBuilder.appendLine("1) Verify whether all attached clips for the same profile_id belong to the EXACT SAME single speaker (NEVER combine different speakers!).")
            promptBuilder.appendLine("2) Recheck if the profile's Name (nickname), Relatable Title (relatable_title), and Description (voice_signature) are truly suitable for the voice heard in the audio and their spoken transcript quotes.")
            promptBuilder.appendLine()

            for (profile in profileBatch) {
                val samples = profile.existingSamples()
                // Select Top 2 highest-quality diverse samples from the up-to-10 saved samples
                val topPickSamples = buildList {
                    val first = samples.first()
                    add(first)
                    if (samples.size >= 2) {
                        // Pick either a sample with lowest biometric similarity (to audit borderline speaker purity!)
                        // or a second high-quality sample from a different session/timestamp
                        val second = samples.drop(1).minByOrNull { s ->
                            if (first.embedding.isNotEmpty() && s.embedding.isNotEmpty()) {
                                SpeakerEmbeddingEngine.biometricSimilarity(first.embedding, s.embedding)
                            } else 1f
                        } ?: samples[1]
                        add(second)
                    }
                }

                val descriptor = samples.firstOrNull()?.embedding
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { SpeakerEmbeddingEngine.describeAcousticVoiceFromEmbedding(it) }
                val measuredSummary = descriptor?.buildAcousticSignatureSummary(
                    snrDb = samples.first().snrDb,
                    speechRatio = samples.first().speechRatio
                ) ?: "Measured on-device 48-D voiceprint"

                val pairwiseSimNote = if (topPickSamples.size >= 2 &&
                    topPickSamples[0].embedding.isNotEmpty() &&
                    topPickSamples[1].embedding.isNotEmpty()
                ) {
                    val simPct = (SpeakerEmbeddingEngine.biometricSimilarity(
                        topPickSamples[0].embedding,
                        topPickSamples[1].embedding
                    ) * 100f).toInt()
                    "On-device Sample#1 vs Sample#2 biometric similarity: $simPct%"
                } else {
                    "Single top-pick sample attached"
                }

                // Collect spoken transcript quotes / task quotes for this profile across Room sessions
                val linkedQuotes = mutableListOf<String>()
                val linkedSessionContexts = mutableSetOf<String>()
                for (sess in allSessions) {
                    val matchingSpkIds = sess.speakers
                        .filter { it.matchedProfileId == profile.id }
                        .map { it.speakerId }
                        .toSet()
                    if (matchingSpkIds.isNotEmpty()) {
                        sess.session.context?.let { linkedSessionContexts.add(it) }
                        sess.sentences
                            .filter { it.speakerId in matchingSpkIds && it.textOriginal.length >= 10 }
                            .take(3)
                            .forEach { s ->
                                val enPart = s.textEn?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                                linkedQuotes.add("\"${s.textOriginal.take(110)}\"$enPart")
                            }
                        sess.items
                            .filter { it.speakerId in matchingSpkIds && it.title.isNotBlank() }
                            .take(2)
                            .forEach { item ->
                                linkedQuotes.add("[${item.type.name}] ${item.title}: \"${item.quoteOriginal.take(90)}\"")
                            }
                    }
                }

                promptBuilder.appendLine("--- PROFILE [profile_id=${profile.id}] ---")
                promptBuilder.appendLine("Current Name (nickname): ${profile.effectiveNickname()}")
                promptBuilder.appendLine("Current Relatable Title: ${profile.effectiveRelatableTitle().ifBlank { "(none)" }}")
                promptBuilder.appendLine("Current Voice Description: ${profile.voiceSignature.ifBlank { profile.voiceHint }.ifBlank { "(none)" }}")
                promptBuilder.appendLine("Profile Status: ${if (profile.isAutoDiscovered) "Auto-Discovered" else "Manual/User-Edited"}${if (profile.isPinned) " (Pinned)" else ""} | Saved Samples: ${samples.size}/10 | Heard: ${profile.sessionsHeardCount}x")
                promptBuilder.appendLine("Measured On-Device Acoustics: $measuredSummary | $pairwiseSimNote")
                if (linkedSessionContexts.isNotEmpty()) {
                    promptBuilder.appendLine("Session Contexts Heard In: ${linkedSessionContexts.joinToString(", ")}")
                }
                if (linkedQuotes.isNotEmpty()) {
                    promptBuilder.appendLine("Spoken Quotes / Statements by this Profile:")
                    linkedQuotes.take(4).forEach { q -> promptBuilder.appendLine("  * $q") }
                }
                promptBuilder.appendLine()

                topPickSamples.forEachIndexed { sIdx, sample ->
                    val f = File(sample.filePath)
                    if (f.exists() && f.length() in 1024L..2_000_000L) {
                        val header = "[PROFILE_AUDIO profile_id=${profile.id} sample_id=${sample.id} rank=${sIdx + 1}/${topPickSamples.size}] " +
                            "Current Name='${profile.effectiveNickname()}', Title='${profile.effectiveRelatableTitle()}', " +
                            "Quality=Q${sample.qualityScore.toInt()}, SNR=${sample.snrDb.toInt()}dB, Duration=${sample.durationMs / 1000}s"
                        batchClips.add(VoiceProfileAuditAudioClip(headerPromptText = header, file = f))
                    }
                }
            }

            if (includeCandidatesInThisBatch) {
                promptBuilder.appendLine("=== TOP-PICK UNIDENTIFIED CANDIDATE CLIPS FROM OLD RECORDINGS ===")
                promptBuilder.appendLine("Analyze each candidate clip below, check if it is a clear single human speaker, and create an accurate Voice Profile (nickname, relatable_title, voice_signature):")
                candidatesSnapshot.take(4).forEach { cand ->
                    val f = File(cand.filePath)
                    if (f.exists() && f.length() in 1024L..2_000_000L) {
                        val desc = SpeakerEmbeddingEngine.describeAcousticVoiceFromEmbedding(cand.embedding)
                            ?.buildAcousticSignatureSummary(cand.snrDb, cand.speechRatio)
                            ?: "Clear solo speech window"
                        promptBuilder.appendLine(
                            "- [CANDIDATE_CLIP candidate_clip_id=${cand.id}] Source='${cand.sourceTitle}' (${cand.sourceContextHint}), " +
                                "Quality=Q${cand.qualityScore.toInt()}, Acoustics=$desc"
                        )
                        val header = "[CANDIDATE_CLIP candidate_clip_id=${cand.id}] Source='${cand.sourceTitle}', Quality=Q${cand.qualityScore.toInt()}, SNR=${cand.snrDb.toInt()}dB"
                        batchClips.add(VoiceProfileAuditAudioClip(headerPromptText = header, file = f))
                    }
                }
            }

            onProgress("Sending ${batchClips.size} top-pick audio sample(s) to Gemini (Batch ${batchIdx + 1}/${profileBatches.size})...")
            val (callResp, routeLabel) = executeAuditCallWithKeyPool(
                prefs = prefs,
                client = client,
                systemInstruction = systemInstruction,
                auditPromptText = promptBuilder.toString(),
                audioClips = batchClips
            )
            usedModelLabel = routeLabel
            totalClipsSent += batchClips.size
            totalInTokens += callResp.inputTokens
            totalOutTokens += callResp.outputTokens
            totalCachedTokens += callResp.cachedTokens
            totalLatencyMs += callResp.latencyMs

            allAuditedDtos.addAll(callResp.result.auditedProfiles)
            allCreatedDtos.addAll(callResp.result.createdProfiles)
            if (callResp.result.overallAuditSummary.isNotBlank()) {
                overallSummaries.add(callResp.result.overallAuditSummary.trim())
            }
        }

        // Record token usage in AnalysisRunEntity so the AI Usage Dashboard reflects the audit call
        runCatching {
            dao.insertAnalysisRun(
                AnalysisRunEntity(
                    sessionId = "voice_profile_audit_${UUID.randomUUID().toString().take(6)}",
                    model = usedModelLabel,
                    promptVersion = "${Prompts.PROMPT_VERSION}:VOICE_PROFILE_AUDIT",
                    inTokens = totalInTokens,
                    outTokens = totalOutTokens,
                    cachedTokens = totalCachedTokens,
                    latencyMs = totalLatencyMs,
                    ok = true
                )
            )
        }

        // Apply Gemini's audit & profile creation results to Speaker Roster and Room sessions
        onProgress("Applying Gemini Voice Profile audit & refinements...")
        val itemSummaries = mutableListOf<GeminiProfileAuditItemSummary>()
        var profilesRefinedCount = 0
        var mixedSeparatedCount = localOutliersSplit
        var newCreatedCount = 0
        val pendingSessionRenames = mutableListOf<Triple<String, String, SpeakerProfileEntry>>()

        synchronized(rosterLock) {
            val roster = prefs.getSpeakerRoster().toMutableList()

            for (audited in allAuditedDtos) {
                val rIdx = roster.indexOfFirst { it.id == audited.profileId }
                if (rIdx < 0) continue
                val existing = roster[rIdx]
                val prevName = existing.effectiveNickname()
                val prevTitle = existing.effectiveRelatableTitle()
                val prevSig = existing.voiceSignature.ifBlank { existing.voiceHint }

                var currentSamples = existing.existingSamples()
                var separatedThisProfile = false

                // 1. Handle mixed-speaker separation if Gemini detected multiple speakers in the profile
                val mismatchedIds = audited.mismatchedSampleIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
                if ((!audited.isSameSingleSpeaker || mismatchedIds.isNotEmpty()) && currentSamples.size >= 2) {
                    val toSplit = currentSamples.filter { it.id in mismatchedIds }
                        .ifEmpty { listOf(currentSamples.last()) }
                    val kept = currentSamples.filterNot { s -> toSplit.any { it.id == s.id } }
                    if (kept.isNotEmpty() && toSplit.isNotEmpty()) {
                        currentSamples = kept
                        separatedThisProfile = true
                        mixedSeparatedCount++

                        // Create a separate new Voice Profile for the split speaker sample(s) if Gemini provided metadata
                        val splitNick = audited.splitNewNickname?.trim()
                            ?.takeIf { it.isNotBlank() && !isGenericSpeakerPlaceholder(it) }
                        if (splitNick != null) {
                            val newSplitId = UUID.randomUUID().toString().take(8)
                            val splitTitle = audited.splitNewRelatableTitle?.trim()
                                ?.takeIf { it.isNotBlank() && !isGenericSpeakerPlaceholder(it) }
                                ?: prevTitle.ifBlank { "Conversation Partner" }
                            val splitSig = audited.splitNewVoiceSignature?.trim().orEmpty()
                            val migratedSplitSamples = toSplit.mapNotNull { s ->
                                val srcFile = File(s.filePath)
                                if (!srcFile.exists() || srcFile.length() < 1024L) return@mapNotNull null
                                val newSampleId = UUID.randomUUID().toString().take(8)
                                val destFile = prefs.getSpeakerSampleAudioFile(newSplitId, newSampleId)
                                val ok = runCatching {
                                    srcFile.copyTo(destFile, overwrite = true)
                                    destFile.setLastModified(s.createdAtMs)
                                    true
                                }.getOrDefault(false)
                                if (ok && destFile.exists()) {
                                    runCatching { srcFile.delete() }
                                    s.copy(id = newSampleId, filePath = destFile.absolutePath)
                                } else null
                            }
                            if (migratedSplitSamples.isNotEmpty()) {
                                val bestSplit = migratedSplitSamples.first()
                                val splitEntry = SpeakerProfileEntry(
                                    id = newSplitId,
                                    displayName = splitNick,
                                    nickname = splitNick,
                                    role = splitTitle,
                                    relatableTitle = splitTitle,
                                    voiceHint = splitSig,
                                    voiceSignature = splitSig,
                                    audioPath = bestSplit.filePath,
                                    isUser = false,
                                    isAutoDiscovered = true,
                                    isPinned = false,
                                    sessionsHeardCount = 1,
                                    lastHeardAtMs = bestSplit.createdAtMs,
                                    sampleDurationMs = bestSplit.durationMs,
                                    sourceSessionId = bestSplit.sourceSessionId,
                                    samples = migratedSplitSamples
                                )
                                roster.add(splitEntry)
                                newCreatedCount++
                                itemSummaries.add(
                                    GeminiProfileAuditItemSummary(
                                        profileId = newSplitId,
                                        previousName = "(Split from $prevName)",
                                        finalName = splitNick,
                                        previousTitle = prevTitle,
                                        finalTitle = splitTitle,
                                        previousSignature = "",
                                        finalSignature = splitSig,
                                        wasNameRefined = true,
                                        wasTitleRefined = true,
                                        wasDescriptionRefined = splitSig.isNotBlank(),
                                        wasSeparatedFromMixedSpeaker = true,
                                        isNewlyCreated = true,
                                        auditNotes = "Separated distinct speaker voice sample from '$prevName' into its own clean Voice Profile."
                                    )
                                )
                            }
                        } else {
                            toSplit.forEach { s -> runCatching { File(s.filePath).delete() } }
                        }
                    }
                }

                // 2. Recheck & apply Gemini-audited Name, Title, and Description
                val recNick = audited.recommendedNickname.trim()
                    .takeIf { it.isNotBlank() && !isGenericSpeakerPlaceholder(it) }
                val recTitle = audited.recommendedRelatableTitle.trim()
                    .takeIf { it.isNotBlank() && !isGenericSpeakerPlaceholder(it) }
                val recSig = audited.recommendedVoiceSignature.trim()
                    .takeIf { it.isNotBlank() }

                val userEditedAndSuitable = (!existing.isAutoDiscovered || existing.isPinned) &&
                    audited.nameSuitable && !isGenericSpeakerPlaceholder(prevName)
                val finalName = when {
                    userEditedAndSuitable -> prevName
                    recNick != null && (!audited.nameSuitable || isRoleOnlyOrSyntheticName(prevName)) -> recNick
                    recNick != null && prevName.isBlank() -> recNick
                    else -> prevName
                }
                val finalTitle = when {
                    (!existing.isAutoDiscovered || existing.isPinned) && audited.titleSuitable && prevTitle.isNotBlank() -> prevTitle
                    recTitle != null && (!audited.titleSuitable || prevTitle.isBlank() || prevTitle.equals("Conversation Partner", ignoreCase = true)) -> recTitle
                    recTitle != null -> recTitle
                    else -> prevTitle
                }
                val finalSig = when {
                    recSig != null && (!audited.descriptionSuitable || recSig.length > prevSig.length || prevSig.isBlank()) -> recSig
                    else -> prevSig
                }

                val nameChanged = !finalName.equals(prevName, ignoreCase = false)
                val titleChanged = !finalTitle.equals(prevTitle, ignoreCase = false)
                val sigChanged = !finalSig.equals(prevSig, ignoreCase = false)
                if (nameChanged || titleChanged || sigChanged || separatedThisProfile) {
                    profilesRefinedCount++
                }

                val updatedAliases = buildList {
                    addAll(existing.aliases)
                    if (!isRoleOnlyOrSyntheticName(prevName) && !prevName.equals(finalName, ignoreCase = true)) {
                        add(prevName)
                    }
                }.distinctBy { it.lowercase(Locale.US) }.take(6)

                val bestSample = currentSamples.firstOrNull()
                val updatedEntry = existing.copy(
                    displayName = finalName,
                    nickname = finalName,
                    role = finalTitle,
                    relatableTitle = finalTitle,
                    voiceHint = finalSig,
                    voiceSignature = finalSig,
                    samples = currentSamples,
                    audioPath = bestSample?.filePath ?: existing.audioPath,
                    sampleDurationMs = bestSample?.durationMs ?: existing.sampleDurationMs,
                    aliases = updatedAliases
                )
                roster[rIdx] = updatedEntry

                if (nameChanged || titleChanged || sigChanged) {
                    allSessions.forEach { sess ->
                        sess.speakers.filter { it.matchedProfileId == updatedEntry.id }.forEach { spk ->
                            pendingSessionRenames.add(Triple(sess.session.id, spk.speakerId, updatedEntry))
                        }
                    }
                }

                itemSummaries.add(
                    GeminiProfileAuditItemSummary(
                        profileId = updatedEntry.id,
                        previousName = prevName,
                        finalName = finalName,
                        previousTitle = prevTitle,
                        finalTitle = finalTitle,
                        previousSignature = prevSig,
                        finalSignature = finalSig,
                        wasNameRefined = nameChanged,
                        wasTitleRefined = titleChanged,
                        wasDescriptionRefined = sigChanged,
                        wasSeparatedFromMixedSpeaker = separatedThisProfile,
                        isNewlyCreated = false,
                        auditNotes = audited.auditNotes.ifBlank {
                            if (nameChanged || titleChanged || sigChanged) "Refined profile metadata for accuracy."
                            else "Verified single-speaker purity and suitable profile metadata."
                        }
                    )
                )
            }

            // 3. Process newly created Voice Profiles from top-pick unidentified candidate clips
            for (created in allCreatedDtos) {
                if (!created.isUsableSingleSpeaker) continue
                val staged = candidatesSnapshot.firstOrNull { it.id == created.candidateClipId } ?: continue
                val stagedFile = File(staged.filePath)
                if (!stagedFile.exists() || stagedFile.length() < 1024L) continue

                val cleanNick = created.nickname.trim().takeIf { it.isNotBlank() && !isGenericSpeakerPlaceholder(it) }
                    ?: "Clear-Tone Conversation Partner"
                val cleanTitle = created.relatableTitle.trim().takeIf { it.isNotBlank() && !isGenericSpeakerPlaceholder(it) }
                    ?: "Conversation Partner"
                val cleanSig = created.voiceSignature.trim()

                // Check if Gemini matched an existing profile AND on-device biometrics agree
                val matchPid = created.matchesExistingProfileId?.trim()
                    ?.takeIf { it.isNotBlank() && created.matchConfidence.equals("HIGH", ignoreCase = true) }
                val existingMatchIdx = matchPid?.let { pid -> roster.indexOfFirst { it.id == pid } } ?: -1

                var mergedIntoExisting = false
                if (existingMatchIdx >= 0) {
                    val target = roster[existingMatchIdx]
                    val targetEmbs = target.existingSamples().map { it.embedding }.filter { it.isNotEmpty() }
                    val (consistent, _) = SpeakerEmbeddingEngine.isConsistentWithProfile(
                        candidateEmbedding = staged.embedding,
                        existingEmbeddings = targetEmbs
                    )
                    if (consistent) {
                        val newSampleId = UUID.randomUUID().toString().take(8)
                        val destFile = prefs.getSpeakerSampleAudioFile(target.id, newSampleId)
                        val copied = runCatching {
                            stagedFile.copyTo(destFile, overwrite = true)
                            destFile.setLastModified(staged.recordedAtMs)
                            true
                        }.getOrDefault(false)
                        if (copied && destFile.exists()) {
                            val newSample = VoiceProfileSample(
                                id = newSampleId,
                                filePath = destFile.absolutePath,
                                durationMs = staged.durationMs,
                                qualityScore = staged.qualityScore,
                                snrDb = staged.snrDb,
                                speechRatio = staged.speechRatio,
                                createdAtMs = staged.recordedAtMs,
                                sourceSessionId = staged.sourceSessionId,
                                isManual = false,
                                embedding = staged.embedding
                            )
                            val (mergedSamples, _) = prefs.mergeAndTrimSamples(
                                existing = target.existingSamples(),
                                candidate = newSample,
                                maxSamples = PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE
                            )
                            val best = mergedSamples.firstOrNull()
                            roster[existingMatchIdx] = target.copy(
                                samples = mergedSamples,
                                audioPath = best?.filePath ?: target.audioPath,
                                sampleDurationMs = best?.durationMs ?: target.sampleDurationMs,
                                sessionsHeardCount = target.sessionsHeardCount + 1,
                                lastHeardAtMs = maxOf(target.lastHeardAtMs, staged.recordedAtMs)
                            )
                            mergedIntoExisting = true
                        }
                    }
                }

                if (!mergedIntoExisting) {
                    val newProfileId = UUID.randomUUID().toString().take(8)
                    val newSampleId = UUID.randomUUID().toString().take(8)
                    val destFile = prefs.getSpeakerSampleAudioFile(newProfileId, newSampleId)
                    val copied = runCatching {
                        stagedFile.copyTo(destFile, overwrite = true)
                        destFile.setLastModified(staged.recordedAtMs)
                        true
                    }.getOrDefault(false)
                    if (copied && destFile.exists()) {
                        val sample = VoiceProfileSample(
                            id = newSampleId,
                            filePath = destFile.absolutePath,
                            durationMs = staged.durationMs,
                            qualityScore = staged.qualityScore,
                            snrDb = staged.snrDb,
                            speechRatio = staged.speechRatio,
                            createdAtMs = staged.recordedAtMs,
                            sourceSessionId = staged.sourceSessionId,
                            isManual = false,
                            embedding = staged.embedding
                        )
                        val newEntry = SpeakerProfileEntry(
                            id = newProfileId,
                            displayName = cleanNick,
                            nickname = cleanNick,
                            role = cleanTitle,
                            relatableTitle = cleanTitle,
                            voiceHint = cleanSig,
                            voiceSignature = cleanSig,
                            audioPath = sample.filePath,
                            isUser = false,
                            isAutoDiscovered = true,
                            isPinned = false,
                            sessionsHeardCount = 1,
                            lastHeardAtMs = staged.recordedAtMs,
                            sampleDurationMs = sample.durationMs,
                            sourceSessionId = staged.sourceSessionId,
                            samples = listOf(sample)
                        )
                        roster.add(newEntry)
                        newCreatedCount++
                        itemSummaries.add(
                            GeminiProfileAuditItemSummary(
                                profileId = newProfileId,
                                previousName = "(Unidentified Clip • ${staged.sourceTitle})",
                                finalName = cleanNick,
                                previousTitle = "",
                                finalTitle = cleanTitle,
                                previousSignature = "",
                                finalSignature = cleanSig,
                                wasNameRefined = true,
                                wasTitleRefined = true,
                                wasDescriptionRefined = true,
                                wasSeparatedFromMixedSpeaker = false,
                                isNewlyCreated = true,
                                auditNotes = created.auditNotes.ifBlank { "Created new Voice Profile from top-pick old recording audio." }
                            )
                        )
                    }
                }
            }

            pruneExcessAutoProfiles(prefs, roster)
            prefs.saveSpeakerRoster(roster)
        }

        clearStagedTopPickCandidates(appContext)

        // Propagate Gemini-refined speaker labels into Room sessions
        for ((sessId, spkId, updatedEntry) in pendingSessionRenames) {
            runCatching {
                dao.renameSpeakerProfileAcrossSession(
                    sessionId = sessId,
                    speakerId = spkId,
                    newLabel = updatedEntry.formattedLabel(),
                    matchedProfileId = updatedEntry.id,
                    nickname = updatedEntry.effectiveNickname(),
                    relatableTitle = updatedEntry.effectiveRelatableTitle().ifBlank { null },
                    voiceSignature = updatedEntry.voiceSignature.ifBlank { null },
                    isAutoEnrolled = updatedEntry.isAutoDiscovered
                )
            }
        }

        val combinedSummaryText = overallSummaries.joinToString(" ").ifBlank {
            "Audited ${allAuditedDtos.size} profile(s) with $totalClipsSent top-pick audio sample(s): " +
                "$profilesRefinedCount refined, $newCreatedCount created, $mixedSeparatedCount mixed-speaker sample(s) separated."
        }

        GeminiVoiceProfileAuditSummary(
            profilesAudited = allAuditedDtos.size,
            topPickAudiosSent = totalClipsSent,
            profilesRefined = profilesRefinedCount,
            newProfilesCreated = newCreatedCount,
            mixedProfilesSeparated = mixedSeparatedCount,
            modelUsed = usedModelLabel,
            overallAuditSummary = combinedSummaryText,
            items = itemSummaries
        )
    }

    private suspend fun executeAuditCallWithKeyPool(
        prefs: PreferencesRepo,
        client: GeminiInteractionsClient,
        systemInstruction: String,
        auditPromptText: String,
        audioClips: List<VoiceProfileAuditAudioClip>
    ): Pair<GeminiVoiceProfileAuditCallResponse, String> {
        if (prefs.isGeminiUsagePausedNow()) {
            throw IllegalStateException("Gemini AI is currently paused. Resume Gemini in Settings or the top bar to run Voice Profile Audit.")
        }
        val orderedPool = prefs.getAllApiKeys()
        if (orderedPool.isEmpty()) {
            throw IllegalStateException("No Gemini API key configured in Settings.")
        }
        val estTokens = GeminiRateLimitManager.estimateInputTokens(
            systemInstruction = systemInstruction,
            promptText = auditPromptText,
            audioFiles = audioClips.map { it.file }
        )
        val primaryModel = prefs.geminiModel
        val fallbackModel = prefs.fallbackGeminiModel
        val hasFallback = fallbackModel != PreferencesRepo.FALLBACK_MODEL_NONE &&
            !fallbackModel.equals(primaryModel, ignoreCase = true)
        val modelsToTry = if (hasFallback) listOf(primaryModel, fallbackModel) else listOf(primaryModel)

        var lastErr: Exception? = null
        for ((modelIdx, activeModel) in modelsToTry.withIndex()) {
            val hasNextFallback = modelIdx == 0 && hasFallback
            val strategy = GeminiRateLimitManager.planRequestStrategy(
                prefs = prefs,
                model = activeModel,
                estimatedInputTokens = estTokens,
                hasFallbackModel = hasNextFallback
            )
            if (hasNextFallback && strategy.recommendProactiveFallback) {
                continue
            }

            val keysToTry = strategy.orderedKeys
            val strictModel = modelIdx == 0 && hasFallback
            var sawQuotaBillingOrUnavailable = false
            var sawNetworkOffline = false

            for ((attemptIdx, keyCandidate) in keysToTry.withIndex()) {
                if (prefs.isGeminiUsagePausedNow()) {
                    throw IllegalStateException("Gemini AI was paused during Voice Profile Audit.")
                }
                val slot = orderedPool.indexOf(keyCandidate).let { if (it >= 0) it + 1 else attemptIdx + 1 }
                try {
                    GeminiRateLimitManager.acquireSlotBeforeCall(
                        prefs = prefs,
                        apiKey = keyCandidate,
                        model = activeModel,
                        estimatedInputTokens = estTokens
                    )
                    val resp = client.auditAndCreateVoiceProfiles(
                        apiKey = keyCandidate,
                        model = activeModel,
                        systemInstruction = systemInstruction,
                        auditPromptText = auditPromptText,
                        topPickAudioClips = audioClips,
                        strictModelOnly = strictModel
                    )
                    val resolvedModel = resp.actualModel.ifBlank { activeModel }
                    prefs.recordApiKeySuccess(keyCandidate, resolvedModel)
                    GeminiRateLimitManager.recordActualTokensCompleted(
                        prefs = prefs,
                        apiKey = keyCandidate,
                        model = resolvedModel,
                        actualInputTokens = resp.inputTokens
                    )
                    val fallbackTag = if (modelIdx > 0) " (fallback)" else ""
                    return resp to "$resolvedModel$fallbackTag:key-pool#$slot"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e
                    val cls = GeminiInteractionsClient.classifyApiFailure(e)
                    val retryAfterMs = if (cls.isQuotaOrBilling) {
                        GeminiRateLimitManager.learnFrom429Response(
                            prefs = prefs,
                            apiKey = keyCandidate,
                            model = activeModel,
                            errorMessage = e.message.orEmpty()
                        )
                    } else null
                    prefs.recordApiKeyFailure(
                        key = keyCandidate,
                        model = activeModel,
                        failureCategory = cls.category,
                        errorMsg = e.message ?: cls.category,
                        retryAfterMs = retryAfterMs
                    )
                    if (cls.isQuotaOrBilling || cls.isModelUnavailable) {
                        sawQuotaBillingOrUnavailable = true
                    }
                    if (cls.isNetworkTransient && !cls.shouldFailoverKey) {
                        sawNetworkOffline = true
                        break
                    }
                    if (cls.isModelUnavailable) {
                        break
                    }
                }
            }
            if (modelIdx == 0 && hasFallback && sawQuotaBillingOrUnavailable && !sawNetworkOffline) {
                continue
            } else {
                break
            }
        }
        throw lastErr ?: IllegalStateException("All saved API keys in pool failed during Voice Profile Audit.")
    }

    private data class UnlabeledScanResult(
        val matchedEntities: List<SessionSpeakerEntity>,
        val samplesAddedOrUpgraded: Int
    )

    private fun extractKnownVoicesFromUnlabeledAudio(
        context: Context,
        prefs: PreferencesRepo,
        audioFile: File,
        totalDurationMs: Long,
        segments: List<SegmentMapEntity>,
        sessionId: String?,
        sourceStartMs: Long,
        sourceTitle: String = audioFile.name,
        sourceContextHint: String = "OTHER"
    ): UnlabeledScanResult {
        ensureSampleEmbeddingsBackfilled(context, prefs, maxToCompute = 20)
        val mySamples = prefs.getMyVoiceSamples()
        val rosterSnapshot = prefs.getSpeakerRoster()
        if (totalDurationMs < 2_500L) {
            return UnlabeledScanResult(emptyList(), 0)
        }

        val windowMs = 6_500L
        val count = when {
            totalDurationMs <= 12_000L -> 1
            totalDurationMs <= 30_000L -> 3
            totalDurationMs <= 90_000L -> 6
            totalDurationMs <= 240_000L -> 9
            else -> 12
        }
        val evalDir = File(
            context.cacheDir,
            "voice_unlabeled_scan/${sessionId ?: audioFile.nameWithoutExtension}_${UUID.randomUUID().toString().take(6)}"
        ).apply { mkdirs() }

        val candidatesByProfile = LinkedHashMap<String, MutableList<Pair<VoiceprintMatchResult, EvaluatedCandidateClip>>>()
        var bestUnmatchedClip: EvaluatedCandidateClip? = null
        val seg = SliceSourceSegment(
            file = audioFile,
            globalOffsetMs = 0L,
            durationMs = totalDurationMs,
            segments = segments,
            sourceStartMs = sourceStartMs
        )

        try {
            val stepMs = if (count <= 1) 0L else ((totalDurationMs - windowMs).coerceAtLeast(1_000L) / (count - 1))
            for (i in 0 until count) {
                val winStart = (i * stepMs).coerceAtLeast(0L)
                val winEnd = (winStart + windowMs).coerceAtMost(totalDurationMs)
                if (winEnd - winStart < 2_500L) continue

                val tmpFile = File(evalDir, "win_${i}.aac")
                val sliced = ClipSlicer.sliceAdtsVoiceSample(
                    rawAdtsFile = audioFile,
                    outFile = tmpFile,
                    startMs = winStart,
                    endMs = winEnd,
                    minMs = 3_000L,
                    maxMs = 7_500L
                ) ?: continue

                val metrics = ClipSlicer.analyzeAdtsSampleQuality(sliced.file)
                if (!metrics.isUsable || metrics.hasSuspectedCrosstalk || metrics.score < MIN_UNLABELED_WINDOW_QUALITY_SCORE) {
                    runCatching { tmpFile.delete() }
                    continue
                }

                val emb = SpeakerEmbeddingEngine.extractEmbeddingFromFile(context, sliced.file, maxSeconds = 8)
                if (emb.isEmpty()) {
                    runCatching { tmpFile.delete() }
                    continue
                }

                val recordedAtMs = resolveSegmentWallClockMs(seg, winStart, sourceStartMs)
                val cand = EvaluatedCandidateClip(
                    tempFile = sliced.file,
                    evalDir = evalDir,
                    durationMs = sliced.durationMs,
                    metrics = metrics,
                    embedding = emb,
                    recordedAtMs = recordedAtMs
                )

                val match = SpeakerEmbeddingEngine.findBestVoiceprintMatch(
                    candidateEmbedding = emb,
                    myVoiceSamples = mySamples,
                    roster = rosterSnapshot,
                    minSelfSimilarity = SpeakerEmbeddingEngine.UNLABELED_HIGH_CONFIDENCE_THRESHOLD,
                    minRosterSimilarity = SpeakerEmbeddingEngine.UNLABELED_HIGH_CONFIDENCE_THRESHOLD,
                    minMargin = 0.05f
                )
                if (match == null) {
                    // Retain the single highest-quality unmatched clip from this recording for Gemini Voice Profile creation
                    if (cand.metrics.score >= 54f &&
                        (bestUnmatchedClip == null || cand.metrics.score > bestUnmatchedClip!!.metrics.score)
                    ) {
                        bestUnmatchedClip?.tempFile?.let { old -> runCatching { old.delete() } }
                        bestUnmatchedClip = cand
                    } else {
                        runCatching { tmpFile.delete() }
                    }
                    continue
                }

                val listForProfile = candidatesByProfile.getOrPut(match.profileId) { mutableListOf() }
                listForProfile.add(match to cand)
            }

            // Stage best unmatched clip for optional Gemini Top-Pick Voice Profile Creation
            bestUnmatchedClip?.let { unmatched ->
                stageUnidentifiedTopPickCandidate(
                    context = context,
                    candidate = unmatched,
                    sessionId = sessionId,
                    sourceTitle = sourceTitle,
                    sourceContextHint = sourceContextHint
                )
            }

            if (candidatesByProfile.isEmpty()) {
                return UnlabeledScanResult(emptyList(), 0)
            }

            var samplesAdded = 0
            val matchedEntities = mutableListOf<SessionSpeakerEntity>()
            val userNick = prefs.userDisplayName.ifBlank { "Dr. (Me)" }

            synchronized(rosterLock) {
                val roster = prefs.getSpeakerRoster().toMutableList()
                var rosterChanged = false
                var spkCounter = 1

                candidatesByProfile.forEach { (profileId, pairs) ->
                    val sortedPairs = pairs.sortedByDescending { (m, c) -> c.metrics.score + m.similarity * 20f }
                        .take(3)
                    val firstMatch = sortedPairs.first().first

                    if (firstMatch.isSelfUser) {
                        for ((_, cand) in sortedPairs) {
                            val existingMyEmbs = prefs.getMyVoiceSamples().map { it.embedding }.filter { it.isNotEmpty() }
                            val (isConsistent, _) = SpeakerEmbeddingEngine.isConsistentWithProfile(cand.embedding, existingMyEmbs)
                            if (isConsistent) {
                                val committed = commitEvaluatedCandidateToProfile(
                                    prefs = prefs,
                                    candidate = cand,
                                    profileId = "me",
                                    isSelfUser = true,
                                    sessionId = sessionId,
                                    isManual = false
                                )
                                if (committed != null) {
                                    val (accepted, _) = prefs.addMyVoiceSample(committed)
                                    if (accepted) samplesAdded++
                                }
                            }
                        }
                        if (!sessionId.isNullOrBlank()) {
                            val label = buildFormattedSpeakerLabel(userNick, "Self / Device Owner")
                            matchedEntities.add(
                                SessionSpeakerEntity(
                                    sessionId = sessionId,
                                    speakerId = "SPK_${spkCounter++}",
                                    speakerLabel = label,
                                    inferredRole = "Self / Device Owner",
                                    isUser = true,
                                    matchedProfileId = "me",
                                    nickname = userNick,
                                    relatableTitle = "Self / Device Owner",
                                    voiceSignature = null,
                                    isAutoEnrolled = false
                                )
                            )
                        }
                    } else {
                        val rIdx = roster.indexOfFirst { it.id == profileId }
                        if (rIdx >= 0) {
                            val entry = roster[rIdx]
                            var updatedSamples = entry.existingSamples()
                            var latestRecMs = entry.lastHeardAtMs
                            for ((_, cand) in sortedPairs) {
                                val existingEmbs = updatedSamples.map { it.embedding }.filter { it.isNotEmpty() }
                                val (isConsistent, _) = SpeakerEmbeddingEngine.isConsistentWithProfile(cand.embedding, existingEmbs)
                                if (isConsistent) {
                                    val committed = commitEvaluatedCandidateToProfile(
                                        prefs = prefs,
                                        candidate = cand,
                                        profileId = entry.id,
                                        isSelfUser = false,
                                        sessionId = sessionId,
                                        isManual = false
                                    )
                                    if (committed != null) {
                                        val (merged, accepted) = prefs.mergeAndTrimSamples(
                                            existing = updatedSamples,
                                            candidate = committed,
                                            maxSamples = PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE
                                        )
                                        updatedSamples = merged
                                        if (accepted) samplesAdded++
                                    }
                                    latestRecMs = maxOf(latestRecMs, cand.recordedAtMs)
                                }
                            }
                            val best = updatedSamples.firstOrNull()
                            val newCount = if (!sessionId.isNullOrBlank() && entry.sourceSessionId != sessionId) {
                                entry.sessionsHeardCount + 1
                            } else {
                                entry.sessionsHeardCount
                            }
                            roster[rIdx] = entry.copy(
                                samples = updatedSamples,
                                audioPath = best?.filePath ?: entry.audioPath,
                                sampleDurationMs = best?.durationMs ?: entry.sampleDurationMs,
                                sessionsHeardCount = newCount,
                                lastHeardAtMs = latestRecMs,
                                sourceSessionId = sessionId ?: entry.sourceSessionId
                            )
                            rosterChanged = true

                            if (!sessionId.isNullOrBlank()) {
                                matchedEntities.add(
                                    SessionSpeakerEntity(
                                        sessionId = sessionId,
                                        speakerId = "SPK_${spkCounter++}",
                                        speakerLabel = entry.formattedLabel(),
                                        inferredRole = entry.effectiveRelatableTitle().ifBlank { null },
                                        isUser = false,
                                        matchedProfileId = entry.id,
                                        nickname = entry.effectiveNickname(),
                                        relatableTitle = entry.effectiveRelatableTitle().ifBlank { null },
                                        voiceSignature = entry.voiceSignature.ifBlank { null },
                                        isAutoEnrolled = entry.isAutoDiscovered
                                    )
                                )
                            }
                        }
                    }
                }

                if (rosterChanged) {
                    prefs.saveSpeakerRoster(roster)
                }
            }

            return UnlabeledScanResult(matchedEntities, samplesAdded)
        } finally {
            runCatching { evalDir.deleteRecursively() }
        }
    }

    private fun formatMsToMmSs(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) / 1000L)
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}

