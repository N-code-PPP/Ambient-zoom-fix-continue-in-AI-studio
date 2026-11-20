package com.ambientbrain.recorder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import com.ambientbrain.recorder.ai.ReviewSourceMode
import com.ambientbrain.recorder.ai.SessionAnalyzeWorker
import com.ambientbrain.recorder.ai.VoiceProfileAutoManager
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SessionSpeakerEntity
import com.ambientbrain.recorder.data.SpeakerProfileEntry
import com.ambientbrain.recorder.data.Status
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ambientbrain.recorder.data.PatientBlockEntity
import com.ambientbrain.recorder.data.SessionMode
import com.ambientbrain.recorder.data.SessionWithDetails
import com.ambientbrain.recorder.data.TranscriptLangMode
import com.ambientbrain.recorder.data.TranscriptSentenceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RoundsScreen(
    roundSessions: List<SessionWithDetails>,
    defaultLangMode: TranscriptLangMode,
    playbackController: AudioPlaybackController,
    onTogglePinSession: (String, Boolean) -> Unit,
    onRenameSpeaker: (sessionId: String, speakerId: String, newLabel: String) -> Unit,
    onReanalyzeSession: (String) -> Unit,
    onStartManualRound: () -> Unit
) {
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    val selectedDetails = remember(roundSessions, selectedSessionId) {
        roundSessions.firstOrNull { it.session.id == selectedSessionId }
    }

    if (selectedDetails != null) {
        BackHandler { selectedSessionId = null }
        RoundDetailView(
            details = selectedDetails,
            defaultLangMode = defaultLangMode,
            playbackController = playbackController,
            onBack = { selectedSessionId = null },
            onTogglePin = { onTogglePinSession(selectedDetails.session.id, !selectedDetails.session.isPinned) },
            onRenameSpeaker = { spkId, newLabel ->
                onRenameSpeaker(selectedDetails.session.id, spkId, newLabel)
            },
            onReanalyze = { onReanalyzeSession(selectedDetails.session.id) }
        )
    } else {
        if (roundSessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = "Medical & Neurology Rounds",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No rounds recorded yet. Tap '+ Start Round' or simply begin discussing patients — Ambient Brain automatically detects ward rounds and structures per-patient notes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onStartManualRound,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("+ Start Manual Round", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ward Rounds",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${roundSessions.size} session(s) • Auto-detected & manual rounds",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = onStartManualRound,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("+ Round", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                items(roundSessions, key = { it.session.id }) { details ->
                        RoundSummaryCard(
                            details = details,
                            onClick = { selectedSessionId = details.session.id },
                            onTogglePin = {
                                onTogglePinSession(details.session.id, !details.session.isPinned)
                            }
                        )
                    }
                }
            }
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoundSummaryCard(
    details: SessionWithDetails,
    onClick: () -> Unit,
    onTogglePin: () -> Unit
) {
    val s = details.session
    val dateStr = remember(s.startMs) {
        SimpleDateFormat("EEE, dd MMM HH:mm", Locale.getDefault()).format(Date(s.startMs))
    }
    val durationStr = remember(s.speechMs, s.startMs, s.endMs) {
        val durMs = if (s.speechMs > 0L) s.speechMs else (s.endMs - s.startMs).coerceAtLeast(0L)
        formatAudioDuration(durMs)
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                BadgePill(
                    text = if (s.mode == SessionMode.ROUND_MANUAL) "🩺 Manual Round" else "✨ Auto-Detected Round",
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$dateStr • $durationStr",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pin audio forever",
                        tint = if (s.isPinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = s.title ?: "Ward Round Session",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (!s.summary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = s.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BadgePill(
                    text = "🛏️ ${details.patients.size} Patient(s)",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
                BadgePill(
                    text = "☑️ ${details.items.size} Task(s)/Note(s)",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
                BadgePill(
                    text = "💬 ${details.sentences.size} Sentences",
                    containerColor = MaterialTheme.colorScheme.surface
                )
                if (s.hadPhoneCall) {
                    BadgePill(
                        text = "📞 Call Included",
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoundDetailView(
    details: SessionWithDetails,
    defaultLangMode: TranscriptLangMode,
    playbackController: AudioPlaybackController,
    onBack: () -> Unit,
    onTogglePin: () -> Unit,
    onRenameSpeaker: (speakerId: String, newLabel: String) -> Unit,
    onReanalyze: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepo(context) }
    var rosterRefreshTick by remember { mutableIntStateOf(0) }
    val speakerById = remember(details.speakers) { details.speakers.associateBy { it.speakerId } }
    val rosterById = remember(details.speakers, rosterRefreshTick) {
        prefs.getSpeakerRoster().associateBy { it.id }
    }
    val myVoiceSamples = remember(details.speakers, rosterRefreshTick) {
        prefs.getMyVoiceSamples()
    }
    var activeTab by remember { mutableIntStateOf(0) }
    var viewLangMode by remember(defaultLangMode) { mutableStateOf(defaultLangMode) }
    var speakerToRename by remember { mutableStateOf<Pair<String, String>?>(null) }
    var viewingSamplesSpeaker by remember { mutableStateOf<SessionSpeakerEntity?>(null) }
    var reviewRequest by remember { mutableStateOf<ReviewTargetRequest?>(null) }

    val rawFileExists = remember(details.session.rawPath, details.session.isRawPurged) {
        !details.session.isRawPurged &&
            !details.session.rawPath.isNullOrBlank() &&
            File(details.session.rawPath).exists()
    }
    val hasExtractedInfo = remember(details) {
        !details.session.summary.isNullOrBlank() ||
            !details.session.keyPointsJson.isNullOrBlank() ||
            details.sentences.isNotEmpty() ||
            details.items.isNotEmpty() ||
            details.patients.isNotEmpty()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Detail Header Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = details.session.title ?: "Medical Round",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = if (rawFileExists) {
                        if (details.session.isPinned) "📌 Pinned — Raw Audio Kept Forever" else "Raw Audio Available"
                    } else {
                        "Raw full audio auto-purged — ${details.items.size} task voice clips preserved"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "Pin Audio Forever",
                    tint = if (details.session.isPinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(
                onClick = {
                    reviewRequest = ReviewTargetRequest(
                        title = "AI Review Round",
                        sessionIds = listOf(details.session.id),
                        rawAudioCount = if (rawFileExists) 1 else 0,
                        extractedInfoCount = if (hasExtractedInfo) 1 else 0
                    )
                }
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "AI Review (Audio or Extracted Info)",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (rawFileExists) {
                IconButton(onClick = onReanalyze) {
                    Icon(Icons.Default.Refresh, contentDescription = "Re-analyze session")
                }
            }
            IconButton(
                onClick = {
                    CopyFormatter.shareText(
                        context = context,
                        subject = details.session.title ?: "Ward Round Handover",
                        text = CopyFormatter.formatEntireRound(details)
                    )
                }
            ) {
                Icon(Icons.Default.Share, contentDescription = "Share Round Sheet")
            }
            OutlinedButton(
                onClick = {
                    CopyFormatter.copyToClipboard(
                        context = context,
                        label = "Entire Round",
                        text = CopyFormatter.formatEntireRound(details),
                        toastMsg = "Entire Round Sheet copied!"
                    )
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Copy All", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Language Mode Switcher (TH+EN | TH | EN) + Full Audio Play Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = viewLangMode == TranscriptLangMode.BILINGUAL_TH_EN,
                    onClick = { viewLangMode = TranscriptLangMode.BILINGUAL_TH_EN },
                    label = { Text("TH + EN") }
                )
                FilterChip(
                    selected = viewLangMode == TranscriptLangMode.ORIGINAL_ONLY,
                    onClick = { viewLangMode = TranscriptLangMode.ORIGINAL_ONLY },
                    label = { Text("Thai Only") }
                )
                FilterChip(
                    selected = viewLangMode == TranscriptLangMode.ENGLISH_ONLY,
                    onClick = { viewLangMode = TranscriptLangMode.ENGLISH_ONLY },
                    label = { Text("EN Only") }
                )
            }

            if (rawFileExists && details.session.rawPath != null) {
                VoiceProofClipButton(
                    clipPath = details.session.rawPath,
                    clipDurationMs = details.session.speechMs,
                    playbackController = playbackController
                )
            }
        }

        val s = details.session
        val isFailed = s.status == Status.FAILED_RETRYABLE || s.status == Status.FAILED_PERMANENT || !s.lastError.isNullOrBlank()
        if (isFailed) {
            val minLeft = if (s.nextRetryEpochMs > System.currentTimeMillis()) {
                ((s.nextRetryEpochMs - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(1L)
            } else 0L

            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
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
                                "Gemini Connection Failed (3/3 Attempts)"
                            } else {
                                "Gemini Connection Failed • Auto-Retry Scheduled"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        if (rawFileExists) {
                            Button(
                                onClick = onReanalyze,
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
                        if (rawFileExists) {
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
        }

        TabRow(selectedTabIndex = activeTab) {
            Tab(
                selected = activeTab == 0,
                onClick = { activeTab = 0 },
                text = { Text("Patients (${details.patients.size})") }
            )
            Tab(
                selected = activeTab == 1,
                onClick = { activeTab = 1 },
                text = { Text("Speaker Transcript (${details.sentences.size})") }
            )
        }

        if (activeTab == 0) {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (!details.session.aiReviewReport.isNullOrBlank()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "✨ AI Review Audit (${if (details.session.reviewSourceMode == ReviewSourceMode.EXTRACTED_ONLY.name) "From Extracted Info" else "Audio Verified"})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = details.session.aiReviewReport,
                                    style = MaterialTheme.typography.bodyMedium
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

                if (!details.session.summary.isNullOrBlank()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Round Executive Summary (English)",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = details.session.summary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                items(details.patients, key = { it.id }) { patient ->
                    PatientRoundCard(
                        patient = patient,
                        sessionTitle = details.session.title ?: "Medical Round",
                        rawSessionPath = details.session.rawPath.takeIf { rawFileExists },
                        playbackController = playbackController,
                        onCopyPatient = {
                            CopyFormatter.copyToClipboard(
                                context = context,
                                label = patient.patientIdentifier,
                                text = CopyFormatter.formatPatientBlock(patient),
                                toastMsg = "Copied ${patient.patientIdentifier}"
                            )
                        },
                        onSharePatient = {
                            CopyFormatter.shareText(
                                context = context,
                                subject = patient.patientIdentifier,
                                text = CopyFormatter.formatPatientBlock(patient)
                            )
                        }
                    )
                }
            }
        } else {
            SpeakerLabeledTranscriptList(
                sentences = details.sentences.sortedBy { it.ordinal },
                langMode = viewLangMode,
                rawSessionPath = details.session.rawPath.takeIf { rawFileExists },
                sessionTitle = details.session.title ?: "Medical Round",
                playbackController = playbackController,
                speakersById = speakerById,
                onTapSpeakerPill = { spkId, currentLabel ->
                    speakerToRename = spkId to currentLabel
                },
                onSaveVoiceSampleFromSentence = { spkId, approxMs ->
                    scope.launch {
                        val (ok, msg) = withContext(Dispatchers.IO) {
                            VoiceProfileAutoManager.addSampleFromSessionSegment(
                                context = context,
                                sessionId = details.session.id,
                                speakerId = spkId,
                                centerMs = approxMs
                            )
                        }
                        if (ok) rosterRefreshTick++
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    if (reviewRequest != null) {
        BackHandler { reviewRequest = null }
        AiReviewChoiceDialog(
            request = reviewRequest!!,
            defaultIsAudio = prefs.defaultReviewSourceIsAudio,
            onDismiss = { reviewRequest = null },
            onConfirm = { sourceMode, _ ->
                SessionAnalyzeWorker.enqueueAiReview(
                    context = context,
                    sessionId = details.session.id,
                    sourceMode = sourceMode
                )
                Toast.makeText(
                    context,
                    if (sourceMode == ReviewSourceMode.EXTRACTED_ONLY) {
                        "Queued AI Review from saved extracted info..."
                    } else {
                        "Queued full AI re-analysis from raw audio..."
                    },
                    Toast.LENGTH_SHORT
                ).show()
                reviewRequest = null
            }
        )
    }

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
                        text = "Updates recognizing nickname & relatable title across sentences/tasks in this round and enrolls their voice clip for future matching:",
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
                                    sessionId = details.session.id,
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
}

@Composable
private fun PatientRoundCard(
    patient: PatientBlockEntity,
    sessionTitle: String? = null,
    rawSessionPath: String?,
    playbackController: AudioPlaybackController,
    onCopyPatient: () -> Unit,
    onSharePatient: () -> Unit = {}
) {
    val problems = remember(patient.problemsJson) { CopyFormatter.decodeStringList(patient.problemsJson) }
    val results = remember(patient.resultsJson) { CopyFormatter.decodeStringList(patient.resultsJson) }
    val pending = remember(patient.pendingJson) { CopyFormatter.decodeStringList(patient.pendingJson) }
    val plans = remember(patient.planJson) { CopyFormatter.decodePlans(patient.planJson) }
    val teaching = remember(patient.teachingJson) { CopyFormatter.decodeStringList(patient.teachingJson) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                ) {
                    Text(
                        text = "🛏️ ${patient.patientIdentifier}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (rawSessionPath != null) {
                    Surface(
                        onClick = {
                            playbackController.seekAndPlay(
                                filePath = rawSessionPath,
                                targetMs = patient.approxStartMs,
                                title = "$sessionTitle • ${patient.patientIdentifier}"
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = formatMsAsMmSs(patient.approxStartMs),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onSharePatient, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Share, contentDescription = "Share Patient Note", modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onCopyPatient, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Patient Note", modifier = Modifier.size(18.dp))
                }
            }

            if (patient.oneLiner.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = patient.oneLiner,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Dedicated Neuro Exam Callout Card
            if (!patient.neuroExam.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "🧠 Neuro Exam Findings",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = patient.neuroExam,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (patient.assessment.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Assessment: ${patient.assessment}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (problems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Problems: ${problems.joinToString(" • ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (results.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Labs/Imaging: ${results.joinToString(" • ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (pending.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "⏳ Pending: ${pending.joinToString(" • ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // Action Plan Categorized Orders
            if (plans.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Action Plan & Clinical Orders",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                plans.forEach { order ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when (order.category.uppercase()) {
                                "MEDICATION", "MED" -> MaterialTheme.colorScheme.primaryContainer
                                "LAB", "IMAGING", "RAD" -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surface
                            },
                            modifier = Modifier.padding(end = 6.dp, top = 2.dp)
                        ) {
                            Text(
                                text = order.category,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = order.text,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (order.needsVerification) {
                                Text(
                                    text = "⚠️ Verify dose / order with pharmacy",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFBBF24)
                                )
                            }
                        }
                    }
                }
            }

            // Teaching Pearls Callout Card
            if (teaching.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "💡 Teaching Pearls & Takeaways",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        teaching.forEach { pearl ->
                            Text(
                                text = "• $pearl",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpeakerLabeledTranscriptList(
    sentences: List<TranscriptSentenceEntity>,
    langMode: TranscriptLangMode,
    rawSessionPath: String?,
    sessionTitle: String? = null,
    playbackController: AudioPlaybackController,
    speakersById: Map<String, SessionSpeakerEntity> = emptyMap(),
    onTapSpeakerPill: (speakerId: String, currentLabel: String) -> Unit,
    onSaveVoiceSampleFromSentence: ((speakerId: String, approxMs: Long) -> Unit)? = null
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(sentences, key = { it.sentenceUuid }) { sent ->
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
                        val spkMeta = speakersById[sent.speakerId]
                        val displaySpeaker = spkMeta?.nickname?.takeIf { it.isNotBlank() } ?: sent.speakerLabel
                        val relatableTag = spkMeta?.relatableTitle?.takeIf {
                            it.isNotBlank() && !displaySpeaker.contains(it, ignoreCase = true)
                        }
                        // Tappable Speaker Pill (1-tap edit voice profile across round)
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
                                    onTapSpeakerPill(sent.speakerId, sent.speakerLabel)
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
                                        contentDescription = "Edit voice profile",
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
                        if (rawSessionPath != null && onSaveVoiceSampleFromSentence != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)),
                                modifier = Modifier.clickable {
                                    onSaveVoiceSampleFromSentence(sent.speakerId, sent.approxMs)
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = "Save voice sample from this sentence",
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = "+ Clip",
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

                        // Tappable Approximate Timestamp [▶ ~MM:SS]
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                            modifier = Modifier.clickable(enabled = rawSessionPath != null) {
                                if (rawSessionPath != null) {
                                    playbackController.seekAndPlay(
                                        filePath = rawSessionPath,
                                        targetMs = sent.approxMs,
                                        title = sessionTitle ?: "Medical Round"
                                    )
                                }
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                if (rawSessionPath != null) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                }
                                Text(
                                    text = "~${formatMsAsMmSs(sent.approxMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (langMode != TranscriptLangMode.ENGLISH_ONLY) {
                        Text(
                            text = sent.textOriginal,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (langMode != TranscriptLangMode.ORIGINAL_ONLY && !sent.textEn.isNullOrBlank()) {
                        if (langMode == TranscriptLangMode.BILINGUAL_TH_EN) {
                            Spacer(modifier = Modifier.height(3.dp))
                        }
                        Text(
                            text = sent.textEn,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (langMode == TranscriptLangMode.BILINGUAL_TH_EN) {
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

private fun formatMsAsMmSs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60L
    val s = totalSec % 60L
    return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}
