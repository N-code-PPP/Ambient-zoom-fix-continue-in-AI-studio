package com.ambientbrain.recorder.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

enum class ShizukuStatus {
    NOT_INSTALLED,
    SERVICE_STOPPED,
    UNAUTHORIZED,
    AUTHORIZED
}

data class ActiveAudioClientInfo(
    val packageName: String,
    val appLabel: String,
    val uid: Int?,
    val sessionId: Int?,
    val audioSource: String,
    val isSilenced: Boolean,
    val isActive: Boolean,
    val isSelf: Boolean
)

data class AudioInspectorSnapshot(
    val capturedAtMs: Long,
    val audioModeLine: String?,
    val clients: List<ActiveAudioClientInfo>,
    val maxPhantomProcesses: String?,
    val rawExcerpt: String,
    val error: String? = null
)

data class SelfHealResult(
    val hasPermission: Boolean,
    val attempted: Boolean,
    val repaired: Boolean,
    val alreadyHealthy: Boolean,
    val message: String
)

data class AdbDiagnosticsState(
    val shizukuStatus: ShizukuStatus,
    val recordAudioAppOpMode: Int,
    val appOpsRecordAudioAllowed: Boolean,
    val appOpsSystemAlertWindow: Boolean,
    val appOpsRunAnyInBackground: Boolean,
    val batteryOptimizationExempt: Boolean,
    val accessibilitySettingsEnabled: Boolean,
    val accessibilityServiceBound: Boolean,
    val accessibilityOverlayAttached: Boolean,
    val accessibilityOverlayType: String,
    val accessibilityOverlayError: String?,
    val runtimeRecordAudio: Boolean,
    val runtimeReadPhoneState: Boolean,
    val runtimeWriteSecureSettings: Boolean = false,
    val runtimeCaptureAudioOutput: Boolean = false,
    val appOpsProjectMedia: Boolean = false,
    val appOpsAutoRevokeExempt: Boolean = false,
    val phantomProcessMonitorDisabled: Boolean = false
) {
    val isRecordAudioForegroundOnly: Boolean
        get() = recordAudioAppOpMode == AppOpsManager.MODE_FOREGROUND

    val isPhantomFreezerShielded: Boolean
        get() = appOpsRunAnyInBackground &&
            batteryOptimizationExempt &&
            (phantomProcessMonitorDisabled || appOpsAutoRevokeExempt)

    val isFullyAdbOptimized: Boolean
        get() = appOpsRecordAudioAllowed &&
            appOpsRunAnyInBackground &&
            batteryOptimizationExempt &&
            accessibilitySettingsEnabled &&
            accessibilityServiceBound &&
            runtimeRecordAudio

    val hasDeepPrivilegesReady: Boolean
        get() = isFullyAdbOptimized &&
            runtimeWriteSecureSettings &&
            appOpsProjectMedia
}

object ShizukuAdbManager {
    private const val TAG = "ShizukuAdbManager"
    const val SHIZUKU_REQ_CODE = 8901
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"
    private const val OPSTR_PROJECT_MEDIA_COMPAT = "android:project_media"
    private const val OPSTR_AUTO_REVOKE_COMPAT = "android:auto_revoke_permissions_if_unused"
    private const val SETTING_MONITOR_PHANTOM_PROCS = "settings_enable_monitor_phantom_procs"
    private const val SELF_HEAL_COOLDOWN_MS = 20_000L
    private const val MAX_DUMPSYS_SECTION_LINES = 6000

    private val selfHealLock = Any()

    @Volatile
    private var lastSelfHealAttemptMonoMs: Long = 0L

    private data class AdbCommandSpec(
        val label: String,
        val command: String,
        val optional: Boolean = false
    )

    private data class MutableDumpsysRecord(
        var packageName: String? = null,
        var uid: Int? = null,
        var sessionId: Int? = null,
        var audioSource: String? = null,
        var isSilenced: Boolean = false,
        var isActive: Boolean = true
    )

    fun getShizukuStatus(context: Context): ShizukuStatus {
        val isInstalled = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

        if (!isInstalled) {
            return ShizukuStatus.NOT_INSTALLED
        }

        val isAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!isAlive) {
            return ShizukuStatus.SERVICE_STOPPED
        }

