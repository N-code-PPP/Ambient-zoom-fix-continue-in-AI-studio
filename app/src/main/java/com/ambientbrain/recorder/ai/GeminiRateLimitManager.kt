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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
    val lastActionNote: String = "Ready",
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class StrategyKeyDecision(
    val orderedKeys: List<String>,
    val waitBeforeFirstKeyMs: Long,
    val recommendProactiveFallback: Boolean,
    val strategyNote: String,
    val effectiveProfile: GeminiRateLimitProfile
)

/**
 * Adaptive RPM & TPM Rate-Limit Strategy Engine for Google AI Studio Gemini API Key Pools.
 *
 * Key capabilities:
 *  - Ships with a curated **Nov 20, 2026** default per-model RPM/TPM/RPD reference table
 *    (covering Free Tier and Paid Tier 1+ models).
 *  - Maintains a thread-safe 60-second rolling window ledger of requests (RPM) and input tokens
 *    (TPM) per `(apiKeyFingerprint, model)` plus daily request tracking (RPD).
 *  - Dynamically adapts pacing, least-loaded key selection, proactive Fallback Model routing,
 *    and audio batch size caps based on either:
 *      (a) the built-in Nov 20, 2026 default reference (when `useDefaultRateLimitReference` is ON), or
 *      (b) live rate-limit info fetched from Google (`ai.google.dev` + `/v1beta/models` + tier probe
 *          + real-time HTTP 429 quota/Retry-After calibration).
 */
object GeminiRateLimitManager {

    private const val TAG = "GeminiRateLimit"
    const val DEFAULT_REFERENCE_DATE = "2026-11-20"
    private const val WINDOW_MS = 60_000L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    // Safety headroom factors so requests pace smoothly BEFORE hitting Google's hard 429 wall
    private const val RPM_SAFETY_FACTOR = 0.85
    private const val TPM_SAFETY_FACTOR = 0.80

    // Maximum inline pacing sleep before proactively failing over to Fallback Model
    private const val MAX_INLINE_WAIT_BEFORE_FALLBACK_MS = 22_000L
    private const val MAX_INLINE_WAIT_SINGLE_MODEL_MS = 45_000L

    private data class UsageEvent(
        val atMs: Long,
        val inputTokens: Long
    )

    private val ledgerLock = Any()
    private val windowEventsByKeyModel = ConcurrentHashMap<String, MutableList<UsageEvent>>()
    private val dailyEventsByKeyModel = ConcurrentHashMap<String, MutableList<Long>>()
    private val lastRequestAtByKeyModel = ConcurrentHashMap<String, Long>()

    private val _telemetry = MutableStateFlow(LiveRateLimitTelemetry())
    val telemetry: StateFlow<LiveRateLimitTelemetry> = _telemetry.asStateFlow()

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

    fun safeRpmTarget(rawRpm: Int): Int {
        return if (rawRpm <= 2) rawRpm.coerceAtLeast(1)
        else (rawRpm * RPM_SAFETY_FACTOR).toInt().coerceAtLeast(1)
    }

    fun safeTpmTarget(rawTpm: Long): Long {
        return (rawTpm * TPM_SAFETY_FACTOR).toLong().coerceAtLeast(16_000L)
    }

    /**
     * Minimum spacing between consecutive requests on the same key/model so traffic is
     * smoothed evenly across the 60-second window instead of bursting at T=0.
     */
    fun minSpacingMsForRpm(safeRpm: Int): Long {
        if (safeRpm >= 60) return 400L
        return (WINDOW_MS / safeRpm.coerceAtLeast(1)).coerceIn(800L, 12_000L)
    }

