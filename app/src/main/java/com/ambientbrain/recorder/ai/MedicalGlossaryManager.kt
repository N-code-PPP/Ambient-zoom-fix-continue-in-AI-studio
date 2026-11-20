package com.ambientbrain.recorder.ai

import android.content.Context
import android.util.Log
import com.ambientbrain.recorder.data.AppDatabase
import com.ambientbrain.recorder.data.GlossaryTermEntry
import com.ambientbrain.recorder.data.PatientPlanOrder
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class AutoGlossaryIngestResult(
    val addedCount: Int,
    val updatedCount: Int,
    val skippedCount: Int,
    val addedTerms: List<String>
)

data class BuiltInGlossaryStats(
    val phoneticMappingsCount: Int,
    val neurologyTermsCount: Int,
    val workflowTemplatesCount: Int,
    val rawBytes: Int,
    val compactBytes: Int
) {
    val totalEntriesCount: Int
        get() = phoneticMappingsCount + neurologyTermsCount + workflowTemplatesCount
}

/**
 * Manages the bilingual (Thai-English) Neurology & Medical Glossary architecture:
 *  1. Compiles `assets/default_neuro_glossary.json` into a compact, prefix-cache-stable plain-text
 *     reference block (stripping JSON syntax overhead while preserving 100% clinical coverage).
 *  2. Builds an in-memory normalized index of built-in English canonical terms and Thai phonetics
 *     for zero-cost deduplication.
 *  3. Auto-ingests newly discovered medical/neurology terms (`discovered_glossary_terms[]`) from
 *     Gemini session analyses with noise filtering, speaker-name rejection, Thai phonetic enrichment,
 *     encounter counting, and bounded auto-learned storage (manual terms are never pruned).
 *  4. Provides a deterministic on-device scanner over previously analyzed clinical sessions to
 *     retroactively populate missing drugs, abbreviations, Thai-English mappings, and ward names
 *     at zero API cost.
 */
object MedicalGlossaryManager {
    private const val TAG = "MedicalGlossaryMgr"
    private const val ASSET_FILE_NAME = "default_neuro_glossary.json"
    const val MAX_AUTO_STORED_ENTRIES = 200
    const val MAX_PROMPT_AUTO_TERMS = 120

    private val glossaryLock = Any()

    private data class BuiltInGlossaryIndex(
        val compactPromptText: String,
        val canonicalTokens: Set<String>,
        val thaiTokens: Set<String>,
        val stats: BuiltInGlossaryStats
    )

    @Volatile
    private var cachedIndex: BuiltInGlossaryIndex? = null

    private val THAI_CHAR_REGEX = Regex("[\\u0E00-\\u0E7F]")
    private val LATIN_CHAR_REGEX = Regex("[A-Za-z]")
    private val PURE_NUMBER_OR_TIME_REGEX = Regex("^[\\d\\s:./%+-]+$")
    private val SPEAKER_PLACEHOLDER_REGEX = Regex("^(?:speaker|spk|person|user|bed|room|pt|patient)[\\s_#-]*\\d*$", RegexOption.IGNORE_CASE)

    // High-frequency generic clinical/everyday words that should not pollute the custom glossary
    private val GENERIC_STOPWORDS = setOf(
        "patient", "patients", "doctor", "doctors", "nurse", "nurses", "attending", "resident", "fellow",
        "intern", "extern", "hospital", "clinic", "ward", "room", "bed", "case", "cases", "round", "rounds",
        "medicine", "medical", "medication", "drug", "drugs", "dose", "doses", "treatment", "therapy",
        "plan", "order", "orders", "task", "tasks", "note", "notes", "summary", "review", "follow up",
        "followup", "follow-up", "check", "monitor", "continue", "hold", "stop", "start", "increase", "decrease",
        "normal", "abnormal", "stable", "better", "worse", "good", "okay", "ok", "today", "tomorrow", "yesterday",
        "morning", "evening", "tonight", "night", "history", "exam", "physical exam", "vital signs", "vitals",
        "blood pressure", "heart rate", "respiratory rate", "temperature", "oxygen", "lab", "labs", "imaging",
        "scan", "test", "tests", "result", "results", "symptom", "symptoms", "pain", "fever", "headache",
        "weakness", "numbness", "seizure", "stroke", "brain", "nerve", "muscle", "blood", "urine", "fluid",
        "bp", "hr", "rr", "bt", "spo2", "o2", "iv", "im", "sc", "po", "ng", "od", "bid", "tid", "qid",
        "hs", "prn", "stat", "ac", "pc", "q4h", "q6h", "q8h", "q12h", "q24h", "mg", "mcg", "ml", "kg",
        "cm", "mm", "dl", "meq", "mmol", "iu", "unit", "units", "tab", "tabs", "cap", "caps", "amp",
        "people", "family", "mother", "father", "daughter", "son", "wife", "husband", "meeting", "call",
        "คนไข้", "ผู้ป่วย", "หมอ", "อาจารย์", "พยาบาล", "ยา", "ผลแล็บ", "อาการ", "วันนี้", "พรุ่งนี้"
    )

