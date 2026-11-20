package com.ambientbrain.recorder.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.ambientbrain.recorder.data.AudioPurgeWorker
import com.ambientbrain.recorder.data.CaptureStateBus
import com.ambientbrain.recorder.data.LiveCaptureStatus
import com.ambientbrain.recorder.data.PreferencesRepo

/**
 * Persistent AccessibilityService (Zero-Notification Mode):
 *  1. Attaches a 1x1 px visible alpha-1 `TYPE_ACCESSIBILITY_OVERLAY` (or `TYPE_APPLICATION_OVERLAY`
 *     fallback when `SYSTEM_ALERT_WINDOW` is granted) while listening is active so Android's
 *     AudioPolicyService honors `isA11yOnTop` for concurrent `VOICE_RECOGNITION` capture during calls.
 *  2. Monitors `TYPE_WINDOW_STATE_CHANGED` packages (with `canRetrieveWindowContent="false"`) to
 *     preemptively yield the hardware mic before Camera or external Recorder apps fail with "in-use".
 *  3. Operates completely silently without any status bar or shade notification.
 */
class AmbientCaptureAccessibilityService : AccessibilityService() {

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var captureEngine: CaptureEngine? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayRetryRunnable: Runnable? = null
    private var overlayRetryAttempt: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        isSystemBound = true
        isRunning = true
        instance = this
        syncA11yTelemetry()
        AudioPurgeWorker.scheduleDaily(applicationContext)

        val prefs = PreferencesRepo(applicationContext)
        if (prefs.isListeningEnabled) {
            Log.i(TAG, "AmbientCaptureAccessibilityService connected -> starting silent listening")
            startListeningInternal()
        } else {
            Log.i(TAG, "AmbientCaptureAccessibilityService connected -> listening is toggled OFF")
            CaptureStateBus.update {
                it.copy(
                    status = LiveCaptureStatus.STOPPED,
                    isListeningEnabled = false,
                    isAccessibilityBound = true,
                    isAccessibilityOverlayAttached = isOverlayAttached,
                    currentSessionElapsedSec = 0L,
                    lastVadProbability = 0f
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isSystemBound) {
            Log.w(TAG, "Ignoring onStartCommand(${intent?.action}) because AccessibilityService is not bound by system")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START_ROUND -> CaptureEngineController.startManualRound()
            ACTION_END_ROUND -> CaptureEngineController.endActiveRoundOrSession()
            ACTION_START_LISTENING -> startListeningInternal()
            ACTION_STOP_LISTENING -> stopListeningInternal()
        }
        return START_STICKY
    }

    fun activeCaptureEngine(): CaptureEngine? = captureEngine

    fun startListeningInternal() {
        val prefs = PreferencesRepo(applicationContext)
        prefs.isListeningEnabled = true
        if (isSystemBound) {
            attachOneByOneOverlay(resetRetries = true)
        }
        val activeEngine = CaptureEngineController.instance
        if (activeEngine != null && activeEngine !== captureEngine) {
            activeEngine.stop(persistDisabled = false)
        }
        val ownEngine = captureEngine
        if (ownEngine != null && !ownEngine.isActiveRunning()) {
            ownEngine.stop(persistDisabled = false)
            captureEngine = null
        }
        if (captureEngine == null) {
            captureEngine = CaptureEngine(applicationContext).also { it.start() }
        } else {
            captureEngine?.start()
        }
        CaptureStateBus.update {
            it.copy(
                status = if (it.status == LiveCaptureStatus.STOPPED) LiveCaptureStatus.VAD_STANDBY else it.status,
                isListeningEnabled = true,
                isAccessibilityBound = isSystemBound,
                isAccessibilityOverlayAttached = isOverlayAttached
            )
        }
    }

    fun stopListeningInternal() {
        CaptureEngineController.setUserManualPaused(false)
        CaptureEngineController.clearForegroundYield()
        val prefs = PreferencesRepo(applicationContext)
        prefs.isListeningEnabled = false
        captureEngine?.stop(persistDisabled = true)
        captureEngine = null
        removeOverlay()
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
                isAccessibilityBound = isSystemBound,
                isAccessibilityOverlayAttached = false,
                currentSessionElapsedSec = 0L,
                lastVadProbability = 0f
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank()) {
                val cls = event.className?.toString()
                val text = event.text?.joinToString(" ") { it?.toString().orEmpty() }.orEmpty()
                CaptureEngineController.onForegroundWindowChanged(
                    context = applicationContext,
                    pkg = pkg,
                    className = cls,
                    isFullScreen = event.isFullScreen,
                    eventText = text
                )
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AmbientCaptureAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isSystemBound = false
        isRunning = false
        if (instance === this) {
            instance = null
        }
        // Preserve prefs.isListeningEnabled across OS/ADB service rebinds while cleanly releasing mic
        captureEngine?.stop(persistDisabled = false)
        captureEngine = null
        removeOverlay()
        notifyAccessibilityUnbound()
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
                isAccessibilityBound = false,
                isAccessibilityOverlayAttached = false,
                currentSessionElapsedSec = 0L,
                lastVadProbability = 0f
            )
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isSystemBound = false
        isRunning = false
        if (instance === this) {
            instance = null
        }
        captureEngine?.stop(persistDisabled = false)
        captureEngine = null
        removeOverlay()
        notifyAccessibilityUnbound()
        syncA11yTelemetry()
        super.onDestroy()
    }

