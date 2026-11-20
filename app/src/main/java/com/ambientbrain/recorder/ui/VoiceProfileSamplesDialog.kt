package com.ambientbrain.recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.ambientbrain.recorder.ai.SpeakerEmbeddingEngine
import com.ambientbrain.recorder.data.AppDataExporter
import com.ambientbrain.recorder.data.ClipSlicer
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.VoiceProfileSample
import com.ambientbrain.recorder.service.VoiceProfileRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

fun voiceQualityTierLabel(score: Float): String = when {
    score >= 82f -> "Excellent"
    score >= 68f -> "Clear"
    score >= 52f -> "Good"
    score >= 38f -> "Fair"
    else -> "Weak"
}

fun voiceQualityBadgeColor(score: Float): Color = when {
    score >= 82f -> Color(0xFF10B981).copy(alpha = 0.22f)
    score >= 68f -> Color(0xFF06B6D4).copy(alpha = 0.22f)
    score >= 52f -> Color(0xFF3B82F6).copy(alpha = 0.22f)
    score >= 38f -> Color(0xFFF59E0B).copy(alpha = 0.22f)
    else -> Color(0xFFEF4444).copy(alpha = 0.22f)
}

/**
 * Reusable modal viewer & exporter for any Voice Profile ("My Voice" or a Speaker Roster profile).
 * Shows up to 10 highest-quality recorded voice samples ranked best-first, with acoustic quality
 * scores (SNR, active speech %, duration, file size), inline audio playback, live recording of
 * additional samples, deletion, individual `.m4a` Save/Share, and full-profile `.zip` export.
 */
