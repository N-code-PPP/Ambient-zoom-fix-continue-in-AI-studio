package com.ambientbrain.recorder.ai

import java.util.Locale

/**
 * Deterministic post-processing, clinical reconciliation, and fuzzy deduplication engine
 * that refines Gemini's structured extraction into clean, non-redundant Tasks, Notes,
 * Decisions, Events, Patient blocks, and high-signal Key Points.
 */
object ExtractionRefiner {

    private val PRIORITY_RANK = mapOf(
        "URGENT" to 3,
        "HIGH" to 2,
        "NORMAL" to 1
    )

    private val TYPE_RANK = mapOf(
        "TASK" to 4,
        "DECISION" to 3,
        "EVENT" to 2,
        "NOTE" to 1
    )

    private val STOP_WORDS = setOf(
        "a", "an", "the", "to", "for", "of", "in", "on", "at", "by", "with", "and", "or",
        "from", "is", "are", "was", "were", "be", "will", "should", "must", "need", "needs",
        "please", "also", "then", "patient", "pt", "case", "regarding", "about"
    )

    private val URGENT_TRIGGERS = listOf(
        "stat", "emergency", "immediately", "right away", "code stroke", "stroke fast",
        "rt-pa", "rtpa", "alteplase", "tenecteplase", "tnk", "intubat", "arrest", "critical",
        "sepsis", "status epilepticus", "ด่วนที่สุด", "ทันที", "วิกฤต"
    )

    private val HIGH_PRIORITY_TRIGGERS = listOf(
        "asap", "urgent", "today", "this afternoon", "this evening", "before round",
        "ct brain", "cta", "mri", "lumbar puncture", "consult", "ด่วน", "วันนี้", "รีบ"
    )

    private val HIGH_ALERT_MED_KEYWORDS = listOf(
        "heparin", "enoxaparin", "clexane", "warfarin", "dabigatran", "rivaroxaban", "apixaban",
        "rt-pa", "rtpa", "alteplase", "tenecteplase", "tnk", "insulin", "potassium", "kcl",
        "norepinephrine", "levophed", "dopamine", "epinephrine", "amiodarone",
        "keppra", "levetiracetam", "phenytoin", "dilantin", "fosphenytoin", "valproate", "depakine",
        "lacosamide", "vimpat", "phenobarbital", "midazolam", "propofol",
        "vancomycin", "meropenem", "mannitol", "3% nacl", "hypertonic", "nimodipine"
    )

    private val DOSE_PATTERN = Regex("""\b\d+(?:\.\d+)?\s*(?:mg|mcg|ug|g|gm|ml|cc|unit|units|iu|meq|mmol|%|tab|tabs|cap|caps|q\d+h|od|bid|tid|qid|prn|iv|po|im|sc)\b""", RegexOption.IGNORE_CASE)