    private fun cancelOverlayRetries() {
        overlayRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        overlayRetryRunnable = null
    }

    private fun syncA11yTelemetry() {
        CaptureStateBus.update {
            it.copy(
                isAccessibilityBound = isSystemBound,
                isAccessibilityOverlayAttached = isOverlayAttached
            )
        }
    }

    private fun attachOneByOneOverlay(resetRetries: Boolean = true) {
        if (resetRetries) {
            cancelOverlayRetries()
            overlayRetryAttempt = 0
        }
        if (!isSystemBound || !isRunning) {
            isOverlayAttached = false
            overlayWindowType = "NONE"
            lastOverlayError = "Service not system-bound"
            syncA11yTelemetry()
            Log.d(TAG, "Skipping 1x1 overlay attach: AccessibilityService not system-bound")
            return
        }
        if (overlayView != null) {
            return
        }

        val wm = getSystemService(WindowManager::class.java)
        if (wm == null) {
            lastOverlayError = "WindowManager unavailable"
            isOverlayAttached = false
            syncA11yTelemetry()
            return
        }
        windowManager = wm

        if (tryAddOverlayWindow(wm, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, "TYPE_ACCESSIBILITY_OVERLAY")) {
            return
        }

        if (overlayRetryAttempt < OVERLAY_RETRY_DELAYS_MS.size) {
            val delayMs = OVERLAY_RETRY_DELAYS_MS[overlayRetryAttempt]
            overlayRetryAttempt++
            val runnable = Runnable {
                overlayRetryRunnable = null
                if (isSystemBound && isRunning && PreferencesRepo(applicationContext).isListeningEnabled) {
                    attachOneByOneOverlay(resetRetries = false)
                }
            }
            overlayRetryRunnable = runnable
            mainHandler.postDelayed(runnable, delayMs)
            return
        }

        // Retries exhausted on TYPE_ACCESSIBILITY_OVERLAY; attempt TYPE_APPLICATION_OVERLAY fallback if permitted
        if (runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)) {
            if (tryAddOverlayWindow(wm, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, "TYPE_APPLICATION_OVERLAY")) {
                return
            }
        }
        syncA11yTelemetry()
    }

    private fun tryAddOverlayWindow(wm: WindowManager, windowType: Int, typeLabel: String): Boolean {
        val view = View(this).apply {
            // 1/255 alpha ensures SurfaceFlinger composites an active layer instead of culling alpha=0
            setBackgroundColor(Color.argb(1, 0, 0, 0))
            visibility = View.VISIBLE
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (v === overlayView) {
                        isOverlayAttached = true
                        syncA11yTelemetry()
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {
                    if (v === overlayView) {
                        isOverlayAttached = false
                        overlayView = null
                        overlayWindowType = "NONE"
                        syncA11yTelemetry()
                    }
                }
            })
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            title = "AmbientBrainCaptureOverlay"
        }

        return try {
            overlayView = view
            wm.addView(view, params)
            isOverlayAttached = true
            overlayWindowType = typeLabel
            lastOverlayError = null
            syncA11yTelemetry()
            Log.i(TAG, "1x1 $typeLabel attached")
            true
        } catch (e: WindowManager.BadTokenException) {
            overlayView = null
            isOverlayAttached = false
            overlayWindowType = "NONE"
            lastOverlayError = "BadTokenException ($typeLabel): ${e.message ?: "window token unavailable"}"
            Log.w(TAG, "Overlay $typeLabel failed with BadTokenException (attempt $overlayRetryAttempt): ${e.message}")
            syncA11yTelemetry()
            false
        } catch (e: Exception) {
            overlayView = null
            isOverlayAttached = false
            overlayWindowType = "NONE"
            lastOverlayError = "${e::class.java.simpleName}: ${e.message ?: "failed"}"
            Log.e(TAG, "Failed to attach 1x1 $typeLabel overlay", e)
            syncA11yTelemetry()
            false
        }
    }

    private fun removeOverlay() {
        cancelOverlayRetries()
        overlayRetryAttempt = 0
        val view = overlayView
        overlayView = null
        if (view != null) {
            runCatching { windowManager?.removeViewImmediate(view) }
                .onFailure { runCatching { windowManager?.removeView(view) } }
            Log.i(TAG, "1x1 overlay ($overlayWindowType) removed")
        }
        isOverlayAttached = false
        overlayWindowType = "NONE"
        syncA11yTelemetry()
    }

    companion object {
        private const val TAG = "AmbientCaptureA11y"
        const val ACTION_START_ROUND = "com.ambientbrain.recorder.ACTION_START_ROUND"
        const val ACTION_END_ROUND = "com.ambientbrain.recorder.ACTION_END_ROUND"
        const val ACTION_START_LISTENING = "com.ambientbrain.recorder.ACTION_START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.ambientbrain.recorder.ACTION_STOP_LISTENING"

        private val OVERLAY_RETRY_DELAYS_MS = longArrayOf(250L, 600L, 1_200L, 2_500L)

        @Volatile
        var isRunning: Boolean = false
            internal set

        @Volatile
        var isSystemBound: Boolean = false
            private set

        @Volatile
        var isOverlayAttached: Boolean = false
            private set

        @Volatile
        var overlayWindowType: String = "NONE"
            private set

        @Volatile
        var lastOverlayError: String? = null
            private set

        @Volatile
        var instance: AmbientCaptureAccessibilityService? = null
            private set

        @Volatile
        var onAccessibilityUnbound: (() -> Unit)? = null

        private fun notifyAccessibilityUnbound() {
            runCatching { onAccessibilityUnbound?.invoke() }
        }

        fun startListening(context: Context): Boolean {
            val prefs = PreferencesRepo(context)
            prefs.isListeningEnabled = true
            val svc = instance
            return if (svc != null && isSystemBound) {
                svc.startListeningInternal()
                true
            } else {
                false
            }
        }

        fun stopListening(context: Context) {
            CaptureEngineController.setUserManualPaused(false)
            CaptureEngineController.clearForegroundYield()
            val prefs = PreferencesRepo(context)
            prefs.isListeningEnabled = false
            val svc = instance
            if (svc != null) {
                svc.stopListeningInternal()
            } else {
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
        }
    }
}