        val hasPermission = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        return if (hasPermission) ShizukuStatus.AUTHORIZED else ShizukuStatus.UNAUTHORIZED
    }

    fun requestShizukuPermission() {
        runCatching {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(SHIZUKU_REQ_CODE)
            }
        }.onFailure {
            Log.e(TAG, "Failed to request Shizuku permission", it)
        }
    }

    fun hasWriteSecureSettings(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCaptureAudioOutput(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            "android.permission.CAPTURE_AUDIO_OUTPUT"
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun checkDiagnostics(context: Context): AdbDiagnosticsState {
        val pkg = context.packageName
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager

        val recordAudioMode = checkAppOpMode(appOps, AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), pkg)
        val recordAudioOpAllowed = recordAudioMode == AppOpsManager.MODE_ALLOWED
        val runAnyAllowed = checkAppOpMode(appOps, OPSTR_RUN_ANY_IN_BACKGROUND, Process.myUid(), pkg) == AppOpsManager.MODE_ALLOWED
        val systemAlertWindowAllowed = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false) ||
            checkAppOpMode(appOps, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), pkg) == AppOpsManager.MODE_ALLOWED
        val projectMediaAllowed = checkAppOpMode(appOps, OPSTR_PROJECT_MEDIA_COMPAT, Process.myUid(), pkg) == AppOpsManager.MODE_ALLOWED
        val autoRevokeExempt = checkAppOpMode(appOps, OPSTR_AUTO_REVOKE_COMPAT, Process.myUid(), pkg) == AppOpsManager.MODE_IGNORED

        val phantomMonitorDisabled = runCatching {
            val raw = Settings.Global.getString(context.contentResolver, SETTING_MONITOR_PHANTOM_PROCS)
                ?.trim()
                ?.lowercase()
            raw == "0" || raw == "false"
        }.getOrDefault(false)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryExempt = powerManager?.isIgnoringBatteryOptimizations(pkg) == true

        val accessibilitySettingsEnabled = isAccessibilityServiceSettingsEnabled(context)
        val accessibilityServiceBound = AmbientCaptureAccessibilityService.isSystemBound
        val accessibilityOverlayAttached = AmbientCaptureAccessibilityService.isOverlayAttached
        val accessibilityOverlayType = AmbientCaptureAccessibilityService.overlayWindowType
        val accessibilityOverlayError = AmbientCaptureAccessibilityService.lastOverlayError

        val runtimeRecordAudio = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val runtimeReadPhoneState = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val runtimeWriteSecureSettings = hasWriteSecureSettings(context)
        val runtimeCaptureAudioOutput = hasCaptureAudioOutput(context)

        return AdbDiagnosticsState(
            shizukuStatus = getShizukuStatus(context),
            recordAudioAppOpMode = recordAudioMode,
            appOpsRecordAudioAllowed = recordAudioOpAllowed,
            appOpsSystemAlertWindow = systemAlertWindowAllowed,
            appOpsRunAnyInBackground = runAnyAllowed,
            batteryOptimizationExempt = batteryExempt,
            accessibilitySettingsEnabled = accessibilitySettingsEnabled,
            accessibilityServiceBound = accessibilityServiceBound,
            accessibilityOverlayAttached = accessibilityOverlayAttached,
            accessibilityOverlayType = accessibilityOverlayType,
            accessibilityOverlayError = accessibilityOverlayError,
            runtimeRecordAudio = runtimeRecordAudio,
            runtimeReadPhoneState = runtimeReadPhoneState,
            runtimeWriteSecureSettings = runtimeWriteSecureSettings,
            runtimeCaptureAudioOutput = runtimeCaptureAudioOutput,
            appOpsProjectMedia = projectMediaAllowed,
            appOpsAutoRevokeExempt = autoRevokeExempt,
            phantomProcessMonitorDisabled = phantomMonitorDisabled
        )
    }

    private fun checkAppOpMode(appOps: AppOpsManager?, opStr: String, uid: Int, pkg: String): Int {
        if (appOps == null) return AppOpsManager.MODE_ERRORED
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(opStr, uid, pkg)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(opStr, uid, pkg)
            }
        } catch (_: Exception) {
            AppOpsManager.MODE_ERRORED
        }
    }

    fun isRecordAudioAppOpAllowed(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return checkAppOpMode(
            appOps,
            AppOpsManager.OPSTR_RECORD_AUDIO,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun isUsageStatsAppOpAllowed(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = checkAppOpMode(
            appOps,
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        if (mode == AppOpsManager.MODE_ALLOWED) return true
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.PACKAGE_USAGE_STATS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return false
    }

    fun isAccessibilityServiceSettingsEnabled(context: Context): Boolean {
        val component = ComponentName(context, AmbientCaptureAccessibilityService::class.java)
        val fullName = component.flattenToString()
        val shortName = component.flattenToShortString()

        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val amEnabled = runCatching {
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    val id = info.id
                    id == fullName || id == shortName || info.resolveInfo?.serviceInfo?.let { si ->
                        si.packageName == context.packageName &&
                            si.name == AmbientCaptureAccessibilityService::class.java.name
                    } == true
                } == true
        }.getOrDefault(false)
        if (amEnabled) return true

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.split(':').any { token ->
            val trimmed = token.trim()
            trimmed == fullName || trimmed == shortName
        }
    }

    /**
     * Zero-Touch Self-Healing via `WRITE_SECURE_SETTINGS`:
     * Safely restores or re-binds [AmbientCaptureAccessibilityService] in `Settings.Secure`
     * without Shizuku or a PC whenever `WRITE_SECURE_SETTINGS` has been granted once.
     * Preserves all other user-enabled accessibility services cleanly.
     */
    fun selfHealAccessibilityService(
        context: Context,
        forceRebindIfUnbound: Boolean = false,
        bypassCooldown: Boolean = false
    ): SelfHealResult {
        val appCtx = context.applicationContext
        if (!hasWriteSecureSettings(appCtx)) {
            return SelfHealResult(
                hasPermission = false,
                attempted = false,
                repaired = false,
                alreadyHealthy = false,
                message = "WRITE_SECURE_SETTINGS not granted (run 1-Tap Shizuku or ADB setup once)."
            )
        }

        val component = ComponentName(appCtx, AmbientCaptureAccessibilityService::class.java)
        val fullName = component.flattenToString()
        val shortName = component.flattenToShortString()
        val cr = appCtx.contentResolver

        synchronized(selfHealLock) {
            val nowMono = SystemClock.elapsedRealtime()
            val withinCooldown = !bypassCooldown &&
                lastSelfHealAttemptMonoMs > 0L &&
                nowMono - lastSelfHealAttemptMonoMs < SELF_HEAL_COOLDOWN_MS

            return try {
                val rawEnabled = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                val tokens = rawEnabled.split(':')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                val hasServiceInSettings = tokens.any { it == fullName || it == shortName }
                val others = tokens.filter { it != fullName && it != shortName }
                val a11yMasterEnabled = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
                val bound = AmbientCaptureAccessibilityService.isSystemBound

                if (!hasServiceInSettings || !a11yMasterEnabled) {
                    if (withinCooldown) {
                        return SelfHealResult(
                            hasPermission = true,
                            attempted = false,
                            repaired = false,
                            alreadyHealthy = false,
                            message = "Self-heal cooldown active; retry shortly."
                        )
                    }
                    lastSelfHealAttemptMonoMs = nowMono
                    val restored = (others + fullName).joinToString(":")
                    val okServices = Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, restored)
                    val okEnabled = Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    if (okServices && okEnabled) {
                        Log.i(TAG, "Zero-touch self-heal restored AccessibilityService in Settings.Secure")
                        SelfHealResult(
                            hasPermission = true,
                            attempted = true,
                            repaired = true,
                            alreadyHealthy = false,
                            message = "Restored AccessibilityService setting autonomously via WRITE_SECURE_SETTINGS."
                        )
                    } else {
                        SelfHealResult(
                            hasPermission = true,
                            attempted = true,
                            repaired = false,
                            alreadyHealthy = false,
                            message = "Settings.Secure write returned false (OEM restricted settings lock)."
                        )
                    }
                } else if (!bound && forceRebindIfUnbound) {
                    if (withinCooldown) {
                        return SelfHealResult(
                            hasPermission = true,
                            attempted = false,
                            repaired = false,
                            alreadyHealthy = false,
                            message = "Rebind cooldown active; waiting for OS Accessibility bind."
                        )
                    }
                    lastSelfHealAttemptMonoMs = nowMono
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
                    runCatching { Thread.sleep(350L) }
                    val restored = (others + fullName).joinToString(":")
                    val okServices = Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, restored)
                    val okEnabled = Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    if (okServices && okEnabled) {
                        Log.i(TAG, "Zero-touch self-heal cycled & re-bound AccessibilityService via WRITE_SECURE_SETTINGS")
                        SelfHealResult(
                            hasPermission = true,
                            attempted = true,
                            repaired = true,
                            alreadyHealthy = false,
                            message = "Cycled & re-bound AccessibilityService via WRITE_SECURE_SETTINGS."
                        )
                    } else {
                        SelfHealResult(
                            hasPermission = true,
                            attempted = true,
                            repaired = false,
                            alreadyHealthy = false,
                            message = "Failed to re-write ENABLED_ACCESSIBILITY_SERVICES."
                        )
                    }
                } else {
                    SelfHealResult(
                        hasPermission = true,
                        attempted = false,
                        repaired = false,
                        alreadyHealthy = true,
                        message = if (bound) {
                            "AccessibilityService already enabled & OS-bound."
                        } else {
                            "AccessibilityService enabled in Settings (waiting on OS bind)."
                        }
                    )
                }
            } catch (se: SecurityException) {
                Log.w(TAG, "Self-heal SecurityException: ${se.message}")
                SelfHealResult(
                    hasPermission = false,
                    attempted = true,
                    repaired = false,
                    alreadyHealthy = false,
                    message = "SecurityException writing Settings.Secure (${se.message ?: "OEM lock"})."
                )
            } catch (e: Exception) {
                Log.w(TAG, "Self-heal failed: ${e.message}")
                SelfHealResult(
                    hasPermission = true,
                    attempted = true,
                    repaired = false,
                    alreadyHealthy = false,
                    message = "Self-heal error: ${e.message ?: e::class.java.simpleName}"
                )
            }
        }
    }

    private fun unwrapThrowable(t: Throwable): Throwable {
        var cur: Throwable = t
        while (cur is java.lang.reflect.InvocationTargetException && cur.targetException != null) {
            cur = cur.targetException
        }
        return cur
    }

    private fun formatThrowableSummary(t: Throwable): String {
        val root = unwrapThrowable(t)
        val cls = root.javaClass.simpleName
        val msg = root.message?.trim().orEmpty()
        return if (msg.isNotEmpty()) "$cls: $msg" else cls
    }

    private fun executeShizukuProcess(command: String): java.lang.Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return try {
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as java.lang.Process
        } catch (ite: java.lang.reflect.InvocationTargetException) {
            throw (ite.targetException ?: ite)
        }
    }

    internal fun openShizukuShellProcess(command: String): java.lang.Process {
        return executeShizukuProcess(command)
    }

    /**
     * Binder-safe timed wait for a [rikka.shizuku.ShizukuRemoteProcess].
     *
     * `ShizukuRemoteProcess` does NOT override `Process.waitFor(long, TimeUnit)`. Java's default
     * implementation polls `exitValue()` catching only `IllegalThreadStateException`, whereas
     * Android Binder marshals `IllegalThreadStateException` as plain `IllegalArgumentException`
     * ("process hasn't exited"), causing timed `waitFor` to abort immediately on the first poll.
     *
     * Calling the overridden blocking 0-arg `proc.waitFor()` on a dedicated waiter thread avoids
     * `exitValue()` polling entirely while preserving strict caller timeouts. Returns the process
     * exit code if it exited before [timeoutMs], or `null` if it timed out.
     */
    internal fun waitForShizukuProcess(proc: java.lang.Process, timeoutMs: Long): Int? {
        val boundedMs = timeoutMs.coerceAtLeast(100L)
        val exitCodeRef = java.util.concurrent.atomic.AtomicReference<Int?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        val waiter = Thread({
            try {
                exitCodeRef.set(proc.waitFor())
            } catch (_: InterruptedException) {
                // Interrupted on timeout or cancellation
            } catch (_: Throwable) {
                // Defensive fallback: poll exitValue() catching BOTH IllegalThreadStateException
                // and Binder-downgraded IllegalArgumentException until deadline.
                val deadlineMs = SystemClock.elapsedRealtime() + boundedMs
                while (!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadlineMs) {
                    try {
                        exitCodeRef.set(proc.exitValue())
                        break
                    } catch (_: IllegalThreadStateException) {
                        // Still running
                    } catch (_: IllegalArgumentException) {
                        // Shizuku Binder marshaled "process hasn't exited"
                    } catch (_: Throwable) {
                        break
                    }
                    try {
                        Thread.sleep(50L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } finally {
                latch.countDown()
            }
        }, "Shizuku-WaitFor").apply {
            isDaemon = true
            start()
        }

        val completed = try {
            latch.await(boundedMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!completed) {
            waiter.interrupt()
            return null
        }
        return exitCodeRef.get()
    }

    private data class ProcessExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean
    )

    private fun readBoundedStream(stream: java.io.InputStream, maxBytes: Int = 256 * 1024): String {
        val baos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        try {
            stream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val remaining = maxBytes - total
                    if (remaining > 0) {
                        val toWrite = minOf(n, remaining)
                        baos.write(buf, 0, toWrite)
                        total += toWrite
                    }
                }
            }
        } catch (_: Throwable) {
            // Stream closed on forcible process termination
        }
        return baos.toString(Charsets.UTF_8.name()).trim()
    }

    private fun runProcessWithTimeout(
        proc: java.lang.Process,
        timeoutSec: Long,
        maxOutputBytes: Int = 256 * 1024
    ): ProcessExecResult {
        val outRef = java.util.concurrent.atomic.AtomicReference("")
        val errRef = java.util.concurrent.atomic.AtomicReference("")
        val outThread = Thread({
            outRef.set(readBoundedStream(proc.inputStream, maxBytes = maxOutputBytes))
        }, "Shizuku-Stdout").apply {
            isDaemon = true
            start()
        }
        val errThread = Thread({
            errRef.set(readBoundedStream(proc.errorStream))
        }, "Shizuku-Stderr").apply {
            isDaemon = true
            start()
        }

        val exitCode = waitForShizukuProcess(proc, timeoutSec.coerceAtLeast(2L) * 1_000L)
        if (exitCode == null) {
            runCatching { proc.destroy() }
            runCatching { proc.destroyForcibly() }
            runCatching { proc.inputStream.close() }
            runCatching { proc.errorStream.close() }
            runCatching { outThread.join(400L) }
            runCatching { errThread.join(400L) }
            return ProcessExecResult(
                exitCode = -1,
                stdout = outRef.get(),
                stderr = errRef.get(),
                timedOut = true
            )
        }

        runCatching { outThread.join(1_000L) }
        runCatching { errThread.join(1_000L) }
        return ProcessExecResult(
            exitCode = exitCode,
            stdout = outRef.get(),
            stderr = errRef.get(),
            timedOut = false
        )
    }

    internal fun runShizukuShellCommand(command: String, timeoutSec: Long = 10L): Pair<Int, String> {
        var proc: java.lang.Process? = null
        return try {
            proc = executeShizukuProcess(command)
            val res = runProcessWithTimeout(proc, timeoutSec)
            val combined = buildString {
                if (res.stdout.isNotEmpty()) append(res.stdout)
                if (res.stderr.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(res.stderr)
                }
                if (res.timedOut) {
                    if (isNotEmpty()) append("\n")
                    append("Timed out after ${timeoutSec.coerceAtLeast(2L)}s")
                }
            }
            res.exitCode to combined
        } catch (t: Throwable) {
            runCatching { proc?.destroyForcibly() }
            val errSummary = formatThrowableSummary(t)
            Log.w(TAG, "runShizukuShellCommand failed: $errSummary")
            -1 to errSummary
        }
    }

    private fun audioSourceToString(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"
            else -> "SRC_$source"
        }
    }

    private fun resolvePackageForUid(context: Context, uid: Int): String? {
        return when (uid) {
            1000 -> "android"
            1001 -> "com.android.phone"
            1041 -> "audioserver"
            else -> runCatching {
                context.packageManager.getPackagesForUid(uid)?.firstOrNull()
            }.getOrNull()
        }
    }

    private fun resolveAppLabel(context: Context, packageName: String, uid: Int?): String {
        if (packageName == "android" || uid == 1000) return "Android System"
        if (packageName == "audioserver" || uid == 1041) return "Android AudioServer"
        if (packageName == "com.android.phone" || uid == 1001) return "Phone / Telecom"
        if (packageName.startsWith("uid:")) return "System UID ${packageName.removePrefix("uid:")}"
        return runCatching {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            pm.getApplicationLabel(ai).toString().trim().ifBlank { packageName }
        }.getOrDefault(packageName)
    }

    /**
     * Inspects live recording owners using `dumpsys audio` + `dumpsys media.audio_flinger`
     * cross-referenced against [AudioManager.getActiveRecordingConfigurations].
     * Resolves exact package name, UID, session ID, AudioSource, and silence status even for
     * background services or overlay/PiP apps hidden from normal foreground detection.
     */
    fun inspectActiveAudioClientsViaShizuku(
        context: Context,
        existingConfigs: List<AudioRecordingConfiguration>? = null
    ): AudioInspectorSnapshot {
        val appCtx = context.applicationContext
        val capturedAt = System.currentTimeMillis()
        val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val sysConfigs = existingConfigs ?: runCatching {
            audioManager?.activeRecordingConfigurations.orEmpty()
        }.getOrDefault(emptyList())
        val sysBySession = sysConfigs.associateBy { it.clientAudioSessionId }

        val shizukuStatus = getShizukuStatus(appCtx)
        if (shizukuStatus != ShizukuStatus.AUTHORIZED) {
            val fallbackClients = sysConfigs.map { cfg ->
                ActiveAudioClientInfo(
                    packageName = "session:${cfg.clientAudioSessionId}",
                    appLabel = "Audio Client #${cfg.clientAudioSessionId}",
                    uid = null,
                    sessionId = cfg.clientAudioSessionId,
                    audioSource = audioSourceToString(cfg.clientAudioSource),
                    isSilenced = cfg.isClientSilenced,
                    isActive = true,
                    isSelf = false
                )
            }
            return AudioInspectorSnapshot(
                capturedAtMs = capturedAt,
                audioModeLine = audioManager?.let { "AudioManager.mode=${it.mode}" },
                clients = fallbackClients,
                maxPhantomProcesses = null,
                rawExcerpt = "",
                error = "Shizuku not authorized (Status: $shizukuStatus)"
            )
        }

        val cmd = """
            echo "===PHANTOM==="
            device_config get activity_manager max_phantom_processes 2>/dev/null || echo "unknown"
            echo "===AUDIO_SERVICE==="
            dumpsys audio 2>/dev/null
            echo "===AUDIO_FLINGER==="
            dumpsys media.audio_flinger 2>/dev/null
        """.trimIndent()

        return try {
            val process = executeShizukuProcess(cmd)
            var section = ""
            var maxPhantom: String? = null
            var audioModeLine: String? = null
            val excerptLines = mutableListOf<String>()

            // Active sessions tracked from dumpsys audio RecordingActivityMonitor events + active dumps
            val audioServiceByKey = LinkedHashMap<String, MutableDumpsysRecord>()
            val audioServiceBySession = HashMap<Int, MutableDumpsysRecord>()
            val attributionHistoryBySession = HashMap<Int, MutableDumpsysRecord>()

            // Active RecordTracks from dumpsys media.audio_flinger
            val flingerBySession = HashMap<Int, MutableDumpsysRecord>()
            var inRecordThread = false
            var colSessionIdx = -1
            var colUidIdx = -1
            var colActiveIdx = -1
            var colNameIdx = -1

            val stopReleaseRegex = Regex("\\brec\\s+(stop|release)\\b", RegexOption.IGNORE_CASE)
            val startUpdateRegex = Regex("\\brec\\s+(start|update|config)\\b", RegexOption.IGNORE_CASE)
            val riidRegex = Regex("\\briid[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
            val uidRegex = Regex("\\buid[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
            val sessionRegex = Regex("\\bsession(?:Id)?[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
            val srcRegex = Regex("\\b(?:src|source)[:=]\\s*([A-Za-z0-9_]+)", RegexOption.IGNORE_CASE)
            val packRegex = Regex("\\b(?:pack|pkg|package|packageName|mPackageName|clientPackage|opPackageName|clientPackageName)[:=]\\s*([A-Za-z0-9_.]+)", RegexOption.IGNORE_CASE)
            val silencedRegex = Regex("\\bsilenced[:=]\\s*(true|false)", RegexOption.IGNORE_CASE)
            val audioModeRegex = Regex("\\b(mMode|AudioMode|Mode)\\s*[:=]\\s*([A-Za-z0-9_ ()-]+)")
            val recordThreadStartRegex = Regex("^\\s*(?:Input thread|RecordThread|DirectRecordThread|MmapCaptureThread)\\b", RegexOption.IGNORE_CASE)
            val otherThreadHeaderRegex = Regex("^\\s*(?:Output thread|MixerThread|DirectOutputThread|OffloadThread|DuplicatingThread|PlaybackThread|Effect Chains|Global session)\\b", RegexOption.IGNORE_CASE)

            fun recordExcerpt(line: String) {
                if (excerptLines.size < 45) {
                    excerptLines.add(line.trim().take(220))
                }
            }

            val execRes = runProcessWithTimeout(
                proc = process,
                timeoutSec = 6L,
                maxOutputBytes = 1024 * 1024
            )
            execRes.stdout.lineSequence().let { lines ->
                var sectionLineCount = 0
                for (rawLine in lines) {
                        val line = rawLine.trimEnd()
                        when {
                            line == "===PHANTOM===" -> {
                                section = "PHANTOM"
                                sectionLineCount = 0
                                continue
                            }
                            line == "===AUDIO_SERVICE===" -> {
                                section = "AUDIO_SERVICE"
                                sectionLineCount = 0
                                continue
                            }
                            line == "===AUDIO_FLINGER===" -> {
                                section = "AUDIO_FLINGER"
                                sectionLineCount = 0
                                continue
                            }
                        }

                        sectionLineCount++
                        if (sectionLineCount > MAX_DUMPSYS_SECTION_LINES) {
                            if (section == "AUDIO_FLINGER") break else continue
                        }

                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue

                        when (section) {
                            "PHANTOM" -> {
                                if (maxPhantom == null && trimmed != "null") {
                                    maxPhantom = trimmed
                                }
                            }

                            "AUDIO_SERVICE" -> {
                                if (audioModeLine == null && (trimmed.contains("mMode=") || trimmed.startsWith("Mode:"))) {
                                    audioModeLine = audioModeRegex.find(trimmed)?.value ?: trimmed.take(80)
                                    recordExcerpt(trimmed)
                                }

                                val riid = riidRegex.find(trimmed)?.groupValues?.getOrNull(1)
                                val uid = uidRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                val sid = sessionRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                val src = srcRegex.find(trimmed)?.groupValues?.getOrNull(1)
                                val pack = packRegex.find(trimmed)?.groupValues?.getOrNull(1)
                                val silenced = silencedRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()

                                if (sid != null && (pack != null || uid != null || src != null)) {
                                    val hist = attributionHistoryBySession.getOrPut(sid) { MutableDumpsysRecord(sessionId = sid) }
                                    if (!pack.isNullOrBlank()) hist.packageName = pack
                                    if (uid != null) hist.uid = uid
                                    if (!src.isNullOrBlank()) hist.audioSource = src
                                }

                                if (stopReleaseRegex.containsMatchIn(trimmed)) {
                                    val key = riid?.let { "riid:$it" } ?: sid?.let { "sess:$it" }
                                    if (key != null) {
                                        audioServiceByKey.remove(key)?.sessionId?.let { audioServiceBySession.remove(it) }
                                    } else if (sid != null) {
                                        audioServiceBySession.remove(sid)
                                    }
                                } else if (startUpdateRegex.containsMatchIn(trimmed) ||
                                    (trimmed.contains("rec ", ignoreCase = true) && (sid != null || uid != null || pack != null))
                                ) {
                                    val key = riid?.let { "riid:$it" } ?: sid?.let { "sess:$it" } ?: uid?.let { "uid:$it" }
                                    if (key != null) {
                                        val rec = audioServiceByKey.getOrPut(key) { MutableDumpsysRecord() }
                                        if (!pack.isNullOrBlank()) rec.packageName = pack
                                        if (uid != null) rec.uid = uid
                                        if (sid != null) rec.sessionId = sid
                                        if (!src.isNullOrBlank()) rec.audioSource = src
                                        if (silenced != null) rec.isSilenced = silenced
                                        rec.isActive = true
                                        rec.sessionId?.let { audioServiceBySession[it] = rec }
                                        recordExcerpt(trimmed)
                                    }
                                }
                            }

                            "AUDIO_FLINGER" -> {
                                if (recordThreadStartRegex.containsMatchIn(trimmed) ||
                                    (trimmed.contains("thread", ignoreCase = true) && trimmed.contains("RECORD", ignoreCase = true))
                                ) {
                                    inRecordThread = true
                                    colSessionIdx = -1
                                    colUidIdx = -1
                                    colActiveIdx = -1
                                    colNameIdx = -1
                                    recordExcerpt(trimmed)
                                    continue
                                }
                                if (otherThreadHeaderRegex.containsMatchIn(trimmed)) {
                                    inRecordThread = false
                                    continue
                                }
                                if (!inRecordThread) continue

                                val lower = trimmed.lowercase()
                                if (lower.contains("session") && (lower.contains("client") || lower.contains("uid") || lower.contains("active"))) {
                                    val cols = trimmed.split(Regex("\\s+"))
                                    colSessionIdx = cols.indexOfFirst { it.equals("session", ignoreCase = true) || it.equals("sid", ignoreCase = true) }
                                    colUidIdx = cols.indexOfFirst { it.equals("uid", ignoreCase = true) }
                                    colActiveIdx = cols.indexOfFirst { it.equals("active", ignoreCase = true) }
                                    colNameIdx = cols.indexOfFirst { it.equals("client", ignoreCase = true) }
                                    continue
                                }

                                // Parse either key=value track line or tabular track row
                                val kvSid = sessionRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                val kvUid = uidRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                val kvPack = packRegex.find(trimmed)?.groupValues?.getOrNull(1)
                                val kvSrc = srcRegex.find(trimmed)?.groupValues?.getOrNull(1)

                                if (kvSid != null || kvUid != null) {
                                    val sid = kvSid ?: continue
                                    val rec = flingerBySession.getOrPut(sid) { MutableDumpsysRecord(sessionId = sid) }
                                    if (kvUid != null) rec.uid = kvUid
                                    if (!kvPack.isNullOrBlank()) rec.packageName = kvPack
                                    if (!kvSrc.isNullOrBlank()) rec.audioSource = kvSrc
                                    rec.isActive = !lower.contains("idle") && !lower.contains("stopped")
                                    recordExcerpt(trimmed)
                                } else if (colSessionIdx >= 0) {
                                    val parts = trimmed.split(Regex("\\s+"))
                                    if (parts.size > colSessionIdx) {
                                        val parsedSid = parts.getOrNull(colSessionIdx)?.toIntOrNull()
                                        if (parsedSid != null && parsedSid > 0) {
                                            val activeToken = parts.getOrNull(colActiveIdx)?.lowercase().orEmpty()
                                            val isTrackActive = activeToken in setOf("yes", "true", "active", "1")
                                            // Only read explicit Uid column; AOSP "Client" column holds PID, not UID.
                                            val uidCandidate = if (colUidIdx >= 0) parts.getOrNull(colUidIdx)?.toIntOrNull() else null
                                            val rec = flingerBySession.getOrPut(parsedSid) { MutableDumpsysRecord(sessionId = parsedSid) }
                                            if (uidCandidate != null) rec.uid = uidCandidate
                                            rec.isActive = isTrackActive || colActiveIdx < 0
                                            recordExcerpt(trimmed)
                                        }
                                    }
                                }
                        }
                    }
                }
            }
            if (execRes.timedOut) {
                Log.w(TAG, "dumpsys audio/flinger inspection timed out after 4s; using partial snapshot")
            }

            // Reconcile live sessions from OS AudioRecordingConfiguration + AudioFlinger + AudioService
            val candidateSessions = LinkedHashSet<Int>().apply {
                addAll(sysBySession.keys)
                addAll(flingerBySession.values.filter { it.isActive }.mapNotNull { it.sessionId })
                if (sysBySession.isEmpty() && flingerBySession.isEmpty()) {
                    addAll(audioServiceBySession.keys)
                } else {
                    audioServiceBySession.keys.filter { it in sysBySession || flingerBySession[it]?.isActive == true }.forEach { add(it) }
                }
            }

            val resolvedClients = mutableListOf<ActiveAudioClientInfo>()
            val seenPackagesWithoutSession = HashSet<String>()

            for (sid in candidateSessions) {
                val sysCfg = sysBySession[sid]
                val svcRec = audioServiceBySession[sid]
                val histRec = attributionHistoryBySession[sid]
                val flgRec = flingerBySession[sid]

                val uid = svcRec?.uid ?: histRec?.uid ?: flgRec?.uid
                val rawPkg = svcRec?.packageName?.takeIf { it.isNotBlank() }
                    ?: histRec?.packageName?.takeIf { it.isNotBlank() }
                    ?: flgRec?.packageName?.takeIf { it.isNotBlank() }
                    ?: uid?.let { resolvePackageForUid(appCtx, it) }
                    ?: uid?.let { "uid:$it" }
                    ?: "session:$sid"

                val sourceStr = sysCfg?.let { audioSourceToString(it.clientAudioSource) }
                    ?: svcRec?.audioSource
                    ?: histRec?.audioSource
                    ?: flgRec?.audioSource
                    ?: "UNKNOWN"

                val isSilenced = sysCfg?.isClientSilenced ?: svcRec?.isSilenced ?: false
                val isActive = sysCfg != null || flgRec?.isActive == true || svcRec?.isActive == true
                val isSelf = rawPkg == appCtx.packageName || (uid != null && uid == Process.myUid())
                val appLabel = resolveAppLabel(appCtx, rawPkg, uid)

                resolvedClients.add(
                    ActiveAudioClientInfo(
                        packageName = rawPkg,
                        appLabel = appLabel,
                        uid = uid,
                        sessionId = sid,
                        audioSource = sourceStr,
                        isSilenced = isSilenced,
                        isActive = isActive,
                        isSelf = isSelf
                    )
                )
                seenPackagesWithoutSession.add(rawPkg)
            }

            // Include any AudioService entries that had no session ID (e.g., OEM format omitting session)
            if (resolvedClients.isEmpty()) {
                audioServiceByKey.values.forEach { rec ->
                    val pkg = rec.packageName ?: rec.uid?.let { resolvePackageForUid(appCtx, it) } ?: return@forEach
                    if (pkg in seenPackagesWithoutSession) return@forEach
                    val isSelf = pkg == appCtx.packageName || (rec.uid != null && rec.uid == Process.myUid())
                    resolvedClients.add(
                        ActiveAudioClientInfo(
                            packageName = pkg,
                            appLabel = resolveAppLabel(appCtx, pkg, rec.uid),
                            uid = rec.uid,
                            sessionId = rec.sessionId,
                            audioSource = rec.audioSource ?: "UNKNOWN",
                            isSilenced = rec.isSilenced,
                            isActive = rec.isActive,
                            isSelf = isSelf
                        )
                    )
                }
            }

            val sortedClients = resolvedClients.sortedWith(
                compareBy<ActiveAudioClientInfo> { it.isSelf }
                    .thenByDescending { it.isActive }
                    .thenBy { it.isSilenced }
                    .thenBy { it.appLabel }
            )

            AudioInspectorSnapshot(
                capturedAtMs = capturedAt,
                audioModeLine = audioModeLine ?: audioManager?.let { "AudioManager.mode=${it.mode}" },
                clients = sortedClients,
                maxPhantomProcesses = maxPhantom,
                rawExcerpt = excerptLines.joinToString("\n"),
                error = null
            )
        } catch (e: Exception) {
            val errSummary = formatThrowableSummary(e)
            Log.w(TAG, "dumpsys audio inspection failed: $errSummary")
            AudioInspectorSnapshot(
                capturedAtMs = capturedAt,
                audioModeLine = audioManager?.let { "AudioManager.mode=${it.mode}" },
                clients = emptyList(),
                maxPhantomProcesses = null,
                rawExcerpt = "",
                error = "Inspection failed: $errSummary"
            )
        }
    }

    suspend fun grantAllPrivilegesViaShizuku(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val status = getShizukuStatus(context)
        if (status != ShizukuStatus.AUTHORIZED) {
            return@withContext Pair(
                false,
                "Shizuku service is not authorized (Status: $status). Please start Shizuku and grant permission first."
            )
        }

        val pkg = context.packageName
        val component = ComponentName(context, AmbientCaptureAccessibilityService::class.java)
        val serviceComponent = component.flattenToString()
        val shortComponent = component.flattenToShortString()

        val a11yAppendOrRebindCmd = if (!AmbientCaptureAccessibilityService.isSystemBound) {
            """
            CUR=${'$'}(settings get secure enabled_accessibility_services | tr -d '\r\n')
            SVC="$serviceComponent"
            SHORT="$shortComponent"
            CLEAN=""
            OLD_IFS="${'$'}IFS"
            IFS=':'
            for S in ${'$'}CUR; do
              case "${'$'}S" in
                ""|null|"${'$'}SVC"|"${'$'}SHORT") ;;
                *) CLEAN="${'$'}{CLEAN:+${'$'}CLEAN:}${'$'}S" ;;
              esac
            done
            IFS="${'$'}OLD_IFS"
            if [ -n "${'$'}CLEAN" ]; then
              settings put secure enabled_accessibility_services "${'$'}CLEAN"
              sleep 1
              settings put secure enabled_accessibility_services "${'$'}CLEAN:${'$'}SVC"
            else
              settings put secure enabled_accessibility_services ""
              sleep 1
              settings put secure enabled_accessibility_services "${'$'}SVC"
            fi
            settings put secure accessibility_enabled 1
            """.trimIndent()
        } else {
            """
            CUR=${'$'}(settings get secure enabled_accessibility_services | tr -d '\r\n')
            SVC="$serviceComponent"
            SHORT="$shortComponent"
            case ":${'$'}CUR:" in
              *":${'$'}SVC:"*|*":${'$'}SHORT:"*) NEW="${'$'}CUR" ;;
              ":null:"|"::") NEW="${'$'}SVC" ;;
              *) NEW="${'$'}CUR:${'$'}SVC" ;;
            esac
            settings put secure enabled_accessibility_services "${'$'}NEW"
            settings put secure accessibility_enabled 1
            """.trimIndent()
        }

        val commands = listOf(
            AdbCommandSpec(
                "Grant Runtime RECORD_AUDIO",
                "pm grant --user 0 $pkg android.permission.RECORD_AUDIO || pm grant $pkg android.permission.RECORD_AUDIO"
            ),
            AdbCommandSpec(
                "Grant Runtime READ_PHONE_STATE",
                "pm grant --user 0 $pkg android.permission.READ_PHONE_STATE || pm grant $pkg android.permission.READ_PHONE_STATE",
                optional = true
            ),
            AdbCommandSpec(
                "Grant Runtime POST_NOTIFICATIONS (Meeting Controls)",
                "pm grant --user 0 $pkg android.permission.POST_NOTIFICATIONS || pm grant $pkg android.permission.POST_NOTIFICATIONS",
                optional = true
            ),
            AdbCommandSpec(
                "Grant WRITE_SECURE_SETTINGS (Zero-Touch A11y Self-Heal)",
                "pm grant --user 0 $pkg android.permission.WRITE_SECURE_SETTINGS || pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
            ),
            AdbCommandSpec(
                "Allow Background RECORD_AUDIO AppOp",
                "cmd appops set --user 0 $pkg RECORD_AUDIO allow"
            ),
            AdbCommandSpec(
                "Unlock ACCESS_RESTRICTED_SETTINGS (Android 13-16 Sideloads)",
                "cmd appops set --user 0 $pkg ACCESS_RESTRICTED_SETTINGS allow",
                optional = true
            ),
            AdbCommandSpec(
                "Allow SYSTEM_ALERT_WINDOW (Overlay Fallback)",
                "cmd appops set --user 0 $pkg SYSTEM_ALERT_WINDOW allow",
                optional = true
            ),
            AdbCommandSpec(
                "Allow PROJECT_MEDIA AppOp (Silent Prompt Bypass)",
                "cmd appops set --user 0 $pkg PROJECT_MEDIA allow"
            ),
            AdbCommandSpec(
                "Allow RUN_ANY_IN_BACKGROUND",
                "cmd appops set --user 0 $pkg RUN_ANY_IN_BACKGROUND allow"
            ),
            AdbCommandSpec(
                "Allow RUN_IN_BACKGROUND",
                "cmd appops set --user 0 $pkg RUN_IN_BACKGROUND allow"
            ),
            AdbCommandSpec(
                "Allow WAKE_LOCK AppOp",
                "cmd appops set --user 0 $pkg WAKE_LOCK allow",
                optional = true
            ),
            AdbCommandSpec(
                "Exempt AUTO_REVOKE_PERMISSIONS_IF_UNUSED",
                "cmd appops set --user 0 $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore",
                optional = true
            ),
            AdbCommandSpec(
                "Allow GET_USAGE_STATS (Foreground App Attribution)",
                "cmd appops set --user 0 $pkg GET_USAGE_STATS allow",
                optional = true
            ),
            AdbCommandSpec(
                "Whitelist Doze & Cached Freezer",
                "cmd deviceidle whitelist +$pkg || dumpsys deviceidle whitelist +$pkg"
            ),
            AdbCommandSpec(
                "Force Active Standby Bucket",
                "am set-standby-bucket --user 0 $pkg active || am set-standby-bucket $pkg active",
                optional = true
            ),
            AdbCommandSpec(
                "Disable Phantom Process Monitor (Android 14-16)",
                "settings put global $SETTING_MONITOR_PHANTOM_PROCS false",
                optional = true
            ),
            AdbCommandSpec(
                "Set Max Phantom Processes = 2147483647",
                "device_config put activity_manager max_phantom_processes 2147483647",
                optional = true
            ),
            AdbCommandSpec(
                "Append & Bind AccessibilityService",
                a11yAppendOrRebindCmd
            )
        )

        val logBuilder = StringBuilder()
        var allRequiredSuccess = true
        var optionalSkipped = 0
        var sawSecurityException = false

        for (spec in commands) {
            try {
                logBuilder.append("[").append(spec.label).append("]\n")
                logBuilder.append("> ").append(spec.command).append("\n")
                val process = executeShizukuProcess(spec.command)
                val execRes = runProcessWithTimeout(process, timeoutSec = 15L)
                val out = execRes.stdout
                val err = execRes.stderr
                val finished = !execRes.timedOut
                val code = execRes.exitCode
                if (out.isNotEmpty()) logBuilder.append(out).append("\n")
                if (!finished) {
                    logBuilder.append("Timed out after 15s\n")
                }
                if (err.isNotEmpty()) {
                    if (spec.optional && code != 0) {
                        logBuilder.append("[Optional OEM-restricted] ").append(err).append("\n")
                    } else {
                        logBuilder.append("Error: ").append(err).append("\n")
                    }
                    if (!spec.optional &&
                        (err.contains("SecurityException", ignoreCase = true) ||
                            err.contains("WRITE_SECURE_SETTINGS", ignoreCase = true))
                    ) {
                        sawSecurityException = true
                    }
                }
                if (code != 0) {
                    if (spec.optional) {
                        optionalSkipped++
                        logBuilder.append("Skipped optional step (exit=$code)\n")
                    } else {
                        allRequiredSuccess = false
                        logBuilder.append("Failed with exit code: ").append(code).append("\n")
                    }
                }
            } catch (e: Exception) {
                val errSummary = formatThrowableSummary(e)
                if (spec.optional) {
                    optionalSkipped++
                    logBuilder.append("Optional step exception: ").append(errSummary).append("\n")
                } else {
                    allRequiredSuccess = false
                    logBuilder.append("Exception: ").append(errSummary).append("\n")
                }
            }
        }

        // Allow OS AccessibilityManager a moment to finish binding before sampling post-grant telemetry
        kotlinx.coroutines.delay(450L)

        // Run live post-grant verification & dumpsys audio snapshot right inside the 1-tap log
        val snapshot = inspectActiveAudioClientsViaShizuku(context)
        val diagAfter = checkDiagnostics(context)
        logBuilder.append("\n=== POST-GRANT VERIFICATION ===\n")
        logBuilder.append("• WRITE_SECURE_SETTINGS Self-Heal: ").append(if (diagAfter.runtimeWriteSecureSettings) "GRANTED" else "DENIED").append("\n")
        logBuilder.append("• Accessibility Bound: ").append(diagAfter.accessibilityServiceBound).append("\n")
        logBuilder.append("• AppOps RECORD_AUDIO allow: ").append(diagAfter.appOpsRecordAudioAllowed).append("\n")
        logBuilder.append("• AppOps PROJECT_MEDIA allow: ").append(diagAfter.appOpsProjectMedia).append("\n")
        logBuilder.append("• AppOps Auto-Revoke Exempt: ").append(diagAfter.appOpsAutoRevokeExempt).append("\n")
        logBuilder.append("• Privileged 2-Way Call Audio (CAPTURE_AUDIO_OUTPUT): ")
            .append(if (diagAfter.runtimeCaptureAudioOutput) "GRANTED (VOICE_CALL active)" else "A11y Fallback (Non-Root / Non-System)").append("\n")
        logBuilder.append("• Phantom Monitor Disabled: ").append(diagAfter.phantomProcessMonitorDisabled)
            .append(" | max_phantom_processes=").append(snapshot.maxPhantomProcesses ?: "unknown").append("\n")
        logBuilder.append("• Audio Mode: ").append(snapshot.audioModeLine ?: "unknown").append("\n")
        logBuilder.append("• Active Mic Clients (${snapshot.clients.size}): ")
        if (snapshot.clients.isEmpty()) {
            logBuilder.append("None currently active\n")
        } else {
            logBuilder.append("\n")
            snapshot.clients.forEach { c ->
                logBuilder.append("   - ${c.appLabel} (${c.packageName}) uid=${c.uid ?: "?"} sess=${c.sessionId ?: "?"} src=${c.audioSource} active=${c.isActive} silenced=${c.isSilenced}\n")
            }
        }
        if (optionalSkipped > 0) {
            logBuilder.append("• Optional OEM-guarded commands skipped: ").append(optionalSkipped).append("\n")
        }

        if (sawSecurityException) {
            logBuilder.append(
                "\n💡 Xiaomi/HyperOS Hint: Enable Developer Options -> 'USB debugging (Security settings)' before running ADB/Shizuku commands.\n"
            )
        }

        Pair(allRequiredSuccess, logBuilder.toString().trim())
    }

    fun getStandaloneAdbScript(context: Context): String {
        val pkg = context.packageName
        val component = ComponentName(context, AmbientCaptureAccessibilityService::class.java)
        val serviceComponent = component.flattenToString()
        val shortComponent = component.flattenToShortString()
        return """
            #!/usr/bin/env bash
            # Ambient Brain Zero-Notification 24/7 Mic Sharing, Self-Healing & Freezer Bypass (Android 14-16)
            set -euo pipefail
            PKG="$pkg"
            A11Y_FULL="$serviceComponent"
            A11Y_SHORT="$shortComponent"

            echo "[1/5] Granting Runtime Permissions & Zero-Touch Self-Heal (WRITE_SECURE_SETTINGS)..."
            adb shell pm grant --user 0 "${'$'}PKG" android.permission.RECORD_AUDIO || adb shell pm grant "${'$'}PKG" android.permission.RECORD_AUDIO
            adb shell pm grant --user 0 "${'$'}PKG" android.permission.READ_PHONE_STATE || adb shell pm grant "${'$'}PKG" android.permission.READ_PHONE_STATE || true
            adb shell pm grant --user 0 "${'$'}PKG" android.permission.POST_NOTIFICATIONS || adb shell pm grant "${'$'}PKG" android.permission.POST_NOTIFICATIONS || true
            adb shell pm grant --user 0 "${'$'}PKG" android.permission.WRITE_SECURE_SETTINGS || adb shell pm grant "${'$'}PKG" android.permission.WRITE_SECURE_SETTINGS

            echo "[2/5] Configuring AppOps (Mic + Restricted Settings + Overlay + PROJECT_MEDIA + UsageStats)..."
            adb shell cmd appops set --user 0 "${'$'}PKG" RECORD_AUDIO allow
            adb shell cmd appops set --user 0 "${'$'}PKG" ACCESS_RESTRICTED_SETTINGS allow || true
            adb shell cmd appops set --user 0 "${'$'}PKG" SYSTEM_ALERT_WINDOW allow || true
            adb shell cmd appops set --user 0 "${'$'}PKG" PROJECT_MEDIA allow
            adb shell cmd appops set --user 0 "${'$'}PKG" RUN_ANY_IN_BACKGROUND allow
            adb shell cmd appops set --user 0 "${'$'}PKG" RUN_IN_BACKGROUND allow
            adb shell cmd appops set --user 0 "${'$'}PKG" WAKE_LOCK allow || true
            adb shell cmd appops set --user 0 "${'$'}PKG" AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore || true
            adb shell cmd appops set --user 0 "${'$'}PKG" GET_USAGE_STATS allow || true

            echo "[3/5] Bypassing Android 14-16 Cached Freezer & Phantom Process Killer..."
            adb shell "cmd deviceidle whitelist +${'$'}PKG || dumpsys deviceidle whitelist +${'$'}PKG"
            adb shell am set-standby-bucket --user 0 "${'$'}PKG" active || adb shell am set-standby-bucket "${'$'}PKG" active || true
            adb shell settings put global $SETTING_MONITOR_PHANTOM_PROCS false || true
            adb shell device_config put activity_manager max_phantom_processes 2147483647 || true

            echo "[4/5] Appending AccessibilityService without overwriting existing services..."
            CUR=${'$'}( (adb shell settings get secure enabled_accessibility_services || true) | tr -d '\r\n' )
            CLEAN=${'$'}(printf '%s\n' "${'$'}CUR" | awk -v a="${'$'}A11Y_FULL" -v b="${'$'}A11Y_SHORT" -F: '{
              out = ""
              for (i = 1; i <= NF; i++) {
                gsub(/^[[:space:]]+|[[:space:]]+$/, "", ${'$'}i)
                if (${'$'}i != "" && ${'$'}i != "null" && ${'$'}i != a && ${'$'}i != b) {
                  out = (out == "" ? ${'$'}i : out ":" ${'$'}i)
                }
              }
              print out
            }')
            if [[ -n "${'$'}CLEAN" ]]; then
              adb shell settings put secure enabled_accessibility_services "${'$'}CLEAN"
              sleep 1
              adb shell settings put secure enabled_accessibility_services "${'$'}CLEAN:${'$'}A11Y_FULL"
            else
              adb shell settings put secure enabled_accessibility_services ""
              sleep 1
              adb shell settings put secure enabled_accessibility_services "${'$'}A11Y_FULL"
            fi
            adb shell settings put secure accessibility_enabled 1

            echo "[5/5] Verification:"
            adb shell cmd appops get "${'$'}PKG" | grep -E "RECORD_AUDIO|ACCESS_RESTRICTED_SETTINGS|SYSTEM_ALERT_WINDOW|PROJECT_MEDIA|RUN_ANY_IN_BACKGROUND|AUTO_REVOKE|GET_USAGE_STATS" || true
            PHANTOM_MON=${'$'}( (adb shell settings get global $SETTING_MONITOR_PHANTOM_PROCS 2>/dev/null || true) | tr -d '\r\n' )
            PHANTOM_MAX=${'$'}( (adb shell device_config get activity_manager max_phantom_processes 2>/dev/null || true) | tr -d '\r\n' )
            echo "Phantom Monitor: ${'$'}{PHANTOM_MON:-unset} | Max Phantom: ${'$'}{PHANTOM_MAX:-unset}"
            echo "Done! Reboot not required. Open Ambient Brain -> Diagnostics to verify all shields."
        """.trimIndent()
    }
}