    /**
     * Cleans, fuzzily deduplicates, and enriches Key Points across single or multi-batch runs.
     * Synthesizes missing critical points from patients/items if Gemini under-produced key_points.
     */
    fun refineKeyPoints(
        rawKeyPoints: List<String>,
        patients: List<GeminiPatientDto>,
        items: List<GeminiExtractedItemDto>,
        maxCount: Int = 16
    ): List<String> {
        val cleanedCandidates = mutableListOf<String>()

        rawKeyPoints.forEach { raw ->
            val cleaned = cleanKeyPointText(raw)
            if (cleaned.length >= 6 && !isGenericFillerKeyPoint(cleaned)) {
                cleanedCandidates.add(cleaned)
            }
        }

        // Synthesize high-signal fallback key points from urgent items / decisions / patient plans
        // when key_points are sparse (or empty on dense conversations).
        if (cleanedCandidates.size < 4) {
            items.filter { it.itemType == "DECISION" || it.priority == "URGENT" || it.priority == "HIGH" }
                .take(5)
                .forEach { itItem ->
                    val prefix = when (itItem.itemType) {
                        "DECISION" -> "[Decision]"
                        else -> "[Action]"
                    }
                    val pat = itItem.patientIdentifier?.takeIf { p -> p.isNotBlank() }?.let { "$it: " }.orEmpty()
                    val spk = itItem.speakerLabel.takeIf { s -> s.isNotBlank() && !s.startsWith("Speaker") }?.let { " ($it)" }.orEmpty()
                    cleanedCandidates.add("$prefix $pat${itItem.title}$spk")
                }

            patients.forEach { p ->
                val topPlan = p.plan.firstOrNull { it.category in setOf("MEDICATION", "INVESTIGATION", "PROCEDURE") }
                if (topPlan != null) {
                    cleanedCandidates.add("[${topPlan.category.lowercase().replaceFirstChar { it.uppercase() }}] ${p.patientIdentifier}: ${topPlan.text}")
                } else if (p.assessment.isNotBlank()) {
                    cleanedCandidates.add("[Clinical] ${p.patientIdentifier}: ${p.assessment.take(120)}")
                }
            }
        }

        val deduped = mutableListOf<String>()
        for (candidate in cleanedCandidates) {
            val candTokens = tokenize(stripCategoryPrefix(candidate))
            if (candTokens.isEmpty()) continue

            val dupIndex = deduped.indexOfFirst { existing ->
                val existTokens = tokenize(stripCategoryPrefix(existing))
                tokenJaccardSimilarity(candTokens, existTokens) >= 0.66 ||
                    isSubstringMatch(stripCategoryPrefix(candidate), stripCategoryPrefix(existing))
            }

            if (dupIndex >= 0) {
                val existing = deduped[dupIndex]
                // Keep the richer / better-attributed version
                if (scoreKeyPointRichness(candidate) > scoreKeyPointRichness(existing)) {
                    deduped[dupIndex] = candidate
                }
            } else {
                deduped.add(candidate)
            }
        }

        return deduped
            .sortedByDescending { scoreKeyPointImportance(it) }
            .take(maxCount)
    }

