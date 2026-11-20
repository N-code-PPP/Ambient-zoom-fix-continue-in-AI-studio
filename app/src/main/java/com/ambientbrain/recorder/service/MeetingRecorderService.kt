package com.ambientbrain.recorder.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ambientbrain.recorder.ai.SessionAnalyzeWorker
import com.ambientbrain.recorder.data.AppDatabase
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SegmentMapEntity
import com.ambientbrain.recorder.data.SessionEntity
import com.ambientbrain.recorder.data.SessionMode
import com.ambientbrain.recorder.data.Status
import com.ambientbrain.recorder.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

enum class MeetingPlatform(
    val label: String,
    val shortLabel: String,
    val packageCandidates: List<String>
) {
    ZOOM(
        label = "Zoom",
        shortLabel = "Zoom",
        packageCandidates = listOf("us.zoom.videomeetings", "us.zoom.zrc")
    ),
    WEBEX(
        label = "Webex",
        shortLabel = "Webex",
        packageCandidates = listOf("com.cisco.wx2.android", "com.cisco.webex.meetings")
    ),
    OTHER_APP(
        label = "Other App",
        shortLabel = "App",
        packageCandidates = emptyList()
    ),
    AUTO(
        label = "Online Meeting",
        shortLabel = "Meeting",
        packageCandidates = listOf(
            "us.zoom.videomeetings",
            "com.cisco.wx2.android",
            "com.cisco.webex.meetings",
            "us.zoom.zrc"
        )
    )
}

enum class MeetingAudioCaptureMode(
    val title: String,
    val shortLabel: String,
    val subtitle: String,
    val needsMediaProjection: Boolean,
    val needsMic: Boolean,
    val usesAdbStealthAudio: Boolean = false
) {
    STEALTH_ADB_ISOLATED(
        title = "Stealth Isolated Audio + Virtual Mic (ADB)",
        shortLabel = "Stealth ADB",
        subtitle = "Zero screen-capture prompt. Redirects ONLY the selected app's audio silently off the physical speaker (Per-UID AudioPolicy) and feeds it a silent virtual mic so physical screen, speaker, and mic (Ambient Brain) stay completely untouched!",
        needsMediaProjection = false,
        needsMic = false,
        usesAdbStealthAudio = true
    ),
    INTERNAL_AND_MIC(
        title = "Internal App Audio + Mic",
        shortLabel = "Internal + Mic",
        subtitle = "Captures app playback audio + your mic into the saved recording/video. Gemini ignores the internal app audio stream when exclusion is enabled.",
        needsMediaProjection = true,
        needsMic = true
    ),
    INTERNAL_ONLY(
        title = "Internal App Audio Only",
        shortLabel = "Internal Only",
        subtitle = "Captures only app playback via MediaProjection without using your microphone (excluded from Gemini when exclusion is enabled).",
        needsMediaProjection = true,
        needsMic = false
    ),
    MIC_SPEAKERPHONE(
        title = "Mic + Speakerphone Mode",
        shortLabel = "Mic + Speaker",
        subtitle = "No screen-capture prompt for audio. Routes remote VoIP voices to speakerphone for reliable capture on any Android ROM.",
        needsMediaProjection = false,
        needsMic = true
    )
}

data class MeetingRecorderState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val isFinalizing: Boolean = false,
    val sessionId: String? = null,
    val platform: MeetingPlatform = MeetingPlatform.ZOOM,
    val targetPackageName: String = "",
    val targetAppLabel: String = "",
    val captureMode: MeetingAudioCaptureMode = MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED,
    val videoMode: MeetingVideoMode = MeetingVideoMode.ADB_VIRTUAL_SCREEN,
    val meetingTitle: String = "",
    val isClinicalMeeting: Boolean = false,
    val autoAnalyzeOnStop: Boolean = true,
    val ignoreInternalAppAudioForGemini: Boolean = true,
    val enableVirtualMic: Boolean = true,
    val silentSpeakerIsolation: Boolean = true,
    val keepAmbientBrainListening: Boolean = true,
    val elapsedSec: Long = 0L,
    val recordedBytes: Long = 0L,
    val internalLevelNorm: Float = 0f,
    val micLevelNorm: Float = 0f,
    val internalAudioDetected: Boolean = false,
    val internalSilenceHint: Boolean = false,
    val micSilencedByOs: Boolean = false,
    val speakerphoneBoostEnabled: Boolean = false,
    val activeMicSourceLabel: String = "",
    val internalSampleRateHz: Int = 0,
    val videoActive: Boolean = false,
    val virtualDisplayId: Int? = null,
    val virtualDisplayWidth: Int = 720,
    val virtualDisplayHeight: Int = 1280,
    val isMiniPreviewVisible: Boolean = false,
    val videoStatusNote: String = "",
    val audioCaptureTier: String = "",
    val virtualMicTier: String = "",
    val stealthAudioSessionId: Int = 0,
    val ambientMicKeptActive: Boolean = false,
    val lastError: String? = null,
    val lastSavedSessionId: String? = null,
    val lastSavedTitle: String? = null,
    val lastSavedAutoAnalyzed: Boolean = false,
    val lastSavedGeminiExcludedInternal: Boolean = false,
    val lastSavedHasRawAudio: Boolean = false,
    val lastSavedVideoPath: String? = null,
    val lastSavedGalleryUri: String? = null,
    val lastSavedPublicVideoPath: String? = null
) {
    fun effectiveAppDisplayName(): String {
        return targetAppLabel.takeIf { it.isNotBlank() } ?: platform.label
    }

    fun effectiveShortAppName(): String {
        return targetAppLabel.takeIf { it.isNotBlank() }?.take(14) ?: platform.shortLabel
    }
}

object MeetingRecorderStateBus {
    private val _state = MutableStateFlow(MeetingRecorderState())
    val state: StateFlow<MeetingRecorderState> = _state.asStateFlow()

    fun update(transform: (MeetingRecorderState) -> MeetingRecorderState) {
        _state.update(transform)
    }

    fun isStealthParallelAmbientActive(): Boolean {
        val st = _state.value
        return st.isRecording && st.captureMode.usesAdbStealthAudio && st.keepAmbientBrainListening
    }

    fun isIsolatedStealthTargetPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val st = _state.value
        if (!st.isRecording || !st.captureMode.usesAdbStealthAudio) return false
        return st.targetPackageName.isNotBlank() && st.targetPackageName.equals(pkg, ignoreCase = true)
    }

    fun activeStealthAudioSessionId(): Int {
        val st = _state.value
        return if (st.isRecording && st.captureMode.usesAdbStealthAudio) st.stealthAudioSessionId else 0
    }

    fun clearLastError() {
        _state.update { it.copy(lastError = null) }
    }

    fun clearLastSavedBanner() {
        _state.update {
            it.copy(
                lastSavedSessionId = null,
                lastSavedTitle = null,
                lastSavedAutoAnalyzed = false,
                lastSavedGeminiExcludedInternal = false,
                lastSavedHasRawAudio = false,
                lastSavedVideoPath = null,
                lastSavedGalleryUri = null,
                lastSavedPublicVideoPath = null
            )
        }
    }
}

/**
 * Dedicated Foreground Service for recording Zoom, Webex, or any selected app's audio and
 * background video — including full 3-way **Stealth ADB Isolation Mode** (headless Virtual Display
 * + Per-UID silent speaker loopback + Per-UID Virtual Silent Microphone, leaving physical screen,
 * speaker, and Ambient Brain's physical microphone untouched) and File-Explorer-accessible MP4 export
 * (default `Download/AmbientBrain`, adjustable in Settings).
 */
