package com.ambientbrain.recorder.ui

import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambientbrain.recorder.data.CaptureStateBus
import com.ambientbrain.recorder.data.ClipSlicer
import com.ambientbrain.recorder.data.LiveCaptureStatus
import com.ambientbrain.recorder.service.AdbVirtualScreenController
import com.ambientbrain.recorder.service.AudioInspectorSnapshot
import com.ambientbrain.recorder.service.SelfTestStatus
import com.ambientbrain.recorder.service.ShizukuAdbManager
import com.ambientbrain.recorder.service.ShizukuStatus
import com.ambientbrain.recorder.service.StealthSelfTestReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AdbDiagnosticsDialog(
    onDismissRequest: () -> Unit,
    onStartListening: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val telemetry by CaptureStateBus.telemetry.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf(ShizukuAdbManager.checkDiagnostics(context)) }
    var isExecutingShizuku by remember { mutableStateOf(false) }
    var isSelfHealing by remember { mutableStateOf(false) }
    var isInspectingAudio by remember { mutableStateOf(false) }
    var audioSnapshot by remember { mutableStateOf<AudioInspectorSnapshot?>(null) }
    var isRunningStealthSelfTest by remember { mutableStateOf(false) }
    var stealthSelfTestReport by remember { mutableStateOf<StealthSelfTestReport?>(null) }
    var executionLog by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        state = ShizukuAdbManager.checkDiagnostics(context)
    }

    DisposableEffect(lifecycleOwner) {
        CaptureStateBus.setDiagnosticsOpen(true)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            CaptureStateBus.setDiagnosticsOpen(false)
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 20.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "ADB, Shizuku & Audio Telemetry",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Zero-Notification A11y Share • Self-Heal • Freezer Shield",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismissRequest) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Overall Status Banner
                val fullDeepReady = state.hasDeepPrivilegesReady && state.isPhantomFreezerShielded
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (state.isFullyAdbOptimized) {
                        Color(0xFF10B981).copy(alpha = 0.15f)
                    } else {
                        Color(0xFFF59E0B).copy(alpha = 0.15f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (state.isFullyAdbOptimized) Color(0xFF10B981) else Color(0xFFF59E0B)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isFullyAdbOptimized) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (state.isFullyAdbOptimized) Color(0xFF10B981) else Color(0xFFF59E0B),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = when {
                                    fullDeepReady -> "Silent 24/7 + Deep ADB Shield Active"
                                    state.isFullyAdbOptimized -> "Core 24/7 Ready (Deep Shield Partial)"
                                    else -> "Privileges Incomplete"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (state.isFullyAdbOptimized) Color(0xFF10B981) else Color(0xFFF59E0B)
                            )
                            Text(
                                text = when {
                                    fullDeepReady -> "AppOps RECORD_AUDIO, Zero-Touch A11y Self-Heal, PROJECT_MEDIA & Phantom/Freezer shields are armed with zero notifications."
                                    state.isFullyAdbOptimized -> "Core mic & Accessibility active. Tap '1-Tap Auto-Grant All via Shizuku' (or ADB script) to arm Self-Heal, PROJECT_MEDIA & Phantom/Freezer shield."
                                    else -> "Fix highlighted items via Shizuku or ADB so Android shares the mic cleanly without notifications."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Audio Pipeline & Soak Telemetry Card
                Text(
                    text = "LIVE AUDIO PIPELINE & MIC SHARING TELEMETRY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(6.dp))

                val sharingSummary = when {
                    telemetry.status == LiveCaptureStatus.PAUSED ->
                        "${telemetry.micSharingMode.shortLabel} • PAUSED (${telemetry.pauseReason ?: "Yielded"})"
                    telemetry.isMicSilencedByOs ->
                        "${telemetry.micSharingMode.shortLabel} • SILENCED BY OS (${telemetry.externalMicOwnerLabel ?: "ExternalMic=${telemetry.isExternalMicActive}"})"
                    telemetry.isExternalMicActive ->
                        "${telemetry.micSharingMode.shortLabel} • Shared / External (${telemetry.externalMicOwnerLabel ?: "Active"})"
                    telemetry.status == LiveCaptureStatus.STOPPED ->
                        "${telemetry.micSharingMode.shortLabel} • Capture Stopped"
                    else ->
                        "${telemetry.micSharingMode.shortLabel} • Active Standby/Capture"
                }
                DiagnosticItemRow(
                    label = "Mic Sharing Policy & State",
                    statusText = sharingSummary,
                    isPositive = !telemetry.isMicSilencedByOs,
                    isWarning = telemetry.status == LiveCaptureStatus.PAUSED || telemetry.isMicSilencedByOs
                )

                val micOwnerText = when {
                    telemetry.externalMicOwnerLabel != null ->
                        "${telemetry.externalMicOwnerLabel} (${telemetry.externalMicOwnerPackage ?: "?"}) • ${telemetry.externalMicOwnerSource ?: "ACTIVE"}"
                    telemetry.isExternalMicActive ->
                        "External mic active (run Shizuku / dumpsys audio to inspect PID/UID)"
                    else ->
                        "None (No competing external recording stream)"
                }
                DiagnosticItemRow(
                    label = "Live Mic Owner (Shizuku dumpsys)",
                    statusText = micOwnerText,
                    isPositive = !telemetry.isExternalMicActive && telemetry.externalMicOwnerLabel == null,
                    isWarning = telemetry.isExternalMicActive || telemetry.externalMicOwnerLabel != null
                )

                DiagnosticItemRow(
                    label = "AudioRecord Source & Priority",
                    statusText = "${telemetry.activeAudioSourceName} • URGENT_AUDIO (-19)",
                    isPositive = true
                )

                DiagnosticItemRow(
                    label = "MediaCodec AAC Encoder",
                    statusText = "${telemetry.activeEncoderName} (${if (telemetry.isEncoderHwAccelerated) "Hardware Accelerated" else "Software C2 Low-Power"})",
                    isPositive = true
                )

                DiagnosticItemRow(
                    label = "DirectPcmBufferPool Health",
                    statusText = "Read Errors: ${telemetry.audioReadErrors} • Pool Starvation Drops: ${telemetry.poolStarvationDrops}",
                    isPositive = telemetry.audioReadErrors == 0L && telemetry.poolStarvationDrops == 0L,
                    isWarning = telemetry.audioReadErrors > 0L || telemetry.poolStarvationDrops > 0L
                )

                DiagnosticItemRow(
                    label = "ART GC & Native Direct Heap",
                    statusText = "GC Count: ${telemetry.gcCount} (${telemetry.gcTimeMs}ms) • Native Heap: ${telemetry.nativeHeapKb} KB",
                    isPositive = true
                )

                val totalVadFrames = telemetry.vadGatedFrames + telemetry.vadOnnxFrames
                val gatePct = if (totalVadFrames > 0L) {
                    ((telemetry.vadGatedFrames * 100L) / totalVadFrames).toInt()
                } else 0
                DiagnosticItemRow(
                    label = "VAD Energy Gate & Encoder",
                    statusText = "Quiet Gate: ${telemetry.vadGatedFrames}/$totalVadFrames bypassed ($gatePct% ONNX saved) • Event-Driven AAC",
                    isPositive = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Diagnostic Checklist
                Text(
                    text = "HARDWARE, ACCESSIBILITY & PERMISSION TELEMETRY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                DiagnosticItemRow(
                    label = "Shizuku Service Status",
                    statusText = when (state.shizukuStatus) {
                        ShizukuStatus.AUTHORIZED -> "Running & Authorized"
                        ShizukuStatus.UNAUTHORIZED -> "Running (Permission Needed)"
                        ShizukuStatus.SERVICE_STOPPED -> "Stopped / Service Not Active"
                        ShizukuStatus.NOT_INSTALLED -> "Not Installed"
                    },
                    isPositive = state.shizukuStatus == ShizukuStatus.AUTHORIZED,
                    isWarning = state.shizukuStatus == ShizukuStatus.UNAUTHORIZED
                )

                DiagnosticItemRow(
                    label = "AppOps RECORD_AUDIO (Critical)",
                    statusText = when {
                        state.appOpsRecordAudioAllowed -> "MODE_ALLOWED (Always Share in Background)"
                        state.isRecordAudioForegroundOnly -> "MODE_FOREGROUND ('While in use' — silenced in background! Run Shizuku/ADB)"
                        state.recordAudioAppOpMode == AppOpsManager.MODE_IGNORED -> "MODE_IGNORED (Blocked by OS)"
                        else -> "Restricted (mode=${state.recordAudioAppOpMode})"
                    },
                    isPositive = state.appOpsRecordAudioAllowed,
                    isWarning = state.isRecordAudioForegroundOnly
                )

                DiagnosticItemRow(
                    label = "WRITE_SECURE_SETTINGS (Zero-Touch Self-Heal)",
                    statusText = if (state.runtimeWriteSecureSettings) {
                        "Granted (Auto-restores & re-binds A11y after reboot/update without PC)"
                    } else {
                        "Not Granted (Run 1-Tap Shizuku or ADB script once to arm self-healing)"
                    },
                    isPositive = state.runtimeWriteSecureSettings,
                    isWarning = !state.runtimeWriteSecureSettings
                )

                DiagnosticItemRow(
                    label = "Accessibility Service Setting",
                    statusText = if (state.accessibilitySettingsEnabled) "Enabled in Settings" else "Disabled in Settings",
                    isPositive = state.accessibilitySettingsEnabled
                )

                DiagnosticItemRow(
                    label = "Accessibility Service Binding",
                    statusText = when {
                        state.accessibilityServiceBound -> "Bound by Android OS (Ready for A11y Concurrent Mic)"
                        state.accessibilitySettingsEnabled -> "Not Bound! Restricted Settings or toggle OFF/ON needed"
                        else -> "Not Bound (Enable in Settings or ADB)"
                    },
                    isPositive = state.accessibilityServiceBound,
                    isWarning = state.accessibilitySettingsEnabled && !state.accessibilityServiceBound
                )

                DiagnosticItemRow(
                    label = "1x1 Silent A11y Overlay",
                    statusText = when {
                        state.accessibilityOverlayAttached -> "Attached (${state.accessibilityOverlayType})"
                        !state.accessibilityOverlayError.isNullOrBlank() -> "Failed: ${state.accessibilityOverlayError}"
                        state.accessibilityServiceBound -> "Not Attached (Standby / Listening Stopped)"
                        else -> "Waiting for A11y Binding"
                    },
                    isPositive = state.accessibilityOverlayAttached,
                    isWarning = state.accessibilityServiceBound && !state.accessibilityOverlayAttached
                )

                DiagnosticItemRow(
                    label = "AppOps PROJECT_MEDIA (Prompt Bypass)",
                    statusText = if (state.appOpsProjectMedia) {
                        "MODE_ALLOWED (Silent projection/far-end route pre-authorized)"
                    } else {
                        "Default / Restricted (Grant via Shizuku or ADB)"
                    },
                    isPositive = state.appOpsProjectMedia,
                    isWarning = !state.appOpsProjectMedia
                )

                DiagnosticItemRow(
                    label = "Privileged 2-Way Call Audio (CAPTURE_AUDIO_OUTPUT)",
                    statusText = if (state.runtimeCaptureAudioOutput) {
                        "Granted (Direct VOICE_CALL uplink + downlink active)"
                    } else {
                        "Privileged/Root Only (Using A11y VOICE_RECOGNITION call-through)"
                    },
                    isPositive = true
                )

                DiagnosticItemRow(
                    label = "AppOps SYSTEM_ALERT_WINDOW",
                    statusText = if (state.appOpsSystemAlertWindow) "Allowed (Overlay fallback ready)" else "Not Allowed (A11y overlay only)",
                    isPositive = state.appOpsSystemAlertWindow,
                    isWarning = !state.appOpsSystemAlertWindow
                )

                DiagnosticItemRow(
                    label = "Android 14-16 Freezer & Phantom Shield",
                    statusText = buildList {
                        add(if (state.appOpsRunAnyInBackground) "BG Run: ALLOW" else "BG Run: STD")
                        add(if (state.batteryOptimizationExempt) "Doze: EXEMPT" else "Doze: OPT")
                        add(if (state.appOpsAutoRevokeExempt) "Auto-Revoke: EXEMPT" else "Auto-Revoke: ON")
                        add(if (state.phantomProcessMonitorDisabled) "Phantom Mon: OFF" else "Phantom Mon: DEFAULT")
                    }.joinToString(" • "),
                    isPositive = state.isPhantomFreezerShielded,
                    isWarning = !state.isPhantomFreezerShielded
                )

                DiagnosticItemRow(
                    label = "Runtime Permissions (Mic / Phone)",
                    statusText = "RECORD_AUDIO: ${if (state.runtimeRecordAudio) "Granted" else "Denied"} • READ_PHONE_STATE: ${if (state.runtimeReadPhoneState) "Granted" else "Optional/Denied"}",
                    isPositive = state.runtimeRecordAudio && state.runtimeReadPhoneState,
                    isWarning = state.runtimeRecordAudio && !state.runtimeReadPhoneState
                )

                if (!state.isFullyAdbOptimized) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Android 14-16 & HyperOS Setup Notes",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF59E0B)
                            )
                            Text(
                                text = "• Android 13-16 sideloads lock Accessibility under Restricted Settings until App Info -> Allow restricted settings (or Shizuku/ADB ACCESS_RESTRICTED_SETTINGS allow).\n" +
                                    "• Xiaomi / Poco HyperOS: enable Developer Options -> 'USB debugging (Security settings)', App Info -> Autostart, and Other permissions -> 'Display pop-up windows while running in background'.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        })
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Accessibility", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", context.packageName, null)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("App Info", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                // Actions Section
                Text(
                    text = "ELEVATED SETUP & VALIDATION ACTIONS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Shizuku Request or Auto-Grant Button
                when (state.shizukuStatus) {
                    ShizukuStatus.UNAUTHORIZED -> {
                        Button(
                            onClick = {
                                ShizukuAdbManager.requestShizukuPermission()
                                refresh()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Request Shizuku Permission")
                        }
                    }
                    ShizukuStatus.AUTHORIZED -> {
                        Button(
                            onClick = {
                                scope.launch {
                                    isExecutingShizuku = true
                                    val (success, log) = ShizukuAdbManager.grantAllPrivilegesViaShizuku(context)
                                    audioSnapshot = withContext(Dispatchers.IO) {
                                        ShizukuAdbManager.inspectActiveAudioClientsViaShizuku(context)
                                    }
                                    executionLog = log
                                    isExecutingShizuku = false
                                    refresh()
                                    Toast.makeText(
                                        context,
                                        if (success) "All ADB & Deep Shield privileges granted!" else "Some commands failed (see log)",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            enabled = !isExecutingShizuku,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isExecutingShizuku) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Executing All ADB & Shield Commands...")
                            } else {
                                Icon(imageVector = Icons.Default.FlashOn, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("1-Tap Auto-Grant All via Shizuku")
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Grants RECORD_AUDIO, WRITE_SECURE_SETTINGS (Self-Heal), PROJECT_MEDIA, UsageStats, WAKE_LOCK, Auto-Revoke exemption, Doze/Phantom/Freezer bypass, A11y bind & verifies dumpsys audio.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ShizukuStatus.SERVICE_STOPPED -> {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "💡 Shizuku app is installed but not running. Open Shizuku and start via Wireless Debugging, then tap Refresh.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    ShizukuStatus.NOT_INSTALLED -> {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "💡 Shizuku is not installed. You can install Shizuku to grant ADB permissions without a PC, or run the ADB setup script below.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Zero-Touch Self-Heal A11y Binding Button (Works offline with zero Shizuku/PC once WRITE_SECURE_SETTINGS is granted)
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isSelfHealing = true
                            val res = withContext(Dispatchers.IO) {
                                ShizukuAdbManager.selfHealAccessibilityService(
                                    context = context,
                                    forceRebindIfUnbound = true,
                                    bypassCooldown = true
                                )
                            }
                            if (res.repaired) {
                                delay(550L)
                            }
                            isSelfHealing = false
                            refresh()
                            executionLog = "Zero-Touch A11y Self-Heal: ${res.message}"
                            Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isSelfHealing,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        when {
                            isSelfHealing -> "Self-Healing Accessibility Binding..."
                            !state.runtimeWriteSecureSettings -> "Self-Heal A11y (Needs WRITE_SECURE_SETTINGS)"
                            else -> "🩹 Self-Heal A11y Binding Now (No PC / Shizuku)"
                        }
                    )
                }

                if (state.shizukuStatus == ShizukuStatus.AUTHORIZED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isInspectingAudio = true
                                val snap = withContext(Dispatchers.IO) {
                                    ShizukuAdbManager.inspectActiveAudioClientsViaShizuku(context)
                                }
                                audioSnapshot = snap
                                executionLog = if (!snap.error.isNullOrBlank()) {
                                    "dumpsys audio Inspector Error: ${snap.error}"
                                } else if (snap.rawExcerpt.isNotBlank()) {
                                    "=== dumpsys audio & AudioFlinger Inspector ===\nMode: ${snap.audioModeLine ?: "unknown"}\nmax_phantom_processes: ${snap.maxPhantomProcesses ?: "unknown"}\n\n${snap.rawExcerpt}"
                                } else {
                                    "dumpsys audio Inspector: Mode=${snap.audioModeLine ?: "unknown"} | Active clients=${snap.clients.size} | max_phantom_processes=${snap.maxPhantomProcesses ?: "unknown"}"
                                }
                                isInspectingAudio = false
                            }
                        },
                        enabled = !isInspectingAudio,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isInspectingAudio) "Inspecting dumpsys audio..." else "🔍 Inspect Active Mic Owners (dumpsys audio)")
                    }
                }

                audioSnapshot?.let { snap ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "DUMPSYS AUDIO & AUDIOFLINGER INSPECTOR",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${snap.audioModeLine ?: "AudioMode unknown"} • max_phantom_processes=${snap.maxPhantomProcesses ?: "?"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (snap.clients.isEmpty()) {
                                Text(
                                    text = "No active recording clients reported right now.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                snap.clients.forEach { client ->
                                    val badgeColor = when {
                                        client.isSilenced -> MaterialTheme.colorScheme.error
                                        client.isSelf -> MaterialTheme.colorScheme.primary
                                        client.isActive -> Color(0xFFF59E0B)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    val flags = buildList {
                                        add(if (client.isSelf) "SELF" else "EXTERNAL")
                                        add(if (client.isActive) "ACTIVE" else "IDLE")
                                        if (client.isSilenced) add("SILENCED")
                                    }.joinToString(" • ")
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Row(
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = client.appLabel,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = flags,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = badgeColor
                                                )
                                            }
                                            Text(
                                                text = "${client.packageName} • uid=${client.uid ?: "?"} • sess=${client.sessionId ?: "?"} • src=${client.audioSource}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // On-Device Stealth Readiness Self-Test Button (~5s dry run)
                Button(
                    onClick = {
                        scope.launch {
                            isRunningStealthSelfTest = true
                            val report = withContext(Dispatchers.IO) {
                                AdbVirtualScreenController.runStealthReadinessSelfTest(context)
                            }
                            stealthSelfTestReport = report
                            executionLog = report.rawLog
                            isRunningStealthSelfTest = false
                            refresh()
                        }
                    },
                    enabled = !isRunningStealthSelfTest,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0F766E),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("run_stealth_self_test_button")
                ) {
                    if (isRunningStealthSelfTest) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Running Stealth Readiness Self-Test (~5s)...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🛡️ Run Stealth Readiness Self-Test (~5s)")
                    }
                }

                stealthSelfTestReport?.let { report ->
                    Spacer(modifier = Modifier.height(10.dp))
                    val overallColor = when (report.overallStatus) {
                        SelfTestStatus.PASS -> Color(0xFF10B981)
                        SelfTestStatus.WARN -> Color(0xFFF59E0B)
                        SelfTestStatus.FAIL -> MaterialTheme.colorScheme.error
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = overallColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, overallColor.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "STEALTH CAPABILITY MATRIX",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = overallColor
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = overallColor.copy(alpha = 0.22f)
                                ) {
                                    Text(
                                        text = when (report.overallStatus) {
                                            SelfTestStatus.PASS -> "ALL PASS"
                                            SelfTestStatus.WARN -> "READY (FALLBACK)"
                                            SelfTestStatus.FAIL -> "ACTION NEEDED"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = overallColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = report.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            report.items.forEach { item ->
                                val itemColor = when (item.status) {
                                    SelfTestStatus.PASS -> Color(0xFF10B981)
                                    SelfTestStatus.WARN -> Color(0xFFF59E0B)
                                    SelfTestStatus.FAIL -> MaterialTheme.colorScheme.error
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, itemColor.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = itemColor.copy(alpha = 0.18f)
                                            ) {
                                                Text(
                                                    text = item.status.name,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = itemColor,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = item.detail,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Run ADTS Self-Test Button
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                ClipSlicer.runAdtsEncoderSelfTest(context)
                            }
                            executionLog = "ADTS Encoder Self-Test: ${if (res.isValid) "PASSED ✅" else "FAILED ❌"}\n${res.summary}"
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔊 Run ADTS Encoder & MediaExtractor Self-Test")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Copy ADB Script Button
                OutlinedButton(
                    onClick = {
                        val script = ShizukuAdbManager.getStandaloneAdbScript(context)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Ambient Brain ADB Script", script))
                        Toast.makeText(context, "ADB script copied to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Standalone ADB Script (PC / Termux)")
                }

                // Execution Log Box
                executionLog?.let { log ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF090D12),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "SHELL EXECUTION LOG",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Ambient Brain Shell Log", log))
                                        Toast.makeText(context, "Execution log copied!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.testTag("copy_shell_log_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy execution log",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = log,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { refresh() }) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                }
                Spacer(modifier = Modifier.width(6.dp))
                if (telemetry.status == LiveCaptureStatus.STOPPED) {
                    Button(
                        onClick = {
                            onStartListening()
                            onDismissRequest()
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start Listening")
                    }
                } else {
                    Button(
                        onClick = onDismissRequest,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    )
}

@Composable
private fun DiagnosticItemRow(
    label: String,
    statusText: String,
    isPositive: Boolean,
    isWarning: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isPositive -> Color(0xFF10B981)
                        isWarning -> Color(0xFFF59E0B)
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isPositive -> Color(0xFF10B981)
                            isWarning -> Color(0xFFF59E0B)
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
            )
        }
    }
}
