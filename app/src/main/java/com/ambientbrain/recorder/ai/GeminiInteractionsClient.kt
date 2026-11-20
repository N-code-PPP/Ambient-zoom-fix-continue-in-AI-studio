package com.ambientbrain.recorder.ai

import android.util.Base64
import android.util.Log
import com.ambientbrain.recorder.data.GeminiRateLimitProfile
import com.ambientbrain.recorder.data.GeminiRateLimitSnapshot
import com.ambientbrain.recorder.data.ModelAllowanceEntry
import com.ambientbrain.recorder.data.PreferencesRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class AudioPartInput(
    val label: String,
    val file: File,
    val offsetMs: Long = 0L
)

data class ReferenceVoiceInput(
    val speakerLabel: String,
    val roleHint: String = "",
    val file: File,
    val profileId: String = "",
    val voiceSignature: String = "",
    val sampleIndex: Int = 1,
    val sampleTotal: Int = 1,
    val qualityScore: Float = 0f
)

data class GeminiCallResponse(
    val analysis: GeminiSessionAnalysisResult,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int = 0,
    val latencyMs: Long,
    val actualModel: String = ""
)

data class VoiceProfileAuditAudioClip(
    val headerPromptText: String,
    val file: File
)

data class GeminiVoiceProfileAuditCallResponse(
    val result: GeminiVoiceProfileAuditResultDto,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int = 0,
    val latencyMs: Long,
    val actualModel: String = ""
)

data class ApiFailureClassification(
    val category: String,
    val shouldFailoverKey: Boolean,
    val isNetworkTransient: Boolean,
    val remediationHint: String,
    val isModelUnavailable: Boolean = false,
    val isQuotaOrBilling: Boolean = false
)

/**
 * Direct Google AI Studio Gemini client powered strictly by the user's API Key Pool:
 *  - Supports Gemini Interactions API + automatic fallback to `/v1beta/models/{model}:generateContent`
 *  - Dynamically resolves available models & probes real live model allowance via `GET /v1beta/models`
 *  - Automatically uploads oversized recordings via the Gemini Files API and cleans up remote files
 *  - Supports multi-sample reference voice attachments (up to 65,536 max output tokens)
 */
class GeminiInteractionsClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private fun buildCandidateModels(preferredModel: String, strictModelOnly: Boolean = false): List<String> {
        val normalized = PreferencesRepo.normalizeSupportedModel(preferredModel)
        if (strictModelOnly) return listOf(normalized)
        return buildList {
            add(normalized)
            add("gemini-3.8-flash")
            add("gemini-3.5-flash-lite")
        }.distinct()
    }

    /**
     * Resolves candidate models for direct REST API calls (`generativelanguage.googleapis.com`).
     * When [strictModelOnly] is true, strictly attempts [preferredModel] so the worker's
     * Primary -> Fallback model policy controls model failover deterministically.
     */
    private fun resolveRestCandidateModels(
        apiKey: String,
        preferredModel: String,
        strictModelOnly: Boolean = false
    ): List<String> {
        val base = buildCandidateModels(preferredModel, strictModelOnly = strictModelOnly)
        if (strictModelOnly) return base
        val discovered = discoverRestModelsFromServer(apiKey, preferredModel)
        return (base + discovered).distinct()
    }

    private fun isEligibleMultimodalModelName(rawName: String): Boolean {
        val lower = rawName.lowercase().trim()
        if (!lower.startsWith("gemini-")) return false
        // Exclude deprecated Dec 1, 2026 models (non-lite 2.5-flash and 2.5-pro)
        if (lower == "gemini-2.5-flash" || lower == "gemini-2.5-pro") return false
        if (lower.startsWith("gemini-2.5-flash-") && !lower.contains("lite")) return false
        if (lower.startsWith("gemini-2.5-pro-")) return false

        // Skip non-audio/non-JSON-chat models, realtime WebSocket-only models, and legacy 1.x/2.0 models
        val excludedTokens = listOf(
            "embedding", "aqa", "imagen", "veo", "tts", "image", "vision",
            "thinking", "computer-use", "robotics", "live", "transcribe",
            "omni", "native-audio", "1.0", "1.5", "2.0"
        )
        return excludedTokens.none { lower.contains(it) }
    }

    private fun discoverRestModelsFromServer(apiKey: String, preferredModel: String): List<String> {
        val cacheKey = "${apiKey.take(10)}_${apiKey.takeLast(4)}_$preferredModel"
        discoveredRestModelsCache[cacheKey]?.let { cached ->
            if (cached.isNotEmpty()) return cached
        }

        return try {
            val req = Request.Builder()
                .url("$BASE_URL/v1beta/models?key=${apiKey.trim()}")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val bodyStr = resp.body?.string().orEmpty()
                val root = json.parseToJsonElement(bodyStr) as? JsonObject ?: return emptyList()
                val modelsArr = root["models"] as? JsonArray ?: return emptyList()

                val wantsFlagship = preferredModel.contains("argon", ignoreCase = true) ||
                    preferredModel.contains("carbon", ignoreCase = true) ||
                    preferredModel.contains("pro", ignoreCase = true)
                val wantsHydrogen = preferredModel.contains("hydrogen", ignoreCase = true)
                val wantsLite = preferredModel.contains("lite", ignoreCase = true)

                val candidates = mutableListOf<Pair<String, Int>>()
                for (el in modelsArr) {
                    val obj = el as? JsonObject ?: continue
                    val rawName = obj["name"]?.jsonPrimitive?.content?.removePrefix("models/")?.trim().orEmpty()
                    if (rawName.isBlank() || !isEligibleMultimodalModelName(rawName)) continue

                    val methods = (obj["supportedGenerationMethods"] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.content }
                        .orEmpty()
                    if ("generateContent" !in methods) continue

                    val lower = rawName.lowercase()
                    val versionScore = Regex("""gemini-(\d+)(?:\.(\d+))?""").find(lower)?.let { m ->
                        val major = m.groupValues[1].toIntOrNull() ?: 0
                        val minor = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                        major * 100 + minor * 10
                    } ?: 0

                    val tierBonus = when {
                        wantsHydrogen && lower.contains("hydrogen") -> 50
                        wantsLite && lower.contains("flash-lite") -> 45
                        !wantsFlagship && (lower.contains("hydrogen") || (lower.contains("flash") && !lower.contains("lite"))) -> 40
                        wantsFlagship && (lower.contains("argon") || lower.contains("carbon") || lower.contains("pro")) -> 45
                        lower.contains("flash") -> 30
                        else -> 15
                    }
                    val stabilityBonus = when {
                        !lower.contains("exp") && !lower.contains("preview") && !Regex("""-\d{3}$""").containsMatchIn(lower) -> 15
                        lower.contains("preview") -> 5
                        else -> 0
                    }

                    candidates.add(rawName to (versionScore + tierBonus + stabilityBonus))
                }

                val sorted = candidates
                    .sortedByDescending { it.second }
                    .map { it.first }
                    .distinct()
                    .take(6)

                if (sorted.isNotEmpty()) {
                    discoveredRestModelsCache[cacheKey] = sorted
                }
                sorted
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic REST model discovery skipped: ${e.message}")
            emptyList()
        }
    }

    private fun resolveAudioMimeType(file: File): String {
        val lower = file.name.lowercase()
        return when {
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".m4a") || lower.endsWith(".mp4") -> "audio/mp4"
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".ogg") -> "audio/ogg"
            else -> "audio/aac"
        }
    }

    private fun formatReferenceVoicePrompt(idx: Int, ref: ReferenceVoiceInput): String {
        val idTag = if (ref.profileId.isNotBlank()) " [profile_id=${ref.profileId}]" else ""
        val sampleTag = if (ref.sampleTotal > 1) {
            val rank = if (ref.sampleIndex == 1) "Best" else "Alt"
            " [Sample ${ref.sampleIndex}/${ref.sampleTotal} • $rank]"
        } else ""
        val roleText = if (ref.roleHint.isNotBlank()) " — Title: ${ref.roleHint}" else ""
        val sigText = if (ref.voiceSignature.isNotBlank()) " — Voice: ${ref.voiceSignature}" else ""
        return "Reference Audio ${idx + 1}$idTag$sampleTag: Voice sample of '${ref.speakerLabel}'$roleText$sigText."
    }

    /**
     * Text-only AI Review & Continuous Merge synthesis using a key from the user's API Key Pool.
     */
    suspend fun analyzeExtractedInfo(
        apiKey: String,
        model: String,
        systemInstruction: String,
        sessionContextText: String,
        extractedInfoDocument: String,
        strictModelOnly: Boolean = false
    ): GeminiCallResponse = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API Key is empty. Please add a key in Settings." }
        require(extractedInfoDocument.isNotBlank()) { "Extracted info document is empty." }

        val startMs = System.currentTimeMillis()
        val candidateModels = resolveRestCandidateModels(apiKey, model, strictModelOnly = strictModelOnly)
        var lastError: Exception? = null

        for (m in candidateModels) {
            var abortKey = false
            for (useSchema in listOf(true, false)) {
                try {
                    val partsArray = buildJsonArray {
                        add(buildJsonObject { put("text", sessionContextText) })
                        add(buildJsonObject { put("text", extractedInfoDocument) })
                        if (!useSchema) {
                            add(buildJsonObject {
                                put(
                                    "text",
                                    "Return ONLY a single valid JSON object matching keys: has_meaningful_content, is_clinical_round, context, title, summary, key_points, speakers, transcript, patients, items, ai_review_report."
                                )
                            })
                        }
                    }
                    val genPayload = buildJsonObject {
                        putJsonObject("system_instruction") {
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", systemInstruction) })
                            }
                        }
                        putJsonArray("contents") {
                            add(buildJsonObject {
                                put("role", "user")
                                put("parts", partsArray)
                            })
                        }
                        putJsonObject("generationConfig") {
                            put("response_mime_type", "application/json")
                            if (useSchema) {
                                put("response_schema", Prompts.buildGenerateContentSchema())
                            }
                            put("max_output_tokens", MAX_OUTPUT_TOKENS)
                        }
                    }

                    val req = Request.Builder()
                        .url("$BASE_URL/v1beta/models/$m:generateContent?key=$apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(genPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    var shouldTryNextModel = false
                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            lastError = IllegalStateException("HTTP ${resp.code} on $m: ${bodyStr.take(300)}")
                            if (resp.code in setOf(401, 403, 429)) {
                                abortKey = true
                                shouldTryNextModel = true
                            } else if (resp.code != 400 || !useSchema) {
                                shouldTryNextModel = true
                            }
                            return@use
                        }

                        val root = json.parseToJsonElement(bodyStr)
                        val outputJsonText = extractOutputTextFromGenerateContent(root)
                            ?: throw IllegalStateException("No text output found in generateContent response: ${bodyStr.take(300)}")

                        val cleanedJson = cleanJsonText(outputJsonText)
                        val parsedAnalysis = json.decodeFromString<GeminiSessionAnalysisResult>(cleanedJson)
                        val rootObj = root as? JsonObject
                        val usageObj = rootObj?.get("usageMetadata")?.jsonObject
                        val inTokens = usageObj?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
                        val outTokens = usageObj?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0
                        val cachedTokens = usageObj?.get("cachedContentTokenCount")?.jsonPrimitive?.intOrNull ?: 0
                        val latency = System.currentTimeMillis() - startMs

                        return@withContext GeminiCallResponse(
                            analysis = parsedAnalysis,
                            inputTokens = inTokens,
                            outputTokens = outTokens,
                            cachedTokens = cachedTokens,
                            latencyMs = latency,
                            actualModel = m
                        )
                    }
                    if (shouldTryNextModel) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (classifyApiFailure(e).shouldFailoverKey) {
                        abortKey = true
                    }
                    break
                }
            }
            if (abortKey) break
        }

        throw lastError ?: IllegalStateException("Extracted-Info Review failed across all candidate models")
    }

    /**
     * Single-file convenience wrapper using a key from the user's API Key Pool.
     */
    suspend fun analyzeSessionAudio(
        apiKey: String,
        model: String,
        systemInstruction: String,
        sessionContextText: String = "",
        sessionAudioFile: File,
        voiceProfileFile: File?
    ): GeminiCallResponse {
        val refs = if (voiceProfileFile != null && voiceProfileFile.exists() && voiceProfileFile.length() in 1024..2_000_000) {
            listOf(
                ReferenceVoiceInput(
                    speakerLabel = "User (Me)",
                    roleHint = "Device Owner / Primary User",
                    file = voiceProfileFile,
                    profileId = "me"
                )
            )
        } else {
            emptyList()
        }
        return analyzeMultiAudio(
            apiKey = apiKey,
            model = model,
            systemInstruction = systemInstruction,
            sessionContextText = sessionContextText,
            audioParts = listOf(AudioPartInput("Main Session Audio", sessionAudioFile)),
            referenceVoices = refs
        )
    }

    /**
     * Multi-audio & multi-voice analysis using a key from the user's API Key Pool.
     */
    suspend fun analyzeMultiAudio(
        apiKey: String,
        model: String,
        systemInstruction: String,
        sessionContextText: String = "",
        audioParts: List<AudioPartInput>,
        referenceVoices: List<ReferenceVoiceInput> = emptyList(),
        strictModelOnly: Boolean = false
    ): GeminiCallResponse = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API Key is empty. Please add a key in Settings." }
        require(audioParts.isNotEmpty()) { "At least one audio file is required." }
        audioParts.forEach { part ->
            require(part.file.exists()) { "Session audio file does not exist: ${part.file.absolutePath}" }
        }

        val startMs = System.currentTimeMillis()
        val uploadedRemoteFiles = mutableListOf<Pair<String, String>>() // name to uri
        val targetModel = PreferencesRepo.normalizeSupportedModel(model)

        try {
            val partRemoteUris = mutableMapOf<Int, String>()
            val inputParts = buildJsonArray {
                // 1. Reference voice clips
                referenceVoices.forEachIndexed { idx, ref ->
                    if (ref.file.exists() && ref.file.length() in 1024..2_500_000) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", formatReferenceVoicePrompt(idx, ref))
                        })
                        val voiceB64 = Base64.encodeToString(ref.file.readBytes(), Base64.NO_WRAP)
                        add(buildJsonObject {
                            put("type", "audio")
                            put("data", voiceB64)
                            put("mime_type", resolveAudioMimeType(ref.file))
                        })
                    }
                }

                // 2. Dynamic session context
                add(buildJsonObject {
                    put("type", "text")
                    put(
                        "text",
                        sessionContextText.ifBlank {
                            "Gather and reorganize all important information from the audio in English and identify all speakers."
                        }
                    )
                })

                // 3. Audio segment(s)
                audioParts.forEachIndexed { idx, part ->
                    if (audioParts.size > 1) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Audio Segment ${idx + 1}: ${part.label}")
                        })
                    }
                    val partMime = resolveAudioMimeType(part.file)
                    if (part.file.length() <= INLINE_MAX_BYTES && audioParts.sumOf { it.file.length() } <= INLINE_MAX_BYTES) {
                        val audioB64 = Base64.encodeToString(part.file.readBytes(), Base64.NO_WRAP)
                        add(buildJsonObject {
                            put("type", "audio")
                            put("data", audioB64)
                            put("mime_type", partMime)
                        })
                    } else {
                        val uploaded = uploadViaFilesApi(apiKey, part.file, partMime)
                        uploadedRemoteFiles.add(uploaded)
                        partRemoteUris[idx] = uploaded.second
                        add(buildJsonObject {
                            put("type", "audio")
                            put("uri", uploaded.second)
                            put("mime_type", partMime)
                        })
                    }
                }
            }

            val requestPayload = buildJsonObject {
                put("model", targetModel)
                put("system_instruction", systemInstruction)
                put("store", false)
                put("input", inputParts)
                putJsonObject("generation_config") {
                    put("max_output_tokens", MAX_OUTPUT_TOKENS)
                }
                putJsonObject("response_format") {
                    put("type", "text")
                    put("mime_type", "application/json")
                    put("schema", Prompts.buildResponseJsonSchema())
                }
            }

            val req = Request.Builder()
                .url("$BASE_URL/v1beta/interactions")
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Api-Revision", API_REVISION)
                .addHeader("Content-Type", "application/json")
                .post(requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val interactionResult = try {
                httpClient.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val root = json.parseToJsonElement(bodyStr).jsonObject
                        val outputJsonText = extractOutputTextFromInteraction(root)
                            ?: throw IllegalStateException("No text output found in Interactions response: ${bodyStr.take(400)}")

                        val cleanedJson = cleanJsonText(outputJsonText)
                        val parsedAnalysis = json.decodeFromString<GeminiSessionAnalysisResult>(cleanedJson)
                        val usageObj = root["usage"]?.jsonObject
                        val inTokens = usageObj?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                        val outTokens = usageObj?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
                        val cachedTokens = usageObj?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                            ?: usageObj?.get("cached_content_token_count")?.jsonPrimitive?.intOrNull
                            ?: 0
                        val latency = System.currentTimeMillis() - startMs

                        GeminiCallResponse(
                            analysis = parsedAnalysis,
                            inputTokens = inTokens,
                            outputTokens = outTokens,
                            cachedTokens = cachedTokens,
                            latencyMs = latency,
                            actualModel = targetModel
                        )
                    } else if (resp.code in setOf(400, 404, 422, 501)) {
                        Log.w(TAG, "Interactions API HTTP ${resp.code}, falling back to generateContent: $bodyStr")
                        null
                    } else {
                        throw IllegalStateException("Gemini Interactions API HTTP ${resp.code}: ${bodyStr.take(500)}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is IllegalStateException && (e.message?.contains("400") == true || e.message?.contains("404") == true || e.message?.contains("422") == true || e.message?.contains("501") == true)) {
                    null
                } else {
                    throw e
                }
            }

            if (interactionResult != null) {
                return@withContext interactionResult
            }

            // Standard Google AI Studio generateContent endpoint (with dynamic or strict model resolution)
            executeGenerateContentAnalysis(
                apiKey = apiKey,
                model = targetModel,
                systemInstruction = systemInstruction,
                sessionContextText = sessionContextText,
                audioParts = audioParts,
                referenceVoices = referenceVoices,
                partRemoteUris = partRemoteUris,
                startMs = startMs,
                strictModelOnly = strictModelOnly
            )
        } finally {
            uploadedRemoteFiles.forEach { (remoteName, _) ->
                runCatching { deleteRemoteFile(apiKey, remoteName) }
            }
        }
    }

    private fun executeGenerateContentAnalysis(
        apiKey: String,
        model: String,
        systemInstruction: String,
        sessionContextText: String,
        audioParts: List<AudioPartInput>,
        referenceVoices: List<ReferenceVoiceInput>,
        partRemoteUris: Map<Int, String>,
        startMs: Long,
        strictModelOnly: Boolean = false
    ): GeminiCallResponse {
        val candidateModels = resolveRestCandidateModels(apiKey, model, strictModelOnly = strictModelOnly)
        var lastError: Exception? = null

        for (m in candidateModels) {
            var abortKey = false
            for (useSchema in listOf(true, false)) {
                try {
                    val partsArray = buildJsonArray {
                        referenceVoices.forEachIndexed { idx, ref ->
                            if (ref.file.exists() && ref.file.length() in 1024..2_500_000) {
                                add(buildJsonObject {
                                    put("text", formatReferenceVoicePrompt(idx, ref))
                                })
                                val voiceB64 = Base64.encodeToString(ref.file.readBytes(), Base64.NO_WRAP)
                                add(buildJsonObject {
                                    putJsonObject("inline_data") {
                                        put("mime_type", resolveAudioMimeType(ref.file))
                                        put("data", voiceB64)
                                    }
                                })
                            }
                        }

                        add(buildJsonObject {
                            put(
                                "text",
                                sessionContextText.ifBlank {
                                    "Gather and reorganize all important information from the audio in English and identify all speakers."
                                }
                            )
                        })

                        audioParts.forEachIndexed { idx, part ->
                            if (audioParts.size > 1) {
                                add(buildJsonObject {
                                    put("text", "Audio Segment ${idx + 1}: ${part.label}")
                                })
                            }
                            val partMime = resolveAudioMimeType(part.file)
                            val remoteUri = partRemoteUris[idx]
                            if (remoteUri != null) {
                                add(buildJsonObject {
                                    putJsonObject("file_data") {
                                        put("mime_type", partMime)
                                        put("file_uri", remoteUri)
                                    }
                                })
                            } else {
                                val audioB64 = Base64.encodeToString(part.file.readBytes(), Base64.NO_WRAP)
                                add(buildJsonObject {
                                    putJsonObject("inline_data") {
                                        put("mime_type", partMime)
                                        put("data", audioB64)
                                    }
                                })
                            }
                        }

                        if (!useSchema) {
                            add(buildJsonObject {
                                put(
                                    "text",
                                    "Return ONLY a single valid JSON object matching keys: has_meaningful_content, is_clinical_round, context, title, summary, key_points, speakers, transcript, patients, items, ai_review_report."
                                )
                            })
                        }
                    }

                    val genPayload = buildJsonObject {
                        putJsonObject("system_instruction") {
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", systemInstruction) })
                            }
                        }
                        putJsonArray("contents") {
                            add(buildJsonObject {
                                put("role", "user")
                                put("parts", partsArray)
                            })
                        }
                        putJsonObject("generationConfig") {
                            put("response_mime_type", "application/json")
                            if (useSchema) {
                                put("response_schema", Prompts.buildGenerateContentSchema())
                            }
                            put("max_output_tokens", MAX_OUTPUT_TOKENS)
                        }
                    }

                    val req = Request.Builder()
                        .url("$BASE_URL/v1beta/models/$m:generateContent?key=$apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(genPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    var shouldTryNextModel = false
                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            lastError = IllegalStateException("HTTP ${resp.code} on $m: ${bodyStr.take(300)}")
                            if (resp.code in setOf(401, 403, 429)) {
                                abortKey = true
                                shouldTryNextModel = true
                            } else if (resp.code != 400 || !useSchema) {
                                shouldTryNextModel = true
                            }
                            return@use
                        }

                        val root = json.parseToJsonElement(bodyStr)
                        val outputJsonText = extractOutputTextFromGenerateContent(root)
                            ?: throw IllegalStateException("No text output found in generateContent response: ${bodyStr.take(300)}")

                        val cleanedJson = cleanJsonText(outputJsonText)
                        val parsedAnalysis = json.decodeFromString<GeminiSessionAnalysisResult>(cleanedJson)
                        val rootObj = root as? JsonObject
                        val usageObj = rootObj?.get("usageMetadata")?.jsonObject
                        val inTokens = usageObj?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
                        val outTokens = usageObj?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0
                        val cachedTokens = usageObj?.get("cachedContentTokenCount")?.jsonPrimitive?.intOrNull ?: 0
                        val latency = System.currentTimeMillis() - startMs

                        return GeminiCallResponse(
                            analysis = parsedAnalysis,
                            inputTokens = inTokens,
                            outputTokens = outTokens,
                            cachedTokens = cachedTokens,
                            latencyMs = latency,
                            actualModel = m
                        )
                    }
                    if (shouldTryNextModel) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (classifyApiFailure(e).shouldFailoverKey) {
                        abortKey = true
                    }
                    break
                }
            }
            if (abortKey) break
        }

        throw lastError ?: IllegalStateException("generateContent failed for all candidate models")
    }

    /**
     * Sends top-pick voice sample audio clips + profile metadata & spoken context quotes to Gemini
     * to analyze, audit speaker purity (preventing combining different speakers), recheck Name/Title/Description
     * suitability, and create Voice Profiles for new top-pick clips.
     */
    suspend fun auditAndCreateVoiceProfiles(
        apiKey: String,
        model: String,
        systemInstruction: String,
        auditPromptText: String,
        topPickAudioClips: List<VoiceProfileAuditAudioClip>,
        strictModelOnly: Boolean = false
    ): GeminiVoiceProfileAuditCallResponse = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API Key is empty. Please add a key in Settings." }

        val startMs = System.currentTimeMillis()
        val targetModel = PreferencesRepo.normalizeSupportedModel(model)
        val validClips = topPickAudioClips.filter { it.file.exists() && it.file.length() in 1024L..2_500_000L }

        // 1. Try Interactions API
        val interactionResult = try {
            val inputParts = buildJsonArray {
                validClips.forEach { clip ->
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", clip.headerPromptText)
                    })
                    val b64 = Base64.encodeToString(clip.file.readBytes(), Base64.NO_WRAP)
                    add(buildJsonObject {
                        put("type", "audio")
                        put("data", b64)
                        put("mime_type", resolveAudioMimeType(clip.file))
                    })
                }
                add(buildJsonObject {
                    put("type", "text")
                    put("text", auditPromptText)
                })
            }

            val requestPayload = buildJsonObject {
                put("model", targetModel)
                put("system_instruction", systemInstruction)
                put("store", false)
                put("input", inputParts)
                putJsonObject("generation_config") {
                    put("max_output_tokens", MAX_OUTPUT_TOKENS)
                }
                putJsonObject("response_format") {
                    put("type", "text")
                    put("mime_type", "application/json")
                    put("schema", Prompts.buildVoiceProfileAuditJsonSchema())
                }
            }

            val req = Request.Builder()
                .url("$BASE_URL/v1beta/interactions")
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Api-Revision", API_REVISION)
                .addHeader("Content-Type", "application/json")
                .post(requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val root = json.parseToJsonElement(bodyStr).jsonObject
                    val outputJsonText = extractOutputTextFromInteraction(root)
                        ?: throw IllegalStateException("No text output found in Interactions response: ${bodyStr.take(300)}")
                    val cleanedJson = cleanJsonText(outputJsonText)
                    val parsed = json.decodeFromString<GeminiVoiceProfileAuditResultDto>(cleanedJson)
                    val usageObj = root["usage"]?.jsonObject
                    val inTokens = usageObj?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                    val outTokens = usageObj?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
                    val cachedTokens = usageObj?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                        ?: usageObj?.get("cached_content_token_count")?.jsonPrimitive?.intOrNull
                        ?: 0
                    GeminiVoiceProfileAuditCallResponse(
                        result = parsed,
                        inputTokens = inTokens,
                        outputTokens = outTokens,
                        cachedTokens = cachedTokens,
                        latencyMs = System.currentTimeMillis() - startMs,
                        actualModel = targetModel
                    )
                } else if (resp.code in setOf(400, 404, 422, 501)) {
                    null
                } else {
                    throw IllegalStateException("Gemini Interactions API HTTP ${resp.code}: ${bodyStr.take(400)}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is IllegalStateException && (e.message?.contains("400") == true || e.message?.contains("404") == true || e.message?.contains("422") == true || e.message?.contains("501") == true)) {
                null
            } else {
                throw e
            }
        }

        if (interactionResult != null) {
            return@withContext interactionResult
        }

        // 2. Fallback to standard generateContent endpoint
        val candidateModels = resolveRestCandidateModels(apiKey, targetModel, strictModelOnly = strictModelOnly)
        var lastError: Exception? = null

        for (m in candidateModels) {
            var abortKey = false
            for (useSchema in listOf(true, false)) {
                try {
                    val partsArray = buildJsonArray {
                        validClips.forEach { clip ->
                            add(buildJsonObject {
                                put("text", clip.headerPromptText)
                            })
                            val b64 = Base64.encodeToString(clip.file.readBytes(), Base64.NO_WRAP)
                            add(buildJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", resolveAudioMimeType(clip.file))
                                    put("data", b64)
                                }
                            })
                        }
                        add(buildJsonObject {
                            put("text", auditPromptText)
                        })
                        if (!useSchema) {
                            add(buildJsonObject {
                                put(
                                    "text",
                                    "Return ONLY a single valid JSON object matching keys: audited_profiles, created_profiles, overall_audit_summary."
                                )
                            })
                        }
                    }

                    val genPayload = buildJsonObject {
                        putJsonObject("system_instruction") {
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", systemInstruction) })
                            }
                        }
                        putJsonArray("contents") {
                            add(buildJsonObject {
                                put("role", "user")
                                put("parts", partsArray)
                            })
                        }
                        putJsonObject("generationConfig") {
                            put("response_mime_type", "application/json")
                            if (useSchema) {
                                put("response_schema", Prompts.buildVoiceProfileAuditGenerateContentSchema())
                            }
                            put("max_output_tokens", MAX_OUTPUT_TOKENS)
                        }
                    }

                    val req = Request.Builder()
                        .url("$BASE_URL/v1beta/models/$m:generateContent?key=$apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(genPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    var shouldTryNextModel = false
                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            lastError = IllegalStateException("HTTP ${resp.code} on $m: ${bodyStr.take(300)}")
                            if (resp.code in setOf(401, 403, 429)) {
                                abortKey = true
                                shouldTryNextModel = true
                            } else if (resp.code != 400 || !useSchema) {
                                shouldTryNextModel = true
                            }
                            return@use
                        }

                        val root = json.parseToJsonElement(bodyStr)
                        val outputJsonText = extractOutputTextFromGenerateContent(root)
                            ?: throw IllegalStateException("No text output found in generateContent response: ${bodyStr.take(300)}")
                        val cleanedJson = cleanJsonText(outputJsonText)
                        val parsed = json.decodeFromString<GeminiVoiceProfileAuditResultDto>(cleanedJson)
                        val rootObj = root as? JsonObject
                        val usageObj = rootObj?.get("usageMetadata")?.jsonObject
                        val inTokens = usageObj?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
                        val outTokens = usageObj?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0
                        val cachedTokens = usageObj?.get("cachedContentTokenCount")?.jsonPrimitive?.intOrNull ?: 0

                        return@withContext GeminiVoiceProfileAuditCallResponse(
                            result = parsed,
                            inputTokens = inTokens,
                            outputTokens = outTokens,
                            cachedTokens = cachedTokens,
                            latencyMs = System.currentTimeMillis() - startMs,
                            actualModel = m
                        )
                    }
                    if (shouldTryNextModel) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (classifyApiFailure(e).shouldFailoverKey) {
                        abortKey = true
                    }
                    break
                }
            }
            if (abortKey) break
        }

        throw lastError ?: IllegalStateException("Voice Profile Audit failed across all candidate models")
    }

    suspend fun testApiKey(apiKey: String, model: String = PreferencesRepo.DEFAULT_MODEL): Result<String> =
        withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            if (key.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Gemini API Key is empty."))
            }

            val normalizedModel = PreferencesRepo.normalizeSupportedModel(model)
            var lastError: Exception? = null

            // 1. Try Interactions API with selected model
            try {
                val payload = buildJsonObject {
                    put("model", normalizedModel)
                    put("store", false)
                    put("input", "Reply with OK if you are ready for Thai + English neurology round transcription.")
                }
                val req = Request.Builder()
                    .url("$BASE_URL/v1beta/interactions")
                    .addHeader("x-goog-api-key", key)
                    .addHeader("Api-Revision", API_REVISION)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val element = json.parseToJsonElement(bodyStr)
                        val text = (element as? JsonObject)?.let { extractOutputTextFromInteraction(it) } ?: "Connected"
                        return@withContext Result.success("Connected ($normalizedModel • Interactions API): $text")
                    } else {
                        lastError = IllegalStateException("HTTP ${resp.code}: ${bodyStr.take(200)}")
                        if (resp.code in setOf(401, 403)) {
                            return@withContext Result.failure(lastError!!)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }

            // 2. Fallback to standard generateContent across configured + dynamically discovered server models
            val candidateModels = resolveRestCandidateModels(key, normalizedModel)

            for ((idx, m) in candidateModels.withIndex()) {
                try {
                    val genPayload = buildJsonObject {
                        putJsonArray("contents") {
                            add(buildJsonObject {
                                putJsonArray("parts") {
                                    add(buildJsonObject {
                                        put("text", "Reply with OK if you are ready for Thai + English neurology round transcription.")
                                    })
                                }
                            })
                        }
                    }
                    val req = Request.Builder()
                        .url("$BASE_URL/v1beta/models/$m:generateContent?key=$key")
                        .addHeader("Content-Type", "application/json")
                        .post(genPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val element = json.parseToJsonElement(bodyStr)
                            val candText = extractOutputTextFromGenerateContent(element) ?: "OK"
                            val prefix = if (idx > 0 && m != normalizedModel) {
                                "Connected ($m • auto-resolved for '$normalizedModel')"
                            } else {
                                "Connected ($m)"
                            }
                            return@withContext Result.success("$prefix: $candText")
                        } else {
                            val errMsg = "HTTP ${resp.code} on $m: ${bodyStr.take(200)}"
                            lastError = IllegalStateException(errMsg)
                            if (resp.code in setOf(401, 403, 429)) {
                                return@withContext Result.failure(lastError!!)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                }
            }

            Result.failure(lastError ?: IllegalStateException("Failed to connect via Google AI Studio API"))
        }

    /**
     * Queries `GET /v1beta/models` using the user's saved API key pool and optionally sends a
     * minimal 4-token `generateContent` allowance probe per model so Settings can display real
     * live model availability (`AVAILABLE`, `QUOTA_EXCEEDED`, `PAID_ONLY`, `UNAVAILABLE`, `LISTED`)
     * for both curated and live-discovered Gemini multimodal models.
     */
    suspend fun checkAvailableModelsAllowance(
        apiKeys: List<String>,
        curatedModels: List<String> = PreferencesRepo.SUPPORTED_MODELS,
        probeQuota: Boolean = true,
        onProgress: ((String) -> Unit)? = null
    ): List<ModelAllowanceEntry> = withContext(Dispatchers.IO) {
        val cleanKeys = apiKeys.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleanKeys.isEmpty()) {
            return@withContext emptyList()
        }

        val now = System.currentTimeMillis()
        val listedDisplayNames = LinkedHashMap<String, String>()

        // 1. Discover live models from GET /v1beta/models across up to 2 keys
        onProgress?.invoke("Fetching live model list from Google AI Studio...")
        for (key in cleanKeys.take(2)) {
            val listed = runCatching {
                val req = Request.Builder()
                    .url("$BASE_URL/v1beta/models?key=$key")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val bodyStr = resp.body?.string().orEmpty()
                    val root = json.parseToJsonElement(bodyStr) as? JsonObject ?: return@use false
                    val modelsArr = root["models"] as? JsonArray ?: return@use false
                    for (el in modelsArr) {
                        val obj = el as? JsonObject ?: continue
                        val rawName = obj["name"]?.jsonPrimitive?.content?.removePrefix("models/")?.trim().orEmpty()
                        if (rawName.isBlank() || !isEligibleMultimodalModelName(rawName)) continue
                        val methods = (obj["supportedGenerationMethods"] as? JsonArray)
                            ?.mapNotNull { it.jsonPrimitive.content }
                            .orEmpty()
                        if ("generateContent" !in methods) continue
                        val disp = obj["displayName"]?.jsonPrimitive?.content?.trim().orEmpty()
                        listedDisplayNames[rawName] = disp.ifBlank { rawName }
                    }
                    listedDisplayNames.isNotEmpty()
                }
            }.getOrDefault(false)
            if (listed) break
        }

        // 2. Build merged model list: curated active models + top live-discovered models (capped at 12)
        val extraDiscovered = listedDisplayNames.keys
            .filter { it !in curatedModels }
            .filter { name ->
                val lower = name.lowercase()
                !Regex("""-\d{3}$""").containsMatchIn(lower) && !lower.contains("exp-")
            }
            .sortedByDescending { name ->
                Regex("""gemini-(\d+)(?:\.(\d+))?""").find(name.lowercase())?.let { m ->
                    (m.groupValues[1].toIntOrNull() ?: 0) * 100 + (m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0) * 10
                } ?: 0
            }
            .take(4)

        val allModels = (curatedModels + extraDiscovered).distinct().take(12)
        val results = mutableListOf<ModelAllowanceEntry>()

        // 3. Probe each model with a tiny 4-token generateContent call to verify actual key allowance
        val tinyPayload = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", "OK") })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("max_output_tokens", 4)
            }
        }.toString()

        allModels.forEachIndexed { idx, modelId ->
            val isListed = modelId in listedDisplayNames
            val displayName = listedDisplayNames[modelId].orEmpty().ifBlank { modelId }
            val freeHint = PreferencesRepo.isModelFreeTierEligible(modelId)

            if (!probeQuota) {
                results.add(
                    ModelAllowanceEntry(
                        modelId = modelId,
                        displayName = displayName,
                        status = if (isListed) ModelAllowanceEntry.STATUS_LISTED else ModelAllowanceEntry.STATUS_UNKNOWN,
                        isFreeTierHint = freeHint,
                        detail = if (isListed) "Listed in /v1beta/models" else "Not probed",
                        checkedAtMs = now
                    )
                )
                return@forEachIndexed
            }

            onProgress?.invoke("Probing ${idx + 1}/${allModels.size}: $modelId...")
            // Rotate start key across the pool to distribute probe traffic
            val rotatedKeys = buildList {
                for (k in 0 until minOf(cleanKeys.size, 2)) {
                    add(cleanKeys[(idx + k) % cleanKeys.size])
                }
            }

            var resolvedEntry: ModelAllowanceEntry? = null
            for (key in rotatedKeys) {
                val probeStart = System.currentTimeMillis()
                try {
                    val req = Request.Builder()
                        .url("$BASE_URL/v1beta/models/$modelId:generateContent?key=$key")
                        .addHeader("Content-Type", "application/json")
                        .post(tinyPayload.toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    httpClient.newCall(req).execute().use { resp ->
                        val latency = System.currentTimeMillis() - probeStart
                        val bodyStr = resp.body?.string().orEmpty()
                        val bodyLower = bodyStr.lowercase()

                        when {
                            resp.isSuccessful -> {
                                resolvedEntry = ModelAllowanceEntry(
                                    modelId = modelId,
                                    displayName = displayName,
                                    status = ModelAllowanceEntry.STATUS_AVAILABLE,
                                    isFreeTierHint = freeHint,
                                    latencyMs = latency,
                                    detail = "Ready (${latency}ms)",
                                    checkedAtMs = now
                                )
                            }
                            resp.code == 429 -> {
                                val isZeroLimitPaidOnly = bodyLower.contains("limit: 0") ||
                                    bodyLower.contains("\"limit\": 0") ||
                                    bodyLower.contains("billing") ||
                                    bodyLower.contains("paid tier")
                                resolvedEntry = ModelAllowanceEntry(
                                    modelId = modelId,
                                    displayName = displayName,
                                    status = if (isZeroLimitPaidOnly) {
                                        ModelAllowanceEntry.STATUS_PAID_ONLY
                                    } else {
                                        ModelAllowanceEntry.STATUS_QUOTA_EXCEEDED
                                    },
                                    isFreeTierHint = if (isZeroLimitPaidOnly) false else freeHint,
                                    latencyMs = latency,
                                    detail = if (isZeroLimitPaidOnly) "Paid Tier required (free limit: 0)" else "429 Quota reached",
                                    checkedAtMs = now
                                )
                            }
                            resp.code == 403 -> {
                                val isBilling = bodyLower.contains("billing") || bodyLower.contains("paid") || bodyLower.contains("tier")
                                resolvedEntry = ModelAllowanceEntry(
                                    modelId = modelId,
                                    displayName = displayName,
                                    status = if (isBilling) ModelAllowanceEntry.STATUS_PAID_ONLY else ModelAllowanceEntry.STATUS_UNAVAILABLE,
                                    isFreeTierHint = if (isBilling) false else freeHint,
                                    latencyMs = latency,
                                    detail = if (isBilling) "Paid Tier required" else "HTTP 403 Forbidden",
                                    checkedAtMs = now
                                )
                            }
                            resp.code == 404 -> {
                                resolvedEntry = ModelAllowanceEntry(
                                    modelId = modelId,
                                    displayName = displayName,
                                    status = ModelAllowanceEntry.STATUS_UNAVAILABLE,
                                    isFreeTierHint = freeHint,
                                    latencyMs = latency,
                                    detail = "Not available (HTTP 404)",
                                    checkedAtMs = now
                                )
                            }
                            resp.code == 401 -> {
                                resolvedEntry = ModelAllowanceEntry(
                                    modelId = modelId,
                                    displayName = displayName,
                                    status = ModelAllowanceEntry.STATUS_UNAVAILABLE,
                                    isFreeTierHint = freeHint,
                                    latencyMs = latency,
                                    detail = "API key rejected (HTTP 401)",
                                    checkedAtMs = now
                                )
                            }
                            else -> {
                                resolvedEntry = ModelAllowanceEntry(
                                    modelId = modelId,
                                    displayName = displayName,
                                    status = if (isListed) ModelAllowanceEntry.STATUS_LISTED else ModelAllowanceEntry.STATUS_UNKNOWN,
                                    isFreeTierHint = freeHint,
                                    latencyMs = latency,
                                    detail = "HTTP ${resp.code}",
                                    checkedAtMs = now
                                )
                            }
                        }
                    }
                    // If we got a definitive AVAILABLE or 404 result, no need to probe a 2nd key
                    val st = resolvedEntry?.status
                    if (st == ModelAllowanceEntry.STATUS_AVAILABLE || st == ModelAllowanceEntry.STATUS_UNAVAILABLE) {
                        if (resolvedEntry?.detail?.contains("401") != true) break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    resolvedEntry = ModelAllowanceEntry(
                        modelId = modelId,
                        displayName = displayName,
                        status = if (isListed) ModelAllowanceEntry.STATUS_LISTED else ModelAllowanceEntry.STATUS_UNKNOWN,
                        isFreeTierHint = freeHint,
                        detail = e.message?.take(60) ?: "Probe error",
                        checkedAtMs = now
                    )
                }
            }

            results.add(
                resolvedEntry ?: ModelAllowanceEntry(
                    modelId = modelId,
                    displayName = displayName,
                    status = if (isListed) ModelAllowanceEntry.STATUS_LISTED else ModelAllowanceEntry.STATUS_UNKNOWN,
                    isFreeTierHint = freeHint,
                    detail = if (isListed) "Listed in API" else "Unchecked",
                    checkedAtMs = now
                )
            )
        }

        results
    }

    /**
     * Checks live Gemini rate limits directly from Google using two complementary live sources:
     *  1. Official Google AI Gemini Rate Limits docs (`https://ai.google.dev/gemini-api/docs/rate-limits`)
     *     to extract any updated published RPM / TPM / RPD numbers per model.
     *  2. Live Gemini REST API (`GET /v1beta/models` + tiny 2-token probes on user's saved key pool)
     *     to verify real `inputTokenLimit`, live rate-limit headers (`x-ratelimit-*`), 429 `quotaValue`
     *     payloads, and whether the account is **Free Tier** vs **Paid Tier 1+**.
     *
     * Merges live findings cleanly over the Nov 20, 2026 baseline so every model stays covered.
     */
    suspend fun checkLiveRateLimitsFromGoogle(
        apiKeys: List<String>,
        primaryModel: String,
        onProgress: ((String) -> Unit)? = null
    ): GeminiRateLimitSnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cleanKeys = apiKeys.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val baselineById = GeminiRateLimitManager.getDefaultNov2026Profiles()
            .associateBy { it.modelId.lowercase() }
            .toMutableMap()

        var docsParsedModels = 0
        var apiModelsListed = 0
        var headersDetected = false
        var detectedTier = GeminiRateLimitSnapshot.TIER_FREE
        val notes = mutableListOf<String>()

        // 1. Live fetch of Google's published Gemini rate limits reference page
        onProgress?.invoke("Fetching Google official Gemini rate-limit docs...")
        runCatching {
            val docsReq = Request.Builder()
                .url("https://ai.google.dev/gemini-api/docs/rate-limits")
                .addHeader("User-Agent", "Mozilla/5.0 (Android 15; AmbientBrain)")
                .get()
                .build()
            httpClient.newCall(docsReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    val parsed = parseGoogleRateLimitDocsHtml(html)
                    for ((modelKey, triple) in parsed) {
                        val existing = baselineById[modelKey] ?: GeminiRateLimitManager.getDefaultProfileForModel(modelKey)
                        val (rpm, tpm, rpd) = triple
                        baselineById[modelKey] = existing.copy(
                            freeRpm = rpm ?: existing.freeRpm,
                            freeTpm = tpm ?: existing.freeTpm,
                            freeRpd = rpd ?: existing.freeRpd,
                            source = GeminiRateLimitProfile.SOURCE_GOOGLE_LIVE
                        )
                        docsParsedModels++
                    }
                    if (docsParsedModels > 0) {
                        notes.add("Docs parsed $docsParsedModels model(s)")
                    }
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Official rate-limits page fetch skipped: ${e.message}")
        }

        // 2. Live query of /v1beta/models to capture live inputTokenLimit & active account models
        if (cleanKeys.isNotEmpty()) {
            onProgress?.invoke("Querying Google /v1beta/models for token limits...")
            for (key in cleanKeys.take(2)) {
                val ok = runCatching {
                    val req = Request.Builder()
                        .url("$BASE_URL/v1beta/models?key=$key")
                        .get()
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use false
                        val body = resp.body?.string().orEmpty()
                        val root = json.parseToJsonElement(body) as? JsonObject ?: return@use false
                        val arr = root["models"] as? JsonArray ?: return@use false
                        for (el in arr) {
                            val obj = el as? JsonObject ?: continue
                            val name = obj["name"]?.jsonPrimitive?.content?.removePrefix("models/")?.trim().orEmpty()
                            if (name.isBlank() || !isEligibleMultimodalModelName(name)) continue
                            val methods = (obj["supportedGenerationMethods"] as? JsonArray)
                                ?.mapNotNull { it.jsonPrimitive.content }
                                .orEmpty()
                            if ("generateContent" !in methods) continue
                            val inLimit = obj["inputTokenLimit"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1_048_576L
                            val keyLower = name.lowercase()
                            val existing = baselineById[keyLower] ?: GeminiRateLimitManager.getDefaultProfileForModel(name)
                            baselineById[keyLower] = existing.copy(
                                modelId = name,
                                inputTokenLimit = inLimit,
                                source = GeminiRateLimitProfile.SOURCE_GOOGLE_LIVE
                            )
                            apiModelsListed++
                        }
                        true
                    }
                }.getOrDefault(false)
                if (ok) break
            }
            if (apiModelsListed > 0) {
                notes.add("API verified $apiModelsListed active model(s)")
            }

            // 3. Live probe for Account Tier (Free vs Paid Tier 1+) & HTTP rate-limit headers / 429 quotaValue
            val tinyPayload = buildJsonObject {
                putJsonArray("contents") {
                    add(buildJsonObject {
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", "Hi") })
                        }
                    })
                }
                putJsonObject("generationConfig") {
                    put("max_output_tokens", 2)
                }
            }.toString()

            val probeKey = cleanKeys.first()
            val probeTargets = listOf(
                PreferencesRepo.normalizeSupportedModel(primaryModel),
                "gemini-4-argon",
                "gemini-4-carbon"
            ).distinct()

            for (probeModel in probeTargets) {
                onProgress?.invoke("Probing Google quota & tier on $probeModel...")
                runCatching {
                    val req = Request.Builder()
                        .url("$BASE_URL/v1beta/models/$probeModel:generateContent?key=$probeKey")
                        .addHeader("Content-Type", "application/json")
                        .post(tinyPayload.toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        val bodyLower = bodyStr.lowercase()
                        val keyLower = probeModel.lowercase()
                        val existing = baselineById[keyLower] ?: GeminiRateLimitManager.getDefaultProfileForModel(probeModel)

                        // Inspect any rate-limit headers returned by Google edge proxies
                        val hdrRpm = (resp.header("x-ratelimit-limit-requests")
                            ?: resp.header("x-goog-ratelimit-limit-requests"))?.toIntOrNull()
                        val hdrTpm = (resp.header("x-ratelimit-limit-tokens")
                            ?: resp.header("x-goog-ratelimit-limit-tokens"))?.toLongOrNull()
                        if (hdrRpm != null || hdrTpm != null) {
                            headersDetected = true
                            val updated = if (detectedTier == GeminiRateLimitSnapshot.TIER_PAID_1) {
                                existing.copy(
                                    paidRpm = hdrRpm ?: existing.paidRpm,
                                    paidTpm = hdrTpm ?: existing.paidTpm,
                                    source = GeminiRateLimitProfile.SOURCE_GOOGLE_LIVE
                                )
                            } else {
                                existing.copy(
                                    freeRpm = hdrRpm ?: existing.freeRpm,
                                    freeTpm = hdrTpm ?: existing.freeTpm,
                                    source = GeminiRateLimitProfile.SOURCE_GOOGLE_LIVE
                                )
                            }
                            baselineById[keyLower] = updated
                        }

                        val isPaidOnlyIndicator = !PreferencesRepo.isModelFreeTierEligible(probeModel)
                        when {
                            resp.isSuccessful && isPaidOnlyIndicator -> {
                                detectedTier = GeminiRateLimitSnapshot.TIER_PAID_1
                            }
                            resp.code == 429 -> {
                                val reportedLimit = Regex("""(?:limit:\s*|quotaValue["':\s]+)(\d+)""", RegexOption.IGNORE_CASE)
                                    .find(bodyStr)?.groupValues?.getOrNull(1)?.toLongOrNull()
                                if (reportedLimit == 0L && isPaidOnlyIndicator) {
                                    // Confirmed Free Tier (Paid model limit: 0)
                                    baselineById[keyLower] = existing.copy(
                                        freeRpm = 0,
                                        freeTpm = 0L,
                                        freeRpd = 0,
                                        source = GeminiRateLimitProfile.SOURCE_GOOGLE_LIVE
                                    )
                                } else if (reportedLimit != null && reportedLimit > 0L) {
                                    val isTpm = bodyLower.contains("token") || bodyLower.contains("tpm")
                                    val isRpm = bodyLower.contains("minute") && !isTpm
                                    baselineById[keyLower] = when {
                                        isTpm -> existing.copy(
                                            freeTpm = if (detectedTier == GeminiRateLimitSnapshot.TIER_FREE) reportedLimit else existing.freeTpm,
                                            paidTpm = if (detectedTier == GeminiRateLimitSnapshot.TIER_PAID_1) reportedLimit else existing.paidTpm,
                                            source = GeminiRateLimitProfile.SOURCE_GOOGLE_LIVE
                                        )
                                        isRpm -> existing.copy(
                                            freeRpm = if (detectedTier == GeminiRateLimitSnapshot.TIER_FREE) reportedLimit.toInt() else existing.freeRpm,
                                            paidRpm = if (detectedTier == GeminiRateLimitSnapshot.TIER_PAID_1) reportedLimit.toInt() else existing.paidRpm,
                                            source = GeminiRateLimitProfile.SOURCE_GOOGLE_LIVE
                                        )
                                        else -> existing
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val tierText = if (detectedTier == GeminiRateLimitSnapshot.TIER_PAID_1) "Paid Tier 1+" else "Free Tier"
        if (headersDetected) notes.add("Live rate-limit headers applied")
        notes.add("Account tier: $tierText")

        GeminiRateLimitSnapshot(
            referenceDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now)),
            source = GeminiRateLimitProfile.SOURCE_GOOGLE_LIVE,
            detectedTier = detectedTier,
            checkedAtMs = now,
            statusSummary = notes.joinToString(" • ").ifBlank { "Live Google check complete ($tierText)" },
            profiles = baselineById.values.sortedBy { p ->
                val idx = PreferencesRepo.SUPPORTED_MODELS.indexOf(p.modelId)
                if (idx >= 0) idx else 100
            }
        )
    }

    /**
     * Best-effort parser for Google's official `ai.google.dev/gemini-api/docs/rate-limits` HTML tables.
     * Extracts `(RPM, TPM, RPD)` triples for any `gemini-*` model rows found.
     */
    private fun parseGoogleRateLimitDocsHtml(html: String): Map<String, Triple<Int?, Long?, Int?>> {
        if (html.isBlank()) return emptyMap()
        val parsed = LinkedHashMap<String, Triple<Int?, Long?, Int?>>()
        val rowRegex = Regex("""<tr\b[^>]*>(.*?)</tr>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("""<t[dh]\b[^>]*>(.*?)</t[dh]>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val tagStripRegex = Regex("""<[^>]+>""")
        val modelIdRegex = Regex("""gemini-[a-z0-9._-]{2,40}""", RegexOption.IGNORE_CASE)

        fun parseCompactNumber(raw: String): Long? {
            val cleaned = raw.replace(",", "").replace(" ", "").trim().lowercase()
            val m = Regex("""^(\d+(?:\.\d+)?)(k|m)?$""").find(cleaned) ?: return null
            val num = m.groupValues[1].toDoubleOrNull() ?: return null
            return when (m.groupValues[2]) {
                "k" -> (num * 1_000.0).toLong()
                "m" -> (num * 1_000_000.0).toLong()
                else -> num.toLong()
            }
        }

        for (rowMatch in rowRegex.findAll(html)) {
            val cells = cellRegex.findAll(rowMatch.groupValues[1])
                .map { tagStripRegex.replace(it.groupValues[1], " ").replace("&nbsp;", " ").trim() }
                .toList()
            if (cells.size < 3) continue

            val modelMatch = modelIdRegex.find(cells[0]) ?: continue
            val modelId = modelMatch.value.lowercase()
            if (!isEligibleMultimodalModelName(modelId)) continue
            if (modelId in parsed) continue // Keep the first (Free Tier) table row

            val numericCells = cells.drop(1).mapNotNull { parseCompactNumber(it) }
            if (numericCells.size >= 2) {
                val rpm = numericCells.getOrNull(0)?.toInt()?.takeIf { it in 1..10_000 }
                val tpm = numericCells.getOrNull(1)?.takeIf { it >= 10_000L }
                val rpd = numericCells.getOrNull(2)?.toInt()?.takeIf { it in 1..1_000_000 }
                if (rpm != null || tpm != null) {
                    parsed[modelId] = Triple(rpm, tpm, rpd)
                }
            }
        }
        return parsed
    }

    private fun extractOutputTextFromInteraction(root: JsonObject): String? {
        val steps = root["steps"] as? JsonArray
        if (steps != null) {
            val sb = StringBuilder()
            for (stepEl in steps) {
                val stepObj = stepEl as? JsonObject ?: continue
                if (stepObj["type"]?.jsonPrimitive?.content == "model_output") {
                    val contentArr = stepObj["content"] as? JsonArray ?: continue
                    for (partEl in contentArr) {
                        val partObj = partEl as? JsonObject ?: continue
                        if (partObj["type"]?.jsonPrimitive?.content == "text") {
                            val t = partObj["text"]?.jsonPrimitive?.content
                            if (!t.isNullOrBlank()) sb.append(t)
                        }
                    }
                }
            }
            if (sb.isNotEmpty()) return sb.toString().trim()
        }

        val outputs = root["outputs"] as? JsonArray
        if (outputs != null) {
            for (outEl in outputs.reversed()) {
                val outObj = outEl as? JsonObject ?: continue
                if (outObj["type"]?.jsonPrimitive?.content == "text") {
                    return outObj["text"]?.jsonPrimitive?.content?.trim()
                }
            }
        }
        return null
    }

    private fun extractOutputTextFromGenerateContent(element: kotlinx.serialization.json.JsonElement): String? {
        val root = element as? JsonObject ?: return null
        val candidates = root["candidates"] as? JsonArray ?: return null
        val sb = StringBuilder()
        for (c in candidates) {
            val cObj = c as? JsonObject ?: continue
            val content = cObj["content"] as? JsonObject ?: continue
            val parts = content["parts"] as? JsonArray ?: continue
            for (p in parts) {
                val pObj = p as? JsonObject ?: continue
                val t = pObj["text"]?.jsonPrimitive?.content
                if (!t.isNullOrBlank()) sb.append(t)
            }
        }
        return if (sb.isNotEmpty()) sb.toString().trim() else null
    }

    private fun uploadViaFilesApi(apiKey: String, file: File, mimeType: String): Pair<String, String> {
        val numBytes = file.length()
        val metaJson = buildJsonObject {
            putJsonObject("file") {
                put("display_name", file.name)
            }
        }
        val startReq = Request.Builder()
            .url("$BASE_URL/upload/v1beta/files")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("X-Goog-Upload-Protocol", "resumable")
            .addHeader("X-Goog-Upload-Command", "start")
            .addHeader("X-Goog-Upload-Header-Content-Length", numBytes.toString())
            .addHeader("X-Goog-Upload-Header-Content-Type", mimeType)
            .addHeader("Content-Type", "application/json")
            .post(metaJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val uploadUrl = httpClient.newCall(startReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Files API init failed: HTTP ${resp.code}")
            }
            resp.header("X-Goog-Upload-URL")
                ?: throw IllegalStateException("Missing X-Goog-Upload-URL header")
        }

        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .addHeader("Content-Length", numBytes.toString())
            .addHeader("X-Goog-Upload-Offset", "0")
            .addHeader("X-Goog-Upload-Command", "upload, finalize")
            .post(file.asRequestBody(mimeType.toMediaType()))
            .build()

        return httpClient.newCall(uploadReq).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Files API upload failed: HTTP ${resp.code}: $bodyStr")
            }
            val fileObj = json.parseToJsonElement(bodyStr).jsonObject["file"]?.jsonObject
                ?: throw IllegalStateException("Missing file object in upload response")
            val name = fileObj["name"]?.jsonPrimitive?.content ?: ""
            val uri = fileObj["uri"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Missing file.uri in upload response")
            Log.i(TAG, "Uploaded ${file.name} via Files API: $uri")
            name to uri
        }
    }

    private fun deleteRemoteFile(apiKey: String, remoteFileName: String) {
        if (remoteFileName.isBlank()) return
        val req = Request.Builder()
            .url("$BASE_URL/v1beta/$remoteFileName")
            .addHeader("x-goog-api-key", apiKey)
            .delete()
            .build()
        httpClient.newCall(req).execute().close()
    }

    private fun cleanJsonText(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```json", ignoreCase = true)) {
            text = text.substring(7).trim()
        } else if (text.startsWith("```")) {
            text = text.substring(3).trim()
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length - 3).trim()
        }
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace >= firstBrace) {
            text = text.substring(firstBrace, lastBrace + 1)
        }
        return text.trim()
    }

    companion object {
        private const val TAG = "GeminiInteractions"
        private const val BASE_URL = "https://generativelanguage.googleapis.com"
        private const val API_REVISION = "2026-05-20"
        private const val INLINE_MAX_BYTES = 12L * 1024L * 1024L // 12 MB raw (~16 MB base64)
        private const val MAX_OUTPUT_TOKENS = 65536
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val discoveredRestModelsCache = ConcurrentHashMap<String, List<String>>()

        fun formatExceptionDetail(e: Throwable): String {
            val parts = mutableListOf<String>()
            var cur: Throwable? = e
            var depth = 0
            while (cur != null && depth < 5) {
                val cls = cur::class.java.simpleName.ifBlank { "Exception" }
                val msg = cur.message?.trim().orEmpty()
                val entry = if (msg.isNotBlank()) "$cls: $msg" else cls
                if (parts.none { it.equals(entry, ignoreCase = true) }) {
                    parts.add(entry)
                }
                cur = cur.cause
                depth++
            }
            return parts.joinToString(" <- ").take(460).ifBlank { e::class.java.simpleName }
        }

        fun classifyApiFailure(e: Throwable): ApiFailureClassification {
            val chain = generateSequence(e) { it.cause }.take(6).toList()
            val combinedText = chain.joinToString(" | ") { "${it::class.java.name}: ${it.message.orEmpty()}" }
                .lowercase()

            val isNetworkType = chain.any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is SocketTimeoutException ||
                    it is TimeoutCancellationException
            } || combinedText.contains("unable to resolve host") ||
                combinedText.contains("timed out") ||
                combinedText.contains("network") ||
                combinedText.contains("connection reset") ||
                combinedText.contains("eofexception")

            val isBilling = combinedText.contains("billing") ||
                combinedText.contains("paid tier") ||
                combinedText.contains("credit card") ||
                combinedText.contains("limit: 0") ||
                combinedText.contains("\"limit\": 0")

            val isKeyOrAuth = !isBilling && (
                combinedText.contains("api_key_invalid") ||
                    combinedText.contains("api key not valid") ||
                    combinedText.contains("api key expired") ||
                    (combinedText.contains("expired") && combinedText.contains("key")) ||
                    combinedText.contains("permission_denied") ||
                    combinedText.contains("unauthenticated") ||
                    combinedText.contains("http 401") ||
                    combinedText.contains(" 401") ||
                    combinedText.contains("http 403") ||
                    combinedText.contains(" 403") ||
                    combinedText.contains("unauthorized") ||
                    combinedText.contains("invalid api key")
                )

            val isRateLimit = !isBilling && (
                combinedText.contains("429") ||
                    combinedText.contains("resource_exhausted") ||
                    combinedText.contains("quota") ||
                    combinedText.contains("rate limit")
                )

            val isModelUnavailable = combinedText.contains("model_not_found") ||
                combinedText.contains("not found for api version") ||
                combinedText.contains("is not supported") ||
                combinedText.contains("unknown model") ||
                combinedText.contains("http 404") ||
                (combinedText.contains("404") && combinedText.contains("model"))

            val isServerError = combinedText.contains("http 500") ||
                combinedText.contains("http 502") ||
                combinedText.contains("http 503") ||
                combinedText.contains("http 504") ||
                combinedText.contains("overloaded") ||
                combinedText.contains("internal error")

            return when {
                isBilling -> ApiFailureClassification(
                    category = "Paid Tier Required",
                    shouldFailoverKey = true,
                    isNetworkTransient = false,
                    remediationHint = "Selected model requires a billed key. Auto-failing over to Fallback Model or switch to Free-tier 'gemini-4-hydrogen' / 'gemini-3.8-flash' / 'gemini-3.5-flash-lite'.",
                    isQuotaOrBilling = true
                )
                isKeyOrAuth -> ApiFailureClassification(
                    category = "Invalid / Restricted API Key",
                    shouldFailoverKey = true,
                    isNetworkTransient = false,
                    remediationHint = "Check or replace this API key in Settings -> Gemini API Key Pool."
                )
                isRateLimit -> ApiFailureClassification(
                    category = "Rate Limit / Quota (429)",
                    shouldFailoverKey = true,
                    isNetworkTransient = true,
                    remediationHint = "Model/key quota reached; failing over to next healthy key or Fallback Model.",
                    isQuotaOrBilling = true
                )
                isModelUnavailable -> ApiFailureClassification(
                    category = "Model Unavailable (404)",
                    shouldFailoverKey = false,
                    isNetworkTransient = false,
                    remediationHint = "Model unavailable on this API key; failing over to Fallback Model.",
                    isModelUnavailable = true
                )
                isServerError -> ApiFailureClassification(
                    category = "Gemini Server Error (5xx)",
                    shouldFailoverKey = true,
                    isNetworkTransient = true,
                    remediationHint = "Temporary Google API outage; retrying automatically."
                )
                isNetworkType -> ApiFailureClassification(
                    category = "Network / Timeout",
                    shouldFailoverKey = false,
                    isNetworkTransient = true,
                    remediationHint = "Device appears offline or timed out; jobs auto-retry when internet reconnects."
                )
                else -> ApiFailureClassification(
                    category = "Gemini API Error",
                    shouldFailoverKey = true,
                    isNetworkTransient = false,
                    remediationHint = "Verify your API keys in Settings -> Gemini API Key Pool."
                )
            }
        }
    }
}