    /**
     * Dynamically caps per-request audio payload bytes based on the active model's TPM budget
     * (32 kbps ADTS AAC ≈ 4,000 bytes/sec ≈ 32 audio tokens/sec -> ~125 bytes per token).
     */
    fun recommendedMaxAudioBatchBytes(
        prefs: PreferencesRepo,
        modelId: String,
        baseLimitBytes: Long
    ): Long {
        val (profile, tier) = getActiveProfileForModel(prefs, modelId)
        val (_, rawTpm, _) = effectiveRawLimits(profile, tier)
        val safeTpm = safeTpmTarget(rawTpm)
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
        val cutoff = nowMs - DAY_MS
        while (list.isNotEmpty() && list.first() < cutoff) {
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

        val minSpacing = minSpacingMsForRpm(safeRpm)
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
     * Ranks API keys for [model] using real-time RPM/TPM utilization + health/cooldowns,
     * and decides whether to pace briefly or proactively shift to [fallbackModel].
     */
    fun planRequestStrategy(
        prefs: PreferencesRepo,
        model: String,
        estimatedInputTokens: Long,
        hasFallbackModel: Boolean
    ): StrategyKeyDecision {
        val nowMs = System.currentTimeMillis()
        val normalizedModel = PreferencesRepo.normalizeSupportedModel(model)
        val snapshot = getActiveSnapshot(prefs)
        val (profile, tier) = getActiveProfileForModel(prefs, normalizedModel)
        val (rawRpm, rawTpm, rawRpd) = effectiveRawLimits(profile, tier)
        val safeRpm = safeRpmTarget(rawRpm)
        val safeTpm = safeTpmTarget(rawTpm)
        val baseKeys = prefs.getPrioritizedApiKeysForModel(normalizedModel)
        val healthMap = prefs.getApiKeyHealthMap()

        if (baseKeys.isEmpty()) {
            return StrategyKeyDecision(
                orderedKeys = emptyList(),
                waitBeforeFirstKeyMs = 0L,
                recommendProactiveFallback = false,
                strategyNote = "No API keys configured",
                effectiveProfile = profile
            )
        }

        // If on Free Tier and the model explicitly has 0 free quota (Paid-only), immediately shift to fallback
        if (tier == GeminiRateLimitSnapshot.TIER_FREE && profile.freeRpm == 0 && hasFallbackModel) {
            return StrategyKeyDecision(
                orderedKeys = baseKeys,
                waitBeforeFirstKeyMs = 0L,
                recommendProactiveFallback = true,
                strategyNote = "$normalizedModel requires Paid Tier (Free RPM=0) -> proactive fallback",
                effectiveProfile = profile
            )
        }

        data class ScoredKey(
            val key: String,
            val tierBucket: Int, // 0 = ready now, 1 = needs short wait, 2 = cooling down, 3 = auth error
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
                val usage = inspectBucketUsageLocked(k, normalizedModel, estimatedInputTokens, safeRpm, safeTpm, nowMs)
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

        val note = when {
            recommendFallback -> "Primary $normalizedModel saturated (wait ~${ceil(best.waitMs / 1000.0).toInt()}s) -> proactive fallback"
            clampedWait > 0L -> "Pacing ${ceil(clampedWait / 1000.0).toInt()}s to stay under $safeRpm RPM / ${safeTpm / 1000}k TPM"
            else -> "Dispatching on least-loaded key (${(best.utilization * 100).toInt()}% window load)"
        }

        refreshTelemetry(prefs, normalizedModel, note)

        return StrategyKeyDecision(
            orderedKeys = ordered.map { it.key },
            waitBeforeFirstKeyMs = clampedWait,
            recommendProactiveFallback = recommendFallback,
            strategyNote = note,
            effectiveProfile = profile
        )
    }

    /**
     * Reserves a slot in the rolling 60s RPM/TPM window before firing an HTTP request,
     * applying smooth pacing if the chosen key needs a short breather.
     */
    suspend fun acquireSlotBeforeCall(
        prefs: PreferencesRepo,
        apiKey: String,
        model: String,
        estimatedInputTokens: Long
    ) {
        val normalizedModel = PreferencesRepo.normalizeSupportedModel(model)
        val (profile, tier) = getActiveProfileForModel(prefs, normalizedModel)
        val (rawRpm, rawTpm, _) = effectiveRawLimits(profile, tier)
        val safeRpm = safeRpmTarget(rawRpm)
        val safeTpm = safeTpmTarget(rawTpm)

        val waitMs = synchronized(ledgerLock) {
            val nowMs = System.currentTimeMillis()
            val usage = inspectBucketUsageLocked(apiKey, normalizedModel, estimatedInputTokens, safeRpm, safeTpm, nowMs)
            usage.totalWaitMs.coerceAtMost(MAX_INLINE_WAIT_SINGLE_MODEL_MS)
        }

        if (waitMs > 0L) {
            val sec = ceil(waitMs / 1000.0).toInt()
            Log.i(TAG, "Rate-limit strategy pacing ${sec}s before calling $normalizedModel (est=${estimatedInputTokens} tok, safe=$safeRpm RPM/${safeTpm / 1000}k TPM)")
            refreshTelemetry(prefs, normalizedModel, "Pacing ${sec}s to prevent 429 on $normalizedModel")
            delay(waitMs)
        }

        synchronized(ledgerLock) {
            val nowMs = System.currentTimeMillis()
            val key = bucketKey(apiKey, normalizedModel)
            val events = windowEventsByKeyModel.getOrPut(key) { mutableListOf() }
            pruneWindowLocked(events, nowMs)
            events.add(UsageEvent(atMs = nowMs, inputTokens = estimatedInputTokens.coerceAtLeast(1L)))

            val daily = dailyEventsByKeyModel.getOrPut(key) { mutableListOf() }
            pruneDailyLocked(daily, nowMs)
            daily.add(nowMs)
            lastRequestAtByKeyModel[key] = nowMs
        }
        refreshTelemetry(prefs, normalizedModel, "Active request dispatched ($normalizedModel)")
    }

    /**
     * Reconciles estimated input tokens with actual Google `usageMetadata.promptTokenCount`
     * after a successful response so the TPM rolling window stays exact.
     */
    fun recordActualTokensCompleted(
        prefs: PreferencesRepo,
        apiKey: String,
        model: String,
        actualInputTokens: Int
    ) {
        if (actualInputTokens <= 0) return
        val normalizedModel = PreferencesRepo.normalizeSupportedModel(model)
        synchronized(ledgerLock) {
            val key = bucketKey(apiKey, normalizedModel)
            val events = windowEventsByKeyModel[key]
            if (!events.isNullOrEmpty()) {
                val lastIdx = events.lastIndex
                events[lastIdx] = events[lastIdx].copy(inputTokens = actualInputTokens.toLong())
            }
        }
        refreshTelemetry(prefs, normalizedModel, "Completed ($actualInputTokens in-tokens)")
    }

    /**
     * Adapts rate limits dynamically when Google returns an HTTP 429 error body containing
     * `limit: <N>`, `quotaMetric`, or `retryDelay`, unless the user locked Default Nov 20, 2026 mode.
     * Returns an extracted retry-after wait in ms if Google explicitly told us how long to wait.
     */
    fun learnFrom429Response(
        prefs: PreferencesRepo,
        apiKey: String,
        model: String,
        errorMessage: String
    ): Long? {
        val normalizedModel = PreferencesRepo.normalizeSupportedModel(model)
        val lower = errorMessage.lowercase()

        // Extract retry delay e.g. "Please retry in 34.5s" or "retryDelay": "42s"
        val retrySec = Regex("""(?:retry in|retrydelay["':\s]+)(\d+(?:\.\d+)?)\s*s""", RegexOption.IGNORE_CASE)
            .find(errorMessage)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val retryMs = retrySec?.let { (it * 1000.0).toLong().coerceIn(5_000L, 180_000L) }

        // If user enabled "Use Default Nov 20, 2026 Reference", do not overwrite stored reference
        if (prefs.useDefaultRateLimitReference) {
            refreshTelemetry(prefs, normalizedModel, "429 hit on $normalizedModel (locked to Nov 20, 2026 default)")
            return retryMs
        }

        // Parse Google quotaValue / limit: N if present
        val reportedLimit = Regex("""(?:limit:\s*|quotaValue["':\s]+)(\d+)""", RegexOption.IGNORE_CASE)
            .find(errorMessage)?.groupValues?.getOrNull(1)?.toLongOrNull()

        val isTpmMetric = lower.contains("token") || lower.contains("tpm") || lower.contains("inputtokens")
        val isRpmMetric = lower.contains("requestsperminute") || lower.contains("rpm") || lower.contains("perminute")
        val isRpdMetric = lower.contains("requestsperday") || lower.contains("rpd") || lower.contains("perday")

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
            checkedAtMs = System.currentTimeMillis(),
            statusSummary = "Adapted from Google 429 telemetry on $normalizedModel",
            profiles = nextProfiles
        )
        prefs.saveLiveRateLimitSnapshot(updatedSnap)
        refreshTelemetry(prefs, normalizedModel, "Adapted limits after Google 429 on $normalizedModel")
        Log.i(TAG, "Adapted rate limit for $normalizedModel from 429: $updatedProfile (retryMs=$retryMs)")
        return retryMs
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
        val normalized = PreferencesRepo.normalizeSupportedModel(model)
        val snapshot = getActiveSnapshot(prefs)
        val (profile, tier) = getActiveProfileForModel(prefs, normalized)
        val (rawRpm, rawTpm, _) = effectiveRawLimits(profile, tier)
        val keys = prefs.getAllApiKeys()
        val keyMultiplier = keys.size.coerceAtLeast(1)

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

        _telemetry.update { prev ->
            LiveRateLimitTelemetry(
                activeModel = normalized,
                activeSourceLabel = sourceLabel,
                detectedTierLabel = tierLabel,
                usedRpmInWindow = totalRpmUsed,
                safeRpmLimit = safeRpmTarget(rawRpm) * keyMultiplier,
                rawRpmLimit = rawRpm * keyMultiplier,
                usedTpmInWindow = totalTpmUsed,
                safeTpmLimit = safeTpmTarget(rawTpm) * keyMultiplier,
                rawTpmLimit = rawTpm * keyMultiplier,
                activeKeyCount = keys.size,
                lastActionNote = actionNote ?: prev.lastActionNote,
                updatedAtMs = nowMs
            )
        }
    }
}
