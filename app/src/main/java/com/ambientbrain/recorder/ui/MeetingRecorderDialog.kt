package com.ambientbrain.recorder.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.service.InstalledAppInfo
import com.ambientbrain.recorder.service.InstalledAppQueryHelper
import com.ambientbrain.recorder.service.MeetingAudioCaptureMode
import com.ambientbrain.recorder.service.MeetingMp4Muxer
import com.ambientbrain.recorder.service.MeetingPlatform
import com.ambientbrain.recorder.service.MeetingRecorderService
import com.ambientbrain.recorder.service.MeetingRecorderState
import com.ambientbrain.recorder.service.MeetingRecorderStateBus
import com.ambientbrain.recorder.service.MeetingVideoMode
import com.ambientbrain.recorder.service.ShizukuAdbManager
import com.ambientbrain.recorder.service.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeetingRecorderDialog(
    onDismissRequest: () -> Unit,
    onOpenAudioVault: () -> Unit = {},
    onOpenTimeline: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { PreferencesRepo(context) }
    val meetingState by MeetingRecorderStateBus.state.collectAsStateWithLifecycle()

    var shizukuStatus by remember { mutableStateOf(ShizukuAdbManager.getShizukuStatus(context)) }
    var saveFolderDisplay by remember { mutableStateOf(prefs.effectiveMeetingVideoFolderDisplay()) }

    val initialCaptureMode = remember {
        if (meetingState.isRecording || meetingState.isFinalizing) {
            meetingState.captureMode
        } else if (shizukuStatus == ShizukuStatus.AUTHORIZED) {
            MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED
        } else {
            MeetingAudioCaptureMode.INTERNAL_AND_MIC
        }
    }
    val initialVideoMode = remember {
        if (meetingState.isRecording || meetingState.isFinalizing) {
            meetingState.videoMode
        } else if (shizukuStatus == ShizukuStatus.AUTHORIZED) {
            MeetingVideoMode.ADB_VIRTUAL_SCREEN
        } else {
            MeetingVideoMode.AUDIO_ONLY
        }
    }

    var selectedPlatform by rememberSaveable { mutableStateOf(meetingState.platform) }
    var selectedTargetPackageName by rememberSaveable { mutableStateOf(meetingState.targetPackageName) }
    var selectedTargetAppLabel by rememberSaveable { mutableStateOf(meetingState.targetAppLabel) }
    var selectedCaptureMode by rememberSaveable { mutableStateOf(initialCaptureMode) }
    var selectedVideoMode by rememberSaveable { mutableStateOf(initialVideoMode) }
    var userManuallyChangedMode by rememberSaveable { mutableStateOf(false) }
    var meetingTitle by rememberSaveable { mutableStateOf(meetingState.meetingTitle) }
    var isClinicalMeeting by rememberSaveable { mutableStateOf(meetingState.isClinicalMeeting) }
    var autoAnalyzeOnStop by rememberSaveable { mutableStateOf(meetingState.autoAnalyzeOnStop) }
    var ignoreInternalAppAudioForGemini by rememberSaveable { mutableStateOf(meetingState.ignoreInternalAppAudioForGemini) }
    var silentSpeakerIsolation by rememberSaveable { mutableStateOf(meetingState.silentSpeakerIsolation) }
    var enableVirtualMic by rememberSaveable { mutableStateOf(meetingState.enableVirtualMic) }
    var keepAmbientBrainListening by rememberSaveable { mutableStateOf(meetingState.keepAmbientBrainListening) }
    var showMiniPreviewOnStart by rememberSaveable { mutableStateOf(false) }
    var startWithSpeakerphone by rememberSaveable { mutableStateOf(false) }
    var autoSwitchToApp by rememberSaveable { mutableStateOf(false) }
    var showAppPickerDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val prevStatus = shizukuStatus
                val newStatus = ShizukuAdbManager.getShizukuStatus(context)
                shizukuStatus = newStatus
                saveFolderDisplay = prefs.effectiveMeetingVideoFolderDisplay()
                if (prevStatus != ShizukuStatus.AUTHORIZED &&
                    newStatus == ShizukuStatus.AUTHORIZED &&
                    !userManuallyChangedMode &&
                    !MeetingRecorderStateBus.state.value.isRecording &&
                    !MeetingRecorderStateBus.state.value.isFinalizing
                ) {
                    selectedCaptureMode = MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED
                    selectedVideoMode = MeetingVideoMode.ADB_VIRTUAL_SCREEN
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val zoomInstalled = remember { MeetingRecorderService.isPlatformInstalled(context, MeetingPlatform.ZOOM) }
    val webexInstalled = remember { MeetingRecorderService.isPlatformInstalled(context, MeetingPlatform.WEBEX) }

    val effectiveSelectedAppLabel = remember(selectedPlatform, selectedTargetAppLabel) {
        if (selectedPlatform == MeetingPlatform.OTHER_APP && selectedTargetAppLabel.isNotBlank()) {
            selectedTargetAppLabel
        } else {
            selectedPlatform.label
        }
    }
    val effectiveSelectedShortLabel = remember(selectedPlatform, selectedTargetAppLabel) {
        if (selectedPlatform == MeetingPlatform.OTHER_APP && selectedTargetAppLabel.isNotBlank()) {
            selectedTargetAppLabel.take(14)
        } else {
            selectedPlatform.shortLabel
        }
    }
    val effectiveTargetPackage = remember(selectedPlatform, selectedTargetPackageName) {
        if (selectedPlatform == MeetingPlatform.OTHER_APP) {
            selectedTargetPackageName.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    val needsMediaProjection = selectedCaptureMode.needsMediaProjection || selectedVideoMode.requiresMediaProjection
    val requiresShizuku = selectedVideoMode == MeetingVideoMode.ADB_VIRTUAL_SCREEN || selectedCaptureMode.usesAdbStealthAudio

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            MeetingRecorderService.startRecording(
                context = context,
                platform = selectedPlatform,
                targetPackageName = effectiveTargetPackage,
                targetAppLabel = if (selectedPlatform == MeetingPlatform.OTHER_APP) selectedTargetAppLabel else "",
                captureMode = selectedCaptureMode,
                videoMode = selectedVideoMode,
                meetingTitle = meetingTitle,
                isClinicalMeeting = isClinicalMeeting,
                autoAnalyzeOnStop = autoAnalyzeOnStop,
                ignoreInternalAppAudioForGemini = ignoreInternalAppAudioForGemini,
                enableVirtualMic = enableVirtualMic,
                silentSpeaker = silentSpeakerIsolation,
                keepAmbientBrainListening = keepAmbientBrainListening,
                showMiniPreviewOnStart = showMiniPreviewOnStart,
                enableSpeakerphoneBoost = selectedCaptureMode == MeetingAudioCaptureMode.MIC_SPEAKERPHONE ||
                    (selectedCaptureMode == MeetingAudioCaptureMode.INTERNAL_AND_MIC && startWithSpeakerphone),
                projectionResultCode = result.resultCode,
                projectionData = result.data
            )
            Toast.makeText(
                context,
                if (selectedVideoMode != MeetingVideoMode.AUDIO_ONLY) {
                    "Recording $effectiveSelectedAppLabel video & audio..."
                } else {
                    "Recording $effectiveSelectedAppLabel..."
                },
                Toast.LENGTH_SHORT
            ).show()
            if (autoSwitchToApp && selectedVideoMode != MeetingVideoMode.ADB_VIRTUAL_SCREEN) {
                MeetingRecorderService.launchMeetingApp(context, selectedPlatform, effectiveTargetPackage)
            }
        } else {
            Toast.makeText(
                context,
                "Capture permission cancelled. You can also use Stealth ADB Isolated mode or Mic + Speakerphone.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun proceedWithStart() {
        MeetingRecorderStateBus.clearLastError()
        MeetingRecorderStateBus.clearLastSavedBanner()

        if (selectedPlatform == MeetingPlatform.OTHER_APP && selectedTargetPackageName.isBlank()) {
            Toast.makeText(context, "Please select an installed app to isolate & record.", Toast.LENGTH_SHORT).show()
            showAppPickerDialog = true
            return
        }

        if (requiresShizuku) {
            shizukuStatus = ShizukuAdbManager.getShizukuStatus(context)
            when (shizukuStatus) {
                ShizukuStatus.UNAUTHORIZED -> {
                    ShizukuAdbManager.requestShizukuPermission()
                    Toast.makeText(
                        context,
                        "Please grant Shizuku permission for Stealth ADB Virtual Screen, then tap Start again.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                ShizukuStatus.NOT_INSTALLED, ShizukuStatus.SERVICE_STOPPED -> {
                    Toast.makeText(
                        context,
                        "Shizuku service is not running. Start Shizuku for Stealth ADB isolation or choose Screen / Single-App Video.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                ShizukuStatus.AUTHORIZED -> Unit
            }
        }

        if (needsMediaProjection) {
            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mpManager == null) {
                Toast.makeText(context, "MediaProjection unavailable on this device.", Toast.LENGTH_LONG).show()
                return
            }
            projectionLauncher.launch(mpManager.createScreenCaptureIntent())
        } else {
            val effectiveVideo = if (selectedCaptureMode.usesAdbStealthAudio) {
                MeetingVideoMode.ADB_VIRTUAL_SCREEN
            } else {
                selectedVideoMode
            }
            MeetingRecorderService.startRecording(
                context = context,
                platform = selectedPlatform,
                targetPackageName = effectiveTargetPackage,
                targetAppLabel = if (selectedPlatform == MeetingPlatform.OTHER_APP) selectedTargetAppLabel else "",
                captureMode = selectedCaptureMode,
                videoMode = effectiveVideo,
                meetingTitle = meetingTitle,
                isClinicalMeeting = isClinicalMeeting,
                autoAnalyzeOnStop = autoAnalyzeOnStop,
                ignoreInternalAppAudioForGemini = ignoreInternalAppAudioForGemini,
                enableVirtualMic = enableVirtualMic,
                silentSpeaker = silentSpeakerIsolation,
                keepAmbientBrainListening = keepAmbientBrainListening,
                showMiniPreviewOnStart = showMiniPreviewOnStart,
                enableSpeakerphoneBoost = selectedCaptureMode == MeetingAudioCaptureMode.MIC_SPEAKERPHONE
            )
            Toast.makeText(
                context,
                when {
                    selectedCaptureMode.usesAdbStealthAudio ->
                        "Starting Stealth Virtual Screen + Isolated Audio for $effectiveSelectedAppLabel..."
                    effectiveVideo == MeetingVideoMode.ADB_VIRTUAL_SCREEN ->
                        "Launching ADB Virtual Screen & recording $effectiveSelectedAppLabel..."
                    else ->
                        "Recording $effectiveSelectedAppLabel via Mic + Speakerphone..."
                },
                Toast.LENGTH_SHORT
            ).show()
            if (autoSwitchToApp && effectiveVideo != MeetingVideoMode.ADB_VIRTUAL_SCREEN) {
                MeetingRecorderService.launchMeetingApp(context, selectedPlatform, effectiveTargetPackage)
            }
        }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        val micOk = !selectedCaptureMode.needsMic ||
            grantedMap[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micOk) {
            proceedWithStart()
        } else {
            Toast.makeText(
                context,
                "Microphone permission is required for ${selectedCaptureMode.title}.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun requestPermissionsAndStart() {
        val permsToAsk = mutableListOf<String>()
        if (selectedCaptureMode.needsMic &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            permsToAsk.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permsToAsk.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permsToAsk.isNotEmpty()) {
            runtimePermissionLauncher.launch(permsToAsk.toTypedArray())
        } else {
            proceedWithStart()
        }
    }

    val isActiveOrFinalizing = meetingState.isRecording || meetingState.isFinalizing

    if (showAppPickerDialog) {
        InstalledAppPickerDialog(
            selectedPackageName = selectedTargetPackageName,
            onDismiss = { showAppPickerDialog = false },
            onSelectApp = { appInfo ->
                selectedPlatform = MeetingPlatform.OTHER_APP
                selectedTargetPackageName = appInfo.packageName
                selectedTargetAppLabel = appInfo.appLabel
                showAppPickerDialog = false
            }
        )
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 790.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (meetingState.isRecording) {
                            Color(0xFFEF4444).copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                tint = if (meetingState.isRecording) Color(0xFFFB7185) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Zoom, Webex & Isolated App Recorder",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                meetingState.isFinalizing -> "Muxing & saving MP4 to $saveFolderDisplay..."
                                meetingState.isRecording && meetingState.isPaused ->
                                    "${meetingState.effectiveAppDisplayName()} recording paused"
                                meetingState.isRecording && meetingState.captureMode.usesAdbStealthAudio ->
                                    "Stealth Isolation active • ${meetingState.effectiveAppDisplayName()}"
                                meetingState.isRecording && meetingState.videoActive ->
                                    "Live ${meetingState.effectiveAppDisplayName()} video + audio active"
                                meetingState.isRecording ->
                                    "Live ${meetingState.effectiveAppDisplayName()} audio capture active"
                                else -> "Stealth Virtual Screen + Silent Speaker + Virtual Mic"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // Error banner if present
                    meetingState.lastError?.let { err ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { MeetingRecorderStateBus.clearLastError() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss error",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Last saved confirmation banner when idle
                    if (!isActiveOrFinalizing && meetingState.lastSavedSessionId != null) {
                        val savedVideoFile = remember(meetingState.lastSavedVideoPath) {
                            meetingState.lastSavedVideoPath?.let { File(it) }?.takeIf { it.exists() }
                        }
                        val hasPlayableVideo by produceState(
                            initialValue = savedVideoFile != null || !meetingState.lastSavedGalleryUri.isNullOrBlank(),
                            key1 = meetingState.lastSavedGalleryUri,
                            key2 = meetingState.lastSavedVideoPath
                        ) {
                            value = withContext(Dispatchers.IO) {
                                MeetingMp4Muxer.isPlayableVideoAvailable(
                                    context,
                                    meetingState.lastSavedGalleryUri,
                                    savedVideoFile
                                )
                            }
                        }
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Saved: ${meetingState.lastSavedTitle ?: "Online Meeting"}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        val detailText = buildString {
                                            if (!meetingState.lastSavedPublicVideoPath.isNullOrBlank()) {
                                                append("MP4 saved in ${meetingState.lastSavedPublicVideoPath}. ")
                                            } else if (hasPlayableVideo) {
                                                append("MP4 video saved. ")
                                            }
                                            when {
                                                meetingState.lastSavedGeminiExcludedInternal && !meetingState.lastSavedAutoAnalyzed ->
                                                    append("Internal app audio was excluded from Gemini.")
                                                meetingState.lastSavedGeminiExcludedInternal && meetingState.lastSavedAutoAnalyzed ->
                                                    append("Queued local mic only for Gemini (internal app audio ignored).")
                                                meetingState.lastSavedAutoAnalyzed ->
                                                    append("Queued for Gemini analysis.")
                                                meetingState.lastSavedHasRawAudio ->
                                                    append("Saved safely in Audio Vault.")
                                                else ->
                                                    append("Saved safely to your video folder.")
                                            }
                                        }
                                        Text(
                                            text = detailText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (hasPlayableVideo) {
                                        Button(
                                            onClick = {
                                                if (!MeetingMp4Muxer.openVideo(context, meetingState.lastSavedGalleryUri, savedVideoFile)) {
                                                    Toast.makeText(
                                                        context,
                                                        "MP4 file was moved/deleted or no video player is installed.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Watch Video", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                MeetingMp4Muxer.shareVideo(context, meetingState.lastSavedGalleryUri, savedVideoFile)
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Share MP4", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            MeetingRecorderStateBus.clearLastSavedBanner()
                                            onDismissRequest()
                                            onOpenTimeline()
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("View Timeline", style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (meetingState.lastSavedHasRawAudio) {
                                        OutlinedButton(
                                            onClick = {
                                                MeetingRecorderStateBus.clearLastSavedBanner()
                                                onDismissRequest()
                                                onOpenAudioVault()
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("Audio Vault", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isActiveOrFinalizing) {
                        ActiveMeetingRecordingBody(
                            state = meetingState,
                            onTogglePause = { MeetingRecorderService.togglePause(context) },
                            onToggleSpeakerphone = { MeetingRecorderService.toggleSpeakerphoneBoost(context) },
                            onMoveToVirtualScreen = {
                                MeetingRecorderService.moveMeetingAppToVirtualScreen(context)
                                Toast.makeText(
                                    context,
                                    "Moving ${meetingState.effectiveShortAppName()} to background Virtual Screen...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onBringToMainScreen = {
                                MeetingRecorderService.bringMeetingAppToMainScreen(context)
                                Toast.makeText(
                                    context,
                                    "Bringing ${meetingState.effectiveShortAppName()} back to main screen...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onToggleMiniPreview = {
                                MeetingRecorderService.toggleMiniPreview(context)
                            },
                            onLaunchMeetingApp = {
                                val launched = MeetingRecorderService.launchMeetingApp(
                                    context = context,
                                    platform = meetingState.platform,
                                    customPackageName = meetingState.targetPackageName
                                )
                                if (!launched) {
                                    Toast.makeText(
                                        context,
                                        "${meetingState.effectiveAppDisplayName()} app is not installed on this device.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onStopAndSave = {
                                MeetingRecorderService.stopRecording(context)
                            }
                        )
                    } else {
                        IdleMeetingSetupBody(
                            selectedPlatform = selectedPlatform,
                            onSelectPlatform = { platform ->
                                selectedPlatform = platform
                                if (platform == MeetingPlatform.OTHER_APP && selectedTargetPackageName.isBlank()) {
                                    showAppPickerDialog = true
                                }
                            },
                            selectedTargetAppLabel = selectedTargetAppLabel,
                            selectedTargetPackageName = selectedTargetPackageName,
                            onOpenAppPicker = { showAppPickerDialog = true },
                            effectiveSelectedAppLabel = effectiveSelectedAppLabel,
                            effectiveSelectedShortLabel = effectiveSelectedShortLabel,
                            zoomInstalled = zoomInstalled,
                            webexInstalled = webexInstalled,
                            meetingTitle = meetingTitle,
                            onMeetingTitleChange = { meetingTitle = it },
                            selectedVideoMode = selectedVideoMode,
                            onSelectVideoMode = { vMode ->
                                userManuallyChangedMode = true
                                selectedVideoMode = vMode
                                if (vMode != MeetingVideoMode.ADB_VIRTUAL_SCREEN &&
                                    selectedCaptureMode == MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED
                                ) {
                                    selectedCaptureMode = MeetingAudioCaptureMode.INTERNAL_AND_MIC
                                }
                            },
                            shizukuStatus = shizukuStatus,
                            onRequestShizukuPermission = {
                                ShizukuAdbManager.requestShizukuPermission()
                                Toast.makeText(context, "Requesting Shizuku permission...", Toast.LENGTH_SHORT).show()
                            },
                            selectedCaptureMode = selectedCaptureMode,
                            onSelectCaptureMode = { mode ->
                                userManuallyChangedMode = true
                                selectedCaptureMode = mode
                                if (mode == MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED) {
                                    selectedVideoMode = MeetingVideoMode.ADB_VIRTUAL_SCREEN
                                }
                            },
                            silentSpeakerIsolation = silentSpeakerIsolation,
                            onSilentSpeakerIsolationChange = { silentSpeakerIsolation = it },
                            enableVirtualMic = enableVirtualMic,
                            onEnableVirtualMicChange = { enableVirtualMic = it },
                            keepAmbientBrainListening = keepAmbientBrainListening,
                            onKeepAmbientBrainListeningChange = { keepAmbientBrainListening = it },
                            showMiniPreviewOnStart = showMiniPreviewOnStart,
                            onShowMiniPreviewOnStartChange = { showMiniPreviewOnStart = it },
                            saveFolderDisplay = saveFolderDisplay,
                            onChangeSaveFolder = {
                                onDismissRequest()
                                onOpenSettings()
                            },
                            ignoreInternalAppAudioForGemini = ignoreInternalAppAudioForGemini,
                            onIgnoreInternalForGeminiChange = { ignoreInternalAppAudioForGemini = it },
                            isClinicalMeeting = isClinicalMeeting,
                            onClinicalMeetingChange = { isClinicalMeeting = it },
                            autoAnalyzeOnStop = autoAnalyzeOnStop,
                            onAutoAnalyzeChange = { autoAnalyzeOnStop = it },
                            startWithSpeakerphone = startWithSpeakerphone,
                            onStartWithSpeakerphoneChange = { startWithSpeakerphone = it },
                            autoSwitchToApp = autoSwitchToApp,
                            onAutoSwitchToAppChange = { autoSwitchToApp = it },
                            onQuickLaunchApp = { platform, customPkg ->
                                val ok = MeetingRecorderService.launchMeetingApp(context, platform, customPkg)
                                if (!ok) {
                                    Toast.makeText(
                                        context,
                                        "Selected app is not installed.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onStartRecording = { requestPermissionsAndStart() }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleMeetingSetupBody(
    selectedPlatform: MeetingPlatform,
    onSelectPlatform: (MeetingPlatform) -> Unit,
    selectedTargetAppLabel: String,
    selectedTargetPackageName: String,
    onOpenAppPicker: () -> Unit,
    effectiveSelectedAppLabel: String,
    effectiveSelectedShortLabel: String,
    zoomInstalled: Boolean,
    webexInstalled: Boolean,
    meetingTitle: String,
    onMeetingTitleChange: (String) -> Unit,
    selectedVideoMode: MeetingVideoMode,
    onSelectVideoMode: (MeetingVideoMode) -> Unit,
    shizukuStatus: ShizukuStatus,
    onRequestShizukuPermission: () -> Unit,
    selectedCaptureMode: MeetingAudioCaptureMode,
    onSelectCaptureMode: (MeetingAudioCaptureMode) -> Unit,
    silentSpeakerIsolation: Boolean,
    onSilentSpeakerIsolationChange: (Boolean) -> Unit,
    enableVirtualMic: Boolean,
    onEnableVirtualMicChange: (Boolean) -> Unit,
    keepAmbientBrainListening: Boolean,
    onKeepAmbientBrainListeningChange: (Boolean) -> Unit,
    showMiniPreviewOnStart: Boolean,
    onShowMiniPreviewOnStartChange: (Boolean) -> Unit,
    saveFolderDisplay: String,
    onChangeSaveFolder: () -> Unit,
    ignoreInternalAppAudioForGemini: Boolean,
    onIgnoreInternalForGeminiChange: (Boolean) -> Unit,
    isClinicalMeeting: Boolean,
    onClinicalMeetingChange: (Boolean) -> Unit,
    autoAnalyzeOnStop: Boolean,
    onAutoAnalyzeChange: (Boolean) -> Unit,
    startWithSpeakerphone: Boolean,
    onStartWithSpeakerphoneChange: (Boolean) -> Unit,
    autoSwitchToApp: Boolean,
    onAutoSwitchToAppChange: (Boolean) -> Unit,
    onQuickLaunchApp: (MeetingPlatform, String?) -> Unit,
    onStartRecording: () -> Unit
) {
    // 1. Target App / Platform Selector
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "1. Target App (Zoom, Webex, or Any App)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            MeetingPlatform.entries.forEach { platform ->
                val selected = selectedPlatform == platform
                val installed = when (platform) {
                    MeetingPlatform.ZOOM -> zoomInstalled
                    MeetingPlatform.WEBEX -> webexInstalled
                    MeetingPlatform.OTHER_APP -> selectedTargetPackageName.isNotBlank()
                    MeetingPlatform.AUTO -> zoomInstalled || webexInstalled
                }
                val chipText = if (platform == MeetingPlatform.OTHER_APP && selectedTargetAppLabel.isNotBlank()) {
                    selectedTargetAppLabel.take(16)
                } else {
                    platform.shortLabel
                }
                FilterChip(
                    selected = selected,
                    onClick = { onSelectPlatform(platform) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (platform == MeetingPlatform.OTHER_APP) {
                                Icon(
                                    imageVector = Icons.Default.Apps,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = chipText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (installed && platform != MeetingPlatform.AUTO) {
                                Spacer(modifier = Modifier.width(5.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(Color(0xFF10B981), CircleShape)
                                )
                            }
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        if (selectedPlatform == MeetingPlatform.OTHER_APP) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedTargetAppLabel.ifBlank { "No App Selected Yet" },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = selectedTargetPackageName.ifBlank { "Tap Pick App to select any installed app for isolated recording" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenAppPicker,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            text = if (selectedTargetPackageName.isBlank()) "Pick App" else "Change",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (zoomInstalled) {
                OutlinedButton(
                    onClick = { onQuickLaunchApp(MeetingPlatform.ZOOM, null) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open Zoom", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (webexInstalled) {
                OutlinedButton(
                    onClick = { onQuickLaunchApp(MeetingPlatform.WEBEX, null) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open Webex", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (selectedPlatform == MeetingPlatform.OTHER_APP && selectedTargetPackageName.isNotBlank()) {
                OutlinedButton(
                    onClick = { onQuickLaunchApp(MeetingPlatform.OTHER_APP, selectedTargetPackageName) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open $effectiveSelectedShortLabel", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    // Optional Meeting Title
    OutlinedTextField(
        value = meetingTitle,
        onValueChange = onMeetingTitleChange,
        label = { Text("Recording Title (Optional)") },
        placeholder = { Text("e.g., Weekly Case Conference, Stroke Board") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )

    // 2. Audio & Hardware Isolation Mode
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "2. Audio & Hardware Isolation Mode",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        MeetingAudioCaptureMode.entries.forEach { mode ->
            val selected = selectedCaptureMode == mode
            val isStealth = mode == MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (selected) {
                    if (isStealth) Color(0xFF064E3B).copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                border = BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = when {
                        selected && isStealth -> Color(0xFF10B981)
                        selected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectCaptureMode(mode) }
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(11.dp)
                ) {
                    Icon(
                        imageVector = if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = when {
                            selected && isStealth -> Color(0xFF34D399)
                            selected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isStealth) {
                                val ready = shizukuStatus == ShizukuStatus.AUTHORIZED
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (ready) Color(0xFF064E3B) else Color(0xFF78350F)
                                ) {
                                    Text(
                                        text = if (ready) "3-Way Stealth" else "Needs Shizuku",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (ready) Color(0xFFA7F3D0) else Color(0xFFFDE68A),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            } else if (mode == MeetingAudioCaptureMode.INTERNAL_AND_MIC && shizukuStatus != ShizukuStatus.AUTHORIZED) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = "No-ADB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = mode.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (selected && isStealth && shizukuStatus == ShizukuStatus.UNAUTHORIZED) {
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = onRequestShizukuPermission,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Authorize Shizuku", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Stealth Isolation Hardware Controls (shown when STEALTH_ADB_ISOLATED is active)
    if (selectedCaptureMode == MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF064E3B).copy(alpha = 0.2f),
            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.45f))
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Stealth Hardware Isolation Controls",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFA7F3D0)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = null,
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Silent Physical Speaker (Per-UID Loopback)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Redirects $effectiveSelectedShortLabel audio silently into MP4 so nothing plays on the phone speaker",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = silentSpeakerIsolation, onCheckedChange = onSilentSpeakerIsolationChange)
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.MicOff,
                        contentDescription = null,
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Virtual Silent Microphone (AppOps Fallback)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Feeds silent virtual mic to $effectiveSelectedShortLabel (or AppOps mute fallback, auto-restored on stop)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enableVirtualMic, onCheckedChange = onEnableVirtualMicChange)
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keep Ambient Brain Listening to Room",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Physical room mic stays active for Ambient Brain & Gemini in parallel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = keepAmbientBrainListening, onCheckedChange = onKeepAmbientBrainListeningChange)
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureInPictureAlt,
                        contentDescription = null,
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Open Floating Interactive Mini-Preview",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Tap 'Join Audio' or dismiss popups on the virtual screen without leaving Stealth Mode",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = showMiniPreviewOnStart, onCheckedChange = onShowMiniPreviewOnStartChange)
                }
            }
        }
    }

    // 3. Video Recording Mode (hidden/locked cleanly when Stealth ADB already runs Virtual Screen, or selectable for non-stealth)
    if (selectedCaptureMode != MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "3. Background Video Mode",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            MeetingVideoMode.entries.forEach { vMode ->
                val selected = selectedVideoMode == vMode
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    },
                    border = BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectVideoMode(vMode) }
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(11.dp)
                    ) {
                        Icon(
                            imageVector = if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = vMode.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (vMode == MeetingVideoMode.ADB_VIRTUAL_SCREEN) {
                                    val ready = shizukuStatus == ShizukuStatus.AUTHORIZED
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (ready) Color(0xFF064E3B) else Color(0xFF78350F)
                                    ) {
                                        Text(
                                            text = if (ready) "ADB Ready" else "Needs Shizuku",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (ready) Color(0xFFA7F3D0) else Color(0xFFFDE68A),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = vMode.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (selected && vMode == MeetingVideoMode.ADB_VIRTUAL_SCREEN && shizukuStatus == ShizukuStatus.UNAUTHORIZED) {
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = onRequestShizukuPermission,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Authorize Shizuku", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // MP4 Save Folder Summary Row (accessible to normal File Explorer, adjustable in Settings)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MP4 Save Folder (File Explorer)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = saveFolderDisplay,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(
                onClick = onChangeSaveFolder,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Change", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }

    // 4. Gemini Filtering & Meeting Options
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.FilterAltOff,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gemini Ignores Internal App Audio",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Full isolated app audio stays in your saved MP4, while Gemini never analyzes internal app audio.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = ignoreInternalAppAudioForGemini,
                    onCheckedChange = onIgnoreInternalForGeminiChange
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.MedicalServices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Clinical / Medical Case Conference",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Also route session into Ward Rounds tab",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = isClinicalMeeting, onCheckedChange = onClinicalMeetingChange)
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 4.dp)
            )

            val geminiDisabledByInternalOnly = ignoreInternalAppAudioForGemini &&
                (selectedCaptureMode == MeetingAudioCaptureMode.INTERNAL_ONLY ||
                    selectedCaptureMode == MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = if (geminiDisabledByInternalOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (ignoreInternalAppAudioForGemini) {
                            "Auto-Analyze Local Mic with Gemini"
                        } else {
                            "Auto-Analyze Full Recording with Gemini"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when {
                            selectedCaptureMode == MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED && ignoreInternalAppAudioForGemini ->
                                "Skipped for isolated app MP4 (Ambient Brain still analyzes your physical room mic separately)"
                            geminiDisabledByInternalOnly ->
                                "Skipped in Internal-Only mode (internal app audio is excluded from Gemini)"
                            ignoreInternalAppAudioForGemini ->
                                "Sends ONLY your local mic track (0% internal app audio)"
                            else ->
                                "Extracts speakers, meeting minutes, decisions & tasks"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoAnalyzeOnStop && !geminiDisabledByInternalOnly,
                    enabled = !geminiDisabledByInternalOnly,
                    onCheckedChange = onAutoAnalyzeChange
                )
            }

            if (selectedCaptureMode == MeetingAudioCaptureMode.INTERNAL_AND_MIC) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Start with Speakerphone Boost",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Helpful fallback if Zoom/Webex blocks internal VoIP audio",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = startWithSpeakerphone, onCheckedChange = onStartWithSpeakerphoneChange)
                }
            }

            val selectedInstalled = when (selectedPlatform) {
                MeetingPlatform.ZOOM -> zoomInstalled
                MeetingPlatform.WEBEX -> webexInstalled
                MeetingPlatform.OTHER_APP -> selectedTargetPackageName.isNotBlank()
                MeetingPlatform.AUTO -> zoomInstalled || webexInstalled
            }
            if (selectedInstalled &&
                selectedVideoMode != MeetingVideoMode.ADB_VIRTUAL_SCREEN &&
                selectedCaptureMode != MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED
            ) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Launch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Open $effectiveSelectedShortLabel on Start",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Jump directly into $effectiveSelectedShortLabel after recording begins",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = autoSwitchToApp, onCheckedChange = onAutoSwitchToAppChange)
                }
            }
        }
    }

    // Mode Tip Banner
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(15.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            val tipText = when {
                selectedCaptureMode == MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED ->
                    "Best Stealth workflow: Start Stealth Isolation BEFORE tapping 'Connect/Join Audio' in $effectiveSelectedShortLabel (so Android routes its VoIP stream onto our silent loopback & virtual mic from the start). Need to tap Join? Tap 'Bring to Main', join the call, then tap 'Send to Virtual' to move the active call off-screen. MP4 exports to $saveFolderDisplay."
                selectedVideoMode == MeetingVideoMode.ADB_VIRTUAL_SCREEN ->
                    "ADB Virtual Screen launches an off-screen 720p display via Shizuku, moves $effectiveSelectedShortLabel onto it so your main phone screen stays free, and saves the MP4 to $saveFolderDisplay."
                selectedVideoMode == MeetingVideoMode.MEDIA_PROJECTION_VIDEO ->
                    "In Android's capture prompt, pick 'A single app' → $effectiveSelectedShortLabel to record only the target window. The final 720p MP4 is exported to $saveFolderDisplay."
                selectedCaptureMode.needsMediaProjection ->
                    "Audio-only mode records 64 kbps AAC (no video saved) and pauses background ambient listening automatically until you stop."
                else ->
                    "Mic + Speakerphone audio-only mode starts instantly without any screen-capture prompt."
            }
            Text(
                text = tipText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Primary Start Button
    Button(
        onClick = onStartRecording,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selectedCaptureMode == MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED) {
                Color(0xFF059669)
            } else {
                Color(0xFFDC2626)
            },
            contentColor = Color.White
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Icon(
            imageVector = if (selectedCaptureMode == MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED) {
                Icons.Default.Shield
            } else {
                Icons.Default.FiberManualRecord
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when {
                selectedCaptureMode == MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED ->
                    "Start Stealth Isolated Rec ($effectiveSelectedShortLabel)"
                selectedVideoMode == MeetingVideoMode.ADB_VIRTUAL_SCREEN ->
                    "Start Background Video ($effectiveSelectedShortLabel)"
                selectedVideoMode == MeetingVideoMode.MEDIA_PROJECTION_VIDEO ->
                    "Start Video + Audio ($effectiveSelectedShortLabel)"
                else ->
                    "Start Recording $effectiveSelectedAppLabel"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveMeetingRecordingBody(
    state: MeetingRecorderState,
    onTogglePause: () -> Unit,
    onToggleSpeakerphone: () -> Unit,
    onMoveToVirtualScreen: () -> Unit,
    onBringToMainScreen: () -> Unit,
    onToggleMiniPreview: () -> Unit,
    onLaunchMeetingApp: () -> Unit,
    onStopAndSave: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "meetingPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "meetingPulseAlpha"
    )

    val formattedTime = remember(state.elapsedSec) {
        MeetingRecorderService.formatDuration(state.elapsedSec)
    }
    val formattedSize = remember(state.recordedBytes) {
        when {
            state.recordedBytes >= 1_048_576L -> String.format("%.2f MB", state.recordedBytes / 1_048_576.0)
            state.recordedBytes >= 1_024L -> "${state.recordedBytes / 1_024L} KB"
            else -> "${state.recordedBytes} B"
        }
    }

    val appDisplayName = state.effectiveAppDisplayName()
    val appShortName = state.effectiveShortAppName()

    // 1. Live Timer & Status Hero Card
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (state.isPaused) Color(0xFFF59E0B) else Color(0xFFEF4444).copy(alpha = 0.7f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .alpha(if (state.isPaused) 1f else pulseAlpha)
                        .background(
                            color = if (state.isPaused) Color(0xFFF59E0B) else Color(0xFFEF4444),
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        state.isFinalizing -> "MUXING & SAVING MP4..."
                        state.isPaused -> "PAUSED • ${appDisplayName.uppercase()}"
                        state.captureMode.usesAdbStealthAudio -> "STEALTH ISOLATED • ${appDisplayName.uppercase()}"
                        else -> "RECORDING • ${appDisplayName.uppercase()}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (state.isPaused) Color(0xFFF59E0B) else Color(0xFFFB7185),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = formattedTime,
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (state.meetingTitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = state.meetingTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        text = state.captureMode.shortLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                if (state.videoMode != MeetingVideoMode.AUDIO_ONLY) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (state.videoActive) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            text = if (state.virtualDisplayId != null) {
                                "Virtual Display #${state.virtualDisplayId}"
                            } else {
                                state.videoMode.shortLabel
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (state.videoActive) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                if (state.audioCaptureTier.isNotBlank() && state.audioCaptureTier != "DISABLED") {
                    val (audioPillText, audioPillBg, audioPillFg) = when {
                        state.audioCaptureTier == "PER_UID_SILENT_LOOPBACK" ->
                            Triple("🔇 Silent Speaker (Per-UID)", Color(0xFF064E3B), Color(0xFFA7F3D0))
                        state.audioCaptureTier == "PER_UID_SHARED_LOOPBACK" ->
                            Triple("🔊 Shared Loopback (Per-UID)", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
                        state.audioCaptureTier.contains("SILENT", ignoreCase = true) ||
                            state.audioCaptureTier.contains("SUBMIX", ignoreCase = true) ->
                            Triple("🔇 Silent Speaker (Submix)", Color(0xFF064E3B), Color(0xFFA7F3D0))
                        state.audioCaptureTier.startsWith("FAILED") ->
                            Triple("⚠️ Audio Policy Blocked", Color(0xFF78350F), Color(0xFFFDE68A))
                        else ->
                            Triple(state.audioCaptureTier, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = audioPillBg) {
                        Text(
                            text = audioPillText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = audioPillFg,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                if (state.virtualMicTier.isNotBlank() && state.virtualMicTier != "DISABLED") {
                    val (micPillText, micPillBg, micPillFg) = when {
                        state.virtualMicTier == "VIRTUAL_SILENT_MIC_ACTIVE" ->
                            Triple("🎙️ Virtual Silent Mic", Color(0xFF064E3B), Color(0xFFA7F3D0))
                        state.virtualMicTier == "APPOPS_MIC_LOCKED" ->
                            Triple("🎙️ App Mic Muted (AppOps)", Color(0xFF064E3B), Color(0xFFA7F3D0))
                        state.virtualMicTier.startsWith("FALLBACK_APPOPS") ->
                            Triple("🎙️ AppOps Mic Fallback", Color(0xFF78350F), Color(0xFFFDE68A))
                        else ->
                            Triple(state.virtualMicTier, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = micPillBg) {
                        Text(
                            text = micPillText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = micPillFg,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                if (state.ambientMicKeptActive) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0C4A6E)
                    ) {
                        Text(
                            text = "🟢 Ambient Mic: Free",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFBAE6FD),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                if (state.ignoreInternalAppAudioForGemini) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            text = "Gemini: App Audio Ignored",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        text = formattedSize,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }

    // 2. Active Video / ADB Virtual Screen Control Card
    if (state.videoMode != MeetingVideoMode.AUDIO_ONLY) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.28f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (state.videoMode == MeetingVideoMode.ADB_VIRTUAL_SCREEN) {
                            Icons.Default.DesktopWindows
                        } else {
                            Icons.Default.Cast
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.captureMode.usesAdbStealthAudio) {
                                "Stealth Virtual Screen & Hardware Isolation"
                            } else if (state.videoActive) {
                                "Background Video Active"
                            } else {
                                "Video Pipeline Status"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = state.videoStatusNote.ifBlank { state.videoMode.title },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (state.videoMode == MeetingVideoMode.ADB_VIRTUAL_SCREEN && state.virtualDisplayId != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onMoveToVirtualScreen,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                        ) {
                            Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Send to Virtual",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = onBringToMainScreen,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                        ) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Bring to Main",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Button(
                        onClick = onToggleMiniPreview,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isMiniPreviewVisible) {
                                Color(0xFF065F46)
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                            contentColor = if (state.isMiniPreviewVisible) {
                                Color(0xFFD1FAE5)
                            } else {
                                MaterialTheme.colorScheme.onTertiary
                            }
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .testTag("toggle_mini_preview_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPictureAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (state.isMiniPreviewVisible) {
                                "Hide Interactive Mini-Preview"
                            } else {
                                "Open Interactive Mini-Preview (Tap Virtual Screen)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // 3. Live Audio Level Meters
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(12.dp)
        ) {
            if (state.captureMode.needsMediaProjection || state.captureMode.usesAdbStealthAudio) {
                AudioLevelMeterRow(
                    icon = Icons.Default.GraphicEq,
                    label = when {
                        state.captureMode.usesAdbStealthAudio && state.ignoreInternalAppAudioForGemini ->
                            "Isolated $appShortName Audio (MP4 Only • Gemini Ignored)"
                        state.captureMode.usesAdbStealthAudio ->
                            "Isolated $appShortName Audio (Silent Speaker Loopback)"
                        state.ignoreInternalAppAudioForGemini ->
                            "Internal App Audio (Saved Only • Gemini Ignored)"
                        else ->
                            "Internal App Audio ($appShortName)"
                    },
                    levelNorm = state.internalLevelNorm,
                    activeColor = if (state.captureMode.usesAdbStealthAudio) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                    statusBadge = when {
                        state.isPaused -> "Paused"
                        state.internalAudioDetected -> "Signal Active"
                        else -> "Waiting..."
                    },
                    statusColor = when {
                        state.isPaused -> MaterialTheme.colorScheme.onSurfaceVariant
                        state.internalAudioDetected -> Color(0xFF10B981)
                        else -> Color(0xFFF59E0B)
                    }
                )
            }
            if (state.captureMode.needsMic) {
                AudioLevelMeterRow(
                    icon = if (state.micSilencedByOs) Icons.Default.MicOff else Icons.Default.Mic,
                    label = buildString {
                        append("Local Microphone")
                        if (state.activeMicSourceLabel.isNotBlank()) {
                            append(" (${state.activeMicSourceLabel})")
                        }
                    },
                    levelNorm = state.micLevelNorm,
                    activeColor = if (state.micSilencedByOs) MaterialTheme.colorScheme.error else Color(0xFF10B981),
                    statusBadge = when {
                        state.isPaused -> "Paused"
                        state.micSilencedByOs -> "Silenced by VoIP App"
                        else -> "Active"
                    },
                    statusColor = when {
                        state.isPaused -> MaterialTheme.colorScheme.onSurfaceVariant
                        state.micSilencedByOs -> MaterialTheme.colorScheme.error
                        else -> Color(0xFF10B981)
                    }
                )
            }
        }
    }

    // 4. Smart VoIP Silence Diagnostic Banner
    if (state.internalSilenceHint) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF78350F).copy(alpha = 0.35f)
            ),
            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.65f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "No Internal Playback Signal Detected Yet",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFDE68A)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$appDisplayName may be routing remote voices on Android's protected VoIP channel (USAGE_VOICE_COMMUNICATION). Enable Speakerphone Boost below so your mic captures remote speakers too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFEF3C7).copy(alpha = 0.9f)
                )
                if (state.captureMode.needsMic) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onToggleSpeakerphone,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.speakerphoneBoostEnabled) Color(0xFF10B981) else Color(0xFFF59E0B),
                            contentColor = Color(0xFF0F172A)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (state.speakerphoneBoostEnabled) "Speakerphone Boost ON (Tap to Disable)" else "Enable Speakerphone Boost",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // 5. Mic silenced by OS warning
    if (state.micSilencedByOs) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MicOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Android temporarily silenced our microphone because $appDisplayName holds active VoIP mic priority. Internal playback & video continue recording.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }

    // 6. Speakerphone Boost Quick Toggle
    if (state.captureMode.needsMic && !state.internalSilenceHint) {
        OutlinedButton(
            onClick = onToggleSpeakerphone,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(
                1.dp,
                if (state.speakerphoneBoostEnabled) Color(0xFF10B981) else MaterialTheme.colorScheme.outline
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (state.speakerphoneBoostEnabled) Color(0xFF10B981).copy(alpha = 0.14f) else Color.Transparent,
                contentColor = if (state.speakerphoneBoostEnabled) Color(0xFF34D399) else MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (state.speakerphoneBoostEnabled) {
                    "Speakerphone Route Boost: ON"
                } else {
                    "Enable Speakerphone Route Boost"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    // 7. Pause / Resume + Open Target App Row
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = onTogglePause,
            enabled = !state.isFinalizing,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
        ) {
            Icon(
                imageVector = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (state.isPaused) "Resume" else "Pause",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        OutlinedButton(
            onClick = onLaunchMeetingApp,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Launch,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Open $appShortName",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    // 8. Stop & Save Button
    Button(
        onClick = onStopAndSave,
        enabled = !state.isFinalizing,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = Color.White
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        if (state.isFinalizing) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (state.videoActive) "Muxing MP4 & Saving..." else "Saving Meeting Audio...",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        } else {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (state.videoActive) "Stop & Save MP4 Video" else "Stop & Save Meeting",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Text(
        text = "Closing this panel keeps recording running safely in the background via the status-bar notification.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AudioLevelMeterRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    levelNorm: Float,
    activeColor: Color,
    statusBadge: String,
    statusColor: Color
) {
    val animatedLevel by animateFloatAsState(
        targetValue = levelNorm.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 120),
        label = "vuLevel"
    )
    val animatedBarColor by animateColorAsState(
        targetValue = if (animatedLevel > 0.85f) Color(0xFFF59E0B) else activeColor,
        label = "vuColor"
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = statusBadge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = statusColor
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = animatedLevel.coerceAtLeast(0.02f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(animatedBarColor)
            )
        }
    }
}

@Composable
private fun InstalledAppPickerDialog(
    selectedPackageName: String,
    onDismiss: () -> Unit,
    onSelectApp: (InstalledAppInfo) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            InstalledAppQueryHelper.listLaunchableApps(context)
        }
        apps = loaded
        isLoading = false
    }

    val filteredApps = remember(apps, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isBlank()) {
            apps
        } else {
            apps.filter {
                it.appLabel.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(min = 320.dp, max = 640.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Select App to Isolate & Record",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Zoom, Webex, Teams, Meet, LINE, YouTube, or any installed app",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search app name or package...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (isLoading) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Loading installed apps...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (filteredApps.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "No matching apps found.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val isSelected = app.packageName == selectedPackageName
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectApp(app) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.appLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = app.packageName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (app.uid > 0) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surface
                                        ) {
                                            Text(
                                                text = "UID ${app.uid}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
}

