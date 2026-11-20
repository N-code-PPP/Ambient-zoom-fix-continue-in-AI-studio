package com.ambientbrain.recorder.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray

@Serializable
data class VoiceProfileSample(
    val id: String = UUID.randomUUID().toString().take(8),
    val filePath: String,
    val durationMs: Long = 0L,
    val qualityScore: Float = 50f,
    val snrDb: Float = 0f,
    val speechRatio: Float = 0f,
    val createdAtMs: Long = System.currentTimeMillis(),
    val sourceSessionId: String? = null,
    val isManual: Boolean = false,
    val embedding: List<Float> = emptyList()
)

@Serializable
data class SpeakerProfileEntry(
    val id: String = UUID.randomUUID().toString().take(8),
    val displayName: String,
    val role: String = "",
    val voiceHint: String = "",
    val audioPath: String? = null,
    val isUser: Boolean = false,
    val nickname: String = "",
    val relatableTitle: String = "",
    val voiceSignature: String = "",
    val isAutoDiscovered: Boolean = false,
    val isPinned: Boolean = false,
    val sessionsHeardCount: Int = 1,
    val lastHeardAtMs: Long = 0L,
    val sampleDurationMs: Long = 0L,
    val sourceSessionId: String? = null,
    val samples: List<VoiceProfileSample> = emptyList(),
    val aliases: List<String> = emptyList()
) {
    fun effectiveNickname(): String = nickname.trim().ifBlank { displayName.trim() }

    fun effectiveRelatableTitle(): String = relatableTitle.trim().ifBlank { role.trim() }

    fun formattedLabel(): String {
        val nick = effectiveNickname()
        val title = effectiveRelatableTitle()
        return when {
            title.isBlank() -> nick
            nick.contains(title, ignoreCase = true) -> nick
            else -> "$nick ($title)"
        }
    }

    fun allKnownNames(): List<String> {
        return (listOf(displayName, nickname) + aliases)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    /**
     * Returns all on-disk voice samples ranked best-quality first (max 10).
     * Automatically bridges legacy single-file [audioPath] profiles.
     */
    fun existingSamples(): List<VoiceProfileSample> {
        val valid = samples.filter { s ->
            s.filePath.isNotBlank() && runCatching {
                val f = File(s.filePath)
                f.exists() && f.length() >= 1024L
            }.getOrDefault(false)
        }
        if (valid.isNotEmpty()) {
            return valid.sortedWith(
                compareByDescending<VoiceProfileSample> { it.qualityScore }
                    .thenByDescending { it.isManual }
                    .thenByDescending { it.createdAtMs }
            ).take(PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE)
        }
        val legacyPath = audioPath?.takeIf { it.isNotBlank() } ?: return emptyList()
        val legacyFile = File(legacyPath)
        if (!legacyFile.exists() || legacyFile.length() < 1024L) return emptyList()
        return listOf(
            VoiceProfileSample(
                id = "legacy",
                filePath = legacyFile.absolutePath,
                durationMs = sampleDurationMs,
                qualityScore = if (isAutoDiscovered) 50f else 68f,
                createdAtMs = if (lastHeardAtMs > 0L) lastHeardAtMs else legacyFile.lastModified(),
                sourceSessionId = sourceSessionId,
                isManual = !isAutoDiscovered
            )
        )
    }

    fun bestSample(): VoiceProfileSample? = existingSamples().firstOrNull()

    fun bestQualityScore(): Float = bestSample()?.qualityScore ?: 0f

    /**
     * Computes the L2-normalized centroid voiceprint across all stored samples that have
     * computed embeddings, weighting higher-quality samples slightly more strongly.
     */
    fun voiceprintCentroid(): List<Float> {
        val embedded = existingSamples().filter { it.embedding.isNotEmpty() }
        if (embedded.isEmpty()) return emptyList()
        val dim = embedded.maxOf { it.embedding.size }
        val compatible = embedded.filter { it.embedding.size == dim }
        if (compatible.isEmpty()) return emptyList()

        val acc = DoubleArray(dim)
        for (s in compatible) {
            val w = (0.5 + (s.qualityScore.coerceIn(0f, 100f) / 100.0))
            val vec = s.embedding
            for (i in 0 until dim) {
                acc[i] += vec[i] * w
            }
        }
        var normSq = 0.0
        for (i in 0 until dim) normSq += acc[i] * acc[i]
        val norm = sqrt(normSq)
        if (norm < 1e-9) return emptyList()
        return List(dim) { i -> (acc[i] / norm).toFloat() }
    }
}

data class AttachableVoiceClip(
    val entry: SpeakerProfileEntry,
    val file: File,
    val sampleIndex: Int,
    val sampleTotal: Int,
    val qualityScore: Float
)

@Serializable
data class ApiKeyHealth(
    val keyFingerprint: String,
    val status: String = STATUS_UNTESTED,
    val cooldownUntilMs: Long = 0L,
    val modelCooldowns: Map<String, Long> = emptyMap(),
    val consecutiveFailures: Int = 0,
    val successCount: Int = 0,
    val errorCount: Int = 0,
    val lastSuccessMs: Long = 0L,
    val lastErrorMs: Long = 0L,
    val lastErrorMessage: String = ""
) {
    fun activeCooldownUntilForModel(model: String, nowMs: Long = System.currentTimeMillis()): Long {
        val modelUntil = modelCooldowns[model] ?: 0L
        val maxUntil = maxOf(cooldownUntilMs, modelUntil)
        return if (maxUntil > nowMs) maxUntil else 0L
    }

    fun isCoolingDownForModel(model: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return activeCooldownUntilForModel(model, nowMs) > nowMs
    }

    fun anyActiveCooldownUntil(nowMs: Long = System.currentTimeMillis()): Long {
        val maxModel = modelCooldowns.values.maxOrNull() ?: 0L
        val maxUntil = maxOf(cooldownUntilMs, maxModel)
        return if (maxUntil > nowMs) maxUntil else 0L
    }

    companion object {
        const val STATUS_UNTESTED = "UNTESTED"
        const val STATUS_HEALTHY = "HEALTHY"
        const val STATUS_RATE_LIMITED = "RATE_LIMITED"
        const val STATUS_AUTH_ERROR = "AUTH_ERROR"
        const val STATUS_ERROR = "ERROR"
    }
}

@Serializable
data class ModelAllowanceEntry(
    val modelId: String,
    val displayName: String = "",
    val status: String = STATUS_UNKNOWN,
    val isFreeTierHint: Boolean = true,
    val latencyMs: Long = 0L,
    val detail: String = "",
    val checkedAtMs: Long = 0L
) {
    companion object {
        const val STATUS_AVAILABLE = "AVAILABLE"
        const val STATUS_QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
        const val STATUS_PAID_ONLY = "PAID_ONLY"
        const val STATUS_UNAVAILABLE = "UNAVAILABLE"
        const val STATUS_LISTED = "LISTED"
        const val STATUS_UNKNOWN = "UNKNOWN"
    }
}

@Serializable
data class GeminiRateLimitProfile(
    val modelId: String,
    val freeRpm: Int = 10,
    val freeTpm: Long = 250_000L,
    val freeRpd: Int = 250,
    val paidRpm: Int = 1_000,
    val paidTpm: Long = 1_000_000L,
    val paidRpd: Int = 10_000,
    val inputTokenLimit: Long = 1_048_576L,
    val source: String = SOURCE_DEFAULT_NOV_2026
) {
    companion object {
        const val SOURCE_DEFAULT_NOV_2026 = "DEFAULT_NOV_20_2026"
        const val SOURCE_GOOGLE_LIVE = "GOOGLE_LIVE"
        const val SOURCE_ADAPTED_429 = "ADAPTED_429"
    }
}

@Serializable
data class GeminiRateLimitSnapshot(
    val referenceDate: String = "2026-11-20",
    val source: String = GeminiRateLimitProfile.SOURCE_DEFAULT_NOV_2026,
    val detectedTier: String = TIER_FREE,
    val checkedAtMs: Long = 0L,
    val statusSummary: String = "",
    val profiles: List<GeminiRateLimitProfile> = emptyList()
) {
    companion object {
        const val TIER_FREE = "FREE"
        const val TIER_PAID_1 = "PAID_TIER_1"
    }
}

data class GeminiPauseState(
    val isPaused: Boolean = false,
    val pausedUntilMs: Long = 0L // 0L = paused indefinitely until user resumes
) {
    fun isActivelyPaused(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isPaused) return false
        return pausedUntilMs <= 0L || nowMs < pausedUntilMs
    }
}

object GeminiPauseStateBus {
    private val _state = MutableStateFlow(GeminiPauseState())
    val state: StateFlow<GeminiPauseState> = _state.asStateFlow()

    fun publish(isPaused: Boolean, pausedUntilMs: Long) {
        _state.value = GeminiPauseState(isPaused = isPaused, pausedUntilMs = pausedUntilMs)
    }
}

@Serializable
data class GlossaryTermEntry(
    val id: String = UUID.randomUUID().toString().take(8),
    val term: String,
    val thaiPhonetic: String = "",
    val category: String = CATEGORY_GENERAL_MED,
    val notes: String = "",
    val isAutoAdded: Boolean = false,
    val seenCount: Int = 1,
    val createdAtMs: Long = System.currentTimeMillis(),
    val lastSeenAtMs: Long = System.currentTimeMillis(),
    val sourceSessionId: String? = null
) {
    companion object {
        const val CATEGORY_DRUG = "DRUG"
        const val CATEGORY_DIAGNOSIS = "DIAGNOSIS"
        const val CATEGORY_EXAM_SCALE = "EXAM_SCALE"
        const val CATEGORY_PROCEDURE_LAB = "PROCEDURE_LAB"
        const val CATEGORY_WARD_HOSPITAL = "WARD_HOSPITAL"
        const val CATEGORY_PHONETIC_TH_EN = "PHONETIC_TH_EN"
        const val CATEGORY_GENERAL_MED = "GENERAL_MED"

        val ALL_CATEGORIES = listOf(
            CATEGORY_DRUG,
            CATEGORY_DIAGNOSIS,
            CATEGORY_EXAM_SCALE,
            CATEGORY_PROCEDURE_LAB,
            CATEGORY_WARD_HOSPITAL,
            CATEGORY_PHONETIC_TH_EN,
            CATEGORY_GENERAL_MED
        )

        fun categoryDisplayLabel(category: String): String = when (category.uppercase()) {
            CATEGORY_DRUG -> "Drug / Dose"
            CATEGORY_DIAGNOSIS -> "Diagnosis"
            CATEGORY_EXAM_SCALE -> "Exam / Scale"
            CATEGORY_PROCEDURE_LAB -> "Lab / Procedure"
            CATEGORY_WARD_HOSPITAL -> "Ward / Hospital"
            CATEGORY_PHONETIC_TH_EN -> "Thai Phonetic"
            else -> "Medical Term"
        }

        fun normalizeCategory(raw: String?): String {
            val upper = raw?.trim()?.uppercase().orEmpty()
            return when {
                upper in ALL_CATEGORIES -> upper
                upper.contains("DRUG") || upper.contains("MEDICATION") || upper.contains("PHARM") -> CATEGORY_DRUG
                upper.contains("DIAG") || upper.contains("DISEASE") || upper.contains("SYNDROME") -> CATEGORY_DIAGNOSIS
                upper.contains("EXAM") || upper.contains("SCALE") || upper.contains("SCORE") -> CATEGORY_EXAM_SCALE
                upper.contains("PROC") || upper.contains("LAB") || upper.contains("IMAG") || upper.contains("TEST") -> CATEGORY_PROCEDURE_LAB
                upper.contains("WARD") || upper.contains("HOSP") || upper.contains("UNIT") || upper.contains("LOCAL") -> CATEGORY_WARD_HOSPITAL
                upper.contains("PHONETIC") || upper.contains("THAI") || upper.contains("TRANSLIT") -> CATEGORY_PHONETIC_TH_EN
                else -> CATEGORY_GENERAL_MED
            }
        }
    }
}