    /**
     * Cross-checks `patients[].plan` and `patients[].pending` against `items[]`.
     * Any concrete clinical order/investigation/consult/pending follow-up that Gemini placed
     * inside `patients[]` but omitted from `items[]` is synthesized into an actionable TASK
     * anchored to that patient's spoken timestamp window.
     */
    fun synthesizeMissingPlanTasks(
        patients: List<GeminiPatientDto>,
        existingItems: List<GeminiExtractedItemDto>
    ): List<GeminiExtractedItemDto> {
        if (patients.isEmpty()) return existingItems
        val result = existingItems.toMutableList()

        for (patient in patients) {
            val normPatient = normalizePatientId(patient.patientIdentifier)
            val patientItems = result.filter {
                normalizePatientId(it.patientIdentifier.orEmpty()) == normPatient ||
                    it.patientIdentifier.isNullOrBlank()
            }

            // 1. Concrete actionable plan orders
            patient.plan.forEach { order ->
                if (order.text.isBlank()) return@forEach
                val orderCategory = order.category.uppercase(Locale.US)
                if (orderCategory !in setOf("MEDICATION", "INVESTIGATION", "CONSULT", "PROCEDURE", "DISCHARGE", "FAMILY", "MONITORING", "REHAB")) {
                    return@forEach
                }

                val orderTokens = tokenize(order.text)
                val alreadyRepresented = patientItems.any { item ->
                    val combined = "${item.title} ${item.details}"
                    tokenJaccardSimilarity(orderTokens, tokenize(combined)) >= 0.50 ||
                        isSubstringMatch(order.text, combined)
                }

                if (!alreadyRepresented) {
                    val verbTitle = formatOrderTitle(orderCategory, order.text)
                    val inferredUrgent = containsAny(order.text, URGENT_TRIGGERS)
                    val inferredHigh = inferredUrgent || containsAny(order.text, HIGH_PRIORITY_TRIGGERS) ||
                        orderCategory in setOf("MEDICATION", "PROCEDURE", "CONSULT")
                    val needsVerify = order.needsVerification ||
                        (orderCategory == "MEDICATION" && isHighAlertMedicationWithDose(order.text))

                    val synth = GeminiExtractedItemDto(
                        itemType = "TASK",
                        title = verbTitle,
                        details = "From ${patient.patientIdentifier} round plan ($orderCategory): ${order.text}",
                        patientIdentifier = patient.patientIdentifier,
                        speakerId = "SPK_1",
                        speakerLabel = "Round Team",
                        assignee = null,
                        isForUser = true,
                        due = if (inferredUrgent) "STAT / Now" else null,
                        priority = when {
                            inferredUrgent -> "URGENT"
                            inferredHigh -> "HIGH"
                            else -> "NORMAL"
                        },
                        quoteOriginal = order.text,
                        quoteEn = order.text,
                        approxStart = patient.approxStart,
                        approxEnd = patient.approxEnd,
                        needsVerification = needsVerify
                    )
                    result.add(synth)
                }
            }

            // 2. Pending results to chase/follow up
            patient.pending.forEach { pendingText ->
                if (pendingText.isBlank()) return@forEach
                val pendTokens = tokenize(pendingText)
                val alreadyRepresented = result.filter {
                    normalizePatientId(it.patientIdentifier.orEmpty()) == normPatient
                }.any { item ->
                    val combined = "${item.title} ${item.details}"
                    tokenJaccardSimilarity(pendTokens, tokenize(combined)) >= 0.50 ||
                        isSubstringMatch(pendingText, combined)
                }

                if (!alreadyRepresented && pendingText.length >= 4) {
                    result.add(
                        GeminiExtractedItemDto(
                            itemType = "TASK",
                            title = "Follow up pending: ${pendingText.trim().take(72)}",
                            details = "Pending item mentioned for ${patient.patientIdentifier}: $pendingText",
                            patientIdentifier = patient.patientIdentifier,
                            speakerId = "SPK_1",
                            speakerLabel = "Round Team",
                            assignee = null,
                            isForUser = true,
                            due = "Today",
                            priority = if (containsAny(pendingText, HIGH_PRIORITY_TRIGGERS)) "HIGH" else "NORMAL",
                            quoteOriginal = pendingText,
                            quoteEn = pendingText,
                            approxStart = patient.approxStart,
                            approxEnd = patient.approxEnd,
                            needsVerification = false
                        )
                    )
                }
            }
        }

        return result
    }

    /**
     * Deduplicates near-identical tasks/notes across segments or repeated mentions,
     * enriches priority/verification/assignee signals, and ranks by urgency & actionability.
     */
    fun deduplicateAndEnrichItems(
        rawItems: List<GeminiExtractedItemDto>,
        userDisplayName: String,
        priorItemsToAvoid: List<Pair<String, String>> = emptyList()
    ): List<GeminiExtractedItemDto> {
        val merged = mutableListOf<GeminiExtractedItemDto>()

        for (raw in rawItems) {
            val cleanTitle = raw.title.trim().replace(Regex("\\s+"), " ")
            if (cleanTitle.length < 3) continue

            val enriched = enrichSingleItem(raw.copy(title = cleanTitle), userDisplayName)
            val candTokens = tokenize("${enriched.title} ${enriched.details}")
            if (candTokens.isEmpty()) continue

            // Skip items that strictly repeat an already-persisted prior segment item in a chained event
            val repeatsPrior = priorItemsToAvoid.any { (priorPat, priorTitle) ->
                val patCompatible = priorPat.isBlank() || enriched.patientIdentifier.isNullOrBlank() ||
                    normalizePatientId(priorPat) == normalizePatientId(enriched.patientIdentifier)
                patCompatible && tokenJaccardSimilarity(tokenize(enriched.title), tokenize(priorTitle)) >= 0.78
            }
            if (repeatsPrior) continue

            val matchIdx = merged.indexOfFirst { existing ->
                itemsAreDuplicates(existing, enriched)
            }

            if (matchIdx >= 0) {
                merged[matchIdx] = mergeDuplicateItems(merged[matchIdx], enriched)
            } else {
                merged.add(enriched)
            }
        }

        return merged.sortedWith(
            compareByDescending<GeminiExtractedItemDto> { PRIORITY_RANK[it.priority.uppercase(Locale.US)] ?: 1 }
                .thenByDescending { TYPE_RANK[it.itemType.uppercase(Locale.US)] ?: 1 }
                .thenByDescending { it.isForUser }
        )
    }

