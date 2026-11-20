package com.ambientbrain.recorder.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambientbrain.recorder.ai.GeminiInteractionsClient
import com.ambientbrain.recorder.ai.GeminiRateLimitManager
import com.ambientbrain.recorder.ai.SessionAnalyzeWorker
import com.ambientbrain.recorder.data.GeminiPauseStateBus
import com.ambientbrain.recorder.data.GeminiRateLimitProfile
import com.ambientbrain.recorder.data.GeminiRateLimitSnapshot
import com.ambientbrain.recorder.data.PreferencesRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AmberAccent = Color(0xFFF59E0B)
private val EmeraldAccent = Color(0xFF10B981)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeminiRateLimitControlsCard(
    prefs: PreferencesRepo,
    selectedModel: String,
    fallbackModel: String,
    savedKeys: List<String>,
    queuedSessionsCount: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pauseState by GeminiPauseStateBus.state.collectAsStateWithLifecycle()
    val rlTelemetry by GeminiRateLimitManager.telemetry.collectAsStateWithLifecycle()

    var useDefaultRef by remember { mutableStateOf(prefs.useDefaultRateLimitReference) }
    var activeSnapshot by remember { mutableStateOf(GeminiRateLimitManager.getActiveSnapshot(prefs)) }
    var isCheckingGoogle by remember { mutableStateOf(false) }
    var checkProgressText by remember { mutableStateOf<String?>(null) }
    var showLimitsTable by remember { mutableStateOf(false) }
    var nowTickMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Keep telemetry and snapshot synchronized when model, keys, or toggle changes
    LaunchedEffect(selectedModel, savedKeys.size, useDefaultRef) {
        prefs.syncGeminiPauseStateBus()
        activeSnapshot = GeminiRateLimitManager.getActiveSnapshot(prefs)
        GeminiRateLimitManager.refreshTelemetry(prefs, selectedModel)
    }

    // Live 1s countdown & auto-expiry check when Gemini is paused on a timer
    LaunchedEffect(pauseState.isPaused, pauseState.pausedUntilMs) {
        while (pauseState.isPaused) {
            val now = System.currentTimeMillis()
            nowTickMs = now
            if (pauseState.pausedUntilMs > 0L && now >= pauseState.pausedUntilMs) {
                prefs.isGeminiUsagePausedNow(now)
                SessionAnalyzeWorker.resumeAllQueuedAfterUnpause(context)
                break
            }
            delay(1_000L)
        }
    }

    val isActivelyPaused = pauseState.isActivelyPaused(nowTickMs)
    val remainingPauseMs = if (isActivelyPaused && pauseState.pausedUntilMs > 0L) {
        (pauseState.pausedUntilMs - nowTickMs).coerceAtLeast(0L)
    } else 0L

    // 1. Temporary Gemini AI Pause Card
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActivelyPaused) {
                AmberAccent.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
        ),
        border = BorderStroke(
            1.dp,
            if (isActivelyPaused) AmberAccent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isActivelyPaused) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                        contentDescription = if (isActivelyPaused) "Gemini AI Paused" else "Gemini AI Active",
                        tint = if (isActivelyPaused) AmberAccent else EmeraldAccent,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "Pause Gemini AI Calls",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isActivelyPaused) {
                                if (remainingPauseMs > 0L) {
                                    "Paused — auto-resumes in ${formatRemainingShort(remainingPauseMs)}"
                                } else {
                                    "Paused indefinitely until you resume"
                                }
                            } else {
                                "Active — recordings analyze automatically with RPM/TPM pacing"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isActivelyPaused) AmberAccent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = isActivelyPaused,
                    onCheckedChange = { shouldPause ->
                        if (shouldPause) {
                            prefs.setGeminiPaused(paused = true, durationMs = 0L)
                            Toast.makeText(
                                context,
                                "Gemini AI paused. Audio will keep recording & queueing safely.",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            prefs.setGeminiPaused(paused = false, durationMs = 0L)
                            SessionAnalyzeWorker.resumeAllQueuedAfterUnpause(context)
                            Toast.makeText(
                                context,
                                if (queuedSessionsCount > 0) {
                                    "Gemini AI resumed — dispatching $queuedSessionsCount queued session(s)..."
                                } else {
                                    "Gemini AI resumed."
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.testTag("gemini_pause_switch")
                )
            }

            // Quick duration presets for temporary pause
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val presets = listOf(
                    Triple("Until Resumed", 0L, "pause_preset_indefinite"),
                    Triple("15 min", 15L * 60_000L, "pause_preset_15m"),
                    Triple("1 hour", 60L * 60_000L, "pause_preset_1h"),
                    Triple("4 hours", 4L * 60L * 60_000L, "pause_preset_4h")
                )
                presets.forEach { (label, durationMs, tag) ->
                    val isSelected = isActivelyPaused && when {
                        durationMs == 0L -> pauseState.pausedUntilMs <= 0L
                        else -> pauseState.pausedUntilMs > 0L &&
                            remainingPauseMs in ((durationMs - 10L * 60_000L).coerceAtLeast(1L)..(durationMs + 5_000L))
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            prefs.setGeminiPaused(paused = true, durationMs = durationMs)
                            nowTickMs = System.currentTimeMillis()
                            val msg = if (durationMs > 0L) {
                                "Gemini AI paused for $label. Queued audio resumes automatically after."
                            } else {
                                "Gemini AI paused until manually resumed."
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.testTag(tag)
                    )
                }
            }

            if (isActivelyPaused || queuedSessionsCount > 0) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (isActivelyPaused) {
                                "Audio capture continues normally. $queuedSessionsCount session(s) queued waiting for resume."
                            } else {
                                "$queuedSessionsCount session(s) currently queued for Gemini analysis."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (isActivelyPaused) {
                            Spacer(modifier = Modifier.width(8.dp))
                            BadgePill(
                                text = "PAUSED",
                                containerColor = AmberAccent.copy(alpha = 0.24f)
                            )
                        }
                    }
                }
            }
        }
    }

    // 2. Adaptive RPM / TPM Rate-Limit Strategy & Live Google Checker Card
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "RPM and TPM Rate Limit Strategy",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "RPM / TPM Rate-Limit Strategy",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Prevents 429 bursts via 60s rolling pacing, least-loaded key routing & TPM audio batch caps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Status Badges: Source + Detected Tier + Active Keys
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BadgePill(
                    text = rlTelemetry.activeSourceLabel,
                    containerColor = if (useDefaultRef) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                    } else {
                        EmeraldAccent.copy(alpha = 0.22f)
                    }
                )
                BadgePill(
                    text = rlTelemetry.detectedTierLabel,
                    containerColor = if (activeSnapshot.detectedTier == GeminiRateLimitSnapshot.TIER_PAID_1) {
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                )
                BadgePill(
                    text = "${savedKeys.size.coerceAtLeast(0)} Key(s) in Pool",
                    containerColor = MaterialTheme.colorScheme.surface
                )
            }

            // Live 60s Rolling RPM & TPM Usage Meters
            val rpmProgress = if (rlTelemetry.safeRpmLimit > 0) {
                (rlTelemetry.usedRpmInWindow.toFloat() / rlTelemetry.safeRpmLimit.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val tpmProgress = if (rlTelemetry.safeTpmLimit > 0L) {
                (rlTelemetry.usedTpmInWindow.toFloat() / rlTelemetry.safeTpmLimit.toFloat()).coerceIn(0f, 1f)
            } else 0f

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Live 60s Window — ${rlTelemetry.activeModel}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // RPM Bar
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Requests / min (RPM)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${rlTelemetry.usedRpmInWindow} / ${rlTelemetry.safeRpmLimit} safe (cap ${rlTelemetry.rawRpmLimit})",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { rpmProgress },
                            color = when {
                                rpmProgress >= 0.85f -> MaterialTheme.colorScheme.error
                                rpmProgress >= 0.6f -> AmberAccent
                                else -> MaterialTheme.colorScheme.primary
                            },
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                    }

                    // TPM Bar
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Input Tokens / min (TPM)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${formatCompactTokens(rlTelemetry.usedTpmInWindow)} / ${formatCompactTokens(rlTelemetry.safeTpmLimit)} safe (cap ${formatCompactTokens(rlTelemetry.rawTpmLimit)})",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { tpmProgress },
                            color = when {
                                tpmProgress >= 0.85f -> MaterialTheme.colorScheme.error
                                tpmProgress >= 0.6f -> AmberAccent
                                else -> EmeraldAccent
                            },
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                    }

                    // Active Pacing & Audio Batch Strategy Details
                    val (primaryProfile, tier) = remember(activeSnapshot, selectedModel, useDefaultRef) {
                        GeminiRateLimitManager.getActiveProfileForModel(prefs, selectedModel)
                    }
                    val (rawRpm, rawTpm, rawRpd) = remember(primaryProfile, tier) {
                        GeminiRateLimitManager.effectiveRawLimits(primaryProfile, tier)
                    }
                    val safeRpmPerKey = remember(rawRpm) { GeminiRateLimitManager.safeRpmTarget(rawRpm) }
                    val safeTpmPerKey = remember(rawTpm) { GeminiRateLimitManager.safeTpmTarget(rawTpm) }
                    val minSpacingSec = remember(safeRpmPerKey) {
                        GeminiRateLimitManager.minSpacingMsForRpm(safeRpmPerKey) / 1000.0
                    }
                    val maxBatchMb = remember(activeSnapshot, selectedModel, useDefaultRef) {
                        GeminiRateLimitManager.recommendedMaxAudioBatchBytes(
                            prefs = prefs,
                            modelId = selectedModel,
                            baseLimitBytes = 24L * 1024L * 1024L
                        ) / (1024.0 * 1024.0)
                    }

                    Text(
                        text = String.format(
                            Locale.US,
                            "Per-key strategy: %d safe RPM (%.1fs min gap) • %s safe TPM • %d RPD • Max %.1f MB audio/batch",
                            safeRpmPerKey,
                            minSpacingSec,
                            formatCompactTokens(safeTpmPerKey),
                            rawRpd,
                            maxBatchMb
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (fallbackModel != PreferencesRepo.FALLBACK_MODEL_NONE && fallbackModel != selectedModel) {
                        val (fbProfile, fbTier) = remember(activeSnapshot, fallbackModel, useDefaultRef) {
                            GeminiRateLimitManager.getActiveProfileForModel(prefs, fallbackModel)
                        }
                        val (fbRpm, fbTpm, _) = remember(fbProfile, fbTier) {
                            GeminiRateLimitManager.effectiveRawLimits(fbProfile, fbTier)
                        }
                        Text(
                            text = "Proactive failover -> $fallbackModel (${GeminiRateLimitManager.safeRpmTarget(fbRpm)} safe RPM / ${formatCompactTokens(GeminiRateLimitManager.safeTpmTarget(fbTpm))} safe TPM)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    Text(
                        text = "Engine: ${rlTelemetry.lastActionNote}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Live Check from Google Button
            Button(
                onClick = {
                    if (isCheckingGoogle) return@Button
                    scope.launch {
                        isCheckingGoogle = true
                        checkProgressText = "Connecting to Google AI Studio & rate-limit docs..."
                        val result = runCatching {
                            GeminiInteractionsClient().checkLiveRateLimitsFromGoogle(
                                apiKeys = savedKeys,
                                primaryModel = selectedModel,
                                onProgress = { msg -> checkProgressText = msg }
                            )
                        }
                        isCheckingGoogle = false
                        result.onSuccess { snapshot ->
                            prefs.saveLiveRateLimitSnapshot(snapshot)
                            prefs.useDefaultRateLimitReference = false
                            useDefaultRef = false
                            activeSnapshot = snapshot
                            GeminiRateLimitManager.refreshTelemetry(
                                prefs = prefs,
                                model = selectedModel,
                                actionNote = "Live Google limits loaded (${snapshot.detectedTier})"
                            )
                            checkProgressText = null
                            Toast.makeText(
                                context,
                                "Rate limits updated from Google (${snapshot.statusSummary})",
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure { err ->
                            checkProgressText = "Live check failed: ${err.message ?: "Network error"}"
                            Toast.makeText(
                                context,
                                "Could not fetch Google rate limits: ${err.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                enabled = !isCheckingGoogle,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("check_google_rate_limits_button")
            ) {
                if (isCheckingGoogle) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = checkProgressText ?: "Checking Google rate limits...",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = "Check Rate Limits from Google",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Check Current Rate Limits from Google & Adapt",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (!checkProgressText.isNullOrBlank() && !isCheckingGoogle) {
                Text(
                    text = checkProgressText!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (activeSnapshot.statusSummary.isNotBlank()) {
                val checkedLabel = if (activeSnapshot.checkedAtMs > 0L) {
                    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
                    "Checked ${fmt.format(Date(activeSnapshot.checkedAtMs))} • ${activeSnapshot.statusSummary}"
                } else {
                    activeSnapshot.statusSummary
                }
                Text(
                    text = checkedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Reset Rate Limit Reference Toggle (Nov 20, 2026 Default)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = "Reset Rate Limit Reference to Nov 20, 2026 Default",
                        tint = if (useDefaultRef) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Reset / Use Default Reference (Nov 20, 2026)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (useDefaultRef) {
                                "ON — Locked to built-in Nov 20, 2026 limits & default conservative strategy"
                            } else {
                                "OFF — Adapting dynamically from Google live checks & 429 quota telemetry"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = useDefaultRef,
                    onCheckedChange = { enabled ->
                        useDefaultRef = enabled
                        prefs.useDefaultRateLimitReference = enabled
                        if (enabled) {
                            GeminiRateLimitManager.clearRollingLedger(prefs)
                            activeSnapshot = GeminiRateLimitManager.getActiveSnapshot(prefs)
                            GeminiRateLimitManager.refreshTelemetry(
                                prefs = prefs,
                                model = selectedModel,
                                actionNote = "Reset to Nov 20, 2026 default strategy"
                            )
                            Toast.makeText(
                                context,
                                "Reset to Nov 20, 2026 default rate limits & strategy",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            activeSnapshot = GeminiRateLimitManager.getActiveSnapshot(prefs)
                            val hasLive = prefs.getLiveRateLimitSnapshot() != null
                            GeminiRateLimitManager.refreshTelemetry(
                                prefs = prefs,
                                model = selectedModel,
                                actionNote = if (hasLive) "Live Google adaptive strategy active" else "Adaptive 429 strategy active (tap Check from Google)"
                            )
                            Toast.makeText(
                                context,
                                if (hasLive) {
                                    "Switched to live Google rate-limit strategy"
                                } else {
                                    "Adaptive mode ON — tap 'Check Current Rate Limits from Google' to fetch live quotas"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.testTag("reset_default_rate_limit_toggle")
                )
            }

            // Expandable Reference Table
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLimitsTable = !showLimitsTable }
                    .padding(vertical = 4.dp)
                    .testTag("toggle_rate_limits_table")
            ) {
                Text(
                    text = if (useDefaultRef) {
                        "View Default Nov 20, 2026 Model Limits (${activeSnapshot.profiles.size})"
                    } else {
                        "View Active Adapted Model Limits (${activeSnapshot.profiles.size})"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (showLimitsTable) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showLimitsTable) "Collapse model limits" else "Expand model limits",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            if (showLimitsTable) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val isPaid = activeSnapshot.detectedTier == GeminiRateLimitSnapshot.TIER_PAID_1
                        activeSnapshot.profiles.forEach { prof ->
                            val isPrimary = prof.modelId.equals(selectedModel, ignoreCase = true)
                            val rpm = if (isPaid) prof.paidRpm else prof.freeRpm
                            val tpm = if (isPaid) prof.paidTpm else prof.freeTpm
                            val rpd = if (isPaid) prof.paidRpd else prof.freeRpd
                            val tag = when (prof.source) {
                                GeminiRateLimitProfile.SOURCE_GOOGLE_LIVE -> "LIVE"
                                GeminiRateLimitProfile.SOURCE_ADAPTED_429 -> "429"
                                else -> "DEF"
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (isPrimary) "★ ${prof.modelId}" else prof.modelId,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (rpm == 0 && !isPaid) {
                                        "Paid-only [$tag]"
                                    } else {
                                        "${rpm} RPM • ${formatCompactTokens(tpm)} TPM • ${rpd} RPD [$tag]"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (rpm == 0 && !isPaid) AmberAccent else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatCompactTokens(tokens: Long): String {
    return when {
        tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000L -> String.format(Locale.US, "%.0fk", tokens / 1_000.0)
        else -> tokens.toString()
    }
}

private fun formatRemainingShort(remainingMs: Long): String {
    val totalSec = (remainingMs / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return when {
        hrs > 0L -> "${hrs}h ${mins}m"
        mins > 0L -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }
}
