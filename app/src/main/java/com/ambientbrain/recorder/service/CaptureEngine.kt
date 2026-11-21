package com.ambientbrain.recorder.service

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.ambientbrain.recorder.ai.SessionAnalyzeWorker
import com.ambientbrain.recorder.data.AppDatabase
import com.ambientbrain.recorder.data.CaptureStateBus
import com.ambientbrain.recorder.data.LiveCaptureStatus
import com.ambientbrain.recorder.data.MicSharingMode
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SegmentMapEntity
import com.ambientbrain.recorder.data.SessionEntity
import com.ambientbrain.recorder.data.SessionMode
import com.ambientbrain.recorder.data.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object CaptureEngineController {
    private const val TAG = "CaptureEngineCtrl"
    private const val YIELD_EXIT_DEBOUNCE_MS = 600L
    private const val INTENT_DISCOVERY_REFRESH_MS = 60_000L
    private const val USAGE_STATS_POLL_INTERVAL_MS = 250L

    private val KNOWN_YIELD_PACKAGES = setOf(
        // Google / AOSP / LineageOS
        "com.google.android.apps.recorder",
        "com.google.android.GoogleCamera",
        "com.android.camera",
        "com.android.camera2",
        "com.android.soundrecorder",
        "org.lineageos.recorder",
        "org.lineageos.aperture",
        // Xiaomi / Redmi / Poco / MIUI / HyperOS
        "com.miui.camera",
        "com.miui.screenrecorder",
        "com.xiaomi.recorder",
        "com.miui.soundrecorder",
        "com.miui.notes",
        // Samsung OneUI
        "com.sec.android.app.voicenote",
        "com.sec.android.app.camera",
        "com.samsung.android.app.screenrecorder",
        "com.samsung.android.app.notes",
        "com.sec.android.app.notes",
        // Oppo / OnePlus / Realme / ColorOS / OxygenOS
        "com.oplus.camera",
        "com.oneplus.camera",
        "com.oppo.camera",
        "com.oplus.recorder",
        "com.oplus.soundrecorder",
        "com.oneplus.soundrecorder",
        "com.oppo.soundrecorder",
        "com.coloros.soundrecorder",
        "com.realme.soundrecorder",
        "com.oplus.screenrecorder",
        "com.oneplus.screenrecorder",
        "com.coloros.screenrecorder",
        "com.realme.screenrecorder",
        // Vivo / iQOO
        "com.vivo.camera",
        "com.android.bbksoundrecorder",
        "com.vivo.soundrecorder",
        "com.iqoo.soundrecorder",
        "com.vivo.screenrecorder",
        // Sony / Motorola / Huawei / Honor / Asus / LG / Nothing / Transsion
        "com.sonyericsson.android.camera",
        "com.sonymobile.android.recorder",
        "com.motorola.camera3",
        "com.motorola.camera2",
        "com.motorola.audiorecorder",
        "com.huawei.camera",
        "com.huawei.soundrecorder",
        "com.hihonor.camera",
        "com.hihonor.soundrecorder",
        "com.nothing.camera",
        "com.nothing.soundrecorder",
        "com.asus.camera",
        "com.asus.soundrecorder",
        "com.lge.camera",
        "com.lge.voicerecorder",
        "com.transsion.camera",
        "com.transsion.soundrecorder",
        // Popular 3rd-party Camera / Recorder / Voice Note apps
        "net.sourceforge.opencamera",
        "com.andrwq.recorder",
        "com.coffeebeanventures.easyvoicerecorder",
        "com.coffeebeanventures.easyvoicerecorder.pro",
        "com.splendapps.voicerec",
        "com.media.bestrecorder.audiorecorder",
        "com.dimowner.audiorecorder",
        "com.first75.voicerecorder2",
        "com.nll.asr",
        "com.nll.cb",
        "com.otter.android",
        "com.aisense.otter",
        "ai.notta.app",
        "ai.plaud.android",
        "com.dolby.dolby234",
        "com.bandlab.bandlab",
        "com.kimcy929.screenrecorder",
        "videoeditor.videorecorder.screenrecorder",
        "com.hecorat.screenrecorder.free",
        "com.shazam.android",
        "com.melodis.midomiMusicIdentifier.freemium"
    )

    private val FALLBACK_LAUNCHER_PACKAGES = setOf(
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.sec.android.app.launcher",
        "com.android.launcher",
        "com.android.launcher3",
        "com.oppo.launcher",
        "com.oneplus.launcher",
        "com.bbk.launcher2",
        "com.huawei.android.launcher",
        "com.hihonor.android.launcher"
    )

    private val IGNORED_CORE_PACKAGES_FOR_REGEX = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.vending"
    )

    private val TRANSIENT_OVERLAY_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.samsung.android.permissioncontroller",
        "com.miui.securitycenter",
        "com.lbe.security.miui",
        "com.miui.freeform",
        "com.miui.aod",
        "com.xiaomi.mirror",
        "com.oplus.exserviceui",
        "com.coloros.safecenter"
    )

    private val DIALER_OR_TELECOM_REGEX = Regex(
        "(dialer|telecom|incallui|server\\.telecom)",
        RegexOption.IGNORE_CASE
    )

    private val IME_PACKAGE_REGEX = Regex(
        "(inputmethod|gboard|honeyboard|swiftkey|sogou\\.input|baidu\\.input)",
        RegexOption.IGNORE_CASE
    )

    private val PREEMPTIVE_YIELD_PKG_REGEX = Regex(
        "(?:^|[._-])(camera\\d*|googlecamera|opencamera|gcam)(?:$|[._-])|" +
            "(camcorder|recorder|voicerec|soundrec|audiorec|screenrec|screencast|" +
            "voicenote|voicememo|audiomemo|dictaphone|audionote|callrec|" +
            "miui\\.notes|samsung\\.android\\.app\\.notes)",
        RegexOption.IGNORE_CASE
    )

    private val PREEMPTIVE_YIELD_CLASS_REGEX = Regex(
        "(camera\\w*activity|camcorder\\w*|screenrecord\\w*|voicerecord\\w*|soundrecord\\w*|audiorecord\\w*|recordingactivity)",
        RegexOption.IGNORE_CASE
    )

    private val LABEL_YIELD_KEYWORDS = listOf(
        "recorder",
        "voice memo",
        "voice note",
        "sound note",
        "audio note",
        "audio memo",
        "dictaphone",
        "screen record",
        "camcorder",
        "camera",
        "บันทึกเสียง",
        "เครื่องบันทึก",
        "อัดเสียง",
        "บันทึกหน้าจอ",
        "กล้อง",
        "grabadora",
        "enregistreur",
        "rekorder",
        "perekam",
        "rekam suara",
        "录音",
        "録音",
        "녹음"
    )

    private val micStateLock = ReentrantLock()
    private val micReleasedCondition = micStateLock.newCondition()
    private val micHolders = Collections.newSetFromMap(IdentityHashMap<CaptureEngine, Boolean>())

    private val externalPauseHoldCount = AtomicInteger(0)
    private val userManualPaused = AtomicBoolean(false)

    private val yieldStateLock = Any()
    private val intentDiscoveryLock = Any()
    private val appLabelCache = ConcurrentHashMap<String, String>()
    private val recordAudioGrantCache = ConcurrentHashMap<String, Boolean>()
    private val learnedYieldPackages = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var discoveredHomePackages: Set<String> = emptySet()

    @Volatile
    private var discoveredCaptureIntentPackages: Set<String> = emptySet()

    @Volatile
    private var lastIntentDiscoveryMonoMs: Long = 0L

    private val nudgeExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AmbientBrain-MicYieldNudge").apply {
            isDaemon = true
        }
    }

    @Volatile
    var instance: CaptureEngine? = null

    @Volatile
    var externalPauseReason: String? = null
        private set

    @Volatile
    var yieldForegroundPackage: String? = null
        private set

    @Volatile
    private var yieldForegroundLabel: String? = null

    @Volatile
    private var pendingExitFromYieldMonoMs: Long = 0L

    @Volatile
    private var lastObservedNonOverlayPkg: String? = null

    @Volatile
    private var lastA11yWindowEventMonoMs: Long = 0L

    @Volatile
    private var lastA11yWindowEventWallMs: Long = 0L

    @Volatile
    private var lastUsagePollMonoMs: Long = 0L

    @Volatile
    private var lastProcessedUsageEventWallMs: Long = 0L

    private fun triggerAsyncMicNudge() {
        val engine = instance ?: return
        runCatching {
            nudgeExecutor.execute {
                engine.nudgeAudioStopIfBlocked()
            }
        }
    }

    internal fun requestMicNudgeFromCallback(targetEngine: CaptureEngine) {
        runCatching {
            nudgeExecutor.execute {
                targetEngine.nudgeAudioStopIfBlocked()
            }
        }
    }

    internal fun warmCachesAsync(context: Context) {
        val appCtx = context.applicationContext
        runCatching {
            nudgeExecutor.execute {
                ensureIntentDiscoveryCache(appCtx)
            }
        }
    }

    internal fun registerEngine(engine: CaptureEngine) {
        instance = engine
    }

    internal fun unregisterEngine(engine: CaptureEngine) {
        notifyMicHardwareState(engine, holding = false)
        if (instance === engine) {
            instance = null
        }
    }

    internal fun notifyMicHardwareState(engine: CaptureEngine, holding: Boolean) {
        micStateLock.withLock {
            if (holding) {
                micHolders.add(engine)
            } else if (micHolders.remove(engine)) {
                micReleasedCondition.signalAll()
            }
        }
    }

    internal fun awaitAnyOtherMicReleased(excludingEngine: CaptureEngine, timeoutMs: Long = 1_500L): Boolean {
        var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
        micStateLock.withLock {
            while (remainingNs > 0L && micHolders.any { it !== excludingEngine }) {
                try {
                    remainingNs = micReleasedCondition.awaitNanos(remainingNs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return micHolders.none { it !== excludingEngine }
        }
    }

    fun acquireExternalMicPause(reason: String, timeoutMs: Long = 1_500L) {
        externalPauseHoldCount.incrementAndGet()
        externalPauseReason = reason
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(50L))
        var nudged = false
        while (true) {
            var toNudge: List<CaptureEngine> = emptyList()
            val done = micStateLock.withLock {
                if (micHolders.isEmpty()) return@withLock true
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return@withLock true
                val waitNs = if (!nudged) minOf(remainingNs, TimeUnit.MILLISECONDS.toNanos(120L)) else remainingNs
                try {
                    micReleasedCondition.awaitNanos(waitNs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@withLock true
                }
                if (micHolders.isNotEmpty() && !nudged) {
                    nudged = true
                    toNudge = micHolders.toList()
                }
                micHolders.isEmpty()
            }
            if (toNudge.isNotEmpty()) {
                toNudge.forEach { it.nudgeAudioStopIfBlocked() }
            }
            if (done) break
        }
    }

    fun releaseExternalMicPause() {
        val remaining = externalPauseHoldCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (remaining == 0) {
            externalPauseReason = null
        }
    }

    fun setUserManualPaused(paused: Boolean) {
        userManualPaused.set(paused)
        if (paused) {
            triggerAsyncMicNudge()
        }
    }

    fun isUserManualPaused(): Boolean = userManualPaused.get()

    fun onForegroundPackageChanged(context: Context, pkg: String?) {
        onForegroundWindowChanged(
            context = context,
            pkg = pkg,
            className = null,
            isFullScreen = true,
            eventText = ""
        )
    }

    fun onForegroundWindowChanged(
        context: Context,
        pkg: String?,
        className: String?,
        isFullScreen: Boolean,
        eventText: String = "",
        fromUsageStats: Boolean = false
    ) {
        val packageName = pkg?.trim().orEmpty()
        if (packageName.isEmpty()) return

        val nowMono = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        if (!fromUsageStats) {
            lastA11yWindowEventMonoMs = nowMono
            lastA11yWindowEventWallMs = nowWall
        }

        val appCtx = context.applicationContext
        val cls = className?.trim().orEmpty()
        val ownPkg = appCtx.packageName

        // Catch AOSP/Pixel SystemUI Screen Recorder dialog before ignoring SystemUI overlays
        val isSystemUiScreenRecorder = packageName == "com.android.systemui" &&
            (cls.contains("screenrecord", ignoreCase = true) ||
                eventText.contains("screen record", ignoreCase = true) ||
                eventText.contains("screenrecord", ignoreCase = true) ||
                eventText.contains("บันทึกหน้าจอ", ignoreCase = true))

        if (isSystemUiScreenRecorder) {
            synchronized(yieldStateLock) {
                yieldForegroundPackage = packageName
                yieldForegroundLabel = "Screen Recorder"
                pendingExitFromYieldMonoMs = 0L
            }
            Log.i(TAG, "Preemptive mic yield -> SystemUI Screen Recorder ($cls)")
            triggerAsyncMicNudge()
            return
        }

        // Ignore our own 1x1 Accessibility overlay / Toast events so they never clear an active yield;
        // genuine returns to Ambient Brain are handled cleanly by MainActivity ON_RESUME or fullscreen MainActivity.
        if (packageName == ownPkg) {
            if ((fromUsageStats || isFullScreen) && cls.contains("MainActivity", ignoreCase = true)) {
                onOwnActivityResumed(ownPkg)
            }
            return
        }

        val sharingMode = PreferencesRepo(appCtx).micSharingMode
        if (isTransientOverlayWindow(appCtx, packageName, cls, isFullScreen, sharingMode)) {
            return
        }

        if (MeetingRecorderStateBus.isStealthParallelAmbientActive() &&
            MeetingRecorderStateBus.isIsolatedStealthTargetPackage(packageName)
        ) {
            synchronized(yieldStateLock) {
                lastObservedNonOverlayPkg = packageName
                if (yieldForegroundPackage == packageName) {
                    yieldForegroundPackage = null
                    yieldForegroundLabel = null
                    pendingExitFromYieldMonoMs = 0L
                }
            }
            return
        }

        if (sharingMode != MicSharingMode.FORCE_CONCURRENT &&
            isYieldTargetApp(appCtx, packageName, cls, sharingMode)
        ) {
            val label = friendlyYieldAppLabel(appCtx, packageName, cls)
            val changed = synchronized(yieldStateLock) {
                lastObservedNonOverlayPkg = packageName
                val prev = yieldForegroundPackage
                yieldForegroundPackage = packageName
                yieldForegroundLabel = label
                pendingExitFromYieldMonoMs = 0L
                prev != packageName
            }
            if (changed) {
                Log.i(TAG, "Preemptive mic yield -> $packageName ($label, class=$cls, usageFallback=$fromUsageStats)")
            }
            triggerAsyncMicNudge()
            return
        }

        synchronized(yieldStateLock) {
            lastObservedNonOverlayPkg = packageName
            if (yieldForegroundPackage != null && pendingExitFromYieldMonoMs == 0L) {
                pendingExitFromYieldMonoMs = nowMono
            }
        }
    }

    fun onOwnActivityResumed(ownPkg: String) {
        val clearedPkg = synchronized(yieldStateLock) {
            lastObservedNonOverlayPkg = ownPkg
            val prev = yieldForegroundPackage
            if (prev != null) {
                yieldForegroundPackage = null
                yieldForegroundLabel = null
                pendingExitFromYieldMonoMs = 0L
            }
            prev
        }
        if (clearedPkg != null) {
            Log.i(TAG, "Own activity resumed -> clearing foreground yield ($clearedPkg)")
        }
    }

    sealed class ExternalConflictResolution {
        data class YieldToApp(val label: String) : ExternalConflictResolution()
        data object ExemptedStealthClient : ExternalConflictResolution()
        data object UnknownExternalClient : ExternalConflictResolution()
    }

    fun noteExternalConflictForForegroundApp(
        context: Context,
        shizukuClient: ActiveAudioClientInfo? = null
    ): ExternalConflictResolution {
        val appCtx = context.applicationContext
        val stealthParallel = MeetingRecorderStateBus.isStealthParallelAmbientActive()

        // Prefer exact dumpsys audio / AudioFlinger attribution from Shizuku when available
        val resolvedPkg = shizukuClient?.packageName
        if (shizukuClient != null &&
            !shizukuClient.isSelf &&
            resolvedPkg != null &&
            !resolvedPkg.startsWith("uid:") &&
            !resolvedPkg.startsWith("session:")
        ) {
            if (isDialerOrTelecomPackage(resolvedPkg)) {
                return ExternalConflictResolution.UnknownExternalClient
            }
            if (stealthParallel &&
                (resolvedPkg == "com.android.shell" ||
                    MeetingRecorderStateBus.isIsolatedStealthTargetPackage(resolvedPkg))
            ) {
                return ExternalConflictResolution.ExemptedStealthClient
            }
            if (resolvedPkg != appCtx.packageName &&
                resolvedPkg !in setOf("android", "audioserver", "com.android.shell", "com.android.settings", "com.android.vending") &&
                !isHomeLauncherPackage(appCtx, resolvedPkg)
            ) {
                val isForeground = synchronized(yieldStateLock) {
                    lastObservedNonOverlayPkg == resolvedPkg
                }
                val label = shizukuClient.appLabel.ifBlank { friendlyYieldAppLabel(appCtx, resolvedPkg, "") }
                // Only add to persistent foreground yield list when the owner is actually a foreground UI app;
                // background/PiP recorders yield cleanly via active externalRecordingActive & clear immediately on stop.
                if (isForeground && resolvedPkg != "com.android.systemui") {
                    learnedYieldPackages.add(resolvedPkg)
                    synchronized(yieldStateLock) {
                        yieldForegroundPackage = resolvedPkg
                        yieldForegroundLabel = label
                        pendingExitFromYieldMonoMs = 0L
                    }
                }
                Log.i(TAG, "Resolved conflicting mic app via Shizuku -> $resolvedPkg ($label, fg=$isForeground)")
                return ExternalConflictResolution.YieldToApp("$label (Shizuku)")
            }
        }

        if (!AmbientCaptureAccessibilityService.isSystemBound || lastObservedNonOverlayPkg == null) {
            pollForegroundViaUsageStatsIfNeeded(appCtx, forceImmediate = true)
        }
        val candidate = synchronized(yieldStateLock) {
            lastObservedNonOverlayPkg ?: yieldForegroundPackage
        } ?: return ExternalConflictResolution.UnknownExternalClient

        if (stealthParallel &&
            (candidate == "com.android.shell" ||
                MeetingRecorderStateBus.isIsolatedStealthTargetPackage(candidate))
        ) {
            return ExternalConflictResolution.ExemptedStealthClient
        }
        if (candidate == appCtx.packageName) return ExternalConflictResolution.UnknownExternalClient
        if (isDialerOrTelecomPackage(candidate)) return ExternalConflictResolution.UnknownExternalClient
        if (isHomeLauncherPackage(appCtx, candidate)) return ExternalConflictResolution.UnknownExternalClient
        if (candidate in IGNORED_CORE_PACKAGES_FOR_REGEX) return ExternalConflictResolution.UnknownExternalClient

        learnedYieldPackages.add(candidate)
        val label = friendlyYieldAppLabel(appCtx, candidate, "")
        synchronized(yieldStateLock) {
            yieldForegroundPackage = candidate
            yieldForegroundLabel = label
            pendingExitFromYieldMonoMs = 0L
        }
        Log.i(TAG, "Learned conflicting mic app from OS audio event -> $candidate ($label)")
        return ExternalConflictResolution.YieldToApp(label)
    }

    fun pollForegroundViaUsageStatsIfNeeded(context: Context, forceImmediate: Boolean = false) {
        val appCtx = context.applicationContext
        val a11yBound = AmbientCaptureAccessibilityService.isSystemBound
        val nowMono = SystemClock.elapsedRealtime()
        val hasYield = yieldForegroundPackage != null

        val needsPoll = forceImmediate ||
            !a11yBound ||
            (hasYield && nowMono - lastA11yWindowEventMonoMs >= 1_000L)
        if (!needsPoll) return

        if (!forceImmediate && nowMono - lastUsagePollMonoMs < USAGE_STATS_POLL_INTERVAL_MS) {
            return
        }
        lastUsagePollMonoMs = nowMono

        if (!ShizukuAdbManager.isUsageStatsAppOpAllowed(appCtx)) {
            return
        }

        val usm = appCtx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val endWall = System.currentTimeMillis()
        val lookbackFloorWall = endWall - 120_000L
        val startWall = if (forceImmediate || lastProcessedUsageEventWallMs <= 0L) {
            lookbackFloorWall
        } else {
            maxOf(lookbackFloorWall, lastProcessedUsageEventWallMs - 2_000L)
        }

        val events = runCatching { usm.queryEvents(startWall, endWall) }.getOrNull() ?: return
        val event = UsageEvents.Event()
        var latestPkg: String? = null
        var latestCls: String? = null
        var latestTs = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latestTs) {
                latestTs = event.timeStamp
                latestPkg = event.packageName
                latestCls = event.className
            }
        }

        if (latestPkg.isNullOrBlank() || latestTs <= 0L) return

        if (a11yBound && latestTs <= lastA11yWindowEventWallMs + 200L) {
            if (!hasYield || latestPkg == yieldForegroundPackage || latestTs <= lastProcessedUsageEventWallMs) {
                return
            }
        }

        val needsFallbackAttribution = forceImmediate && lastObservedNonOverlayPkg == null
        if (!needsFallbackAttribution && latestTs <= lastProcessedUsageEventWallMs) {
            return
        }
        lastProcessedUsageEventWallMs = maxOf(lastProcessedUsageEventWallMs, latestTs)

        onForegroundWindowChanged(
            context = appCtx,
            pkg = latestPkg,
            className = latestCls,
            isFullScreen = true,
            eventText = "",
            fromUsageStats = true
        )
    }

    fun getActiveYieldForegroundLabel(): String? {
        val nowMono = SystemClock.elapsedRealtime()
        synchronized(yieldStateLock) {
            val pkg = yieldForegroundPackage ?: return null
            val exitStartedAt = pendingExitFromYieldMonoMs
            if (exitStartedAt > 0L && nowMono - exitStartedAt >= YIELD_EXIT_DEBOUNCE_MS) {
                yieldForegroundPackage = null
                yieldForegroundLabel = null
                pendingExitFromYieldMonoMs = 0L
                return null
            }
            return yieldForegroundLabel ?: pkg
        }
    }

    fun clearForegroundYield() {
        synchronized(yieldStateLock) {
            yieldForegroundPackage = null
            yieldForegroundLabel = null
            pendingExitFromYieldMonoMs = 0L
        }
    }

    private fun ensureIntentDiscoveryCache(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastIntentDiscoveryMonoMs > 0L && now - lastIntentDiscoveryMonoMs < INTENT_DISCOVERY_REFRESH_MS) {
            return
        }
        synchronized(intentDiscoveryLock) {
            val checkNow = SystemClock.elapsedRealtime()
            if (lastIntentDiscoveryMonoMs > 0L && checkNow - lastIntentDiscoveryMonoMs < INTENT_DISCOVERY_REFRESH_MS) {
                return
            }
            val pm = context.packageManager
            val flags = PackageManager.ResolveInfoFlags.of(0L)

            val homes = HashSet<String>()
            runCatching {
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                pm.queryIntentActivities(homeIntent, flags).forEach { ri ->
                    ri.activityInfo?.packageName?.takeIf { it.isNotBlank() }?.let { homes.add(it) }
                }
            }
            if (homes.isNotEmpty()) {
                discoveredHomePackages = homes
            }

            val captureApps = HashSet<String>()
            val actions = listOf(
                MediaStore.Audio.Media.RECORD_SOUND_ACTION,
                MediaStore.ACTION_IMAGE_CAPTURE,
                MediaStore.ACTION_VIDEO_CAPTURE
            )
            for (action in actions) {
                runCatching {
                    pm.queryIntentActivities(Intent(action), flags).forEach { ri ->
                        ri.activityInfo?.packageName?.takeIf { it.isNotBlank() }?.let { captureApps.add(it) }
                    }
                }
            }
            discoveredCaptureIntentPackages = captureApps
            appLabelCache.clear()
            recordAudioGrantCache.clear()
            lastIntentDiscoveryMonoMs = checkNow
        }
    }

    private fun isHomeLauncherPackage(context: Context, pkg: String): Boolean {
        if (pkg in FALLBACK_LAUNCHER_PACKAGES) return true
        ensureIntentDiscoveryCache(context)
        return pkg in discoveredHomePackages
    }

    private fun isDialerOrTelecomPackage(pkg: String): Boolean {
        return DIALER_OR_TELECOM_REGEX.containsMatchIn(pkg) ||
            pkg == "com.android.phone"
    }

    private fun getResolvedAppLabel(context: Context, pkg: String): String {
        appLabelCache[pkg]?.let { return it }
        val resolved = runCatching {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            pm.getApplicationLabel(ai).toString().trim()
        }.getOrDefault("")
        appLabelCache[pkg] = resolved
        return resolved
    }

    private fun hasRecordAudioPermission(context: Context, pkg: String): Boolean {
        recordAudioGrantCache[pkg]?.let { return it }
        val granted = runCatching {
            context.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, pkg) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        recordAudioGrantCache[pkg] = granted
        return granted
    }

    private fun isYieldTargetApp(
        context: Context,
        pkg: String,
        cls: String,
        sharingMode: MicSharingMode
    ): Boolean {
        if (pkg == context.packageName) return false
        if (isDialerOrTelecomPackage(pkg)) return false
        if (isHomeLauncherPackage(context, pkg)) return false
        if (pkg in IGNORED_CORE_PACKAGES_FOR_REGEX) return false

        if (pkg in KNOWN_YIELD_PACKAGES) return true
        if (pkg in learnedYieldPackages) return true

        ensureIntentDiscoveryCache(context)
        if (pkg in discoveredCaptureIntentPackages) return true

        if (PREEMPTIVE_YIELD_PKG_REGEX.containsMatchIn(pkg)) return true
        if (cls.isNotEmpty() && PREEMPTIVE_YIELD_CLASS_REGEX.containsMatchIn(cls)) return true

        val labelLower = getResolvedAppLabel(context, pkg).lowercase()
        if (labelLower.isNotEmpty() && LABEL_YIELD_KEYWORDS.any { labelLower.contains(it) }) {
            return true
        }

        if (sharingMode == MicSharingMode.STRICT_YIELD && hasRecordAudioPermission(context, pkg)) {
            return true
        }
        return false
    }

    private fun isTransientOverlayWindow(
        context: Context,
        pkg: String,
        cls: String,
        isFullScreen: Boolean,
        sharingMode: MicSharingMode
    ): Boolean {
        if (pkg in TRANSIENT_OVERLAY_PACKAGES) return true
        if (IME_PACKAGE_REGEX.containsMatchIn(pkg)) return true
        val lowerPkg = pkg.lowercase()
        if (lowerPkg.contains("permissioncontroller") ||
            lowerPkg.contains("packageinstaller") ||
            lowerPkg.contains("cocktailbar") ||
            lowerPkg.contains("edgepanel")
        ) {
            return true
        }
        if (!isFullScreen && !isHomeLauncherPackage(context, pkg)) {
            val looksLikeDialogOrOverlay = cls.contains("Dialog", ignoreCase = true) ||
                cls.contains("Popup", ignoreCase = true) ||
                cls.contains("Toast", ignoreCase = true) ||
                cls.contains("Sheet", ignoreCase = true) ||
                cls.contains("Overlay", ignoreCase = true) ||
                cls.contains("Menu", ignoreCase = true) ||
                cls.contains("FrameLayout", ignoreCase = true) ||
                cls.contains("LinearLayout", ignoreCase = true) ||
                lowerPkg == "com.google.android.gms"
            if (looksLikeDialogOrOverlay) return true
            if (yieldForegroundPackage != null) {
                val isRealNonYieldSplitActivity = cls.endsWith("Activity") &&
                    !isYieldTargetApp(context, pkg, cls, sharingMode)
                if (!isRealNonYieldSplitActivity) return true
            }
        }
        return false
    }

    private fun friendlyYieldAppLabel(context: Context, pkg: String, cls: String): String {
        val resolved = getResolvedAppLabel(context, pkg)
        if (resolved.isNotBlank()) return resolved
        val combined = "$pkg $cls".lowercase()
        return when {
            combined.contains("camera") || combined.contains("camcorder") -> "Camera"
            combined.contains("screenrecord") -> "Screen Recorder"
            else -> "Recorder App"
        }
    }

    fun startManualRound() {
        instance?.startManualRound()
    }

    fun endActiveRoundOrSession() {
        instance?.endActiveRoundOrSession()
    }

    fun promoteActiveCaptureToAutoRound(roundGroupId: String) {
        instance?.promoteActiveCaptureToAutoRound(roundGroupId)
    }

    fun stop() {
        setUserManualPaused(false)
        clearForegroundYield()
        instance?.stop(persistDisabled = true)
        instance = null
    }
}