    // Clinical regexes for deterministic retroactive extraction from stored sessions
    private val THAI_PAREN_EN_REGEX = Regex("([\\u0E00-\\u0E7F][\\u0E00-\\u0E7F\\s/\\-]{1,36})\\s*\\(([A-Za-z][A-Za-z0-9\\s\\-/+]{2,48})\\)")
    private val EN_PAREN_THAI_REGEX = Regex("([A-Za-z][A-Za-z0-9\\s\\-/+]{2,48})\\s*\\(([\\u0E00-\\u0E7F][\\u0E00-\\u0E7F\\s/\\-]{1,36})\\)")
    private val ACRONYM_EXPANSION_REGEX = Regex("\\b([A-Z][A-Z0-9\\-]{1,7})\\s*\\(([A-Za-z][A-Za-z0-9\\s\\-/]{4,58})\\)")
    private val DRUG_WITH_DOSE_REGEX = Regex(
        "\\b([A-Z][a-zA-Z\\-]{3,28})\\s+(\\d+(?:\\.\\d+)?\\s*(?:mg|mcg|g|gm|mL|ml|units?|IU|mEq|mmol)(?:/kg|/hr|/day)?)\\b"
    )
    private val WARD_UNIT_REGEX = Regex(
        "\\b((?:Neuro\\s*ICU|Stroke\\s*Unit|NSICU|NICU|MICU|SICU|CCU|RCU|ER|OPD|IPD|Ward\\s*[A-Z0-9\\-]{1,8}|Med\\s*(?:Ward\\s*)?\\d[A-Z0-9\\-]{0,4}|Neuro\\s*Ward\\s*[A-Z0-9\\-]{0,6}))\\b",
        RegexOption.IGNORE_CASE
    )

    private val DRUG_NON_MED_PREFIXES = setOf(
        "total", "target", "about", "around", "over", "under", "below", "above", "give", "given", "start",
        "hold", "keep", "drop", "dose", "load", "loading", "bolus", "infusion", "fluid", "water", "saline",
        "sodium", "potassium", "calcium", "magnesium", "glucose", "sugar", "protein", "albumin", "creatinine",
        "bilirubin", "hemoglobin", "platelet", "lactate", "volume", "weight", "height", "blood", "serum",
        "plasma", "level", "trough", "peak", "range", "daily", "every", "single", "repeat", "check", "order",
        "patient", "after", "before", "during", "within", "change", "reduce", "increase", "adjust", "taper"
    )

    private fun getBuiltInIndex(context: Context): BuiltInGlossaryIndex {
        cachedIndex?.let { return it }
        return synchronized(this) {
            cachedIndex?.let { return it }
            val rawAsset = runCatching {
                context.applicationContext.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
            }.getOrDefault("")

            val parsed = parseBuiltInGlossary(rawAsset)
            cachedIndex = parsed
            parsed
        }
    }

    fun getBuiltInStats(context: Context): BuiltInGlossaryStats {
        return getBuiltInIndex(context).stats
    }

