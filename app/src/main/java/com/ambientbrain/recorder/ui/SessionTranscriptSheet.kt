package com.ambientbrain.recorder.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.platform.testTag
import com.ambientbrain.recorder.ai.ContinuousMergePlanner
import com.ambientbrain.recorder.ai.ReviewSourceMode
import com.ambientbrain.recorder.ai.SessionAnalyzeWorker
import com.ambientbrain.recorder.ai.VoiceProfileAutoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ambientbrain.recorder.data.ItemType
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SessionSpeakerEntity
import com.ambientbrain.recorder.data.SessionWithDetails
import com.ambientbrain.recorder.data.SpeakerProfileEntry
import com.ambientbrain.recorder.data.Status
import com.ambientbrain.recorder.data.TranscriptLangMode
import com.ambientbrain.recorder.data.TranscriptSentenceEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionTranscriptSheet(
    details: SessionWithDetails,
    defaultLangMode: TranscriptLangMode,
    playbackController: AudioPlaybackController,
    onDismiss: () -> Unit,
    onReanalyze: (sessionId: String) -> Unit,
    onRequestOriginalTranscript: ((sessionId: String) -> Unit)? = null,
    onRenameSpeaker: (sessionId: String, speakerId: String, newLabel: String) -> Unit,
    onTogglePin: (sessionId: String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val triggerOriginalTranscript: (String) -> Unit = { id ->
        if (onRequestOriginalTranscript != null) {
            onRequestOriginalTranscript(id)
        } else {
            com.ambientbrain.recorder.ai.SessionAnalyzeWorker.requestOriginalTranscriptNow(context, id)
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val session = details.session

    var activeTab by remember { mutableIntStateOf(0) }
    var viewLangMode by remember { mutableStateOf(defaultLangMode) }
    var transcriptSearchQuery by remember { mutableStateOf("") }
    var speakerToRename by remember { mutableStateOf<Pair<String, String>?>(null) }
    var viewingSamplesSpeaker by remember { mutableStateOf<SessionSpeakerEntity?>(null) }
    var rosterRefreshTick by remember { mutableIntStateOf(0) }
    var pendingReviewRequest by remember { mutableStateOf<ReviewTargetRequest?>(null) }
    val prefs = remember { PreferencesRepo(context) }
    val speakerById = remember(details.speakers) { details.speakers.associateBy { it.speakerId } }
    val rosterById = remember(details.speakers, rosterRefreshTick) {
        prefs.getSpeakerRoster().associateBy { it.id }
    }
    val myVoiceSamples = remember(details.speakers, rosterRefreshTick) {
        prefs.getMyVoiceSamples()
    }

    val rawPath = session.rawPath
    val rawFile = remember(rawPath) {
        if (!rawPath.isNullOrBlank()) File(rawPath).takeIf { it.exists() && it.length() > 0L } else null
    }

    val isAnalyzing = session.status == Status.ANALYZING || session.status == Status.QUEUED || session.status == Status.CLIPPING
    val hasExtractedInfo = remember(details) { ContinuousMergePlanner.hasExtractedInfo(details) }
    val combinedSourceIds = remember(session.combinedSourceIdsJson) {
        session.combinedSourceIdsJson?.let { raw ->
            runCatching { Json.decodeFromString<List<String>>(raw) }.getOrNull()
        }.orEmpty()
    }
    val timeFormatted = remember(session.startMs) {
        SimpleDateFormat("EEE, dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(session.startMs))
    }
    val keyPoints = remember(session.keyPointsJson) {
        CopyFormatter.decodeStringList(session.keyPointsJson)
    }

    val isAudioActive = rawFile != null && playbackController.activeFilePath == rawFile.absolutePath
    val isPlaying = isAudioActive && playbackController.isPlaying
    val progress = if (isAudioActive) playbackController.progressFraction else 0f
    val currentPositionMs = if (isAudioActive) playbackController.currentPositionMs else 0L
    val effectiveDurationMs = if (isAudioActive && playbackController.durationMs > 0L) {
        playbackController.durationMs
    } else {
        if (session.speechMs > 0L) session.speechMs else (session.endMs - session.startMs).coerceAtLeast(1_000L)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxHeight(0.94f)
    ) {
        BackHandler {
            when {
                viewingSamplesSpeaker != null -> viewingSamplesSpeaker = null
                pendingReviewRequest != null -> pendingReviewRequest = null
                speakerToRename != null -> speakerToRename = null
                else -> onDismiss()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Header Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title ?: "Session Recording",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$timeFormatted • ${formatAudioDuration(effectiveDurationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Badges Row
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                BadgePill(
                    text = session.context ?: session.mode.name,
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )

                if (isAnalyzing) {
                    BadgePill(
                        text = "⏳ Analyzing with Gemini...",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                } else if (session.status == Status.DONE) {
                    BadgePill(
                        text = if (details.sentences.isNotEmpty()) "🗣️ ${details.sentences.size} Original Sentences" else "✅ EN Info Gathered",
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    )
                } else if (session.status == Status.FAILED_RETRYABLE || session.status == Status.FAILED_PERMANENT) {
                    BadgePill(
                        text = "⚠️ Connection Pending",
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                }

                if (session.isPinned) {
                    BadgePill(
                        text = "📌 Pinned",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                if (!session.combinedSourceIdsJson.isNullOrBlank()) {
                    BadgePill(
                        text = if (session.title?.startsWith("Auto-Merged") == true) "🤖 Auto-Merged" else "🔗 Combined Multi-Audio",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                } else if (!session.eventGroupId.isNullOrBlank() && session.eventGroupId != session.id) {
                    BadgePill(
                        text = "🔗 Linked Event",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                }

                if (session.reviewedAtMs > 0L || !session.aiReviewReport.isNullOrBlank()) {
                    val srcLabel = if (session.reviewSourceMode == ReviewSourceMode.EXTRACTED_ONLY.name) {
                        "✨ AI Reviewed (Extracted Info)"
                    } else {
                        "✨ AI Reviewed (Audio)"
                    }
                    BadgePill(
                        text = srcLabel,
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // DUAL GEMINI ACTION BUTTONS:
            // 1) Gather & Describe All Info in English
            // 2) Ask Gemini for 100% Original Transcription
            if (isAnalyzing) {
                Button(
                    onClick = {},
                    enabled = false,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "⏳ Gemini is processing audio...",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            activeTab = 0
                            val totalFiles = combinedSourceIds.size.coerceAtLeast(1)
                            pendingReviewRequest = ReviewTargetRequest(
                                title = "AI Review Options",
                                sessionIds = if (combinedSourceIds.isNotEmpty()) combinedSourceIds else listOf(session.id),
                                rawAudioCount = if (rawFile != null) totalFiles else 0,
                                extractedInfoCount = if (hasExtractedInfo) totalFiles else 0,
                                existingCombinedSessionId = if (combinedSourceIds.isNotEmpty()) session.id else null
                            )
                        },
                        enabled = hasExtractedInfo || rawFile != null,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sheet_ai_review_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Review",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (session.reviewedAtMs > 0L) {
                                "✨ Re-run AI Review (Choose Audio vs Extracted Info)"
                            } else {
                                "✨ Run AI Review (Choose Audio vs Extracted Info)"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                activeTab = 0
                                SessionAnalyzeWorker.reanalyzeFromAudioNow(context, session.id)
                                Toast.makeText(
                                    context,
                                    "Re-analyzing raw audio from Stage 1...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            enabled = rawFile != null,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Gather All Info in English",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "📋 Re-extract Audio (EN)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                activeTab = 1
                                triggerOriginalTranscript(session.id)
                                Toast.makeText(
                                    context,
                                    "Asking Gemini for 100% original verbatim transcription...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            enabled = rawFile != null,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = "100% Original Transcription",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "🗣️ 100% Transcript",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Secondary Quick Actions Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Copy Full Transcript & Summary
                OutlinedButton(
                    onClick = {
                        val transcriptText = details.sentences.sortedBy { it.ordinal }.joinToString("\n") { sent ->
                            val en = sent.textEn?.let { " | EN: $it" }.orEmpty()
                            "[${sent.speakerLabel}] ${sent.textOriginal}$en"
                        }
                        val fullDoc = buildString {
                            appendLine(session.title ?: "Session Audio")
                            appendLine(timeFormatted)
                            if (!session.summary.isNullOrBlank()) {
                                appendLine("\n--- CLINICAL SUMMARY ---")
                                appendLine(session.summary)
                            }
                            if (transcriptText.isNotBlank()) {
                                appendLine("\n--- VERBATIM TRANSCRIPT ---")
                                appendLine(transcriptText)
                            }
                        }
                        CopyFormatter.copyToClipboard(
                            context = context,
                            label = session.title ?: "Transcript",
                            text = fullDoc,
                            toastMsg = "Full transcript & clinical summary copied to clipboard"
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }

                // Share Text Summary & Transcript
                OutlinedButton(
                    onClick = {
                        val transcriptText = details.sentences.sortedBy { it.ordinal }.joinToString("\n") { sent ->
                            val en = sent.textEn?.let { " | EN: $it" }.orEmpty()
                            "[${sent.speakerLabel}] ${sent.textOriginal}$en"
                        }
                        val fullDoc = buildString {
                            appendLine(session.title ?: "Session Audio")
                            appendLine(timeFormatted)
                            if (!session.summary.isNullOrBlank()) {
                                appendLine("\n--- CLINICAL SUMMARY ---")
                                appendLine(session.summary)
                            }
                            if (transcriptText.isNotBlank()) {
                                appendLine("\n--- VERBATIM TRANSCRIPT ---")
                                appendLine(transcriptText)
                            }
                        }
                        CopyFormatter.shareText(
                            context = context,
                            subject = session.title ?: "Clinical Notes & Transcript",
                            text = fullDoc
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share Text", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }

                // Share Audio File
                if (rawFile != null) {
                    OutlinedButton(
                        onClick = {
                            shareAudioFile(context, rawFile, session.title ?: "Recorded Session")
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Audio", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }

                // Pin Toggle
                OutlinedButton(
                    onClick = { onTogglePin(session.id, !session.isPinned) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pin",
                        tint = if (session.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Audio Player Bar (if raw file exists)
            if (rawFile != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Surface(
                            onClick = {
                                playbackController.togglePlayFile(rawFile.absolutePath, title = session.title ?: "Session Audio")
                            },
                            shape = CircleShape,
                            color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            InteractiveAudioWaveform(
                                seedKey = rawFile.absolutePath,
                                progress = if (isAudioActive) progress else 0f,
                                isPlaying = isPlaying,
                                isMeaningful = details.sentences.isNotEmpty() || (session.summary?.isNotBlank() == true),
                                barCount = 42,
                                activeColor = MaterialTheme.colorScheme.primary,
                                inactiveColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                onSeek = { frac ->
                                    if (isAudioActive) {
                                        playbackController.seekToFraction(frac)
                                    } else {
                                        val dur = effectiveDurationMs.coerceAtLeast(1_000L)
                                        playbackController.seekAndPlay(rawFile.absolutePath, (frac * dur).toLong(), title = session.title ?: "Session Audio")
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(30.dp)
                                    .padding(vertical = 2.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = formatAudioDuration(currentPositionMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isAudioActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatAudioDuration(effectiveDurationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (isAudioActive) {
                            IconButton(onClick = { playbackController.skipBackward(10_000L) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Replay10, contentDescription = "-10s", modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { playbackController.skipForward(10_000L) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Forward10, contentDescription = "+10s", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tab Navigation: [📋 All Info (English)] | [🗣️ 100% Original Transcript]
            TabRow(selectedTabIndex = activeTab) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = { Text("📋 All Info (English)") }
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = { Text("🗣️ 100% Original (${details.sentences.size})") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (activeTab == 0) {
                // Tab 0: All Gathered Information in English & Extracted Orders
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // AI Review Audit Card (when AI Review has been completed)
                    if (!session.aiReviewReport.isNullOrBlank()) {
                        item {
                            val reviewSourceLabel = if (session.reviewSourceMode == ReviewSourceMode.EXTRACTED_ONLY.name) {
                                "Source: Previously Extracted Info Only"
                            } else {
                                "Source: Re-analyzed Raw Audio"
                            }
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_review_report_card")
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.tertiary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Column {
                                                Text(
                                                    text = "AI Review Audit & Quality Check",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.tertiary
                                                )
                                                Text(
                                                    text = reviewSourceLabel,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                CopyFormatter.copyToClipboard(
                                                    context = context,
                                                    label = "AI Review Audit",
                                                    text = session.aiReviewReport,
                                                    toastMsg = "AI Review report copied"
                                                )
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy AI Review", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = session.aiReviewReport,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    if (details.speakers.isNotEmpty()) {
                        item {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Recognized Voice Profiles (${details.speakers.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    details.speakers.forEach { spk ->
                                        val displayNick = spk.nickname?.takeIf { it.isNotBlank() } ?: spk.speakerLabel
                                        val titleText = spk.relatableTitle?.takeIf { it.isNotBlank() }
                                            ?: spk.inferredRole?.takeIf { it.isNotBlank() }
                                        val sigText = spk.voiceSignature?.takeIf { it.isNotBlank() }
                                        val badgeLabel = when {
                                            spk.isUser -> "You"
                                            spk.isAutoEnrolled -> "Auto Profile"
                                            !spk.matchedProfileId.isNullOrBlank() -> "Saved"
                                            else -> "Session"
                                        }
                                        val isUserSpk = spk.isUser || spk.matchedProfileId == "me"
                                        val pid = spk.matchedProfileId?.takeIf { it.isNotBlank() && it != "me" }
                                        val spkSamples = when {
                                            isUserSpk -> myVoiceSamples
                                            pid != null -> rosterById[pid]?.existingSamples().orEmpty()
                                            else -> emptyList()
                                        }
                                        val bestSample = spkSamples.firstOrNull()
                                        val sampleFile = bestSample?.filePath?.let { File(it) }
                                            ?.takeIf { it.exists() && it.length() >= 1024L }
                                            ?: when {
                                                isUserSpk -> prefs.getBestMyVoiceFile()
                                                pid != null -> prefs.getSpeakerProfileAudioFile(pid)
                                                    .takeIf { it.exists() && it.length() >= 1024L }
                                                else -> null
                                            }
                                        val canOpenSamplesDialog = isUserSpk || pid != null
                                        val isPlayingSample = sampleFile != null &&
                                            playbackController.activeFilePath == sampleFile.absolutePath &&
                                            playbackController.isPlaying

                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    speakerToRename = spk.speakerId to spk.speakerLabel
                                                }
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    FlowRow(
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Text(
                                                            text = displayNick,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        BadgePill(
                                                            text = badgeLabel,
                                                            containerColor = if (spk.isUser || spk.isAutoEnrolled) {
                                                                MaterialTheme.colorScheme.primaryContainer
                                                            } else {
                                                                MaterialTheme.colorScheme.secondaryContainer
                                                            }
                                                        )
                                                        if (spkSamples.isNotEmpty()) {
                                                            val qSuffix = bestSample?.qualityScore
                                                                ?.takeIf { it > 0f }
                                                                ?.let { " • Q${it.toInt()}" }
                                                                .orEmpty()
                                                            BadgePill(
                                                                text = "${spkSamples.size}/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE}$qSuffix",
                                                                containerColor = bestSample?.qualityScore
                                                                    ?.takeIf { it > 0f }
                                                                    ?.let { voiceQualityBadgeColor(it) }
                                                                    ?: MaterialTheme.colorScheme.tertiaryContainer
                                                            )
                                                        }
                                                    }
                                                    if (!titleText.isNullOrBlank()) {
                                                        Text(
                                                            text = titleText,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                    if (!sigText.isNullOrBlank()) {
                                                        Text(
                                                            text = sigText,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                if (sampleFile != null) {
                                                    IconButton(
                                                        onClick = {
                                                            playbackController.togglePlayFile(
                                                                filePath = sampleFile.absolutePath,
                                                                title = displayNick
                                                            )
                                                        },
                                                        modifier = Modifier.size(34.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (isPlayingSample) Icons.Default.Stop else Icons.Default.PlayArrow,
                                                            contentDescription = if (isPlayingSample) "Stop voice clip" else "Play best voice clip",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                                if (canOpenSamplesDialog) {
                                                    IconButton(
                                                        onClick = { viewingSamplesSpeaker = spk },
                                                        modifier = Modifier.size(34.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.GraphicEq,
                                                            contentDescription = "View & export voice samples",
                                                            tint = MaterialTheme.colorScheme.secondary,
                                                            modifier = Modifier.size(17.dp)
                                                        )
                                                    }
                                                }
                                                IconButton(
                                                    onClick = {
                                                        speakerToRename = spk.speakerId to spk.speakerLabel
                                                    },
                                                    modifier = Modifier.size(34.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Edit,
                                                        contentDescription = "Edit voice profile",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Exhaustive English Information Card
                    item {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "All Gathered Conversation Info (English)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    IconButton(
                                        onClick = {
                                            CopyFormatter.copyToClipboard(
                                                context = context,
                                                label = "Conversation Info (EN)",
                                                text = session.summary.orEmpty(),
                                                toastMsg = "English conversation info copied"
                                            )
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy info", modifier = Modifier.size(16.dp))
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                if (!session.summary.isNullOrBlank()) {
                                    Text(
                                        text = session.summary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                } else {
                                    Text(
                                        text = if (isAnalyzing) "Gemini is currently gathering and describing all information from this recording in English..." else "No English information breakdown generated yet. Tap '📋 Describe All Info (EN)' above.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Key Points if any
                    if (keyPoints.isNotEmpty()) {
                        item {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Key Gathered Facts & Figures (English)",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                        IconButton(
                                            onClick = {
                                                val bulletText = keyPoints.joinToString("\n") { "• $it" }
                                                CopyFormatter.copyToClipboard(
                                                    context = context,
                                                    label = "Key Points",
                                                    text = bulletText,
                                                    toastMsg = "${keyPoints.size} key points copied"
                                                )
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy key points",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    keyPoints.forEach { kp ->
                                        val catMatch = Regex("^(\\[[^]]+])\\s*(.*)$").find(kp)
                                        if (catMatch != null) {
                                            val tag = catMatch.groupValues[1]
                                            val body = catMatch.groupValues[2]
                                            Text(
                                                text = buildAnnotatedString {
                                                    append("• ")
                                                    withStyle(
                                                        SpanStyle(
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    ) {
                                                        append(tag)
                                                    }
                                                    append(" ")
                                                    append(body)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                            )
                                        } else {
                                            Text(
                                                text = "• $kp",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Extracted Orders, Tasks & Verification Items
                    if (details.items.isNotEmpty()) {
                        item {
                            Text(
                                text = "Extracted Tasks, Notes & Decisions (${details.items.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        items(details.items, key = { it.id }) { item ->
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (item.needsVerification) {
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    }
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (item.needsVerification) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        BadgePill(
                                            text = item.type.name,
                                            containerColor = if (item.type == ItemType.TASK) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (item.needsVerification) {
                                            Icon(
                                                imageVector = Icons.Default.WarningAmber,
                                                contentDescription = "Needs verification",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    if (item.details.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = item.details,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    if (item.quoteOriginal.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "🗣️ \"${item.quoteOriginal}\"",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Tab 1: 100% Original Verbatim Speaker Transcript
                Column(modifier = Modifier.fillMaxSize()) {
                    // Language Switcher Chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        FilterChip(
                            selected = viewLangMode == TranscriptLangMode.BILINGUAL_TH_EN,
                            onClick = { viewLangMode = TranscriptLangMode.BILINGUAL_TH_EN },
                            label = { Text("Original + EN") }
                        )
                        FilterChip(
                            selected = viewLangMode == TranscriptLangMode.ORIGINAL_ONLY,
                            onClick = { viewLangMode = TranscriptLangMode.ORIGINAL_ONLY },
                            label = { Text("100% Original Only") }
                        )
                        FilterChip(
                            selected = viewLangMode == TranscriptLangMode.ENGLISH_ONLY,
                            onClick = { viewLangMode = TranscriptLangMode.ENGLISH_ONLY },
                            label = { Text("English Only") }
                        )
                    }

                    if (details.sentences.isNotEmpty()) {
                        OutlinedTextField(
                            value = transcriptSearchQuery,
                            onValueChange = { transcriptSearchQuery = it },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                if (transcriptSearchQuery.isNotBlank()) {
                                    IconButton(onClick = { transcriptSearchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            placeholder = { Text("Search speaker or transcript text...") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        )
                    }

                    val displayedSentences = remember(details.sentences, transcriptSearchQuery) {
                        val sorted = details.sentences.sortedBy { it.ordinal }
                        if (transcriptSearchQuery.isBlank()) sorted
                        else {
                            val q = transcriptSearchQuery.trim().lowercase()
                            sorted.filter {
                                it.speakerLabel.lowercase().contains(q) ||
                                    it.textOriginal.lowercase().contains(q) ||
                                    (it.textEn?.lowercase()?.contains(q) == true)
                            }
                        }
                    }

                    if (details.sentences.isEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = if (isAnalyzing) "Gemini is generating the 100% original verbatim transcript..." else "100% Original Verbatim Transcript Not Generated Yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "By default, Gemini gathers and describes all conversation information in English first. Tap the button below to ask Gemini for a 100% original word-for-word transcription of this recording.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        triggerOriginalTranscript(session.id)
                                        Toast.makeText(
                                            context,
                                            "Asking Gemini for 100% original verbatim transcription...",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                        contentColor = MaterialTheme.colorScheme.onSecondary
                                    ),
                                    enabled = !isAnalyzing && rawFile != null
                                ) {
                                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("🗣️ Ask Gemini for 100% Original Transcription")
                                }
                            }
                        }
                    } else if (displayedSentences.isEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize().padding(24.dp)
                        ) {
                            Text(
                                text = "No transcript lines matching '$transcriptSearchQuery'",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        var savingSampleUuid by remember { mutableStateOf<String?>(null) }
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(displayedSentences, key = { it.sentenceUuid }) { sent ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            val spkMeta = speakerById[sent.speakerId]
                                            val displaySpeaker = spkMeta?.nickname?.takeIf { it.isNotBlank() } ?: sent.speakerLabel
                                            val relatableTag = spkMeta?.relatableTitle?.takeIf {
                                                it.isNotBlank() && !displaySpeaker.contains(it, ignoreCase = true)
                                            }
                                            // Speaker Pill (Tappable to edit voice profile)
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                                    modifier = Modifier.clickable {
                                                        speakerToRename = sent.speakerId to sent.speakerLabel
                                                    }
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    ) {
                                                        Text(
                                                            text = displaySpeaker,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Icon(
                                                            imageVector = Icons.Default.Edit,
                                                            contentDescription = "Edit speaker profile",
                                                            modifier = Modifier.size(12.dp),
                                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                    }
                                                }
                                                if (!relatableTag.isNullOrBlank()) {
                                                    Text(
                                                        text = relatableTag,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(6.dp))

                                            // 1-Tap Save Voice Sample from Sentence (+ Clip)
                                            if (rawFile != null) {
                                                val isSavingThis = savingSampleUuid == sent.sentenceUuid
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)),
                                                    modifier = Modifier.clickable(enabled = savingSampleUuid == null) {
                                                        savingSampleUuid = sent.sentenceUuid
                                                        scope.launch {
                                                            val (ok, msg) = withContext(Dispatchers.IO) {
                                                                VoiceProfileAutoManager.addSampleFromSessionSegment(
                                                                    context = context,
                                                                    sessionId = session.id,
                                                                    speakerId = sent.speakerId,
                                                                    centerMs = sent.approxMs
                                                                )
                                                            }
                                                            if (ok) rosterRefreshTick++
                                                            savingSampleUuid = null
                                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.GraphicEq,
                                                            contentDescription = "Save voice sample from this sentence",
                                                            modifier = Modifier.size(12.dp),
                                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                                        )
                                                        Spacer(modifier = Modifier.width(2.dp))
                                                        Text(
                                                            text = if (isSavingThis) "..." else "+ Clip",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            maxLines = 1,
                                                            softWrap = false
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }

                                            // Timestamp Seek Pill [▶ ~MM:SS]
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                                                modifier = Modifier.clickable(enabled = rawFile != null) {
                                                    if (rawFile != null) {
                                                        playbackController.seekAndPlay(rawFile.absolutePath, sent.approxMs, title = session.title ?: "Session Audio")
                                                    }
                                                }
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(12.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                    Text(
                                                        text = "~${formatAudioDuration(sent.approxMs)}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        // Sentence Text (Original Thai and/or English translation)
                                        if (viewLangMode != TranscriptLangMode.ENGLISH_ONLY) {
                                            Text(
                                                text = sent.textOriginal,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        if (viewLangMode != TranscriptLangMode.ORIGINAL_ONLY && !sent.textEn.isNullOrBlank()) {
                                            if (viewLangMode == TranscriptLangMode.BILINGUAL_TH_EN) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                            }
                                            Text(
                                                text = if (viewLangMode == TranscriptLangMode.BILINGUAL_TH_EN) "EN: ${sent.textEn}" else sent.textEn,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (viewLangMode == TranscriptLangMode.BILINGUAL_TH_EN) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Rename & Voice Profile Dialog
    if (speakerToRename != null) {
        val (spkId, currentLabel) = speakerToRename!!
        val existingSpk = speakerById[spkId]
        val existingProfile = existingSpk?.matchedProfileId?.let { rosterById[it] }
        var editedNick by remember(spkId, existingSpk) {
            mutableStateOf(existingSpk?.nickname?.takeIf { it.isNotBlank() } ?: currentLabel)
        }
        var editedTitle by remember(spkId, existingSpk) {
            mutableStateOf(
                existingSpk?.relatableTitle?.takeIf { it.isNotBlank() }
                    ?: existingSpk?.inferredRole?.takeIf { it.isNotBlank() }
                    ?: existingProfile?.effectiveRelatableTitle().orEmpty()
            )
        }
        var editedSig by remember(spkId, existingSpk) {
            mutableStateOf(
                existingSpk?.voiceSignature?.takeIf { it.isNotBlank() }
                    ?: existingProfile?.voiceSignature?.ifBlank { existingProfile.voiceHint }.orEmpty()
            )
        }
        var saveToRoster by remember(spkId) { mutableStateOf(true) }
        var pinProfile by remember(spkId, existingProfile) {
            mutableStateOf(existingProfile?.isPinned == true)
        }

        AlertDialog(
            onDismissRequest = { speakerToRename = null },
            title = { Text("Edit Voice Profile ($spkId)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Updates recognizing nickname & relatable title across sentences/tasks in this session and enrolls their voice clip for future matching:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = editedNick,
                        onValueChange = { editedNick = it },
                        singleLine = true,
                        label = { Text("Recognizing Nickname *") },
                        placeholder = { Text("e.g., Dr. Arthit, Nurse Pim") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedTitle,
                        onValueChange = { editedTitle = it },
                        singleLine = true,
                        label = { Text("Relatable Title / Role") },
                        placeholder = { Text("e.g., Senior Neuro Attending, Charge Nurse") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedSig,
                        onValueChange = { editedSig = it },
                        maxLines = 2,
                        label = { Text("Voice Notes / Signature") },
                        placeholder = { Text("e.g., Warm calm baritone, fast Thai cadence") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { saveToRoster = !saveToRoster }
                    ) {
                        Checkbox(
                            checked = saveToRoster,
                            onCheckedChange = { saveToRoster = it }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Save to Auto Voice Profiles & slice clean clip",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = saveToRoster) { pinProfile = !pinProfile }
                    ) {
                        Checkbox(
                            checked = pinProfile && saveToRoster,
                            enabled = saveToRoster,
                            onCheckedChange = { pinProfile = it }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Pin profile (prioritize attachment & protect from pruning)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (saveToRoster) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = editedNick.isNotBlank(),
                    onClick = {
                        val cleanNick = editedNick.trim()
                        val cleanTitle = editedTitle.trim()
                        val cleanSig = editedSig.trim()
                        val shouldSave = saveToRoster
                        val shouldPin = pinProfile && shouldSave
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                VoiceProfileAutoManager.renameOrUpdateSpeakerProfile(
                                    context = context,
                                    sessionId = session.id,
                                    speakerId = spkId,
                                    newNickname = cleanNick,
                                    newRelatableTitle = cleanTitle,
                                    voiceSignature = cleanSig,
                                    saveToRoster = shouldSave,
                                    pinProfile = shouldPin
                                )
                            }
                            rosterRefreshTick++
                            Toast.makeText(
                                context,
                                "Updated voice profile: $cleanNick",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        speakerToRename = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { speakerToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    viewingSamplesSpeaker?.let { target ->
        val isUser = target.isUser || target.matchedProfileId == "me"
        val profileId = target.matchedProfileId?.takeIf { it.isNotBlank() && it != "me" }
        VoiceProfileSamplesDialog(
            prefs = prefs,
            isUserProfile = isUser,
            speakerProfileId = profileId,
            fallbackProfileName = target.nickname?.takeIf { it.isNotBlank() } ?: target.speakerLabel,
            fallbackRelatableTitle = target.relatableTitle?.takeIf { it.isNotBlank() }
                ?: target.inferredRole.orEmpty(),
            fallbackVoiceSignature = target.voiceSignature.orEmpty(),
            playbackController = playbackController,
            onSamplesChanged = { rosterRefreshTick++ },
            onDismissRequest = { viewingSamplesSpeaker = null }
        )
    }

    pendingReviewRequest?.let { req ->
        AiReviewChoiceDialog(
            request = req,
            defaultIsAudio = prefs.defaultReviewSourceIsAudio,
            onDismiss = { pendingReviewRequest = null },
            onConfirm = { sourceMode, wantTranscript ->
                if (req.existingCombinedSessionId != null && req.sessionIds.size >= 2) {
                    SessionAnalyzeWorker.enqueueCombinedWithSourceChoice(
                        context = context,
                        sourceSessionIds = req.sessionIds,
                        sourceMode = sourceMode,
                        wantVerbatimTranscript = wantTranscript,
                        existingCombinedSessionId = req.existingCombinedSessionId
                    )
                } else {
                    if (wantTranscript && sourceMode == ReviewSourceMode.REANALYZE_AUDIO) {
                        triggerOriginalTranscript(session.id)
                    } else {
                        SessionAnalyzeWorker.enqueueAiReview(
                            context = context,
                            sessionId = session.id,
                            sourceMode = sourceMode
                        )
                    }
                }
                Toast.makeText(
                    context,
                    if (sourceMode == ReviewSourceMode.EXTRACTED_ONLY) {
                        "Running AI Review using previously extracted info only..."
                    } else {
                        "Re-analyzing raw audio for AI Review..."
                    },
                    Toast.LENGTH_SHORT
                ).show()
                pendingReviewRequest = null
            }
        )
    }
}