/**
 * 24/7 Ambient + Medical Round Capture Engine:
 *  - Runs on a dedicated capture thread elevated to `Process.THREAD_PRIORITY_URGENT_AUDIO`.
 *  - Uses [DirectPcmBufferPool] and `AudioRecord.read(ByteBuffer, size, READ_BLOCKING)` with
 *    explicit `limit(n)` / `position(0)` and `ByteOrder.nativeOrder()` semantics.
 *  - Gates 16 kHz PCM frames through Silero VAD v5 (512-sample = 32 ms window).
 *  - Maintains a 2-second circular pre-roll buffer so speech starts are never clipped.
 *  - Hands filled direct buffers off to [AdtsFileWriter]'s dedicated encoder thread with
 *    zero per-frame heap allocations.
 *  - Supports cooperative mic sharing (Smart Share / Auto-Yield / Force Concurrent), instant
 *    hardware release on yield/handoff/stop, and race-free JNI ONNX teardown.
 */
class CaptureEngine(private val context: Context) {

    private val db = AppDatabase.get(context).dao()
    private val prefs = PreferencesRepo(context)
    private val vad = SileroVadProcessor(context)
    private val callGuard = CallRouteGuard(context)
    private val bufferPool = DirectPcmBufferPool()

    private val loopState = AtomicInteger(LOOP_INIT)
    private val vadClosed = AtomicBoolean(false)
    private val handoffStopRequested = AtomicBoolean(false)