    private fun parseBuiltInGlossary(rawJson: String): BuiltInGlossaryIndex {
        if (rawJson.isBlank()) {
            return BuiltInGlossaryIndex(
                compactPromptText = "",
                canonicalTokens = emptySet(),
                thaiTokens = emptySet(),
                stats = BuiltInGlossaryStats(0, 0, 0, 0, 0)
            )
        }

        val canonical = HashSet<String>(512)
        val thaiSet = HashSet<String>(256)
        val phonetics = mutableListOf<String>()
        val neuroTerms = mutableListOf<String>()
        val templates = mutableListOf<String>()

        runCatching {
            val obj = JSONObject(rawJson)

            obj.optJSONArray("thai_phonetic_mappings")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val line = arr.optString(i).trim()
                    if (line.isBlank()) continue
                    phonetics.add(line)
                    val eqIdx = line.indexOf('=')
                    if (eqIdx > 0) {
                        val lhs = line.substring(0, eqIdx)
                        val rhs = line.substring(eqIdx + 1)
                        lhs.split('/').map { normalizeToken(it) }.filter { it.isNotBlank() }.forEach {
                            thaiSet.add(it)
                            canonical.add(it)
                        }
                        extractCanonicalVariantsFromClause(rhs).forEach { canonical.add(it) }
                    }
                }
            }

