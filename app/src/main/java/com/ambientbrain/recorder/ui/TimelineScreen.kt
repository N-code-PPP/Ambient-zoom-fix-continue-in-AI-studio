package com.ambientbrain.recorder.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ambientbrain.recorder.ai.AnalysisMode
import com.ambientbrain.recorder.ai.ContinuousMergePlanner
import com.ambientbrain.recorder.ai.ReviewSourceMode
import com.ambientbrain.recorder.ai.SessionAnalyzeWorker
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SessionWithDetails
import com.ambientbrain.recorder.data.Status
import com.ambientbrain.recorder.data.TranscriptLangMode
import com.ambientbrain.recorder.service.MeetingMetadataHelper
import com.ambientbrain.recorder.service.MeetingMp4Muxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimelineScreen(
    allSessions: List<SessionWithDetails>,
    retentionDays: Int,
    transcriptLangMode: TranscriptLangMode,
    playbackController: AudioPlaybackController,
    onTogglePin: (String, Boolean) -> Unit,
    onReanalyze: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onExportSelectedSessions: (Set<String>) -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesRepo(context) }
    var searchQuery by remember { mutableStateOf("") }
    var expandedSessionId by remember { mutableStateOf<String?>(null) }
    var viewingTranscriptSessionId by remember { mutableStateOf<String?>(null) }
    var sessionPendingDelete by remember { mutableStateOf<SessionWithDetails?>(null) }
    var isSelectMode by remember { mutableStateOf(false) }
    var selectedSessionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingReviewRequest by remember { mutableStateOf<ReviewTargetRequest?>(null) }

    BackHandler(enabled = pendingReviewRequest != null) {
        pendingReviewRequest = null
    }
    BackHandler(enabled = sessionPendingDelete != null && pendingReviewRequest == null) {
        sessionPendingDelete = null
    }
    BackHandler(enabled = viewingTranscriptSessionId != null && sessionPendingDelete == null && pendingReviewRequest == null) {
        viewingTranscriptSessionId = null
    }
    BackHandler(enabled = isSelectMode && viewingTranscriptSessionId == null && sessionPendingDelete == null && pendingReviewRequest == null) {
        isSelectMode = false
        selectedSessionIds = emptySet()
    }

    val continuousClusters = remember(allSessions, prefs.autoMergeGapMinutes) {
        ContinuousMergePlanner.detectClusters(allSessions, prefs.autoMergeGapMinutes)
    }

    sessionPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { sessionPendingDelete = null },
            title = { Text("Delete Session?") },
            text = {
                Text(
                    "Delete \"${target.session.title ?: "Captured Session"}\" along with its transcript, tasks, and audio? This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSession(target.session.id)
                        sessionPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { sessionPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    val filteredSessions = remember(allSessions, searchQuery) {
        if (searchQuery.isBlank()) {
            allSessions
        } else {
            val q = searchQuery.trim().lowercase()
            allSessions.filter { d ->
                (d.session.title?.lowercase()?.contains(q) == true) ||
                    (d.session.summary?.lowercase()?.contains(q) == true) ||
                    d.sentences.any {
                        it.textOriginal.lowercase().contains(q) ||
                            (it.textEn?.lowercase()?.contains(q) == true) ||
                            it.speakerLabel.lowercase().contains(q)
                    } ||
                    d.patients.any { it.patientIdentifier.lowercase().contains(q) }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                placeholder = { Text("Search Thai/English transcript, speaker, or patient...") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )

            OutlinedButton(
                onClick = {
                    isSelectMode = !isSelectMode
                    if (!isSelectMode) selectedSessionIds = emptySet()
                },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    .height(52.dp)
                    .testTag("timeline_combine_toggle")
            ) {
                Icon(
                    imageVector = if (isSelectMode) Icons.Default.Close else Icons.Default.Checklist,
                    contentDescription = if (isSelectMode) "Exit combine mode" else "Combine events",
                    tint = if (isSelectMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isSelectMode) "Cancel" else "Combine",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (continuousClusters.isNotEmpty() && searchQuery.isBlank()) {
            ContinuousClustersBanner(
                clusters = continuousClusters,
                onAutoMergePending = { pending ->
                    pendingReviewRequest = ReviewTargetRequest(
                        title = "Auto-Merge ${pending.size} Continuous Group(s)",
                        sessionIds = pending.flatMap { it.sessionIds }.distinct(),
                        rawAudioCount = pending.sumOf { it.rawAudioCount },
                        extractedInfoCount = pending.sumOf { it.extractedInfoCount },
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

        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(filteredSessions, key = { it.session.id }) { details ->
                val s = details.session
                val isExpanded = expandedSessionId == s.id
                val isCombinedSession = !s.combinedSourceIdsJson.isNullOrBlank()
                val isLinkedEvent = !isCombinedSession && !s.eventGroupId.isNullOrBlank() && s.eventGroupId != s.id
                val hasRawAudio = ContinuousMergePlanner.hasRawAudio(s)
                val hasExtractedInfo = ContinuousMergePlanner.hasExtractedInfo(details)
                val isSelectable = !isCombinedSession && (hasRawAudio || hasExtractedInfo)
                val isSelected = isSelectable && selectedSessionIds.contains(s.id)
                val toggleSelection = {
                    if (isSelectable) {
                        selectedSessionIds = if (isSelected) {
                            selectedSessionIds - s.id
                        } else {
                            selectedSessionIds + s.id
                        }
                    }
                }
                val keyPoints = remember(s.keyPointsJson) {
                    CopyFormatter.decodeStringList(s.keyPointsJson)
                }
                val timeStr = remember(s.startMs) {
                    SimpleDateFormat("EEE, dd MMM HH:mm", Locale.getDefault()).format(Date(s.startMs))
                }
                val daysLeft = remember(s.endMs, retentionDays) {
                    val expireMs = s.endMs + TimeUnit.DAYS.toMillis(retentionDays.toLong())
                    ((expireMs - System.currentTimeMillis()) / TimeUnit.DAYS.toMillis(1)).coerceAtLeast(0L)
                }
                val rawExists = remember(s.rawPath, s.isRawPurged) {
                    !s.isRawPurged && !s.rawPath.isNullOrBlank() && File(s.rawPath).exists()
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        }
                    ),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isSelectMode && isSelectable) {
                                Modifier.clickable { toggleSelection() }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSelectMode && isSelectable) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { toggleSelection() }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            BadgePill(
                                text = s.context ?: s.mode.name,
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = timeStr,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onTogglePin(s.id, !s.isPinned) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = "Pin session",
                                    tint = if (s.isPinned) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    }
                                )
                            }
                            IconButton(
                                onClick = { onReanalyze(s.id) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Re-analyze")
                            }
                            IconButton(
                                onClick = { sessionPendingDelete = details },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete session")
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = s.title ?: "Captured Session (${s.status.name})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        if (!s.summary.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = s.summary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        val isFailed = s.status == Status.FAILED_RETRYABLE || s.status == Status.FAILED_PERMANENT || !s.lastError.isNullOrBlank()
                        if (isFailed) {
                            val minLeft = if (s.nextRetryEpochMs > System.currentTimeMillis()) {
                                ((s.nextRetryEpochMs - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(1L)
                            } else 0L

                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (s.status == Status.FAILED_PERMANENT) {
                                                "Connection Failed (3/3 Attempts)"
                                            } else {
                                                "Connection Failed • Auto-Retry Scheduled"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (rawExists) {
                                            Button(
                                                onClick = { onReanalyze(s.id) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.error,
                                                    contentColor = MaterialTheme.colorScheme.onError
                                                ),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Try Again", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }

                                    if (!s.lastError.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = s.lastError,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (rawExists) {
                                            BadgePill(
                                                text = "💾 Audio Saved (${(s.rawBytes / 1024L).coerceAtLeast(1L)} KB)",
                                                containerColor = MaterialTheme.colorScheme.surface
                                            )
                                        }
                                        if (minLeft > 0L) {
                                            BadgePill(
                                                text = "⏱️ Retry in ${minLeft}m",
                                                containerColor = MaterialTheme.colorScheme.surface
                                            )
                                        }
                                        if (s.retryCount > 0) {
                                            BadgePill(
                                                text = if (s.retryCount > 3 && s.status != Status.FAILED_PERMANENT) {
                                                    "Attempt ${s.retryCount}"
                                                } else {
                                                    "Attempt ${s.retryCount}/3"
                                                },
                                                containerColor = MaterialTheme.colorScheme.surface
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (s.status == Status.ANALYZING || s.status == Status.QUEUED) {
                            Spacer(modifier = Modifier.height(6.dp))
                            BadgePill(
                                text = "⏳ Analyzing with Gemini...",
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        }

                        if (keyPoints.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            keyPoints.forEach { kp ->
                                Text(
                                    text = "• $kp",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        val meetingMarker = remember(s.callEventsJson) {
                            MeetingMetadataHelper.parseFirst(s.callEventsJson)
                        }
                        val videoFile = remember(meetingMarker?.videoPath) {
                            meetingMarker?.videoPath?.let { File(it) }?.takeIf { it.exists() }
                        }
                        val hasPlayableVideo by produceState(
                            initialValue = videoFile != null || !meetingMarker?.galleryUri.isNullOrBlank(),
                            key1 = meetingMarker?.galleryUri,
                            key2 = meetingMarker?.videoPath
                        ) {
                            value = if (meetingMarker == null) {
                                false
                            } else {
                                withContext(Dispatchers.IO) {
                                    MeetingMp4Muxer.isPlayableVideoAvailable(
                                        context,
                                        meetingMarker.galleryUri,
                                        videoFile
                                    )
                                }
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val (purgeBadge, purgeColor) = when {
                                meetingMarker != null && s.rawPath == null && (hasPlayableVideo || !meetingMarker.publicVideoDisplayPath.isNullOrBlank()) ->
                                    "🎬 MP4 Saved" to MaterialTheme.colorScheme.tertiaryContainer
                                s.isPinned -> "📌 Kept Forever" to MaterialTheme.colorScheme.primaryContainer
                                s.isRawPurged -> "Raw Purged • Proofs Kept" to MaterialTheme.colorScheme.surface
                                daysLeft <= 2L -> "⚠️ Purges in ${daysLeft}d" to MaterialTheme.colorScheme.errorContainer
                                else -> "Purges in ${daysLeft}d" to MaterialTheme.colorScheme.secondaryContainer
                            }
                            BadgePill(
                                text = purgeBadge,
                                containerColor = purgeColor
                            )
                            if (isCombinedSession) {
                                BadgePill(
                                    text = if (s.title?.startsWith("Auto-Merged") == true) "🤖 Auto-Merged" else "🔗 Combined Event",
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            } else if (isLinkedEvent) {
                                BadgePill(
                                    text = "🔗 Linked Segment",
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            }
                            if (s.reviewedAtMs > 0L || !s.aiReviewReport.isNullOrBlank()) {
                                BadgePill(
                                    text = if (s.reviewSourceMode == ReviewSourceMode.EXTRACTED_ONLY.name) {
                                        "✨ AI Reviewed (Info)"
                                    } else {
                                        "✨ AI Reviewed (Audio)"
                                    },
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            }
                            if (s.hadPhoneCall) {
                                BadgePill(
                                    text = "📞 Recorded Through Call",
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            }
                            if (s.context == "ONLINE_MEETING" || meetingMarker != null) {
                                val meetingBadgeLabel = when {
                                    meetingMarker?.platform?.contains("Zoom", ignoreCase = true) == true ||
                                        s.callEventsJson?.contains("Zoom", ignoreCase = true) == true -> "🎥 Zoom Meeting"
                                    meetingMarker?.platform?.contains("Webex", ignoreCase = true) == true ||
                                        s.callEventsJson?.contains("Webex", ignoreCase = true) == true -> "🎥 Webex Meeting"
                                    !meetingMarker?.platform.isNullOrBlank() -> "🎥 ${meetingMarker!!.platform}"
                                    else -> "🎥 Online Meeting"
                                }
                                BadgePill(
                                    text = meetingBadgeLabel,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                                if (meetingMarker?.audioCaptureTier?.contains("SILENT", ignoreCase = true) == true) {
                                    BadgePill(
                                        text = "🛡️ Stealth Isolated",
                                        containerColor = Color(0xFF064E3B)
                                    )
                                }
                                if (meetingMarker?.ignoreInternalAppAudioForGemini == true) {
                                    BadgePill(
                                        text = "🔇 Gemini Ignored App Audio",
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                }
                                if (hasPlayableVideo) {
                                    Surface(
                                        onClick = {
                                            if (!MeetingMp4Muxer.openVideo(context, meetingMarker?.galleryUri, videoFile)) {
                                                Toast.makeText(
                                                    context,
                                                    "MP4 file was moved/deleted or no video player is installed.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = "🎬 Watch Video",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                val publicPath = meetingMarker?.publicVideoDisplayPath
                                if (!publicPath.isNullOrBlank()) {
                                    val folderOnly = publicPath.substringBeforeLast('/', publicPath)
                                    BadgePill(
                                        text = "📁 $folderOnly",
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                }
                            }
                            if (rawExists && s.rawPath != null) {
                                VoiceProofClipButton(
                                    clipPath = s.rawPath,
                                    clipDurationMs = s.speechMs,
                                    playbackController = playbackController
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { viewingTranscriptSessionId = s.id },
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
                                    text = "📋 View Info",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }

                            Button(
                                onClick = {
                                    com.ambientbrain.recorder.ai.SessionAnalyzeWorker.requestOriginalTranscriptNow(context, s.id)
                                    android.widget.Toast.makeText(
                                        context,
                                        "Asking Gemini for 100% original verbatim transcription...",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1.15f)
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
                                onClick = {
                                    pendingReviewRequest = ReviewTargetRequest(
                                        title = "AI Review Options",
                                        sessionIds = listOf(s.id),
                                        rawAudioCount = if (hasRawAudio) 1 else 0,
                                        extractedInfoCount = if (hasExtractedInfo) 1 else 0
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.tertiary
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(34.dp)
                                    .testTag("timeline_ai_review_button")
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

                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(
                                onClick = {
                                    expandedSessionId = if (isExpanded) null else s.id
                                }
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (isExpanded) "Hide Speaker Transcript"
                                    else "Show Speaker Transcript (${details.sentences.size} sentences)"
                                )
                            }

                            TextButton(
                                onClick = {
                                    val transcriptText = details.sentences.sortedBy { it.ordinal }
                                        .joinToString("\n") { sent ->
                                            val en = sent.textEn?.let { " | EN: $it" }.orEmpty()
                                            "[${sent.speakerLabel}] ${sent.textOriginal}$en"
                                        }
                                    val fullCopy = "${s.title}\n${s.summary.orEmpty()}\n\n$transcriptText"
                                    CopyFormatter.copyToClipboard(
                                        context = context,
                                        label = s.title ?: "Session",
                                        text = fullCopy,
                                        toastMsg = "Summary & Transcript copied"
                                    )
                                }
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy")
                            }
                        }

                        if (isExpanded && details.sentences.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            details.sentences.sortedBy { it.ordinal }.forEach { sent ->
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        text = "🗣️ ${sent.speakerLabel}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (transcriptLangMode != TranscriptLangMode.ENGLISH_ONLY) {
                                        Text(
                                            text = sent.textOriginal,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (transcriptLangMode != TranscriptLangMode.ORIGINAL_ONLY && !sent.textEn.isNullOrBlank()) {
                                        Text(
                                            text = "EN: ${sent.textEn}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                            text = when {
                                selectedSessionIds.isEmpty() -> "Select 1+ to export, or 2+ to combine"
                                selectedSessionIds.size == 1 -> "1 session selected (Export now or add 1+ to combine)"
                                else -> "${selectedSessionIds.size} sessions selected (Ready to export or combine)"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
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
                                val chosen = allSessions.filter { selectedSessionIds.contains(it.session.id) }
                                pendingReviewRequest = ReviewTargetRequest(
                                    title = "Merge & AI Review (${chosen.size} Sessions)",
                                    sessionIds = selectedSessionIds.toList(),
                                    rawAudioCount = chosen.count { ContinuousMergePlanner.hasRawAudio(it.session) },
                                    extractedInfoCount = chosen.count { ContinuousMergePlanner.hasExtractedInfo(it) }
                                )
                            },
                            modifier = Modifier
                                .weight(1.2f)
                                .height(48.dp)
                                .testTag("timeline_combine_en_button")
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Merge & AI Review", maxLines = 1)
                        }

                        OutlinedButton(
                            enabled = selectedSessionIds.size >= 2 &&
                                allSessions.any { selectedSessionIds.contains(it.session.id) && ContinuousMergePlanner.hasRawAudio(it.session) },
                            onClick = {
                                SessionAnalyzeWorker.enqueueCombinedAnalysis(
                                    context = context,
                                    sourceSessionIds = selectedSessionIds.toList(),
                                    mode = AnalysisMode.VERBATIM_ORIGINAL_TRANSCRIPT
                                )
                                Toast.makeText(
                                    context,
                                    "Combining ${selectedSessionIds.size} sessions for 100% transcript...",
                                    Toast.LENGTH_SHORT
                                ).show()
                                isSelectMode = false
                                selectedSessionIds = emptySet()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("timeline_combine_transcript_button")
                        ) {
                            Text("Transcript", maxLines = 1)
                        }
                    }

                    OutlinedButton(
                        enabled = selectedSessionIds.isNotEmpty(),
                        onClick = {
                            val chosen = selectedSessionIds.toSet()
                            isSelectMode = false
                            selectedSessionIds = emptySet()
                            onExportSelectedSessions(chosen)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .testTag("timeline_export_selected_button")
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (selectedSessionIds.isEmpty()) {
                                "Export Selected Sessions..."
                            } else {
                                "Export Selected (${selectedSessionIds.size})..."
                            },
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    val viewingDetails = remember(allSessions, viewingTranscriptSessionId) {
        allSessions.firstOrNull { it.session.id == viewingTranscriptSessionId }
    }
    if (viewingDetails != null) {
        val scope = rememberCoroutineScope()
        SessionTranscriptSheet(
            details = viewingDetails,
            defaultLangMode = transcriptLangMode,
            playbackController = playbackController,
            onDismiss = { viewingTranscriptSessionId = null },
            onReanalyze = { sessionId ->
                onReanalyze(sessionId)
            },
            onRenameSpeaker = { sessionId, speakerId, newLabel ->
                scope.launch {
                    val db = com.ambientbrain.recorder.data.AppDatabase.get(context).dao()
                    db.renameSpeakerAcrossSession(sessionId, speakerId, newLabel)
                }
            },
            onTogglePin = { sessionId, pinned ->
                onTogglePin(sessionId, pinned)
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
                            "Merging ${req.sessionIds.size} sessions using extracted info only..."
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
