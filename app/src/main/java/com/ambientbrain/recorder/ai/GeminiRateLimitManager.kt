package com.ambientbrain.recorder.ai

import android.util.Log
import com.ambientbrain.recorder.data.ApiKeyHealth
import com.ambientbrain.recorder.data.GeminiRateLimitProfile
import com.ambientbrain.recorder.data.GeminiRateLimitSnapshot
import com.ambientbrain.recorder.data.PreferencesRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/**
 * Live rolling-window telemetry exposed to the UI for RPM/TPM rate-limit visibility.
 */
data class LiveRateLimitTelemetry(
    val activeModel: String = PreferencesRepo.DEFAULT_MODEL,
    val activeSourceLabel: String = "Default (Nov 20, 2026)",
    val detectedTierLabel: String = "Free Tier",
    val usedRpmInWindow: Int = 0,
    val safeRpmLimit: Int = 8,
    val rawRpmLimit: Int = 10,
    val usedTpmInWindow: Long = 0L,
    val safeTpmLimit: Long = 200_000L,
    val rawTpmLimit: Long = 250_000L,
    val activeKeyCount: Int = 0,
    val availableKeyCount: Int = 0,
    val rpmSafetyPercent: Int = 85,
    val tpmSafetyPercent: Int = 80,
    val nextDailyResetMs: Long = 0L,
    val lastActionNote: String = "Ready",
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class StrategyKeyDecision(
    val orderedKeys: List<String>,
    val waitBeforeFirstKeyMs: Long,
    val recommendProactiveFallback: Boolean,
    val strategyNote: String,
    val effectiveProfile: GeminiRateLimitProfile,
    val availableKeyCount: Int = 0
)

/**
 * Handle returned by [GeminiRateLimitManager.acquireSlotBeforeCall] so callers can either
 * reconcile actual prompt tokens on success or immediately reclaim phantom TPM tokens on failure.
 */
data class SlotReservation(
    val reservationId: Long,
    val apiKey: String,
    val model: String,
    val estimatedTokens: Long
)

/**
 * Adaptive RPM & TPM Rate-Limit Strategy Engine for Google AI Studio Gemini API Key Pools.
 *
 * Key capabilities:
 *  - Ships with a curated **Nov 20, 2026** default per-model RPM/TPM/RPD reference table
 *    (covering Free Tier and Paid Tier 1+ models).
 *  - Maintains a thread-safe 60-second rolling window ledger of requests (RPM) and input tokens
 *    (TPM) per `(apiKeyFingerprint, model)` plus Pacific Time (`America/Los_Angeles`) daily RPD tracking.
 *  - Automatically marks ALL API keys available again at **00:00 Midnight Pacific Time** (when Google
 *    AI Studio resets daily RPD quotas, automatically handling PST/PDT daylight saving transitions).
 *  - Dynamically scales RPM/TPM safety margins based on truly available pool capacity:
 *      * **> 3 available keys (4+)** -> **90% RPM & 90% TPM** (higher throughput & larger audio batches)
 *      * **<= 3 available keys** -> **85% RPM & 80% TPM** (extra protection against single-key 429 impact)
 *  - Uses elastic load-aware pacing (fast burst dispatch below 50% window load, smooth proportional
 *    pacing above 50%) and rolls back phantom TPM tokens immediately if an HTTP call fails.
 */
object GeminiRateLimitManager {

    private const val TAG = "GeminiRateLimit"
    const val DEFAULT_REFERENCE_DATE = "2026-11-20"
    private const val WINDOW_MS = 60_000L

    // Google AI Studio resets daily RPD limits at Midnight Pacific Time (PST/PDT)
    val GOOGLE_RESET_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

    // Dynamic safety headroom factors based on number of available healthy keys for the target model
    private const val HIGH_POOL_AVAILABLE_KEY_THRESHOLD = 3 // Strictly > 3 (i.e. 4+ available keys)
    private const val RPM_SAFETY_FACTOR_BASE = 0.85
    private const val TPM_SAFETY_FACTOR_BASE = 0.80
    private const val RPM_SAFETY_FACTOR_HIGH_POOL = 0.90
    private const val TPM_SAFETY_FACTOR_HIGH_POOL = 0.90

    // Maximum inline pacing sleep before proactively failing over to Fallback Model
    private const val MAX_INLINE_WAIT_BEFORE_FALLBACK_MS = 22_000L
    private const val MAX_INLINE_WAIT_SINGLE_MODEL_MS = 45_000L

    private data class UsageEvent(
        val id: Long,
        val atMs: Long,
        val inputTokens: Long
    )

    private val ledgerLock = Any()
    private val nextReservationId = AtomicLong(1L)
    private val windowEventsByKeyModel = ConcurrentHashMap<String, MutableList<UsageEvent>>()
    private val dailyEventsByKeyModel = ConcurrentHashMap<String, MutableList<Long>>()
    private val lastRequestAtByKeyModel = ConcurrentHashMap<String, Long>()

    private val _telemetry = MutableStateFlow(LiveRateLimitTelemetry())
    val telemetry: StateFlow<LiveRateLimitTelemetry> = _telemetry.asStateFlow()

    /**
     * Current calendar date in Pacific Time (`America/Los_Angeles`), e.g. `"2026-11-20"`.
     */
    fun currentPacificDate(nowMs: Long = System.currentTimeMillis()): LocalDate {
        return Instant.ofEpochMilli(nowMs).atZone(GOOGLE_RESET_ZONE).toLocalDate()
    }

    /**
     * Epoch timestamp of the start of the current Pacific Time day (`00:00 America/Los_Angeles`).
     */
    fun startOfCurrentPacificDayEpochMs(nowMs: Long = System.currentTimeMillis()): Long {
        return currentPacificDate(nowMs)
            .atStartOfDay(GOOGLE_RESET_ZONE)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Epoch timestamp of the next Google Gemini API daily quota reset (`00:00 America/Los_Angeles` tomorrow).
     */
    fun nextMidnightPacificEpochMs(nowMs: Long = System.currentTimeMillis()): Long {
        return currentPacificDate(nowMs)
            .plusDays(1)
            .atStartOfDay(GOOGLE_RESET_ZONE)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Ensures that once Google's daily reset boundary (`00:00 America/Los_Angeles`) passes,
     * all API keys are automatically marked available again and daily RPD counters reset cleanly.
     */
    fun ensureDailyResetIfNeeded(prefs: PreferencesRepo, nowMs: Long = System.currentTimeMillis()): Boolean {
        val currentPtDateStr = currentPacificDate(nowMs).toString()
        val lastResetStr = prefs.lastRateLimitResetPtDate
        if (lastResetStr == currentPtDateStr) return false

        val restoredCount = if (lastResetStr.isNotBlank()) {
            prefs.resetKeysForGoogleDailyReset(currentPtDateStr)
        } else {
            prefs.lastRateLimitResetPtDate = currentPtDateStr
            0
        }

        synchronized(ledgerLock) {
            dailyEventsByKeyModel.clear()
        }

        if (restoredCount > 0) {
            Log.i(TAG, "Google Midnight Pacific Time ($currentPtDateStr PT) reset triggered: marked $restoredCount API key(s) available again.")
            return true
        }
        return false
    }

    /**
     * Built-in Nov 20, 2026 default reference rate limits (Google AI Studio Free Tier & Tier 1).
     * Free Tier defaults are intentionally conservative to prevent 429 bursts on multi-clip audio.
     */
    fun getDefaultNov2026Profiles(): List<GeminiRateLimitProfile> = listOf(
        GeminiRateLimitProfile(
            modelId = "gemini-4-hydrogen",
            freeRpm = 10,
            freeTpm = 250_000L,
            freeRpd = 500,
            paidRpm = 1_000,
            paidTpm = 1_000_000L,
            paidRpd = 10_000,
            inputTokenLimit = 1_048_576L,
            source = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026
        ),
        GeminiRateLimitProfile(
            modelId = "gemini-3.8-flash",
            freeRpm = 10,
            freeTpm = 250_000L,
            freeRpd = 250,
            paidRpm = 1_000,
            paidTpm = 1_000_000L,
            paidRpd = 10_000,
            inputTokenLimit = 1_048_576L,
            source = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026
        ),
        GeminiRateLimitProfile(
            modelId = "gemini-3.5-flash-lite",
            freeRpm = 15,
            freeTpm = 250_000L,
            freeRpd = 1_000,
            paidRpm = 4_000,
            paidTpm = 4_000_000L,
            paidRpd = 100_000,
            inputTokenLimit = 1_048_576L,
            source = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026
        ),
        GeminiRateLimitProfile(
            modelId = "gemini-3.6-flash",
            freeRpm = 10,
            freeTpm = 250_000L,
            freeRpd = 250,
            paidRpm = 1_000,
            paidTpm = 1_000_000L,
            paidRpd = 10_000,
            inputTokenLimit = 1_048_576L,
            source = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026
        ),
        GeminiRateLimitProfile(
            modelId = "gemini-3.1-flash-lite",
            freeRpm = 15,
            freeTpm = 250_000L,
            freeRpd = 1_000,
            paidRpm = 4_000,
            paidTpm = 4_000_000L,
            paidRpd = 100_000,
            inputTokenLimit = 1_048_576L,
            source = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026
        ),
        GeminiRateLimitProfile(
            modelId = "gemini-2.5-flash-lite",
            freeRpm = 15,
            freeTpm = 250_000L,
            freeRpd = 1_000,
            paidRpm = 4_000,
            paidTpm = 4_000_000L,
            paidRpd = 100_000,
            inputTokenLimit = 1_048_576L,
            source = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026
        ),
        GeminiRateLimitProfile(
            modelId = "gemini-4-argon",
            freeRpm = 0,
            freeTpm = 0L,
            freeRpd = 0,
            paidRpm = 150,
            paidTpm = 2_000_000L,
            paidRpd = 10_000,
            inputTokenLimit = 1_048_576L,
            source = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026
        ),
        GeminiRateLimitProfile(
            modelId = "gemini-4-carbon",
            freeRpm = 0,
            freeTpm = 0L,
            freeRpd = 0,
            paidRpm = 150,
            paidTpm = 2_000_000L,
            paidRpd = 10_000,
            inputTokenLimit = 1_048_576L,
            source = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026
        )
    )

    fun defaultSnapshotNov2026(): GeminiRateLimitSnapshot = GeminiRateLimitSnapshot(
        referenceDate = DEFAULT_REFERENCE_DATE,
        source = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026,
        detectedTier = GeminiRateLimitSnapshot.TIER_FREE,
        checkedAtMs = 0L,
        statusSummary = "Built-in Nov 20, 2026 default reference (Free Tier conservative pacing)",
        profiles = getDefaultNov2026Profiles()
    )

    fun getDefaultProfileForModel(modelId: String): GeminiRateLimitProfile {
        val normalized = PreferencesRepo.normalizeSupportedModel(modelId)
        getDefaultNov2026Profiles().firstOrNull { it.modelId.equals(normalized, ignoreCase = true) }?.let {
            return it
        }
        val lower = normalized.lowercase()
        val isLite = lower.contains("lite")
        val isPaidFlagship = !PreferencesRepo.isModelFreeTierEligible(normalized)
        return when {
            isPaidFlagship -> GeminiRateLimitProfile(
                modelId = normalized,
                freeRpm = 0,
                freeTpm = 0L,
                freeRpd = 0,
                paidRpm = 150,
                paidTpm = 2_000_000L,
                paidRpd = 10_000,
                source = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026
            )
            isLite -> GeminiRateLimitProfile(
                modelId = normalized,
                freeRpm = 15,
                freeTpm = 250_000L,
                freeRpd = 1_000,
                paidRpm = 4_000,
                paidTpm = 4_000_000L,
                paidRpd = 100_000,
                source = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026
            )
            else -> GeminiRateLimitProfile(
                modelId = normalized,
                freeRpm = 10,
                freeTpm = 250_000L,
                freeRpd = 250,
                paidRpm = 1_000,
                paidTpm = 1_000_000L,
                paidRpd = 10_000,
                source = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026
            )
        }
    }

    /**
     * Resolves the currently active [GeminiRateLimitSnapshot] honoring the user's
     * "Reset / Use Default Reference (Nov 20, 2026)" toggle.
     */
    fun getActiveSnapshot(prefs: PreferencesRepo): GeminiRateLimitSnapshot {
        if (prefs.useDefaultRateLimitReference) {
            return defaultSnapshotNov2026()
        }
        return prefs.getLiveRateLimitSnapshot() ?: defaultSnapshotNov2026()
    }

    fun getActiveProfileForModel(prefs: PreferencesRepo, modelId: String): Pair<GeminiRateLimitProfile, String> {
        val snapshot = getActiveSnapshot(prefs)
        val normalized = PreferencesRepo.normalizeSupportedModel(modelId)
        val matched = snapshot.profiles.firstOrNull { it.modelId.equals(normalized, ignoreCase = true) }
            ?: getDefaultProfileForModel(normalized)
        return matched to snapshot.detectedTier
    }

    fun effectiveRawLimits(profile: GeminiRateLimitProfile, detectedTier: String): Triple<Int, Long, Int> {
        val isPaid = detectedTier == GeminiRateLimitSnapshot.TIER_PAID_1
        return if (isPaid) {
            Triple(
                profile.paidRpm.coerceAtLeast(1),
                profile.paidTpm.coerceAtLeast(32_000L),
                profile.paidRpd.coerceAtLeast(100)
            )
        } else {
            val rpm = if (profile.freeRpm > 0) profile.freeRpm else profile.paidRpm.coerceAtMost(5)
            val tpm = if (profile.freeTpm > 0L) profile.freeTpm else profile.paidTpm.coerceAtMost(125_000L)
            val rpd = if (profile.freeRpd > 0) profile.freeRpd else profile.paidRpd.coerceAtMost(50)
            Triple(rpm.coerceAtLeast(1), tpm.coerceAtLeast(32_000L), rpd.coerceAtLeast(10))
        }
    }

    /**
     * Counts how many saved API keys are currently available (healthy/untested, not in cooldown,
     * not in auth error, and not RPD-exhausted for the current Pacific Time day) for [modelId].
     */
    fun countAvailableKeysForModel(
        prefs: PreferencesRepo,
        modelId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        ensureDailyResetIfNeeded(prefs, nowMs)
        val normalized = PreferencesRepo.normalizeSupportedModel(modelId)
        val allKeys = prefs.getAllApiKeys()
        if (allKeys.isEmpty()) return 0

        val (profile, tier) = getActiveProfileForModel(prefs, normalized)
        val (_, _, rawRpd) = effectiveRawLimits(profile, tier)
        val healthMap = prefs.getApiKeyHealthMap()

        return synchronized(ledgerLock) {
            allKeys.count { k ->
                val h = healthMap[PreferencesRepo.apiKeyFingerprint(k)]
                val isAuthErr = h?.status == ApiKeyHealth.STATUS_AUTH_ERROR
                val isCoolingDown = (h?.activeCooldownUntilForModel(normalized, nowMs) ?: 0L) > nowMs
                val daily = dailyEventsByKeyModel[bucketKey(k, normalized)]
                if (daily != null) pruneDailyLocked(daily, nowMs)
                val rpdUsed = daily?.size ?: 0
                !isAuthErr && !isCoolingDown && rpdUsed < rawRpd
            }
        }
    }

    /**
     * Dynamic RPM safety factor: extends to 90% when strictly more than 3 API keys are available,
     * keeping 85% when <= 3 keys are available.
     */
    fun rpmSafetyFactorForPool(availableKeysCount: Int): Double {
        return if (availableKeysCount > HIGH_POOL_AVAILABLE_KEY_THRESHOLD) {
            RPM_SAFETY_FACTOR_HIGH_POOL
        } else {
            RPM_SAFETY_FACTOR_BASE
        }
    }

    /**
     * Dynamic TPM safety factor: extends to 90% when strictly more than 3 API keys are available,
     * keeping 80% when <= 3 keys are available.
     */
    fun tpmSafetyFactorForPool(availableKeysCount: Int): Double {
        return if (availableKeysCount > HIGH_POOL_AVAILABLE_KEY_THRESHOLD) {
            TPM_SAFETY_FACTOR_HIGH_POOL
        } else {
            TPM_SAFETY_FACTOR_BASE
        }
    }

    fun safeRpmTarget(rawRpm: Int, availableKeysCount: Int = 1): Int {
        if (rawRpm <= 2) return rawRpm.coerceAtLeast(1)
        val factor = rpmSafetyFactorForPool(availableKeysCount)
        return (rawRpm * factor).toInt().coerceAtLeast(1)
    }

    fun safeTpmTarget(rawTpm: Long, availableKeysCount: Int = 1): Long {
        val factor = tpmSafetyFactorForPool(availableKeysCount)
        return (rawTpm * factor).toLong().coerceAtLeast(16_000L)
    }

    /**
     * Full smoothing spacing between consecutive requests on the same key when load is high (>=80%).
     */
    fun minSpacingMsForRpm(safeRpm: Int): Long {
        if (safeRpm >= 60) return 400L
        return (WINDOW_MS / safeRpm.coerceAtLeast(1)).coerceIn(800L, 12_000L)
    }

    /**
     * Elastic load-aware spacing:
     *  - Below 50% window load: minimal jitter guard (800ms for >3 keys, 1200ms otherwise) for fast bursts.
     *  - 50%..80% window load: 50% proportional pacing.
     *  - >= 80% window load: full smoothing gap so traffic glides cleanly up to the safe ceiling.
     */
    private fun elasticSpacingMs(safeRpm: Int, currentLoadRatio: Double, availableKeysCount: Int): Long {
        val fullGap = minSpacingMsForRpm(safeRpm)
        return when {
            currentLoadRatio < 0.50 -> if (availableKeysCount > HIGH_POOL_AVAILABLE_KEY_THRESHOLD) 800L else 1_200L
            currentLoadRatio < 0.80 -> (fullGap / 2L).coerceAtLeast(1_000L)
            else -> fullGap
        }
    }

    /**
     * Dynamically caps per-request audio payload bytes based on the active model's pool-aware TPM budget
     * (32 kbps ADTS AAC ≈ 4,000 bytes/sec ≈ 32 audio tokens/sec -> ~125 bytes per token).
     * When > 3 API keys are available (90% TPM margin), audio batches expand by ~12.5%, reducing splits & RPM usage.
     */
    fun recommendedMaxAudioBatchBytes(
        prefs: PreferencesRepo,
        modelId: String,
        baseLimitBytes: Long
    ): Long {
        val availableKeys = countAvailableKeysForModel(prefs, modelId)
        val (profile, tier) = getActiveProfileForModel(prefs, modelId)
        val (_, rawTpm, _) = effectiveRawLimits(profile, tier)
        val safeTpm = safeTpmTarget(rawTpm, availableKeys)
        // Reserve ~14,000 tokens for system instruction, glossary, and voice reference clips
        val availableAudioTokens = (safeTpm - 14_000L).coerceAtLeast(24_000L)
        // 32 audio tokens/sec * 4,110 bytes/sec -> ~128 bytes/token
        val tpmCappedBytes = (availableAudioTokens * 128L).coerceAtLeast(2_500_000L)
        return minOf(baseLimitBytes, tpmCappedBytes)
    }

    /**
     * Estimates input tokens for a Gemini request before transmission:
     * - Text: ~1 token per 3.5 characters (slightly conservative for bilingual Thai/English)
     * - Audio (.aac 32 kbps / .m4a): ~32 tokens per second of audio
     */
    fun estimateInputTokens(
        systemInstruction: String,
        promptText: String,
        audioFiles: List<File> = emptyList(),
        referenceVoiceFiles: List<File> = emptyList()
    ): Long {
        val textTokens = ((systemInstruction.length + promptText.length) / 3.5).toLong() + 300L
        var audioSec = 0L
        for (f in audioFiles) {
            if (f.exists() && f.length() > 0L) {
                audioSec += (f.length() / 4_110L).coerceAtLeast(1L)
            }
        }
        for (f in referenceVoiceFiles) {
            if (f.exists() && f.length() > 0L) {
                // Voice profile clips are 64 kbps (~8,200 bytes/sec)
                audioSec += (f.length() / 8_200L).coerceAtLeast(1L)
            }
        }
        val audioTokens = audioSec * 32L
        return (textTokens + audioTokens).coerceAtLeast(256L)
    }

    private fun bucketKey(apiKey: String, model: String): String {
        val fp = PreferencesRepo.apiKeyFingerprint(apiKey)
        val norm = PreferencesRepo.normalizeSupportedModel(model)
        return "$fp|$norm"
    }

    private fun pruneWindowLocked(list: MutableList<UsageEvent>, nowMs: Long) {
        val cutoff = nowMs - WINDOW_MS
        while (list.isNotEmpty() && list.first().atMs < cutoff) {
            list.removeAt(0)
        }
    }

    private fun pruneDailyLocked(list: MutableList<Long>, nowMs: Long) {
        val pacificDayStartMs = startOfCurrentPacificDayEpochMs(nowMs)
        while (list.isNotEmpty() && list.first() < pacificDayStartMs) {
            list.removeAt(0)
        }
    }

    private data class KeyBucketUsage(
        val rpmUsed: Int,
        val tpmUsed: Long,
        val rpdUsed: Int,
        val lastAtMs: Long,
        val waitUntilRpmSlotMs: Long,
        val waitUntilTpmSlotMs: Long,
        val waitForSpacingMs: Long
    ) {
        val totalWaitMs: Long
            get() = maxOf(waitUntilRpmSlotMs, waitUntilTpmSlotMs, waitForSpacingMs)
    }

    private fun inspectBucketUsageLocked(
        apiKey: String,
        model: String,
        estimatedTokens: Long,
        safeRpm: Int,
        safeTpm: Long,
        availableKeysCount: Int,
        nowMs: Long
    ): KeyBucketUsage {
        val key = bucketKey(apiKey, model)
        val events = windowEventsByKeyModel.getOrPut(key) { mutableListOf() }
        pruneWindowLocked(events, nowMs)
        val daily = dailyEventsByKeyModel.getOrPut(key) { mutableListOf() }
        pruneDailyLocked(daily, nowMs)

        val rpmUsed = events.size
        val tpmUsed = events.sumOf { it.inputTokens }
        val lastAt = lastRequestAtByKeyModel[key] ?: 0L

        val waitRpm = if (rpmUsed >= safeRpm && events.isNotEmpty()) {
            val oldest = events.first().atMs
            ((oldest + WINDOW_MS) - nowMs + 250L).coerceAtLeast(0L)
        } else 0L

        var waitTpm = 0L
        if (tpmUsed + estimatedTokens > safeTpm && events.isNotEmpty()) {
            var remaining = tpmUsed
            for (ev in events) {
                remaining -= ev.inputTokens
                if (remaining + estimatedTokens <= safeTpm) {
                    waitTpm = ((ev.atMs + WINDOW_MS) - nowMs + 250L).coerceAtLeast(0L)
                    break
                }
            }
            if (waitTpm == 0L && tpmUsed > 0L) {
                // Single payload exceeds remaining window even after oldest frees: wait for full window reset
                waitTpm = ((events.last().atMs + WINDOW_MS) - nowMs + 250L).coerceAtLeast(0L)
            }
        }

        val currentLoadRatio = maxOf(
            rpmUsed.toDouble() / safeRpm.coerceAtLeast(1).toDouble(),
            tpmUsed.toDouble() / safeTpm.coerceAtLeast(1L).toDouble()
        )
        val minSpacing = elasticSpacingMs(safeRpm, currentLoadRatio, availableKeysCount)
        val waitSpacing = if (lastAt > 0L && nowMs - lastAt < minSpacing) {
            (minSpacing - (nowMs - lastAt)).coerceAtLeast(0L)
        } else 0L

        return KeyBucketUsage(
            rpmUsed = rpmUsed,
            tpmUsed = tpmUsed,
            rpdUsed = daily.size,
            lastAtMs = lastAt,
            waitUntilRpmSlotMs = waitRpm,
            waitUntilTpmSlotMs = waitTpm,
            waitForSpacingMs = waitSpacing
        )
    }

    /**
     * Ranks API keys for [model] using real-time RPM/TPM utilization + health/cooldowns + dynamic
     * safety margin (90% when >3 keys are available), optionally excluding [excludedKeys] already
     * attempted in the current failover cycle.
     */
    fun planRequestStrategy(
        prefs: PreferencesRepo,
        model: String,
        estimatedInputTokens: Long,
        hasFallbackModel: Boolean,
        excludedKeys: Set<String> = emptySet()
    ): StrategyKeyDecision {
        val nowMs = System.currentTimeMillis()
        ensureDailyResetIfNeeded(prefs, nowMs)

        val normalizedModel = PreferencesRepo.normalizeSupportedModel(model)
        val (profile, tier) = getActiveProfileForModel(prefs, normalizedModel)
        val (rawRpm, rawTpm, rawRpd) = effectiveRawLimits(profile, tier)
        val availableKeysCount = countAvailableKeysForModel(prefs, normalizedModel, nowMs)
        val safeRpm = safeRpmTarget(rawRpm, availableKeysCount)
        val safeTpm = safeTpmTarget(rawTpm, availableKeysCount)
        val baseKeys = prefs.getPrioritizedApiKeysForModel(normalizedModel)
            .filterNot { it in excludedKeys }
        val healthMap = prefs.getApiKeyHealthMap()

        if (baseKeys.isEmpty()) {
            return StrategyKeyDecision(
                orderedKeys = emptyList(),
                waitBeforeFirstKeyMs = 0L,
                recommendProactiveFallback = false,
                strategyNote = if (excludedKeys.isEmpty()) "No API keys configured" else "All keys tried",
                effectiveProfile = profile,
                availableKeyCount = availableKeysCount
            )
        }

        // If on Free Tier and the model explicitly has 0 free quota (Paid-only), immediately shift to fallback
        if (tier == GeminiRateLimitSnapshot.TIER_FREE && profile.freeRpm == 0 && hasFallbackModel) {
            return StrategyKeyDecision(
                orderedKeys = baseKeys,
                waitBeforeFirstKeyMs = 0L,
                recommendProactiveFallback = true,
                strategyNote = "$normalizedModel requires Paid Tier (Free RPM=0) -> proactive fallback",
                effectiveProfile = profile,
                availableKeyCount = availableKeysCount
            )
        }

        data class ScoredKey(
            val key: String,
            val tierBucket: Int, // 0 = ready now, 1 = needs short wait, 2 = cooling down / RPD full, 3 = auth error
            val waitMs: Long,
            val utilization: Double,
            val lastAtMs: Long,
            val rpdExhausted: Boolean
        )

        val scored = synchronized(ledgerLock) {
            baseKeys.map { k ->
                val h = healthMap[PreferencesRepo.apiKeyFingerprint(k)]
                val isAuthErr = h?.status == ApiKeyHealth.STATUS_AUTH_ERROR
                val cooldownUntil = h?.activeCooldownUntilForModel(normalizedModel, nowMs) ?: 0L
                val usage = inspectBucketUsageLocked(
                    apiKey = k,
                    model = normalizedModel,
                    estimatedTokens = estimatedInputTokens,
                    safeRpm = safeRpm,
                    safeTpm = safeTpm,
                    availableKeysCount = availableKeysCount,
                    nowMs = nowMs
                )
                val rpdExhausted = usage.rpdUsed >= rawRpd
                val rpmRatio = usage.rpmUsed.toDouble() / safeRpm.toDouble()
                val tpmRatio = usage.tpmUsed.toDouble() / safeTpm.toDouble()
                val util = maxOf(rpmRatio, tpmRatio)

                val bucket = when {
                    isAuthErr -> 3
                    cooldownUntil > nowMs || rpdExhausted -> 2
                    usage.totalWaitMs == 0L -> 0
                    else -> 1
                }
                val effectiveWait = when {
                    cooldownUntil > nowMs -> (cooldownUntil - nowMs).coerceAtLeast(usage.totalWaitMs)
                    else -> usage.totalWaitMs
                }
                ScoredKey(k, bucket, effectiveWait, util, usage.lastAtMs, rpdExhausted)
            }
        }

        val ordered = scored.sortedWith(
            compareBy<ScoredKey> { it.tierBucket }
                .thenBy { it.waitMs }
                .thenBy { it.utilization }
                .thenBy { it.lastAtMs }
        )

        val best = ordered.first()
        val recommendFallback = hasFallbackModel && (
            best.tierBucket >= 2 ||
                best.rpdExhausted ||
                best.waitMs > MAX_INLINE_WAIT_BEFORE_FALLBACK_MS
            )

        val clampedWait = when {
            recommendFallback -> 0L
            best.tierBucket >= 2 -> 0L
            else -> best.waitMs.coerceAtMost(MAX_INLINE_WAIT_SINGLE_MODEL_MS)
        }

        val marginTag = if (availableKeysCount > HIGH_POOL_AVAILABLE_KEY_THRESHOLD) "90% high-pool" else "85%/80%"
        val note = when {
            recommendFallback -> "Primary $normalizedModel saturated (wait ~${ceil(best.waitMs / 1000.0).toInt()}s) -> proactive fallback"
            clampedWait > 0L -> "Pacing ${ceil(clampedWait / 1000.0).toInt()}s ($marginTag: $safeRpm RPM / ${safeTpm / 1000}k TPM)"
            else -> "Least-loaded key (${(best.utilization * 100).toInt()}% load • $marginTag margin)"
        }

        refreshTelemetry(prefs, normalizedModel, note)

        return StrategyKeyDecision(
            orderedKeys = ordered.map { it.key },
            waitBeforeFirstKeyMs = clampedWait,
            recommendProactiveFallback = recommendFallback,
            strategyNote = note,
            effectiveProfile = profile,
            availableKeyCount = availableKeysCount
        )
    }

    /**
     * Reserves a slot in the rolling 60s RPM/TPM window before firing an HTTP request,
     * applying elastic pacing only when needed, and returns a [SlotReservation] handle.
     */
    suspend fun acquireSlotBeforeCall(
        prefs: PreferencesRepo,
        apiKey: String,
        model: String,
        estimatedInputTokens: Long
    ): SlotReservation {
        ensureDailyResetIfNeeded(prefs)
        val normalizedModel = PreferencesRepo.normalizeSupportedModel(model)
        val availableKeysCount = countAvailableKeysForModel(prefs, normalizedModel)
        val (profile, tier) = getActiveProfileForModel(prefs, normalizedModel)
        val (rawRpm, rawTpm, _) = effectiveRawLimits(profile, tier)
        val safeRpm = safeRpmTarget(rawRpm, availableKeysCount)
        val safeTpm = safeTpmTarget(rawTpm, availableKeysCount)

        val waitMs = synchronized(ledgerLock) {
            val nowMs = System.currentTimeMillis()
            val usage = inspectBucketUsageLocked(
                apiKey = apiKey,
                model = normalizedModel,
                estimatedTokens = estimatedInputTokens,
                safeRpm = safeRpm,
                safeTpm = safeTpm,
                availableKeysCount = availableKeysCount,
                nowMs = nowMs
            )
            usage.totalWaitMs.coerceAtMost(MAX_INLINE_WAIT_SINGLE_MODEL_MS)
        }

        if (waitMs > 0L) {
            val sec = ceil(waitMs / 1000.0).toInt()
            Log.i(TAG, "Rate-limit strategy pacing ${sec}s before calling $normalizedModel (est=${estimatedInputTokens} tok, safe=$safeRpm RPM/${safeTpm / 1000}k TPM, availKeys=$availableKeysCount)")
            refreshTelemetry(prefs, normalizedModel, "Pacing ${sec}s to prevent 429 on $normalizedModel")
            delay(waitMs)
        }

        val reservationId = nextReservationId.getAndIncrement()
        val cleanTokens = estimatedInputTokens.coerceAtLeast(1L)
        synchronized(ledgerLock) {
            val nowMs = System.currentTimeMillis()
            val key = bucketKey(apiKey, normalizedModel)
            val events = windowEventsByKeyModel.getOrPut(key) { mutableListOf() }
            pruneWindowLocked(events, nowMs)
            events.add(UsageEvent(id = reservationId, atMs = nowMs, inputTokens = cleanTokens))

            val daily = dailyEventsByKeyModel.getOrPut(key) { mutableListOf() }
            pruneDailyLocked(daily, nowMs)
            daily.add(nowMs)
            lastRequestAtByKeyModel[key] = nowMs
        }
        refreshTelemetry(prefs, normalizedModel, "Active request dispatched ($normalizedModel)")
        return SlotReservation(
            reservationId = reservationId,
            apiKey = apiKey,
            model = normalizedModel,
            estimatedTokens = cleanTokens
        )
    }

    /**
     * Reclaims phantom TPM tokens (and optionally the RPM/RPD tick) when an HTTP request fails
     * before Google consumed the payload tokens (e.g., network timeout, 5xx, 401, 404, or 429 rejection).
     */
    fun releaseFailedSlot(
        prefs: PreferencesRepo,
        reservation: SlotReservation?,
        keepRpmCount: Boolean
    ) {
        if (reservation == null) return
        val normalizedModel = PreferencesRepo.normalizeSupportedModel(reservation.model)
        synchronized(ledgerLock) {
            val key = bucketKey(reservation.apiKey, normalizedModel)
            val events = windowEventsByKeyModel[key]
            if (!events.isNullOrEmpty()) {
                val idx = events.indexOfLast { it.id == reservation.reservationId }
                if (idx >= 0) {
                    if (keepRpmCount) {
                        // Keep the 1 RPM tick (Google counted the request attempt), reclaim unconsumed TPM
                        events[idx] = events[idx].copy(inputTokens = 0L)
                    } else {
                        events.removeAt(idx)
                        val daily = dailyEventsByKeyModel[key]
                        if (!daily.isNullOrEmpty()) {
                            daily.removeAt(daily.lastIndex)
                        }
                    }
                }
            }
        }
        refreshTelemetry(prefs, normalizedModel)
    }

    /**
     * Reconciles estimated input tokens with actual Google `usageMetadata.promptTokenCount`
     * after a successful response so the TPM rolling window stays exact.
     */
    fun recordActualTokensCompleted(
        prefs: PreferencesRepo,
        apiKey: String,
        model: String,
        actualInputTokens: Int,
        reservation: SlotReservation? = null
    ) {
        if (actualInputTokens <= 0) return
        val normalizedModel = PreferencesRepo.normalizeSupportedModel(model)
        synchronized(ledgerLock) {
            val key = bucketKey(apiKey, normalizedModel)
            val events = windowEventsByKeyModel[key]
            if (!events.isNullOrEmpty()) {
                val targetIdx = if (reservation != null) {
                    events.indexOfLast { it.id == reservation.reservationId }.takeIf { it >= 0 } ?: events.lastIndex
                } else {
                    events.lastIndex
                }
                events[targetIdx] = events[targetIdx].copy(inputTokens = actualInputTokens.toLong())
            }
        }
        refreshTelemetry(prefs, normalizedModel, "Completed ($actualInputTokens in-tokens)")
    }

    /**
     * Adapts rate limits dynamically when Google returns an HTTP 429 error body containing
     * `limit: <N>`, `quotaMetric`, or `retryDelay`, unless the user locked Default Nov 20, 2026 mode.
     *
     * When the 429 explicitly indicates daily RPD exhaustion (`requestsperday` / `perday`),
     * returns the exact wait until Google's **00:00 Midnight Pacific Time** daily reset so the key
     * stops thrashing for the rest of the day and unlocks automatically at reset time.
     */
    fun learnFrom429Response(
        prefs: PreferencesRepo,
        apiKey: String,
        model: String,
        errorMessage: String
    ): Long? {
        val nowMs = System.currentTimeMillis()
        ensureDailyResetIfNeeded(prefs, nowMs)
        val normalizedModel = PreferencesRepo.normalizeSupportedModel(model)
        val lower = errorMessage.lowercase()

        val isTpmMetric = lower.contains("token") || lower.contains("tpm") || lower.contains("inputtokens")
        val isRpmMetric = lower.contains("requestsperminute") || lower.contains("rpm") || lower.contains("perminute")
        val isRpdMetric = lower.contains("requestsperday") || lower.contains("rpd") || lower.contains("perday")

        // Extract retry delay e.g. "Please retry in 34.5s" or "retryDelay": "42s"
        val retrySec = Regex("""(?:retry in|retrydelay["':\s]+)(\d+(?:\.\d+)?)\s*s""", RegexOption.IGNORE_CASE)
            .find(errorMessage)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val parsedRetryMs = retrySec?.let { (it * 1000.0).toLong().coerceIn(5_000L, 180_000L) }

        // If daily RPD quota is exhausted, cool down right until Google's 00:00 Midnight Pacific Time reset
        val effectiveRetryMs = if (isRpdMetric && !isRpmMetric && !isTpmMetric) {
            (nextMidnightPacificEpochMs(nowMs) - nowMs).coerceIn(60_000L, 24L * 60L * 60_000L)
        } else {
            parsedRetryMs
        }

        // If user enabled "Use Default Nov 20, 2026 Reference", do not overwrite stored reference
        if (prefs.useDefaultRateLimitReference) {
            val reason = if (isRpdMetric && !isRpmMetric && !isTpmMetric) {
                "Daily RPD hit on $normalizedModel (key resting until 00:00 PT)"
            } else {
                "429 hit on $normalizedModel (locked to Nov 20, 2026 default)"
            }
            refreshTelemetry(prefs, normalizedModel, reason)
            return effectiveRetryMs
        }

        // Parse Google quotaValue / limit: N if present
        val reportedLimit = Regex("""(?:limit:\s*|quotaValue["':\s]+)(\d+)""", RegexOption.IGNORE_CASE)
            .find(errorMessage)?.groupValues?.getOrNull(1)?.toLongOrNull()

        val currentSnap = prefs.getLiveRateLimitSnapshot() ?: defaultSnapshotNov2026()
        val existingProfile = currentSnap.profiles.firstOrNull { it.modelId.equals(normalizedModel, ignoreCase = true) }
            ?: getDefaultProfileForModel(normalizedModel)
        val isPaid = currentSnap.detectedTier == GeminiRateLimitSnapshot.TIER_PAID_1

        val updatedProfile = when {
            reportedLimit != null && reportedLimit == 0L -> {
                existingProfile.copy(
                    freeRpm = 0,
                    freeTpm = 0L,
                    freeRpd = 0,
                    source = GeminiRateLimitProfile.SOURCE_ADAPTED_429
                )
            }
            isTpmMetric && reportedLimit != null && reportedLimit >= 10_000L -> {
                if (isPaid) existingProfile.copy(paidTpm = reportedLimit, source = GeminiRateLimitProfile.SOURCE_ADAPTED_429)
                else existingProfile.copy(freeTpm = reportedLimit, source = GeminiRateLimitProfile.SOURCE_ADAPTED_429)
            }
            isRpmMetric && reportedLimit != null && reportedLimit in 1L..10_000L -> {
                if (isPaid) existingProfile.copy(paidRpm = reportedLimit.toInt(), source = GeminiRateLimitProfile.SOURCE_ADAPTED_429)
                else existingProfile.copy(freeRpm = reportedLimit.toInt(), source = GeminiRateLimitProfile.SOURCE_ADAPTED_429)
            }
            isRpdMetric && reportedLimit != null && reportedLimit >= 1L -> {
                if (isPaid) existingProfile.copy(paidRpd = reportedLimit.toInt(), source = GeminiRateLimitProfile.SOURCE_ADAPTED_429)
                else existingProfile.copy(freeRpd = reportedLimit.toInt(), source = GeminiRateLimitProfile.SOURCE_ADAPTED_429)
            }
            else -> {
                // No explicit numeric limit in body: defensively trim RPM by 20% for this model
                if (isPaid) {
                    existingProfile.copy(
                        paidRpm = (existingProfile.paidRpm * 0.8).toInt().coerceAtLeast(10),
                        source = GeminiRateLimitProfile.SOURCE_ADAPTED_429
                    )
                } else {
                    existingProfile.copy(
                        freeRpm = (existingProfile.freeRpm - 2).coerceAtLeast(2),
                        source = GeminiRateLimitProfile.SOURCE_ADAPTED_429
                    )
                }
            }
        }

        val nextProfiles = currentSnap.profiles
            .filterNot { it.modelId.equals(normalizedModel, ignoreCase = true) } + updatedProfile
        val updatedSnap = currentSnap.copy(
            source = GeminiRateLimitProfile.SOURCE_ADAPTED_429,
            checkedAtMs = nowMs,
            statusSummary = "Adapted from Google 429 telemetry on $normalizedModel",
            profiles = nextProfiles
        )
        prefs.saveLiveRateLimitSnapshot(updatedSnap)
        refreshTelemetry(prefs, normalizedModel, "Adapted limits after Google 429 on $normalizedModel")
        Log.i(TAG, "Adapted rate limit for $normalizedModel from 429: $updatedProfile (retryMs=$effectiveRetryMs)")
        return effectiveRetryMs
    }

    fun clearRollingLedger(prefs: PreferencesRepo) {
        synchronized(ledgerLock) {
            windowEventsByKeyModel.clear()
            dailyEventsByKeyModel.clear()
            lastRequestAtByKeyModel.clear()
        }
        refreshTelemetry(prefs, prefs.geminiModel, "Reset to default Nov 20, 2026 strategy")
    }

    fun refreshTelemetry(
        prefs: PreferencesRepo,
        model: String = prefs.geminiModel,
        actionNote: String? = null
    ) {
        val nowMs = System.currentTimeMillis()
        val didDailyReset = ensureDailyResetIfNeeded(prefs, nowMs)
        val normalized = PreferencesRepo.normalizeSupportedModel(model)
        val snapshot = getActiveSnapshot(prefs)
        val (profile, tier) = getActiveProfileForModel(prefs, normalized)
        val (rawRpm, rawTpm, _) = effectiveRawLimits(profile, tier)
        val keys = prefs.getAllApiKeys()
        val availableKeys = countAvailableKeysForModel(prefs, normalized, nowMs)
        val poolMultiplier = availableKeys.coerceAtLeast(1)
        val rpmPct = (rpmSafetyFactorForPool(availableKeys) * 100.0).toInt()
        val tpmPct = (tpmSafetyFactorForPool(availableKeys) * 100.0).toInt()

        var totalRpmUsed = 0
        var totalTpmUsed = 0L
        synchronized(ledgerLock) {
            for (k in keys) {
                val bKey = bucketKey(k, normalized)
                val events = windowEventsByKeyModel[bKey] ?: continue
                pruneWindowLocked(events, nowMs)
                totalRpmUsed += events.size
                totalTpmUsed += events.sumOf { it.inputTokens }
            }
        }

        val sourceLabel = when {
            prefs.useDefaultRateLimitReference -> "Default (Nov 20, 2026)"
            snapshot.source == GeminiRateLimitProfile.SOURCE_GOOGLE_LIVE -> "Google Live Checked"
            snapshot.source == GeminiRateLimitProfile.SOURCE_ADAPTED_429 -> "Live + 429 Adapted"
            else -> "Default (Nov 20, 2026)"
        }
        val tierLabel = if (tier == GeminiRateLimitSnapshot.TIER_PAID_1) "Paid Tier 1+" else "Free Tier"
        val resolvedNote = when {
            actionNote != null -> actionNote
            didDailyReset -> "00:00 PT daily reset — all API keys restored to Available"
            else -> null
        }

        _telemetry.update { prev ->
            LiveRateLimitTelemetry(
                activeModel = normalized,
                activeSourceLabel = sourceLabel,
                detectedTierLabel = tierLabel,
                usedRpmInWindow = totalRpmUsed,
                safeRpmLimit = safeRpmTarget(rawRpm, availableKeys) * poolMultiplier,
                rawRpmLimit = rawRpm * poolMultiplier,
                usedTpmInWindow = totalTpmUsed,
                safeTpmLimit = safeTpmTarget(rawTpm, availableKeys) * poolMultiplier,
                rawTpmLimit = rawTpm * poolMultiplier,
                activeKeyCount = keys.size,
                availableKeyCount = availableKeys,
                rpmSafetyPercent = rpmPct,
                tpmSafetyPercent = tpmPct,
                nextDailyResetMs = nextMidnightPacificEpochMs(nowMs),
                lastActionNote = resolvedNote ?: prev.lastActionNote,
                updatedAtMs = nowMs
            )
        }
    }
}
