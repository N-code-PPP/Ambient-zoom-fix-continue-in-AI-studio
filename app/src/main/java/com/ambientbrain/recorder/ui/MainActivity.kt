package com.ambientbrain.recorder.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.core.content.ContextCompat
import com.ambientbrain.recorder.ai.GeminiRateLimitManager
import com.ambientbrain.recorder.ai.SessionAnalyzeWorker
import com.ambientbrain.recorder.data.AppDatabase
import com.ambientbrain.recorder.data.AudioPurgeWorker
import com.ambientbrain.recorder.data.CaptureStateBus
import com.ambientbrain.recorder.data.ExportPreset
import com.ambientbrain.recorder.data.GeminiPauseState
import com.ambientbrain.recorder.data.GeminiPauseStateBus
import com.ambientbrain.recorder.data.LiveCaptureStatus
import com.ambientbrain.recorder.data.LiveCaptureTelemetry
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SessionEntity
import com.ambientbrain.recorder.data.SessionMode
import com.ambientbrain.recorder.data.Status
import com.ambientbrain.recorder.service.AmbientCaptureAccessibilityService
import com.ambientbrain.recorder.service.CaptureEngine
import com.ambientbrain.recorder.service.CaptureEngineController
import com.ambientbrain.recorder.service.MeetingMetadataHelper
import com.ambientbrain.recorder.service.MeetingRecorderService
import com.ambientbrain.recorder.service.MeetingRecorderState
import com.ambientbrain.recorder.service.MeetingRecorderStateBus
import com.ambientbrain.recorder.service.ShizukuAdbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import java.util.Calendar

private val DarkColorPalette = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFF022C22),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFF818CF8),
    onTertiary = Color(0xFF1E1B4B),
    tertiaryContainer = Color(0xFF2E2A68),
    onTertiaryContainer = Color(0xFFE0E7FF),
    error = Color(0xFFFB7185),
    errorContainer = Color(0xFF4C0519),
    onErrorContainer = Color(0xFFFFE4E6),
    background = Color(0xFF080C11),
    surface = Color(0xFF0E1520),
    surfaceVariant = Color(0xFF141F2D),
    outline = Color(0xFF1E2D40),
    outlineVariant = Color(0xFF162232)
)

class MainActivity : ComponentActivity() {