    /**
     * Merges PatientBlocks referring to the same patient (e.g. across Batch 1 and Batch 2
     * in long recordings), deduplicating problems, labs, pending items, plans, and teaching pearls.
     */
    fun mergePatients(patients: List<GeminiPatientDto>): List<GeminiPatientDto> {
        if (patients.size <= 1) return patients
        val byKey = LinkedHashMap<String, GeminiPatientDto>()

        for (p in patients) {
            val key = normalizePatientId(p.patientIdentifier).ifBlank { p.patientIdentifier.lowercase(Locale.US) }
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = p
            } else {
                val mergedPlans = (existing.plan + p.plan)
                    .distinctBy { "${it.category.uppercase(Locale.US)}:${tokenize(it.text).sorted().joinToString("_")}" }
                byKey[key] = existing.copy(
                    patientIdentifier = if (p.patientIdentifier.length > existing.patientIdentifier.length) p.patientIdentifier else existing.patientIdentifier,
                    approxEnd = p.approxEnd,
                    oneLiner = pickRicherText(existing.oneLiner, p.oneLiner),
                    neuroExam = pickRicherNullable(existing.neuroExam, p.neuroExam),
                    assessment = combineDistinctSentences(existing.assessment, p.assessment),
                    problems = fuzzyDistinctStrings(existing.problems + p.problems),
                    resultsMentioned = fuzzyDistinctStrings(existing.resultsMentioned + p.resultsMentioned),
                    pending = fuzzyDistinctStrings(existing.pending + p.pending),
                    plan = mergedPlans,
                    teachingPoints = fuzzyDistinctStrings(existing.teachingPoints + p.teachingPoints)
                )
            }
        }
        return byKey.values.toList()
    }

    private fun enrichSingleItem(
        item: GeminiExtractedItemDto,
        userDisplayName: String
    ): GeminiExtractedItemDto {
        val combinedText = "${item.title} ${item.details} ${item.quoteOriginal} ${item.quoteEn.orEmpty()}"
        val currentRank = PRIORITY_RANK[item.priority.uppercase(Locale.US)] ?: 1
        val calibratedPriority = when {
            currentRank >= 3 || containsAny(combinedText, URGENT_TRIGGERS) -> "URGENT"
            currentRank >= 2 || containsAny(combinedText, HIGH_PRIORITY_TRIGGERS) -> "HIGH"
            else -> "NORMAL"
        }

        val calibratedVerify = item.needsVerification ||
            (item.itemType.uppercase(Locale.US) == "TASK" && isHighAlertMedicationWithDose(combinedText))

        val cleanUser = userDisplayName.trim()
        val assigneeTrim = item.assignee?.trim()?.takeIf { it.isNotBlank() }
        val resolvedIsForUser = item.isForUser || (assigneeTrim != null && (
            assigneeTrim.equals("me", ignoreCase = true) ||
                assigneeTrim.contains("(me)", ignoreCase = true) ||
                (cleanUser.length >= 2 && assigneeTrim.contains(cleanUser, ignoreCase = true))
            ))

        return item.copy(
            priority = calibratedPriority,
            needsVerification = calibratedVerify,
            assignee = assigneeTrim,
            isForUser = resolvedIsForUser
        )
    }

    private fun itemsAreDuplicates(a: GeminiExtractedItemDto, b: GeminiExtractedItemDto): Boolean {
        val patA = normalizePatientId(a.patientIdentifier.orEmpty())
        val patB = normalizePatientId(b.patientIdentifier.orEmpty())
        if (patA.isNotBlank() && patB.isNotBlank() && patA != patB) {
            return false
        }

        val titleSim = tokenJaccardSimilarity(tokenize(a.title), tokenize(b.title))
        if (titleSim >= 0.72) return true

        val fullSim = tokenJaccardSimilarity(
            tokenize("${a.title} ${a.details}"),
            tokenize("${b.title} ${b.details}")
        )
        return fullSim >= 0.74 || isSubstringMatch(a.title, b.title)
    }

    private fun mergeDuplicateItems(
        primary: GeminiExtractedItemDto,
        secondary: GeminiExtractedItemDto
    ): GeminiExtractedItemDto {
        val p1 = PRIORITY_RANK[primary.priority.uppercase(Locale.US)] ?: 1
        val p2 = PRIORITY_RANK[secondary.priority.uppercase(Locale.US)] ?: 1
        val t1 = TYPE_RANK[primary.itemType.uppercase(Locale.US)] ?: 1
        val t2 = TYPE_RANK[secondary.itemType.uppercase(Locale.US)] ?: 1

        val winnerTitle = if (secondary.title.length in 10..90 && secondary.title.length > primary.title.length) {
            secondary.title
        } else {
            primary.title
        }

        return primary.copy(
            itemType = if (t2 > t1) secondary.itemType else primary.itemType,
            title = winnerTitle,
            details = pickRicherText(primary.details, secondary.details),
            patientIdentifier = primary.patientIdentifier?.takeIf { it.isNotBlank() } ?: secondary.patientIdentifier,
            speakerLabel = if (primary.speakerLabel.startsWith("Speaker") && !secondary.speakerLabel.startsWith("Speaker")) {
                secondary.speakerLabel
            } else {
                primary.speakerLabel
            },
            assignee = primary.assignee?.takeIf { it.isNotBlank() } ?: secondary.assignee,
            isForUser = primary.isForUser || secondary.isForUser,
            due = primary.due?.takeIf { it.isNotBlank() } ?: secondary.due,
            priority = if (p2 > p1) secondary.priority else primary.priority,
            quoteOriginal = pickRicherText(primary.quoteOriginal, secondary.quoteOriginal),
            quoteEn = pickRicherNullable(primary.quoteEn, secondary.quoteEn),
            needsVerification = primary.needsVerification || secondary.needsVerification
        )
    }

    private fun formatOrderTitle(category: String, rawOrder: String): String {
        val trimmed = rawOrder.trim().replace(Regex("\\s+"), " ")
        val firstWord = trimmed.substringBefore(' ').lowercase(Locale.US)
        val hasActionVerb = firstWord in setOf(
            "order", "start", "stop", "hold", "continue", "switch", "increase", "decrease",
            "taper", "give", "administer", "check", "recheck", "repeat", "send", "draw",
            "consult", "notify", "inform", "schedule", "arrange", "prepare", "monitor",
            "wean", "discharge", "follow", "review", "obtain", "perform", "do"
        )
        val core = if (hasActionVerb) {
            trimmed.replaceFirstChar { it.uppercase() }
        } else {
            val prefix = when (category) {
                "MEDICATION" -> "Order/Adjust med: "
                "INVESTIGATION" -> "Order workup: "
                "CONSULT" -> "Consult: "
                "PROCEDURE" -> "Arrange procedure: "
                "MONITORING" -> "Monitor: "
                "DISCHARGE" -> "Plan discharge: "
                "FAMILY" -> "Update family: "
                else -> "Action: "
            }
            "$prefix$trimmed"
        }
        return core.take(88)
    }

    private fun cleanKeyPointText(raw: String): String {
        return raw.trim()
            .replace(Regex("^(?:[•\\-*]|\\d+[.)])\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun stripCategoryPrefix(text: String): String {
        return text.replace(Regex("^\\[[A-Za-z/\\s]+]\\s*"), "").trim()
    }

    private fun isGenericFillerKeyPoint(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return lower.startsWith("they discussed") && text.length < 28 ||
            lower == "none" ||
            lower == "n/a" ||
            lower == "no key points"
    }

    private fun scoreKeyPointRichness(text: String): Int {
        var score = text.length.coerceAtMost(160)
        if (text.startsWith("[")) score += 30
        if (DOSE_PATTERN.containsMatchIn(text)) score += 25
        if (text.contains("(") && text.contains(")")) score += 15 // speaker attribution or qualifier
        return score
    }

    private fun scoreKeyPointImportance(text: String): Int {
        val lower = text.lowercase(Locale.US)
        var score = 0
        if (lower.startsWith("[decision]")) score += 50
        if (lower.startsWith("[med")) score += 45
        if (lower.startsWith("[risk")) score += 45
        if (lower.startsWith("[action")) score += 40
        if (lower.startsWith("[lab") || lower.startsWith("[clinical")) score += 35
        if (containsAny(lower, URGENT_TRIGGERS)) score += 30
        if (DOSE_PATTERN.containsMatchIn(text)) score += 15
        return score + (text.length / 20).coerceAtMost(10)
    }

    private fun isHighAlertMedicationWithDose(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return containsAny(lower, HIGH_ALERT_MED_KEYWORDS) &&
            (DOSE_PATTERN.containsMatchIn(lower) || lower.contains("dose") || lower.contains("load") || lower.contains("drip"))
    }

    private fun containsAny(text: String, needles: List<String>): Boolean {
        val lower = text.lowercase(Locale.US)
        return needles.any { lower.contains(it) }
    }

    private fun normalizePatientId(raw: String): String {
        return raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\u0E00-\\u0E7F]"), "")
            .trim()
    }

    private fun tokenize(text: String): Set<String> {
        val normalized = text.lowercase(Locale.US)
            .replace(Regex("(\\d)\\s+(mg|mcg|g|ml|cc|iu|q\\d+h)\\b"), "$1$2")
            .replace(Regex("[^a-z0-9\\u0E00-\\u0E7F\\s]"), " ")
        return normalized.split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()
    }

    private fun tokenJaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }.toDouble()
        val union = (a.size + b.size - intersection)
        return if (union <= 0.0) 0.0 else intersection / union
    }

    private fun isSubstringMatch(a: String, b: String): Boolean {
        val normA = a.lowercase(Locale.US).replace(Regex("[^a-z0-9\\u0E00-\\u0E7F]"), "")
        val normB = b.lowercase(Locale.US).replace(Regex("[^a-z0-9\\u0E00-\\u0E7F]"), "")
        if (normA.length < 14 || normB.length < 14) return false
        return normA.contains(normB) || normB.contains(normA)
    }

    private fun fuzzyDistinctStrings(list: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (raw in list) {
            val clean = raw.trim()
            if (clean.isBlank()) continue
            val tok = tokenize(clean)
            val idx = out.indexOfFirst { tokenJaccardSimilarity(tok, tokenize(it)) >= 0.72 || isSubstringMatch(clean, it) }
            if (idx >= 0) {
                if (clean.length > out[idx].length) out[idx] = clean
            } else {
                out.add(clean)
            }
        }
        return out
    }

    private fun pickRicherText(a: String, b: String): String {
        return when {
            a.isBlank() -> b
            b.isBlank() -> a
            b.length > a.length -> b
            else -> a
        }
    }

    private fun pickRicherNullable(a: String?, b: String?): String? {
        return when {
            a.isNullOrBlank() -> b?.takeIf { it.isNotBlank() }
            b.isNullOrBlank() -> a
            b.length > a.length -> b
            else -> a
        }
    }

    private fun combineDistinctSentences(a: String, b: String): String {
        if (a.isBlank()) return b
        if (b.isBlank()) return a
        if (tokenJaccardSimilarity(tokenize(a), tokenize(b)) >= 0.70 || isSubstringMatch(a, b)) {
            return if (b.length > a.length) b else a
        }
        return "$a | $b"
    }
}