@Composable
fun VoiceProfileSamplesDialog(
    prefs: PreferencesRepo,
    isUserProfile: Boolean,
    speakerProfileId: String? = null,
    fallbackProfileName: String = "",
    fallbackRelatableTitle: String = "",
    fallbackVoiceSignature: String = "",
    playbackController: AudioPlaybackController? = null,
    onSamplesChanged: () -> Unit = {},
    onDismissRequest: () -> Unit
) {
    if (playbackController != null) {
        VoiceProfileSamplesDialogContent(
            prefs = prefs,
            isUserProfile = isUserProfile,
            speakerProfileId = speakerProfileId,
            fallbackProfileName = fallbackProfileName,
            fallbackRelatableTitle = fallbackRelatableTitle,
            fallbackVoiceSignature = fallbackVoiceSignature,
            playbackController = playbackController,
            onSamplesChanged = onSamplesChanged,
            onDismissRequest = onDismissRequest
        )
    } else {
        val localPlayer = rememberAudioPlaybackController()
        VoiceProfileSamplesDialogContent(
            prefs = prefs,
            isUserProfile = isUserProfile,
            speakerProfileId = speakerProfileId,
            fallbackProfileName = fallbackProfileName,
            fallbackRelatableTitle = fallbackRelatableTitle,
            fallbackVoiceSignature = fallbackVoiceSignature,
            playbackController = localPlayer,
            onSamplesChanged = onSamplesChanged,
            onDismissRequest = onDismissRequest
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceProfileSamplesDialogContent(
    prefs: PreferencesRepo,
    isUserProfile: Boolean,
    speakerProfileId: String?,
    fallbackProfileName: String,
    fallbackRelatableTitle: String,
    fallbackVoiceSignature: String,
    playbackController: AudioPlaybackController,
    onSamplesChanged: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refreshTick by remember { mutableIntStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    var recordedSec by remember { mutableIntStateOf(0) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var pendingSaveSample by remember { mutableStateOf<Pair<VoiceProfileSample, Int>?>(null) }

    val rosterEntry = remember(refreshTick, isUserProfile, speakerProfileId) {
        if (!isUserProfile && !speakerProfileId.isNullOrBlank()) {
            prefs.getSpeakerRoster().firstOrNull { it.id == speakerProfileId }
        } else {
            null
        }
    }

    val profileName = remember(refreshTick, isUserProfile, rosterEntry, fallbackProfileName) {
        if (isUserProfile) {
            prefs.userDisplayName.ifBlank { fallbackProfileName.ifBlank { "My Voice" } }
        } else {
            rosterEntry?.effectiveNickname()?.ifBlank { fallbackProfileName }
                ?: fallbackProfileName.ifBlank { "Voice Profile" }
        }
    }
    val relatableTitle = remember(refreshTick, isUserProfile, rosterEntry, fallbackRelatableTitle) {
        if (isUserProfile) {
            fallbackRelatableTitle.ifBlank { "Device Owner / Primary User" }
        } else {
            rosterEntry?.effectiveRelatableTitle()?.ifBlank { fallbackRelatableTitle }
                ?: fallbackRelatableTitle
        }
    }
    val voiceSignature = remember(refreshTick, isUserProfile, rosterEntry, fallbackVoiceSignature) {
        if (isUserProfile) {
            fallbackVoiceSignature
        } else {
            rosterEntry?.voiceSignature?.ifBlank { rosterEntry.voiceHint }?.ifBlank { fallbackVoiceSignature }
                ?: fallbackVoiceSignature
        }
    }
    val samples: List<VoiceProfileSample> = remember(refreshTick, isUserProfile, rosterEntry) {
        if (isUserProfile) {
            prefs.getMyVoiceSamples()
        } else {
            rosterEntry?.existingSamples().orEmpty()
        }
    }

    val canRecordForProfile = isUserProfile || rosterEntry != null
    val recordTargetSeconds = if (isUserProfile) 12 else 10
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    val saveSingleSampleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/mp4")
    ) { uri ->
        val target = pendingSaveSample
        pendingSaveSample = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        val (sample, rank) = target
        busyMessage = "Exporting Sample #$rank as .m4a..."
        scope.launch {
            runCatching {
                AppDataExporter.exportVoiceSampleToUri(
                    context = context,
                    sampleFile = File(sample.filePath),
                    targetUri = uri
                )
            }.onSuccess { bytes ->
                Toast.makeText(
                    context,
                    "Saved Sample #$rank (${AppDataExporter.formatBytesHuman(bytes)})",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    "Export failed: ${err.message ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
            busyMessage = null
        }
    }

    val saveAllZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busyMessage = "Building .voiceprofile.zip bundle for ${samples.size} sample(s)..."
        scope.launch {
            runCatching {
                AppDataExporter.exportProfileSamplesZipToUri(
                    context = context,
                    profileName = profileName,
                    relatableTitle = relatableTitle,
                    voiceSignature = voiceSignature,
                    isUser = isUserProfile,
                    samples = samples,
                    targetUri = uri,
                    profileId = rosterEntry?.id.orEmpty(),
                    aliases = rosterEntry?.aliases.orEmpty(),
                    isPinned = rosterEntry?.isPinned ?: false,
                    sessionsHeardCount = rosterEntry?.sessionsHeardCount ?: 1
                )
            }.onSuccess { res ->
                Toast.makeText(context, res.summary, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    "ZIP export failed: ${err.message ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
            busyMessage = null
        }
    }

    val importAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!isUserProfile && speakerProfileId.isNullOrBlank()) return@rememberLauncherForActivityResult
        if (playbackController.isPlaying) {
            playbackController.stop()
        }
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        val uriStr = uri.toString().lowercase()
        val isZipBundle = mime.contains("zip") || uriStr.endsWith(".zip")

        if (isZipBundle) {
            busyMessage = "Importing .voiceprofile.zip bundle..."
            scope.launch {
                runCatching {
                    AppDataExporter.importVoiceProfileZipFromUri(
                        context = context,
                        zipUri = uri,
                        targetIsUserProfile = isUserProfile,
                        targetSpeakerProfileId = speakerProfileId
                    )
                }.onSuccess { res ->
                    refreshTick++
                    onSamplesChanged()
                    Toast.makeText(context, res.summaryMessage, Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    Toast.makeText(
                        context,
                        "Profile ZIP import failed: ${err.message ?: "Unknown error"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                busyMessage = null
            }
            return@rememberLauncherForActivityResult
        }

        busyMessage = "Importing & converting to lossless 16 kHz WAV..."
        scope.launch {
            val outcome: Pair<Boolean, String> = withContext(Dispatchers.IO) {
                val newSampleId = UUID.randomUUID().toString().take(8)
                val destFile = if (isUserProfile) {
                    prefs.getMyVoiceSampleWavFile(newSampleId)
                } else {
                    prefs.getSpeakerSampleWavFile(speakerProfileId!!, newSampleId)
                }
                val converted = ClipSlicer.importAudioUriToAdtsSample(
                    context = context,
                    sourceUri = uri,
                    outAdtsFile = destFile,
                    maxDurationMs = 15_000L
                )
                if (!converted || !destFile.exists() || destFile.length() < 1024L) {
                    runCatching { destFile.delete() }
                    return@withContext false to "Could not decode audio file (choose a speech clip >= 1s)."
                }
                val metrics = ClipSlicer.analyzeAdtsSampleQuality(destFile)
                val embedding = SpeakerEmbeddingEngine.extractEmbeddingFromFile(context, destFile)
                val manualScore = (metrics.score + 10f).coerceIn(10f, 100f)
                val newSample = VoiceProfileSample(
                    id = newSampleId,
                    filePath = destFile.absolutePath,
                    durationMs = metrics.durationMs.takeIf { it > 0L }
                        ?: ClipSlicer.estimateAdtsDurationMs(destFile),
                    qualityScore = manualScore,
                    snrDb = metrics.snrDb,
                    speechRatio = metrics.speechRatio,
                    createdAtMs = System.currentTimeMillis(),
                    sourceSessionId = null,
                    isManual = true,
                    embedding = embedding
                )
                val accepted = if (isUserProfile) {
                    prefs.addMyVoiceSample(newSample).first
                } else {
                    prefs.addSpeakerVoiceSample(speakerProfileId!!, newSample).first
                }
                val tier = voiceQualityTierLabel(manualScore)
                if (accepted) {
                    true to "Imported lossless WAV clip (Q${manualScore.toInt()} • $tier) into top ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE}!"
                } else {
                    false to "Imported clip (Q${manualScore.toInt()} • $tier) scored below existing top ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE}."
                }
            }
            refreshTick++
            onSamplesChanged()
            Toast.makeText(context, outcome.second, Toast.LENGTH_SHORT).show()
            busyMessage = null
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isRecording) onDismissRequest()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 20.dp)
                .heightIn(max = 680.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = profileName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (relatableTitle.isNotBlank()) {
                            Text(
                                text = relatableTitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (voiceSignature.isNotBlank()) {
                            Text(
                                text = voiceSignature,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(
                        enabled = !isRecording,
                        onClick = onDismissRequest,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Sample count & ranking info bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BadgePill(
                        text = "${samples.size} / ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} Best Samples",
                        containerColor = if (samples.isNotEmpty()) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    val topSample = samples.firstOrNull()
                    if (topSample != null) {
                        BadgePill(
                            text = "Best Q${topSample.qualityScore.toInt()} • ${voiceQualityTierLabel(topSample.qualityScore)}",
                            containerColor = voiceQualityBadgeColor(topSample.qualityScore)
                        )
                    }
                }

                Text(
                    text = "Keeps up to ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} highest-quality voice clips ranked by acoustic clarity (SNR, active speech, modulation). Top diverse samples are attached to Gemini for accurate recognition.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (busyMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = busyMessage!!,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (isRecording) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Recording voice sample... ${recordedSec}s / ${recordTargetSeconds}s",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(
                            progress = { (recordedSec.toFloat() / recordTargetSeconds.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Scrollable Samples List
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (samples.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "No recorded voice samples yet",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (canRecordForProfile) {
                                        "Tap 'Record' or 'Import Audio' below, or analyze recordings with Auto-Recognize enabled to capture up to ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} best-quality clips."
                                    } else {
                                        "Save this speaker to Voice Profiles first to store, play, and export up to ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} recorded voice samples."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        samples.forEachIndexed { idx, sample ->
                            val rank = idx + 1
                            val sampleFile = File(sample.filePath)
                            val fileSizeText = remember(sample.filePath) {
                                AppDataExporter.formatBytesHuman(sampleFile.length())
                            }
                            val durationMs = remember(sample.filePath, sample.durationMs) {
                                sample.durationMs.takeIf { it > 0L }
                                    ?: runCatching { ClipSlicer.estimateAdtsDurationMs(sampleFile) }.getOrDefault(0L)
                            }
                            val isThisPlaying = playbackController.activeFilePath == sample.filePath &&
                                playbackController.isPlaying
                            val isThisActive = playbackController.activeFilePath == sample.filePath

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (rank == 1) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (rank == 1) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        BadgePill(
                                            text = if (rank == 1) "#1 BEST" else "#$rank",
                                            containerColor = if (rank == 1) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            }
                                        )
                                        BadgePill(
                                            text = "Q${sample.qualityScore.toInt()} • ${voiceQualityTierLabel(sample.qualityScore)}",
                                            containerColor = voiceQualityBadgeColor(sample.qualityScore)
                                        )
                                        BadgePill(
                                            text = if (sample.isManual) "Manual" else "Auto",
                                            containerColor = if (sample.isManual) {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.tertiaryContainer
                                            }
                                        )
                                        if (sample.embedding.isNotEmpty()) {
                                            BadgePill(
                                                text = "Voiceprint",
                                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                            )
                                        }
                                        val isWavSample = remember(sample.filePath) {
                                            ClipSlicer.isWavFile(sampleFile)
                                        }
                                        BadgePill(
                                            text = when {
                                                isWavSample -> "Lossless WAV"
                                                sample.filePath.endsWith(".m4a", ignoreCase = true) -> "M4A"
                                                else -> "AAC"
                                            },
                                            containerColor = if (isWavSample) {
                                                Color(0xFF10B981).copy(alpha = 0.20f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            }
                                        )
                                    }

                                    val metaParts = buildList {
                                        if (durationMs > 0L) add(formatAudioDuration(durationMs))
                                        if (sample.snrDb > 0f) add("SNR ${sample.snrDb.toInt()} dB")
                                        if (sample.speechRatio > 0f) add("${(sample.speechRatio * 100f).toInt()}% speech")
                                        add(fileSizeText)
                                        if (sample.createdAtMs > 0L) {
                                            add(dateFmt.format(Date(sample.createdAtMs)))
                                        }
                                    }
                                    Text(
                                        text = metaParts.joinToString(" • "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    if (isThisActive && (isThisPlaying || playbackController.progressFraction > 0f)) {
                                        LinearProgressIndicator(
                                            progress = { playbackController.progressFraction.coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    // Clean single-line action row: Play | Save | Share | Delete
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        FilledTonalButton(
                                            enabled = !isRecording && sampleFile.exists(),
                                            onClick = {
                                                playbackController.togglePlayFile(
                                                    filePath = sample.filePath,
                                                    title = "$profileName — Sample #$rank"
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isThisPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                                contentDescription = if (isThisPlaying) "Stop sample" else "Play sample",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isThisPlaying) "Stop" else "Play",
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }

                                        OutlinedButton(
                                            enabled = !isRecording && busyMessage == null && sampleFile.exists(),
                                            onClick = {
                                                val rankTag = if (rank == 1) "sample1_best" else "sample$rank"
                                                val outExt = if (ClipSlicer.isWavFile(sampleFile)) "wav" else "m4a"
                                                pendingSaveSample = sample to rank
                                                saveSingleSampleLauncher.launch(
                                                    AppDataExporter.buildSafeProfileFileName(profileName, rankTag, outExt)
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "Save audio sample",
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Save",
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        OutlinedButton(
                                            enabled = !isRecording && busyMessage == null && sampleFile.exists(),
                                            onClick = {
                                                busyMessage = "Preparing Sample #$rank..."
                                                scope.launch {
                                                    runCatching {
                                                        val shared = AppDataExporter.prepareVoiceSampleShareFile(
                                                            context = context,
                                                            sampleFile = sampleFile,
                                                            profileName = profileName,
                                                            rankIndex = rank
                                                        )
                                                        AppDataExporter.launchAudioFileShareIntent(
                                                            context = context,
                                                            file = shared,
                                                            subject = "$profileName — Voice Sample #$rank"
                                                        )
                                                    }.onFailure { err ->
                                                        Toast.makeText(
                                                            context,
                                                            "Share failed: ${err.message ?: "Unknown error"}",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                    busyMessage = null
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Share sample",
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Share",
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        IconButton(
                                            enabled = !isRecording && busyMessage == null,
                                            onClick = {
                                                if (playbackController.activeFilePath == sample.filePath) {
                                                    playbackController.stop()
                                                }
                                                if (isUserProfile) {
                                                    prefs.removeMyVoiceSample(sample.id)
                                                } else if (!speakerProfileId.isNullOrBlank()) {
                                                    prefs.removeSpeakerVoiceSample(speakerProfileId, sample.id)
                                                }
                                                refreshTick++
                                                onSamplesChanged()
                                                Toast.makeText(context, "Deleted Sample #$rank", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete sample #$rank",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Footer actions: Record New Sample (Lossless WAV) + Import Audio/.zip + Export (.voiceprofile.zip)
                if (canRecordForProfile) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            enabled = !isRecording && busyMessage == null,
                            onClick = {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasMic) {
                                    Toast.makeText(
                                        context,
                                        "Microphone permission is required to record a voice sample.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@Button
                                }
                                if (playbackController.isPlaying) {
                                    playbackController.stop()
                                }
                                isRecording = true
                                recordedSec = 0
                                scope.launch {
                                    val newSampleId = UUID.randomUUID().toString().take(8)
                                    val outFile = if (isUserProfile) {
                                        prefs.getMyVoiceSampleWavFile(newSampleId)
                                    } else {
                                        prefs.getSpeakerSampleWavFile(speakerProfileId!!, newSampleId)
                                    }
                                    val ok = VoiceProfileRecorder.recordVoiceClipToFile(
                                        outFile = outFile,
                                        durationSeconds = recordTargetSeconds,
                                        onProgressSec = { sec -> recordedSec = sec }
                                    )
                                    if (ok && outFile.exists() && outFile.length() >= 1024L) {
                                        val (metrics, embedding) = withContext(Dispatchers.IO) {
                                            ClipSlicer.analyzeAdtsSampleQuality(outFile) to
                                                SpeakerEmbeddingEngine.extractEmbeddingFromFile(context, outFile)
                                        }
                                        val manualScore = (metrics.score + 10f).coerceIn(10f, 100f)
                                        val newSample = VoiceProfileSample(
                                            id = newSampleId,
                                            filePath = outFile.absolutePath,
                                            durationMs = metrics.durationMs.takeIf { it > 0L }
                                                ?: (recordTargetSeconds * 1000L),
                                            qualityScore = manualScore,
                                            snrDb = metrics.snrDb,
                                            speechRatio = metrics.speechRatio,
                                            createdAtMs = System.currentTimeMillis(),
                                            sourceSessionId = null,
                                            isManual = true,
                                            embedding = embedding
                                        )
                                        val accepted = if (isUserProfile) {
                                            prefs.addMyVoiceSample(newSample).first
                                        } else {
                                            prefs.addSpeakerVoiceSample(speakerProfileId!!, newSample).first
                                        }
                                        refreshTick++
                                        onSamplesChanged()
                                        val tier = voiceQualityTierLabel(manualScore)
                                        Toast.makeText(
                                            context,
                                            if (accepted) {
                                                "Saved lossless WAV sample (Q${manualScore.toInt()} • $tier) into top ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE}!"
                                            } else {
                                                "Recorded sample (Q${manualScore.toInt()} • $tier) scored below existing top ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE}."
                                            },
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        runCatching { outFile.delete() }
                                        Toast.makeText(
                                            context,
                                            "Could not record sample — check mic access.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    isRecording = false
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1.15f)
                                .height(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isRecording) {
                                    "Rec ${recordedSec}s/${recordTargetSeconds}s"
                                } else {
                                    "Record ${recordTargetSeconds}s (WAV)"
                                },
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            enabled = !isRecording && busyMessage == null,
                            onClick = {
                                importAudioLauncher.launch(
                                    arrayOf(
                                        "audio/*",
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/octet-stream"
                                    )
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.UploadFile,
                                contentDescription = "Import audio clip or .voiceprofile.zip",
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Import Audio / .zip",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (samples.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            enabled = !isRecording && busyMessage == null,
                            onClick = {
                                saveAllZipLauncher.launch(
                                    AppDataExporter.buildSafeProfileFileName(profileName, "voiceprofile", "zip")
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderZip,
                                contentDescription = "Save profile bundle as .voiceprofile.zip",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Export (.voiceprofile.zip)",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            enabled = !isRecording && busyMessage == null,
                            onClick = {
                                busyMessage = "Preparing .voiceprofile.zip bundle for sharing..."
                                scope.launch {
                                    runCatching {
                                        val res = AppDataExporter.prepareProfileSamplesZipForShare(
                                            context = context,
                                            profileName = profileName,
                                            relatableTitle = relatableTitle,
                                            voiceSignature = voiceSignature,
                                            isUser = isUserProfile,
                                            samples = samples,
                                            profileId = rosterEntry?.id.orEmpty(),
                                            aliases = rosterEntry?.aliases.orEmpty(),
                                            isPinned = rosterEntry?.isPinned ?: false,
                                            sessionsHeardCount = rosterEntry?.sessionsHeardCount ?: 1
                                        )
                                        AppDataExporter.launchShareIntent(context, res)
                                    }.onFailure { err ->
                                        Toast.makeText(
                                            context,
                                            "Share ZIP failed: ${err.message ?: "Unknown error"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    busyMessage = null
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share all as .voiceprofile.zip",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Share (.voiceprofile.zip)",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
