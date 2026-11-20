package com.ambientbrain.recorder.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.ambientbrain.recorder.data.CaptureStateBus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class CallMarkerEvent(
    val type: String, // "CALL_STARTED" or "CALL_ENDED"
    val epochMs: Long,
    val sessionOffsetMs: Long,
    val audioMode: Int
)

/**
 * Ensures resilient recording across phone/VoIP calls (`MODE_IN_CALL`,
 * `MODE_IN_COMMUNICATION`, `MODE_RINGTONE`) and cooperative mic sharing with other apps.
 *
 * Monitors:
 *  1. `AudioManager.OnModeChangedListener` to log call transitions without dropping speech.
 *  2. System-wide `AudioManager.AudioRecordingCallback` tracking our exact `audioSessionId`
 *     to detect `isClientSilenced` accurately AND detect when other apps open/close the mic.
 *  3. When Shizuku is authorized, resolves exact external mic owner package/UID/source via
 *     `dumpsys audio` + `dumpsys media.audio_flinger` asynchronously on a helper thread.
 *  4. Provides `openResilientAudioRecord()` using `VOICE_RECOGNITION` primary (required by AOSP
 *     `AudioPolicyService::updateUidStates_l` for `isA11yOnTop` concurrent capture, preventing
 *     `UNPROCESSED` raw HAL locks that cause "microphone in use" in other apps).
 */
