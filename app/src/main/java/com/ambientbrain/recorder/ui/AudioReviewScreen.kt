package com.ambientbrain.recorder.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Speed
import com.ambientbrain.recorder.ai.ContinuousMergePlanner
import com.ambientbrain.recorder.ai.ReviewSourceMode
import com.ambientbrain.recorder.ai.VoiceProfileAutoManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ambientbrain.recorder.ai.AnalysisMode
import com.ambientbrain.recorder.ai.SessionAnalyzeWorker
import com.ambientbrain.recorder.data.ClipSlicer
import com.ambientbrain.recorder.data.ItemEntity
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SessionWithDetails
import com.ambientbrain.recorder.data.Status
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

enum class AudioVaultFilter(val label: String) {
    ALL("All"),
    MEANINGFUL("🗣️ Transcribed"),
    AMBIENT_NOISE("🔇 Ambient"),
    SESSIONS("🎙️ Sessions"),
    PROOFS("📌 Proofs"),
    OFFLINE("☁️ Offline Pending"),
    PINNED("🔒 Pinned")
}

enum class AudioDateFilter(val label: String) {
    ALL("All Dates"),
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_7_DAYS("7 Days"),
    CUSTOM_DATE("Pick Date")
}

enum class AudioVaultSort(val label: String) {
    NEWEST("Newest First"),
    OLDEST("Oldest First"),
    LARGEST("Largest Size"),
    LONGEST("Longest Duration")
}