    private var captureExecutor: ExecutorService? = null
    private var scope: CoroutineScope? = null
    private var captureJob: Job? = null

    @Volatile
    private var isRunning: Boolean = false

    @Volatile
    private var currentAudioRecord: AudioRecord? = null

    @Volatile
    private var activeRoundMode: SessionMode = SessionMode.EVERYDAY

    @Volatile
    private var activeRoundGroupId: String? = null

    @Volatile
    private var forceCloseRequested: Boolean = false

    fun isActiveRunning(): Boolean = isRunning && loopState.get() != LOOP_EXITED

    internal fun nudgeAudioStopIfBlocked() {
        callGuard.nudgeStopActiveAudioRecord()
    }

    fun start() {
        if (isRunning || loopState.get() == LOOP_EXITED) return
        isRunning = true
        prefs.isListeningEnabled = true
        CaptureEngineController.registerEngine(this)
        CaptureEngineController.warmCachesAsync(context)
        callGuard.startMonitoring()

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AmbientBrain-UrgentAudioCapture").apply {
                isDaemon = true
            }
        }
        captureExecutor = executor
        val coroutineScope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        scope = coroutineScope

        captureJob = coroutineScope.launch {
            runContinuousCaptureLoop()
        }
    }

    fun stop(persistDisabled: Boolean = true) {
        if (!persistDisabled) {
            handoffStopRequested.set(true)
        }
        isRunning = false
        if (persistDisabled) {
            prefs.isListeningEnabled = false
        }

        // Unblock native AudioRecord.read(READ_BLOCKING) immediately under lifecycle lock
        nudgeAudioStopIfBlocked()

        callGuard.stopMonitoring()

        // Only close VAD / shutdown CallGuard on caller thread if the capture coroutine never started.
        // Once LOOP_RUNNING, the loop's own `finally` block exclusively owns resource cleanup so JNI
        // sessions are never torn down mid-inference.
        if (loopState.compareAndSet(LOOP_INIT, LOOP_EXITED)) {
            if (vadClosed.compareAndSet(false, true)) {
                vad.close()
            }
            callGuard.shutdown()
            bufferPool.drainFilledToFree()
            CaptureEngineController.unregisterEngine(this)
        } else if (CaptureEngineController.instance === this) {
            CaptureEngineController.instance = null
        }

        captureJob?.cancel()
        captureJob = null
        runCatching { scope?.cancel() }
        scope = null
        runCatching { captureExecutor?.shutdown() }
        captureExecutor = null

        if (persistDisabled) {
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

    fun startManualRound() {
        val newGroupId = UUID.randomUUID().toString()
        activeRoundMode = SessionMode.ROUND_MANUAL
        activeRoundGroupId = newGroupId
        forceCloseRequested = true // Finalize any current everyday session and immediately open Round
        Log.i(TAG, "Manual Round started (roundGroupId=$newGroupId)")
    }

    fun endActiveRoundOrSession() {
        activeRoundMode = SessionMode.EVERYDAY
        activeRoundGroupId = null
        forceCloseRequested = true
        Log.i(TAG, "Active Round / Session manually ended -> returning to Everyday mode")
    }

    fun promoteActiveCaptureToAutoRound(roundGroupId: String) {
        if (activeRoundMode == SessionMode.EVERYDAY) {
            activeRoundMode = SessionMode.ROUND_AUTO
            activeRoundGroupId = roundGroupId
            Log.i(TAG, "Promoted active capture to ROUND_AUTO (roundGroupId=$roundGroupId)")
        }
    }

    private suspend fun runContinuousCaptureLoop() {
        if (!loopState.compareAndSet(LOOP_INIT, LOOP_RUNNING)) {
            return
        }
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        }

        var audioRecord: AudioRecord? = null
        var silencedSinceMonoMs = 0L
        var silenceYieldCooldownUntilMonoMs = 0L
        var cachedAppOpAllowed = ShizukuAdbManager.isRecordAudioAppOpAllowed(context)
        var lastAppOpCheckMonoMs = 0L

        fun isRecordAudioAppOpAllowedCached(nowMonoMs: Long): Boolean {
            if (nowMonoMs - lastAppOpCheckMonoMs >= APP_OP_REFRESH_INTERVAL_MS) {
                cachedAppOpAllowed = ShizukuAdbManager.isRecordAudioAppOpAllowed(context)
                lastAppOpCheckMonoMs = nowMonoMs
            }
            return cachedAppOpAllowed
        }

        // Active session state
        var sessionId: String? = null
        var sessionMode: SessionMode = SessionMode.EVERYDAY
        var sessionRoundGroupId: String? = null
        var sessionStartWallMs = 0L
        var sessionStartMonoMs = 0L
        var lastRecordedAudioMonoMs = 0L
        var writer: AdtsFileWriter? = null
        var submittedFileSamples = 0L
        var speechSamplesAccumulated = 0L
        var silenceConsecutiveMs = 0L
        var inSpeechHangover = false
        var wasCompactSkippingSilence = false
        var currentBurstSpeechMs = 0L
        var maxBurstSpeechMs = 0L
        var burstPauseMs = 0L
        var activeEventGroupId: String? = null
        var lastFinalizedEndWallMs = 0L
        val segmentMaps = mutableListOf<SegmentMapEntity>()
        var currentSegmentStartSample = 0L
        var currentSegmentStartWallMs = 0L
        var currentSegmentSamples = 0L
        var lastGcSampleMs = 0L

        fun releaseMicHardwareNow() {
            val rec = audioRecord ?: currentAudioRecord
            audioRecord = null
            currentAudioRecord = null
            callGuard.releaseAudioRecord(rec)
            silencedSinceMonoMs = 0L
            CaptureEngineController.notifyMicHardwareState(this@CaptureEngine, holding = false)
        }

        fun computeActiveYieldReason(nowMonoMs: Long): String? {
            CaptureEngineController.externalPauseReason?.let { return it }
            if (CaptureEngineController.isUserManualPaused()) {
                return MANUAL_PAUSE_REASON
            }
            if (CaptureStateBus.isUiForegroundActive || AmbientCaptureAccessibilityService.isSystemBound) {
                lastAppOpCheckMonoMs = 0L
            } else if (!isRecordAudioAppOpAllowedCached(nowMonoMs)) {
                return BACKGROUND_NO_PRIVILEGE_REASON
            }
            val mode = prefs.micSharingMode
            if (mode == MicSharingMode.FORCE_CONCURRENT) {
                return null
            }
            val stealthParallel = MeetingRecorderStateBus.isStealthParallelAmbientActive()
            CaptureEngineController.getActiveYieldForegroundLabel()?.let { appLabel ->
                return "Yielded to $appLabel"
            }
            if (mode == MicSharingMode.STRICT_YIELD && callGuard.isInCallMode &&
                !(stealthParallel && AmbientCaptureAccessibilityService.isSystemBound)
            ) {
                return "Paused during call"
            }
            if (callGuard.externalRecordingActive &&
                (mode == MicSharingMode.STRICT_YIELD || !callGuard.isInCallMode) &&
                !(stealthParallel && (callGuard.isExternalClientExemptedStealth || callGuard.isStealthInspectionPending))
            ) {
                val resolution = if (!callGuard.isInCallMode || stealthParallel) {
                    CaptureEngineController.noteExternalConflictForForegroundApp(
                        context = context,
                        shizukuClient = callGuard.lastShizukuExternalClient
                    )
                } else {
                    CaptureEngineController.ExternalConflictResolution.UnknownExternalClient
                }
                when (resolution) {
                    is CaptureEngineController.ExternalConflictResolution.ExemptedStealthClient -> Unit
                    is CaptureEngineController.ExternalConflictResolution.YieldToApp ->
                        return "Yielded to ${resolution.label}"
                    CaptureEngineController.ExternalConflictResolution.UnknownExternalClient ->
                        if (!(stealthParallel && callGuard.isInCallMode && AmbientCaptureAccessibilityService.isSystemBound)) {
                            return "Yielded to another app"
                        }
                }
            }
            val checkSilence = (mode == MicSharingMode.STRICT_YIELD && !(stealthParallel && AmbientCaptureAccessibilityService.isSystemBound)) ||
                !callGuard.isInCallMode
            if (checkSilence && callGuard.isSilencedBySystem) {
                val thresholdMs = if (mode == MicSharingMode.STRICT_YIELD) {
                    SILENCE_YIELD_STRICT_MS
                } else {
                    SILENCE_YIELD_SMART_MS
                }
                if (silencedSinceMonoMs == 0L) {
                    silencedSinceMonoMs = nowMonoMs
                } else if (nowMonoMs - silencedSinceMonoMs >= thresholdMs) {
                    val resolution = if (!callGuard.isInCallMode) {
                        CaptureEngineController.noteExternalConflictForForegroundApp(
                            context = context,
                            shizukuClient = callGuard.lastShizukuExternalClient
                        )
                    } else {
                        CaptureEngineController.ExternalConflictResolution.UnknownExternalClient
                    }
                    when (resolution) {
                        is CaptureEngineController.ExternalConflictResolution.ExemptedStealthClient -> Unit
                        is CaptureEngineController.ExternalConflictResolution.YieldToApp ->
                            return "Yielded to ${resolution.label}"
                        CaptureEngineController.ExternalConflictResolution.UnknownExternalClient ->
                            return SILENCE_YIELD_REASON
                    }
                }
            } else {
                silencedSinceMonoMs = 0L
            }
            if (nowMonoMs < silenceYieldCooldownUntilMonoMs) {
                return SILENCE_YIELD_REASON
            }
            return null
        }

        fun computePauseParkReason(nowMonoMs: Long, cooldownUntilMonoMs: Long): String? {
            CaptureEngineController.externalPauseReason?.let { return it }
            if (CaptureEngineController.isUserManualPaused()) {
                return MANUAL_PAUSE_REASON
            }
            if (CaptureStateBus.isUiForegroundActive || AmbientCaptureAccessibilityService.isSystemBound) {
                lastAppOpCheckMonoMs = 0L
            } else if (!isRecordAudioAppOpAllowedCached(nowMonoMs)) {
                return BACKGROUND_NO_PRIVILEGE_REASON
            }
            val mode = prefs.micSharingMode
            if (mode == MicSharingMode.FORCE_CONCURRENT) {
                return null
            }
            val stealthParallel = MeetingRecorderStateBus.isStealthParallelAmbientActive()
            CaptureEngineController.getActiveYieldForegroundLabel()?.let { appLabel ->
                return "Yielded to $appLabel"
            }
            if (mode == MicSharingMode.STRICT_YIELD && callGuard.isInCallMode &&
                !(stealthParallel && AmbientCaptureAccessibilityService.isSystemBound)
            ) {
                return "Paused during call"
            }
            if (callGuard.externalRecordingActive &&
                (mode == MicSharingMode.STRICT_YIELD || !callGuard.isInCallMode) &&
                !(stealthParallel && (callGuard.isExternalClientExemptedStealth || callGuard.isStealthInspectionPending))
            ) {
                val resolution = if (!callGuard.isInCallMode || stealthParallel) {
                    CaptureEngineController.noteExternalConflictForForegroundApp(
                        context = context,
                        shizukuClient = callGuard.lastShizukuExternalClient
                    )
                } else {
                    CaptureEngineController.ExternalConflictResolution.UnknownExternalClient
                }
                when (resolution) {
                    is CaptureEngineController.ExternalConflictResolution.ExemptedStealthClient -> Unit
                    is CaptureEngineController.ExternalConflictResolution.YieldToApp ->
                        return "Yielded to ${resolution.label}"
                    CaptureEngineController.ExternalConflictResolution.UnknownExternalClient ->
                        if (!(stealthParallel && callGuard.isInCallMode && AmbientCaptureAccessibilityService.isSystemBound)) {
                            return "Yielded to another app"
                        }
                }
            }
            if (nowMonoMs < cooldownUntilMonoMs) {
                return SILENCE_YIELD_REASON
            }
            return null
        }

        suspend fun finalizeCurrentSession(preserveEventContinuation: Boolean = false) {
            val activeId = sessionId ?: return
            val activeWriter = writer
            sessionId = null
            writer = null

            activeWriter?.finishAndClose()
            bufferPool.drainFilledToFree()
            val encodedFileDurationMs = activeWriter?.writtenDurationMs ?: 0L
            val submittedDurationMs = (submittedFileSamples * 1000L) / SileroVadProcessor.SAMPLE_RATE
            val recordedAudioDurationMs = if (encodedFileDurationMs > 0L) encodedFileDurationMs else submittedDurationMs
            val speechAccumulatedMs = (speechSamplesAccumulated * 1000L) / SileroVadProcessor.SAMPLE_RATE
            val outFile = activeWriter?.outputFile

            // Use the end timestamp of the last actually recorded audio frame (not System.currentTimeMillis()
            // after waiting 60-120s of silence to auto-split), anchored via monotonic clock delta.
            val activeSpanMonoMs = (lastRecordedAudioMonoMs - sessionStartMonoMs).coerceAtLeast(recordedAudioDurationMs)
            val endWallMs = sessionStartWallMs + activeSpanMonoMs
            val (hadCall, callEventsJson) = callGuard.getCallEventsSnapshot()

            val currentSegmentLengthMs = (currentSegmentSamples * 1000L) / SileroVadProcessor.SAMPLE_RATE
            if (currentSegmentLengthMs > 0L) {
                segmentMaps.add(
                    SegmentMapEntity(
                        sessionId = activeId,
                        fileOffsetMs = (currentSegmentStartSample * 1000L) / SileroVadProcessor.SAMPLE_RATE,
                        wallClockMs = currentSegmentStartWallMs,
                        lengthMs = currentSegmentLengthMs
                    )
                )
            }

            // Discard sessions shorter than user-configured speech length OR lacking a sustained speech burst
            // (prevents scattered ambient clicks/coughs from triggering wasted Gemini calls)
            val configuredMinSpeechMs = prefs.minMeaningfulSpeechSeconds.coerceIn(1, 60) * 1000L
            val minSpeechMs = if (sessionMode == SessionMode.ROUND_MANUAL) 800L else configuredMinSpeechMs
            val requiredBurstMs = minOf(minSpeechMs, MAX_SUSTAINED_BURST_GATE_MS)
            val passesSpeechGate = speechAccumulatedMs >= minSpeechMs && maxBurstSpeechMs >= requiredBurstMs

            if (!passesSpeechGate || outFile == null || !outFile.exists() || outFile.length() < 256L) {
                outFile?.delete()
                Log.i(
                    TAG,
                    "Discarded short/non-burst session $activeId (speech=${speechAccumulatedMs}ms, maxBurst=${maxBurstSpeechMs}ms, min=${minSpeechMs}ms)"
                )
                if (!preserveEventContinuation) {
                    activeEventGroupId = null
                }
            } else {
                // Stamp OS file lastModified to the true recording start time rather than delayed silence-split close time
                if (sessionStartWallMs > 0L) {
                    runCatching { outFile.setLastModified(sessionStartWallMs) }
                }
                val resolvedEventGroupId = sessionRoundGroupId ?: activeEventGroupId ?: activeId
                val entity = SessionEntity(
                    id = activeId,
                    roundGroupId = sessionRoundGroupId,
                    eventGroupId = resolvedEventGroupId,
                    mode = sessionMode,
                    startMs = sessionStartWallMs,
                    endMs = endWallMs,
                    speechMs = recordedAudioDurationMs,
                    hadPhoneCall = hadCall,
                    callEventsJson = callEventsJson,
                    rawPath = outFile.absolutePath,
                    rawBytes = outFile.length(),
                    status = Status.QUEUED
                )
                db.upsertSession(entity)
                if (segmentMaps.isNotEmpty()) {
                    db.insertSegmentMaps(segmentMaps.toList())
                }
                lastFinalizedEndWallMs = endWallMs
                activeEventGroupId = if (preserveEventContinuation) resolvedEventGroupId else null
                Log.i(
                    TAG,
                    "Finalized session $activeId (mode=$sessionMode, eventGroup=$resolvedEventGroupId, speech=${speechAccumulatedMs}ms, audio=${recordedAudioDurationMs}ms, span=${activeSpanMonoMs}ms) -> enqueueing Gemini analysis"
                )
                SessionAnalyzeWorker.enqueue(context, activeId)
            }

            sessionStartWallMs = 0L
            sessionStartMonoMs = 0L
            lastRecordedAudioMonoMs = 0L
            submittedFileSamples = 0L
            speechSamplesAccumulated = 0L
            silenceConsecutiveMs = 0L
            inSpeechHangover = false
            wasCompactSkippingSilence = false
            currentBurstSpeechMs = 0L
            maxBurstSpeechMs = 0L
            burstPauseMs = 0L
            segmentMaps.clear()
            currentSegmentStartSample = 0L
            currentSegmentSamples = 0L
        }

        suspend fun handleYieldAndPause(initialReason: String) {
            // Release physical AudioRecord FIRST before finalizing session (which can block up to 3s closing MediaCodec)
            releaseMicHardwareNow()
            inSpeechHangover = false
            val pauseStartedMonoMs = SystemClock.elapsedRealtime()
            if (initialReason == SILENCE_YIELD_REASON) {
                silenceYieldCooldownUntilMonoMs = pauseStartedMonoMs + SILENCE_YIELD_COOLDOWN_MS
            }
            Log.i(TAG, "Yielding hardware microphone -> PAUSED ($initialReason)")

            CaptureStateBus.update {
                it.copy(
                    status = LiveCaptureStatus.PAUSED,
                    isListeningEnabled = true,
                    currentSessionElapsedSec = 0L,
                    lastVadProbability = 0f,
                    isInPhoneCall = callGuard.isEffectivePhoneCall,
                    isMicSilencedByOs = false,
                    isExternalMicActive = callGuard.externalRecordingActive,
                    pauseReason = initialReason,
                    isAccessibilityBound = AmbientCaptureAccessibilityService.isSystemBound,
                    isAccessibilityOverlayAttached = AmbientCaptureAccessibilityService.isOverlayAttached,
                    micSharingMode = prefs.micSharingMode,
                    lastHeartbeatMs = System.currentTimeMillis()
                )
            }

            withContext(NonCancellable) {
                finalizeCurrentSession()
            }

            while (isRunning && currentCoroutineContext().isActive && prefs.isListeningEnabled) {
                callGuard.refreshActiveRecordingState()
                CaptureEngineController.pollForegroundViaUsageStatsIfNeeded(context)
                val curMono = SystemClock.elapsedRealtime()
                val activeReason = computePauseParkReason(curMono, silenceYieldCooldownUntilMonoMs) ?: break
                val wallNow = System.currentTimeMillis()
                CaptureStateBus.update {
                    it.copy(
                        status = LiveCaptureStatus.PAUSED,
                        isListeningEnabled = true,
                        currentSessionElapsedSec = 0L,
                        lastVadProbability = 0f,
                        isInPhoneCall = callGuard.isEffectivePhoneCall,
                        isMicSilencedByOs = callGuard.isSilencedBySystem,
                        isExternalMicActive = callGuard.externalRecordingActive,
                        pauseReason = activeReason,
                        isAccessibilityBound = AmbientCaptureAccessibilityService.isSystemBound,
                        isAccessibilityOverlayAttached = AmbientCaptureAccessibilityService.isOverlayAttached,
                        micSharingMode = prefs.micSharingMode,
                        lastHeartbeatMs = wallNow
                    )
                }
                delay(300L)
            }

            silenceYieldCooldownUntilMonoMs = 0L
            silencedSinceMonoMs = 0L
            vad.resetState()
            Log.i(TAG, "Mic yield ended -> resuming capture standby")
        }

        fun openNewSessionIfNeeded(
            preRollSamples: ShortArray,
            frameStartMonoMs: Long,
            frameStartWallMs: Long
        ) {
            if (sessionId != null) return
            val newId = UUID.randomUUID().toString()
            sessionId = newId
            sessionMode = activeRoundMode
            sessionRoundGroupId = activeRoundGroupId
            val preRollMs = (preRollSamples.size.toLong() * 1000L) / SileroVadProcessor.SAMPLE_RATE
            sessionStartMonoMs = frameStartMonoMs - preRollMs
            sessionStartWallMs = frameStartWallMs - preRollMs
            lastRecordedAudioMonoMs = frameStartMonoMs

            if (activeEventGroupId == null ||
                (lastFinalizedEndWallMs > 0L && sessionStartWallMs - lastFinalizedEndWallMs > CONTINUOUS_EVENT_GAP_MS)
            ) {
                activeEventGroupId = sessionRoundGroupId ?: newId
            }
            callGuard.onNewSessionStarted(sessionStartWallMs)

            val sessionsDir = File(context.filesDir, "sessions").apply { mkdirs() }
            val adtsFile = File(sessionsDir, "session_${newId}.aac")
            bufferPool.drainFilledToFree()
            val newWriter = AdtsFileWriter(adtsFile, bufferPool).apply {
                start()
            }
            if (preRollSamples.isNotEmpty()) {
                bufferPool.submitShortArrayChunked(preRollSamples)
            }
            writer = newWriter
            submittedFileSamples = preRollSamples.size.toLong()
            currentSegmentStartSample = 0L
            currentSegmentStartWallMs = sessionStartWallMs
            currentSegmentSamples = preRollSamples.size.toLong()
            wasCompactSkippingSilence = false
            inSpeechHangover = true
            Log.i(TAG, "Started session $newId (mode=$sessionMode) with ${preRollMs}ms pre-roll")
        }

        callGuard.onRecordingStateChanged = {
            val mode = prefs.micSharingMode
            val stealthParallel = MeetingRecorderStateBus.isStealthParallelAmbientActive()
            val callModeShouldYield = mode == MicSharingMode.STRICT_YIELD &&
                callGuard.isInCallMode &&
                !(stealthParallel && AmbientCaptureAccessibilityService.isSystemBound)
            val externalShouldYield = callGuard.externalRecordingActive &&
                (mode == MicSharingMode.STRICT_YIELD || !callGuard.isInCallMode) &&
                !(stealthParallel && (callGuard.isExternalClientExemptedStealth || callGuard.isStealthInspectionPending || callGuard.isInCallMode))
            val shouldNudgeImmediately = CaptureEngineController.externalPauseReason != null ||
                CaptureEngineController.isUserManualPaused() ||
                (mode != MicSharingMode.FORCE_CONCURRENT && (
                    CaptureEngineController.getActiveYieldForegroundLabel() != null ||
                        callModeShouldYield ||
                        externalShouldYield
                    ))
            if (shouldNudgeImmediately) {
                CaptureEngineController.requestMicNudgeFromCallback(this@CaptureEngine)
            }
        }

        try {
            while (isRunning && currentCoroutineContext().isActive && prefs.isListeningEnabled) {
                callGuard.refreshActiveRecordingState()
                CaptureEngineController.pollForegroundViaUsageStatsIfNeeded(context)
                val preOpenYield = computeActiveYieldReason(SystemClock.elapsedRealtime())
                if (preOpenYield != null) {
                    handleYieldAndPause(preOpenYield)
                    continue
                }

                if (audioRecord == null || callGuard.needsStreamReopen) {
                    releaseMicHardwareNow()
                    CaptureEngineController.awaitAnyOtherMicReleased(
                        excludingEngine = this@CaptureEngine,
                        timeoutMs = 1_500L
                    )
                    if (!isRunning || !currentCoroutineContext().isActive || !prefs.isListeningEnabled) {
                        break
                    }
                    CaptureEngineController.pollForegroundViaUsageStatsIfNeeded(context)
                    val postAwaitYield = computeActiveYieldReason(SystemClock.elapsedRealtime())
                    if (postAwaitYield != null) {
                        handleYieldAndPause(postAwaitYield)
                        continue
                    }

                    val (opened, _) = callGuard.openResilientAudioRecord(SileroVadProcessor.SAMPLE_RATE)
                    if (opened != null) {
                        CaptureEngineController.pollForegroundViaUsageStatsIfNeeded(context)
                        val postOpenYield = computeActiveYieldReason(SystemClock.elapsedRealtime())
                        if (postOpenYield != null) {
                            callGuard.releaseAudioRecord(opened)
                            handleYieldAndPause(postOpenYield)
                            continue
                        }
                        audioRecord = opened
                        currentAudioRecord = opened
                        CaptureEngineController.notifyMicHardwareState(this@CaptureEngine, holding = true)
                    } else {
                        val openFailYield = computeActiveYieldReason(SystemClock.elapsedRealtime())
                        if (openFailYield != null) {
                            handleYieldAndPause(openFailYield)
                        } else {
                            CaptureStateBus.update {
                                it.copy(
                                    status = LiveCaptureStatus.PAUSED,
                                    isListeningEnabled = true,
                                    pauseReason = "Waiting for microphone...",
                                    isInPhoneCall = callGuard.isEffectivePhoneCall,
                                    isExternalMicActive = callGuard.externalRecordingActive,
                                    isAccessibilityBound = AmbientCaptureAccessibilityService.isSystemBound,
                                    isAccessibilityOverlayAttached = AmbientCaptureAccessibilityService.isOverlayAttached,
                                    micSharingMode = prefs.micSharingMode,
                                    lastHeartbeatMs = System.currentTimeMillis()
                                )
                            }
                            delay(500L)
                        }
                        continue
                    }
                }

                if (!isRunning || !prefs.isListeningEnabled) break

                val activeRecord = audioRecord ?: continue
                val directBuf = bufferPool.acquireFreeBuffer()
                if (directBuf == null) {
                    // Pool temporarily exhausted: yield briefly for encoder thread to recycle buffers
                    delay(8L)
                    continue
                }

                // Read 1024 bytes (512 PCM16 samples = 32 ms @ 16 kHz) directly into native memory
                val bytesRead = activeRecord.read(
                    directBuf,
                    DirectPcmBufferPool.FRAME_BYTES_512_SAMPLES,
                    AudioRecord.READ_BLOCKING
                )
                val readEndMonoMs = SystemClock.elapsedRealtime()
                val readEndWallMs = System.currentTimeMillis()

                if (!isRunning || !prefs.isListeningEnabled) {
                    bufferPool.returnFreeBuffer(directBuf)
                    break
                }

                val postReadYield = computeActiveYieldReason(readEndMonoMs)
                if (postReadYield != null) {
                    bufferPool.returnFreeBuffer(directBuf)
                    handleYieldAndPause(postReadYield)
                    continue
                }

                if (bytesRead < 0) {
                    // Negative return values are error codes (ERROR_INVALID_OPERATION, ERROR_DEAD_OBJECT, etc.)
                    bufferPool.audioReadErrors.incrementAndGet()
                    bufferPool.returnFreeBuffer(directBuf)
                    Log.w(TAG, "AudioRecord.read(ByteBuffer) returned error $bytesRead -> reopening stream")
                    releaseMicHardwareNow()
                    callGuard.needsStreamReopen = true
                    delay(150L)
                    continue
                }

                val alignedBytes = bytesRead and 0x7FFFFFFE // Ensure even number of bytes for 16-bit PCM
                if (alignedBytes <= 0) {
                    bufferPool.returnFreeBuffer(directBuf)
                    if (activeRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Log.w(TAG, "AudioRecord stopped unexpectedly (state=${activeRecord.recordingState}) -> reopening stream")
                        releaseMicHardwareNow()
                        callGuard.needsStreamReopen = true
                        delay(60L)
                    }
                    continue
                }

                // AudioRecord.read(ByteBuffer, size) does not advance position; set limit(n) & position(0)
                directBuf.limit(alignedBytes)
                directBuf.position(0)
                directBuf.order(ByteOrder.nativeOrder())
                val shortView = directBuf.asShortBuffer()
                val frameSamples = alignedBytes / 2L
                val frameDurationMs = (frameSamples * 1000L) / SileroVadProcessor.SAMPLE_RATE
                val frameStartMonoMs = readEndMonoMs - frameDurationMs
                val frameStartWallMs = readEndWallMs - frameDurationMs

                if (forceCloseRequested) {
                    forceCloseRequested = false
                    finalizeCurrentSession()
                    if (activeRoundMode == SessionMode.ROUND_MANUAL) {
                        openNewSessionIfNeeded(vad.drainPreRoll(), frameStartMonoMs, frameStartWallMs)
                    }
                }

                // Propagate live promotion to ROUND_AUTO if SessionAnalyzeWorker escalated mode
                if (sessionId != null && sessionMode == SessionMode.EVERYDAY && activeRoundMode == SessionMode.ROUND_AUTO) {
                    sessionMode = SessionMode.ROUND_AUTO
                    sessionRoundGroupId = activeRoundGroupId
                }

                val prob = vad.processDirectFrame512(shortView, isSessionActive = sessionId != null)
                val isSpeech = if (inSpeechHangover) {
                    prob >= SileroVadProcessor.SPEECH_OFFSET_THRESHOLD
                } else {
                    prob >= SileroVadProcessor.SPEECH_ONSET_THRESHOLD
                }

                if (isSpeech) {
                    if (sessionId == null) {
                        val preRoll = vad.drainPreRoll()
                        openNewSessionIfNeeded(preRoll, frameStartMonoMs, frameStartWallMs)
                    } else if (wasCompactSkippingSilence) {
                        // Record segment map boundary after ANY compacted dead-air pause (> KEEP_TRAILING_SILENCE_MS)
                        val segLenMs = (currentSegmentSamples * 1000L) / SileroVadProcessor.SAMPLE_RATE
                        if (segLenMs > 0L) {
                            segmentMaps.add(
                                SegmentMapEntity(
                                    sessionId = sessionId!!,
                                    fileOffsetMs = (currentSegmentStartSample * 1000L) / SileroVadProcessor.SAMPLE_RATE,
                                    wallClockMs = currentSegmentStartWallMs,
                                    lengthMs = segLenMs
                                )
                            )
                        }
                        currentSegmentStartSample = submittedFileSamples
                        currentSegmentStartWallMs = sessionStartWallMs + (frameStartMonoMs - sessionStartMonoMs)
                        currentSegmentSamples = 0L
                        wasCompactSkippingSilence = false
                    }
                    // Hand ownership of directBuf to the dedicated encoder thread
                    bufferPool.submitFilledBuffer(directBuf)
                    submittedFileSamples += frameSamples
                    speechSamplesAccumulated += frameSamples
                    currentSegmentSamples += frameSamples
                    lastRecordedAudioMonoMs = readEndMonoMs
                    if (burstPauseMs > BURST_BRIDGE_PAUSE_MS) {
                        currentBurstSpeechMs = 0L
                    }
                    burstPauseMs = 0L
                    currentBurstSpeechMs += frameDurationMs
                    if (currentBurstSpeechMs > maxBurstSpeechMs) {
                        maxBurstSpeechMs = currentBurstSpeechMs
                    }
                    silenceConsecutiveMs = 0L
                    inSpeechHangover = true
                } else {
                    if (sessionId == null) {
                        // Standby: keep filling the 2-second circular pre-roll buffer and recycle directBuf
                        vad.appendDirectToPreRoll(shortView)
                        bufferPool.returnFreeBuffer(directBuf)
                    } else {
                        silenceConsecutiveMs += frameDurationMs
                        burstPauseMs += frameDurationMs
                        // Silence compaction: write up to 800 ms of natural trailing pause, discard dead air > 800 ms
                        if (silenceConsecutiveMs <= KEEP_TRAILING_SILENCE_MS) {
                            bufferPool.submitFilledBuffer(directBuf)
                            submittedFileSamples += frameSamples
                            currentSegmentSamples += frameSamples
                            lastRecordedAudioMonoMs = readEndMonoMs
                        } else {
                            wasCompactSkippingSilence = true
                            inSpeechHangover = false
                            vad.appendDirectToPreRoll(shortView)
                            bufferPool.returnFreeBuffer(directBuf)
                        }

                        val splitThresholdMs = when (sessionMode) {
                            SessionMode.ROUND_MANUAL, SessionMode.ROUND_AUTO ->
                                prefs.roundSilenceSplitSeconds * 1000L
                            SessionMode.EVERYDAY ->
                                prefs.silenceSplitSeconds * 1000L
                        }

                        val submittedDurationMs = (submittedFileSamples * 1000L) / SileroVadProcessor.SAMPLE_RATE
                        val maxChunkReachedAndPaused =
                            submittedDurationMs >= MAX_CHUNK_DURATION_MS &&
                                silenceConsecutiveMs >= PAUSE_ALIGNED_SPLIT_MS

                        if (silenceConsecutiveMs >= splitThresholdMs) {
                            if (sessionMode == SessionMode.ROUND_AUTO) {
                                activeRoundMode = SessionMode.EVERYDAY
                                activeRoundGroupId = null
                            }
                            activeEventGroupId = null
                            finalizeCurrentSession(preserveEventContinuation = false)
                        } else if (maxChunkReachedAndPaused) {
                            val preserveMode = sessionMode
                            val preserveGroup = sessionRoundGroupId ?: activeEventGroupId ?: UUID.randomUUID().toString()
                            activeEventGroupId = preserveGroup
                            if (preserveMode != SessionMode.EVERYDAY) {
                                activeRoundGroupId = preserveGroup
                            }
                            finalizeCurrentSession(preserveEventContinuation = true)
                        }
                    }
                }

                // UI-aware telemetry: emit state transitions immediately, pause 4Hz UI & 2s GC polling in background
                val effectiveListening = isRunning && prefs.isListeningEnabled
                val liveStatus = when {
                    !effectiveListening -> LiveCaptureStatus.STOPPED
                    callGuard.isSilencedBySystem -> LiveCaptureStatus.MIC_SILENCED_WARNING
                    callGuard.isEffectivePhoneCall && sessionId != null -> LiveCaptureStatus.RECORDING_IN_CALL
                    sessionId != null && sessionMode == SessionMode.ROUND_MANUAL -> LiveCaptureStatus.RECORDING_ROUND_MANUAL
                    sessionId != null && sessionMode == SessionMode.ROUND_AUTO -> LiveCaptureStatus.RECORDING_ROUND_AUTO
                    sessionId != null -> LiveCaptureStatus.RECORDING_AMBIENT
                    else -> LiveCaptureStatus.VAD_STANDBY
                }
                val currentTelemetry = CaptureStateBus.telemetry.value
                val currentMode = prefs.micSharingMode
                val statusChanged = currentTelemetry.status != liveStatus ||
                    currentTelemetry.isListeningEnabled != effectiveListening ||
                    currentTelemetry.micSharingMode != currentMode ||
                    currentTelemetry.isExternalMicActive != callGuard.externalRecordingActive ||
                    currentTelemetry.pauseReason != null ||
                    currentTelemetry.isAccessibilityBound != AmbientCaptureAccessibilityService.isSystemBound ||
                    currentTelemetry.isAccessibilityOverlayAttached != AmbientCaptureAccessibilityService.isOverlayAttached
                val uiActive = CaptureStateBus.isUiForegroundActive || CaptureStateBus.isDiagnosticsOpen

                if (statusChanged || uiActive) {
                    val now = readEndWallMs
                    if (statusChanged || now - currentTelemetry.lastHeartbeatMs >= 250L) {
                        if (!effectiveListening) break

                        val elapsedSec = if (sessionId != null) {
                            (submittedFileSamples / SileroVadProcessor.SAMPLE_RATE).coerceAtLeast(0L)
                        } else 0L

                        // Sample ART GC and native heap ONLY when Diagnostics dialog is actively visible
                        var gcCount = currentTelemetry.gcCount
                        var gcTimeMs = currentTelemetry.gcTimeMs
                        var nativeHeapKb = currentTelemetry.nativeHeapKb
                        if (CaptureStateBus.isDiagnosticsOpen && now - lastGcSampleMs >= 2_000L) {
                            lastGcSampleMs = now
                            runCatching {
                                gcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: gcCount
                                gcTimeMs = Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: gcTimeMs
                                nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L
                            }
                        }

                        CaptureStateBus.update {
                            it.copy(
                                status = liveStatus,
                                isListeningEnabled = effectiveListening,
                                currentSessionElapsedSec = elapsedSec,
                                lastVadProbability = if (uiActive) prob else it.lastVadProbability,
                                isInPhoneCall = callGuard.isEffectivePhoneCall,
                                isMicSilencedByOs = callGuard.isSilencedBySystem,
                                isExternalMicActive = callGuard.externalRecordingActive,
                                pauseReason = null,
                                isAccessibilityBound = AmbientCaptureAccessibilityService.isSystemBound,
                                isAccessibilityOverlayAttached = AmbientCaptureAccessibilityService.isOverlayAttached,
                                micSharingMode = currentMode,
                                gcCount = gcCount,
                                gcTimeMs = gcTimeMs,
                                nativeHeapKb = nativeHeapKb,
                                audioReadErrors = bufferPool.audioReadErrors.get(),
                                poolStarvationDrops = bufferPool.starvationDrops.get(),
                                vadGatedFrames = vad.gatedFramesCount,
                                vadOnnxFrames = vad.onnxFramesCount,
                                lastHeartbeatMs = now
                            )
                        }
                    }
                }
            }
        } finally {
            callGuard.onRecordingStateChanged = null
            // Release physical AudioRecord FIRST so other apps or handoff engine can open mic immediately
            releaseMicHardwareNow()
            withContext(NonCancellable) {
                finalizeCurrentSession()
            }
            if (vadClosed.compareAndSet(false, true)) {
                vad.close()
            }
            callGuard.shutdown()
            bufferPool.drainFilledToFree()
            loopState.set(LOOP_EXITED)
            CaptureEngineController.unregisterEngine(this@CaptureEngine)
            if (!handoffStopRequested.get() && CaptureEngineController.instance == null) {
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

    companion object {
        private const val TAG = "CaptureEngine"
        const val MANUAL_PAUSE_REASON = "Paused by user"
        private const val SILENCE_YIELD_REASON = "Mic blocked or in use by OS"
        private const val BACKGROUND_NO_PRIVILEGE_REASON = "Paused in background (enable A11y or ADB)"
        private const val APP_OP_REFRESH_INTERVAL_MS = 1_000L
        private const val LOOP_INIT = 0
        private const val LOOP_RUNNING = 1
        private const val LOOP_EXITED = 2
        private const val SILENCE_YIELD_SMART_MS = 50L
        private const val SILENCE_YIELD_STRICT_MS = 20L
        private const val SILENCE_YIELD_COOLDOWN_MS = 2_500L
        private const val KEEP_TRAILING_SILENCE_MS = 800L
        private const val COMPACT_SILENCE_ABOVE_MS = 3_000L
        private const val PAUSE_ALIGNED_SPLIT_MS = 700L
        private const val BURST_BRIDGE_PAUSE_MS = 450L
        private const val MAX_SUSTAINED_BURST_GATE_MS = 1_200L
        private const val MAX_CHUNK_DURATION_MS = 15 * 60 * 1000L // 15 minutes max per chunk
        private const val CONTINUOUS_EVENT_GAP_MS = 15 * 60 * 1000L // Link continuous events up to 15 min gap
    }
}