class CallRouteGuard(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var shizukuInspectExecutor: ExecutorService? = null
    private val callEvents = mutableListOf<CallMarkerEvent>()
    private var sessionStartEpochMs: Long = System.currentTimeMillis()

    @Volatile
    var isInCallMode: Boolean = false
        private set

    @Volatile
    var isSilencedBySystem: Boolean = false
        private set

    @Volatile
    var externalRecordingActive: Boolean = false
        private set

    @Volatile
    var externalRecordingCount: Int = 0
        private set

    @Volatile
    var lastShizukuExternalClient: ActiveAudioClientInfo? = null
        private set

    @Volatile
    var isExternalClientExemptedStealth: Boolean = false
        private set

    @Volatile
    var needsStreamReopen: Boolean = false

    @Volatile
    private var activeAudioSessionId: Int = 0

    @Volatile
    private var lastReleasedSessionId: Int = 0

    @Volatile
    private var lastReleasedEpochMs: Long = 0L

    @Volatile
    private var lastShizukuInspectMonoMs: Long = 0L

    private val shizukuInspectInFlight = AtomicBoolean(false)
    private val audioRecordLifecycleLock = Any()
    private var activeAudioRecordRef: AudioRecord? = null

    @Volatile
    var onRecordingStateChanged: (() -> Unit)? = null

    private fun getOrCreateExecutor(): ExecutorService {
        val existing = executor
        if (existing != null && !existing.isShutdown) return existing
        return Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AmbientBrain-AudioRouteGuard").apply {
                isDaemon = true
            }
        }.also { executor = it }
    }

    private fun getOrCreateShizukuInspectExecutor(): ExecutorService {
        val existing = shizukuInspectExecutor
        if (existing != null && !existing.isShutdown) return existing
        return Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AmbientBrain-ShizukuAudioInspector").apply {
                isDaemon = true
            }
        }.also { shizukuInspectExecutor = it }
    }

    private fun scheduleShizukuMicOwnerInspection(configs: List<AudioRecordingConfiguration>) {
        if (ShizukuAdbManager.getShizukuStatus(appContext) != ShizukuStatus.AUTHORIZED) {
            return
        }
        val nowMono = SystemClock.elapsedRealtime()
        if (nowMono - lastShizukuInspectMonoMs < SHIZUKU_INSPECT_COOLDOWN_MS) {
            return
        }
        if (!shizukuInspectInFlight.compareAndSet(false, true)) {
            return
        }
        val snapshotConfigs = configs.toList()
        val exec = runCatching { getOrCreateShizukuInspectExecutor() }.getOrElse {
            shizukuInspectInFlight.set(false)
            return
        }
        val submitted = runCatching {
            exec.execute {
                try {
                    lastShizukuInspectMonoMs = SystemClock.elapsedRealtime()
                    val snapshot = ShizukuAdbManager.inspectActiveAudioClientsViaShizuku(
                        context = appContext,
                        existingConfigs = snapshotConfigs
                    )
                    if (!externalRecordingActive && !isSilencedBySystem) {
                        lastShizukuExternalClient = null
                        isExternalClientExemptedStealth = false
                        CaptureStateBus.update {
                            it.copy(
                                externalMicOwnerPackage = null,
                                externalMicOwnerLabel = null,
                                externalMicOwnerSource = null
                            )
                        }
                        return@execute
                    }
                    val stealthParallel = MeetingRecorderStateBus.isStealthParallelAmbientActive()
                    var sawExemptedStealthClient = false
                    val filteredClients = snapshot.clients.filter { client ->
                        if (client.isSelf) return@filter false
                        val isRemoteSubmix = client.audioSource.equals("REMOTE_SUBMIX", ignoreCase = true)
                        if (isRemoteSubmix) {
                            if (stealthParallel) sawExemptedStealthClient = true
                            return@filter false
                        }
                        if (stealthParallel) {
                            if (client.packageName == "com.android.shell" ||
                                MeetingRecorderStateBus.isIsolatedStealthTargetPackage(client.packageName)
                            ) {
                                sawExemptedStealthClient = true
                                return@filter false
                            }
                        }
                        true
                    }
                    val ext = filteredClients.firstOrNull { it.isActive }
                        ?: filteredClients.firstOrNull()
                    val prevPkg = lastShizukuExternalClient?.packageName
                    val prevExempted = isExternalClientExemptedStealth
                    val nowExempted = stealthParallel && sawExemptedStealthClient && ext == null
                    lastShizukuExternalClient = ext
                    isExternalClientExemptedStealth = nowExempted
                    CaptureStateBus.update {
                        it.copy(
                            externalMicOwnerPackage = ext?.packageName,
                            externalMicOwnerLabel = ext?.appLabel,
                            externalMicOwnerSource = ext?.audioSource
                        )
                    }
                    if ((ext != null && ext.packageName != prevPkg) || nowExempted != prevExempted) {
                        if (ext != null) {
                            Log.i(TAG, "Shizuku dumpsys mic owner: ${ext.appLabel} (${ext.packageName}, src=${ext.audioSource})")
                        } else if (nowExempted) {
                            Log.i(TAG, "Shizuku dumpsys confirmed only exempted Stealth client active -> keeping Ambient Brain listening")
                        }
                        onRecordingStateChanged?.invoke()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Async Shizuku mic owner inspection failed: ${e.message}")
                } finally {
                    shizukuInspectInFlight.set(false)
                }
            }
        }
        if (submitted.isFailure) {
            shizukuInspectInFlight.set(false)
        }
    }

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        val nowInCall = isCallAudioMode(mode)
        if (nowInCall != isInCallMode) {
            isInCallMode = nowInCall
            val eventType = if (nowInCall) "CALL_STARTED" else "CALL_ENDED"
            val now = System.currentTimeMillis()
            synchronized(callEvents) {
                callEvents.add(
                    CallMarkerEvent(
                        type = eventType,
                        epochMs = now,
                        sessionOffsetMs = (now - sessionStartEpochMs).coerceAtLeast(0L),
                        audioMode = mode
                    )
                )
            }
            Log.i(TAG, "Audio mode changed to $mode ($eventType)")
            // Trigger stream reopen if switching into/out of call mode so HAL picks up the active route
            needsStreamReopen = true
            CaptureStateBus.update {
                it.copy(isInPhoneCall = nowInCall)
            }
            onRecordingStateChanged?.invoke()
        }
    }

    private fun processRecordingConfigs(configs: List<AudioRecordingConfiguration>) {
        val mySessionId = activeAudioSessionId
        val nowMs = System.currentTimeMillis()
        val recentReleasedId = if (nowMs - lastReleasedEpochMs <= 1_000L) lastReleasedSessionId else 0
        val stealthSessionId = MeetingRecorderStateBus.activeStealthAudioSessionId()
        val stealthParallel = MeetingRecorderStateBus.isStealthParallelAmbientActive()

        val myConfig = if (mySessionId != 0) {
            configs.firstOrNull { it.clientAudioSessionId == mySessionId }
        } else {
            null
        }
        val silenced = myConfig?.isClientSilenced ?: false

        val otherConfigs = configs.filter { cfg ->
            val sid = cfg.clientAudioSessionId
            if (sid == mySessionId || sid == recentReleasedId) return@filter false
            // Exclude our own Stealth ADB runner session
            if (stealthSessionId > 0 && sid == stealthSessionId) return@filter false
            // Virtual submix loopback never opens the physical microphone hardware
            if (cfg.clientAudioSource == MediaRecorder.AudioSource.REMOTE_SUBMIX) return@filter false
            // When Stealth Virtual Silent Mic is routing the isolated app onto TYPE_REMOTE_SUBMIX,
            // it does not touch the physical microphone either
            if (stealthParallel && cfg.audioDevice?.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
                return@filter false
            }
            true
        }
        val hasExternal = otherConfigs.isNotEmpty()
        val externalCount = otherConfigs.size

        val silencedChanged = silenced != isSilencedBySystem
        val externalChanged = hasExternal != externalRecordingActive || externalCount != externalRecordingCount

        isSilencedBySystem = silenced
        externalRecordingActive = hasExternal
        externalRecordingCount = externalCount

        if (!hasExternal && !silenced) {
            if (lastShizukuExternalClient != null) {
                lastShizukuExternalClient = null
            }
            isExternalClientExemptedStealth = false
        } else {
            if (!stealthParallel) {
                isExternalClientExemptedStealth = false
            }
            if (silencedChanged || externalChanged || lastShizukuExternalClient == null) {
                scheduleShizukuMicOwnerInspection(configs)
            }
        }

        if (silencedChanged || externalChanged) {
            if (silencedChanged) {
                Log.w(TAG, "AudioRecordingCallback: mySession=$mySessionId silenced=$silenced externalClients=$externalCount")
            } else {
                Log.i(TAG, "AudioRecordingCallback: externalRecordingActive=$hasExternal externalClients=$externalCount")
            }
            CaptureStateBus.update {
                it.copy(
                    isMicSilencedByOs = silenced,
                    isExternalMicActive = hasExternal,
                    externalMicOwnerPackage = if (hasExternal || silenced) lastShizukuExternalClient?.packageName else null,
                    externalMicOwnerLabel = if (hasExternal || silenced) lastShizukuExternalClient?.appLabel else null,
                    externalMicOwnerSource = if (hasExternal || silenced) lastShizukuExternalClient?.audioSource else null
                )
            }
            onRecordingStateChanged?.invoke()
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            processRecordingConfigs(configs)
        }
    }

    private var initialDeviceCallbackDone = false
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
            if (!initialDeviceCallbackDone) {
                initialDeviceCallbackDone = true
                return
            }
            val hasInput = addedDevices?.any { it.isSource } == true
            if (hasInput) {
                Log.i(TAG, "Input audio device added -> scheduling stream reopen")
                needsStreamReopen = true
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
            val hasInput = removedDevices?.any { it.isSource } == true
            if (hasInput) {
                Log.i(TAG, "Input audio device removed -> scheduling stream reopen")
                needsStreamReopen = true
            }
        }
    }

    fun startMonitoring() {
        val currentMode = audioManager.mode
        isInCallMode = isCallAudioMode(currentMode)
        initialDeviceCallbackDone = false
        val exec = getOrCreateExecutor()
        runCatching {
            audioManager.addOnModeChangedListener(exec, modeListener)
        }
        runCatching {
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        }
        runCatching {
            audioManager.registerAudioRecordingCallback(recordingCallback, mainHandler)
        }
        refreshActiveRecordingState()
        CaptureStateBus.update {
            it.copy(
                isInPhoneCall = isInCallMode,
                isMicSilencedByOs = isSilencedBySystem,
                isExternalMicActive = externalRecordingActive,
                externalMicOwnerPackage = lastShizukuExternalClient?.packageName,
                externalMicOwnerLabel = lastShizukuExternalClient?.appLabel,
                externalMicOwnerSource = lastShizukuExternalClient?.audioSource
            )
        }
    }

    fun stopMonitoring() {
        onRecordingStateChanged = null
        runCatching {
            audioManager.removeOnModeChangedListener(modeListener)
        }
        runCatching {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        }
        runCatching {
            audioManager.unregisterAudioRecordingCallback(recordingCallback)
        }
        isSilencedBySystem = false
        externalRecordingActive = false
        externalRecordingCount = 0
        lastShizukuExternalClient = null
        isExternalClientExemptedStealth = false
        CaptureStateBus.update {
            it.copy(
                isMicSilencedByOs = false,
                isExternalMicActive = false,
                externalMicOwnerPackage = null,
                externalMicOwnerLabel = null,
                externalMicOwnerSource = null
            )
        }
    }

    fun shutdown() {
        stopMonitoring()
        runCatching { executor?.shutdownNow() }
        executor = null
        runCatching { shizukuInspectExecutor?.shutdownNow() }
        shizukuInspectExecutor = null
    }

    fun refreshActiveRecordingState(): Boolean {
        val currentMode = audioManager.mode
        val nowInCall = isCallAudioMode(currentMode)
        if (nowInCall != isInCallMode) {
            isInCallMode = nowInCall
            CaptureStateBus.update { it.copy(isInPhoneCall = nowInCall) }
        }
        val configs = runCatching { audioManager.activeRecordingConfigurations }.getOrNull()
        if (configs != null) {
            processRecordingConfigs(configs)
        }
        return externalRecordingActive
    }

    fun onNewSessionStarted(startEpochMs: Long) {
        sessionStartEpochMs = startEpochMs
        synchronized(callEvents) {
            callEvents.clear()
            if (isInCallMode) {
                callEvents.add(
                    CallMarkerEvent(
                        type = "CALL_ACTIVE_AT_START",
                        epochMs = startEpochMs,
                        sessionOffsetMs = 0L,
                        audioMode = audioManager.mode
                    )
                )
            }
        }
    }

    fun getCallEventsSnapshot(): Pair<Boolean, String?> {
        synchronized(callEvents) {
            if (callEvents.isEmpty() && !isInCallMode) return false to null
            return true to Json.encodeToString(callEvents.toList())
        }
    }

    @SuppressLint("MissingPermission")
    fun openResilientAudioRecord(sampleRate: Int = 16000): Pair<AudioRecord?, String> {
        val rawMinBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(DirectPcmBufferPool.FRAME_BYTES_512_SAMPLES * 2)
        // Ensure HAL ring buffer is an integer multiple of both getMinBufferSize() and our 32ms (1024B) read chunk
        val alignedBufBytes = ((rawMinBuf * 4 + DirectPcmBufferPool.FRAME_BYTES_512_SAMPLES - 1) /
            DirectPcmBufferPool.FRAME_BYTES_512_SAMPLES) * DirectPcmBufferPool.FRAME_BYTES_512_SAMPLES

        // VOICE_RECOGNITION is required for AOSP AudioPolicyService::isA11yOnTop concurrent mic sharing
        // and avoids UNPROCESSED raw HAL exclusive mixPort locks that cause "in-use" errors in other apps.
        val hasCaptureAudioOutput = ContextCompat.checkSelfPermission(
            appContext,
            "android.permission.CAPTURE_AUDIO_OUTPUT"
        ) == PackageManager.PERMISSION_GRANTED

        val candidateSources = if (isInCallMode) {
            buildList {
                if (hasCaptureAudioOutput) {
                    add(MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL (Privileged 2-Way)")
                }
                add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION (In-Call)")
                add(MediaRecorder.AudioSource.MIC to "MIC (In-Call)")
                add(MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION (In-Call)")
            }
        } else {
            listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION (A11y Share)",
                MediaRecorder.AudioSource.MIC to "MIC"
            )
        }

        for ((sourceId, sourceName) in candidateSources) {
            try {
                val record = AudioRecord.Builder()
                    .setAudioSource(sourceId)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(alignedBufBytes)
                    .build()

                val started = synchronized(audioRecordLifecycleLock) {
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        activeAudioSessionId = record.audioSessionId
                        record.startRecording()
                        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            activeAudioRecordRef = record
                            true
                        } else {
                            activeAudioSessionId = 0
                            false
                        }
                    } else {
                        false
                    }
                }
                if (started) {
                    needsStreamReopen = false
                    isSilencedBySystem = false
                    CaptureStateBus.update {
                        it.copy(
                            activeAudioSourceName = sourceName,
                            isMicSilencedByOs = false
                        )
                    }
                    refreshActiveRecordingState()
                    return record to sourceName
                }
                synchronized(audioRecordLifecycleLock) {
                    if (activeAudioRecordRef === record) {
                        activeAudioRecordRef = null
                    }
                    runCatching { record.release() }
                }
            } catch (e: Exception) {
                synchronized(audioRecordLifecycleLock) {
                    activeAudioSessionId = 0
                }
                Log.w(TAG, "Source $sourceName failed to open: ${e.message}")
            }
        }
        return null to "NONE"
    }

    fun nudgeStopActiveAudioRecord() {
        synchronized(audioRecordLifecycleLock) {
            val record = activeAudioRecordRef ?: return
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                runCatching { record.stop() }
            }
        }
    }

    fun releaseAudioRecord(record: AudioRecord?) {
        val target: AudioRecord?
        val currentSid = activeAudioSessionId
        synchronized(audioRecordLifecycleLock) {
            target = record ?: activeAudioRecordRef
            if (target != null && target === activeAudioRecordRef) {
                activeAudioRecordRef = null
            }
            activeAudioSessionId = 0
            isSilencedBySystem = false
        }
        if (target == null) {
            CaptureStateBus.update { it.copy(isMicSilencedByOs = false) }
            return
        }
        val sid = runCatching { target.audioSessionId }.getOrDefault(currentSid)
        if (sid != 0) {
            lastReleasedSessionId = sid
            lastReleasedEpochMs = System.currentTimeMillis()
        }
        synchronized(audioRecordLifecycleLock) {
            if (target.state == AudioRecord.STATE_INITIALIZED) {
                runCatching { target.stop() }
            }
            runCatching { target.release() }
        }
        CaptureStateBus.update { it.copy(isMicSilencedByOs = false) }
    }

    private fun isCallAudioMode(mode: Int): Boolean {
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    companion object {
        private const val TAG = "CallRouteGuard"
        private const val SHIZUKU_INSPECT_COOLDOWN_MS = 2_000L
    }
}
