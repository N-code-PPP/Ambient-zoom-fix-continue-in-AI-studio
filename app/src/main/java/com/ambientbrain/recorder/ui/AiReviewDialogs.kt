package com.ambientbrain.recorder.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ambientbrain.recorder.ai.ContinuousCluster
import com.ambientbrain.recorder.ai.ReviewSourceMode

data class ReviewTargetRequest(
    val title: String,
    val sessionIds: List<String>,
    val rawAudioCount: Int,
    val extractedInfoCount: Int,
    val existingCombinedSessionId: String? = null,
    val isContinuousAutoMerge: Boolean = false,
    val clustersToBatchMerge: List<ContinuousCluster> = emptyList()
)

/**
 * Interactive chooser letting the user explicitly pick whether Gemini AI Review / Merge should:
 *  1) Use Previously Extracted Information Only (fast text synthesis, zero audio upload), OR
 *  2) Re-analyze the Raw Audio Files Again (full acoustic re-listen + speaker matching + voice clipping).
 */
@Composable
fun AiReviewChoiceDialog(
    request: ReviewTargetRequest,
    defaultIsAudio: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (sourceMode: ReviewSourceMode, wantVerbatimTranscript: Boolean) -> Unit
) {
    val canUseExtracted = request.extractedInfoCount > 0
    val canUseRawAudio = request.rawAudioCount > 0

    var selectedSource by remember(request) {
        mutableStateOf(
            when {
                defaultIsAudio && canUseRawAudio -> ReviewSourceMode.REANALYZE_AUDIO
                canUseExtracted -> ReviewSourceMode.EXTRACTED_ONLY
                canUseRawAudio -> ReviewSourceMode.REANALYZE_AUDIO
                else -> ReviewSourceMode.EXTRACTED_ONLY
            }
        )
    }
    var wantVerbatimTranscript by remember(request) { mutableStateOf(false) }

    val totalCount = request.sessionIds.size.coerceAtLeast(1)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = request.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (request.clustersToBatchMerge.size > 1) {
                        "Merging ${request.clustersToBatchMerge.size} continuous audio groups ($totalCount files). Choose how AI should review and consolidate them:"
                    } else if (totalCount > 1) {
                        "Selected $totalCount recordings (${request.extractedInfoCount} with extracted info, ${request.rawAudioCount} with raw audio on disk). Choose how AI should review and merge them:"
                    } else {
                        "Choose whether AI should review only previously extracted records or re-listen to the saved audio file again:"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Option 1: Use Previously Extracted Information Only
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedSource == ReviewSourceMode.EXTRACTED_ONLY) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    },
                    border = BorderStroke(
                        width = if (selectedSource == ReviewSourceMode.EXTRACTED_ONLY) 1.5.dp else 1.dp,
                        color = if (selectedSource == ReviewSourceMode.EXTRACTED_ONLY) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canUseExtracted) {
                            selectedSource = ReviewSourceMode.EXTRACTED_ONLY
                            wantVerbatimTranscript = false
                        }
                        .testTag("review_choice_extracted_card")
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(10.dp)
                    ) {
                        RadioButton(
                            selected = selectedSource == ReviewSourceMode.EXTRACTED_ONLY,
                            enabled = canUseExtracted,
                            onClick = {
                                if (canUseExtracted) {
                                    selectedSource = ReviewSourceMode.EXTRACTED_ONLY
                                    wantVerbatimTranscript = false
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.TextSnippet,
                                    contentDescription = null,
                                    tint = if (canUseExtracted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Use Previously Extracted Info Only",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (canUseExtracted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (canUseExtracted) {
                                    "Fast text-only AI review & consolidation from saved summaries, key points, transcripts, patients, and tasks. Zero audio upload; works even if raw audio was purged."
                                } else {
                                    "No previously extracted info yet—select 'Re-analyze Audio' first."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Option 2: Re-analyze Audio Files Again
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedSource == ReviewSourceMode.REANALYZE_AUDIO) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    },
                    border = BorderStroke(
                        width = if (selectedSource == ReviewSourceMode.REANALYZE_AUDIO) 1.5.dp else 1.dp,
                        color = if (selectedSource == ReviewSourceMode.REANALYZE_AUDIO) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canUseRawAudio) {
                            selectedSource = ReviewSourceMode.REANALYZE_AUDIO
                        }
                        .testTag("review_choice_audio_card")
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(10.dp)
                    ) {
                        RadioButton(
                            selected = selectedSource == ReviewSourceMode.REANALYZE_AUDIO,
                            enabled = canUseRawAudio,
                            onClick = {
                                if (canUseRawAudio) {
                                    selectedSource = ReviewSourceMode.REANALYZE_AUDIO
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = if (canUseRawAudio) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Re-analyze Audio File(s) Again",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (canUseRawAudio) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (canUseRawAudio) {
                                    "Re-listens to the saved .aac/.m4a recording(s) + voice profiles to verify doses, recover missed details, audit discrepancies, and re-slice voice proof clips."
                                } else {
                                    "Unavailable: raw audio file(s) have been purged or cannot be found on disk."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (selectedSource == ReviewSourceMode.REANALYZE_AUDIO && canUseRawAudio && request.clustersToBatchMerge.isEmpty() && totalCount > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { wantVerbatimTranscript = !wantVerbatimTranscript }
                            .padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = wantVerbatimTranscript,
                            onCheckedChange = { wantVerbatimTranscript = it }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Generate 100% Verbatim Transcript instead of AI Review",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            val isActionEnabled = when (selectedSource) {
                ReviewSourceMode.EXTRACTED_ONLY -> canUseExtracted
                ReviewSourceMode.REANALYZE_AUDIO -> canUseRawAudio
            }
            Button(
                enabled = isActionEnabled,
                onClick = { onConfirm(selectedSource, wantVerbatimTranscript) },
                modifier = Modifier.testTag("review_choice_confirm_button")
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (selectedSource == ReviewSourceMode.EXTRACTED_ONLY) {
                        "Run with Extracted Info"
                    } else if (wantVerbatimTranscript) {
                        "Run Verbatim Transcript"
                    } else {
                        "Re-analyze Audio"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Compact collapsible banner surfacing detected related continuous audio groups in Audio Vault
 * and Timeline with 1-tap Merge & AI Review actions.
 */
@Composable
fun ContinuousClustersBanner(
    clusters: List<ContinuousCluster>,
    onAutoMergePending: (List<ContinuousCluster>) -> Unit,
    onMergeCluster: (ContinuousCluster) -> Unit,
    onOpenMergedSession: (String) -> Unit
) {
    if (clusters.isEmpty()) return

    val pendingClusters = remember(clusters) { clusters.filter { it.needsMerge } }
    var expanded by remember(pendingClusters.size) { mutableStateOf(pendingClusters.isNotEmpty()) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("continuous_clusters_banner")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Icon(
                    imageVector = Icons.Default.Merge,
                    contentDescription = "Continuous audio groups",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (pendingClusters.isNotEmpty()) {
                            "${pendingClusters.size} Related Continuous Group(s) Ready to Merge"
                        } else {
                            "${clusters.size} Continuous Group(s) Auto-Merged"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (pendingClusters.isNotEmpty()) {
                            "Consecutive files from the same event/round can be merged & reviewed"
                        } else {
                            "Tap to inspect or re-review continuous merged events"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (pendingClusters.isNotEmpty()) {
                    Button(
                        onClick = { onAutoMergePending(pendingClusters) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("auto_merge_all_button")
                    ) {
                        Text(
                            text = if (pendingClusters.size > 1) "Merge All (${pendingClusters.size})" else "Auto-Merge",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse groups" else "Expand groups",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    clusters.take(4).forEach { cluster ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cluster.titlePreview,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${cluster.sharedGroupReason} • ${formatAudioDuration(cluster.totalSpeechMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (!cluster.needsMerge && cluster.existingCombinedSessionId != null) {
                                OutlinedButton(
                                    onClick = { onOpenMergedSession(cluster.existingCombinedSessionId) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("View Merged", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                Button(
                                    onClick = { onMergeCluster(cluster) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Merge & Review", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