@Serializable
data class SettingsBackupPayload(
    val format: String = "ambient_brain_settings_v1",
    val schemaVersion: Int = 5,
    val exportedAtMs: Long = System.currentTimeMillis(),
    val appVersionName: String = "",
    val micSharingMode: String = MicSharingMode.SMART_SHARE.name,
    val geminiModel: String = PreferencesRepo.DEFAULT_MODEL,
    val fallbackGeminiModel: String = PreferencesRepo.DEFAULT_FALLBACK_MODEL,
    val transcriptLangMode: String = TranscriptLangMode.BILINGUAL_TH_EN.name,
    val autoDetectRounds: Boolean = true,
    val autoRecognizeVoiceProfiles: Boolean = true,
    val autoAddGlossaryTerms: Boolean = true,
    val autoMergeContinuousFiles: Boolean = true,
    val autoMergeGapMinutes: Int = 15,
    val defaultReviewSourceIsAudio: Boolean = false,
    val minMeaningfulSpeechSeconds: Int = 3,
    val silenceSplitSeconds: Int = 90,
    val roundSilenceSplitSeconds: Int = 300,
    val retentionDays: Int = 7,
    val meetingVideoSaveFolder: String = PreferencesRepo.DEFAULT_MEETING_VIDEO_FOLDER,
    val userDisplayName: String = "Dr. (Me)",
    val customGlossaryTerms: String = "",
    val structuredGlossaryEntries: List<GlossaryTermEntry> = emptyList(),
    val speakerRoster: List<SpeakerProfileEntry> = emptyList(),
    val myVoiceSamples: List<VoiceProfileSample> = emptyList(),
    val includesApiKeys: Boolean = false,
    val geminiApiKey: String = "",
    val savedApiKeysPool: List<String> = emptyList(),
    val hasMyVoiceProfileHint: Boolean = false,
    val wasListeningEnabled: Boolean = false
)


enum class MicSharingMode(val label: String, val shortLabel: String, val description: String) {
    SMART_SHARE(
        label = "Smart Share (Keep Calls, Yield Camera/Recorders)",
        shortLabel = "Smart Share",
        description = "Uses A11y VOICE_RECOGNITION for call-through capture while releasing the mic for Camera, Voice Recorder, and non-call apps."
    ),
    STRICT_YIELD(
        label = "Auto-Yield to All Apps (Zero Conflict)",
        shortLabel = "Auto-Yield",
        description = "Immediately releases the mic whenever another app or call opens audio, then resumes automatically when finished."
    ),
    FORCE_CONCURRENT(
        label = "Force Concurrent A11y (24/7 Continuous)",
        shortLabel = "Force Concurrent",
        description = "Keeps VOICE_RECOGNITION active alongside all apps and calls (requires working ADB + Accessibility sharing)."
    )
}

enum class LiveCaptureStatus {
    VAD_STANDBY,
    RECORDING_AMBIENT,
    RECORDING_ROUND_MANUAL,
    RECORDING_ROUND_AUTO,
    RECORDING_IN_CALL,
    MIC_SILENCED_WARNING,
    PAUSED,
    STOPPED
}

data class LiveCaptureTelemetry(
    val status: LiveCaptureStatus = LiveCaptureStatus.VAD_STANDBY,
    val isListeningEnabled: Boolean = true,
    val currentSessionElapsedSec: Long = 0L,
    val lastVadProbability: Float = 0f,
    val isInPhoneCall: Boolean = false,
    val isMicSilencedByOs: Boolean = false,
    val isExternalMicActive: Boolean = false,
    val externalMicOwnerPackage: String? = null,
    val externalMicOwnerLabel: String? = null,
    val externalMicOwnerSource: String? = null,
    val pauseReason: String? = null,
    val isAccessibilityBound: Boolean = false,
    val isAccessibilityOverlayAttached: Boolean = false,
    val micSharingMode: MicSharingMode = MicSharingMode.SMART_SHARE,
    val activeAudioSourceName: String = "VOICE_RECOGNITION",
    val activeEncoderName: String = "AAC-LC",
    val isEncoderHwAccelerated: Boolean = false,
    val gcCount: Long = 0L,
    val gcTimeMs: Long = 0L,
    val nativeHeapKb: Long = 0L,
    val audioReadErrors: Long = 0L,
    val poolStarvationDrops: Long = 0L,
    val vadGatedFrames: Long = 0L,
    val vadOnnxFrames: Long = 0L,
    val lastHeartbeatMs: Long = System.currentTimeMillis()
)

object CaptureStateBus {
    private val _telemetry = MutableStateFlow(LiveCaptureTelemetry())
    val telemetry: StateFlow<LiveCaptureTelemetry> = _telemetry.asStateFlow()

    @Volatile
    var isUiForegroundActive: Boolean = false
        private set

    @Volatile
    var isDiagnosticsOpen: Boolean = false
        private set

    fun setUiForeground(active: Boolean) {
        isUiForegroundActive = active
    }

    fun setDiagnosticsOpen(open: Boolean) {
        isDiagnosticsOpen = open
    }

    fun update(transform: (LiveCaptureTelemetry) -> LiveCaptureTelemetry) {
        val current = _telemetry.value
        val next = transform(current)
        if (next != current) {
            _telemetry.value = next
        }
    }
}

class PreferencesRepo(private val context: Context) {