    private var fallbackForegroundEngine: CaptureEngine? = null
    private val openMeetingRecorderNonce = mutableIntStateOf(0)
    private val selfHealExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AmbientBrain-MainSelfHeal").apply {
            isDaemon = true
        }
    }
    private val shizukuPermissionListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == com.ambientbrain.recorder.service.ShizukuAdbManager.SHIZUKU_REQ_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(applicationContext, "Shizuku permission granted!", Toast.LENGTH_SHORT).show()
                runCatching {
                    selfHealExecutor.execute {
                        if (!MeetingRecorderStateBus.state.value.isRecording) {
                            com.ambientbrain.recorder.service.AdbVirtualScreenController
                                .restorePendingAppOpsMicLockIfAny(applicationContext)
                        }
                    }
                }
            }
        }
    }

    private fun handleIntentForMeetingRecorder(intent: Intent?) {
        if (intent?.getBooleanExtra(MeetingRecorderService.EXTRA_OPEN_MEETING_RECORDER, false) == true) {
            intent.removeExtra(MeetingRecorderService.EXTRA_OPEN_MEETING_RECORDER)
            openMeetingRecorderNonce.intValue++
        }
    }

    private fun triggerAsyncSelfHealIfUnbound() {
        val prefs = PreferencesRepo(applicationContext)
        if (!prefs.isListeningEnabled ||
            AmbientCaptureAccessibilityService.isSystemBound ||
            !ShizukuAdbManager.hasWriteSecureSettings(applicationContext)
        ) {
            return
        }
        runCatching {
            selfHealExecutor.execute {
                val res = ShizukuAdbManager.selfHealAccessibilityService(
                    context = applicationContext,
                    forceRebindIfUnbound = true
                )
                if (res.repaired) {
                    Log.i(TAG, "Zero-touch self-heal repaired A11y binding: ${res.message}")
                }
            }
        }
    }

    private fun startListeningEngine() {
        val prefs = PreferencesRepo(applicationContext)
        prefs.isListeningEnabled = true
        triggerAsyncSelfHealIfUnbound()
        if (AmbientCaptureAccessibilityService.isSystemBound) {
            fallbackForegroundEngine?.stop(persistDisabled = false)
            fallbackForegroundEngine = null
            if (AmbientCaptureAccessibilityService.startListening(applicationContext)) {
                return
            }
        }
        val activeEngine = CaptureEngineController.instance
        if (activeEngine != null && activeEngine !== fallbackForegroundEngine) {
            activeEngine.stop(persistDisabled = false)
        }
        val ownEngine = fallbackForegroundEngine
        if (ownEngine == null || !ownEngine.isActiveRunning()) {
            fallbackForegroundEngine = CaptureEngine(applicationContext)
        }
        fallbackForegroundEngine?.start()
    }

    private fun stopListeningEngine() {
        val prefs = PreferencesRepo(applicationContext)
        prefs.isListeningEnabled = false
        CaptureEngineController.setUserManualPaused(false)
        CaptureEngineController.clearForegroundYield()
        AmbientCaptureAccessibilityService.stopListening(applicationContext)
        fallbackForegroundEngine?.stop(persistDisabled = true)
        fallbackForegroundEngine = null
        CaptureEngineController.stop()
        CaptureStateBus.update {
            it.copy(
                status = LiveCaptureStatus.STOPPED,
                isListeningEnabled = false,
                pauseReason = null,
                isExternalMicActive = false,
                isMicSilencedByOs = false,
                externalMicOwnerPackage = null,
                externalMicOwnerLabel = null,
                externalMicOwnerSource = null,
                currentSessionElapsedSec = 0L,
                lastVadProbability = 0f
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AudioPurgeWorker.scheduleDaily(applicationContext)
        handleIntentForMeetingRecorder(intent)

        runCatching {
            selfHealExecutor.execute {
                if (!MeetingRecorderStateBus.state.value.isRecording) {
                    com.ambientbrain.recorder.service.AdbVirtualScreenController
                        .restorePendingAppOpsMicLockIfAny(applicationContext)
                }
            }
        }

        runCatching {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }

        setContent {
            MaterialTheme(colorScheme = DarkColorPalette) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AmbientBrainMainApp(
                        openMeetingRecorderNonce = openMeetingRecorderNonce.intValue,
                        onStartListening = { startListeningEngine() },
                        onStopListening = { stopListeningEngine() },
                        onStopListeningAndExit = {
                            if (MeetingRecorderStateBus.state.value.isRecording) {
                                MeetingRecorderService.stopRecording(applicationContext)
                            }
                            stopListeningEngine()
                            finishAndRemoveTask()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentForMeetingRecorder(intent)
    }

    override fun onDestroy() {
        runCatching {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
        runCatching {
            selfHealExecutor.shutdownNow()
        }
        super.onDestroy()
    }
}

private const val TAG = "MainActivity"

@Composable
private fun AmbientBrainMainApp(
    openMeetingRecorderNonce: Int,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onStopListeningAndExit: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).dao() }
    val prefs = remember { PreferencesRepo(context) }
    val playbackController = rememberAudioPlaybackController()

    var activeTab by remember { mutableIntStateOf(0) } // 0=Inbox, 1=Rounds, 2=AudioVault, 3=Timeline, 4=Settings
    val tabBackStack = remember { mutableStateListOf<Int>() }
    var transcriptLangMode by remember { mutableStateOf(prefs.transcriptLangMode) }
    var retentionDays by remember { mutableIntStateOf(prefs.retentionDays) }

    LaunchedEffect(activeTab) {
        transcriptLangMode = prefs.transcriptLangMode
        retentionDays = prefs.retentionDays
    }

    fun navigateToTab(targetTab: Int) {
        if (targetTab == activeTab) return
        if (tabBackStack.lastOrNull() != activeTab) {
            tabBackStack.add(activeTab)
            if (tabBackStack.size > 16) {
                tabBackStack.removeAt(0)
            }
        }
        activeTab = targetTab
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> CaptureStateBus.setUiForeground(true)
                Lifecycle.Event.ON_RESUME -> {
                    CaptureStateBus.setUiForeground(true)
                    CaptureEngineController.onOwnActivityResumed(context.packageName)
                }
                Lifecycle.Event.ON_STOP -> CaptureStateBus.setUiForeground(false)
                else -> {}
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            CaptureStateBus.setUiForeground(true)
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            CaptureEngineController.onOwnActivityResumed(context.packageName)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            CaptureStateBus.setUiForeground(false)
        }
    }

    BackHandler(enabled = tabBackStack.isNotEmpty() || activeTab != 0) {
        if (tabBackStack.isNotEmpty()) {
            activeTab = tabBackStack.removeAt(tabBackStack.lastIndex)
        } else {
            activeTab = 0
        }
    }

    val telemetry by CaptureStateBus.telemetry.collectAsStateWithLifecycle()
    val meetingState by MeetingRecorderStateBus.state.collectAsStateWithLifecycle()
    val geminiPauseState by GeminiPauseStateBus.state.collectAsStateWithLifecycle()
    val activeItems by dao.observeAllActiveItems().collectAsState(initial = emptyList())
    val roundSessions by dao.observeRoundSessions().collectAsState(initial = emptyList())
    val allSessions by dao.observeAllSessionsWithDetails().collectAsState(initial = emptyList())
    val failedSessions by dao.observeFailedSessions().collectAsState(initial = emptyList())
    val queuedSessionsCount by dao.observeQueuedSessionsCount().collectAsState(initial = 0)
    var pauseClockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        prefs.syncGeminiPauseStateBus()
        GeminiRateLimitManager.refreshTelemetry(prefs)
    }

    LaunchedEffect(geminiPauseState.isPaused, geminiPauseState.pausedUntilMs) {
        while (geminiPauseState.isPaused) {
            val now = System.currentTimeMillis()
            pauseClockMs = now
            if (geminiPauseState.pausedUntilMs > 0L && now >= geminiPauseState.pausedUntilMs) {
                prefs.isGeminiUsagePausedNow(now)
                SessionAnalyzeWorker.resumeAllQueuedAfterUnpause(context)
                break
            }
            delay(1_000L)
        }
    }

    val isGeminiActivelyPaused = geminiPauseState.isActivelyPaused(pauseClockMs)

    val audioFilesCount = remember(allSessions, activeItems) {
        val sessionRawCount = allSessions.count {
            it.session.combinedSourceIdsJson.isNullOrBlank() && !it.session.rawPath.isNullOrBlank()
        }
        val proofClipsCount = activeItems.count { !it.clipPath.isNullOrBlank() }
        sessionRawCount + proofClipsCount
    }

    val startOfDayMs = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis
    }
    val tokensToday by dao.observeTotalTokensSince(startOfDayMs).collectAsState(initial = 0L)
    val cachedTokensToday by dao.observeCachedTokensSince(startOfDayMs).collectAsState(initial = 0L)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        if (grantedMap[Manifest.permission.RECORD_AUDIO] == true && prefs.isListeningEnabled) {
            onStartListening()
        }
    }

    LaunchedEffect(Unit) {
        if (!prefs.isListeningEnabled) {
            CaptureStateBus.update {
                it.copy(
                    status = LiveCaptureStatus.STOPPED,
                    isListeningEnabled = false,
                    currentSessionElapsedSec = 0L,
                    lastVadProbability = 0f
                )
            }
            return@LaunchedEffect
        }

        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasPostNotif = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMic) {
            if (!AmbientCaptureAccessibilityService.isSystemBound &&
                ShizukuAdbManager.hasWriteSecureSettings(context)
            ) {
                val healResult = withContext(Dispatchers.IO) {
                    ShizukuAdbManager.selfHealAccessibilityService(
                        context = context,
                        forceRebindIfUnbound = true
                    )
                }
                if (healResult.repaired) {
                    delay(500L)
                }
            }
            onStartListening()
            if (!hasPostNotif) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        } else {
            val toRequest = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE
            )
            if (!hasPostNotif) {
                toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    var showMeetingRecorderDialog by remember { mutableStateOf(false) }
    var showAdbDiagnosticsDialog by remember { mutableStateOf(false) }
    var showDataExportDialog by remember { mutableStateOf(false) }
    var exportDialogInitialTab by remember { mutableIntStateOf(0) }
    var exportDialogInitialPreset by remember { mutableStateOf(ExportPreset.STANDARD_BACKUP) }
    var exportDialogSessionIds by remember { mutableStateOf<Set<String>?>(null) }
    var settingsSyncVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(openMeetingRecorderNonce) {
        if (openMeetingRecorderNonce > 0) {
            showMeetingRecorderDialog = true
        }
    }

    if (showMeetingRecorderDialog) {
        MeetingRecorderDialog(
            onDismissRequest = { showMeetingRecorderDialog = false },
            onOpenAudioVault = { navigateToTab(2) },
            onOpenTimeline = { navigateToTab(3) },
            onOpenSettings = { navigateToTab(4) }
        )
    }

    if (showAdbDiagnosticsDialog) {
        AdbDiagnosticsDialog(
            onDismissRequest = { showAdbDiagnosticsDialog = false },
            onStartListening = onStartListening
        )
    }

    if (showDataExportDialog) {
        DataExportDialog(
            onDismissRequest = { showDataExportDialog = false },
            initialTab = exportDialogInitialTab,
            initialPreset = exportDialogInitialPreset,
            initialSelectedSessionIds = exportDialogSessionIds,
            onBackupRestored = {
                transcriptLangMode = prefs.transcriptLangMode
                retentionDays = prefs.retentionDays
                settingsSyncVersion++
            }
        )
    }

    val isCurrentlyListening = telemetry.status != LiveCaptureStatus.STOPPED && telemetry.isListeningEnabled

    Scaffold(
        topBar = {
            PersistentTopStatusBar(
                telemetry = telemetry,
                meetingState = meetingState,
                retentionDays = retentionDays,
                isListening = isCurrentlyListening,
                onOpenMeetingRecorder = { showMeetingRecorderDialog = true },
                onStopMeetingRecording = {
                    MeetingRecorderService.stopRecording(context)
                    Toast.makeText(context, "Stopping & saving meeting recording...", Toast.LENGTH_SHORT).show()
                },
                onToggleListening = {
                    if (isCurrentlyListening) {
                        onStopListening()
                        Toast.makeText(
                            context,
                            "Listening stopped. You can safely close the app with zero background run.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            onStartListening()
                            Toast.makeText(context, "Listening service started!", Toast.LENGTH_SHORT).show()
                        } else {
                            prefs.isListeningEnabled = true
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.READ_PHONE_STATE
                                )
                            )
                        }
                    }
                },
                onStopListeningAndExit = onStopListeningAndExit,
                onStartManualRound = {
                    if (!isCurrentlyListening) {
                        onStartListening()
                    }
                    CaptureEngineController.startManualRound()
                    navigateToTab(1)
                },
                onEndRoundOrSession = {
                    CaptureEngineController.endActiveRoundOrSession()
                },
                onOpenSettings = {
                    if (activeTab == 4) {
                        if (tabBackStack.isNotEmpty()) {
                            activeTab = tabBackStack.removeAt(tabBackStack.lastIndex)
                        } else {
                            activeTab = 0
                        }
                    } else {
                        navigateToTab(4)
                    }
                },
                onOpenAudioVault = {
                    navigateToTab(2)
                },
                onOpenAdbDiagnostics = {
                    showAdbDiagnosticsDialog = true
                },
                onOpenDataExport = {
                    exportDialogInitialTab = 0
                    exportDialogInitialPreset = ExportPreset.STANDARD_BACKUP
                    exportDialogSessionIds = null
                    showDataExportDialog = true
                },
                onOpenSettingsBackup = {
                    exportDialogInitialTab = 0
                    exportDialogInitialPreset = ExportPreset.SETTINGS_AND_ROSTER
                    exportDialogSessionIds = null
                    showDataExportDialog = true
                },
                onOpenRestoreBackup = {
                    exportDialogInitialTab = 1
                    exportDialogInitialPreset = ExportPreset.STANDARD_BACKUP
                    exportDialogSessionIds = null
                    showDataExportDialog = true
                },
                onForceSplitSession = {
                    CaptureEngineController.endActiveRoundOrSession()
                    Toast.makeText(context, "Session split & queued for AI analysis!", Toast.LENGTH_SHORT).show()
                },
                onCopyWardHandover = {
                    scope.launch {
                        val markdown = CopyFormatter.formatItemsList(activeItems, TaskCopyTemplate.WARD_BY_PATIENT)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Ward Handover", markdown))
                        Toast.makeText(context, "Ward handover copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                },
                onRunStorageCleaner = {
                    AudioPurgeWorker.enqueueOnce(context)
                    Toast.makeText(context, "Storage clean-up worker triggered!", Toast.LENGTH_SHORT).show()
                },
                failedSessionsCount = failedSessions.size,
                isGeminiPaused = isGeminiActivelyPaused,
                onToggleGeminiPause = {
                    if (isGeminiActivelyPaused) {
                        prefs.setGeminiPaused(paused = false, durationMs = 0L)
                        SessionAnalyzeWorker.resumeAllQueuedAfterUnpause(context)
                        Toast.makeText(
                            context,
                            if (queuedSessionsCount > 0) {
                                "Gemini AI resumed — analyzing $queuedSessionsCount queued session(s)..."
                            } else {
                                "Gemini AI resumed."
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        prefs.setGeminiPaused(paused = true, durationMs = 0L)
                        Toast.makeText(
                            context,
                            "Gemini AI paused. Audio continues recording & queueing safely.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onRetryAllFailed = {
                    SessionAnalyzeWorker.retryAllFailed(context)
                    Toast.makeText(context, "Retrying Gemini connection for all pending sessions...", Toast.LENGTH_SHORT).show()
                }
            )
        },
        bottomBar = {
            Column {
                PersistentAudioPlayerBottomBar(
                    playbackController = playbackController,
                    onOpenAudioVault = { navigateToTab(2) }
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
                        val openTasksCount = activeItems.count { !it.isDone }
                        NavigationBarItem(
                            selected = activeTab == 0,
                            onClick = { navigateToTab(0) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (openTasksCount > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(openTasksCount.toString(), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Checklist, contentDescription = null)
                                }
                            },
                            label = { Text("Tasks & Notes", fontWeight = if (activeTab == 0) FontWeight.Bold else FontWeight.Normal) }
                        )
                        NavigationBarItem(
                            selected = activeTab == 1,
                            onClick = { navigateToTab(1) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (roundSessions.isNotEmpty()) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.secondary,
                                                contentColor = MaterialTheme.colorScheme.onSecondary
                                            ) {
                                                Text(roundSessions.size.toString(), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.MedicalServices, contentDescription = null)
                                }
                            },
                            label = { Text("Ward Rounds", fontWeight = if (activeTab == 1) FontWeight.Bold else FontWeight.Normal) }
                        )
                        NavigationBarItem(
                            selected = activeTab == 2,
                            onClick = { navigateToTab(2) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (audioFilesCount > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.tertiary,
                                                contentColor = MaterialTheme.colorScheme.onTertiary
                                            ) {
                                                Text(audioFilesCount.toString(), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.GraphicEq, contentDescription = null)
                                }
                            },
                            label = { Text("Audio Vault", fontWeight = if (activeTab == 2) FontWeight.Bold else FontWeight.Normal) }
                        )
                        NavigationBarItem(
                            selected = activeTab == 3,
                            onClick = { navigateToTab(3) },
                            icon = { Icon(Icons.Default.History, contentDescription = null) },
                            label = { Text("Timeline", fontWeight = if (activeTab == 3) FontWeight.Bold else FontWeight.Normal) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isGeminiActivelyPaused) {
                GeminiPausedBanner(
                    pauseState = geminiPauseState,
                    nowMs = pauseClockMs,
                    queuedSessionsCount = queuedSessionsCount,
                    onResumeGemini = {
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
                )
            }

            if (failedSessions.isNotEmpty()) {
                FailedSessionsBanner(
                    failedSessions = failedSessions,
                    onRetryAll = {
                        SessionAnalyzeWorker.retryAllFailed(context)
                        Toast.makeText(context, "Retrying Gemini connection now...", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val deleteSessionAndArtifacts: (String) -> Unit = { sessionId ->
                    scope.launch {
                        val s = dao.getSessionById(sessionId)
                        val clipPaths = dao.getClipPathsForSession(sessionId)
                        val isCombined = !s?.combinedSourceIdsJson.isNullOrBlank()
                        val rawPathToDelete = if (!isCombined) s?.rawPath else null

                        val activePath = playbackController.activeFilePath
                        if (activePath != null && (activePath == rawPathToDelete || clipPaths.contains(activePath))) {
                            playbackController.stop()
                        }

                        rawPathToDelete?.let { path ->
                            runCatching { File(path).delete() }
                        }
                        if (!isCombined) {
                            MeetingMetadataHelper.parseFirst(s?.callEventsJson)?.let { marker ->
                                marker.videoPath?.let { vPath -> runCatching { File(vPath).delete() } }
                                marker.geminiAudioPath?.let { gPath ->
                                    if (gPath != rawPathToDelete) {
                                        runCatching { File(gPath).delete() }
                                    }
                                }
                            }
                        }
                        clipPaths.forEach { clipPath ->
                            runCatching { File(clipPath).delete() }
                        }
                        dao.deleteSessionById(sessionId)
                    }
                }

                when (activeTab) {
                    0 -> InboxScreen(
                        items = activeItems,
                        transcriptLangMode = transcriptLangMode,
                        playbackController = playbackController,
                        allSessions = allSessions,
                        onReanalyzeSession = { sessionId ->
                            SessionAnalyzeWorker.retryNow(context, sessionId)
                            Toast.makeText(context, "Retrying Gemini analysis now...", Toast.LENGTH_SHORT).show()
                        },
                        onToggleDone = { item ->
                            scope.launch { dao.setItemDone(item.id, !item.isDone) }
                        },
                        onArchiveItem = { item ->
                            scope.launch { dao.archiveItem(item.id) }
                        },
                        onUnarchiveItem = { item ->
                            scope.launch { dao.unarchiveItem(item.id) }
                        },
                        onArchiveCompleted = {
                            scope.launch {
                                val archived = dao.archiveAllCompletedItems()
                                if (archived > 0) {
                                    Toast.makeText(context, "Archived $archived completed item(s)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onUpdateItem = { updated ->
                            scope.launch {
                                dao.updateItemContent(
                                    itemId = updated.id,
                                    title = updated.title,
                                    details = updated.details,
                                    assignee = updated.assignee,
                                    due = updated.due,
                                    needsVerification = updated.needsVerification
                                )
                            }
                        },
                        onAddNewItem = { newItem ->
                            scope.launch {
                                val session = SessionEntity(
                                    id = newItem.sessionId,
                                    mode = SessionMode.EVERYDAY,
                                    startMs = System.currentTimeMillis(),
                                    endMs = System.currentTimeMillis(),
                                    speechMs = 0L,
                                    rawPath = null,
                                    status = Status.DONE,
                                    title = newItem.title.take(30),
                                    summary = newItem.details.ifBlank { "Manual user note" }
                                )
                                dao.upsertSession(session)
                                dao.insertItems(listOf(newItem))
                                Toast.makeText(context, "Note saved to Inbox!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    1 -> RoundsScreen(
                        roundSessions = roundSessions,
                        defaultLangMode = transcriptLangMode,
                        playbackController = playbackController,
                        onTogglePinSession = { sessionId, pinned ->
                            scope.launch { dao.setSessionPinned(sessionId, pinned) }
                        },
                        onRenameSpeaker = { sessionId, speakerId, newLabel ->
                            scope.launch {
                                dao.renameSpeakerAcrossSession(sessionId, speakerId, newLabel)
                            }
                        },
                        onReanalyzeSession = { sessionId ->
                            SessionAnalyzeWorker.retryNow(context, sessionId)
                            Toast.makeText(context, "Retrying Gemini analysis now...", Toast.LENGTH_SHORT).show()
                        },
                        onStartManualRound = {
                            if (!isCurrentlyListening) {
                                onStartListening()
                            }
                            CaptureEngineController.startManualRound()
                        }
                    )

                    2 -> AudioReviewScreen(
                        allSessions = allSessions,
                        activeItems = activeItems,
                        playbackController = playbackController,
                        onTogglePinSession = { sessionId, pinned ->
                            scope.launch { dao.setSessionPinned(sessionId, pinned) }
                        },
                        onReanalyzeSession = { sessionId ->
                            SessionAnalyzeWorker.retryNow(context, sessionId)
                            Toast.makeText(context, "Retrying Gemini analysis now...", Toast.LENGTH_SHORT).show()
                        },
                        onDeleteSession = deleteSessionAndArtifacts,
                        onRunStorageCleaner = {
                            AudioPurgeWorker.enqueueOnce(context)
                            Toast.makeText(context, "Storage clean-up worker triggered!", Toast.LENGTH_SHORT).show()
                        }
                    )

                    3 -> TimelineScreen(
                        allSessions = allSessions,
                        retentionDays = retentionDays,
                        transcriptLangMode = transcriptLangMode,
                        playbackController = playbackController,
                        onTogglePin = { sessionId, pinned ->
                            scope.launch { dao.setSessionPinned(sessionId, pinned) }
                        },
                        onReanalyze = { sessionId ->
                            SessionAnalyzeWorker.retryNow(context, sessionId)
                            Toast.makeText(context, "Retrying Gemini analysis now...", Toast.LENGTH_SHORT).show()
                        },
                        onDeleteSession = deleteSessionAndArtifacts,
                        onExportSelectedSessions = { selectedIds ->
                            exportDialogInitialTab = 0
                            exportDialogInitialPreset = ExportPreset.CUSTOM
                            exportDialogSessionIds = selectedIds
                            showDataExportDialog = true
                        }
                    )

                    4 -> SettingsScreen(
                        prefs = prefs,
                        tokensToday = tokensToday,
                        cachedTokensToday = cachedTokensToday,
                        externalSettingsSyncVersion = settingsSyncVersion,
                        playbackController = playbackController,
                        onSettingsSaved = {
                            transcriptLangMode = prefs.transcriptLangMode
                            retentionDays = prefs.retentionDays
                            if (tabBackStack.isNotEmpty()) {
                                activeTab = tabBackStack.removeAt(tabBackStack.lastIndex)
                            } else {
                                activeTab = 0
                            }
                        },
                        onSettingsRestored = {
                            transcriptLangMode = prefs.transcriptLangMode
                            retentionDays = prefs.retentionDays
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FailedSessionsBanner(
    failedSessions: List<SessionEntity>,
    onRetryAll: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (failedSessions.size == 1) {
                        "Gemini Connection Failed"
                    } else {
                        "${failedSessions.size} Audio Sessions Waiting for Gemini"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                val first = failedSessions.first()
                val minLeft = if (first.nextRetryEpochMs > System.currentTimeMillis()) {
                    ((first.nextRetryEpochMs - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(1L)
                } else 0L
                val attemptLabel = if (first.retryCount > 3 && first.status != Status.FAILED_PERMANENT) {
                    "Attempt ${first.retryCount}"
                } else {
                    "Attempt ${first.retryCount}/3"
                }
                val infoText = when {
                    !first.lastError.isNullOrBlank() -> first.lastError!!
                    first.status == Status.FAILED_PERMANENT -> "Audio stored safely • Max 3 attempts reached"
                    minLeft > 0L -> "Audio stored safely • Auto-retry in ${minLeft}m ($attemptLabel)"
                    else -> "Audio stored safely • Retrying automatically ($attemptLabel)"
                }
                Text(
                    text = infoText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                    maxLines = 3
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onRetryAll,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Try Again", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GeminiPausedBanner(
    pauseState: GeminiPauseState,
    nowMs: Long,
    queuedSessionsCount: Int,
    onResumeGemini: () -> Unit
) {
    val amber = Color(0xFFF59E0B)
    val remainingText = if (pauseState.pausedUntilMs > nowMs) {
        val totalSec = ((pauseState.pausedUntilMs - nowMs) / 1000L).coerceAtLeast(1L)
        val hrs = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        when {
            hrs > 0L -> "Auto-resumes in ${hrs}h ${mins}m"
            mins > 0L -> "Auto-resumes in ${mins}m ${secs}s"
            else -> "Auto-resumes in ${secs}s"
        }
    } else {
        "Paused until you resume"
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = amber.copy(alpha = 0.16f)
        ),
        border = BorderStroke(1.dp, amber.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PauseCircle,
                contentDescription = "Gemini AI Paused",
                tint = amber,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Gemini AI Temporarily Paused",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = amber
                )
                Text(
                    text = "$remainingText • $queuedSessionsCount queued • Audio capture stays active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 2
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onResumeGemini,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = amber,
                    contentColor = Color(0xFF1F1300)
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("resume_gemini_banner_button")
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Resume", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PersistentTopStatusBar(
    telemetry: LiveCaptureTelemetry,
    meetingState: MeetingRecorderState = MeetingRecorderState(),
    retentionDays: Int,
    isListening: Boolean,
    failedSessionsCount: Int = 0,
    isGeminiPaused: Boolean = false,
    onToggleGeminiPause: () -> Unit = {},
    onOpenMeetingRecorder: () -> Unit = {},
    onStopMeetingRecording: () -> Unit = {},
    onRetryAllFailed: () -> Unit = {},
    onToggleListening: () -> Unit,
    onStopListeningAndExit: () -> Unit,
    onStartManualRound: () -> Unit,
    onEndRoundOrSession: () -> Unit,
    onOpenAudioVault: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenAdbDiagnostics: () -> Unit,
    onOpenDataExport: () -> Unit = {},
    onOpenSettingsBackup: () -> Unit = {},
    onOpenRestoreBackup: () -> Unit = {},
    onForceSplitSession: () -> Unit,
    onCopyWardHandover: () -> Unit,
    onRunStorageCleaner: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    val isMeetingActive = meetingState.isRecording || meetingState.isFinalizing
    val isRoundActive = telemetry.status == LiveCaptureStatus.RECORDING_ROUND_MANUAL ||
        telemetry.status == LiveCaptureStatus.RECORDING_ROUND_AUTO
    val isRecordingActive = isRoundActive ||
        telemetry.status == LiveCaptureStatus.RECORDING_AMBIENT ||
        telemetry.status == LiveCaptureStatus.RECORDING_IN_CALL

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val elapsedFormatted = remember(telemetry.currentSessionElapsedSec) {
        val m = telemetry.currentSessionElapsedSec / 60L
        val s = telemetry.currentSessionElapsedSec % 60L
        "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }

    val meetingElapsedFormatted = remember(meetingState.elapsedSec) {
        MeetingRecorderService.formatDuration(meetingState.elapsedSec)
    }

    val statusDotColor = when {
        meetingState.isFinalizing -> MaterialTheme.colorScheme.primary
        meetingState.isRecording && meetingState.isPaused -> Color(0xFFF59E0B)
        meetingState.isRecording -> Color(0xFFEF4444)
        else -> when (telemetry.status) {
            LiveCaptureStatus.MIC_SILENCED_WARNING -> MaterialTheme.colorScheme.error
            LiveCaptureStatus.RECORDING_ROUND_MANUAL,
            LiveCaptureStatus.RECORDING_ROUND_AUTO -> MaterialTheme.colorScheme.primary
            LiveCaptureStatus.RECORDING_AMBIENT -> MaterialTheme.colorScheme.secondary
            LiveCaptureStatus.RECORDING_IN_CALL -> Color(0xFFF59E0B) // Amber
            LiveCaptureStatus.VAD_STANDBY -> Color(0xFF10B981)
            LiveCaptureStatus.PAUSED -> Color(0xFF64748B)
            LiveCaptureStatus.STOPPED -> Color(0xFFEF4444)
        }
    }

    val statusTitle = when {
        meetingState.isFinalizing -> "Saving ${meetingState.effectiveShortAppName()}..."
        meetingState.isRecording && meetingState.isPaused -> "${meetingState.effectiveShortAppName()} Paused"
        meetingState.isRecording -> "${meetingState.effectiveShortAppName()} REC"
        else -> when (telemetry.status) {
            LiveCaptureStatus.VAD_STANDBY -> "VAD Standby"
            LiveCaptureStatus.RECORDING_AMBIENT -> "Recording Speech"
            LiveCaptureStatus.RECORDING_ROUND_MANUAL -> "Ward Round (Manual)"
            LiveCaptureStatus.RECORDING_ROUND_AUTO -> "Ward Round (Auto)"
            LiveCaptureStatus.RECORDING_IN_CALL -> "Phone Call Recording"
            LiveCaptureStatus.MIC_SILENCED_WARNING -> telemetry.externalMicOwnerLabel?.let { "Mic: $it" } ?: "Mic Silenced (A11y)"
            LiveCaptureStatus.PAUSED -> telemetry.pauseReason?.takeIf { it.isNotBlank() } ?: "Paused"
            LiveCaptureStatus.STOPPED -> "Listening Stopped"
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            // Left: Live Status Pill (opens Meeting Recorder if recording a meeting, else ADB Diagnostics)
            Surface(
                onClick = if (isMeetingActive) onOpenMeetingRecorder else onOpenAdbDiagnostics,
                shape = RoundedCornerShape(20.dp),
                color = if (isMeetingActive) {
                    Color(0xFFEF4444).copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                },
                border = BorderStroke(
                    1.dp,
                    if (isMeetingActive) {
                        Color(0xFFEF4444).copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    }
                ),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    // Pulsating LED Beacon
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .alpha(
                                if ((meetingState.isRecording && !meetingState.isPaused) ||
                                    (!isMeetingActive && (isRecordingActive || telemetry.status == LiveCaptureStatus.VAD_STANDBY))
                                ) {
                                    pulseAlpha
                                } else {
                                    1f
                                }
                            )
                            .background(statusDotColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusTitle,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMeetingActive) Color(0xFFFFE4E6) else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (meetingState.isRecording) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = meetingElapsedFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFB7185)
                        )
                    } else if (!isMeetingActive && isRecordingActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = elapsedFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Right: Compact Action Buttons + Settings Shortcut + Top-Right Options Menu
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (meetingState.isRecording) {
                    Button(
                        onClick = onStopMeetingRecording,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFDC2626),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Meeting Recording",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Save",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (!isListening) {
                    Button(
                        onClick = onToggleListening,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start Listening",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Listen",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // Active Listening Controls
                    // 1. Stop button
                    OutlinedButton(
                        onClick = onToggleListening,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Listening",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Stop",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 2. Contextual round/split action button
                    if (isRoundActive) {
                        Button(
                            onClick = onEndRoundOrSession,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                text = "■ End Round",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (telemetry.status == LiveCaptureStatus.RECORDING_AMBIENT) {
                        Button(
                            onClick = onEndRoundOrSession,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                text = "Split",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = onStartManualRound,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                text = "+ Round",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Settings Shortcut
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    modifier = Modifier.size(30.dp)
                ) {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Top-Right Options Menu (Overflow — includes Zoom / Webex Meeting Recorder)
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isMeetingActive) {
                            Color(0xFFEF4444).copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isMeetingActive) {
                                Color(0xFFEF4444).copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            }
                        ),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options Menu",
                                    tint = if (isMeetingActive) Color(0xFFFB7185) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            if (isMeetingActive) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(6.dp)
                                        .alpha(if (meetingState.isPaused) 1f else pulseAlpha)
                                        .background(
                                            if (meetingState.isPaused) Color(0xFFF59E0B) else Color(0xFFEF4444),
                                            CircleShape
                                        )
                                )
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = when {
                                            meetingState.isRecording && meetingState.isPaused ->
                                                "⏸️ ${meetingState.effectiveAppDisplayName()} Recorder ($meetingElapsedFormatted)"
                                            meetingState.isRecording ->
                                                "🔴 ${meetingState.effectiveAppDisplayName()} Recording ($meetingElapsedFormatted)"
                                            else ->
                                                "🎥 Zoom / Webex / App Recorder"
                                        },
                                        fontWeight = FontWeight.Bold,
                                        color = if (meetingState.isRecording) {
                                            Color(0xFFFB7185)
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    )
                                    Text(
                                        text = if (meetingState.isRecording) {
                                            if (meetingState.captureMode.usesAdbStealthAudio) {
                                                "Stealth Virtual Screen + Silent Speaker + Virtual Mic active"
                                            } else if (meetingState.videoActive) {
                                                "Tap for live video/virtual screen, VU meters & stop"
                                            } else {
                                                "Tap to open live VU meters, speakerphone & stop"
                                            }
                                        } else {
                                            "Stealth Virtual Screen + Isolated Audio + Download MP4"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onOpenMeetingRecorder()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = null,
                                    tint = if (meetingState.isRecording) Color(0xFFFB7185) else MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (isListening) {
                            val isUserPaused = CaptureEngineController.isUserManualPaused() ||
                                (telemetry.status == LiveCaptureStatus.PAUSED && telemetry.pauseReason == CaptureEngine.MANUAL_PAUSE_REASON)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isUserPaused) "🎙️ Resume Mic Capture" else "⏸️ Pause Mic (Free Hardware)",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    val newPaused = !isUserPaused
                                    CaptureEngineController.setUserManualPaused(newPaused)
                                    Toast.makeText(
                                        context,
                                        if (newPaused) "Microphone paused — hardware released for other apps" else "Resuming microphone capture...",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isUserPaused) Icons.Default.Mic else Icons.Default.MicOff,
                                        contentDescription = null,
                                        tint = if (isUserPaused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                    )
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isListening) "🛑 Stop Listening Service" else "▶️ Start Listening Service",
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleListening()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isListening) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = if (isGeminiPaused) "▶️ Resume Gemini AI Calls" else "⏸️ Pause Gemini AI Calls",
                                        fontWeight = FontWeight.Bold,
                                        color = if (isGeminiPaused) Color(0xFF10B981) else Color(0xFFF59E0B)
                                    )
                                    Text(
                                        text = if (isGeminiPaused) {
                                            "Resume cloud analysis & dispatch queued sessions"
                                        } else {
                                            "Pause API calls temporarily; keep recording locally"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleGeminiPause()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isGeminiPaused) Icons.Default.PlayCircle else Icons.Default.PauseCircle,
                                    contentDescription = if (isGeminiPaused) "Resume Gemini AI" else "Pause Gemini AI",
                                    tint = if (isGeminiPaused) Color(0xFF10B981) else Color(0xFFF59E0B)
                                )
                            },
                            modifier = Modifier.testTag("menu_toggle_gemini_pause")
                        )
                        DropdownMenuItem(
                            text = { Text("🚪 Stop Listening & Exit App", fontWeight = FontWeight.SemiBold) },
                            onClick = {
                                menuExpanded = false
                                onStopListeningAndExit()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                        if (failedSessionsCount > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "🔄 Try Again for Failed Sessions ($failedSessionsCount)",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onRetryAllFailed()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(
                            text = { Text("🛡️ ADB, Shizuku & Audio Telemetry", fontWeight = FontWeight.SemiBold) },
                            onClick = {
                                menuExpanded = false
                                onOpenAdbDiagnostics()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("⚡ Force Split & Analyze Now") },
                            onClick = {
                                menuExpanded = false
                                onForceSplitSession()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.FlashOn, contentDescription = null, tint = Color(0xFFF59E0B))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📋 Copy Ward Handover (Markdown)") },
                            onClick = {
                                menuExpanded = false
                                onCopyWardHandover()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🎵 Audio Vault & Reviewer", fontWeight = FontWeight.SemiBold) },
                            onClick = {
                                menuExpanded = false
                                onOpenAudioVault()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🧹 Run Storage Cleaner Now") },
                            onClick = {
                                menuExpanded = false
                                onRunStorageCleaner()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.CleaningServices, contentDescription = null)
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(
                            text = { Text("📦 Export & Backup Data...", fontWeight = FontWeight.SemiBold) },
                            onClick = {
                                menuExpanded = false
                                onOpenDataExport()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Upload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("💾 Quick Settings Backup (.json)") },
                            onClick = {
                                menuExpanded = false
                                onOpenSettingsBackup()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Save, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📥 Restore / Import Backup...") },
                            onClick = {
                                menuExpanded = false
                                onOpenRestoreBackup()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(
                            text = { Text("⚙️ App & Gemini Settings") },
                            onClick = {
                                menuExpanded = false
                                onOpenSettings()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, contentDescription = null)
                            }
                        )
                    }
                }
            }
        }
    }
}