            obj.optJSONArray("neurology_terms")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val line = arr.optString(i).trim()
                    if (line.isBlank()) continue
                    neuroTerms.add(line)
                    extractCanonicalVariantsFromClause(line).forEach { canonical.add(it) }
                }
            }

            obj.optJSONArray("ward_round_workflow_templates")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val line = arr.optString(i).trim()
                    if (line.isNotBlank()) templates.add(line)
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed parsing $ASSET_FILE_NAME compactly; falling back to raw text: ${e.message}")
            return BuiltInGlossaryIndex(
                compactPromptText = rawJson,
                canonicalTokens = emptySet(),
                thaiTokens = emptySet(),
                stats = BuiltInGlossaryStats(0, 0, 0, rawJson.toByteArray().size, rawJson.toByteArray().size)
            )
        }

        // Prefix-cache-stable compact plain text (strips JSON quotes/brackets/indentation while retaining 100% terms)
        val compact = buildString(rawJson.length) {
            appendLine("[THAI-EN PHONETIC MAPPINGS]")
            phonetics.forEach { appendLine("- $it") }
            appendLine()
            appendLine("[NEUROLOGY & CLINICAL REFERENCE TERMS]")
            neuroTerms.forEach { appendLine("- $it") }
            if (templates.isNotEmpty()) {
                appendLine()
                appendLine("[WARD ROUND WORKFLOW TEMPLATES]")
                templates.forEach { appendLine("- $it") }
            }
        }.trim()

        return BuiltInGlossaryIndex(
            compactPromptText = compact,
            canonicalTokens = canonical,
            thaiTokens = thaiSet,
            stats = BuiltInGlossaryStats(
                phoneticMappingsCount = phonetics.size,
                neurologyTermsCount = neuroTerms.size,
                workflowTemplatesCount = templates.size,
                rawBytes = rawJson.toByteArray(Charsets.UTF_8).size,
                compactBytes = compact.toByteArray(Charsets.UTF_8).size
            )
        )
    }

    private fun extractCanonicalVariantsFromClause(clause: String): List<String> {
        val out = mutableListOf<String>()
        val trimmed = clause.trim()
        if (trimmed.isBlank()) return out

        // Head before first '(' or ':'
        val head = trimmed.substringBefore('(').substringBefore(':').trim()
        if (head.isNotBlank()) {
            out.add(normalizeToken(head))
            head.split('/', '&', ',').forEach { part ->
                val norm = normalizeToken(part)
                if (norm.length >= 2) out.add(norm)
            }
        }

        // Capture inner acronyms / generic names inside parentheses, e.g. "Keppra (levetiracetam)"
        Regex("\\(([^)]{2,80})\\)").findAll(trimmed).forEach { m ->
            val inner = m.groupValues[1]
            inner.split('/', ',', ';', ':').forEach { seg ->
                val candidate = seg.trim().substringBefore(" -").trim()
                val norm = normalizeToken(candidate)
                if (norm.length in 2..48) out.add(norm)
                // Also grab leading drug/acronym word if simple
                val firstWord = candidate.split(Regex("\\s+")).firstOrNull().orEmpty()
                val normFirst = normalizeToken(firstWord)
                if (normFirst.length >= 3 && firstWord.any { it.isLetter() }) {
                    out.add(normFirst)
                }
            }
        }
        return out
    }

    /**
     * Builds the glossary block appended to Gemini's `system_instruction`.
     * Layout is strictly ordered for Gemini Prefix Context Caching:
     *   1) Immutable built-in Neurology & Thai-EN phonetic glossary (byte-identical every call)
     *   2) Deterministically sorted User & Auto-Learned structured glossary entries
     *   3) Legacy free-text `customGlossaryTerms` notes (backward compatible)
     */
    fun buildPromptGlossaryText(
        context: Context,
        prefs: PreferencesRepo,
        legacyCustomOverride: String? = null
    ): String {
        val index = getBuiltInIndex(context)
        val structured = prefs.getStructuredGlossaryEntries()
        val legacyText = (legacyCustomOverride ?: prefs.customGlossaryTerms).trim()

        if (structured.isEmpty() && legacyText.isBlank()) {
            return index.compactPromptText
        }

        return buildString {
            append(index.compactPromptText)

            if (structured.isNotEmpty()) {
                val manualEntries = structured
                    .filter { !it.isAutoAdded }
                    .sortedBy { it.term.lowercase(Locale.US) }
                val autoEntries = structured
                    .filter { it.isAutoAdded }
                    .sortedWith(
                        compareByDescending<GlossaryTermEntry> { it.seenCount }
                            .thenBy { it.term.lowercase(Locale.US) }
                    )
                    .take(MAX_PROMPT_AUTO_TERMS)

                val selected = manualEntries + autoEntries
                if (selected.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine("[USER & AUTO-LEARNED MEDICAL GLOSSARY (${selected.size} TERMS)]")
                    selected.forEach { entry ->
                        val cat = entry.category.ifBlank { GlossaryTermEntry.CATEGORY_GENERAL_MED }
                        val thaiPart = if (entry.thaiPhonetic.isNotBlank()) " | Thai: ${entry.thaiPhonetic}" else ""
                        val notePart = if (entry.notes.isNotBlank()) " — ${entry.notes}" else ""
                        appendLine("- [$cat] ${entry.term}$thaiPart$notePart")
                    }
                }
            }

            if (legacyText.isNotBlank()) {
                appendLine()
                appendLine()
                appendLine("[USER CUSTOM GLOSSARY NOTES]")
                append(legacyText)
            }
        }.trim()
    }

    /**
     * Filters, deduplicates against built-in + stored entries, and persists newly discovered
     * neurology/medical terms returned by Gemini (`discovered_glossary_terms[]`) or the
     * retroactive clinical scanner.
     */
    fun autoIngestDiscoveredTerms(
        context: Context,
        prefs: PreferencesRepo,
        sessionId: String?,
        discoveredTerms: List<GeminiGlossaryTermDto>,
        speakerNamesToIgnore: Set<String> = emptySet(),
        recordedAtMs: Long = System.currentTimeMillis()
    ): AutoGlossaryIngestResult {
        if (discoveredTerms.isEmpty()) {
            return AutoGlossaryIngestResult(0, 0, 0, emptyList())
        }

        val builtIn = getBuiltInIndex(context)
        val ignoreNames = speakerNamesToIgnore
            .map { normalizeToken(it) }
            .filter { it.length >= 2 }
            .toSet()

        var addedCount = 0
        var updatedCount = 0
        var skippedCount = 0
        val addedTerms = mutableListOf<String>()
        val eventTimeMs = recordedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()

        synchronized(glossaryLock) {
            val current = prefs.getStructuredGlossaryEntries().toMutableList()

            for (raw in discoveredTerms) {
                val cleanTerm = sanitizeTerm(raw.term)
                val cleanThai = sanitizeThaiPhonetic(raw.thaiPhonetic.orEmpty())
                val cleanNotes = raw.notes?.trim()?.take(140).orEmpty()
                val normCategory = GlossaryTermEntry.normalizeCategory(raw.category)

                if (!isValidCandidateTerm(cleanTerm, ignoreNames)) {
                    skippedCount++
                    continue
                }

                val normKey = normalizeToken(cleanTerm)
                val thaiParts = cleanThai.split('/').map { normalizeToken(it) }.filter { it.isNotBlank() }
                val hasNewThaiPhonetic = thaiParts.any { THAI_CHAR_REGEX.containsMatchIn(it) && it !in builtIn.thaiTokens }

                // Deduplicate against existing user/auto structured glossary first
                val existingIdx = current.indexOfFirst { entry ->
                    normalizeToken(entry.term) == normKey ||
                        (cleanThai.isNotBlank() &&
                            entry.thaiPhonetic.isNotBlank() &&
                            normalizeToken(entry.thaiPhonetic) == normalizeToken(cleanThai))
                }

                if (existingIdx >= 0) {
                    val prev = current[existingIdx]
                    val mergedThai = mergeThaiPhonetics(prev.thaiPhonetic, cleanThai)
                    val mergedNotes = when {
                        prev.notes.isBlank() -> cleanNotes
                        cleanNotes.length > prev.notes.length + 12 && prev.isAutoAdded -> cleanNotes
                        else -> prev.notes
                    }
                    val shouldIncrementCount = sessionId.isNullOrBlank() || prev.sourceSessionId != sessionId
                    current[existingIdx] = prev.copy(
                        thaiPhonetic = mergedThai,
                        notes = mergedNotes,
                        category = if (prev.category == GlossaryTermEntry.CATEGORY_GENERAL_MED && normCategory != GlossaryTermEntry.CATEGORY_GENERAL_MED) {
                            normCategory
                        } else {
                            prev.category
                        },
                        seenCount = if (shouldIncrementCount) prev.seenCount + 1 else prev.seenCount,
                        lastSeenAtMs = maxOf(prev.lastSeenAtMs, eventTimeMs),
                        sourceSessionId = sessionId ?: prev.sourceSessionId
                    )
                    updatedCount++
                    continue
                }

                // Deduplicate against built-in glossary: skip unless it adds a genuinely NEW Thai phonetic mapping
                val alreadyBuiltIn = normKey in builtIn.canonicalTokens ||
                    (thaiParts.isNotEmpty() && thaiParts.all { it in builtIn.thaiTokens } && !LATIN_CHAR_REGEX.containsMatchIn(cleanTerm))
                if (alreadyBuiltIn && !hasNewThaiPhonetic) {
                    skippedCount++
                    continue
                }

                val newEntry = GlossaryTermEntry(
                    id = UUID.randomUUID().toString().take(8),
                    term = cleanTerm,
                    thaiPhonetic = cleanThai,
                    category = if (alreadyBuiltIn && hasNewThaiPhonetic) GlossaryTermEntry.CATEGORY_PHONETIC_TH_EN else normCategory,
                    notes = cleanNotes,
                    isAutoAdded = true,
                    seenCount = 1,
                    createdAtMs = eventTimeMs,
                    lastSeenAtMs = eventTimeMs,
                    sourceSessionId = sessionId
                )
                current.add(newEntry)
                addedCount++
                addedTerms.add(cleanTerm)
            }

            if (addedCount > 0 || updatedCount > 0) {
                pruneExcessAutoEntries(current)
                prefs.saveStructuredGlossaryEntries(current)
            }
        }

        if (addedCount > 0 || updatedCount > 0) {
            Log.i(
                TAG,
                "Auto-glossary ingest (session=${sessionId ?: "scan"}): added=$addedCount, updated=$updatedCount, skipped=$skippedCount"
            )
        }
        return AutoGlossaryIngestResult(
            addedCount = addedCount,
            updatedCount = updatedCount,
            skippedCount = skippedCount,
            addedTerms = addedTerms
        )
    }

    /**
     * Retroactively scans already-analyzed clinical sessions stored in Room at zero API cost,
     * extracting recurring Thai-English clinical mappings, medical abbreviations with expansions,
     * drug-dose mentions, and local hospital/ward unit names not yet in the built-in glossary.
     */
    suspend fun scanExistingSessionsForGlossaryTerms(
        context: Context,
        prefs: PreferencesRepo
    ): AutoGlossaryIngestResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.get(appContext).dao()
        val allSessions = dao.getAllSessionsWithDetailsOnce()
        if (allSessions.isEmpty()) {
            return@withContext AutoGlossaryIngestResult(0, 0, 0, emptyList())
        }

        val json = Json { ignoreUnknownKeys = true }
        val speakerNames = HashSet<String>()
        prefs.getSpeakerRoster().forEach { spk ->
            spk.allKnownNames().forEach { speakerNames.add(it) }
        }
        speakerNames.add(prefs.userDisplayName)

        val candidatesByKey = LinkedHashMap<String, GeminiGlossaryTermDto>()
        val termSessionHits = HashMap<String, Int>()

        fun recordCandidate(dto: GeminiGlossaryTermDto, sessionKey: String) {
            val norm = normalizeToken(dto.term)
            if (norm.length < 2) return
            val hitKey = "$norm::$sessionKey"
            if (termSessionHits.putIfAbsent(hitKey, 1) == null) {
                val existing = candidatesByKey[norm]
                if (existing == null) {
                    candidatesByKey[norm] = dto
                } else {
                    candidatesByKey[norm] = existing.copy(
                        thaiPhonetic = mergeThaiPhonetics(existing.thaiPhonetic.orEmpty(), dto.thaiPhonetic.orEmpty())
                            .ifBlank { null },
                        notes = existing.notes?.takeIf { it.isNotBlank() } ?: dto.notes
                    )
                }
            }
        }

        val clinicalContexts = setOf("NEURO_ROUND", "MEDICAL_ROUND", "CASE_CONFERENCE", "HANDOVER", "TEACHING")

        for (details in allSessions) {
            val s = details.session
            details.speakers.forEach { spk ->
                spk.nickname?.takeIf { it.isNotBlank() }?.let { speakerNames.add(it) }
                speakerNames.add(spk.speakerLabel)
            }

            val isClinical = s.mode == SessionMode.ROUND_MANUAL ||
                s.mode == SessionMode.ROUND_AUTO ||
                (s.context in clinicalContexts) ||
                details.patients.isNotEmpty()
            if (!isClinical) continue

            val corpusParts = mutableListOf<String>()
            s.summary?.takeIf { it.isNotBlank() }?.let { corpusParts.add(it) }
            s.keyPointsJson?.let { raw ->
                runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()?.let { corpusParts.addAll(it) }
            }
            details.patients.forEach { p ->
                corpusParts.add(p.oneLiner)
                p.neuroExam?.let { corpusParts.add(it) }
                corpusParts.add(p.assessment)
                runCatching { json.decodeFromString<List<String>>(p.problemsJson) }.getOrNull()?.let { corpusParts.addAll(it) }
                runCatching { json.decodeFromString<List<String>>(p.resultsJson) }.getOrNull()?.let { corpusParts.addAll(it) }
                runCatching { json.decodeFromString<List<String>>(p.pendingJson) }.getOrNull()?.let { corpusParts.addAll(it) }
                runCatching { json.decodeFromString<List<PatientPlanOrder>>(p.planJson) }.getOrNull()?.forEach { corpusParts.add(it.text) }
                runCatching { json.decodeFromString<List<String>>(p.teachingJson) }.getOrNull()?.let { corpusParts.addAll(it) }
            }
            details.items.forEach { item ->
                corpusParts.add(item.title)
                corpusParts.add(item.details)
                corpusParts.add(item.quoteOriginal)
                item.quoteEn?.let { corpusParts.add(it) }
            }
            details.sentences.take(150).forEach { sent ->
                corpusParts.add(sent.textOriginal)
                sent.textEn?.let { corpusParts.add(it) }
            }

            val combinedText = corpusParts.joinToString("\n")
            if (combinedText.isBlank()) continue

            // 1. Thai (English) & English (Thai) paired clinical terms
            THAI_PAREN_EN_REGEX.findAll(combinedText).forEach { m ->
                val thai = m.groupValues[1].trim()
                val en = m.groupValues[2].trim()
                if (en.length in 3..45 && thai.length in 2..36) {
                    recordCandidate(
                        GeminiGlossaryTermDto(
                            term = en,
                            thaiPhonetic = thai,
                            category = GlossaryTermEntry.CATEGORY_PHONETIC_TH_EN,
                            notes = "Extracted from past clinical session"
                        ),
                        s.id
                    )
                }
            }
            EN_PAREN_THAI_REGEX.findAll(combinedText).forEach { m ->
                val en = m.groupValues[1].trim()
                val thai = m.groupValues[2].trim()
                if (en.length in 3..45 && thai.length in 2..36) {
                    recordCandidate(
                        GeminiGlossaryTermDto(
                            term = en,
                            thaiPhonetic = thai,
                            category = GlossaryTermEntry.CATEGORY_PHONETIC_TH_EN,
                            notes = "Extracted from past clinical session"
                        ),
                        s.id
                    )
                }
            }

            // 2. Medical acronyms with explicit expansions, e.g. "EVD (External ventricular drain)"
            ACRONYM_EXPANSION_REGEX.findAll(combinedText).forEach { m ->
                val abbr = m.groupValues[1].trim()
                val expansion = m.groupValues[2].trim()
                if (abbr.length in 2..7 && expansion.split(Regex("\\s+")).size in 2..7) {
                    recordCandidate(
                        GeminiGlossaryTermDto(
                            term = abbr,
                            thaiPhonetic = null,
                            category = GlossaryTermEntry.CATEGORY_GENERAL_MED,
                            notes = expansion
                        ),
                        s.id
                    )
                }
            }

            // 3. Medication mentions paired with explicit clinical dosage (e.g. "Brivaracetam 50 mg")
            DRUG_WITH_DOSE_REGEX.findAll(combinedText).forEach { m ->
                val drugName = m.groupValues[1].trim()
                val doseSnippet = m.groupValues[2].trim()
                val lowerDrug = drugName.lowercase(Locale.US)
                if (lowerDrug !in DRUG_NON_MED_PREFIXES && drugName.length in 4..28) {
                    recordCandidate(
                        GeminiGlossaryTermDto(
                            term = drugName,
                            thaiPhonetic = null,
                            category = GlossaryTermEntry.CATEGORY_DRUG,
                            notes = "Common dose seen: $doseSnippet"
                        ),
                        s.id
                    )
                }
            }

            // 4. Local hospital ward / ICU names
            WARD_UNIT_REGEX.findAll(combinedText).forEach { m ->
                val ward = m.groupValues[1].trim().replace(Regex("\\s+"), " ")
                if (ward.length in 2..24) {
                    recordCandidate(
                        GeminiGlossaryTermDto(
                            term = ward,
                            thaiPhonetic = null,
                            category = GlossaryTermEntry.CATEGORY_WARD_HOSPITAL,
                            notes = "Local hospital ward / unit"
                        ),
                        s.id
                    )
                }
            }
        }

        autoIngestDiscoveredTerms(
            context = appContext,
            prefs = prefs,
            sessionId = null,
            discoveredTerms = candidatesByKey.values.toList(),
            speakerNamesToIgnore = speakerNames
        )
    }

    private fun pruneExcessAutoEntries(entries: MutableList<GlossaryTermEntry>) {
        val autoEntries = entries.filter { it.isAutoAdded }
        if (autoEntries.size <= MAX_AUTO_STORED_ENTRIES) return
        val excess = autoEntries.size - MAX_AUTO_STORED_ENTRIES
        val idsToRemove = autoEntries
            .sortedWith(
                compareBy<GlossaryTermEntry> { it.seenCount }
                    .thenBy { it.lastSeenAtMs }
            )
            .take(excess)
            .map { it.id }
            .toSet()
        entries.removeAll { it.id in idsToRemove }
    }

    private fun isValidCandidateTerm(term: String, ignoreNames: Set<String>): Boolean {
        if (term.length < 2 || term.length > 90) return false
        if (PURE_NUMBER_OR_TIME_REGEX.matches(term)) return false
        if (SPEAKER_PLACEHOLDER_REGEX.matches(term)) return false
        val norm = normalizeToken(term)
        if (norm.length < 2) return false
        if (norm in GENERIC_STOPWORDS) return false
        if (norm in ignoreNames) return false
        // Reject raw sentences (too many words)
        if (term.split(Regex("\\s+")).size > 8) return false
        // Must contain at least one Latin or Thai letter
        if (!LATIN_CHAR_REGEX.containsMatchIn(term) && !THAI_CHAR_REGEX.containsMatchIn(term)) return false
        return true
    }

    private fun sanitizeTerm(raw: String): String {
        return raw.trim()
            .trim('*', '-', '•', '"', '\'', '.', ',', ';', ':')
            .replace(Regex("\\s+"), " ")
            .take(90)
            .trim()
    }

    private fun sanitizeThaiPhonetic(raw: String): String {
        return raw.trim()
            .trim('*', '-', '•', '"', '\'')
            .replace(Regex("\\s+"), " ")
            .take(80)
            .trim()
    }

    private fun mergeThaiPhonetics(existing: String, incoming: String): String {
        val parts = (existing.split('/') + incoming.split('/'))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeToken(it) }
            .take(4)
        return parts.joinToString(" / ")
    }

    fun normalizeToken(raw: String): String {
        return raw.trim()
            .lowercase(Locale.US)
            .replace(Regex("[()\\[\\]\"'.,;:!?]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
