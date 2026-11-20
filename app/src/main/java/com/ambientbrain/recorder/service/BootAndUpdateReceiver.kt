package com.ambientbrain.recorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ambientbrain.recorder.data.AudioPurgeWorker
import com.ambientbrain.recorder.data.PreferencesRepo
import java.util.concurrent.Executors

/**
 * Silent zero-notification receiver invoked on device boot or app upgrade.
 * If listening is enabled and `WRITE_SECURE_SETTINGS` was granted via Shizuku or ADB,
 * self-heals `AmbientCaptureAccessibilityService` directly so the OS re-binds 24/7
 * capture automatically with zero foreground notifications.
 */
class BootAndUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        executor.execute {
            try {
                AudioPurgeWorker.scheduleDaily(appContext)
                AdbVirtualScreenController.restorePendingAppOpsMicLockIfAny(appContext)
                val prefs = PreferencesRepo(appContext)
                if (prefs.isListeningEnabled) {
                    val healResult = ShizukuAdbManager.selfHealAccessibilityService(
                        context = appContext,
                        forceRebindIfUnbound = true,
                        bypassCooldown = true
                    )
                    Log.i(TAG, "Self-heal on $action: ${healResult.message}")
                    if (AmbientCaptureAccessibilityService.isSystemBound) {
                        AmbientCaptureAccessibilityService.startListening(appContext)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "BootAndUpdateReceiver error on $action: ${e.message}")
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    companion object {
        private const val TAG = "BootAndUpdateReceiver"
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AmbientBrain-BootSelfHeal").apply {
                isDaemon = true
            }
        }
    }
}
