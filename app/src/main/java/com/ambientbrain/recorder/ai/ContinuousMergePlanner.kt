package com.ambientbrain.recorder.ai

import com.ambientbrain.recorder.data.SessionEntity
import com.ambientbrain.recorder.data.SessionWithDetails
import com.ambientbrain.recorder.data.Status
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ContinuousCluster(
    val clusterId: String,
    val sessions: List<SessionWithDetails>,
    val startMs: Long,
    val endMs: Long,
    val totalSpeechMs: Long,
    val rawAudioCount: Int,
    val extractedInfoCount: Int,
    val sharedGroupReason: String,
    val existingCombinedSessionId: String?,
    val needsMerge: Boolean,
    val titlePreview: String
) {
    val sessionIds: List<String> get() = sessions.map { it.session.id }
    val hasAnyRawAudio: Boolean get() = rawAudioCount > 0
    val hasAnyExtractedInfo: Boolean get() = extractedInfoCount > 0
}

/**
 * Deterministic planner that identifies chronologically related continuous audio recordings
 * (consecutive segments split by silence/timeout, shared event/round groups, or overlapping speakers/patients)
 * and tracks whether a unified merged session already covers them.
 */
object ContinuousMergePlanner {

    private val json = Json { ignoreUnknownKeys = true }

    fun hasRawAudio(s: SessionEntity): Boolean {
        if (s.isRawPurged || s.rawPath.isNullOrBlank()) return false
        val f = File(s.rawPath)
        return f.exists() && f.length() > 0L
    }

    fun hasExtractedInfo(d: SessionWithDetails): Boolean {
        val s = d.session
        val summaryOk = !s.summary.isNullOrBlank() &&
            s.summary != "100% verbatim transcript completed" &&
            s.context != "NOISE_OR_TV"
        return summaryOk || d.sentences.isNotEmpty() || d.patients.isNotEmpty() || d.items.isNotEmpty()
    }

    fun detectClusters(
        allSessions: List<SessionWithDetails>,
        gapMinutes: Int = 15
    ): List<ContinuousCluster> {
        val combinedSessions = allSessions.map { it.session }.filter { !it.combinedSourceIdsJson.isNullOrBlank() }
        val baseSessions = allSessions.filter { d ->
            val s = d.session
            s.combinedSourceIdsJson.isNullOrBlank() &&
                s.context != "NOISE_OR_TV" &&
                s.status != Status.NOT_MEANINGFUL &&
                s.status != Status.SKIPPED_NO_SPEECH &&
                s.status != Status.CAPTURING &&
                (hasRawAudio(s) || hasExtractedInfo(d))
        }.sortedBy { it.session.startMs }

        return buildClustersFromSortedBases(baseSessions, combinedSessions, gapMinutes)
    }

    fun detectClusters(
        individualSessions: List<SessionWithDetails>,
        combinedSessions: List<SessionEntity>,
        gapMinutes: Int = 15
    ): List<ContinuousCluster> {
        val sortedBases = individualSessions.filter { d ->
            val s = d.session
            s.combinedSourceIdsJson.isNullOrBlank() &&
                s.context != "NOISE_OR_TV" &&
                s.status != Status.NOT_MEANINGFUL &&
                s.status != Status.SKIPPED_NO_SPEECH &&
                s.status != Status.CAPTURING &&
                (hasRawAudio(s) || hasExtractedInfo(d))
        }.sortedBy { it.session.startMs }

        return buildClustersFromSortedBases(sortedBases, combinedSessions, gapMinutes)
    }