    private val appContext: Context = context.applicationContext

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPrefsSingleton(appContext)
    }

    var isListeningEnabled: Boolean
        get() {
            ensureHotCacheInitialized(appContext)
            return cachedListeningEnabled
        }
        set(value) {
            cachedListeningEnabled = value
            prefs.edit().putBoolean(KEY_LISTENING_ENABLED, value).apply()
        }

    var micSharingMode: MicSharingMode
        get() {
            ensureHotCacheInitialized(appContext)
            return cachedMicSharingMode
        }
        set(value) {
            cachedMicSharingMode = value
            prefs.edit().putString(KEY_MIC_SHARING_MODE, value.name).apply()
            CaptureStateBus.update { it.copy(micSharingMode = value) }
        }

    var geminiApiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var geminiModel: String
        get() = normalizeSupportedModel(prefs.getString(KEY_MODEL, DEFAULT_MODEL))
        set(value) = prefs.edit().putString(KEY_MODEL, normalizeSupportedModel(value)).apply()

    var fallbackGeminiModel: String
        get() = normalizeFallbackModel(prefs.getString(KEY_FALLBACK_MODEL, DEFAULT_FALLBACK_MODEL))
        set(value) = prefs.edit().putString(KEY_FALLBACK_MODEL, normalizeFallbackModel(value)).apply()

    var transcriptLangMode: TranscriptLangMode
        get() {
            val name = prefs.getString(KEY_TRANSCRIPT_LANG_MODE, TranscriptLangMode.BILINGUAL_TH_EN.name)
            return runCatching { TranscriptLangMode.valueOf(name!!) }
                .getOrDefault(TranscriptLangMode.BILINGUAL_TH_EN)
        }
        set(value) = prefs.edit().putString(KEY_TRANSCRIPT_LANG_MODE, value.name).apply()

    var autoDetectRounds: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DETECT_ROUNDS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DETECT_ROUNDS, value).apply()

    var autoRecognizeVoiceProfiles: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECOGNIZE_VOICE_PROFILES, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECOGNIZE_VOICE_PROFILES, value).apply()

    var autoMergeContinuousFiles: Boolean
        get() = prefs.getBoolean(KEY_AUTO_MERGE_CONTINUOUS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_MERGE_CONTINUOUS, value).apply()

    var autoMergeGapMinutes: Int
        get() = prefs.getInt(KEY_AUTO_MERGE_GAP_MIN, 15).coerceIn(5, 60)
        set(value) = prefs.edit().putInt(KEY_AUTO_MERGE_GAP_MIN, value.coerceIn(5, 60)).apply()

    var defaultReviewSourceIsAudio: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_REVIEW_SOURCE_IS_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_DEFAULT_REVIEW_SOURCE_IS_AUDIO, value).apply()

    var minMeaningfulSpeechSeconds: Int
        get() {
            ensureHotCacheInitialized(appContext)
            return cachedMinMeaningfulSpeechSec
        }
        set(value) {
            val clamped = value.coerceIn(1, 60)
            cachedMinMeaningfulSpeechSec = clamped
            prefs.edit().putInt(KEY_MIN_MEANINGFUL_SPEECH_SEC, clamped).apply()
        }

    var silenceSplitSeconds: Int
        get() {
            ensureHotCacheInitialized(appContext)
            return cachedSilenceSplitSec
        }
        set(value) {
            val clamped = value.coerceIn(30, 300)
            cachedSilenceSplitSec = clamped
            prefs.edit().putInt(KEY_SILENCE_SPLIT_SEC, clamped).apply()
        }

    var roundSilenceSplitSeconds: Int
        get() {
            ensureHotCacheInitialized(appContext)
            return cachedRoundSilenceSplitSec
        }
        set(value) {
            val clamped = value.coerceIn(120, 900)
            cachedRoundSilenceSplitSec = clamped
            prefs.edit().putInt(KEY_ROUND_SILENCE_SPLIT_SEC, clamped).apply()
        }

    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, 7)
        set(value) = prefs.edit().putInt(KEY_RETENTION_DAYS, value).apply()

    var meetingVideoSaveFolder: String
        get() = normalizePublicVideoFolder(
            prefs.getString(KEY_MEETING_VIDEO_SAVE_FOLDER, DEFAULT_MEETING_VIDEO_FOLDER)
        )
        set(value) {
            prefs.edit()
                .putString(KEY_MEETING_VIDEO_SAVE_FOLDER, normalizePublicVideoFolder(value))
                .apply()
        }

    var meetingVideoCustomTreeUri: String
        get() = prefs.getString(KEY_MEETING_VIDEO_CUSTOM_TREE_URI, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_MEETING_VIDEO_CUSTOM_TREE_URI, value.trim()).apply()

    var meetingVideoCustomFolderLabel: String
        get() = prefs.getString(KEY_MEETING_VIDEO_CUSTOM_FOLDER_LABEL, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_MEETING_VIDEO_CUSTOM_FOLDER_LABEL, value.trim()).apply()

    fun effectiveMeetingVideoFolderDisplay(): String {
        val customUri = meetingVideoCustomTreeUri
        if (customUri.isNotBlank()) {
            return meetingVideoCustomFolderLabel.ifBlank { "Custom Folder" }
        }
        return meetingVideoSaveFolder
    }

    /**
     * Package name whose `RECORD_AUDIO` AppOp was temporarily set to `ignore` as a Stealth
     * Virtual Mic fallback. Committed synchronously so process death / reboot always recovers it.
     */
    var pendingAppOpsMicRestorePackage: String
        get() = prefs.getString(KEY_PENDING_APPOPS_MIC_RESTORE_PKG, "")?.trim().orEmpty()
        set(value) {
            prefs.edit()
                .putString(KEY_PENDING_APPOPS_MIC_RESTORE_PKG, value.trim())
                .commit()
        }

    var userDisplayName: String
        get() = prefs.getString(KEY_USER_DISPLAY_NAME, "Dr. (Me)") ?: "Dr. (Me)"
        set(value) = prefs.edit().putString(KEY_USER_DISPLAY_NAME, value.trim()).apply()

    var customGlossaryTerms: String
        get() = prefs.getString(KEY_CUSTOM_GLOSSARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_GLOSSARY, value).apply()

    var autoAddGlossaryTerms: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ADD_GLOSSARY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ADD_GLOSSARY, value).apply()

    fun getStructuredGlossaryEntries(): List<GlossaryTermEntry> {
        val raw = prefs.getString(KEY_STRUCTURED_GLOSSARY, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<GlossaryTermEntry>>(raw)
        }.getOrDefault(emptyList())
    }

    fun saveStructuredGlossaryEntries(entries: List<GlossaryTermEntry>) {
        val cleaned = entries
            .map { e ->
                e.copy(
                    term = e.term.trim(),
                    thaiPhonetic = e.thaiPhonetic.trim(),
                    category = GlossaryTermEntry.normalizeCategory(e.category),
                    notes = e.notes.trim(),
                    seenCount = e.seenCount.coerceAtLeast(1)
                )
            }
            .filter { it.term.isNotBlank() }
            .distinctBy { it.term.lowercase() }
        prefs.edit().putString(KEY_STRUCTURED_GLOSSARY, json.encodeToString(cleaned)).apply()
    }

    fun upsertGlossaryEntry(entry: GlossaryTermEntry): List<GlossaryTermEntry> {
        val cleanTerm = entry.term.trim()
        if (cleanTerm.isBlank()) return getStructuredGlossaryEntries()
        val now = System.currentTimeMillis()
        val normalized = entry.copy(
            term = cleanTerm,
            thaiPhonetic = entry.thaiPhonetic.trim(),
            category = GlossaryTermEntry.normalizeCategory(entry.category),
            notes = entry.notes.trim(),
            seenCount = entry.seenCount.coerceAtLeast(1),
            lastSeenAtMs = if (entry.lastSeenAtMs > 0L) entry.lastSeenAtMs else now
        )
        val list = getStructuredGlossaryEntries().toMutableList()
        val existingIdx = list.indexOfFirst {
            it.id == normalized.id || it.term.equals(cleanTerm, ignoreCase = true)
        }
        if (existingIdx >= 0) {
            val prev = list[existingIdx]
            list[existingIdx] = prev.copy(
                term = normalized.term,
                thaiPhonetic = normalized.thaiPhonetic.ifBlank { prev.thaiPhonetic },
                category = normalized.category,
                notes = normalized.notes.ifBlank { prev.notes },
                isAutoAdded = prev.isAutoAdded && normalized.isAutoAdded,
                seenCount = maxOf(prev.seenCount, normalized.seenCount),
                lastSeenAtMs = maxOf(prev.lastSeenAtMs, normalized.lastSeenAtMs),
                sourceSessionId = normalized.sourceSessionId ?: prev.sourceSessionId
            )
        } else {
            list.add(normalized)
        }
        saveStructuredGlossaryEntries(list)
        return getStructuredGlossaryEntries()
    }

    fun removeGlossaryEntry(entryId: String): List<GlossaryTermEntry> {
        val updated = getStructuredGlossaryEntries().filterNot { it.id == entryId }
        saveStructuredGlossaryEntries(updated)
        return updated
    }

    fun promoteGlossaryEntryToManual(entryId: String): List<GlossaryTermEntry> {
        val updated = getStructuredGlossaryEntries().map { entry ->
            if (entry.id == entryId) entry.copy(isAutoAdded = false) else entry
        }
        saveStructuredGlossaryEntries(updated)
        return updated
    }

    fun getProfilesDir(): File {
        return File(appContext.filesDir, "profiles").apply { mkdirs() }
    }

    fun getMyVoiceProfileFile(): File {
        return File(getProfilesDir(), "my_voice_profile.aac")
    }

    fun getMyVoiceSampleAudioFile(
        sampleId: String = UUID.randomUUID().toString().take(8),
        extension: String = "aac"
    ): File {
        val safeId = sampleId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val cleanExt = extension.trim().trimStart('.').lowercase().ifBlank { "aac" }
        return File(getProfilesDir(), "my_voice_${safeId}.$cleanExt")
    }

    fun getMyVoiceSampleWavFile(sampleId: String = UUID.randomUUID().toString().take(8)): File {
        return getMyVoiceSampleAudioFile(sampleId = sampleId, extension = "wav")
    }

    fun getMyVoiceSamples(): List<VoiceProfileSample> {
        val raw = prefs.getString(KEY_MY_VOICE_SAMPLES, null)
        val parsed = if (!raw.isNullOrBlank()) {
            runCatching { json.decodeFromString<List<VoiceProfileSample>>(raw) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val valid = parsed.filter { sample ->
            sample.filePath.isNotBlank() && runCatching {
                val f = File(sample.filePath)
                f.exists() && f.length() >= 1024L
            }.getOrDefault(false)
        }.distinctBy { it.filePath }
            .sortedWith(
                compareByDescending<VoiceProfileSample> { it.qualityScore }
                    .thenByDescending { it.isManual }
                    .thenByDescending { it.createdAtMs }
            )
            .take(MAX_VOICE_SAMPLES_PER_PROFILE)

        if (valid.isNotEmpty()) {
            return valid
        }

        // Seamless migration for legacy single-file my_voice_profile.aac
        val legacy = getMyVoiceProfileFile()
        if (legacy.exists() && legacy.length() > 4096L) {
            val estDur = runCatching { ClipSlicer.estimateAdtsDurationMs(legacy) }.getOrDefault(0L)
            val migrated = listOf(
                VoiceProfileSample(
                    id = "legacy",
                    filePath = legacy.absolutePath,
                    durationMs = estDur,
                    qualityScore = 72f,
                    createdAtMs = legacy.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
                    isManual = true
                )
            )
            saveMyVoiceSamples(migrated)
            return migrated
        }
        return emptyList()
    }

    fun saveMyVoiceSamples(samples: List<VoiceProfileSample>) {
        val cleaned = samples
            .filter { it.filePath.isNotBlank() && File(it.filePath).exists() && File(it.filePath).length() >= 1024L }
            .distinctBy { it.filePath }
            .sortedWith(
                compareByDescending<VoiceProfileSample> { it.qualityScore }
                    .thenByDescending { it.isManual }
                    .thenByDescending { it.createdAtMs }
            )
            .take(MAX_VOICE_SAMPLES_PER_PROFILE)
        prefs.edit().putString(KEY_MY_VOICE_SAMPLES, json.encodeToString(cleaned)).apply()
    }

    fun getBestMyVoiceFile(): File? {
        val best = getMyVoiceSamples().firstOrNull()
        if (best != null) {
            val f = File(best.filePath)
            if (f.exists() && f.length() >= 1024L) return f
        }
        val legacy = getMyVoiceProfileFile()
        return legacy.takeIf { it.exists() && it.length() > 4096L }
    }

    fun hasMyVoiceProfile(): Boolean {
        return getMyVoiceSamples().isNotEmpty()
    }

    /**
     * Adds a voice sample to "My Voice", keeping only the top [MAX_VOICE_SAMPLES_PER_PROFILE]
     * highest-quality clips and automatically deleting lower-quality evicted files on disk.
     * Returns Pair(wasKept, updatedSampleList).
     */
    fun addMyVoiceSample(candidate: VoiceProfileSample): Pair<Boolean, List<VoiceProfileSample>> {
        val current = getMyVoiceSamples()
        val (kept, wasAccepted) = mergeAndTrimSamples(current, candidate, MAX_VOICE_SAMPLES_PER_PROFILE)
        saveMyVoiceSamples(kept)
        return wasAccepted to kept
    }

    fun removeMyVoiceSample(sampleId: String): List<VoiceProfileSample> {
        val current = getMyVoiceSamples().toMutableList()
        val target = current.firstOrNull { it.id == sampleId }
        current.removeAll { it.id == sampleId }
        target?.filePath?.let { path ->
            runCatching { File(path).delete() }
        }
        if (sampleId == "legacy") {
            runCatching { getMyVoiceProfileFile().delete() }
        }
        saveMyVoiceSamples(current)
        return getMyVoiceSamples()
    }

    fun getSpeakerProfileAudioFile(speakerId: String): File {
        val safeId = speakerId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(getProfilesDir(), "spk_${safeId}.aac")
    }

    fun getSpeakerSampleAudioFile(
        speakerId: String,
        sampleId: String = UUID.randomUUID().toString().take(8),
        extension: String = "aac"
    ): File {
        val safeSpeaker = speakerId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeSample = sampleId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val cleanExt = extension.trim().trimStart('.').lowercase().ifBlank { "aac" }
        return File(getProfilesDir(), "spk_${safeSpeaker}_${safeSample}.$cleanExt")
    }

    fun getSpeakerSampleWavFile(
        speakerId: String,
        sampleId: String = UUID.randomUUID().toString().take(8)
    ): File {
        return getSpeakerSampleAudioFile(speakerId = speakerId, sampleId = sampleId, extension = "wav")
    }

    /**
     * Merges [candidate] into [existing] while:
     * 1) Avoiding duplicate/overlapping auto samples from the same session timestamp window (keeps higher-scoring clip)
     * 2) Allowing multiple distinct solo speech windows so each profile can retain up to [maxSamples] (10) best clips
     * 3) Keeping at most [maxSamples] highest-quality voice signature samples
     * 4) Deleting evicted or rejected sample files on disk safely
     */
    fun mergeAndTrimSamples(
        existing: List<VoiceProfileSample>,
        candidate: VoiceProfileSample,
        maxSamples: Int = MAX_VOICE_SAMPLES_PER_PROFILE,
        minReplaceDelta: Float = 1.0f
    ): Pair<List<VoiceProfileSample>, Boolean> {
        val validExisting = existing.filter { s ->
            s.filePath.isNotBlank() && runCatching {
                val f = File(s.filePath)
                f.exists() && f.length() >= 1024L
            }.getOrDefault(false)
        }.toMutableList()

        val candFile = File(candidate.filePath)
        if (!candFile.exists() || candFile.length() < 1024L) {
            return validExisting.sortedByDescending { it.qualityScore }.take(maxSamples) to false
        }

        // If an overlapping auto sample from the same session & timestamp window already exists, keep only the better clip
        if (!candidate.isManual && !candidate.sourceSessionId.isNullOrBlank()) {
            val overlappingSameSessionIdx = validExisting.indexOfFirst { prev ->
                !prev.isManual &&
                    prev.sourceSessionId == candidate.sourceSessionId &&
                    (
                        prev.filePath == candidate.filePath ||
                            (prev.createdAtMs > 0L && candidate.createdAtMs > 0L &&
                                kotlin.math.abs(prev.createdAtMs - candidate.createdAtMs) < 2_500L)
                        )
            }
            if (overlappingSameSessionIdx >= 0) {
                val prev = validExisting[overlappingSameSessionIdx]
                if (candidate.qualityScore <= prev.qualityScore + 0.5f) {
                    if (candidate.filePath != prev.filePath) {
                        runCatching { candFile.delete() }
                    }
                    val sorted = validExisting.sortedWith(
                        compareByDescending<VoiceProfileSample> { it.qualityScore }
                            .thenByDescending { it.isManual }
                            .thenByDescending { it.createdAtMs }
                    ).take(maxSamples)
                    return sorted to false
                } else {
                    if (prev.filePath != candidate.filePath) {
                        runCatching { File(prev.filePath).delete() }
                    }
                    validExisting.removeAt(overlappingSameSessionIdx)
                }
            }
        }

        // If already at cap (10 samples) and candidate is not meaningfully better than the worst kept sample
        if (validExisting.size >= maxSamples) {
            val sortedExisting = validExisting.sortedWith(
                compareByDescending<VoiceProfileSample> { it.qualityScore }
                    .thenByDescending { it.isManual }
                    .thenByDescending { it.createdAtMs }
            )
            val worstKept = sortedExisting.last()
            val threshold = if (candidate.isManual) 0f else minReplaceDelta
            if (candidate.qualityScore < worstKept.qualityScore + threshold) {
                if (sortedExisting.none { it.filePath == candidate.filePath }) {
                    runCatching { candFile.delete() }
                }
                return sortedExisting.take(maxSamples) to false
            }
        }

        validExisting.removeAll { it.id == candidate.id || it.filePath == candidate.filePath }
        validExisting.add(candidate)

        val sortedAll = validExisting.sortedWith(
            compareByDescending<VoiceProfileSample> { it.qualityScore }
                .thenByDescending { it.isManual }
                .thenByDescending { it.createdAtMs }
        )
        val kept = sortedAll.take(maxSamples)
        val keptPaths = kept.map { it.filePath }.toSet()
        sortedAll.drop(maxSamples).forEach { evicted ->
            if (evicted.filePath !in keptPaths) {
                runCatching { File(evicted.filePath).delete() }
            }
        }
        val wasAccepted = kept.any { it.id == candidate.id && it.filePath == candidate.filePath }
        return kept to wasAccepted
    }

    fun getSpeakerRoster(): List<SpeakerProfileEntry> {
        val raw = prefs.getString(KEY_SPEAKER_ROSTER, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<SpeakerProfileEntry>>(raw) }.getOrDefault(emptyList())
    }

    fun saveSpeakerRoster(entries: List<SpeakerProfileEntry>) {
        val cleaned = entries.filter { it.effectiveNickname().isNotBlank() }
            .map { entry ->
                val cleanNick = entry.effectiveNickname()
                val cleanTitle = entry.effectiveRelatableTitle()
                val cleanSig = entry.voiceSignature.trim().ifBlank { entry.voiceHint.trim() }
                val existingSamples = entry.existingSamples()
                val best = existingSamples.firstOrNull()
                val cleanAliases = entry.aliases
                    .map { it.trim() }
                    .filter {
                        it.isNotBlank() &&
                            !it.equals(cleanNick, ignoreCase = true) &&
                            !it.equals(entry.displayName.trim(), ignoreCase = true)
                    }
                    .distinctBy { it.lowercase() }
                entry.copy(
                    displayName = entry.displayName.trim().ifBlank { cleanNick },
                    nickname = cleanNick,
                    role = entry.role.trim().ifBlank { cleanTitle },
                    relatableTitle = cleanTitle,
                    voiceHint = entry.voiceHint.trim().ifBlank { cleanSig },
                    voiceSignature = cleanSig,
                    samples = existingSamples,
                    audioPath = best?.filePath ?: entry.audioPath?.takeIf { File(it).exists() },
                    sampleDurationMs = best?.durationMs ?: entry.sampleDurationMs,
                    sourceSessionId = best?.sourceSessionId ?: entry.sourceSessionId,
                    aliases = cleanAliases
                )
            }
        prefs.edit().putString(KEY_SPEAKER_ROSTER, json.encodeToString(cleaned)).apply()
    }

    fun upsertSpeakerProfile(entry: SpeakerProfileEntry): List<SpeakerProfileEntry> {
        val cleanNick = entry.effectiveNickname()
        if (cleanNick.isBlank()) return getSpeakerRoster()
        val cleanTitle = entry.effectiveRelatableTitle()
        val cleanSig = entry.voiceSignature.trim().ifBlank { entry.voiceHint.trim() }
        val current = getSpeakerRoster().toMutableList()
        val entryCentroid = entry.voiceprintCentroid()
        val idx = current.indexOfFirst { prev ->
            if (prev.id == entry.id) {
                true
            } else if (
                prev.displayName.equals(cleanNick, ignoreCase = true) ||
                prev.effectiveNickname().equals(cleanNick, ignoreCase = true)
            ) {
                val prevCentroid = prev.voiceprintCentroid()
                if (entryCentroid.isNotEmpty() && prevCentroid.isNotEmpty()) {
                    com.ambientbrain.recorder.ai.SpeakerEmbeddingEngine.areEmbeddingsBiometricallyCompatible(
                        entryCentroid,
                        prevCentroid
                    )
                } else {
                    true
                }
            } else {
                false
            }
        }
        val normalized = entry.copy(
            displayName = entry.displayName.trim().ifBlank { cleanNick },
            nickname = cleanNick,
            role = entry.role.trim().ifBlank { cleanTitle },
            relatableTitle = cleanTitle,
            voiceHint = entry.voiceHint.trim().ifBlank { cleanSig },
            voiceSignature = cleanSig,
            lastHeardAtMs = if (entry.lastHeardAtMs > 0L) entry.lastHeardAtMs else System.currentTimeMillis()
        )
        if (idx >= 0) {
            val prev = current[idx]
            val mergedSamples = (normalized.existingSamples() + prev.existingSamples())
                .distinctBy { it.filePath }
                .sortedWith(
                    compareByDescending<VoiceProfileSample> { it.qualityScore }
                        .thenByDescending { it.isManual }
                        .thenByDescending { it.createdAtMs }
                )
                .take(MAX_VOICE_SAMPLES_PER_PROFILE)
            val best = mergedSamples.firstOrNull()
            val mergedAliases = (prev.aliases + normalized.aliases)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
            current[idx] = normalized.copy(
                id = prev.id,
                samples = mergedSamples,
                audioPath = best?.filePath ?: normalized.audioPath ?: prev.audioPath,
                isPinned = normalized.isPinned || prev.isPinned,
                isAutoDiscovered = normalized.isAutoDiscovered && prev.isAutoDiscovered,
                sessionsHeardCount = maxOf(prev.sessionsHeardCount, normalized.sessionsHeardCount),
                sampleDurationMs = best?.durationMs ?: if (normalized.sampleDurationMs > 0L) normalized.sampleDurationMs else prev.sampleDurationMs,
                sourceSessionId = best?.sourceSessionId ?: normalized.sourceSessionId ?: prev.sourceSessionId,
                aliases = mergedAliases
            )
        } else {
            current.add(normalized)
        }
        saveSpeakerRoster(current)
        return getSpeakerRoster()
    }

    fun addSpeakerVoiceSample(
        speakerId: String,
        candidate: VoiceProfileSample
    ): Pair<Boolean, SpeakerProfileEntry?> {
        val current = getSpeakerRoster().toMutableList()
        val idx = current.indexOfFirst { it.id == speakerId }
        if (idx < 0) return false to null
        val entry = current[idx]
        val (kept, accepted) = mergeAndTrimSamples(entry.existingSamples(), candidate, MAX_VOICE_SAMPLES_PER_PROFILE)
        val best = kept.firstOrNull()
        val updated = entry.copy(
            samples = kept,
            audioPath = best?.filePath,
            sampleDurationMs = best?.durationMs ?: 0L,
            sourceSessionId = best?.sourceSessionId ?: entry.sourceSessionId,
            lastHeardAtMs = maxOf(entry.lastHeardAtMs, candidate.createdAtMs)
        )
        current[idx] = updated
        saveSpeakerRoster(current)
        return accepted to getSpeakerRoster().firstOrNull { it.id == speakerId }
    }

    fun removeSpeakerVoiceSample(
        speakerId: String,
        sampleId: String
    ): List<SpeakerProfileEntry> {
        val current = getSpeakerRoster().toMutableList()
        val idx = current.indexOfFirst { it.id == speakerId }
        if (idx < 0) return current
        val entry = current[idx]
        val existing = entry.existingSamples().toMutableList()
        val target = existing.firstOrNull { it.id == sampleId }
        existing.removeAll { it.id == sampleId }
        target?.filePath?.let { path ->
            runCatching { File(path).delete() }
        }
        if (sampleId == "legacy") {
            runCatching { getSpeakerProfileAudioFile(speakerId).delete() }
        }
        val best = existing.firstOrNull()
        current[idx] = entry.copy(
            samples = existing,
            audioPath = best?.filePath,
            sampleDurationMs = best?.durationMs ?: 0L
        )
        saveSpeakerRoster(current)
        return getSpeakerRoster()
    }

    fun updateSpeakerProfileById(
        speakerId: String,
        transform: (SpeakerProfileEntry) -> SpeakerProfileEntry
    ): List<SpeakerProfileEntry> {
        val current = getSpeakerRoster().toMutableList()
        val idx = current.indexOfFirst { it.id == speakerId }
        if (idx < 0) return current
        val updated = transform(current[idx])
        if (updated.effectiveNickname().isBlank()) return current
        current[idx] = updated
        saveSpeakerRoster(current)
        return getSpeakerRoster()
    }

    fun removeSpeakerProfile(speakerId: String): List<SpeakerProfileEntry> {
        val current = getSpeakerRoster().toMutableList()
        val removed = current.firstOrNull { it.id == speakerId }
        current.removeAll { it.id == speakerId }
        removed?.samples?.forEach { sample ->
            runCatching { File(sample.filePath).delete() }
        }
        removed?.audioPath?.let { path ->
            runCatching { File(path).delete() }
        }
        runCatching { getSpeakerProfileAudioFile(speakerId).delete() }
        saveSpeakerRoster(current)
        return getSpeakerRoster()
    }

    /**
     * Builds the optimal set of reference voice clips to attach to Gemini:
     * - Prioritizes "My Voice" (up to [maxSamplesPerTopSpeaker] highest-quality diverse clips)
     * - Pass 1: Adds the #1 highest-quality clip for up to [maxExtraSpeakers] top roster profiles
     *   (On-device voiceprint match -> Pinned -> Manual -> Most Heard -> Highest Quality -> Most Recent)
     * - Pass 2: Adds a 2nd diverse clip (different session or manual+auto complement) for top
     *   matched/pinned/frequent speakers while staying strictly under [maxTotalBytes].
     */
    fun getAttachableVoiceClips(
        maxExtraSpeakers: Int = 6,
        maxSamplesPerTopSpeaker: Int = 2,
        maxTotalBytes: Long = 3_200_000L,
        prioritizedSpeakerScores: Map<String, Float> = emptyMap()
    ): List<AttachableVoiceClip> {
        val selectedBySpeaker = linkedMapOf<String, MutableList<Pair<SpeakerProfileEntry, VoiceProfileSample>>>()
        var totalBytes = 0L

        fun pickDiverseSamples(samples: List<VoiceProfileSample>, limit: Int): List<VoiceProfileSample> {
            if (samples.size <= 1 || limit <= 1) return samples.take(limit)
            val chosen = mutableListOf(samples.first())
            val first = samples.first()
            // Prefer a second clip from a different recording session or manual/auto complement for acoustic diversity
            val diverse = samples.drop(1).firstOrNull { s ->
                s.qualityScore >= 46f && (
                    (!s.sourceSessionId.isNullOrBlank() && s.sourceSessionId != first.sourceSessionId) ||
                        (s.isManual != first.isManual)
                    )
            } ?: samples.drop(1).firstOrNull { it.qualityScore >= 46f }
            if (diverse != null) {
                chosen.add(diverse)
            }
            return chosen.take(limit)
        }

        // 1. My Voice Profile (Device Owner)
        val mySamples = getMyVoiceSamples()
        if (mySamples.isNotEmpty()) {
            val userLabel = userDisplayName.ifBlank { "Dr. (Me)" }
            val myBest = mySamples.first()
            val myEntry = SpeakerProfileEntry(
                id = "me",
                displayName = userLabel,
                nickname = userLabel,
                role = "Device Owner / Primary User",
                relatableTitle = "Device Owner / Primary User",
                audioPath = myBest.filePath,
                isUser = true,
                isPinned = true,
                sampleDurationMs = myBest.durationMs,
                samples = mySamples
            )
            val picks = pickDiverseSamples(mySamples, maxSamplesPerTopSpeaker)
            val list = selectedBySpeaker.getOrPut(myEntry.id) { mutableListOf() }
            for (s in picks) {
                val f = File(s.filePath)
                if (!f.exists() || f.length() < 1024L || totalBytes + f.length() > maxTotalBytes) continue
                list.add(myEntry to s)
                totalBytes += f.length()
            }
            if (list.isEmpty()) selectedBySpeaker.remove(myEntry.id)
        }

        // 2. Roster speakers ordered by on-device acoustic pre-match, pinned importance, & sample quality
        val rankedSpeakers = getSpeakerRoster()
            .asSequence()
            .mapNotNull { spk ->
                val valid = spk.existingSamples()
                if (valid.isNotEmpty()) spk to valid else null
            }
            .sortedWith(
                compareByDescending<Pair<SpeakerProfileEntry, List<VoiceProfileSample>>> {
                    (prioritizedSpeakerScores[it.first.id] ?: 0f) >= 0.58f
                }
                    .thenByDescending { it.first.isPinned }
                    .thenByDescending { ((prioritizedSpeakerScores[it.first.id] ?: 0f) * 20f).toInt() }
                    .thenByDescending { !it.first.isAutoDiscovered }
                    .thenByDescending { it.first.sessionsHeardCount }
                    .thenByDescending { it.second.first().qualityScore }
                    .thenByDescending { it.first.lastHeardAtMs }
            )
            .take(maxExtraSpeakers)
            .toList()

        // Pass 1: #1 best sample for every top speaker
        for ((spk, validSamples) in rankedSpeakers) {
            val best = validSamples.first()
            val f = File(best.filePath)
            if (!f.exists() || f.length() !in 1024L..maxTotalBytes) continue
            if (totalBytes + f.length() > maxTotalBytes) continue
            selectedBySpeaker.getOrPut(spk.id) { mutableListOf() }.add(spk to best)
            totalBytes += f.length()
        }

        // Pass 2: 2nd diverse sample for top matched/pinned/manual/frequent speakers if budget allows
        if (maxSamplesPerTopSpeaker >= 2) {
            for ((spk, validSamples) in rankedSpeakers) {
                if (validSamples.size < 2) continue
                val existingList = selectedBySpeaker[spk.id] ?: continue
                if (existingList.size >= maxSamplesPerTopSpeaker) continue
                val preMatchScore = prioritizedSpeakerScores[spk.id] ?: 0f
                val eligibleForSecond = preMatchScore >= 0.58f || spk.isPinned || !spk.isAutoDiscovered || spk.sessionsHeardCount >= 2
                if (!eligibleForSecond) continue
                val picks = pickDiverseSamples(validSamples, 2)
                val second = picks.getOrNull(1) ?: continue
                val f = File(second.filePath)
                if (!f.exists() || f.length() < 1024L || totalBytes + f.length() > maxTotalBytes) continue
                existingList.add(spk to second)
                totalBytes += f.length()
            }
        }

        // Flatten with per-speaker Sample i/N indexing
        val result = mutableListOf<AttachableVoiceClip>()
        selectedBySpeaker.values.forEach { pairs ->
            val totalForSpk = pairs.size
            pairs.forEachIndexed { idx, (entry, sample) ->
                val file = File(sample.filePath)
                if (file.exists() && file.length() >= 1024L) {
                    result.add(
                        AttachableVoiceClip(
                            entry = entry,
                            file = file,
                            sampleIndex = idx + 1,
                            sampleTotal = totalForSpk,
                            qualityScore = sample.qualityScore
                        )
                    )
                }
            }
        }
        return result
    }

    /**
     * Backward-compatible wrapper returning the single best clip per attached profile.
     */
    fun getAttachableVoiceProfiles(
        maxExtra: Int = 5,
        maxTotalBytes: Long = 600_000L
    ): List<Pair<SpeakerProfileEntry, File>> {
        return getAttachableVoiceClips(
            maxExtraSpeakers = maxExtra,
            maxSamplesPerTopSpeaker = 1,
            maxTotalBytes = maxTotalBytes
        ).map { it.entry to it.file }
    }

    fun getSavedApiKeys(): List<String> {
        val raw = prefs.getString(KEY_SAVED_API_KEYS, null)
        if (raw.isNullOrBlank()) {
            val single = geminiApiKey.trim()
            return if (single.isNotBlank()) listOf(single) else emptyList()
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val key = arr.optString(i)?.trim().orEmpty()
                if (key.isNotBlank()) list.add(key)
            }
            val distinct = list.distinct()
            if (distinct.isEmpty() && geminiApiKey.isNotBlank()) {
                listOf(geminiApiKey.trim())
            } else {
                distinct
            }
        } catch (_: Exception) {
            val single = geminiApiKey.trim()
            if (single.isNotBlank()) listOf(single) else emptyList()
        }
    }

    /**
     * Adds an API key to the pool.
     * Returns true if added, false if it was already present.
     */
    fun addApiKey(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return false
        val current = getSavedApiKeys().toMutableList()
        if (current.contains(trimmed)) {
            return false // Duplicate: already in database!
        }
        current.add(trimmed)
        saveSavedApiKeys(current)
        if (geminiApiKey.isBlank()) {
            geminiApiKey = trimmed
        }
        return true
    }

    fun removeApiKey(key: String): Boolean {
        val trimmed = key.trim()
        val current = getSavedApiKeys().toMutableList()
        val removed = current.remove(trimmed)
        if (removed) {
            saveSavedApiKeys(current)
            if (geminiApiKey == trimmed) {
                geminiApiKey = current.firstOrNull() ?: ""
            }
            synchronized(healthLock) {
                val map = getApiKeyHealthMap().toMutableMap()
                if (map.remove(apiKeyFingerprint(trimmed)) != null) {
                    saveApiKeyHealthMap(map)
                }
            }
        }
        return removed
    }

    fun setSavedApiKeys(keys: List<String>) {
        val distinct = keys.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        saveSavedApiKeys(distinct)
        if (geminiApiKey.isBlank() && distinct.isNotEmpty()) {
            geminiApiKey = distinct.first()
        }
    }

    private fun saveSavedApiKeys(keys: List<String>) {
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        prefs.edit().putString(KEY_SAVED_API_KEYS, arr.toString()).apply()
    }

    fun getAllApiKeys(): List<String> {
        val pool = getSavedApiKeys()
        val single = geminiApiKey.trim()
        return if (single.isNotBlank() && single !in pool) {
            pool + single
        } else {
            pool
        }
    }

    /**
     * Returns all saved API keys in a fresh cryptographically shuffled random order,
     * ensuring requests load-balance randomly across the entire pool instead of
     * always picking the first key.
     */
    fun getShuffledApiKeys(): List<String> {
        val keys = getAllApiKeys()
        if (keys.size <= 1) return keys
        return keys.shuffled(java.security.SecureRandom())
    }

    // ---- Per-Key Health & Per-Model Cooldown Tracking ----

    fun getApiKeyHealthMap(): Map<String, ApiKeyHealth> {
        val raw = prefs.getString(KEY_API_KEY_HEALTH_JSON, null)
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<List<ApiKeyHealth>>(raw).associateBy { it.keyFingerprint }
        }.getOrDefault(emptyMap())
    }

    private fun saveApiKeyHealthMap(map: Map<String, ApiKeyHealth>) {
        prefs.edit().putString(KEY_API_KEY_HEALTH_JSON, json.encodeToString(map.values.toList())).apply()
    }

    fun getApiKeyHealth(key: String): ApiKeyHealth {
        val fp = apiKeyFingerprint(key)
        return getApiKeyHealthMap()[fp] ?: ApiKeyHealth(keyFingerprint = fp)
    }

    fun isKeyInCooldownForModel(key: String, model: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return getApiKeyHealth(key).isCoolingDownForModel(model, nowMs)
    }

    fun recordApiKeySuccess(key: String, model: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return
        val normalizedModel = normalizeSupportedModel(model)
        val now = System.currentTimeMillis()
        synchronized(healthLock) {
            val map = getApiKeyHealthMap().toMutableMap()
            val fp = apiKeyFingerprint(trimmed)
            val prev = map[fp] ?: ApiKeyHealth(keyFingerprint = fp)
            val updatedModelCooldowns = prev.modelCooldowns
                .filterValues { it > now }
                .toMutableMap()
                .apply { remove(normalizedModel) }
            map[fp] = prev.copy(
                status = ApiKeyHealth.STATUS_HEALTHY,
                cooldownUntilMs = 0L,
                modelCooldowns = updatedModelCooldowns,
                consecutiveFailures = 0,
                successCount = prev.successCount + 1,
                lastSuccessMs = now,
                lastErrorMessage = ""
            )
            saveApiKeyHealthMap(map)
        }
    }

    fun recordApiKeyFailure(
        key: String,
        model: String,
        failureCategory: String,
        errorMsg: String,
        retryAfterMs: Long? = null
    ) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return
        val catLower = failureCategory.lowercase()
        val msgLower = errorMsg.lowercase()

        // Network/timeout failures are device connectivity issues, not bad API keys
        if (catLower.contains("network") || catLower.contains("timeout")) {
            return
        }

        val normalizedModel = normalizeSupportedModel(model)
        val now = System.currentTimeMillis()

        synchronized(healthLock) {
            val map = getApiKeyHealthMap().toMutableMap()
            val fp = apiKeyFingerprint(trimmed)
            val prev = map[fp] ?: ApiKeyHealth(keyFingerprint = fp)
            val failures = prev.consecutiveFailures + 1
            val updatedModelCooldowns = prev.modelCooldowns.filterValues { it > now }.toMutableMap()

            val isBillingOrPaidOnly = catLower.contains("paid tier") ||
                catLower.contains("billing") ||
                msgLower.contains("paid tier") ||
                msgLower.contains("limit: 0")
            val isAuthError = !isBillingOrPaidOnly && (
                catLower.contains("invalid") ||
                    catLower.contains("restricted") ||
                    catLower.contains("auth") ||
                    msgLower.contains("api_key_invalid") ||
                    msgLower.contains("api key not valid") ||
                    msgLower.contains("http 401")
                )
            val isRateLimit = catLower.contains("rate limit") ||
                catLower.contains("quota") ||
                catLower.contains("429") ||
                msgLower.contains("429") ||
                msgLower.contains("resource_exhausted")
            val isModelUnavailable = catLower.contains("model unavailable") ||
                catLower.contains("404")

            val newStatus: String
            var globalCooldown = prev.cooldownUntilMs.takeIf { it > now } ?: 0L

            when {
                isAuthError -> {
                    newStatus = ApiKeyHealth.STATUS_AUTH_ERROR
                    globalCooldown = now + 10L * 60_000L // 10 min global deprioritization
                }
                isBillingOrPaidOnly -> {
                    // Only cool down this paid model — keep key immediately usable for Free-tier fallback!
                    newStatus = if (prev.status == ApiKeyHealth.STATUS_HEALTHY) {
                        ApiKeyHealth.STATUS_HEALTHY
                    } else {
                        ApiKeyHealth.STATUS_RATE_LIMITED
                    }
                    updatedModelCooldowns[normalizedModel] = now + 30L * 60_000L
                }
                isRateLimit -> {
                    // Honor Google's explicit retryDelay or Midnight PT RPD reset timestamp (up to 24h),
                    // else progressive backoff (2m -> 5m -> 10m)
                    newStatus = ApiKeyHealth.STATUS_RATE_LIMITED
                    val backoffMs = retryAfterMs?.coerceIn(15_000L, 24L * 60L * 60_000L) ?: when {
                        failures <= 1 -> 2L * 60_000L
                        failures == 2 -> 5L * 60_000L
                        else -> 10L * 60_000L
                    }
                    updatedModelCooldowns[normalizedModel] = now + backoffMs
                }
                isModelUnavailable -> {
                    newStatus = prev.status.ifBlank { ApiKeyHealth.STATUS_UNTESTED }
                    updatedModelCooldowns[normalizedModel] = now + 15L * 60_000L
                }
                else -> {
                    newStatus = ApiKeyHealth.STATUS_ERROR
                    updatedModelCooldowns[normalizedModel] = now + 60_000L
                }
            }

            map[fp] = prev.copy(
                status = newStatus,
                cooldownUntilMs = globalCooldown,
                modelCooldowns = updatedModelCooldowns,
                consecutiveFailures = failures,
                errorCount = prev.errorCount + 1,
                lastErrorMs = now,
                lastErrorMessage = errorMsg.take(160)
            )
            saveApiKeyHealthMap(map)
        }
    }

    fun resetAllApiKeyHealth() {
        synchronized(healthLock) {
            prefs.edit().remove(KEY_API_KEY_HEALTH_JSON).apply()
        }
    }

    /**
     * Marks all API keys available again when Google's daily quota resets at 00:00 Pacific Time
     * (`America/Los_Angeles`), clearing daily RPD and transient rate-limit cooldowns while preserving
     * historical lifetime success/error counters.
     */
    fun resetKeysForGoogleDailyReset(newPtDate: String): Int {
        synchronized(healthLock) {
            val map = getApiKeyHealthMap().toMutableMap()
            var resetCount = 0
            for ((fp, health) in map.entries.toList()) {
                val restoredStatus = if (health.successCount > 0) {
                    ApiKeyHealth.STATUS_HEALTHY
                } else {
                    ApiKeyHealth.STATUS_UNTESTED
                }
                val hadRestrictions = health.status != restoredStatus ||
                    health.cooldownUntilMs > 0L ||
                    health.modelCooldowns.isNotEmpty() ||
                    health.consecutiveFailures > 0
                if (hadRestrictions) {
                    map[fp] = health.copy(
                        status = restoredStatus,
                        cooldownUntilMs = 0L,
                        modelCooldowns = emptyMap(),
                        consecutiveFailures = 0,
                        lastErrorMessage = ""
                    )
                    resetCount++
                }
            }
            if (resetCount > 0) {
                saveApiKeyHealthMap(map)
            }
            prefs.edit().putString(KEY_LAST_RATE_LIMIT_RESET_PT_DATE, newPtDate).apply()
            return resetCount
        }
    }

    var lastRateLimitResetPtDate: String
        get() = prefs.getString(KEY_LAST_RATE_LIMIT_RESET_PT_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_RATE_LIMIT_RESET_PT_DATE, value).apply()

    /**
     * Smart health-aware & per-model cooldown-aware key ordering for [model]:
     * 1. Healthy or untested keys NOT in cooldown for [model] (cryptographically shuffled)
     * 2. Previously rate-limited/errored keys whose cooldown for [model] has expired (shuffled)
     * 3. Keys still cooling down for [model] (soonest expiry first, as last-resort fallback)
     * 4. Auth-error keys (tried very last in case key permissions were restored)
     */
    fun getPrioritizedApiKeysForModel(model: String): List<String> {
        val allKeys = getAllApiKeys()
        if (allKeys.size <= 1) return allKeys

        val normalizedModel = normalizeSupportedModel(model)
        val now = System.currentTimeMillis()
        val healthMap = getApiKeyHealthMap()
        val rng = java.security.SecureRandom()

        val readyFresh = mutableListOf<String>()
        val recoveredAfterCooldown = mutableListOf<String>()
        val coolingDown = mutableListOf<Pair<String, Long>>()
        val authFailed = mutableListOf<String>()

        for (key in allKeys) {
            val h = healthMap[apiKeyFingerprint(key)]
            if (h == null) {
                readyFresh.add(key)
                continue
            }
            val activeCooldownUntil = h.activeCooldownUntilForModel(normalizedModel, now)
            when {
                h.status == ApiKeyHealth.STATUS_AUTH_ERROR -> authFailed.add(key)
                activeCooldownUntil > now -> coolingDown.add(key to activeCooldownUntil)
                h.status == ApiKeyHealth.STATUS_HEALTHY || h.status == ApiKeyHealth.STATUS_UNTESTED -> readyFresh.add(key)
                else -> recoveredAfterCooldown.add(key)
            }
        }

        return buildList {
            addAll(readyFresh.shuffled(rng))
            addAll(recoveredAfterCooldown.shuffled(rng))
            addAll(coolingDown.sortedBy { it.second }.map { it.first })
            addAll(authFailed.shuffled(rng))
        }
    }

    // ---- Live Model Allowance Cache ----

    fun getCachedModelAllowance(): List<ModelAllowanceEntry> {
        val raw = prefs.getString(KEY_MODEL_ALLOWANCE_CACHE, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ModelAllowanceEntry>>(raw)
        }.getOrDefault(emptyList())
    }

    fun saveCachedModelAllowance(entries: List<ModelAllowanceEntry>) {
        prefs.edit().putString(KEY_MODEL_ALLOWANCE_CACHE, json.encodeToString(entries)).apply()
    }

    // ---- Gemini Temporary Pause Controls ----

    var isGeminiPaused: Boolean
        get() = prefs.getBoolean(KEY_GEMINI_PAUSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GEMINI_PAUSED, value).apply()
            if (!value) {
                prefs.edit().putLong(KEY_GEMINI_PAUSED_UNTIL_MS, 0L).apply()
                GeminiPauseStateBus.publish(false, 0L)
            } else {
                GeminiPauseStateBus.publish(true, geminiPausedUntilMs)
            }
        }

    var geminiPausedUntilMs: Long
        get() = prefs.getLong(KEY_GEMINI_PAUSED_UNTIL_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_GEMINI_PAUSED_UNTIL_MS, value.coerceAtLeast(0L)).apply()
            GeminiPauseStateBus.publish(isGeminiPaused, value.coerceAtLeast(0L))
        }

    fun setGeminiPaused(paused: Boolean, durationMs: Long = 0L) {
        val until = if (paused && durationMs > 0L) System.currentTimeMillis() + durationMs else 0L
        prefs.edit()
            .putBoolean(KEY_GEMINI_PAUSED, paused)
            .putLong(KEY_GEMINI_PAUSED_UNTIL_MS, until)
            .apply()
        GeminiPauseStateBus.publish(paused, until)
    }

    /**
     * Checks whether Gemini API calls are currently paused, auto-expiring timed pauses cleanly.
     */
    fun isGeminiUsagePausedNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val paused = prefs.getBoolean(KEY_GEMINI_PAUSED, false)
        if (!paused) {
            if (GeminiPauseStateBus.state.value.isPaused) {
                GeminiPauseStateBus.publish(false, 0L)
            }
            return false
        }
        val until = prefs.getLong(KEY_GEMINI_PAUSED_UNTIL_MS, 0L)
        if (until > 0L && nowMs >= until) {
            setGeminiPaused(false, 0L)
            return false
        }
        if (!GeminiPauseStateBus.state.value.isPaused || GeminiPauseStateBus.state.value.pausedUntilMs != until) {
            GeminiPauseStateBus.publish(true, until)
        }
        return true
    }

    fun syncGeminiPauseStateBus() {
        isGeminiUsagePausedNow()
    }

    // ---- Rate Limit Reference & Adaptive Strategy Persistence ----

    /**
     * When true (default), uses the built-in Nov 20, 2026 reference rate limit table and
     * default conservative Gemini API strategy. Turning this OFF enables Live Google rate-limit
     * adaptation (live checks + real-time 429 quota calibration).
     */
    var useDefaultRateLimitReference: Boolean
        get() = prefs.getBoolean(KEY_USE_DEFAULT_RATE_LIMIT_REF, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_DEFAULT_RATE_LIMIT_REF, value).apply()
        }

    fun getLiveRateLimitSnapshot(): GeminiRateLimitSnapshot? {
        val raw = prefs.getString(KEY_LIVE_RATE_LIMIT_SNAPSHOT_JSON, null)
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString<GeminiRateLimitSnapshot>(raw)
        }.getOrNull()
    }

    fun saveLiveRateLimitSnapshot(snapshot: GeminiRateLimitSnapshot) {
        prefs.edit().putString(KEY_LIVE_RATE_LIMIT_SNAPSHOT_JSON, json.encodeToString(snapshot)).apply()
    }

    fun clearLiveRateLimitSnapshot() {
        prefs.edit().remove(KEY_LIVE_RATE_LIMIT_SNAPSHOT_JSON).apply()
    }

    fun exportSettingsPayload(includeApiKeys: Boolean = false): SettingsBackupPayload {
        val versionName = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        val normalizedRoster = getSpeakerRoster().map { spk ->
            val portableSamples = spk.existingSamples().map { s ->
                s.copy(filePath = File(s.filePath).name)
            }
            val portableBestAudio = portableSamples.firstOrNull()?.filePath
                ?: spk.audioPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 1024L }?.name
            spk.copy(
                audioPath = portableBestAudio,
                samples = portableSamples
            )
        }
        val portableMySamples = getMyVoiceSamples().map { s ->
            s.copy(filePath = File(s.filePath).name)
        }
        val exportedPool = if (includeApiKeys) getSavedApiKeys() else emptyList()
        val exportedPrimaryKey = if (includeApiKeys) geminiApiKey.trim() else ""
        return SettingsBackupPayload(
            format = SETTINGS_BACKUP_FORMAT,
            schemaVersion = 5,
            exportedAtMs = System.currentTimeMillis(),
            appVersionName = versionName,
            micSharingMode = micSharingMode.name,
            geminiModel = geminiModel,
            fallbackGeminiModel = fallbackGeminiModel,
            transcriptLangMode = transcriptLangMode.name,
            autoDetectRounds = autoDetectRounds,
            autoRecognizeVoiceProfiles = autoRecognizeVoiceProfiles,
            autoAddGlossaryTerms = autoAddGlossaryTerms,
            autoMergeContinuousFiles = autoMergeContinuousFiles,
            autoMergeGapMinutes = autoMergeGapMinutes,
            defaultReviewSourceIsAudio = defaultReviewSourceIsAudio,
            minMeaningfulSpeechSeconds = minMeaningfulSpeechSeconds,
            silenceSplitSeconds = silenceSplitSeconds,
            roundSilenceSplitSeconds = roundSilenceSplitSeconds,
            retentionDays = retentionDays,
            meetingVideoSaveFolder = meetingVideoSaveFolder,
            userDisplayName = userDisplayName,
            customGlossaryTerms = customGlossaryTerms,
            structuredGlossaryEntries = getStructuredGlossaryEntries(),
            speakerRoster = normalizedRoster,
            myVoiceSamples = portableMySamples,
            includesApiKeys = includeApiKeys && (exportedPrimaryKey.isNotBlank() || exportedPool.isNotEmpty()),
            geminiApiKey = exportedPrimaryKey,
            savedApiKeysPool = exportedPool,
            hasMyVoiceProfileHint = hasMyVoiceProfile(),
            wasListeningEnabled = isListeningEnabled
        )
    }

    fun encodeSettingsBackupJson(includeApiKeys: Boolean = false): String {
        val pretty = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
        return pretty.encodeToString(exportSettingsPayload(includeApiKeys))
    }

    fun decodeSettingsBackupJson(rawJson: String): SettingsBackupPayload {
        val trimmed = rawJson.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Backup file is empty.")
        }
        val payload = json.decodeFromString<SettingsBackupPayload>(trimmed)
        if (!payload.format.startsWith("ambient_brain_settings")) {
            throw IllegalArgumentException("JSON file is not an Ambient Brain settings backup.")
        }
        return payload
    }

    fun importSettingsPayload(
        payload: SettingsBackupPayload,
        restoreApiKeys: Boolean = false
    ): Int {
        micSharingMode = parseMicSharingMode(payload.micSharingMode)
        geminiModel = normalizeSupportedModel(payload.geminiModel)
        fallbackGeminiModel = normalizeFallbackModel(payload.fallbackGeminiModel)
        transcriptLangMode = runCatching {
            TranscriptLangMode.valueOf(payload.transcriptLangMode)
        }.getOrDefault(TranscriptLangMode.BILINGUAL_TH_EN)
        autoDetectRounds = payload.autoDetectRounds
        autoRecognizeVoiceProfiles = payload.autoRecognizeVoiceProfiles
        autoAddGlossaryTerms = payload.autoAddGlossaryTerms
        autoMergeContinuousFiles = payload.autoMergeContinuousFiles
        autoMergeGapMinutes = payload.autoMergeGapMinutes.coerceIn(5, 60)
        defaultReviewSourceIsAudio = payload.defaultReviewSourceIsAudio
        minMeaningfulSpeechSeconds = payload.minMeaningfulSpeechSeconds.coerceIn(1, 60)
        silenceSplitSeconds = payload.silenceSplitSeconds.coerceIn(30, 300)
        roundSilenceSplitSeconds = payload.roundSilenceSplitSeconds.coerceIn(120, 900)
        retentionDays = payload.retentionDays.coerceIn(1, 365)
        if (payload.meetingVideoSaveFolder.isNotBlank()) {
            meetingVideoSaveFolder = payload.meetingVideoSaveFolder
        }
        if (payload.userDisplayName.isNotBlank()) {
            userDisplayName = payload.userDisplayName.trim()
        }
        customGlossaryTerms = payload.customGlossaryTerms
        if (payload.structuredGlossaryEntries.isNotEmpty()) {
            val mergedGlossary = getStructuredGlossaryEntries().toMutableList()
            payload.structuredGlossaryEntries.forEach { imported ->
                val cleanTerm = imported.term.trim()
                if (cleanTerm.isBlank()) return@forEach
                val idx = mergedGlossary.indexOfFirst {
                    it.id == imported.id || it.term.equals(cleanTerm, ignoreCase = true)
                }
                if (idx >= 0) {
                    val prev = mergedGlossary[idx]
                    mergedGlossary[idx] = prev.copy(
                        thaiPhonetic = imported.thaiPhonetic.trim().ifBlank { prev.thaiPhonetic },
                        category = GlossaryTermEntry.normalizeCategory(imported.category),
                        notes = imported.notes.trim().ifBlank { prev.notes },
                        isAutoAdded = prev.isAutoAdded && imported.isAutoAdded,
                        seenCount = maxOf(prev.seenCount, imported.seenCount),
                        lastSeenAtMs = maxOf(prev.lastSeenAtMs, imported.lastSeenAtMs),
                        sourceSessionId = imported.sourceSessionId ?: prev.sourceSessionId
                    )
                } else {
                    mergedGlossary.add(imported)
                }
            }
            saveStructuredGlossaryEntries(mergedGlossary)
        }

        val profilesDir = getProfilesDir()

        // Re-link My Voice multi-samples if present locally
        if (payload.myVoiceSamples.isNotEmpty()) {
            val relinkedMy = payload.myVoiceSamples.mapNotNull { s ->
                val baseName = File(s.filePath).name
                val localFile = File(profilesDir, baseName)
                if (localFile.exists() && localFile.length() >= 1024L) {
                    s.copy(filePath = localFile.absolutePath)
                } else {
                    null
                }
            }
            if (relinkedMy.isNotEmpty()) {
                val combined = (getMyVoiceSamples() + relinkedMy)
                    .distinctBy { it.filePath }
                    .sortedByDescending { it.qualityScore }
                    .take(MAX_VOICE_SAMPLES_PER_PROFILE)
                saveMyVoiceSamples(combined)
            }
        }

        // Merge speaker roster entries cleanly while re-linking local voice clip paths
        val mergedRoster = getSpeakerRoster().toMutableList()
        var rosterAddedOrUpdated = 0
        payload.speakerRoster.forEach { entry ->
            val cleanNick = entry.effectiveNickname()
            val cleanName = entry.displayName.trim().ifBlank { cleanNick }
            if (cleanName.isBlank()) return@forEach
            val cleanTitle = entry.effectiveRelatableTitle()
            val cleanSig = entry.voiceSignature.trim().ifBlank { entry.voiceHint.trim() }

            val relinkedSamples = entry.samples.mapNotNull { s ->
                val localFile = File(profilesDir, File(s.filePath).name)
                if (localFile.exists() && localFile.length() >= 1024L) {
                    s.copy(filePath = localFile.absolutePath)
                } else {
                    null
                }
            }
            val expectedLocalAudio = getSpeakerProfileAudioFile(entry.id)
            val resolvedAudioPath = relinkedSamples.firstOrNull()?.filePath
                ?: entry.audioPath?.let { File(profilesDir, File(it).name) }
                    ?.takeIf { it.exists() && it.length() > 1024L }?.absolutePath
                ?: expectedLocalAudio.takeIf { it.exists() && it.length() > 1024L }?.absolutePath

            val normalized = entry.copy(
                displayName = cleanName,
                nickname = cleanNick,
                role = entry.role.trim().ifBlank { cleanTitle },
                relatableTitle = cleanTitle,
                voiceHint = entry.voiceHint.trim().ifBlank { cleanSig },
                voiceSignature = cleanSig,
                audioPath = resolvedAudioPath,
                samples = relinkedSamples
            )
            val existingIdx = mergedRoster.indexOfFirst {
                it.id == normalized.id ||
                    it.displayName.equals(cleanName, ignoreCase = true) ||
                    it.effectiveNickname().equals(cleanNick, ignoreCase = true)
            }
            if (existingIdx >= 0) {
                val prev = mergedRoster[existingIdx]
                val combinedSamples = (prev.existingSamples() + relinkedSamples)
                    .distinctBy { it.filePath }
                    .sortedByDescending { it.qualityScore }
                    .take(MAX_VOICE_SAMPLES_PER_PROFILE)
                val best = combinedSamples.firstOrNull()
                mergedRoster[existingIdx] = normalized.copy(
                    id = prev.id,
                    samples = combinedSamples,
                    audioPath = best?.filePath ?: resolvedAudioPath ?: prev.audioPath?.takeIf { File(it).exists() },
                    isPinned = normalized.isPinned || prev.isPinned,
                    sessionsHeardCount = maxOf(prev.sessionsHeardCount, normalized.sessionsHeardCount),
                    lastHeardAtMs = maxOf(prev.lastHeardAtMs, normalized.lastHeardAtMs),
                    sampleDurationMs = best?.durationMs ?: maxOf(prev.sampleDurationMs, normalized.sampleDurationMs),
                    aliases = (prev.aliases + normalized.aliases).distinctBy { it.lowercase() }
                )
            } else {
                mergedRoster.add(normalized)
            }
            rosterAddedOrUpdated++
        }
        if (payload.speakerRoster.isNotEmpty()) {
            saveSpeakerRoster(mergedRoster)
        }

        if (restoreApiKeys && payload.includesApiKeys) {
            val importedPool = (payload.savedApiKeysPool + listOf(payload.geminiApiKey))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (importedPool.isNotEmpty()) {
                val combinedPool = (importedPool + getSavedApiKeys()).distinct()
                setSavedApiKeys(combinedPool)
                val primary = payload.geminiApiKey.trim().ifBlank { importedPool.first() }
                geminiApiKey = primary
            }
        }
        return rosterAddedOrUpdated
    }

    companion object {
        const val SETTINGS_BACKUP_FORMAT = "ambient_brain_settings_v1"
        const val MAX_VOICE_SAMPLES_PER_PROFILE = 10
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_FALLBACK_MODEL = "gemini-3.5-flash-lite"
        const val FALLBACK_MODEL_NONE = "NONE"

        /**
         * Curated active Gemini API multimodal audio models (verified Nov 2026 allowance).
         * Deprecated Dec 1, 2026 models (`gemini-2.5-flash`, `gemini-2.5-pro`) are auto-migrated.
         * Live-discovered `gemini-*` models from `GET /v1beta/models` are also selectable!
         */
        val SUPPORTED_MODELS = listOf(
            "gemini-4-hydrogen",
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash-lite",
            "gemini-4-argon",
            "gemini-4-carbon"
        )

        val FREE_TIER_MODELS = setOf(
            "gemini-4-hydrogen",
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash-lite"
        )

        private val DEPRECATED_MODEL_MIGRATIONS = mapOf(
            "gemini-2.5-flash" to "gemini-3.5-flash-lite",
            "gemini-2.5-pro" to "gemini-3.8-flash"
        )

        private val VALID_GEMINI_MODEL_REGEX = Regex("^gemini-[a-zA-Z0-9._-]{2,64}$")

        fun normalizeSupportedModel(raw: String?): String {
            val trimmed = raw?.trim().orEmpty().removePrefix("models/")
            if (trimmed.isBlank()) return DEFAULT_MODEL
            DEPRECATED_MODEL_MIGRATIONS[trimmed.lowercase()]?.let { return it }
            if (trimmed in SUPPORTED_MODELS) return trimmed
            if (VALID_GEMINI_MODEL_REGEX.matches(trimmed)) return trimmed
            return DEFAULT_MODEL
        }

        fun normalizeFallbackModel(raw: String?): String {
            val trimmed = raw?.trim().orEmpty().removePrefix("models/")
            if (trimmed.equals(FALLBACK_MODEL_NONE, ignoreCase = true)) return FALLBACK_MODEL_NONE
            if (trimmed.isBlank()) return DEFAULT_FALLBACK_MODEL
            return normalizeSupportedModel(trimmed)
        }

        fun isModelFreeTierEligible(model: String): Boolean {
            val normalized = normalizeSupportedModel(model)
            if (normalized in FREE_TIER_MODELS) return true
            val lower = normalized.lowercase()
            if (lower.contains("argon") || lower.contains("carbon") || lower.contains("ultra") || lower.contains("pro")) {
                return false
            }
            return lower.contains("flash") || lower.contains("hydrogen") || lower.contains("lite")
        }

        fun getModelBillingBadge(model: String): String {
            return if (isModelFreeTierEligible(model)) "Free-tier" else "Paid Tier"
        }

        fun getModelShortDescription(model: String): String {
            return when (normalizeSupportedModel(model)) {
                "gemini-4-hydrogen" -> "Gen-4 Multimodal • Free Tier • 1M ctx"
                "gemini-3.8-flash" -> "Balanced Flash • Free Tier"
                "gemini-3.5-flash-lite" -> "High-Quota Lite • Best Fallback"
                "gemini-3.6-flash" -> "Fast Flash • Free Tier"
                "gemini-3.1-flash-lite" -> "Lightweight Lite • Free Tier"
                "gemini-2.5-flash-lite" -> "Legacy Lite (Active) • Free Tier"
                "gemini-4-argon" -> "Gen-4 Flagship • Paid Tier"
                "gemini-4-carbon" -> "Gen-4 Reasoning • Paid Tier"
                else -> if (isModelFreeTierEligible(model)) "Discovered • Free-tier eligible" else "Discovered • Paid Tier"
            }
        }

        fun apiKeyFingerprint(key: String): String {
            val trimmed = key.trim()
            if (trimmed.isBlank()) return ""
            return runCatching {
                val digest = MessageDigest.getInstance("SHA-256").digest(trimmed.toByteArray(Charsets.UTF_8))
                digest.take(8).joinToString("") { "%02x".format(it) }
            }.getOrElse {
                "${trimmed.take(6)}_${trimmed.takeLast(4)}"
            }
        }

        private val healthLock = Any()

        @Volatile
        private var prefsSingleton: SharedPreferences? = null

        @Volatile
        private var isUsingFallbackPrefs: Boolean = false

        @Volatile
        private var lastKeystoreRetryMonoMs: Long = 0L

        @Volatile
        private var hotCacheInitialized: Boolean = false

        @Volatile
        private var cachedListeningEnabled: Boolean = true

        @Volatile
        private var cachedMicSharingMode: MicSharingMode = MicSharingMode.SMART_SHARE

        @Volatile
        private var cachedMinMeaningfulSpeechSec: Int = 3

        @Volatile
        private var cachedSilenceSplitSec: Int = 90

        @Volatile
        private var cachedRoundSilenceSplitSec: Int = 300

        private val cacheLock = Any()

        private fun parseMicSharingMode(raw: String?): MicSharingMode {
            if (raw.isNullOrBlank()) return MicSharingMode.SMART_SHARE
            return runCatching { MicSharingMode.valueOf(raw) }.getOrDefault(MicSharingMode.SMART_SHARE)
        }

        private val hotPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                KEY_LISTENING_ENABLED -> cachedListeningEnabled = sp.getBoolean(KEY_LISTENING_ENABLED, true)
                KEY_MIC_SHARING_MODE -> {
                    val mode = parseMicSharingMode(sp.getString(KEY_MIC_SHARING_MODE, null))
                    cachedMicSharingMode = mode
                    CaptureStateBus.update { it.copy(micSharingMode = mode) }
                }
                KEY_MIN_MEANINGFUL_SPEECH_SEC -> cachedMinMeaningfulSpeechSec = sp.getInt(KEY_MIN_MEANINGFUL_SPEECH_SEC, 3).coerceIn(1, 60)
                KEY_SILENCE_SPLIT_SEC -> cachedSilenceSplitSec = sp.getInt(KEY_SILENCE_SPLIT_SEC, 90).coerceIn(30, 300)
                KEY_ROUND_SILENCE_SPLIT_SEC -> cachedRoundSilenceSplitSec = sp.getInt(KEY_ROUND_SILENCE_SPLIT_SEC, 300).coerceIn(120, 900)
            }
        }

        private fun tryOpenEncryptedPrefs(appContext: Context): SharedPreferences? {
            return try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    "ambient_brain_secure_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun hydrateHotCacheLocked(sp: SharedPreferences): MicSharingMode {
            cachedListeningEnabled = sp.getBoolean(KEY_LISTENING_ENABLED, true)
            val mode = parseMicSharingMode(sp.getString(KEY_MIC_SHARING_MODE, null))
            cachedMicSharingMode = mode
            cachedMinMeaningfulSpeechSec = sp.getInt(KEY_MIN_MEANINGFUL_SPEECH_SEC, 3).coerceIn(1, 60)
            cachedSilenceSplitSec = sp.getInt(KEY_SILENCE_SPLIT_SEC, 90).coerceIn(30, 300)
            cachedRoundSilenceSplitSec = sp.getInt(KEY_ROUND_SILENCE_SPLIT_SEC, 300).coerceIn(120, 900)
            hotCacheInitialized = true
            return mode
        }

        private fun getSharedPrefsSingleton(appContext: Context): SharedPreferences {
            val current = prefsSingleton
            if (current != null && !isUsingFallbackPrefs) return current

            var recoveredMode: MicSharingMode? = null
            val resolved = synchronized(cacheLock) {
                val lockedCurrent = prefsSingleton
                if (lockedCurrent != null && !isUsingFallbackPrefs) {
                    lockedCurrent
                } else {
                    val nowMono = android.os.SystemClock.elapsedRealtime()
                    val shouldTryEncrypted = lockedCurrent == null || (nowMono - lastKeystoreRetryMonoMs >= 10_000L)
                    val encrypted = if (shouldTryEncrypted) {
                        lastKeystoreRetryMonoMs = nowMono
                        tryOpenEncryptedPrefs(appContext)
                    } else {
                        null
                    }
                    if (encrypted != null) {
                        lockedCurrent?.unregisterOnSharedPreferenceChangeListener(hotPrefListener)
                        encrypted.registerOnSharedPreferenceChangeListener(hotPrefListener)
                        prefsSingleton = encrypted
                        val wasFallback = isUsingFallbackPrefs
                        isUsingFallbackPrefs = false
                        if (wasFallback || !hotCacheInitialized) {
                            recoveredMode = hydrateHotCacheLocked(encrypted)
                        }
                        encrypted
                    } else if (lockedCurrent != null) {
                        lockedCurrent
                    } else {
                        val fallback = appContext.getSharedPreferences("ambient_brain_fallback_prefs", Context.MODE_PRIVATE)
                        fallback.registerOnSharedPreferenceChangeListener(hotPrefListener)
                        prefsSingleton = fallback
                        isUsingFallbackPrefs = true
                        fallback
                    }
                }
            }
            recoveredMode?.let { mode ->
                CaptureStateBus.update { it.copy(micSharingMode = mode) }
            }
            return resolved
        }

        private fun ensureHotCacheInitialized(appContext: Context) {
            if (hotCacheInitialized && !isUsingFallbackPrefs) return
            var initializedMode: MicSharingMode? = null
            val sp = getSharedPrefsSingleton(appContext)
            synchronized(cacheLock) {
                if (!hotCacheInitialized) {
                    initializedMode = hydrateHotCacheLocked(sp)
                }
            }
            initializedMode?.let { mode ->
                CaptureStateBus.update { it.copy(micSharingMode = mode) }
            }
        }

        private const val KEY_LISTENING_ENABLED = "is_listening_enabled"
        private const val KEY_MIC_SHARING_MODE = "mic_sharing_mode"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_SAVED_API_KEYS = "saved_api_keys_pool"
        private const val KEY_API_KEY_HEALTH_JSON = "api_key_health_json"
        private const val KEY_MODEL = "gemini_model"
        private const val KEY_FALLBACK_MODEL = "gemini_fallback_model"
        private const val KEY_MODEL_ALLOWANCE_CACHE = "model_allowance_cache_json"
        private const val KEY_TRANSCRIPT_LANG_MODE = "transcript_lang_mode"
        private const val KEY_AUTO_DETECT_ROUNDS = "auto_detect_rounds"
        private const val KEY_AUTO_RECOGNIZE_VOICE_PROFILES = "auto_recognize_voice_profiles"
        private const val KEY_MIN_MEANINGFUL_SPEECH_SEC = "min_meaningful_speech_sec"
        private const val KEY_SILENCE_SPLIT_SEC = "silence_split_sec"
        private const val KEY_ROUND_SILENCE_SPLIT_SEC = "round_silence_split_sec"
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_USER_DISPLAY_NAME = "user_display_name"
        private const val KEY_CUSTOM_GLOSSARY = "custom_glossary"
        private const val KEY_AUTO_ADD_GLOSSARY = "auto_add_glossary_terms"
        private const val KEY_STRUCTURED_GLOSSARY = "structured_glossary_json"
        private const val KEY_SPEAKER_ROSTER = "speaker_roster_json"
        private const val KEY_MY_VOICE_SAMPLES = "my_voice_samples_json"
        private const val KEY_AUTO_MERGE_CONTINUOUS = "auto_merge_continuous_files"
        private const val KEY_AUTO_MERGE_GAP_MIN = "auto_merge_gap_minutes"
        private const val KEY_DEFAULT_REVIEW_SOURCE_IS_AUDIO = "default_review_source_is_audio"
        private const val KEY_MEETING_VIDEO_SAVE_FOLDER = "meeting_video_save_folder"
        private const val KEY_MEETING_VIDEO_CUSTOM_TREE_URI = "meeting_video_custom_tree_uri"
        private const val KEY_MEETING_VIDEO_CUSTOM_FOLDER_LABEL = "meeting_video_custom_folder_label"
        private const val KEY_PENDING_APPOPS_MIC_RESTORE_PKG = "pending_appops_mic_restore_pkg"
        private const val KEY_GEMINI_PAUSED = "gemini_usage_paused"
        private const val KEY_GEMINI_PAUSED_UNTIL_MS = "gemini_usage_paused_until_ms"
        private const val KEY_USE_DEFAULT_RATE_LIMIT_REF = "use_default_rate_limit_reference"
        private const val KEY_LIVE_RATE_LIMIT_SNAPSHOT_JSON = "live_rate_limit_snapshot_json"
        private const val KEY_LAST_RATE_LIMIT_RESET_PT_DATE = "last_rate_limit_reset_pt_date"

        const val DEFAULT_MEETING_VIDEO_FOLDER = "Download/AmbientBrain"

        fun normalizePublicVideoFolder(raw: String?): String {
            val cleaned = raw
                ?.replace('\\', '/')
                ?.trim()
                ?.trim('/')
                ?.replace(Regex("/+"), "/")
                ?.replace(Regex("\\.\\."), "")
                .orEmpty()
            if (cleaned.isBlank()) return DEFAULT_MEETING_VIDEO_FOLDER
            val lower = cleaned.lowercase()
            return when {
                lower == "download" || lower == "downloads" -> "Download"
                lower.startsWith("download/") -> "Download/" + cleaned.substringAfter("/")
                lower.startsWith("downloads/") -> "Download/" + cleaned.substringAfter("/")
                lower == "movies" -> "Movies"
                lower.startsWith("movies/") -> "Movies/" + cleaned.substringAfter("/")
                lower == "dcim" -> "DCIM"
                lower.startsWith("dcim/") -> "DCIM/" + cleaned.substringAfter("/")
                else -> "Download/$cleaned"
            }
        }
    }
}