data class AudioVaultItem(
    val id: String,
    val filePath: String,
    val fileName: String,
    val title: String,
    val subtitle: String,
    val timestampMs: Long,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val format: String, // "AAC" or "M4A"
    val isSession: Boolean,
    val isVoiceProof: Boolean,
    val isOfflinePending: Boolean,
    val isPinned: Boolean,
    val isMeaningful: Boolean,
    val hasTranscription: Boolean,
    val status: Status?,
    val retryCount: Int = 0,
    val nextRetryEpochMs: Long = 0L,
    val lastError: String? = null,
    val speakerLabel: String? = null,
    val quoteOriginal: String? = null,
    val patientId: String? = null,
    val associatedSessionId: String? = null,
    val associatedItemId: String? = null,
    val isCombined: Boolean = false,
    val isLinkedEvent: Boolean = false,
    val hasAiReview: Boolean = false,
    val reviewSourceMode: String? = null,
    val rawFileExists: Boolean = true
)

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / 1024.0)
        else -> "$bytes B"
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AudioReviewScreen(
    allSessions: List<SessionWithDetails>,
    activeItems: List<ItemEntity>,
    playbackController: AudioPlaybackController,
    onTogglePinSession: (String, Boolean) -> Unit,
    onReanalyzeSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRunStorageCleaner: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepo(context) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(AudioVaultFilter.ALL) }
    var selectedDateFilter by remember { mutableStateOf(AudioDateFilter.ALL) }
    var customSelectedLocalDate by remember { mutableStateOf<LocalDate?>(null) }
    var showDatePickerDialog by remember { mutableStateOf(false) }
    var selectedSort by remember { mutableStateOf(AudioVaultSort.NEWEST) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var extractingVoiceItemId by remember { mutableStateOf<String?>(null) }
    var vaultRefreshNonce by remember { mutableIntStateOf(0) }

    var itemPendingDelete by remember { mutableStateOf<AudioVaultItem?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var viewingTranscriptSessionId by remember { mutableStateOf<String?>(null) }
    var isSelectMode by remember { mutableStateOf(false) }
    var selectedSessionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingReviewRequest by remember { mutableStateOf<ReviewTargetRequest?>(null) }

    BackHandler(enabled = showDatePickerDialog) {
        showDatePickerDialog = false
    }
    BackHandler(enabled = pendingReviewRequest != null && !showDatePickerDialog) {
        pendingReviewRequest = null
    }
    BackHandler(enabled = viewingTranscriptSessionId != null && pendingReviewRequest == null && !showDatePickerDialog) {
        viewingTranscriptSessionId = null
    }
    BackHandler(enabled = (itemPendingDelete != null || showBatchDeleteConfirm) && viewingTranscriptSessionId == null && pendingReviewRequest == null && !showDatePickerDialog) {
        itemPendingDelete = null
        showBatchDeleteConfirm = false
    }
    BackHandler(enabled = isSelectMode && viewingTranscriptSessionId == null && itemPendingDelete == null && !showBatchDeleteConfirm && pendingReviewRequest == null && !showDatePickerDialog) {
        isSelectMode = false
        selectedSessionIds = emptySet()
    }

    val continuousClusters = remember(allSessions, prefs.autoMergeGapMinutes) {
        ContinuousMergePlanner.detectClusters(allSessions, prefs.autoMergeGapMinutes)
    }

    // Aggregate all physical audio recordings & merged virtual records from Room DB and disk files
    val allAudioItems = remember(allSessions, activeItems, vaultRefreshNonce) {
        val list = mutableListOf<AudioVaultItem>()
        val seenPaths = mutableSetOf<String>()
        val sessionsById = allSessions.associate { it.session.id to it.session }

        // 1. Sessions (physical .aac sessions + merged/auto-merged continuous events)
        allSessions.forEach { d ->
            val s = d.session
            val isCombined = !s.combinedSourceIdsJson.isNullOrBlank()
            val path = s.rawPath
            val f = path?.let { File(it) }
            val fileExists = f != null && f.exists() && f.length() > 0L
            if (fileExists && !isCombined) {
                seenPaths.add(f!!.absolutePath)
            }
            val hasAnyExtracted = ContinuousMergePlanner.hasExtractedInfo(d)
            if (fileExists || isCombined || hasAnyExtracted) {
                val isOffline = s.status == Status.FAILED_RETRYABLE || s.status == Status.FAILED_PERMANENT || (!s.lastError.isNullOrBlank() && s.status != Status.DONE)
                val dur = if (s.speechMs > 0L) s.speechMs else (s.endMs - s.startMs).coerceAtLeast(1_000L)
                val hasAnyResult = d.sentences.isNotEmpty() || d.items.isNotEmpty() || !s.summary.isNullOrBlank()
                val isNoiseOrAmbient = s.context == "NOISE_OR_TV" ||
                    s.summary == "Filtered Background Audio" ||
                    s.status == Status.NOT_MEANINGFUL ||
                    s.title?.contains("Ambient / Unclassified", ignoreCase = true) == true ||
                    (s.status == Status.DONE && !hasAnyResult)

                val hasMeaningful = !isNoiseOrAmbient && (
                    d.sentences.isNotEmpty() ||
                        d.items.isNotEmpty() ||
                        (s.status == Status.DONE && !s.summary.isNullOrBlank()) ||
                        s.context in setOf("NEURO_ROUND", "MEDICAL_ROUND", "CASE_CONFERENCE", "HANDOVER", "EVERYDAY", "CALL")
                    )

                list.add(
                    AudioVaultItem(
                        id = "session_${s.id}",
                        filePath = if (fileExists) f!!.absolutePath else (path ?: "virtual_${s.id}"),
                        fileName = if (fileExists) f!!.name else "merged_${s.id}.aac",
                        title = s.title ?: "Session Audio (${s.context ?: s.mode.name})",
                        subtitle = s.summary ?: "Full raw ambient recording • ${s.context ?: s.mode.name}",
                        timestampMs = s.startMs,
                        durationMs = dur,
                        fileSizeBytes = if (fileExists) f!!.length() else s.rawBytes,
                        format = if (isCombined) "MERGED" else "AAC",
                        isSession = true,
                        isVoiceProof = false,
                        isOfflinePending = isOffline,
                        isPinned = s.isPinned,
                        isMeaningful = hasMeaningful,
                        hasTranscription = d.sentences.isNotEmpty() || d.items.isNotEmpty(),
                        status = s.status,
                        retryCount = s.retryCount,
                        nextRetryEpochMs = s.nextRetryEpochMs,
                        lastError = s.lastError,
                        associatedSessionId = s.id,
                        isCombined = isCombined,
                        isLinkedEvent = !isCombined && !s.eventGroupId.isNullOrBlank() && s.eventGroupId != s.id,
                        hasAiReview = s.reviewedAtMs > 0L || !s.aiReviewReport.isNullOrBlank(),
                        reviewSourceMode = s.reviewSourceMode,
                        rawFileExists = fileExists
                    )
                )
            }
        }

        // 2. Permanent Voice Proof clips (.m4a) — derive true recorded timestamp from parent session
        // (heals pre-existing historical items whose createdMs was AI-analysis time rather than recording time)
        activeItems.forEach { item ->
            val path = item.clipPath
            if (!path.isNullOrBlank()) {
                val f = File(path)
                if (f.exists() && f.length() > 0L) {
                    seenPaths.add(f.absolutePath)
                    val dur = (item.clipEndMs - item.clipStartMs).coerceAtLeast(1_000L)
                    val parentSession = sessionsById[item.sessionId]
                    val recordedTimestampMs = if (parentSession != null && parentSession.startMs > 0L) {
                        val upperBound = (parentSession.endMs + 60_000L).coerceAtLeast(parentSession.startMs)
                        if (item.createdMs in parentSession.startMs..upperBound) {
                            item.createdMs
                        } else {
                            (parentSession.startMs + item.clipStartMs.coerceAtLeast(0L))
                                .coerceIn(parentSession.startMs, upperBound)
                        }
                    } else {
                        item.createdMs
                    }
                    list.add(
                        AudioVaultItem(
                            id = "item_${item.id}",
                            filePath = f.absolutePath,
                            fileName = f.name,
                            title = item.title,
                            subtitle = "${item.speakerLabel} • ${item.type.name}${item.patientIdentifier?.let { " • $it" } ?: ""}",
                            timestampMs = recordedTimestampMs,
                            durationMs = dur,
                            fileSizeBytes = f.length(),
                            format = "M4A",
                            isSession = false,
                            isVoiceProof = true,
                            isOfflinePending = false,
                            isPinned = true, // Permanent proof clip
                            isMeaningful = true,
                            hasTranscription = true,
                            status = null,
                            speakerLabel = item.speakerLabel,
                            quoteOriginal = item.quoteOriginal,
                            patientId = item.patientIdentifier,
                            associatedItemId = item.id,
                            associatedSessionId = item.sessionId
                        )
                    )
                }
            }
        }

        // 3. Doctor "My Voice" & Speaker Roster Voice Profile Samples (.wav / .aac, up to 10 best per profile)
        prefs.getMyVoiceSamples().forEachIndexed { idx, sample ->
            val profileFile = File(sample.filePath)
            if (profileFile.exists() && profileFile.length() > 0L && !seenPaths.contains(profileFile.absolutePath)) {
                seenPaths.add(profileFile.absolutePath)
                val rankTag = if (idx == 0) "#1 Best" else "#${idx + 1}"
                val qTag = if (sample.qualityScore > 0f) " • Q${sample.qualityScore.toInt()}" else ""
                val srcTag = if (sample.isManual) "Manual" else "Auto"
                val snrTag = if (sample.snrDb > 0f) " • ${sample.snrDb.toInt()} dB SNR" else ""
                val speechTag = if (sample.speechRatio > 0f) " • ${(sample.speechRatio * 100f).toInt()}% speech" else ""
                val srcSess = sample.sourceSessionId?.let { sessionsById[it] }
                val sampleTimestampMs = when {
                    srcSess != null && srcSess.startMs > 0L && (sample.createdAtMs <= 0L || sample.createdAtMs > srcSess.endMs + 60_000L) -> srcSess.startMs
                    sample.createdAtMs > 0L -> sample.createdAtMs
                    else -> profileFile.lastModified()
                }
                val fmtLabel = when {
                    ClipSlicer.isWavFile(profileFile) -> "WAV"
                    profileFile.name.endsWith(".m4a", ignoreCase = true) -> "M4A"
                    else -> "AAC"
                }
                list.add(
                    AudioVaultItem(
                        id = "profile_my_voice_${sample.id}",
                        filePath = profileFile.absolutePath,
                        fileName = profileFile.name,
                        title = "My Voice Sample ($rankTag$qTag)",
                        subtitle = "$srcTag reference signature$snrTag$speechTag",
                        timestampMs = sampleTimestampMs,
                        durationMs = sample.durationMs.takeIf { it > 0L } ?: 12_000L,
                        fileSizeBytes = profileFile.length(),
                        format = fmtLabel,
                        isSession = false,
                        isVoiceProof = false,
                        isOfflinePending = false,
                        isPinned = true,
                        isMeaningful = true,
                        hasTranscription = false,
                        status = null,
                        speakerLabel = prefs.userDisplayName.ifBlank { "Me" }
                    )
                )
            }
        }
        prefs.getSpeakerRoster().forEach { entry ->
            val spkName = entry.effectiveNickname()
            entry.existingSamples().forEachIndexed { idx, sample ->
                val profileFile = File(sample.filePath)
                if (profileFile.exists() && profileFile.length() > 0L && !seenPaths.contains(profileFile.absolutePath)) {
                    seenPaths.add(profileFile.absolutePath)
                    val rankTag = if (idx == 0) "#1 Best" else "#${idx + 1}"
                    val qTag = if (sample.qualityScore > 0f) " • Q${sample.qualityScore.toInt()}" else ""
                    val roleTag = entry.effectiveRelatableTitle().takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
                    val srcSess = sample.sourceSessionId?.let { sessionsById[it] }
                    val sampleTimestampMs = when {
                        srcSess != null && srcSess.startMs > 0L && (sample.createdAtMs <= 0L || sample.createdAtMs > srcSess.endMs + 60_000L) -> srcSess.startMs
                        sample.createdAtMs > 0L -> sample.createdAtMs
                        else -> profileFile.lastModified()
                    }
                    val fmtLabel = when {
                        ClipSlicer.isWavFile(profileFile) -> "WAV"
                        profileFile.name.endsWith(".m4a", ignoreCase = true) -> "M4A"
                        else -> "AAC"
                    }
                    list.add(
                        AudioVaultItem(
                            id = "profile_spk_${entry.id}_${sample.id}",
                            filePath = profileFile.absolutePath,
                            fileName = profileFile.name,
                            title = "$spkName ($rankTag$qTag)",
                            subtitle = "Voice profile sample$roleTag • ${if (sample.isManual) "Manual" else "Auto"}",
                            timestampMs = sampleTimestampMs,
                            durationMs = sample.durationMs.takeIf { it > 0L } ?: ClipSlicer.estimateAdtsDurationMs(profileFile).coerceAtLeast(1_000L),
                            fileSizeBytes = profileFile.length(),
                            format = fmtLabel,
                            isSession = false,
                            isVoiceProof = false,
                            isOfflinePending = false,
                            isPinned = entry.isPinned,
                            isMeaningful = true,
                            hasTranscription = false,
                            status = null,
                            speakerLabel = spkName
                        )
                    )
                }
            }
        }

        // 4. Fallback scan of sessions & clips directories to catch any unindexed physical audio files
        val nowMs = System.currentTimeMillis()
        val sessionsDir = File(context.filesDir, "sessions")
        sessionsDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".aac") || it.name.endsWith(".m4a")) }?.forEach { f ->
            if (!seenPaths.contains(f.absolutePath) && f.length() > 0L) {
                seenPaths.add(f.absolutePath)
                val shortId = f.nameWithoutExtension.removePrefix("session_").take(8)
                val estimatedMs = if (f.name.endsWith(".aac", ignoreCase = true)) {
                    ClipSlicer.estimateAdtsDurationMs(f).takeIf { it > 0L } ?: ((f.length() * 1000L) / 4110L).coerceAtLeast(1_000L)
                } else {
                    ((f.length() * 1000L) / 4110L).coerceAtLeast(1_000L)
                }
                val fileMtime = f.lastModified().takeIf { it > 0L } ?: nowMs
                val recordedStartMs = if (fileMtime + estimatedMs > nowMs + 5_000L) {
                    (fileMtime - estimatedMs).coerceAtLeast(0L)
                } else {
                    fileMtime
                }
                list.add(
                    AudioVaultItem(
                        id = "raw_file_${f.name}",
                        filePath = f.absolutePath,
                        fileName = f.name,
                        title = "Recording #$shortId",
                        subtitle = "Standalone audio file • ${f.name}",
                        timestampMs = recordedStartMs,
                        durationMs = estimatedMs,
                        fileSizeBytes = f.length(),
                        format = if (f.name.endsWith(".m4a")) "M4A" else "AAC",
                        isSession = true,
                        isVoiceProof = false,
                        isOfflinePending = false,
                        isPinned = false,
                        isMeaningful = false,
                        hasTranscription = false,
                        status = null
                    )
                )
            }
        }

        val clipsDir = File(context.filesDir, "clips")
        clipsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".m4a") }?.forEach { f ->
            if (!seenPaths.contains(f.absolutePath) && f.length() > 0L) {
                seenPaths.add(f.absolutePath)
                val shortId = f.nameWithoutExtension.removePrefix("clip_").take(8)
                val estimatedMs = ((f.length() * 1000L) / 4110L).coerceAtLeast(1_000L)
                list.add(
                    AudioVaultItem(
                        id = "raw_clip_${f.name}",
                        filePath = f.absolutePath,
                        fileName = f.name,
                        title = "Voice Clip #$shortId",
                        subtitle = "Audio proof clip • ${f.name}",
                        timestampMs = f.lastModified(),
                        durationMs = estimatedMs,
                        fileSizeBytes = f.length(),
                        format = "M4A",
                        isSession = false,
                        isVoiceProof = true,
                        isOfflinePending = false,
                        isPinned = true,
                        isMeaningful = true,
                        hasTranscription = true,
                        status = null
                    )
                )
            }
        }

        list
    }

    // Recording Date Filter (local timezone calendar day of true recording timestamp)
    val dateFilteredItems = remember(allAudioItems, selectedDateFilter, customSelectedLocalDate) {
        if (selectedDateFilter == AudioDateFilter.ALL) {
            allAudioItems
        } else {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            allAudioItems.filter { item ->
                if (item.timestampMs <= 0L) return@filter false
                val itemDate = Instant.ofEpochMilli(item.timestampMs).atZone(zone).toLocalDate()
                when (selectedDateFilter) {
                    AudioDateFilter.ALL -> true
                    AudioDateFilter.TODAY -> itemDate == today
                    AudioDateFilter.YESTERDAY -> itemDate == today.minusDays(1)
                    AudioDateFilter.LAST_7_DAYS -> !itemDate.isBefore(today.minusDays(6)) && !itemDate.isAfter(today)
                    AudioDateFilter.CUSTOM_DATE -> customSelectedLocalDate == null || itemDate == customSelectedLocalDate
                }
            }
        }
    }

    val activeDateLabel = remember(selectedDateFilter, customSelectedLocalDate) {
        when (selectedDateFilter) {
            AudioDateFilter.ALL -> "All Dates"
            AudioDateFilter.TODAY -> "Today"
            AudioDateFilter.YESTERDAY -> "Yesterday"
            AudioDateFilter.LAST_7_DAYS -> "Last 7 Days"
            AudioDateFilter.CUSTOM_DATE -> customSelectedLocalDate?.format(
                DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
            ) ?: "Pick Date"
        }
    }

    // Disk summary metrics (reflecting active date filter so counts match visible recordings)
    val totalCount = dateFilteredItems.size
    val totalSizeBytes = remember(dateFilteredItems) { dateFilteredItems.sumOf { it.fileSizeBytes } }
    val sessionCount = remember(dateFilteredItems) { dateFilteredItems.count { it.isSession } }
    val proofCount = remember(dateFilteredItems) { dateFilteredItems.count { it.isVoiceProof } }
    val offlineCount = remember(dateFilteredItems) { dateFilteredItems.count { it.isOfflinePending } }
    val pinnedCount = remember(dateFilteredItems) { dateFilteredItems.count { it.isPinned } }
    val meaningfulCount = remember(dateFilteredItems) { dateFilteredItems.count { it.isMeaningful } }
    val ambientNoiseCount = remember(dateFilteredItems) { dateFilteredItems.count { !it.isMeaningful } }

    // Filtering & Sorting
    val displayedItems = remember(dateFilteredItems, searchQuery, selectedFilter, selectedSort) {
        var res = dateFilteredItems.asSequence()

        // Filter chip
        res = when (selectedFilter) {
            AudioVaultFilter.ALL -> res
            AudioVaultFilter.MEANINGFUL -> res.filter { it.isMeaningful }
            AudioVaultFilter.AMBIENT_NOISE -> res.filter { !it.isMeaningful }
            AudioVaultFilter.SESSIONS -> res.filter { it.isSession }
            AudioVaultFilter.PROOFS -> res.filter { it.isVoiceProof }
            AudioVaultFilter.OFFLINE -> res.filter { it.isOfflinePending }
            AudioVaultFilter.PINNED -> res.filter { it.isPinned }
        }

        // Search query
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            res = res.filter {
                it.title.lowercase().contains(q) ||
                    it.subtitle.lowercase().contains(q) ||
                    it.fileName.lowercase().contains(q) ||
                    (it.quoteOriginal?.lowercase()?.contains(q) == true) ||
                    (it.speakerLabel?.lowercase()?.contains(q) == true) ||
                    (it.patientId?.lowercase()?.contains(q) == true)
            }
        }

        // Sorting
        when (selectedSort) {
            AudioVaultSort.NEWEST -> res.sortedByDescending { it.timestampMs }
            AudioVaultSort.OLDEST -> res.sortedBy { it.timestampMs }
            AudioVaultSort.LARGEST -> res.sortedByDescending { it.fileSizeBytes }
            AudioVaultSort.LONGEST -> res.sortedByDescending { it.durationMs }
        }.toList()
    }

    if (showDatePickerDialog) {
        val initialUtcMs = remember(customSelectedLocalDate) {
            (customSelectedLocalDate ?: LocalDate.now(ZoneId.systemDefault()))
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMs)
        DatePickerDialog(
            onDismissRequest = { showDatePickerDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pickedMs = datePickerState.selectedDateMillis
                        if (pickedMs != null) {
                            customSelectedLocalDate = Instant.ofEpochMilli(pickedMs)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            selectedDateFilter = AudioDateFilter.CUSTOM_DATE
                        }
                        showDatePickerDialog = false
                    }
                ) {
                    Text("Apply Date")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (selectedDateFilter != AudioDateFilter.ALL) {
                        TextButton(
                            onClick = {
                                selectedDateFilter = AudioDateFilter.ALL
                                customSelectedLocalDate = null
                                showDatePickerDialog = false
                            }
                        ) {
                            Text("All Dates")
                        }
                    }
                    TextButton(onClick = { showDatePickerDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Deletion confirmation dialog
    itemPendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemPendingDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Audio File?") },
            text = {
                Column {
                    Text("Are you sure you want to delete this recorded audio?")
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• ${item.title} (${formatFileSize(item.fileSizeBytes)})",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "This will permanently remove the audio file from internal storage to free up space.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!item.isCombined) {
                            val file = File(item.filePath)
                            if (file.exists()) {
                                file.delete()
                            }
                        }
                        if (item.associatedSessionId != null && item.isSession) {
                            onDeleteSession(item.associatedSessionId)
                        } else if (item.isVoiceProof && item.associatedItemId != null) {
                            val itemIdToClear = item.associatedItemId
                            scope.launch {
                                com.ambientbrain.recorder.data.AppDatabase.get(context).dao()
                                    .clearItemClipPath(itemIdToClear)
                            }
                        }
                        if (playbackController.activeFilePath == item.filePath) {
                            playbackController.stop()
                        }
                        Toast.makeText(context, "Audio file deleted (${formatFileSize(item.fileSizeBytes)} freed)", Toast.LENGTH_SHORT).show()
                        itemPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { itemPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBatchDeleteConfirm && selectedSessionIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete ${selectedSessionIds.size} Selected Session(s)?") },
            text = {
                Text("This will permanently remove the selected audio recordings, transcripts, and linked task proof clips.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val idsToDelete = selectedSessionIds.toList()
                        idsToDelete.forEach { sid -> onDeleteSession(sid) }
                        Toast.makeText(context, "Deleted ${idsToDelete.size} session(s)", Toast.LENGTH_SHORT).show()
                        showBatchDeleteConfirm = false
                        isSelectMode = false
                        selectedSessionIds = emptySet()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete (${selectedSessionIds.size})")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Compact Top Control Bar (Recovers ~300dp of screen area for audio content)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                // Row 1: Search Field + Storage Cleaner + Sort
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        placeholder = {
                            Text(
                                "Search $totalCount items (${formatFileSize(totalSizeBytes)})...",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Multi-Select Combine Mode Toggle
                    OutlinedButton(
                        onClick = {
                            isSelectMode = !isSelectMode
                            if (!isSelectMode) selectedSessionIds = emptySet()
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("vault_combine_toggle")
                    ) {
                        Icon(
                            imageVector = if (isSelectMode) Icons.Default.Close else Icons.Default.Checklist,
                            contentDescription = if (isSelectMode) "Exit combine selection" else "Select multiple sessions to combine",
                            tint = if (isSelectMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Clean Purgeable Button
                    OutlinedButton(
                        onClick = onRunStorageCleaner,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.CleaningServices, contentDescription = "Clean storage", modifier = Modifier.size(16.dp))
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Sort Dropdown Button
                    Box {
                        OutlinedButton(
                            onClick = { sortMenuExpanded = true },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort", modifier = Modifier.size(16.dp))
                        }

                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            AudioVaultSort.values().forEach { sortOption ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = sortOption.label,
                                            fontWeight = if (selectedSort == sortOption) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selectedSort == sortOption) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        selectedSort = sortOption
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Row 2: Single-Line Horizontal Scrollable Filter Strip
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    AudioVaultFilter.values().forEach { filter ->
                        val countForFilter = when (filter) {
                            AudioVaultFilter.ALL -> totalCount
                            AudioVaultFilter.MEANINGFUL -> meaningfulCount
                            AudioVaultFilter.AMBIENT_NOISE -> ambientNoiseCount
                            AudioVaultFilter.SESSIONS -> sessionCount
                            AudioVaultFilter.PROOFS -> proofCount
                            AudioVaultFilter.OFFLINE -> offlineCount
                            AudioVaultFilter.PINNED -> pinnedCount
                        }
                        if (filter != AudioVaultFilter.OFFLINE || offlineCount > 0 || selectedFilter == AudioVaultFilter.OFFLINE) {
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = { selectedFilter = filter },
                                label = {
                                    Text(
                                        "${filter.label} ($countForFilter)",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.height(32.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = if (filter == AudioVaultFilter.OFFLINE && offlineCount > 0) {
                                        MaterialTheme.colorScheme.errorContainer
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    },
                                    selectedLabelColor = if (filter == AudioVaultFilter.OFFLINE && offlineCount > 0) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    }
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Row 3: Recording Date Filter Strip (filters by true local-timezone recording date)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("vault_date_filter_row")
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = "Filter by recorded date",
                        tint = if (selectedDateFilter != AudioDateFilter.ALL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    AudioDateFilter.values().forEach { dateOpt ->
                        val isSelectedOpt = selectedDateFilter == dateOpt
                        val chipLabel = if (dateOpt == AudioDateFilter.CUSTOM_DATE && customSelectedLocalDate != null) {
                            customSelectedLocalDate!!.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()))
                        } else {
                            dateOpt.label
                        }
                        FilterChip(
                            selected = isSelectedOpt,
                            onClick = {
                                if (dateOpt == AudioDateFilter.CUSTOM_DATE) {
                                    showDatePickerDialog = true
                                } else {
                                    selectedDateFilter = dateOpt
                                }
                            },
                            label = {
                                Text(
                                    text = chipLabel,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            trailingIcon = if (dateOpt == AudioDateFilter.CUSTOM_DATE && isSelectedOpt && customSelectedLocalDate != null) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear custom date",
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable {
                                                selectedDateFilter = AudioDateFilter.ALL
                                                customSelectedLocalDate = null
                                            }
                                    )
                                }
                            } else null,
                            modifier = Modifier
                                .height(30.dp)
                                .testTag("vault_date_chip_${dateOpt.name.lowercase()}"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )
                    }
                }
            }
        }

        // Detected Related Continuous Audio Groups Banner
        if (continuousClusters.isNotEmpty() && selectedFilter in setOf(AudioVaultFilter.ALL, AudioVaultFilter.MEANINGFUL, AudioVaultFilter.SESSIONS)) {
            ContinuousClustersBanner(
                clusters = continuousClusters,
                onAutoMergePending = { pending ->
                    val allIds = pending.flatMap { it.sessionIds }.distinct()
                    val rawCount = pending.sumOf { it.rawAudioCount }
                    val extractedCount = pending.sumOf { it.extractedInfoCount }
                    pendingReviewRequest = ReviewTargetRequest(
                        title = "Auto-Merge ${pending.size} Continuous Group(s)",
                        sessionIds = allIds,
                        rawAudioCount = rawCount,
                        extractedInfoCount = extractedCount,
                        isContinuousAutoMerge = true,
                        clustersToBatchMerge = pending
                    )
                },
                onMergeCluster = { cluster ->
                    pendingReviewRequest = ReviewTargetRequest(
                        title = "Merge & Review Continuous Files",
                        sessionIds = cluster.sessionIds,
                        rawAudioCount = cluster.rawAudioCount,
                        extractedInfoCount = cluster.extractedInfoCount,
                        existingCombinedSessionId = cluster.existingCombinedSessionId,
                        isContinuousAutoMerge = true
                    )
                },
                onOpenMergedSession = { mergedId ->
                    viewingTranscriptSessionId = mergedId
                }
            )
        }

        // List of Audio Items
        if (displayedItems.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.AudioFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = when {
                            searchQuery.isNotBlank() -> "No audio matching '$searchQuery'"
                            selectedDateFilter != AudioDateFilter.ALL -> "No audio recorded on $activeDateLabel"
                            else -> "No recorded audio in this category"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (selectedDateFilter != AudioDateFilter.ALL) {
                            "Try picking another date or showing recordings from all dates."
                        } else {
                            "Audio recordings and voice proof clips will appear here as you listen."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (selectedDateFilter != AudioDateFilter.ALL) {
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = {
                                selectedDateFilter = AudioDateFilter.ALL
                                customSelectedLocalDate = null
                            }
                        ) {
                            Text("Show All Dates")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(displayedItems, key = { it.id }) { item ->
                    val sid = item.associatedSessionId
                    val isSelectable = item.isSession && sid != null && !item.isCombined
                    val isSelected = isSelectable && selectedSessionIds.contains(sid)
                    AudioVaultItemCard(
                        item = item,
                        playbackController = playbackController,
                        isSelectMode = isSelectMode,
                        isSelected = isSelected,
                        isExtractingVoices = extractingVoiceItemId == item.id,
                        onToggleSelect = {
                            if (isSelectable && sid != null) {
                                selectedSessionIds = if (isSelected) {
                                    selectedSessionIds - sid
                                } else {
                                    selectedSessionIds + sid
                                }
                            }
                        },
                        onOpenTranscript = {
                            if (item.associatedSessionId != null) {
                                viewingTranscriptSessionId = item.associatedSessionId
                            } else {
                                SessionAnalyzeWorker.analyzeStandaloneAudioFile(
                                    context = context,
                                    audioFile = File(item.filePath),
                                    existingSessionId = null,
                                    mode = AnalysisMode.INFO_EXTRACTION_EN
                                )
                                Toast.makeText(
                                    context,
                                    "Gathering & describing all conversation info in English with Gemini...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onRequestOriginalTranscript = {
                            if (item.associatedSessionId != null) {
                                SessionAnalyzeWorker.requestOriginalTranscriptNow(context, item.associatedSessionId)
                            } else {
                                SessionAnalyzeWorker.analyzeStandaloneAudioFile(
                                    context = context,
                                    audioFile = File(item.filePath),
                                    existingSessionId = null,
                                    mode = AnalysisMode.VERBATIM_ORIGINAL_TRANSCRIPT
                                )
                            }
                            Toast.makeText(
                                context,
                                "Asking Gemini for 100% original verbatim transcription...",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onExtractVoices = {
                            if (extractingVoiceItemId == null) {
                                extractingVoiceItemId = item.id
                                scope.launch {
                                    try {
                                        val result = if (item.associatedSessionId != null) {
                                            VoiceProfileAutoManager.extractVoiceProfilesFromSession(
                                                context = context,
                                                sessionId = item.associatedSessionId,
                                                allowCreateNewProfiles = true
                                            )
                                        } else {
                                            VoiceProfileAutoManager.extractVoiceProfilesFromStandaloneFile(
                                                context = context,
                                                audioFile = File(item.filePath)
                                            )
                                        }
                                        vaultRefreshNonce++
                                        Toast.makeText(context, result.summaryMessage, Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "Voice extraction failed: ${e.message ?: "unknown error"}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } finally {
                                        extractingVoiceItemId = null
                                    }
                                }
                            }
                        },
                        onShare = {
                            shareAudioFile(context, File(item.filePath), item.title)
                        },
                        onTogglePin = {
                            if (item.associatedSessionId != null && item.isSession) {
                                onTogglePinSession(item.associatedSessionId, !item.isPinned)
                            }
                        },
                        onRetryAnalysis = {
                            if (item.associatedSessionId != null) {
                                val targetDetails = allSessions.firstOrNull { it.session.id == item.associatedSessionId }
                                val hasRaw = targetDetails?.let { ContinuousMergePlanner.hasRawAudio(it.session) } ?: item.rawFileExists
                                val hasExtracted = targetDetails?.let { ContinuousMergePlanner.hasExtractedInfo(it) } ?: item.hasTranscription
                                pendingReviewRequest = ReviewTargetRequest(
                                    title = "AI Review Options",
                                    sessionIds = listOf(item.associatedSessionId),
                                    rawAudioCount = if (hasRaw) 1 else 0,
                                    extractedInfoCount = if (hasExtracted) 1 else 0
                                )
                            } else {
                                SessionAnalyzeWorker.analyzeStandaloneAudioFile(
                                    context = context,
                                    audioFile = File(item.filePath),
                                    existingSessionId = null,
                                    mode = AnalysisMode.INFO_EXTRACTION_EN
                                )
                                Toast.makeText(
                                    context,
                                    "Asking Gemini to gather & describe all conversation info in English...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onDelete = {
                            itemPendingDelete = item
                        }
                    )
                }
            }
        }

        AnimatedVisibility(visible = isSelectMode) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (selectedSessionIds.size < 2) {
                                "Select 2+ recordings to combine (${selectedSessionIds.size} selected)"
                            } else {
                                "${selectedSessionIds.size} recordings selected for combined analysis"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = {
                                isSelectMode = false
                                selectedSessionIds = emptySet()
                            }
                        ) {
                            Text("Cancel")
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            enabled = selectedSessionIds.size >= 2,
                            onClick = {
                                val chosenDetails = allSessions.filter { selectedSessionIds.contains(it.session.id) }
                                val rawCount = chosenDetails.count { ContinuousMergePlanner.hasRawAudio(it.session) }
                                val extractedCount = chosenDetails.count { ContinuousMergePlanner.hasExtractedInfo(it) }
                                pendingReviewRequest = ReviewTargetRequest(
                                    title = "Merge & AI Review (${chosenDetails.size} Files)",
                                    sessionIds = selectedSessionIds.toList(),
                                    rawAudioCount = rawCount,
                                    extractedInfoCount = extractedCount
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1.25f)
                                .height(48.dp)
                                .testTag("vault_combine_en_button")
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Merge & Review", maxLines = 1)
                        }

                        OutlinedButton(
                            enabled = selectedSessionIds.size >= 2,
                            onClick = {
                                SessionAnalyzeWorker.enqueueCombinedAnalysis(
                                    context = context,
                                    sourceSessionIds = selectedSessionIds.toList(),
                                    mode = AnalysisMode.VERBATIM_ORIGINAL_TRANSCRIPT
                                )
                                Toast.makeText(
                                    context,
                                    "Combining ${selectedSessionIds.size} recordings for 100% transcript...",
                                    Toast.LENGTH_SHORT
                                ).show()
                                isSelectMode = false
                                selectedSessionIds = emptySet()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("vault_combine_transcript_button")
                        ) {
                            Text("Transcript", maxLines = 1)
                        }

                        OutlinedButton(
                            enabled = selectedSessionIds.isNotEmpty(),
                            onClick = { showBatchDeleteConfirm = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (selectedSessionIds.isNotEmpty()) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("vault_batch_delete_button")
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete selected sessions", modifier = Modifier.size(18.dp))
                            if (selectedSessionIds.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${selectedSessionIds.size}", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        val viewingDetails = remember(allSessions, viewingTranscriptSessionId) {
            allSessions.firstOrNull { it.session.id == viewingTranscriptSessionId }
        }
        if (viewingDetails != null) {
            SessionTranscriptSheet(
                details = viewingDetails,
                defaultLangMode = prefs.transcriptLangMode,
                playbackController = playbackController,
                onDismiss = { viewingTranscriptSessionId = null },
                onReanalyze = { sessionId ->
                    onReanalyzeSession(sessionId)
                },
                onRequestOriginalTranscript = { sessionId ->
                    SessionAnalyzeWorker.requestOriginalTranscriptNow(context, sessionId)
                },
                onRenameSpeaker = { sessionId, speakerId, newLabel ->
                    scope.launch {
                        val db = com.ambientbrain.recorder.data.AppDatabase.get(context).dao()
                        db.renameSpeakerAcrossSession(sessionId, speakerId, newLabel)
                    }
                },
                onTogglePin = { sessionId, pinned ->
                    onTogglePinSession(sessionId, pinned)
                }
            )
        }

        pendingReviewRequest?.let { req ->
            AiReviewChoiceDialog(
                request = req,
                defaultIsAudio = prefs.defaultReviewSourceIsAudio,
                onDismiss = { pendingReviewRequest = null },
                onConfirm = { sourceMode, wantTranscript ->
                    if (req.clustersToBatchMerge.isNotEmpty()) {
                        req.clustersToBatchMerge.forEach { cluster ->
                            SessionAnalyzeWorker.enqueueCombinedWithSourceChoice(
                                context = context,
                                sourceSessionIds = cluster.sessionIds,
                                sourceMode = sourceMode,
                                wantVerbatimTranscript = wantTranscript,
                                existingCombinedSessionId = cluster.existingCombinedSessionId,
                                isAutoMerged = true
                            )
                        }
                        Toast.makeText(
                            context,
                            "Merging ${req.clustersToBatchMerge.size} continuous group(s)...",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (req.sessionIds.size >= 2) {
                        SessionAnalyzeWorker.enqueueCombinedWithSourceChoice(
                            context = context,
                            sourceSessionIds = req.sessionIds,
                            sourceMode = sourceMode,
                            wantVerbatimTranscript = wantTranscript,
                            existingCombinedSessionId = req.existingCombinedSessionId,
                            isAutoMerged = req.isContinuousAutoMerge
                        )
                        Toast.makeText(
                            context,
                            if (sourceMode == ReviewSourceMode.EXTRACTED_ONLY) {
                                "Merging ${req.sessionIds.size} files using extracted info only..."
                            } else {
                                "Re-analyzing & merging ${req.sessionIds.size} audio files..."
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (req.sessionIds.size == 1) {
                        val sid = req.sessionIds.first()
                        if (wantTranscript && sourceMode == ReviewSourceMode.REANALYZE_AUDIO) {
                            SessionAnalyzeWorker.requestOriginalTranscriptNow(context, sid)
                        } else {
                            SessionAnalyzeWorker.enqueueAiReview(
                                context = context,
                                sessionId = sid,
                                sourceMode = sourceMode
                            )
                        }
                        Toast.makeText(
                            context,
                            if (sourceMode == ReviewSourceMode.EXTRACTED_ONLY) {
                                "Running AI Review using previously extracted info..."
                            } else {
                                "Re-analyzing audio file with AI Review..."
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    pendingReviewRequest = null
                    isSelectMode = false
                    selectedSessionIds = emptySet()
                }
            )
        }
    }
}

@Composable
private fun CompactHeaderPill(
    text: String,
    containerColor: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioVaultItemCard(
    item: AudioVaultItem,
    playbackController: AudioPlaybackController,
    isSelectMode: Boolean = false,
    isSelected: Boolean = false,
    isExtractingVoices: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onOpenTranscript: () -> Unit,
    onRequestOriginalTranscript: () -> Unit,
    onExtractVoices: () -> Unit = {},
    onShare: () -> Unit,
    onTogglePin: () -> Unit,
    onRetryAnalysis: () -> Unit,
    onDelete: () -> Unit
) {
    val isThisActive = playbackController.activeFilePath == item.filePath
    val isThisPlaying = isThisActive && playbackController.isPlaying
    val progress = if (isThisActive) playbackController.progressFraction else 0f
    val currentPositionMs = if (isThisActive) playbackController.currentPositionMs else 0L
    val effectiveDurationMs = if (isThisActive && playbackController.durationMs > 0L) {
        playbackController.durationMs
    } else {
        item.durationMs
    }

    val timeFormatted = remember(item.timestampMs) {
        SimpleDateFormat("EEE, dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(item.timestampMs))
    }

    val isSelectable = item.isSession && item.associatedSessionId != null && !item.isCombined

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else if (isThisPlaying) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
            }
        ),
        border = BorderStroke(
            width = if (isSelected || isThisPlaying) 1.5.dp else 1.dp,
            color = if (isSelected || isThisPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelectMode && isSelectable) {
                    Modifier.clickable { onToggleSelect() }
                } else {
                    Modifier
                }
            )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // Compact Single-Line Metadata Header Row (Never wraps vertically!)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSelectMode && isSelectable) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                ) {
                    // Format Badge (AAC / M4A)
                    CompactHeaderPill(
                        text = item.format,
                        containerColor = if (item.format == "M4A") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
                    )

                    // Category Badge
                    val categoryLabel = when {
                        item.isVoiceProof -> "Proof Clip"
                        item.isOfflinePending -> "Pending"
                        item.id.startsWith("profile_") -> "Profile"
                        else -> "Session"
                    }
                    CompactHeaderPill(
                        text = categoryLabel,
                        containerColor = if (item.isOfflinePending) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
                    )

                    // Meaningful / Transcribed vs Ambient Badge
                    if (item.isMeaningful) {
                        CompactHeaderPill(
                            text = if (item.hasTranscription) "🗣️ Transcribed" else "📋 Info Ready",
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else {
                        CompactHeaderPill(
                            text = "🔇 Ambient",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }

                    if (item.isCombined) {
                        CompactHeaderPill(
                            text = if (item.title.startsWith("Auto-Merged")) "🤖 Auto-Merged" else "🔗 Combined",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    } else if (item.isLinkedEvent) {
                        CompactHeaderPill(
                            text = "🔗 Linked",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    }

                    if (item.hasAiReview) {
                        CompactHeaderPill(
                            text = if (item.reviewSourceMode == ReviewSourceMode.EXTRACTED_ONLY.name) "✨ Reviewed (Info)" else "✨ Reviewed (Audio)",
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    }

                    if (item.isPinned) {
                        CompactHeaderPill(
                            text = "📌",
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Mini Waveform Speech Density Preview
                MiniWaveformBar(
                    seedKey = item.filePath,
                    progress = if (isThisActive) progress else 0f,
                    isPlaying = isThisPlaying,
                    isMeaningful = item.isMeaningful,
                    activeColor = MaterialTheme.colorScheme.primary,
                    inactiveColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .width(28.dp)
                        .height(12.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // File Size & Duration (Single-line non-wrapping)
                Text(
                    text = "${formatFileSize(item.fileSizeBytes)} • ${formatAudioDuration(effectiveDurationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Compact Title & Subtitle
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (item.subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // If Voice Proof with quote snippet
            if (!item.quoteOriginal.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "💬 \"${item.quoteOriginal}\"",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            // If Offline / Failed Session
            if (item.isOfflinePending) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val attemptText = if (item.retryCount > 3 && item.status != Status.FAILED_PERMANENT) {
                                "Attempt ${item.retryCount}"
                            } else {
                                "Attempt ${item.retryCount}/3"
                            }
                            Text(
                                text = "Gemini Connection Pending ($attemptText)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            val minLeft = if (item.nextRetryEpochMs > System.currentTimeMillis()) {
                                ((item.nextRetryEpochMs - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(1L)
                            } else 0L
                            Text(
                                text = if (minLeft > 0L) "Next auto-retry in ${minLeft}m" else "Ready to retry",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                        }
                        Button(
                            onClick = onRetryAnalysis,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Try Now", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Inline Playback Row with Scrubbing & Time Controls (shown when physical file exists)
            if (item.rawFileExists) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Play/Pause button
                            Surface(
                                onClick = {
                                    playbackController.togglePlayFile(item.filePath, title = item.title)
                                },
                                shape = CircleShape,
                                color = if (isThisPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isThisPlaying) "Pause" else "Play",
                                        tint = if (isThisPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // Interactive Audio Waveform Scrubber
                            Column(modifier = Modifier.weight(1f)) {
                                InteractiveAudioWaveform(
                                    seedKey = item.filePath,
                                    progress = if (isThisActive) progress else 0f,
                                    isPlaying = isThisPlaying,
                                    isMeaningful = item.isMeaningful,
                                    barCount = 38,
                                    activeColor = MaterialTheme.colorScheme.primary,
                                    inactiveColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    onSeek = { fraction ->
                                        if (isThisActive) {
                                            playbackController.seekToFraction(fraction)
                                        } else {
                                            val dur = effectiveDurationMs.coerceAtLeast(1_000L)
                                            val targetMs = (fraction * dur.toFloat()).toLong()
                                            playbackController.seekAndPlay(item.filePath, targetMs, title = item.title)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(26.dp)
                                        .padding(vertical = 2.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = formatAudioDuration(currentPositionMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isThisActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = if (!item.isMeaningful) "🔇 Ambient / Quiet" else "🗣️ Conversational Speech",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                    )
                                    Text(
                                        text = formatAudioDuration(effectiveDurationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Skip -10s / +10s if active
                            if (isThisActive) {
                                IconButton(
                                    onClick = { playbackController.skipBackward(10_000L) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Replay10, contentDescription = "-10s", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = { playbackController.skipForward(10_000L) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Forward10, contentDescription = "+10s", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (!item.id.startsWith("profile_")) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onOpenTranscript,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (item.associatedSessionId != null) "📋 View Info" else "📋 Describe EN",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = onRequestOriginalTranscript,
                        enabled = item.rawFileExists,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1.1f)
                            .height(34.dp)
                    ) {
                        Text(
                            text = "🗣️ 100% Transcript",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = onRetryAnalysis,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.tertiary
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("vault_ai_review_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Review",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "AI Review",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Action Toolbar (Share, Pin, Re-analyze, Delete, Timestamp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Extract Voice Profiles from this recording (offline voiceprint + session speaker slicing)
                    if (item.isSession && item.rawFileExists && !item.isCombined) {
                        IconButton(
                            onClick = onExtractVoices,
                            enabled = !isExtractingVoices,
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("vault_extract_voices_button")
                        ) {
                            if (isExtractingVoices) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(15.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = "Extract Voice Profiles",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }

                    // Share Audio via Android Sharesheet
                    if (item.rawFileExists) {
                        IconButton(
                            onClick = onShare,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Audio",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Pin toggle for sessions
                    if (item.isSession) {
                        IconButton(
                            onClick = onTogglePin,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Pin Audio",
                                tint = if (item.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Reanalyze for sessions
                    if (item.isSession) {
                        IconButton(
                            onClick = onRetryAnalysis,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Re-analyze with Gemini",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Delete audio
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete Audio",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}