class MeetingRecorderService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isFinalizing = AtomicBoolean(false)
    private val holdsExternalMicPause = AtomicBoolean(false)
    private val internalAudioEverDetected = AtomicBoolean(false)
    private val speakerphoneBoostUsedEver = AtomicBoolean(false)
    private val micSilencedFlag = AtomicBoolean(false)

    private val lastInternalRmsScaled = AtomicLong(0L)
    private val lastMicRmsScaled = AtomicLong(0L)

    @Volatile
    private var stealthVuNorm: Float = 0f

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var projectionCallback: MediaProjection.Callback? = null

    @Volatile
    private var internalAudioRecord: AudioRecord? = null

    @Volatile
    private var micAudioRecord: AudioRecord? = null

    @Volatile
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var adtsWriter: AdtsFileWriter? = null

    @Volatile
    private var micOnlyWriter: AdtsFileWriter? = null

    @Volatile
    private var mediaProjectionVideoRecorder: MediaProjectionVideoRecorder? = null

    @Volatile
    private var adbVirtualScreenSession: AdbVirtualScreenSession? = null

    @Volatile
    private var miniPreviewOverlay: StealthMiniPreviewOverlay? = null

    @Volatile
    private var appOpsMicLockedPackage: String? = null

    private var stealthTickerJob: Job? = null
    private var internalReaderThread: Thread? = null
    private var micReaderThread: Thread? = null
    private var mixerThread: Thread? = null

    private val internalQueue = ArrayBlockingQueue<ShortArray>(48)
    private val micQueue = ArrayBlockingQueue<ShortArray>(48)

    // Session parameters
    private var activeSessionId: String = ""
    private var activePlatform: MeetingPlatform = MeetingPlatform.ZOOM
    private var activeTargetPackageName: String? = null
    private var activeTargetAppLabel: String = ""
    private var activeCaptureMode: MeetingAudioCaptureMode = MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED
    private var activeVideoMode: MeetingVideoMode = MeetingVideoMode.ADB_VIRTUAL_SCREEN
    private var activeMeetingTitle: String = ""
    private var activeIsClinical: Boolean = false
    private var activeAutoAnalyze: Boolean = true
    private var activeIgnoreInternalForGemini: Boolean = true
    private var activeEnableVirtualMic: Boolean = true
    private var activeSilentSpeaker: Boolean = true
    private var activeKeepAmbientListening: Boolean = true
    private var activeShowMiniPreviewOnStart: Boolean = false

    private var activeOutputFile: File? = null
    private var activeMicOnlyFile: File? = null
    private var activeTempVideoFile: File? = null
    private var activeTempStealthAudioFile: File? = null
    private var activeAudioCaptureTier: String = ""
    private var activeVirtualMicTier: String = ""

    private var sessionStartWallMs: Long = 0L
    private var stealthActiveElapsedMs: Long = 0L
    private var totalWrittenSamples: Long = 0L
    private var micSpeechFrames: Long = 0L
    private var activeElapsedFrames: Long = 0L
    private var speakerphoneAppliedByUs: Boolean = false

    private val segmentMaps = mutableListOf<SegmentMapEntity>()
    private var currentSegmentStartOffsetMs: Long = -1L
    private var currentSegmentWallMs: Long = 0L
    private var currentSegmentSamples: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStartCommand(intent)
            ACTION_STOP -> requestStopAndFinalize("User stopped recording")
            ACTION_TOGGLE_PAUSE -> handleTogglePause()
            ACTION_TOGGLE_SPEAKERPHONE -> handleToggleSpeakerphone()
            ACTION_MOVE_APP_TO_VIRTUAL -> handleMoveAppDisplay(toVirtual = true)
            ACTION_BRING_APP_TO_MAIN -> handleMoveAppDisplay(toVirtual = false)
            ACTION_TOGGLE_MINI_PREVIEW -> handleToggleMiniPreview()
            else -> {
                if (!isRunning.get() && !isFinalizing.get()) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStartCommand(intent: Intent) {
        if (isRunning.get() || isFinalizing.get()) {
            Log.w(TAG, "Ignoring ACTION_START while meeting recording is already active/finalizing")
            return
        }

        val platform = runCatching {
            MeetingPlatform.valueOf(intent.getStringExtra(EXTRA_PLATFORM) ?: MeetingPlatform.ZOOM.name)
        }.getOrDefault(MeetingPlatform.ZOOM)

        val customPkgExtra = intent.getStringExtra(EXTRA_TARGET_PACKAGE)?.trim()?.takeIf { it.isNotBlank() }
        val customLabelExtra = intent.getStringExtra(EXTRA_TARGET_APP_LABEL)?.trim().orEmpty()

        val resolvedTargetPkg = customPkgExtra ?: findInstalledPackage(this, platform)
        val resolvedTargetLabel = when {
            customLabelExtra.isNotBlank() -> customLabelExtra
            platform == MeetingPlatform.OTHER_APP && !resolvedTargetPkg.isNullOrBlank() ->
                InstalledAppQueryHelper.resolveAppLabel(this, resolvedTargetPkg).ifBlank { resolvedTargetPkg }
            else -> platform.label
        }

        val captureMode = runCatching {
            MeetingAudioCaptureMode.valueOf(
                intent.getStringExtra(EXTRA_CAPTURE_MODE) ?: MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED.name
            )
        }.getOrDefault(MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED)

        val requestedVideoMode = runCatching {
            MeetingVideoMode.valueOf(
                intent.getStringExtra(EXTRA_VIDEO_MODE) ?: MeetingVideoMode.ADB_VIRTUAL_SCREEN.name
            )
        }.getOrDefault(MeetingVideoMode.ADB_VIRTUAL_SCREEN)

        // Stealth ADB audio always runs inside our ADB Virtual Display Shizuku runner
        val videoMode = if (captureMode.usesAdbStealthAudio && requestedVideoMode == MeetingVideoMode.AUDIO_ONLY) {
            MeetingVideoMode.ADB_VIRTUAL_SCREEN
        } else {
            requestedVideoMode
        }

        val customTitle = intent.getStringExtra(EXTRA_MEETING_TITLE)?.trim().orEmpty()
        val isClinical = intent.getBooleanExtra(EXTRA_IS_CLINICAL, false)
        val autoAnalyze = intent.getBooleanExtra(EXTRA_AUTO_ANALYZE, true)
        val ignoreInternalForGemini = intent.getBooleanExtra(EXTRA_IGNORE_INTERNAL_FOR_GEMINI, true)
        val enableVirtualMic = intent.getBooleanExtra(EXTRA_ENABLE_VIRTUAL_MIC, true)
        val silentSpeaker = intent.getBooleanExtra(EXTRA_SILENT_SPEAKER, true)
        val keepAmbientListening = intent.getBooleanExtra(EXTRA_KEEP_AMBIENT_LISTENING, true)
        val showMiniPreviewOnStart = intent.getBooleanExtra(EXTRA_SHOW_MINI_PREVIEW, false)
        val initialSpeakerphone = intent.getBooleanExtra(
            EXTRA_SPEAKERPHONE_BOOST,
            captureMode == MeetingAudioCaptureMode.MIC_SPEAKERPHONE
        )
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)

        val needsMediaProjection = captureMode.needsMediaProjection || videoMode.requiresMediaProjection

        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (captureMode.needsMic && !hasMicPerm) {
            emitStartError("Microphone permission is required for ${captureMode.title}.")
            stopSelf()
            return
        }

        if (needsMediaProjection && (resultCode != Activity.RESULT_OK || resultData == null)) {
            emitStartError("Screen / internal audio capture permission was cancelled.")
            stopSelf()
            return
        }

        if ((captureMode.usesAdbStealthAudio || videoMode == MeetingVideoMode.ADB_VIRTUAL_SCREEN) &&
            ShizukuAdbManager.getShizukuStatus(this) != ShizukuStatus.AUTHORIZED
        ) {
            emitStartError("Shizuku / ADB permission is required for Stealth Virtual Screen mode.")
            stopSelf()
            return
        }

        ensureNotificationChannel()

        val fgsType = when {
            needsMediaProjection && captureMode.needsMic ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            needsMediaProjection ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            captureMode.needsMic ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else ->
                // Stealth ADB mode touches neither physical mic nor MediaProjection -> DATA_SYNC FGS!
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

        activeSessionId = "meeting_${UUID.randomUUID().toString().take(8)}"
        activePlatform = platform
        activeTargetPackageName = resolvedTargetPkg
        activeTargetAppLabel = resolvedTargetLabel
        activeCaptureMode = captureMode
        activeVideoMode = videoMode
        activeMeetingTitle = customTitle
        activeIsClinical = isClinical
        activeAutoAnalyze = autoAnalyze
        activeIgnoreInternalForGemini = ignoreInternalForGemini
        activeEnableVirtualMic = enableVirtualMic
        activeSilentSpeaker = silentSpeaker
        activeKeepAmbientListening = keepAmbientListening
        activeShowMiniPreviewOnStart = showMiniPreviewOnStart
        activeOutputFile = null
        activeMicOnlyFile = null
        activeTempVideoFile = null
        activeTempStealthAudioFile = null
        activeAudioCaptureTier = ""
        activeVirtualMicTier = ""
        appOpsMicLockedPackage = null
        sessionStartWallMs = System.currentTimeMillis()
        stealthActiveElapsedMs = 0L
        stealthVuNorm = 0f
        totalWrittenSamples = 0L
        micSpeechFrames = 0L
        activeElapsedFrames = 0L
        internalAudioEverDetected.set(false)
        speakerphoneBoostUsedEver.set(false)
        micSilencedFlag.set(false)
        lastInternalRmsScaled.set(0L)
        lastMicRmsScaled.set(0L)
        isPaused.set(false)
        isFinalizing.set(false)
        segmentMaps.clear()
        currentSegmentStartOffsetMs = -1L
        currentSegmentSamples = 0L
        internalQueue.clear()
        micQueue.clear()

        val initialNotif = buildNotification(
            appLabel = resolvedTargetLabel,
            captureMode = captureMode,
            videoMode = videoMode,
            elapsedSec = 0L,
            paused = false
        )
        val fgsStarted = runCatching {
            startForeground(NOTIFICATION_ID, initialNotif, fgsType)
            true
        }.onFailure { err ->
            Log.e(TAG, "Failed to enter foreground service mode ($fgsType)", err)
            emitStartError("Could not start Meeting Recorder service: ${err.message ?: "Foreground permission denied"}")
            stopSelf()
        }.getOrDefault(false)

        if (!fgsStarted) return

        // Redeem MediaProjection token immediately while in active FGS state
        if (needsMediaProjection && resultData != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = runCatching {
                mpManager.getMediaProjection(resultCode, resultData)
            }.onFailure { err ->
                Log.e(TAG, "getMediaProjection failed", err)
            }.getOrNull()

            if (projection == null) {
                emitStartError("Android rejected the capture session token. Please tap Start Recording again.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system/user — finalizing meeting recording")
                    requestStopAndFinalize("System ended projection")
                }
            }
            projection.registerCallback(cb, Handler(Looper.getMainLooper()))
            mediaProjection = projection
            projectionCallback = cb
        }

        isRunning.set(true)
        MeetingRecorderStateBus.update {
            MeetingRecorderState(
                isRecording = true,
                isPaused = false,
                isFinalizing = false,
                sessionId = activeSessionId,
                platform = platform,
                targetPackageName = resolvedTargetPkg.orEmpty(),
                targetAppLabel = resolvedTargetLabel,
                captureMode = captureMode,
                videoMode = videoMode,
                meetingTitle = customTitle,
                isClinicalMeeting = isClinical,
                autoAnalyzeOnStop = autoAnalyze,
                ignoreInternalAppAudioForGemini = ignoreInternalForGemini,
                enableVirtualMic = enableVirtualMic,
                silentSpeakerIsolation = silentSpeaker,
                keepAmbientBrainListening = keepAmbientListening,
                speakerphoneBoostEnabled = false,
                videoActive = false,
                virtualDisplayId = null,
                ambientMicKeptActive = captureMode.usesAdbStealthAudio && keepAmbientListening,
                videoStatusNote = when {
                    captureMode.usesAdbStealthAudio -> "Launching Stealth Virtual Screen + Isolated Audio + Virtual Mic..."
                    videoMode == MeetingVideoMode.ADB_VIRTUAL_SCREEN -> "Creating ADB Virtual Screen..."
                    videoMode == MeetingVideoMode.MEDIA_PROJECTION_VIDEO -> "Starting 720p MediaProjection video..."
                    else -> ""
                },
                lastError = null
            )
        }

        serviceScope.launch {
            if (captureMode.usesAdbStealthAudio) {
                startStealthAdbIsolationPipeline()
            } else {
                startStandardCapturePipeline(initialSpeakerphone)
            }
        }
    }

    /**
     * Full 3-Way Hardware Isolation Pipeline running inside Shizuku `uid = 2000`:
     * - Off-screen 720p Virtual Display (physical screen Display 0 untouched)
     * - Per-UID AudioPolicy Silent Loopback (physical speaker silent for target app)
     * - Per-UID Virtual Silent Microphone + AppOps fallback (physical mic untouched; Ambient Brain keeps listening!)
     */
    private fun startStealthAdbIsolationPipeline() {
        try {
            acquireWakeLock()

            // Recover any stale AppOps lock left behind by a previous crash before starting a new session
            AdbVirtualScreenController.restorePendingAppOpsMicLockIfAny(applicationContext)

            // Only pause Ambient Brain if user explicitly disabled concurrent ambient listening
            if (!activeKeepAmbientListening) {
                CaptureEngineController.acquireExternalMicPause(
                    reason = "$activeTargetAppLabel Stealth Recorder",
                    timeoutMs = 1_500L
                )
                holdsExternalMicPause.set(true)
            }

            // Shell uid=2000 cannot write to private cacheDir; require externalCacheDir
            val sharedTempDir = externalCacheDir
            if (sharedTempDir == null) {
                emitStartError("Shared external cache storage is unavailable for Stealth ADB recording.")
                requestStopAndFinalize("External cache unavailable", discardAudio = true)
                return
            }

            // If AUTO mode was selected, detect the actually-running meeting app (e.g. Webex vs Zoom)
            if (activePlatform == MeetingPlatform.AUTO) {
                val runningPkg = AdbVirtualScreenController.findRunningCandidatePackage(MeetingPlatform.AUTO.packageCandidates)
                if (!runningPkg.isNullOrBlank()) {
                    activeTargetPackageName = runningPkg
                    activeTargetAppLabel = InstalledAppQueryHelper.resolveAppLabel(applicationContext, runningPkg)
                        .ifBlank { activeTargetAppLabel }
                }
            }

            val targetPkg = activeTargetPackageName
            val targetUid = InstalledAppQueryHelper.resolveAppUid(applicationContext, targetPkg)
            if (targetPkg.isNullOrBlank() || targetUid <= 0) {
                emitStartError("Select an installed app (Zoom, Webex, or Pick Any App) before starting Per-UID Stealth Isolation.")
                requestStopAndFinalize("Target app not resolved for Stealth mode", discardAudio = true)
                return
            }

            val (w, h, dpi) = MediaProjectionVideoRecorder.computeRecordingDimensions(applicationContext, 720)
            val tempVideo = File(sharedTempDir, "vdisp_${activeSessionId}.mp4")
            val tempAudio = File(sharedTempDir, "vdisp_audio_${activeSessionId}.aac")
            activeTempVideoFile = tempVideo
            activeTempStealthAudioFile = tempAudio

            val result = AdbVirtualScreenController.startVirtualScreen(
                context = applicationContext,
                tempVideoFile = tempVideo,
                tempAudioFile = tempAudio,
                targetUid = targetUid,
                enableVirtualMic = activeEnableVirtualMic && targetUid > 0,
                silentSpeaker = activeSilentSpeaker,
                width = w,
                height = h,
                dpi = dpi,
                bitrateBps = 2_000_000,
                fps = 15,
                onAudioLevel = { norm, detected ->
                    stealthVuNorm = norm
                    if (detected) internalAudioEverDetected.set(true)
                },
                onPreviewFrame = {
                    miniPreviewOverlay?.onPreviewFrameAvailable()
                }
            )

            val vSession = result.session
            if (vSession == null) {
                val err = result.error ?: "Failed to initialize Stealth ADB Virtual Screen"
                emitStartError("Stealth ADB Mode failed: $err")
                requestStopAndFinalize("Stealth ADB init failure", discardAudio = true)
                return
            }

            adbVirtualScreenSession = vSession
            activeAudioCaptureTier = vSession.audioCaptureTier
            activeVirtualMicTier = vSession.virtualMicTier

            // Publish targetPkg & audioSessionId immediately so CallRouteGuard filters Stealth's REMOTE_SUBMIX
            MeetingRecorderStateBus.update {
                it.copy(
                    targetPackageName = targetPkg,
                    targetAppLabel = activeTargetAppLabel,
                    stealthAudioSessionId = vSession.audioSessionId
                )
            }

            // Automatic Tier 2 Mic Fallback: if OEM rejected MIX_TYPE_RECORDERS virtual mic, lock via AppOps
            // Persist package synchronously BEFORE setting AppOps ignore so crash/reboot always restores it!
            if (activeEnableVirtualMic &&
                vSession.virtualMicTier.startsWith("FALLBACK_APPOPS")
            ) {
                val prefs = PreferencesRepo(applicationContext)
                prefs.pendingAppOpsMicRestorePackage = targetPkg
                val locked = AdbVirtualScreenController.setAppRecordAudioAppOp(
                    context = applicationContext,
                    packageName = targetPkg,
                    allow = false
                )
                if (locked) {
                    appOpsMicLockedPackage = targetPkg
                    activeVirtualMicTier = "APPOPS_MIC_LOCKED"
                } else {
                    prefs.pendingAppOpsMicRestorePackage = ""
                }
            }

            // Move the target app onto the off-screen headless Virtual Display
            val moved = AdbVirtualScreenController.moveAppToDisplay(
                context = applicationContext,
                packageName = targetPkg,
                displayId = vSession.displayId,
                launchIfNotRunning = true
            )
            val movedNote = if (moved) {
                " • $activeTargetAppLabel moved off-screen"
            } else {
                " • Tap 'Send to Virtual' to move $activeTargetAppLabel"
            }

            val warningMessages = mutableListOf<String>()
            if (activeAudioCaptureTier.startsWith("FAILED:")) {
                warningMessages.add(
                    "Isolated audio tier reported: ${activeAudioCaptureTier.removePrefix("FAILED:")}. Video recording continues."
                )
            }
            if (activeKeepAmbientListening && !AmbientCaptureAccessibilityService.isSystemBound) {
                warningMessages.add(
                    "Enable Ambient Brain Accessibility Service so Android does not silence parallel room listening during VoIP calls."
                )
            }
            val combinedWarning = warningMessages.joinToString(" ").takeIf { it.isNotBlank() }

            MeetingRecorderStateBus.update {
                it.copy(
                    videoActive = true,
                    virtualDisplayId = vSession.displayId,
                    virtualDisplayWidth = w,
                    virtualDisplayHeight = h,
                    videoStatusNote = "Virtual Display #${vSession.displayId} (${w}×${h})$movedNote",
                    audioCaptureTier = activeAudioCaptureTier,
                    virtualMicTier = activeVirtualMicTier,
                    stealthAudioSessionId = vSession.audioSessionId,
                    ambientMicKeptActive = activeKeepAmbientListening && !holdsExternalMicPause.get(),
                    lastError = combinedWarning
                )
            }

            if (activeShowMiniPreviewOnStart) {
                openMiniPreviewOverlay(vSession)
            }

            // Live timer & VU UI ticker for Stealth mode
            stealthTickerJob = serviceScope.launch {
                var lastTickMono = SystemClock.elapsedRealtime()
                var notifTicks = 0
                while (isActive && isRunning.get()) {
                    delay(200L)
                    val nowMono = SystemClock.elapsedRealtime()
                    val pausedNow = isPaused.get()
                    if (!pausedNow) {
                        stealthActiveElapsedMs += (nowMono - lastTickMono).coerceAtLeast(0L)
                    }
                    lastTickMono = nowMono

                    val elapsedSec = stealthActiveElapsedMs / 1000L
                    val totalBytes = (activeTempVideoFile?.length() ?: 0L) + (activeTempStealthAudioFile?.length() ?: 0L)
                    val detected = internalAudioEverDetected.get()

                    miniPreviewOverlay?.updateElapsedText(formatDuration(elapsedSec))

                    MeetingRecorderStateBus.update { st ->
                        if (!st.isRecording) st else st.copy(
                            isPaused = pausedNow,
                            elapsedSec = elapsedSec,
                            recordedBytes = totalBytes,
                            internalLevelNorm = if (pausedNow) 0f else stealthVuNorm,
                            micLevelNorm = 0f,
                            internalAudioDetected = detected,
                            internalSilenceHint = false
                        )
                    }

                    if (++notifTicks >= 5) {
                        notifTicks = 0
                        updateOngoingNotification()
                    }
                }
            }

            Log.i(
                TAG,
                "Stealth ADB Isolation started: id=$activeSessionId, app=$activeTargetAppLabel ($targetPkg uid=$targetUid), display=#${vSession.displayId}, audio=$activeAudioCaptureTier, virtMic=$activeVirtualMicTier, ambientFree=$activeKeepAmbientListening"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Stealth ADB pipeline startup failed", t)
            emitStartError("Stealth ADB mode failed: ${t.message ?: t.javaClass.simpleName}")
            requestStopAndFinalize("Stealth ADB error", discardAudio = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startStandardCapturePipeline(initialSpeakerphone: Boolean) {
        try {
            acquireWakeLock()

            CaptureEngineController.acquireExternalMicPause(
                reason = "$activeTargetAppLabel Meeting Recorder",
                timeoutMs = 1_500L
            )
            holdsExternalMicPause.set(true)

            if (initialSpeakerphone) {
                applySpeakerphoneBoost(true)
            }

            var internalRate = 0
            if (activeCaptureMode.needsMediaProjection) {
                val projection = mediaProjection
                    ?: throw IllegalStateException("MediaProjection not available for internal audio")
                val (rec, rateHz) = createInternalPlaybackAudioRecord(projection)
                internalAudioRecord = rec
                internalRate = rateHz
                rec.startRecording()
            }

            var micSourceName = ""
            if (activeCaptureMode.needsMic) {
                val (rec, label) = createMicrophoneAudioRecord()
                micAudioRecord = rec
                micSourceName = label
                rec.startRecording()
                registerMicSilencedMonitor(rec.audioSessionId)
            }

            val sessionsDir = File(filesDir, "sessions").apply { mkdirs() }
            val outFile = File(sessionsDir, "${activeSessionId}.aac")
            activeOutputFile = outFile

            val writer = AdtsFileWriter(
                outputFile = outFile,
                bufferPool = null,
                bitRate = AdtsFileWriter.VOICE_PROFILE_BIT_RATE
            )
            writer.start()
            adtsWriter = writer

            if (activeIgnoreInternalForGemini && activeCaptureMode == MeetingAudioCaptureMode.INTERNAL_AND_MIC) {
                val micFile = File(sessionsDir, "${activeSessionId}_mic.aac")
                activeMicOnlyFile = micFile
                val mWriter = AdtsFileWriter(
                    outputFile = micFile,
                    bufferPool = null,
                    bitRate = AdtsFileWriter.VOICE_PROFILE_BIT_RATE
                )
                mWriter.start()
                micOnlyWriter = mWriter
            }

            startStandardVideoPipelineIfRequested()

            MeetingRecorderStateBus.update {
                it.copy(
                    activeMicSourceLabel = micSourceName,
                    internalSampleRateHz = internalRate
                )
            }

            val internalRec = internalAudioRecord
            if (internalRec != null) {
                internalReaderThread = Thread(
                    { runInternalReaderLoop(internalRec, internalRate) },
                    "MeetingRecorder-Internal"
                ).apply {
                    isDaemon = true
                    start()
                }
            }

            val micRec = micAudioRecord
            if (micRec != null) {
                micReaderThread = Thread(
                    { runMicReaderLoop(micRec) },
                    "MeetingRecorder-Mic"
                ).apply {
                    isDaemon = true
                    start()
                }
            }

            mixerThread = Thread(
                { runMixerAndEncoderLoop(hasMicClock = micRec != null) },
                "MeetingRecorder-Mixer"
            ).apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start standard meeting capture pipeline", t)
            emitStartError("Meeting recorder failed to start: ${t.message ?: t.javaClass.simpleName}")
            requestStopAndFinalize("Pipeline initialization error", discardAudio = true)
        }
    }

    private fun startStandardVideoPipelineIfRequested() {
        when (activeVideoMode) {
            MeetingVideoMode.AUDIO_ONLY -> Unit

            MeetingVideoMode.MEDIA_PROJECTION_VIDEO -> {
                val projection = mediaProjection
                if (projection == null) {
                    MeetingRecorderStateBus.update {
                        it.copy(
                            videoActive = false,
                            videoStatusNote = "Video unavailable: MediaProjection token missing",
                            lastError = "Video capture could not start; audio continues recording."
                        )
                    }
                    return
                }
                runCatching {
                    val tempVideo = File(cacheDir, "mp_video_${activeSessionId}.mp4")
                    activeTempVideoFile = tempVideo
                    val recorder = MediaProjectionVideoRecorder(
                        context = applicationContext,
                        mediaProjection = projection,
                        outputVideoFile = tempVideo
                    )
                    recorder.start()
                    mediaProjectionVideoRecorder = recorder
                    MeetingRecorderStateBus.update {
                        it.copy(
                            videoActive = true,
                            videoStatusNote = "720p H.264 (${recorder.widthPx}×${recorder.heightPx}) via MediaProjection"
                        )
                    }
                }.onFailure { err ->
                    Log.e(TAG, "MediaProjection H.264 video start failed", err)
                    MeetingRecorderStateBus.update {
                        it.copy(
                            videoActive = false,
                            videoStatusNote = "Video start failed (audio continues)",
                            lastError = "Video encoder failed: ${err.message ?: err.javaClass.simpleName}. Audio continues recording."
                        )
                    }
                }
            }

            MeetingVideoMode.ADB_VIRTUAL_SCREEN -> {
                val sharedTempDir = externalCacheDir
                if (sharedTempDir == null) {
                    MeetingRecorderStateBus.update {
                        it.copy(
                            videoActive = false,
                            virtualDisplayId = null,
                            videoStatusNote = "Virtual Screen unavailable (external cache unmounted)",
                            lastError = "External cache storage unavailable for ADB Virtual Screen. Audio continues recording."
                        )
                    }
                    return
                }
                val (w, h, dpi) = MediaProjectionVideoRecorder.computeRecordingDimensions(applicationContext, 720)
                val tempVideo = File(sharedTempDir, "vdisp_${activeSessionId}.mp4")
                activeTempVideoFile = tempVideo

                val result = AdbVirtualScreenController.startVirtualScreen(
                    context = applicationContext,
                    tempVideoFile = tempVideo,
                    tempAudioFile = null,
                    width = w,
                    height = h,
                    dpi = dpi,
                    bitrateBps = 2_000_000,
                    fps = 15,
                    onPreviewFrame = {
                        miniPreviewOverlay?.onPreviewFrameAvailable()
                    }
                )

                val vSession = result.session
                if (vSession != null) {
                    adbVirtualScreenSession = vSession
                    val targetPkg = activeTargetPackageName
                    var movedNote = ""
                    if (!targetPkg.isNullOrBlank()) {
                        val moved = AdbVirtualScreenController.moveAppToDisplay(
                            context = applicationContext,
                            packageName = targetPkg,
                            displayId = vSession.displayId
                        )
                        movedNote = if (moved) {
                            " • $activeTargetAppLabel moved off-screen"
                        } else {
                            " • Tap 'Send to Virtual' if needed"
                        }
                    }
                    MeetingRecorderStateBus.update {
                        it.copy(
                            videoActive = true,
                            virtualDisplayId = vSession.displayId,
                            virtualDisplayWidth = w,
                            virtualDisplayHeight = h,
                            videoStatusNote = "ADB Virtual Display #${vSession.displayId} (${w}×${h})$movedNote"
                        )
                    }
                    if (activeShowMiniPreviewOnStart) {
                        openMiniPreviewOverlay(vSession)
                    }
                } else {
                    val errText = result.error ?: "Unable to create ADB virtual display"
                    MeetingRecorderStateBus.update {
                        it.copy(
                            videoActive = false,
                            virtualDisplayId = null,
                            videoStatusNote = "Virtual Screen failed (audio continues)",
                            lastError = "ADB Virtual Screen failed: $errText. Audio continues recording."
                        )
                    }
                }
            }
        }
    }

    private fun handleMoveAppDisplay(toVirtual: Boolean) {
        val vSession = adbVirtualScreenSession ?: return
        val pkg = activeTargetPackageName ?: findInstalledPackage(applicationContext, activePlatform) ?: return
        val targetDisplayId = if (toVirtual) vSession.displayId else 0
        serviceScope.launch {
            // Never cold-relaunch the launcher activity onto Display 0 on "Bring to Main",
            // so an active call activity or closed app isn't disrupted.
            val ok = AdbVirtualScreenController.moveAppToDisplay(
                context = applicationContext,
                packageName = pkg,
                displayId = targetDisplayId,
                launchIfNotRunning = toVirtual
            )
            if (!toVirtual && !ok) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        applicationContext,
                        "$activeTargetAppLabel task wasn't found running — not relaunched to avoid interrupting calls.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            MeetingRecorderStateBus.update { st ->
                if (!st.isRecording) st else st.copy(
                    videoStatusNote = if (toVirtual) {
                        if (ok) {
                            "Virtual Display #${vSession.displayId} • $activeTargetAppLabel running in background"
                        } else {
                            "Virtual Display #${vSession.displayId} • Could not move $activeTargetAppLabel"
                        }
                    } else {
                        if (ok) {
                            "Virtual Display #${vSession.displayId} • $activeTargetAppLabel moved to main screen"
                        } else {
                            "Virtual Display #${vSession.displayId} • $activeTargetAppLabel task not found running"
                        }
                    }
                )
            }
        }
    }

    private fun handleToggleMiniPreview() {
        val vSession = adbVirtualScreenSession
        if (vSession == null || !isRunning.get() || isFinalizing.get()) {
            dismissMiniPreviewOverlay()
            return
        }
        val existing = miniPreviewOverlay
        if (existing != null) {
            dismissMiniPreviewOverlay()
        } else {
            val opened = openMiniPreviewOverlay(vSession)
            if (!opened) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        applicationContext,
                        "Enable 'Display over other apps' or bind Ambient Brain Accessibility Service to show the Stealth Mini-Preview.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun openMiniPreviewOverlay(vSession: AdbVirtualScreenSession): Boolean {
        dismissMiniPreviewOverlay()
        val overlay = StealthMiniPreviewOverlay(
            context = applicationContext,
            session = vSession,
            appLabel = activeTargetAppLabel.ifBlank { activePlatform.shortLabel },
            onSendToVirtual = { handleMoveAppDisplay(toVirtual = true) },
            onBringToMain = { handleMoveAppDisplay(toVirtual = false) },
            onDismissed = {
                miniPreviewOverlay = null
                MeetingRecorderStateBus.update { it.copy(isMiniPreviewVisible = false) }
            }
        )
        val shown = overlay.show(startCollapsed = false)
        if (shown) {
            miniPreviewOverlay = overlay
            val elapsedSec = if (activeCaptureMode.usesAdbStealthAudio) {
                stealthActiveElapsedMs / 1000L
            } else {
                totalWrittenSamples / TARGET_SAMPLE_RATE
            }
            overlay.updateElapsedText(formatDuration(elapsedSec))
            MeetingRecorderStateBus.update { it.copy(isMiniPreviewVisible = true) }
        } else {
            overlay.dismiss(notifyCallback = false)
            MeetingRecorderStateBus.update { it.copy(isMiniPreviewVisible = false) }
        }
        return shown
    }

    private fun dismissMiniPreviewOverlay() {
        val overlay = miniPreviewOverlay
        miniPreviewOverlay = null
        overlay?.dismiss(notifyCallback = false)
        MeetingRecorderStateBus.update { it.copy(isMiniPreviewVisible = false) }
    }

    @SuppressLint("MissingPermission")
    private fun createInternalPlaybackAudioRecord(projection: MediaProjection): Pair<AudioRecord, Int> {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()

        val sampleRatesToTry = intArrayOf(TARGET_SAMPLE_RATE, 48_000)
        var lastErr: Throwable? = null

        for (rate in sampleRatesToTry) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue
                val frameBytes = (rate / 50) * 2
                val bufferBytes = maxOf(minBuf * 2, frameBytes * 8)

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                val rec = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()

                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    return rec to rate
                } else {
                    runCatching { rec.release() }
                }
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        throw IllegalStateException(
            "Device did not initialize internal audio capture (${lastErr?.message ?: "unsupported format"})"
        )
    }

    @SuppressLint("MissingPermission")
    private fun createMicrophoneAudioRecord(): Pair<AudioRecord, String> {
        val minBuf = AudioRecord.getMinBufferSize(
            TARGET_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_SAMPLES * 4)
        val bufferBytes = maxOf(minBuf * 2, FRAME_SAMPLES * 2 * 8)

        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED"
        )

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(TARGET_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        var lastErr: Throwable? = null
        for ((src, label) in sources) {
            try {
                val rec = AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    return rec to label
                } else {
                    runCatching { rec.release() }
                }
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        throw IllegalStateException(
            "Unable to open microphone for meeting recording (${lastErr?.message ?: "hardware busy"})"
        )
    }

    private fun registerMicSilencedMonitor(audioSessionId: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == audioSessionId }
                val silenced = mine?.isClientSilenced == true
                micSilencedFlag.set(silenced)
            }
        }
        recordingCallback = cb
        runCatching {
            am.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
        }
    }

    private fun runInternalReaderLoop(rec: AudioRecord, rateHz: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val decimationFactor = (rateHz / TARGET_SAMPLE_RATE).coerceAtLeast(1)
        val readSize = FRAME_SAMPLES * decimationFactor
        val rawBuf = ShortArray(readSize)

        while (isRunning.get()) {
            val read = try {
                rec.read(rawBuf, 0, readSize, AudioRecord.READ_BLOCKING)
            } catch (_: Throwable) {
                break
            }
            if (read <= 0) {
                if (!isRunning.get()) break
                runCatching { Thread.sleep(10L) }
                continue
            }

            val frame16k = if (decimationFactor == 1) {
                ShortArray(FRAME_SAMPLES).also { out ->
                    System.arraycopy(rawBuf, 0, out, 0, minOf(read, FRAME_SAMPLES))
                }
            } else {
                decimateTo16k(rawBuf, read, decimationFactor)
            }

            val rms = computeRms(frame16k)
            lastInternalRmsScaled.set((rms * 100f).toLong())
            if (rms >= INTERNAL_SIGNAL_RMS_THRESHOLD) {
                internalAudioEverDetected.set(true)
            }

            if (!internalQueue.offer(frame16k)) {
                internalQueue.poll()
                internalQueue.offer(frame16k)
            }
        }
    }

    private fun decimateTo16k(src: ShortArray, count: Int, factor: Int): ShortArray {
        val out = ShortArray(FRAME_SAMPLES)
        if (factor == 3) {
            val limit = minOf(FRAME_SAMPLES, count / 3)
            for (i in 0 until limit) {
                val base = i * 3
                val blended = (src[base].toInt() + (src[base + 1].toInt() shl 1) + src[base + 2].toInt()) shr 2
                out[i] = blended.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        } else {
            val limit = minOf(FRAME_SAMPLES, count / factor)
            for (i in 0 until limit) {
                var sum = 0
                val base = i * factor
                for (k in 0 until factor) sum += src[base + k].toInt()
                out[i] = (sum / factor).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return out
    }

    private fun runMicReaderLoop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val readBuf = ShortArray(FRAME_SAMPLES)

        while (isRunning.get()) {
            val read = try {
                rec.read(readBuf, 0, FRAME_SAMPLES, AudioRecord.READ_BLOCKING)
            } catch (_: Throwable) {
                break
            }
            if (read <= 0) {
                if (!isRunning.get()) break
                runCatching { Thread.sleep(10L) }
                continue
            }

            val frame = ShortArray(FRAME_SAMPLES)
            System.arraycopy(readBuf, 0, frame, 0, minOf(read, FRAME_SAMPLES))

            val rms = computeRms(frame)
            lastMicRmsScaled.set((rms * 100f).toLong())

            if (!micQueue.offer(frame)) {
                micQueue.poll()
                micQueue.offer(frame)
            }
        }
    }

    private fun runMixerAndEncoderLoop(hasMicClock: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val mixBuf = ShortArray(FRAME_SAMPLES)
        val silenceFrame = ShortArray(FRAME_SAMPLES)
        var uiTickCounter = 0
        var notifTickCounter = 0
        var smoothedInternalVu = 0f
        var smoothedMicVu = 0f
        var nextFrameMonoMs = SystemClock.elapsedRealtime() + FRAME_DURATION_MS

        while (isRunning.get()) {
            val micFrame: ShortArray?
            val internalFrame: ShortArray?

            if (hasMicClock) {
                micFrame = try {
                    micQueue.poll(35L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                internalFrame = if (activeCaptureMode.needsMediaProjection) internalQueue.poll() else null
                if (micFrame == null && internalFrame == null) continue
            } else {
                val waitMs = (nextFrameMonoMs - SystemClock.elapsedRealtime()).coerceIn(1L, FRAME_DURATION_MS)
                internalFrame = try {
                    internalQueue.poll(waitMs, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                micFrame = null
                val now = SystemClock.elapsedRealtime()
                if (internalFrame == null && now < nextFrameMonoMs) {
                    val remaining = nextFrameMonoMs - now
                    if (remaining > 0L) runCatching { Thread.sleep(remaining) }
                }
                nextFrameMonoMs = maxOf(nextFrameMonoMs + FRAME_DURATION_MS, SystemClock.elapsedRealtime())
            }

            val pausedNow = isPaused.get()
            if (pausedNow) {
                closeOpenSegmentIfNeeded()
                internalQueue.clear()
                if (!hasMicClock) micQueue.clear()
                smoothedInternalVu *= 0.6f
                smoothedMicVu *= 0.6f
            } else {
                if (currentSegmentStartOffsetMs < 0L) {
                    currentSegmentStartOffsetMs = (totalWrittenSamples * 1000L) / TARGET_SAMPLE_RATE
                    currentSegmentWallMs = System.currentTimeMillis()
                    currentSegmentSamples = 0L
                }

                mixFramesSoftLimited(
                    internalFrame = internalFrame,
                    micFrame = micFrame,
                    mode = activeCaptureMode,
                    out = mixBuf
                )

                adtsWriter?.writePcmShorts(mixBuf, 0, FRAME_SAMPLES)
                micOnlyWriter?.writePcmShorts(micFrame ?: silenceFrame, 0, FRAME_SAMPLES)

                val currentMicRms = lastMicRmsScaled.get() / 100f
                if (micFrame != null && currentMicRms >= MIC_SPEECH_RMS_THRESHOLD) {
                    micSpeechFrames++
                }

                totalWrittenSamples += FRAME_SAMPLES
                currentSegmentSamples += FRAME_SAMPLES
                activeElapsedFrames++

                val rawInternalNorm = rmsToVisualNorm(lastInternalRmsScaled.get() / 100f)
                val rawMicNorm = rmsToVisualNorm(currentMicRms)
                smoothedInternalVu = smoothedInternalVu * 0.55f + rawInternalNorm * 0.45f
                smoothedMicVu = smoothedMicVu * 0.55f + rawMicNorm * 0.45f
            }

            uiTickCounter++
            if (uiTickCounter >= 8) {
                uiTickCounter = 0
                val elapsedSec = totalWrittenSamples / TARGET_SAMPLE_RATE
                val audioBytes = activeOutputFile?.length() ?: 0L
                val videoBytes = activeTempVideoFile?.length() ?: 0L
                val internalDetected = internalAudioEverDetected.get()
                val silenceHint = activeCaptureMode.needsMediaProjection &&
                    !internalDetected &&
                    !pausedNow &&
                    elapsedSec >= INTERNAL_SILENCE_HINT_SEC

                MeetingRecorderStateBus.update { st ->
                    if (!st.isRecording) st else st.copy(
                        isPaused = pausedNow,
                        elapsedSec = elapsedSec,
                        recordedBytes = audioBytes + videoBytes,
                        internalLevelNorm = if (activeCaptureMode.needsMediaProjection && !pausedNow) smoothedInternalVu else 0f,
                        micLevelNorm = if (activeCaptureMode.needsMic && !pausedNow) smoothedMicVu else 0f,
                        internalAudioDetected = internalDetected,
                        internalSilenceHint = silenceHint,
                        micSilencedByOs = activeCaptureMode.needsMic && micSilencedFlag.get()
                    )
                }
            }

            notifTickCounter++
            if (notifTickCounter >= 50) {
                notifTickCounter = 0
                updateOngoingNotification()
            }
        }
    }

    private fun mixFramesSoftLimited(
        internalFrame: ShortArray?,
        micFrame: ShortArray?,
        mode: MeetingAudioCaptureMode,
        out: ShortArray
    ) {
        when (mode) {
            MeetingAudioCaptureMode.INTERNAL_ONLY,
            MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED -> {
                if (internalFrame != null) {
                    System.arraycopy(internalFrame, 0, out, 0, FRAME_SAMPLES)
                } else {
                    out.fill(0)
                }
            }
            MeetingAudioCaptureMode.MIC_SPEAKERPHONE -> {
                if (micFrame != null) {
                    System.arraycopy(micFrame, 0, out, 0, FRAME_SAMPLES)
                } else {
                    out.fill(0)
                }
            }
            MeetingAudioCaptureMode.INTERNAL_AND_MIC -> {
                for (i in 0 until FRAME_SAMPLES) {
                    val intSample = internalFrame?.get(i)?.toFloat() ?: 0f
                    val micSample = micFrame?.get(i)?.toFloat() ?: 0f
                    val sum = intSample + micSample * 1.05f
                    val limited = if (abs(sum) <= 24_000f) {
                        sum
                    } else {
                        sign(sum) * (24_000f + 8_760f * tanh(((abs(sum) - 24_000f) / 12_000f).toDouble()).toFloat())
                    }
                    out[i] = limited.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
        }
    }

    private fun closeOpenSegmentIfNeeded() {
        if (currentSegmentStartOffsetMs >= 0L && currentSegmentSamples > 0L) {
            val lengthMs = (currentSegmentSamples * 1000L) / TARGET_SAMPLE_RATE
            segmentMaps.add(
                SegmentMapEntity(
                    sessionId = activeSessionId,
                    fileOffsetMs = currentSegmentStartOffsetMs,
                    wallClockMs = currentSegmentWallMs,
                    lengthMs = lengthMs.coerceAtLeast(20L)
                )
            )
        }
        currentSegmentStartOffsetMs = -1L
        currentSegmentSamples = 0L
    }

    private fun handleTogglePause() {
        if (!isRunning.get() || isFinalizing.get()) return
        val newPaused = !isPaused.get()
        isPaused.set(newPaused)
        mediaProjectionVideoRecorder?.setPaused(newPaused)
        adbVirtualScreenSession?.let { vSession ->
            serviceScope.launch {
                AdbVirtualScreenController.sendCommand(vSession, if (newPaused) "PAUSE" else "RESUME")
            }
        }
        MeetingRecorderStateBus.update {
            it.copy(
                isPaused = newPaused,
                internalLevelNorm = if (newPaused) 0f else it.internalLevelNorm,
                micLevelNorm = if (newPaused) 0f else it.micLevelNorm
            )
        }
        updateOngoingNotification()
    }

    private fun handleToggleSpeakerphone() {
        if (!isRunning.get() || isFinalizing.get()) return
        val target = !MeetingRecorderStateBus.state.value.speakerphoneBoostEnabled
        applySpeakerphoneBoost(target)
    }

    private fun applySpeakerphoneBoost(enable: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (enable) {
                val speaker = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker != null && am.setCommunicationDevice(speaker)) {
                    speakerphoneAppliedByUs = true
                    speakerphoneBoostUsedEver.set(true)
                    MeetingRecorderStateBus.update { it.copy(speakerphoneBoostEnabled = true) }
                }
            } else {
                am.clearCommunicationDevice()
                speakerphoneAppliedByUs = false
                MeetingRecorderStateBus.update { it.copy(speakerphoneBoostEnabled = false) }
            }
        }
    }

    private fun requestStopAndFinalize(reason: String, discardAudio: Boolean = false) {
        if (!isFinalizing.compareAndSet(false, true)) return
        Log.i(TAG, "Finalizing meeting recording ($activeSessionId): reason=$reason, discard=$discardAudio")

        isRunning.set(false)
        stealthTickerJob?.cancel()
        stealthTickerJob = null
        dismissMiniPreviewOverlay()

        MeetingRecorderStateBus.update {
            it.copy(
                isFinalizing = true,
                isMiniPreviewVisible = false,
                internalLevelNorm = 0f,
                micLevelNorm = 0f
            )
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 1. Stop standard AudioRecords & join threads
                runCatching { internalAudioRecord?.stop() }
                runCatching { micAudioRecord?.stop() }
                runCatching { internalReaderThread?.join(1_500L) }
                runCatching { micReaderThread?.join(1_500L) }
                runCatching { mixerThread?.join(2_500L) }
                internalReaderThread = null
                micReaderThread = null
                mixerThread = null

                closeOpenSegmentIfNeeded()
                runCatching { adtsWriter?.finishAndClose() }
                adtsWriter = null
                runCatching { micOnlyWriter?.finishAndClose() }
                micOnlyWriter = null

                // 2. Restore AppOps mic lock immediately if fallback was used
                restoreAppOpsMicLockIfNeeded()

                // 3. Finalize ADB Virtual Screen (and isolated shell audio) or MediaProjection video
                var hasValidTempVideo = false
                var hasValidStealthAudio = false

                val vSession = adbVirtualScreenSession
                adbVirtualScreenSession = null
                if (vSession != null) {
                    val targetPkg = activeTargetPackageName
                    if (!targetPkg.isNullOrBlank()) {
                        runCatching {
                            // Only move back if still running; never relaunch a closed app onto Display 0 on Stop
                            AdbVirtualScreenController.moveAppToDisplay(
                                context = applicationContext,
                                packageName = targetPkg,
                                displayId = 0,
                                launchIfNotRunning = false
                            )
                        }
                    }
                    val (vOk, aOk) = AdbVirtualScreenController.stopAndFinalize(vSession)
                    AdbVirtualScreenController.deleteTempFileBestEffort(applicationContext, vSession.previewFile)
                    hasValidTempVideo = vOk
                    hasValidStealthAudio = aOk
                }

                val mpVideoRec = mediaProjectionVideoRecorder
                mediaProjectionVideoRecorder = null
                if (mpVideoRec != null) {
                    hasValidTempVideo = mpVideoRec.stopAndFinalize() || hasValidTempVideo
                }

                // 4. Release hardware, MediaProjection, speakerphone override, wake lock, and ambient mic pause
                releaseHardwareAndLocks()

                // 5. Consolidate audio file (either standard AAC or copied from Stealth shell AAC)
                val sessionsDir = File(filesDir, "sessions").apply { mkdirs() }
                var finalAudioFile: File? = if (activeCaptureMode.usesAdbStealthAudio) {
                    val stealthAac = activeTempStealthAudioFile
                    if (hasValidStealthAudio && stealthAac != null && stealthAac.exists() && stealthAac.length() >= 1024L) {
                        val dest = File(sessionsDir, "${activeSessionId}.aac")
                        runCatching { stealthAac.copyTo(dest, overwrite = true) }
                        AdbVirtualScreenController.deleteTempFileBestEffort(applicationContext, stealthAac)
                        dest.takeIf { it.exists() && it.length() >= 1024L }
                    } else {
                        AdbVirtualScreenController.deleteTempFileBestEffort(applicationContext, stealthAac)
                        null
                    }
                } else {
                    activeOutputFile?.takeIf { it.exists() && it.length() >= 1024L }
                }

                val micOnlyFile = activeMicOnlyFile
                val tempVideoFile = activeTempVideoFile
                val recordedDurationMs = if (activeCaptureMode.usesAdbStealthAudio) {
                    stealthActiveElapsedMs
                } else {
                    (totalWrittenSamples * 1000L) / TARGET_SAMPLE_RATE
                }
                val endWallMs = System.currentTimeMillis()
                val hasUsableMedia = !discardAudio && recordedDurationMs >= 1_000L &&
                    (finalAudioFile != null || (hasValidTempVideo && tempVideoFile != null && tempVideoFile.exists()))

                if (hasUsableMedia) {
                    finalAudioFile?.let { runCatching { it.setLastModified(sessionStartWallMs) } }

                    val resolvedTitle = buildFinalMeetingTitle(
                        appLabel = activeTargetAppLabel,
                        customTitle = activeMeetingTitle,
                        startWallMs = sessionStartWallMs
                    )

                    // Mux H.264 video + full isolated audio into MP4 & export to user's Download/custom folder
                    var fallbackPrivateVideoPath: String? = null
                    var savedPublicUri: String? = null
                    var savedPublicDisplayPath: String? = null

                    if (hasValidTempVideo && tempVideoFile != null && tempVideoFile.exists() && tempVideoFile.length() > 1024L) {
                        val meetingsDir = File(filesDir, "meetings").apply { mkdirs() }
                        val stagedMp4 = File(meetingsDir, "${activeSessionId}.mp4")
                        val muxedOk = MeetingMp4Muxer.muxVideoAndAdtsAudio(
                            videoOnlyMp4 = tempVideoFile,
                            adtsAacFile = finalAudioFile,
                            outputMp4 = stagedMp4
                        )
                        if (muxedOk && stagedMp4.exists() && stagedMp4.length() > 1024L) {
                            runCatching { stagedMp4.setLastModified(sessionStartWallMs) }
                            val publicRes = MeetingMp4Muxer.saveVideoToConfiguredPublicFolder(
                                context = applicationContext,
                                sourceMp4 = stagedMp4,
                                meetingTitle = resolvedTitle,
                                startWallMs = sessionStartWallMs,
                                prefs = PreferencesRepo(applicationContext)
                            )
                            if (publicRes.contentUri != null) {
                                savedPublicUri = publicRes.contentUri
                                savedPublicDisplayPath = publicRes.displayPath
                                // Remove private duplicate once safely written to public Download/custom folder
                                runCatching { stagedMp4.delete() }
                            } else {
                                // Keep private fallback copy if MediaStore/SAF export failed
                                fallbackPrivateVideoPath = stagedMp4.absolutePath
                            }
                        }
                    }
                    AdbVirtualScreenController.deleteTempFileBestEffort(applicationContext, tempVideoFile)

                    // Resolve clean Gemini audio path while strictly honoring ignoreInternalAppAudioForGemini
                    val micSpeechMs = micSpeechFrames * FRAME_DURATION_MS
                    val resolvedGeminiAudioPath: String? = if (activeIgnoreInternalForGemini) {
                        when (activeCaptureMode) {
                            MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED,
                            MeetingAudioCaptureMode.INTERNAL_ONLY -> null
                            MeetingAudioCaptureMode.INTERNAL_AND_MIC -> {
                                if (micOnlyFile != null &&
                                    micOnlyFile.exists() &&
                                    micOnlyFile.length() >= MIN_ANALYZE_BYTES &&
                                    micSpeechMs >= MIN_MIC_SPEECH_FOR_GEMINI_MS
                                ) {
                                    runCatching { micOnlyFile.setLastModified(sessionStartWallMs) }
                                    micOnlyFile.absolutePath
                                } else {
                                    runCatching { micOnlyFile?.delete() }
                                    null
                                }
                            }
                            MeetingAudioCaptureMode.MIC_SPEAKERPHONE -> finalAudioFile?.absolutePath
                        }
                    } else {
                        runCatching { micOnlyFile?.delete() }
                        null
                    }

                    val geminiExcludedInternalOnly = activeIgnoreInternalForGemini && (
                        activeCaptureMode == MeetingAudioCaptureMode.STEALTH_ADB_ISOLATED ||
                            activeCaptureMode == MeetingAudioCaptureMode.INTERNAL_ONLY ||
                            (activeCaptureMode == MeetingAudioCaptureMode.INTERNAL_AND_MIC && resolvedGeminiAudioPath == null)
                        )

                    // Avoid permanently duplicating a pinned private .aac copy in Stealth mode when the full
                    // audio+video MP4 is already saved to Downloads/custom folder and Gemini ignores it.
                    if (activeCaptureMode.usesAdbStealthAudio &&
                        geminiExcludedInternalOnly &&
                        (savedPublicUri != null || fallbackPrivateVideoPath != null)
                    ) {
                        runCatching { finalAudioFile?.delete() }
                        finalAudioFile = null
                    }

                    val shouldAutoAnalyze = activeAutoAnalyze &&
                        finalAudioFile != null &&
                        finalAudioFile.length() >= MIN_ANALYZE_BYTES &&
                        !geminiExcludedInternalOnly

                    val marker = MeetingMetadataMarker(
                        platform = activeTargetAppLabel.ifBlank { activePlatform.label },
                        targetPackageName = activeTargetPackageName,
                        captureMode = activeCaptureMode.name,
                        videoMode = activeVideoMode.name,
                        meetingTitle = resolvedTitle,
                        internalAudioCaptured = internalAudioEverDetected.get(),
                        speakerphoneBoostUsed = speakerphoneBoostUsedEver.get(),
                        ignoreInternalAppAudioForGemini = activeIgnoreInternalForGemini,
                        geminiAudioPath = resolvedGeminiAudioPath,
                        videoPath = fallbackPrivateVideoPath,
                        galleryUri = savedPublicUri,
                        publicVideoDisplayPath = savedPublicDisplayPath,
                        audioCaptureTier = activeAudioCaptureTier.takeIf { it.isNotBlank() },
                        virtualMicTier = activeVirtualMicTier.takeIf { it.isNotBlank() }
                    )
                    val metadataJson = MeetingMetadataHelper.encode(marker)

                    val sessionMode = if (activeIsClinical) SessionMode.ROUND_MANUAL else SessionMode.EVERYDAY
                    val initialContext = if (activeIsClinical) "CASE_CONFERENCE" else "ONLINE_MEETING"
                    val videoDesc = when {
                        !savedPublicDisplayPath.isNullOrBlank() -> " + MP4 saved to $savedPublicDisplayPath"
                        fallbackPrivateVideoPath != null -> " + 720p MP4 video"
                        else -> ""
                    }

                    val summaryText = when {
                        geminiExcludedInternalOnly ->
                            "Saved $activeTargetAppLabel recording (${formatDuration(recordedDurationMs / 1000L)}$videoDesc). Internal app audio was excluded from Gemini."
                        shouldAutoAnalyze && activeIgnoreInternalForGemini && activeCaptureMode == MeetingAudioCaptureMode.INTERNAL_AND_MIC ->
                            "Saved $activeTargetAppLabel recording (${formatDuration(recordedDurationMs / 1000L)}$videoDesc). Queued local mic-only track for Gemini (internal app audio ignored)."
                        shouldAutoAnalyze ->
                            "Recorded $activeTargetAppLabel (${formatDuration(recordedDurationMs / 1000L)}$videoDesc). Queued for Gemini analysis..."
                        else ->
                            "Recorded $activeTargetAppLabel (${formatDuration(recordedDurationMs / 1000L)}$videoDesc)."
                    }

                    val entity = SessionEntity(
                        id = activeSessionId,
                        roundGroupId = if (activeIsClinical) activeSessionId else null,
                        eventGroupId = activeSessionId,
                        mode = sessionMode,
                        startMs = sessionStartWallMs,
                        endMs = endWallMs,
                        speechMs = recordedDurationMs,
                        hadPhoneCall = false,
                        callEventsJson = metadataJson,
                        rawPath = finalAudioFile?.absolutePath,
                        rawBytes = finalAudioFile?.length() ?: 0L,
                        isRawPurged = finalAudioFile == null,
                        isPinned = true,
                        context = initialContext,
                        title = resolvedTitle,
                        summary = summaryText,
                        status = if (shouldAutoAnalyze) Status.QUEUED else Status.DONE
                    )

                    val dao = AppDatabase.get(applicationContext).dao()
                    dao.upsertSession(entity)
                    if (segmentMaps.isNotEmpty()) {
                        dao.insertSegmentMaps(segmentMaps.toList())
                    }

                    if (shouldAutoAnalyze) {
                        SessionAnalyzeWorker.enqueue(applicationContext, activeSessionId)
                    }

                    MeetingRecorderStateBus.update {
                        MeetingRecorderState(
                            isRecording = false,
                            isPaused = false,
                            isFinalizing = false,
                            platform = activePlatform,
                            targetPackageName = activeTargetPackageName.orEmpty(),
                            targetAppLabel = activeTargetAppLabel,
                            captureMode = activeCaptureMode,
                            videoMode = activeVideoMode,
                            isClinicalMeeting = activeIsClinical,
                            autoAnalyzeOnStop = activeAutoAnalyze,
                            ignoreInternalAppAudioForGemini = activeIgnoreInternalForGemini,
                            enableVirtualMic = activeEnableVirtualMic,
                            silentSpeakerIsolation = activeSilentSpeaker,
                            keepAmbientBrainListening = activeKeepAmbientListening,
                            lastSavedSessionId = activeSessionId,
                            lastSavedTitle = resolvedTitle,
                            lastSavedAutoAnalyzed = shouldAutoAnalyze,
                            lastSavedGeminiExcludedInternal = activeIgnoreInternalForGemini,
                            lastSavedHasRawAudio = finalAudioFile != null && finalAudioFile.exists(),
                            lastSavedVideoPath = fallbackPrivateVideoPath,
                            lastSavedGalleryUri = savedPublicUri,
                            lastSavedPublicVideoPath = savedPublicDisplayPath
                        )
                    }
                } else {
                    runCatching { finalAudioFile?.delete() }
                    runCatching { activeOutputFile?.delete() }
                    runCatching { micOnlyFile?.delete() }
                    AdbVirtualScreenController.deleteTempFileBestEffort(applicationContext, tempVideoFile)
                    AdbVirtualScreenController.deleteTempFileBestEffort(applicationContext, activeTempStealthAudioFile)
                    val existingErr = MeetingRecorderStateBus.state.value.lastError
                    MeetingRecorderStateBus.update {
                        MeetingRecorderState(
                            isRecording = false,
                            isPaused = false,
                            isFinalizing = false,
                            platform = activePlatform,
                            targetPackageName = activeTargetPackageName.orEmpty(),
                            targetAppLabel = activeTargetAppLabel,
                            captureMode = activeCaptureMode,
                            videoMode = activeVideoMode,
                            isClinicalMeeting = activeIsClinical,
                            autoAnalyzeOnStop = activeAutoAnalyze,
                            ignoreInternalAppAudioForGemini = activeIgnoreInternalForGemini,
                            enableVirtualMic = activeEnableVirtualMic,
                            silentSpeakerIsolation = activeSilentSpeaker,
                            keepAmbientBrainListening = activeKeepAmbientListening,
                            lastError = existingErr ?: if (!discardAudio) {
                                "Recording was under 1 second or had no captured video/audio."
                            } else null
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error during meeting recorder finalization", t)
                restoreAppOpsMicLockIfNeeded()
                releaseHardwareAndLocks()
                MeetingRecorderStateBus.update {
                    it.copy(
                        isRecording = false,
                        isPaused = false,
                        isFinalizing = false,
                        lastError = "Error saving recording: ${t.message ?: t.javaClass.simpleName}"
                    )
                }
            } finally {
                isFinalizing.set(false)
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun restoreAppOpsMicLockIfNeeded() {
        val prefs = PreferencesRepo(applicationContext)
        val pkg = appOpsMicLockedPackage ?: prefs.pendingAppOpsMicRestorePackage.takeIf { it.isNotBlank() } ?: return
        appOpsMicLockedPackage = null
        val restored = runCatching {
            AdbVirtualScreenController.setAppRecordAudioAppOp(applicationContext, pkg, allow = true)
        }.getOrDefault(false)
        if (restored) {
            prefs.pendingAppOpsMicRestorePackage = ""
        }
    }

    private fun releaseHardwareAndLocks() {
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            recordingCallback?.let { cb -> am?.unregisterAudioRecordingCallback(cb) }
            if (speakerphoneAppliedByUs) {
                am?.clearCommunicationDevice()
                speakerphoneAppliedByUs = false
            }
        }
        recordingCallback = null

        runCatching { internalAudioRecord?.release() }
        internalAudioRecord = null

        runCatching { micAudioRecord?.release() }
        micAudioRecord = null

        val proj = mediaProjection
        val cb = projectionCallback
        mediaProjection = null
        projectionCallback = null
        if (proj != null) {
            if (cb != null) runCatching { proj.unregisterCallback(cb) }
            runCatching { proj.stop() }
        }

        if (holdsExternalMicPause.compareAndSet(true, false)) {
            CaptureEngineController.releaseExternalMicPause()
        }

        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (wakeLock?.isHeld == true) return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AmbientBrain:MeetingRecorder"
            ).apply {
                setReferenceCounted(false)
                acquire(TimeUnit.HOURS.toMillis(4))
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { wl -> if (wl.isHeld) wl.release() }
        }
        wakeLock = null
    }

    private fun emitStartError(msg: String) {
        MeetingRecorderStateBus.update {
            it.copy(
                isRecording = false,
                isPaused = false,
                isFinalizing = false,
                lastError = msg
            )
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Zoom, Webex & Isolated App Recorder",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live status and quick Pause/Stop controls while recording meetings or isolated apps."
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun updateOngoingNotification() {
        if (!isRunning.get() || isFinalizing.get()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val elapsedSec = if (activeCaptureMode.usesAdbStealthAudio) {
            stealthActiveElapsedMs / 1000L
        } else {
            totalWrittenSamples / TARGET_SAMPLE_RATE
        }
        val notif = buildNotification(
            appLabel = activeTargetAppLabel.ifBlank { activePlatform.label },
            captureMode = activeCaptureMode,
            videoMode = activeVideoMode,
            elapsedSec = elapsedSec,
            paused = isPaused.get()
        )
        runCatching { nm.notify(NOTIFICATION_ID, notif) }
    }

    private fun buildNotification(
        appLabel: String,
        captureMode: MeetingAudioCaptureMode,
        videoMode: MeetingVideoMode,
        elapsedSec: Long,
        paused: Boolean
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_MEETING_RECORDER, true)
        }
        val openAppPending = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseIntent = Intent(this, MeetingRecorderService::class.java).apply {
            action = ACTION_TOGGLE_PAUSE
        }
        val pausePending = PendingIntent.getService(
            this,
            101,
            pauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MeetingRecorderService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val titlePrefix = if (paused) "Paused" else if (captureMode.usesAdbStealthAudio) "Stealth Recording" else "Recording"
        val customSuffix = activeMeetingTitle.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
        val timeText = formatDuration(elapsedSec)
        val videoTag = if (videoMode != MeetingVideoMode.AUDIO_ONLY) " • ${videoMode.shortLabel}" else ""
        val contentText = "$timeText • ${captureMode.shortLabel}$videoTag • Tap for controls"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("$titlePrefix $appLabel$customSuffix")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPending)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                pausePending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop & Save",
                stopPending
            )
            .build()
    }

    /**
     * Android 15 (targetSdk 35) invokes this callback when a `FOREGROUND_SERVICE_TYPE_DATA_SYNC`
     * session hits the 6-hour rolling quota. Finalize and stop cleanly so the OS never throws
     * `ForegroundServiceDidNotStopInTimeException`.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout triggered (startId=$startId, fgsType=$fgsType) — finalizing safely")
        if (isRunning.get() && !isFinalizing.get()) {
            requestStopAndFinalize("Android dataSync FGS time limit reached")
        } else {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        dismissMiniPreviewOverlay()
        if (isRunning.get() && !isFinalizing.get()) {
            // Restore AppOps mic lock immediately on an inline daemon thread bounded to 2s
            // so sudden service teardown never leaves Zoom/Webex muted even if async muxing is cut short.
            runCatching {
                val t = Thread({ restoreAppOpsMicLockIfNeeded() }, "MeetingRec-EmergencyRestore")
                t.start()
                t.join(2_000L)
            }
            requestStopAndFinalize("Service destroyed")
        } else if (!isFinalizing.get()) {
            runCatching {
                val t = Thread({ restoreAppOpsMicLockIfNeeded() }, "MeetingRec-CleanupRestore")
                t.start()
                t.join(1_500L)
            }
            releaseHardwareAndLocks()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun computeRms(samples: ShortArray): Float {
        var sumSq = 0.0
        for (s in samples) {
            val d = s.toDouble()
            sumSq += d * d
        }
        return sqrt(sumSq / samples.size.coerceAtLeast(1)).toFloat()
    }

    private fun rmsToVisualNorm(rms: Float): Float {
        if (rms <= 15f) return 0f
        val db = 20.0 * kotlin.math.log10((rms / 32767.0).coerceAtLeast(1e-5))
        return ((db + 58.0) / 52.0).toFloat().coerceIn(0f, 1f)
    }

    private fun buildFinalMeetingTitle(
        appLabel: String,
        customTitle: String,
        startWallMs: Long
    ): String {
        val cleanApp = appLabel.trim().ifBlank { "Meeting" }
        if (customTitle.isNotBlank()) {
            return if (customTitle.contains(cleanApp, ignoreCase = true) || cleanApp.equals("Online Meeting", ignoreCase = true)) {
                customTitle
            } else {
                "$cleanApp: $customTitle"
            }
        }
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(startWallMs))
        return "$cleanApp Recording ($timeFmt)"
    }

    companion object {
        private const val TAG = "MeetingRecorderService"
        private const val CHANNEL_ID = "meeting_recorder_channel"
        private const val NOTIFICATION_ID = 4207

        private const val TARGET_SAMPLE_RATE = AdtsFileWriter.SAMPLE_RATE // 16,000 Hz
        private const val FRAME_SAMPLES = 320 // 20 ms at 16 kHz
        private const val FRAME_DURATION_MS = 20L
        private const val INTERNAL_SIGNAL_RMS_THRESHOLD = 45f
        private const val MIC_SPEECH_RMS_THRESHOLD = 150f
        private const val MIN_MIC_SPEECH_FOR_GEMINI_MS = 1_500L
        private const val INTERNAL_SILENCE_HINT_SEC = 6L
        private const val MIN_ANALYZE_BYTES = 4096L

        const val EXTRA_OPEN_MEETING_RECORDER = "open_meeting_recorder_dialog"

        private const val ACTION_START = "com.ambientbrain.recorder.action.MEETING_START"
        private const val ACTION_STOP = "com.ambientbrain.recorder.action.MEETING_STOP"
        private const val ACTION_TOGGLE_PAUSE = "com.ambientbrain.recorder.action.MEETING_TOGGLE_PAUSE"
        private const val ACTION_TOGGLE_SPEAKERPHONE = "com.ambientbrain.recorder.action.MEETING_TOGGLE_SPEAKERPHONE"
        private const val ACTION_MOVE_APP_TO_VIRTUAL = "com.ambientbrain.recorder.action.MEETING_MOVE_TO_VIRTUAL"
        private const val ACTION_BRING_APP_TO_MAIN = "com.ambientbrain.recorder.action.MEETING_BRING_TO_MAIN"
        private const val ACTION_TOGGLE_MINI_PREVIEW = "com.ambientbrain.recorder.action.MEETING_TOGGLE_MINI_PREVIEW"

        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val EXTRA_PLATFORM = "extra_platform"
        private const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        private const val EXTRA_TARGET_APP_LABEL = "extra_target_app_label"
        private const val EXTRA_CAPTURE_MODE = "extra_capture_mode"
        private const val EXTRA_VIDEO_MODE = "extra_video_mode"
        private const val EXTRA_MEETING_TITLE = "extra_meeting_title"
        private const val EXTRA_IS_CLINICAL = "extra_is_clinical"
        private const val EXTRA_AUTO_ANALYZE = "extra_auto_analyze"
        private const val EXTRA_IGNORE_INTERNAL_FOR_GEMINI = "extra_ignore_internal_for_gemini"
        private const val EXTRA_ENABLE_VIRTUAL_MIC = "extra_enable_virtual_mic"
        private const val EXTRA_SILENT_SPEAKER = "extra_silent_speaker"
        private const val EXTRA_KEEP_AMBIENT_LISTENING = "extra_keep_ambient_listening"
        private const val EXTRA_SHOW_MINI_PREVIEW = "extra_show_mini_preview"
        private const val EXTRA_SPEAKERPHONE_BOOST = "extra_speakerphone_boost"

        fun formatDuration(totalSec: Long): String {
            val safe = totalSec.coerceAtLeast(0L)
            val h = safe / 3600L
            val m = (safe % 3600L) / 60L
            val s = safe % 60L
            return if (h > 0L) {
                "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
            } else {
                "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
            }
        }

        fun findInstalledPackage(context: Context, platform: MeetingPlatform): String? {
            val pm = context.packageManager
            for (pkg in platform.packageCandidates) {
                val installed = runCatching {
                    pm.getPackageInfo(pkg, 0)
                    true
                }.getOrDefault(false)
                if (installed) return pkg
            }
            return null
        }

        fun isPlatformInstalled(context: Context, platform: MeetingPlatform): Boolean {
            return findInstalledPackage(context, platform) != null
        }

        fun launchPackage(context: Context, packageName: String?): Boolean {
            if (packageName.isNullOrBlank()) return false
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                context.startActivity(launchIntent)
                true
            }.getOrDefault(false)
        }

        fun launchMeetingApp(context: Context, platform: MeetingPlatform, customPackageName: String? = null): Boolean {
            val pkg = customPackageName?.takeIf { it.isNotBlank() } ?: findInstalledPackage(context, platform)
            return launchPackage(context, pkg)
        }

        fun startRecording(
            context: Context,
            platform: MeetingPlatform,
            targetPackageName: String? = null,
            targetAppLabel: String = "",
            captureMode: MeetingAudioCaptureMode,
            videoMode: MeetingVideoMode = MeetingVideoMode.ADB_VIRTUAL_SCREEN,
            meetingTitle: String,
            isClinicalMeeting: Boolean,
            autoAnalyzeOnStop: Boolean,
            ignoreInternalAppAudioForGemini: Boolean = true,
            enableVirtualMic: Boolean = true,
            silentSpeaker: Boolean = true,
            keepAmbientBrainListening: Boolean = true,
            showMiniPreviewOnStart: Boolean = false,
            enableSpeakerphoneBoost: Boolean = false,
            projectionResultCode: Int = Activity.RESULT_CANCELED,
            projectionData: Intent? = null
        ) {
            val intent = Intent(context, MeetingRecorderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PLATFORM, platform.name)
                if (!targetPackageName.isNullOrBlank()) {
                    putExtra(EXTRA_TARGET_PACKAGE, targetPackageName)
                }
                if (targetAppLabel.isNotBlank()) {
                    putExtra(EXTRA_TARGET_APP_LABEL, targetAppLabel)
                }
                putExtra(EXTRA_CAPTURE_MODE, captureMode.name)
                putExtra(EXTRA_VIDEO_MODE, videoMode.name)
                putExtra(EXTRA_MEETING_TITLE, meetingTitle)
                putExtra(EXTRA_IS_CLINICAL, isClinicalMeeting)
                putExtra(EXTRA_AUTO_ANALYZE, autoAnalyzeOnStop)
                putExtra(EXTRA_IGNORE_INTERNAL_FOR_GEMINI, ignoreInternalAppAudioForGemini)
                putExtra(EXTRA_ENABLE_VIRTUAL_MIC, enableVirtualMic)
                putExtra(EXTRA_SILENT_SPEAKER, silentSpeaker)
                putExtra(EXTRA_KEEP_AMBIENT_LISTENING, keepAmbientBrainListening)
                putExtra(EXTRA_SHOW_MINI_PREVIEW, showMiniPreviewOnStart)
                putExtra(EXTRA_SPEAKERPHONE_BOOST, enableSpeakerphoneBoost)
                putExtra(EXTRA_RESULT_CODE, projectionResultCode)
                if (projectionData != null) {
                    putExtra(EXTRA_RESULT_DATA, projectionData)
                }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, MeetingRecorderService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }

        fun togglePause(context: Context) {
            val intent = Intent(context, MeetingRecorderService::class.java).apply {
                action = ACTION_TOGGLE_PAUSE
            }
            runCatching { context.startService(intent) }
        }

        fun toggleSpeakerphoneBoost(context: Context) {
            val intent = Intent(context, MeetingRecorderService::class.java).apply {
                action = ACTION_TOGGLE_SPEAKERPHONE
            }
            runCatching { context.startService(intent) }
        }

        fun moveMeetingAppToVirtualScreen(context: Context) {
            val intent = Intent(context, MeetingRecorderService::class.java).apply {
                action = ACTION_MOVE_APP_TO_VIRTUAL
            }
            runCatching { context.startService(intent) }
        }

        fun bringMeetingAppToMainScreen(context: Context) {
            val intent = Intent(context, MeetingRecorderService::class.java).apply {
                action = ACTION_BRING_APP_TO_MAIN
            }
            runCatching { context.startService(intent) }
        }

        fun toggleMiniPreview(context: Context) {
            val intent = Intent(context, MeetingRecorderService::class.java).apply {
                action = ACTION_TOGGLE_MINI_PREVIEW
            }
            runCatching { context.startService(intent) }
        }
    }
}