    private fun buildClustersFromSortedBases(
        sortedBases: List<SessionWithDetails>,
        combinedSessions: List<SessionEntity>,
        gapMinutes: Int
    ): List<ContinuousCluster> {
        if (sortedBases.size < 2) return emptyList()

        val maxGapMs = TimeUnit.MINUTES.toMillis(gapMinutes.coerceIn(5, 60).toLong())
        val extendedTopicGapMs = maxGapMs * 2L
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        // Pre-parse combined source IDs
        val parsedCombined: List<Pair<SessionEntity, Set<String>>> = combinedSessions.mapNotNull { cs ->
            val ids = cs.combinedSourceIdsJson?.let { raw ->
                runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
            }?.toSet()
            if (ids.isNullOrEmpty()) null else cs to ids
        }

        val groups = mutableListOf<MutableList<SessionWithDetails>>()
        var currentGroup = mutableListOf(sortedBases.first())

        for (i in 1 until sortedBases.size) {
            val prev = currentGroup.last()
            val next = sortedBases[i]
            val gapMs = (next.session.startMs - prev.session.endMs).coerceAtLeast(0L)

            val sameEventGroup = !prev.session.eventGroupId.isNullOrBlank() &&
                prev.session.eventGroupId == next.session.eventGroupId
            val sameRoundGroup = !prev.session.roundGroupId.isNullOrBlank() &&
                prev.session.roundGroupId == next.session.roundGroupId
            val shortGap = gapMs <= maxGapMs

            val sharedTopicOrSpeaker = gapMs <= extendedTopicGapMs &&
                sharesNamedSpeakerOrPatient(prev, next)

            if (sameEventGroup || sameRoundGroup || shortGap || sharedTopicOrSpeaker) {
                currentGroup.add(next)
            } else {
                if (currentGroup.size >= 2) groups.add(currentGroup)
                currentGroup = mutableListOf(next)
            }
        }
        if (currentGroup.size >= 2) groups.add(currentGroup)

        return groups.map { group ->
            val idsSet = group.map { it.session.id }.toSet()
            val exactOrSupersetDone = parsedCombined.firstOrNull { (cs, cIds) ->
                cIds.containsAll(idsSet) &&
                    cs.status in setOf(Status.DONE, Status.QUEUED, Status.ANALYZING, Status.CLIPPING, Status.MERGING)
            }
            val partialOrExisting = exactOrSupersetDone ?: parsedCombined
                .filter { (_, cIds) -> cIds.intersect(idsSet).size >= 2 || (cIds.intersect(idsSet).isNotEmpty() && idsSet.size >= 2) }
                .maxByOrNull { (_, cIds) -> cIds.intersect(idsSet).size }

            val startMs = group.minOf { it.session.startMs }
            val endMs = group.maxOf { it.session.endMs }
            val speechMs = group.sumOf {
                if (it.session.speechMs > 0L) it.session.speechMs else (it.session.endMs - it.session.startMs).coerceAtLeast(1_000L)
            }
            val rawCount = group.count { hasRawAudio(it.session) }
            val extractedCount = group.count { hasExtractedInfo(it) }

            val spanStr = "${timeFmt.format(Date(startMs))}–${timeFmt.format(Date(endMs))}"
            val hasRound = group.any { !it.session.roundGroupId.isNullOrBlank() || it.patients.isNotEmpty() }
            val reason = when {
                hasRound -> "${group.size} continuous segments • $spanStr • Shared Ward Round"
                group.mapNotNull { it.session.eventGroupId }.distinct().size == 1 ->
                    "${group.size} continuous segments • $spanStr • Linked Event"
                else -> "${group.size} consecutive files • $spanStr (gap ≤ ${gapMinutes}m)"
            }
            val previewTitle = group.firstOrNull {
                !it.session.title.isNullOrBlank() && !it.session.title!!.startsWith("Combined")
            }?.session?.title ?: "Continuous Event ($spanStr)"

            val clusterKey = group.joinToString("_") { it.session.id.take(6) }
            ContinuousCluster(
                clusterId = "cluster_${clusterKey.hashCode().toUInt()}",
                sessions = group,
                startMs = startMs,
                endMs = endMs,
                totalSpeechMs = speechMs,
                rawAudioCount = rawCount,
                extractedInfoCount = extractedCount,
                sharedGroupReason = reason,
                existingCombinedSessionId = partialOrExisting?.first?.id,
                needsMerge = exactOrSupersetDone == null,
                titlePreview = previewTitle
            )
        }.sortedByDescending { it.endMs }
    }

    private fun sharesNamedSpeakerOrPatient(a: SessionWithDetails, b: SessionWithDetails): Boolean {
        val patA = a.patients.map { it.patientIdentifier.lowercase(Locale.US).trim() }.filter { it.length >= 2 }.toSet()
        val patB = b.patients.map { it.patientIdentifier.lowercase(Locale.US).trim() }.filter { it.length >= 2 }.toSet()
        if (patA.isNotEmpty() && patB.isNotEmpty() && patA.intersect(patB).isNotEmpty()) {
            return true
        }
        val spkA = a.speakers.map { it.speakerLabel.lowercase(Locale.US).trim() }
            .filter { !it.startsWith("speaker ") && it.length >= 3 }.toSet()
        val spkB = b.speakers.map { it.speakerLabel.lowercase(Locale.US).trim() }
            .filter { !it.startsWith("speaker ") && it.length >= 3 }.toSet()
        return spkA.isNotEmpty() && spkB.isNotEmpty() && spkA.intersect(spkB).isNotEmpty()
    }
}
