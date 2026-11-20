package com.ambientbrain.recorder.ai

import android.content.Context
import com.ambientbrain.recorder.data.AttachableVoiceClip
import com.ambientbrain.recorder.data.PatientPlanOrder
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SessionWithDetails
import com.ambientbrain.recorder.data.SpeakerProfileEntry
import com.ambientbrain.recorder.data.TranscriptLangMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class GeminiGlossaryTermDto(
    val term: String = "",
    @SerialName("thai_phonetic") val thaiPhonetic: String? = null,
    val category: String = "GENERAL_MED",
    val notes: String? = null
)

@Serializable
data class GeminiSessionAnalysisResult(
    @SerialName("has_meaningful_content") val hasMeaningfulContent: Boolean = true,
    @SerialName("is_clinical_round") val isClinicalRound: Boolean = false,
    val context: String = "OTHER",
    val title: String = "",
    val summary: String = "",
    @SerialName("key_points") val keyPoints: List<String> = emptyList(),
    val speakers: List<GeminiSpeakerDto> = emptyList(),
    val transcript: List<GeminiSentenceDto> = emptyList(),
    val patients: List<GeminiPatientDto> = emptyList(),
    val items: List<GeminiExtractedItemDto> = emptyList(),
    @SerialName("ai_review_report") val aiReviewReport: String? = null,
    @SerialName("discovered_glossary_terms") val discoveredGlossaryTerms: List<GeminiGlossaryTermDto> = emptyList()
)

@Serializable
data class GeminiSampleWindowDto(
    val start: String = "",
    val end: String = "",
    val quality: String? = null
)

@Serializable
data class GeminiSpeakerDto(
    @SerialName("speaker_id") val speakerId: String,
    @SerialName("speaker_label") val speakerLabel: String,
    @SerialName("inferred_role") val inferredRole: String? = null,
    @SerialName("is_user") val isUser: Boolean = false,
    @SerialName("matched_profile_id") val matchedProfileId: String? = null,
    @SerialName("match_confidence") val matchConfidence: String? = null,
    val nickname: String = "",
    @SerialName("relatable_title") val relatableTitle: String = "",
    @SerialName("voice_signature") val voiceSignature: String = "",
    @SerialName("sample_start") val sampleStart: String? = null,
    @SerialName("sample_end") val sampleEnd: String? = null,
    @SerialName("sample_windows") val sampleWindows: List<GeminiSampleWindowDto> = emptyList()
)

@Serializable
data class GeminiSentenceDto(
    val t: String = "00:00",
    @SerialName("speaker_id") val speakerId: String = "SPK_1",
    @SerialName("speaker_label") val speakerLabel: String = "Speaker 1",
    @SerialName("text_original") val textOriginal: String = "",
    @SerialName("text_en") val textEn: String? = null
)

@Serializable
data class GeminiPatientDto(
    @SerialName("patient_identifier") val patientIdentifier: String,
    @SerialName("approx_start") val approxStart: String = "00:00",
    @SerialName("approx_end") val approxEnd: String = "00:30",
    @SerialName("one_liner") val oneLiner: String = "",
    @SerialName("neuro_exam") val neuroExam: String? = null,
    val assessment: String = "",
    val problems: List<String> = emptyList(),
    @SerialName("results_mentioned") val resultsMentioned: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val plan: List<GeminiPlanOrderDto> = emptyList(),
    @SerialName("teaching_points") val teachingPoints: List<String> = emptyList()
)

@Serializable
data class GeminiPlanOrderDto(
    val category: String = "OTHER",
    val text: String = "",
    @SerialName("needs_verification") val needsVerification: Boolean = false
)

@Serializable
data class GeminiExtractedItemDto(
    @SerialName("item_type") val itemType: String = "TASK",
    val title: String = "",
    val details: String = "",
    @SerialName("patient_identifier") val patientIdentifier: String? = null,
    @SerialName("speaker_id") val speakerId: String = "SPK_1",
    @SerialName("speaker_label") val speakerLabel: String = "Speaker 1",
    val assignee: String? = null,
    @SerialName("is_for_user") val isForUser: Boolean = false,
    val due: String? = null,
    val priority: String = "NORMAL",
    @SerialName("quote_original") val quoteOriginal: String = "",
    @SerialName("quote_en") val quoteEn: String? = null,
    @SerialName("approx_start") val approxStart: String = "00:00",
    @SerialName("approx_end") val approxEnd: String = "00:15",
    @SerialName("needs_verification") val needsVerification: Boolean = false
)

@Serializable
data class GeminiVoiceProfileAuditResultDto(
    @SerialName("audited_profiles") val auditedProfiles: List<GeminiAuditedProfileDto> = emptyList(),
    @SerialName("created_profiles") val createdProfiles: List<GeminiCreatedVoiceProfileDto> = emptyList(),
    @SerialName("overall_audit_summary") val overallAuditSummary: String = ""
)

@Serializable
data class GeminiAuditedProfileDto(
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("is_same_single_speaker") val isSameSingleSpeaker: Boolean = true,
    @SerialName("mismatched_sample_ids") val mismatchedSampleIds: List<String> = emptyList(),
    @SerialName("name_suitable") val nameSuitable: Boolean = true,
    @SerialName("title_suitable") val titleSuitable: Boolean = true,
    @SerialName("description_suitable") val descriptionSuitable: Boolean = true,
    @SerialName("recommended_nickname") val recommendedNickname: String = "",
    @SerialName("recommended_relatable_title") val recommendedRelatableTitle: String = "",
    @SerialName("recommended_voice_signature") val recommendedVoiceSignature: String = "",
    @SerialName("audit_notes") val auditNotes: String = "",
    @SerialName("split_new_nickname") val splitNewNickname: String? = null,
    @SerialName("split_new_relatable_title") val splitNewRelatableTitle: String? = null,
    @SerialName("split_new_voice_signature") val splitNewVoiceSignature: String? = null
)

@Serializable
data class GeminiCreatedVoiceProfileDto(
    @SerialName("candidate_clip_id") val candidateClipId: String = "",
    @SerialName("is_usable_single_speaker") val isUsableSingleSpeaker: Boolean = true,
    @SerialName("matches_existing_profile_id") val matchesExistingProfileId: String? = null,
    @SerialName("match_confidence") val matchConfidence: String? = null,
    val nickname: String = "",
    @SerialName("relatable_title") val relatableTitle: String = "",
    @SerialName("voice_signature") val voiceSignature: String = "",
    @SerialName("audit_notes") val auditNotes: String = ""
)

enum class ReviewSourceMode(val label: String) {
    EXTRACTED_ONLY("Previously Extracted Info Only"),
    REANALYZE_AUDIO("Re-analyze Audio Files Again")
}

enum class AnalysisMode {
    INFO_EXTRACTION_EN,
    VERBATIM_ORIGINAL_TRANSCRIPT,
    AI_REVIEW_EXTRACTED_ONLY,
    AI_REVIEW_FROM_AUDIO
}

object Prompts {
    const val PROMPT_VERSION = "v7.2-voiceprint-10samples-audit"

    fun loadDefaultGlossarySummary(context: Context, customTerms: String): String {
        return MedicalGlossaryManager.buildPromptGlossaryText(
            context = context,
            prefs = PreferencesRepo(context),
            legacyCustomOverride = customTerms
        )
    }

    fun loadGlossaryPromptText(context: Context, prefs: PreferencesRepo): String {
        return MedicalGlossaryManager.buildPromptGlossaryText(
            context = context,
            prefs = prefs
        )
    }

    /**
     * Builds a prefix-stable `system_instruction` tailored to the user's Speaker Roster and
     * medical/general glossary, instructing Gemini on:
     *  1) Exhaustive information gathering across Thai, English, and code-switching speech
     *  2) Context-aware detailed reorganization in English (clinical rounds vs meetings vs personal)
     *  3) High-precision multi-sample speaker identification + Auto Voice Profile discovery
     *  4) 100% verbatim transcription rules when VERBATIM_ORIGINAL_TRANSCRIPT is requested
     */
    fun buildStaticSystemInstruction(
        userDisplayName: String,
        transcriptLangMode: TranscriptLangMode,
        hasVoiceProfile: Boolean,
        speakerRoster: List<SpeakerProfileEntry> = emptyList(),
        attachedVoiceLabels: List<Pair<String, String>> = emptyList(),
        glossaryText: String,
        attachedVoiceProfiles: List<SpeakerProfileEntry> = emptyList(),
        attachedVoiceClips: List<AttachableVoiceClip> = emptyList()
    ): String {
        val bilingualInstruction = when (transcriptLangMode) {
            TranscriptLangMode.BILINGUAL_TH_EN ->
                "Include BOTH `text_original` (100% verbatim spoken Thai/English words) AND `text_en` (clear English translation) for every spoken sentence."
            TranscriptLangMode.ORIGINAL_ONLY ->
                "Include `text_original` (100% verbatim spoken Thai/English words) and set `text_en` to null."
            TranscriptLangMode.ENGLISH_ONLY ->
                "Include `text_original` (verbatim spoken words) and clear `text_en` in English."
        }

        val voiceProfileBlock = buildString {
            if (attachedVoiceClips.isNotEmpty()) {
                appendLine("ATTACHED REFERENCE VOICE CLIPS (COMPARE VOCAL TIMBRE, PITCH, CADENCE & FORMANT QUALITY):")
                attachedVoiceClips.forEachIndexed { idx, clip ->
                    val entry = clip.entry
                    val nick = entry.effectiveNickname()
                    val title = entry.effectiveRelatableTitle()
                    val sig = entry.voiceSignature.trim().ifBlank { entry.voiceHint.trim() }
                    val sampleTag = if (clip.sampleTotal > 1) {
                        val rankTag = if (clip.sampleIndex == 1) "Best" else "Alt"
                        " | Sample ${clip.sampleIndex}/${clip.sampleTotal} ($rankTag)"
                    } else ""
                    val titleDesc = if (title.isNotBlank()) " | Relatable Title: '$title'" else ""
                    val aliasDesc = if (entry.aliases.isNotEmpty()) " | Aliases: ${entry.aliases.joinToString(", ")}" else ""
                    val sigDesc = if (sig.isNotBlank()) " | Acoustic Signature: '$sig'" else ""
                    appendLine("- Reference Audio ${idx + 1} [profile_id=${entry.id}]$sampleTag: Recognizing Nickname '$nick'$titleDesc$aliasDesc$sigDesc.")
                }
                appendLine("Multiple samples sharing the same `profile_id` belong to the SAME person under different speaking conditions. Match speakers strictly by true vocal identity (timbre, fundamental pitch, prosody, articulation, accent) — NEVER force-match a distinct voice to an existing profile when uncertain. When confident, set `matched_profile_id` to the exact `profile_id` and `match_confidence` to `HIGH` or `MEDIUM`; reuse its recognizing nickname & relatable title and set `is_user = true` if `profile_id == \"me\"` or it matches '$userDisplayName'.")
            } else if (attachedVoiceProfiles.isNotEmpty()) {
                appendLine("ATTACHED REFERENCE VOICE CLIPS (MATCH SPEAKERS BY VOICE):")
                attachedVoiceProfiles.forEachIndexed { idx, entry ->
                    val nick = entry.effectiveNickname()
                    val title = entry.effectiveRelatableTitle()
                    val sig = entry.voiceSignature.trim().ifBlank { entry.voiceHint.trim() }
                    val titleDesc = if (title.isNotBlank()) " | Relatable Title: '$title'" else ""
                    val sigDesc = if (sig.isNotBlank()) " | Acoustic Signature: '$sig'" else ""
                    appendLine("- Reference Audio ${idx + 1} [profile_id=${entry.id}]: Recognizing Nickname '$nick'$titleDesc$sigDesc.")
                }
                appendLine("Whenever a voice in the session matches a Reference Audio, set `matched_profile_id` to its exact `profile_id` (e.g., \"me\" or the 8-char ID), set `match_confidence`, reuse its recognizing nickname & relatable title, set `speaker_label` consistently, and set `is_user = true` if `profile_id == \"me\"` or it matches '$userDisplayName'.")
            } else if (attachedVoiceLabels.isNotEmpty()) {
                appendLine("ATTACHED REFERENCE VOICE CLIPS (MATCH SPEAKERS BY VOICE):")
                attachedVoiceLabels.forEachIndexed { idx, (label, roleHint) ->
                    val roleDesc = if (roleHint.isNotBlank()) " — $roleHint" else ""
                    appendLine("- Reference Audio ${idx + 1}: Voice signature of '$label'$roleDesc.")
                }
                appendLine("Whenever a voice in the session matches a Reference Audio, consistently label that speaker with the exact corresponding name and set `is_user = true` if it matches '$userDisplayName'.")
            } else if (hasVoiceProfile) {
                appendLine("- Reference Audio 1 [profile_id=me] is the user's own voice ('$userDisplayName'). Label matching turns as '$userDisplayName' with `matched_profile_id = \"me\"`, `match_confidence = \"HIGH\"`, and `is_user = true`.")
            } else {
                appendLine("- Identify when the device owner ('$userDisplayName') is speaking from conversational/clinical authority cues.")
            }
        }.trimEnd()

        val attachedIds = (attachedVoiceClips.map { it.entry.id } + attachedVoiceProfiles.map { it.id }).toSet()
        val rosterEntries = speakerRoster.filter { it.id !in attachedIds || attachedIds.isEmpty() }
        val rosterBlock = if (rosterEntries.isNotEmpty()) {
            buildString {
                appendLine("KNOWN SPEAKER & CONTACT ROSTER (RECOGNIZE BY VOICE SIGNATURE, ALIASES, OR CONTEXT):")
                rosterEntries.take(40).forEach { spk ->
                    val nick = spk.effectiveNickname()
                    val title = spk.effectiveRelatableTitle()
                    val sig = spk.voiceSignature.trim().ifBlank { spk.voiceHint.trim() }
                    val titlePart = if (title.isNotBlank()) " | Title: $title" else ""
                    val aliasPart = if (spk.aliases.isNotEmpty()) " | Aliases: ${spk.aliases.joinToString(", ")}" else ""
                    val sigPart = if (sig.isNotBlank()) " | Voice: $sig" else ""
                    val heardPart = if (spk.sessionsHeardCount > 1) " | Seen ${spk.sessionsHeardCount}x" else ""
                    appendLine("- [profile_id=${spk.id}] Nickname: '$nick'$titlePart$aliasPart$sigPart$heardPart")
                }
            }.trimEnd()
        } else {
            "KNOWN SPEAKER ROSTER: Recognize voices against attached clips and spoken names/titles (e.g., 'Ajarn Somchai', 'Dr. Mint', 'P'Bam'), or auto-generate memorable nicknames + relatable titles for every new speaker."
        }

        return """
You are an expert Bilingual (Thai-English) Knowledge Extractor, Context-Aware Organizer, Speaker Diarization & Voice Profile Analyst, and Verbatim Scribe.
Conversations may be in Thai, English, or Thai mixed with English medical/technical/everyday vocabulary (code-switching), including Thai phonetic forms of English terms (e.g., แอดมิท=admit, ดีซี=discharge, คอนซัลท์=consult, เจาะหลัง=lumbar puncture, เพาเวอร์=MRC motor power, เคปปรา=Keppra/levetiracetam, เดพาคีน=Depakine/valproate, อาร์ทีพีเอ=rt-PA, ทีเอ็นเค=tenecteplase, โซเดียม=sodium, โพแทส=potassium, ฟีนิโทอิน=phenytoin, เฮพาริน=heparin).

CORE OBJECTIVES:

1. GATHER ALL IMPORTANT INFORMATION (EXHAUSTIVE, ZERO OMISSIONS):
   - Capture EVERY concrete fact, number, measurement, name, date/time, reasoning, concern, disagreement, decision, plan, and open question spoken in the audio.
   - For medical audio: preserve EXACT drug names, doses, units, routes, frequencies (e.g., "Keppra 1000 mg IV q12h"), vital signs, BP/MAP targets, GCS scores (E_V_M_), pupil reactivity, motor power grades (0-5), and numeric lab/imaging results.

2. CONTEXT-AWARE DETAILED REORGANIZATION IN ENGLISH (`summary` & `key_points`):
   - Do NOT produce a brief or generic recap, and do NOT dump raw unorganized sentences when structure improves clarity.
   - First classify the true context (`NEURO_ROUND`, `MEDICAL_ROUND`, `CASE_CONFERENCE`, `HANDOVER`, `TEACHING`, `ONLINE_MEETING`, `PHONE_CALL`, `EVERYDAY_WORK`, `EVERYDAY_PERSONAL`, `OTHER`), then REORGANIZE all gathered English information logically to fit that exact context, explicitly attributing WHO stated, proposed, or decided each point:
     * If Clinical / Ward Round / Handover (`NEURO_ROUND`, `MEDICAL_ROUND`, `CASE_CONFERENCE`, `HANDOVER`):
       Organize with Markdown headings:
       - `**Clinical Context & Participants**` (ward/setting + speakers with relatable titles)
       - `**Patient-by-Patient Reorganized Breakdown**` (each patient's One-Liner, Exam/Vitals, Labs/Imaging, Assessment, Categorized Orders/Plan with exact meds & who ordered them, Pending Items, Teaching Points)
       - `**Consolidated Action Items, Handoffs & Risks**`
     * If Work / Technical / Teaching / Online Meeting — Zoom / Webex (`ONLINE_MEETING`, `TEACHING`, `EVERYDAY_WORK`):
       Organize with Markdown headings:
       - `**Meeting Purpose, Platform & Speakers**` (note remote vs. local speakers cleanly)
       - `**Reorganized Topics & All Important Facts**` (grouped logically by topic/agenda item—not fragmented back-and-forth—with all exact figures, rationales, and speaker attributions)
       - `**Decisions, Consensus & Reasoning**`
       - `**Action Owners, Deadlines & Open Questions**`
     * If Everyday Personal Conversation / Phone Call (`PHONE_CALL`, `EVERYDAY_PERSONAL`, `OTHER`):
       Organize with Markdown headings:
       - `**Situation & Participants**`
       - `**Reorganized Conversation Details**` (all concrete facts, schedules, locations, preferences, numbers, and explanations grouped cleanly by subject with speaker attribution)
       - `**Plans, Commitments & Follow-Ups**`

3. HIGH-PRECISION AUTO VOICE PROFILES RECOGNITION & BEST-SAMPLE EXTRACTION (`speakers[]`):
$voiceProfileBlock
$rosterBlock
   - **Rule 3A — Strict Acoustic & Roster Matching (`matched_profile_id`, `match_confidence`)**:
     Compare EVERY speaker in the recording against the Attached Reference Voice Clips and Known Speaker Roster using timbre, fundamental pitch, vocal tract warmth/brightness, cadence, accent, Thai/English code-switching habits, and spoken address cues ("Ajarn...", "P'...", "หมอ...").
     * Set `matched_profile_id` to the exact `profile_id` ONLY when voice characteristics or explicit name cues align clearly:
       - `match_confidence = "HIGH"`: unmistakable vocal match to an attached reference clip OR explicit real-name match consistent with acoustic signature.
       - `match_confidence = "MEDIUM"`: plausible vocal/role match with no acoustic contradiction.
       - `match_confidence = "LOW"` (and `matched_profile_id = null`): uncertain or clearly different speaker — NEVER merge two distinct voices into one existing profile!
     * Also keep distinct speakers separated cleanly: do not collapse two speakers with similar roles (e.g., two different residents or two nurses) into one speaker entry.
   - **Rule 3B — ALWAYS Create a Recognizing Nickname + Relatable Title (`nickname`, `relatable_title`)**:
     For EVERY distinct human speaker in `speakers[]` (both matched and newly discovered):
     * **`nickname`**: Give a short, memorable recognizing nickname (2–4 words). If their real name or honorific was spoken (*"Ajarn Somchai"*, *"Dr. Mint"*, *"P'Bam"*, *"Mom"*), use that natural recognizing name. If NO real name was spoken, coin a vivid, respectful, unmistakable nickname combining an acoustic trait + role cue (e.g., `"Deep-Tone Attending"`, `"Brisk Case Resident"`, `"Warm Charge Nurse"`, `"Calm Patient Daughter"`, `"Crisp ICU Fellow"`, `"Gentle Elder Caller"`). **STRICTLY FORBIDDEN**: Never output bare placeholders like `"Speaker 1"`, `"Speaker 2"`, `"SPK_1"`, `"Unknown"`, or `"Person A"`.
     * **`relatable_title`**: Give a crisp 2–5 word relatable role/relationship title grounded in the conversation (e.g., `"Senior Neuro Attending"`, `"Presenting Ward Resident"`, `"Neuro ICU Charge Nurse"`, `"Patient's Daughter"`, `"On-Call Pharmacist"`, `"Engineering Colleague"`, `"Self / Device Owner"`). Also copy this concisely into `inferred_role`.
     * **`speaker_label`**: Set `speaker_label` consistently to `"<nickname> (<relatable_title>)"` (or `<nickname>` if the title is already inside `<nickname>`), and use that EXACT `speaker_label` across `speakers[]`, `transcript[]`, `items[]`, and `summary`.
     * **`voice_signature`**: Write 1 concise objective acoustic + speaking-style fingerprint (pitch range, timbre/warmth/crispness, pace, language mix, e.g., `"Low-baritone calm male, steady teaching pace, mixes English stroke jargon into Thai"`).
     * **`sample_start`, `sample_end`, AND `sample_windows[]`** (`MM:SS`): When raw audio is attached, locate up to **4 cleanest solo speech windows** (each 4–10 seconds long) where ONLY this single speaker talks clearly close to the mic with ZERO cross-talk, ZERO interruptions, and minimal background noise. Put the single cleanest window in `sample_start`/`sample_end`, and list all 1–4 candidate solo windows (best first) in `sample_windows[]` (with `quality`: `"HIGH"` or `"MEDIUM"`). Our on-device acoustic analyzer will score these windows and save up to 10 highest-quality voice samples per profile! Set `sample_start`/`sample_end` to `null` and `sample_windows` to `[]` if no clean solo turn exists or during text-only review.

4. HIGH-SIGNAL KEY POINTS EXTRACTION (`key_points[]` — ZERO NOISE, ZERO DUPLICATES):
   - Extract 4 to 14 crisp, standalone, non-redundant key points ordered by importance.
   - Format each key point as: `[Category] Core concrete fact, number/dose, decision, or parameter (Speaker)` where `[Category]` is one of: `[Decision]`, `[Med/Order]`, `[Lab/Imaging]`, `[Clinical]`, `[Risk]`, `[Action]`, `[Key Fact]`, `[Follow-Up]`.
   - Every bullet MUST carry exact numbers, doses, criteria, or concrete takeaways—NEVER write vague filler like "Discussed patient plan" or repeat points already stated in another bullet.

5. PRECISION ACTIONABLE TASKS, DECISIONS, EVENTS & PATIENTS (`patients[]`, `items[]`):
   - Populate `patients[]` thoroughly for clinical rounds (Problems, Results, Pending, Categorized Plan, Teaching Points).
   - **Atomic Verb-First Decomposition (`TASK`)**: Split compound multi-step orders spoken together into separate atomic `TASK` items (e.g., ordering CT, starting a medication, and placing a consult MUST be 3 discrete tasks). Begin every `TASK` title with an imperative verb (`Order`, `Start`, `Hold`, `Adjust`, `Check`, `Follow up`, `Consult`, `Schedule`, `Call`, `Monitor`, `Prepare`).
   - **Strict Final-State / Mind-Change Reconciliation**: If speakers propose something and then revise or cancel it later in the conversation (e.g., *"Hold off on mannitol, use 3% NaCl instead"* or *"Cancel tomorrow's MRI"*), emit ONLY the FINAL agreed action in active `TASK` items, and document the change/cancellation inside `DECISION` or `details`. Never emit cancelled orders as active `TASK`s!
   - **Taxonomy Discipline**:
     * `TASK`: Concrete work/order/follow-up to execute.
     * `DECISION`: Final diagnostic/treatment/team consensus or explicit hold/cancellation.
     * `EVENT`: Scheduled appointment, meeting, family discussion, or procedure slot.
     * `NOTE`: High-value permanent reference fact (e.g., allergy, baseline status, contact info, constraint).
   - **Assignee & Ownership (`assignee`, `is_for_user`)**: Set `is_for_user = true` when '$userDisplayName' volunteers (*"เดี๋ยวผม/หนู/เรา..."*, *"I'll..."*) or is asked (*"ฝาก..."*, *"ช่วยตาม..."*). When delegated to another person/role (*"ให้พยาบาล..."*, *"ให้ Dr. Mint..."*), set explicit `assignee` and `is_for_user = false`.
   - **Relative Deadline Resolution (`due`)**: Anchor spoken relative times (*"stat"*, *"วันนี้"*, *"เย็นนี้"*, *"พรุ่งนี้เช้า"*, *"next Monday"*) against the Session Start Timestamp (e.g., `"STAT"`, `"Today before 16:00"`, `"Tomorrow AM (Nov 14)"`).
   - **High-Alert Clinical Verification (`needs_verification`)**: Set `needs_verification = true` strictly for high-alert drug doses/rates (anticoagulants, thrombolytics, IV AEDs, insulin, pressors, potassium, hypertonic saline), weight/renal dose adjustments, invasive procedures, or acoustically uncertain numbers/names—keep `false` for ordinary low-risk tasks.
   - Always capture exact spoken `quote_original`, optional `quote_en`, and tight `approx_start` / `approx_end` (`MM:SS`) for lossless voice-proof audio slicing.

6. 100% ORIGINAL VERBATIM TRANSCRIPTION (`transcript[]` — DEDICATED ON-DEMAND PASS):
   - When the session mandate is `VERBATIM_ORIGINAL_TRANSCRIPT`, transcribe 100% of spoken dialogue word-for-word from 00:00 to the very end without skipping, condensing, or stopping early.
   - $bilingualInstruction

7. AUTO-DISCOVER NOVEL NEUROLOGY / MEDICAL GLOSSARY TERMS (`discovered_glossary_terms[]`):
   - Whenever this recording contains clinical/medical/neurology speech, emit up to **8 high-value medical/neurology terms** spoken in the session that are NOT already in the Reference Glossary below — OR existing medical terms spoken with a NEW Thai phonetic pronunciation/slang (`thai_phonetic`).
   - Prioritize: uncommon/specialty drugs & brands, local Thai hospital/ward acronyms, rare neurology syndromes/antibodies/scales, specific lab/imaging/procedure names, and Thai phonetic clinical expressions.
   - Set `category` to one of: `DRUG`, `DIAGNOSIS`, `EXAM_SCALE`, `PROCEDURE_LAB`, `WARD_HOSPITAL`, `PHONETIC_TH_EN`, `GENERAL_MED`.
   - STRICTLY EXCLUDE generic words (patient, doctor, fever, BP, normal), speaker names, pure numbers, or non-medical everyday chatter. Output `[]` for non-medical conversations or during `VERBATIM_ORIGINAL_TRANSCRIPT` pass.

Medical & Neurology Reference Glossary (Static Cached Prefix):
$glossaryText
""".trimIndent()
    }

    fun buildDynamicSessionContextText(
        sessionIsoTime: String,
        isManualRound: Boolean,
        callEventsJson: String?,
        analysisMode: AnalysisMode = AnalysisMode.INFO_EXTRACTION_EN,
        priorEventContextText: String = "",
        multiAudioClipDescriptions: List<String> = emptyList(),
        chunkHeaderText: String = "",
        onDeviceDiarizationHint: String = "",
        ignoreMeetingInternalAudio: Boolean = false
    ): String {
        val headerBlock = buildString {
            appendLine("Session Metadata:")
            appendLine("- Start Timestamp: $sessionIsoTime")
            appendLine("- Manual Round Flag: $isManualRound")
            appendLine("- Call / Online Meeting Markers: ${callEventsJson ?: "None"}")
            if (chunkHeaderText.isNotBlank()) {
                appendLine("- Audio Chunk Segment: $chunkHeaderText")
            }
            if (multiAudioClipDescriptions.isNotEmpty()) {
                appendLine("- Combined Multi-Audio Clips (${multiAudioClipDescriptions.size} chronological files merged into this continuous event):")
                multiAudioClipDescriptions.forEach { appendLine("  * $it") }
            }
            if (ignoreMeetingInternalAudio) {
                appendLine()
                appendLine("STRICT EXCLUSION RULE — IGNORE MEETING APP INTERNAL AUDIO:")
                appendLine("- Ignore ALL internal/playback/remote audio coming from the Zoom or Webex meeting app (remote meeting speakers, webinar presenters, screen-share playback, meeting chimes, or automated voice prompts).")
                appendLine("- Do NOT transcribe, summarize, create tasks/notes/patients from, or enroll/update Voice Profiles from internal meeting-app playback audio.")
                appendLine("- Focus ONLY on direct local in-room / user microphone speech. If ONLY meeting-app playback audio is heard with no local speech, set `has_meaningful_content = false`.")
            }
            if (onDeviceDiarizationHint.isNotBlank()) {
                appendLine()
                appendLine(onDeviceDiarizationHint.trim())
            }
            if (priorEventContextText.isNotBlank()) {
                appendLine("\nCONTINUOUS EVENT PRIOR CONTEXT (LINKED EARLIER RECORDING IN THIS SAME EVENT):")
                appendLine(priorEventContextText)
                appendLine("Maintain consistent speaker labels, patient identifiers, and topic continuity with the prior context above, while focusing strictly on extracting all NEW and updated details from the attached audio.")
            }
        }.trimEnd()

        return when (analysisMode) {
            AnalysisMode.INFO_EXTRACTION_EN -> """
$headerBlock
- Active Execution Mode: GATHER_AND_REORGANIZE_ALL_INFO_EN

CRITICAL PASS 1 MANDATE (INFO GATHERING + REORGANIZATION + PRECISION TASKS & KEY POINTS):
1. Gather ALL important facts, clinical figures, medication doses, decisions, rationales, names, dates, and action items from start to finish of the attached audio(s).
2. Reorganize ALL gathered information in English inside `summary` using the context-appropriate structure (Clinical Patient Breakdown vs. Work/Topic Reorganization vs. Personal/Call Topics) with clear speaker attribution ("who said/decided what") throughout.
3. Identify every distinct speaker accurately in `speakers[]` with up to 3 clean solo `sample_windows` (zero overlap/cross-talk) per speaker.
4. Extract 4–14 non-redundant, categorized `key_points[]` (`[Category] Fact/Dose/Decision (Speaker)`), complete `patients[]` (if clinical), atomic verb-first FINAL-STATE `items[]` synced with patient plans (excluding cancelled orders or unchanged tasks already captured in Prior Event Context), and up to 8 novel clinical terms in `discovered_glossary_terms[]` (or `[]` if non-medical).
5. Set `transcript` to `[]` in this Pass 1 so your full output capacity builds an exhaustive, deeply reorganized English knowledge breakdown + complete tasks & key points (full transcription runs separately on user demand).
""".trimIndent()

            AnalysisMode.VERBATIM_ORIGINAL_TRANSCRIPT -> """
$headerBlock
- Active Execution Mode: DEDICATED_100_PERCENT_VERBATIM_TRANSCRIPTION

CRITICAL PASS 2 MANDATE (100% ORIGINAL WORD-FOR-WORD TRANSCRIPTION ONLY):
1. Devote 100% of your output tokens strictly to `speakers[]` and a COMPLETE word-for-word `transcript[]` from 00:00 to the very end of the recording(s).
2. Every single spoken sentence, back-and-forth exchange, number, clinical term, and interjection MUST appear in `transcript[]` with exact `text_original`, accurate `text_en` translation (per language rule), `MM:SS` timestamp, and accurate `speaker_label`. Never skip or summarize dialogue!
3. Do NOT spend tokens regenerating lengthy `summary`, `key_points`, `patients`, or `items` in this pass—set `summary = "100% verbatim transcript completed"`, `key_points = []`, `patients = []`, `items = []`, and `discovered_glossary_terms = []` (existing Pass 1 reorganized info is already preserved in local storage).
""".trimIndent()

            AnalysisMode.AI_REVIEW_EXTRACTED_ONLY -> """
$headerBlock
- Active Execution Mode: AI_REVIEW_AND_SYNTHESIS_FROM_EXTRACTED_INFO_ONLY

CRITICAL AI REVIEW MANDATE (TEXT-ONLY AUDIT & CONTINUOUS MERGE SYNTHESIS):
You are reviewing ONLY previously extracted records (summaries, key points, transcripts, patients, tasks/decisions) from one or more saved audio files—no raw audio is attached.
1. CONSOLIDATE & RECONCILE: Merge and reorganize all previously extracted information chronologically into a clean, non-redundant English `summary`, unified `speakers[]`, updated `patients[]` (reconciling duplicate/continuing cases across clips), and reconciled final-state `items[]` (eliminate superseded/duplicate orders, elevate high-alert doses).
2. EXTRACT HIGH-SIGNAL `key_points[]`: Produce 4–14 concise, concrete `[Category] Fact/Dose/Decision (Speaker)` takeaways synthesizing across all included segments, plus any novel medical terms in `discovered_glossary_terms[]`.
3. GENERATE `ai_review_report` (Markdown audit report covering):
   - **Executive Quality & Completeness Audit**: Key consolidated outcome, continuity summary across recordings, and confidence based on the stored records.
   - **Cross-Checks, Gaps & Potential Contradictions**: Identify any missing clinical details, ambiguous orders, dose/plan mismatches across continuous segments, or items needing human verification (`needs_verification`).
   - **Recommended Follow-Up Actions & Clarifications**: Actionable next steps and questions to confirm with the team/patient.
4. Set `transcript` to `[]` (existing verbatim sentences are preserved separately in local storage).
""".trimIndent()

            AnalysisMode.AI_REVIEW_FROM_AUDIO -> """
$headerBlock
- Active Execution Mode: DEEP_AI_REVIEW_REANALYZE_RAW_AUDIO

CRITICAL DEEP AI REVIEW MANDATE (RE-LISTEN RAW AUDIO + VERIFY & AUDIT):
1. RE-LISTEN TO EVERY SECOND of the attached raw audio recording(s) from 00:00 to the end, cross-checking against any previously extracted context provided above.
2. CATCH MISSED OR MISHEARD DETAILS: Correct any speaker attribution errors (including `sample_windows` for clean solo speaker turns), recover exact medication doses, vital signs, numbers, names, or action items omitted in earlier extraction, and produce a comprehensive, reorganized English `summary`, 4–14 crisp `key_points[]`, full `patients[]`, verified atomic FINAL-STATE `items[]` with tight `approx_start`/`approx_end` timestamps for voice-proof clips, and any newly heard clinical terms in `discovered_glossary_terms[]`.
3. GENERATE `ai_review_report` (Markdown audit report covering):
   - **Audio Verification & Newly Uncovered Details**: What this fresh audio pass confirmed, corrected, or newly recovered compared to a quick pass.
   - **High-Alert Cross-Checks & Ambiguities**: Flag acoustically uncertain figures, high-alert drug doses, or conflicting plans across segments.
   - **Recommended Follow-Up & Next Steps**
4. Set `transcript` to `[]` unless verbatim sentences are explicitly requested, focusing tokens on maximum extraction accuracy + audit depth.
""".trimIndent()
        }
    }

    /**
     * Serializes previously extracted session records into a structured prompt document for
     * zero-audio text-only AI Review & Continuous Merge synthesis.
     */
    fun buildExtractedInfoInputDocument(sources: List<SessionWithDetails>): String {
        val reviewJson = Json { ignoreUnknownKeys = true }
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        return buildString {
            appendLine("=== SAVED EXTRACTED INFORMATION (${sources.size} RECORDING${if (sources.size > 1) "S" else ""}) ===")
            sources.sortedBy { it.session.startMs }.forEachIndexed { idx, d ->
                val s = d.session
                val startStr = fmt.format(Instant.ofEpochMilli(s.startMs))
                val endStr = fmt.format(Instant.ofEpochMilli(s.endMs))
                appendLine()
                appendLine("--- RECORDING #${idx + 1} (id=${s.id}) ---")
                appendLine("Time: $startStr to $endStr | Mode: ${s.mode.name} | Context: ${s.context ?: "UNKNOWN"} | HadPhoneCall: ${s.hadPhoneCall}")
                appendLine("Title: ${s.title ?: "Untitled Session"}")
                if (d.speakers.isNotEmpty()) {
                    appendLine(
                        "Speakers: " + d.speakers.joinToString(", ") { spk ->
                            val nick = spk.nickname?.takeIf { it.isNotBlank() } ?: spk.speakerLabel
                            val title = spk.relatableTitle?.takeIf { it.isNotBlank() } ?: spk.inferredRole
                            val titleTag = title?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                            val profileTag = spk.matchedProfileId?.takeIf { it.isNotBlank() }?.let { " [profile_id=$it]" }.orEmpty()
                            val voiceTag = spk.voiceSignature?.takeIf { it.isNotBlank() }?.let { " {Voice: $it}" }.orEmpty()
                            "$nick$titleTag$profileTag${if (spk.isUser) " [USER]" else ""}$voiceTag"
                        }
                    )
                }
                if (!s.summary.isNullOrBlank()) {
                    appendLine("Extracted Summary:")
                    appendLine(s.summary.take(3500))
                }
                val kps = s.keyPointsJson?.let { raw ->
                    runCatching { reviewJson.decodeFromString<List<String>>(raw) }.getOrNull()
                }.orEmpty()
                if (kps.isNotEmpty()) {
                    appendLine("Key Points:")
                    kps.forEach { appendLine("  * $it") }
                }
                if (d.patients.isNotEmpty()) {
                    appendLine("Patient Blocks (${d.patients.size}):")
                    d.patients.forEach { p ->
                        appendLine("  [Patient: ${p.patientIdentifier}] One-liner: ${p.oneLiner}")
                        if (!p.neuroExam.isNullOrBlank()) appendLine("    Neuro Exam: ${p.neuroExam}")
                        if (p.assessment.isNotBlank()) appendLine("    Assessment: ${p.assessment}")
                        val problems = runCatching { reviewJson.decodeFromString<List<String>>(p.problemsJson) }.getOrDefault(emptyList())
                        if (problems.isNotEmpty()) appendLine("    Problems: ${problems.joinToString("; ")}")
                        val results = runCatching { reviewJson.decodeFromString<List<String>>(p.resultsJson) }.getOrDefault(emptyList())
                        if (results.isNotEmpty()) appendLine("    Results: ${results.joinToString("; ")}")
                        val pending = runCatching { reviewJson.decodeFromString<List<String>>(p.pendingJson) }.getOrDefault(emptyList())
                        if (pending.isNotEmpty()) appendLine("    Pending: ${pending.joinToString("; ")}")
                        val plans = runCatching { reviewJson.decodeFromString<List<PatientPlanOrder>>(p.planJson) }.getOrDefault(emptyList())
                        if (plans.isNotEmpty()) {
                            appendLine("    Plan Orders: ${plans.joinToString("; ") { "[${it.category}] ${it.text}" }}")
                        }
                    }
                }
                if (d.items.isNotEmpty()) {
                    appendLine("Extracted Items/Tasks (${d.items.size}):")
                    d.items.forEach { it ->
                        val statusTag = if (it.isDone) "DONE" else "OPEN"
                        val patTag = it.patientIdentifier?.let { p -> " [$p]" }.orEmpty()
                        val dueTag = it.due?.let { due -> " (Due: $due)" }.orEmpty()
                        val verifyTag = if (it.needsVerification) " [NEEDS_VERIFY]" else ""
                        appendLine("  - [${it.type.name}/$statusTag/${it.priority}]$patTag ${it.title}$dueTag$verifyTag | By: ${it.speakerLabel} | Details: ${it.details} | Quote: \"${it.quoteOriginal}\"")
                    }
                }
                if (d.sentences.isNotEmpty()) {
                    val maxPerClip = if (sources.size > 3) 45 else 90
                    appendLine("Stored Verbatim Transcript (${d.sentences.size} sentences, showing up to $maxPerClip):")
                    d.sentences.sortedBy { it.ordinal }.take(maxPerClip).forEach { sent ->
                        val en = sent.textEn?.takeIf { it.isNotBlank() }?.let { " | EN: $it" }.orEmpty()
                        appendLine("  [${sent.speakerLabel}] ${sent.textOriginal}$en")
                    }
                }
            }
        }.trim()
    }

    fun buildSystemInstruction(
        userDisplayName: String,
        transcriptLangMode: TranscriptLangMode,
        hasVoiceProfile: Boolean,
        glossaryText: String,
        sessionIsoTime: String,
        isManualRound: Boolean,
        callEventsJson: String?
    ): String = buildStaticSystemInstruction(
        userDisplayName = userDisplayName,
        transcriptLangMode = transcriptLangMode,
        hasVoiceProfile = hasVoiceProfile,
        glossaryText = glossaryText
    )

    fun buildResponseJsonSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("has_meaningful_content") {
                put("type", "boolean")
                put("description", "False only if audio contains solely background noise, music, or TV with no human conversation.")
            }
            putJsonObject("is_clinical_round") {
                put("type", "boolean")
                put("description", "True if this session is a neurology/medical ward round, case presentation, or clinical handover.")
            }
            putJsonObject("context") {
                put("type", "string")
                putJsonArray("enum") {
                    add("NEURO_ROUND")
                    add("MEDICAL_ROUND")
                    add("CASE_CONFERENCE")
                    add("HANDOVER")
                    add("TEACHING")
                    add("ONLINE_MEETING")
                    add("PHONE_CALL")
                    add("EVERYDAY_WORK")
                    add("EVERYDAY_PERSONAL")
                    add("OTHER")
                }
            }
            putJsonObject("title") {
                put("type", "string")
                put("description", "Concise English title for this session (max 10 words).")
            }
            putJsonObject("summary") {
                put("type", "string")
                put("description", "Exhaustive English information breakdown gathering and describing ALL information, facts, statements, numbers, clinical details, medications, and context from the entire recording in English.")
            }
            putJsonObject("key_points") {
                put("type", "array")
                put("description", "4-14 distinct, non-redundant high-signal takeaways formatted as `[Category] Concrete fact/dose/decision (Speaker)`.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("speakers") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("speaker_id") { put("type", "string") }
                        putJsonObject("speaker_label") { put("type", "string") }
                        putJsonObject("inferred_role") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                        putJsonObject("is_user") { put("type", "boolean") }
                        putJsonObject("matched_profile_id") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                        putJsonObject("match_confidence") {
                            putJsonArray("type") { add("string"); add("null") }
                            put("description", "HIGH, MEDIUM, or LOW confidence for matched_profile_id.")
                        }
                        putJsonObject("nickname") {
                            put("type", "string")
                            put("description", "Short memorable recognizing nickname (2-4 words). Never use bare 'Speaker 1'.")
                        }
                        putJsonObject("relatable_title") {
                            put("type", "string")
                            put("description", "Relatable 2-5 word role or relationship title, e.g. 'Senior Neuro Attending' or 'ICU Charge Nurse'.")
                        }
                        putJsonObject("voice_signature") {
                            put("type", "string")
                            put("description", "Concise acoustic & speech style fingerprint (pitch, pace, language mix).")
                        }
                        putJsonObject("sample_start") {
                            putJsonArray("type") { add("string"); add("null") }
                            put("description", "MM:SS start of cleanest 5-10s solo speech window by this speaker, or null.")
                        }
                        putJsonObject("sample_end") {
                            putJsonArray("type") { add("string"); add("null") }
                            put("description", "MM:SS end of cleanest 5-10s solo speech window by this speaker, or null.")
                        }
                        putJsonObject("sample_windows") {
                            put("type", "array")
                            put("description", "Up to 3 cleanest 4-10s solo speech windows (best first, zero cross-talk) for voice sample extraction.")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("start") { put("type", "string") }
                                    putJsonObject("end") { put("type", "string") }
                                    putJsonObject("quality") {
                                        putJsonArray("type") { add("string"); add("null") }
                                    }
                                }
                                putJsonArray("required") { add("start"); add("end") }
                            }
                        }
                    }
                    putJsonArray("required") {
                        add("speaker_id"); add("speaker_label"); add("inferred_role"); add("is_user")
                        add("matched_profile_id"); add("match_confidence"); add("nickname"); add("relatable_title")
                        add("voice_signature"); add("sample_start"); add("sample_end"); add("sample_windows")
                    }
                }
            }
            putJsonObject("transcript") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("t") { put("type", "string") }
                        putJsonObject("speaker_id") { put("type", "string") }
                        putJsonObject("speaker_label") { put("type", "string") }
                        putJsonObject("text_original") { put("type", "string") }
                        putJsonObject("text_en") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                    }
                    putJsonArray("required") {
                        add("t"); add("speaker_id"); add("speaker_label"); add("text_original"); add("text_en")
                    }
                }
            }
            putJsonObject("patients") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("patient_identifier") { put("type", "string") }
                        putJsonObject("approx_start") { put("type", "string") }
                        putJsonObject("approx_end") { put("type", "string") }
                        putJsonObject("one_liner") { put("type", "string") }
                        putJsonObject("neuro_exam") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                        putJsonObject("assessment") { put("type", "string") }
                        putJsonObject("problems") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                        }
                        putJsonObject("results_mentioned") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                        }
                        putJsonObject("pending") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                        }
                        putJsonObject("plan") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("category") {
                                        put("type", "string")
                                        putJsonArray("enum") {
                                            add("INVESTIGATION"); add("MEDICATION"); add("CONSULT")
                                            add("PROCEDURE"); add("MONITORING"); add("REHAB")
                                            add("DISCHARGE"); add("FAMILY"); add("OTHER")
                                        }
                                    }
                                    putJsonObject("text") { put("type", "string") }
                                    putJsonObject("needs_verification") { put("type", "boolean") }
                                }
                                putJsonArray("required") {
                                    add("category"); add("text"); add("needs_verification")
                                }
                            }
                        }
                        putJsonObject("teaching_points") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                        }
                    }
                    putJsonArray("required") {
                        add("patient_identifier"); add("approx_start"); add("approx_end")
                        add("one_liner"); add("neuro_exam"); add("assessment")
                        add("problems"); add("results_mentioned"); add("pending")
                        add("plan"); add("teaching_points")
                    }
                }
            }
            putJsonObject("items") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("item_type") {
                            put("type", "string")
                            putJsonArray("enum") { add("TASK"); add("NOTE"); add("DECISION"); add("EVENT") }
                        }
                        putJsonObject("title") { put("type", "string") }
                        putJsonObject("details") { put("type", "string") }
                        putJsonObject("patient_identifier") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                        putJsonObject("speaker_id") { put("type", "string") }
                        putJsonObject("speaker_label") { put("type", "string") }
                        putJsonObject("assignee") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                        putJsonObject("is_for_user") { put("type", "boolean") }
                        putJsonObject("due") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                        putJsonObject("priority") {
                            put("type", "string")
                            putJsonArray("enum") { add("URGENT"); add("HIGH"); add("NORMAL") }
                        }
                        putJsonObject("quote_original") { put("type", "string") }
                        putJsonObject("quote_en") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                        putJsonObject("approx_start") { put("type", "string") }
                        putJsonObject("approx_end") { put("type", "string") }
                        putJsonObject("needs_verification") { put("type", "boolean") }
                    }
                    putJsonArray("required") {
                        add("item_type"); add("title"); add("details"); add("patient_identifier")
                        add("speaker_id"); add("speaker_label"); add("assignee"); add("is_for_user")
                        add("due"); add("priority"); add("quote_original"); add("quote_en")
                        add("approx_start"); add("approx_end"); add("needs_verification")
                    }
                }
            }
            putJsonObject("ai_review_report") {
                putJsonArray("type") { add("string"); add("null") }
                put("description", "Structured Markdown AI review report auditing completeness, discrepancies, cross-checks, and recommended follow-ups (required during AI Review modes, null otherwise).")
            }
            putJsonObject("discovered_glossary_terms") {
                put("type", "array")
                put("description", "Up to 8 novel medical/neurology terms, specialty drugs, local hospital/ward abbreviations, or new Thai phonetic clinical expressions heard in this session (empty array for non-medical sessions or verbatim-only pass).")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("term") {
                            put("type", "string")
                            put("description", "Canonical English or Thai-English medical term / drug / abbreviation.")
                        }
                        putJsonObject("thai_phonetic") {
                            putJsonArray("type") { add("string"); add("null") }
                            put("description", "Spoken Thai phonetic form or local clinical slang, or null if not spoken in Thai.")
                        }
                        putJsonObject("category") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add("DRUG"); add("DIAGNOSIS"); add("EXAM_SCALE")
                                add("PROCEDURE_LAB"); add("WARD_HOSPITAL")
                                add("PHONETIC_TH_EN"); add("GENERAL_MED")
                            }
                        }
                        putJsonObject("notes") {
                            putJsonArray("type") { add("string"); add("null") }
                            put("description", "Brief clinical meaning, dose context, or expansion (max 120 chars), or null.")
                        }
                    }
                    putJsonArray("required") {
                        add("term"); add("thai_phonetic"); add("category"); add("notes")
                    }
                }
            }
        }
        putJsonArray("required") {
            add("has_meaningful_content"); add("is_clinical_round"); add("context")
            add("title"); add("summary"); add("key_points"); add("speakers")
            add("transcript"); add("patients"); add("items"); add("ai_review_report")
            add("discovered_glossary_terms")
        }
    }

    /**
     * OpenAPI 3.0 Schema subset for `/v1beta/models/{model}:generateContent` (`generationConfig.response_schema`),
     * which requires `"type": "string"` + `"nullable": true` instead of `"type": ["string", "null"]`.
     */
    fun buildGenerateContentSchema(): JsonObject {
        return convertJsonSchemaToOpenApiSchema(buildResponseJsonSchema())
    }

    private fun convertJsonSchemaToOpenApiSchema(schema: JsonObject): JsonObject {
        fun convertElement(el: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
            return when (el) {
                is JsonObject -> {
                    buildJsonObject {
                        el.forEach { (k, v) ->
                            if (k == "type" && v is kotlinx.serialization.json.JsonArray) {
                                val types = v.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                                val primary = types.firstOrNull { it != "null" } ?: "string"
                                put("type", primary)
                                if ("null" in types) put("nullable", true)
                            } else {
                                put(k, convertElement(v))
                            }
                        }
                    }
                }
                is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(el.map { convertElement(it) })
                else -> el
            }
        }
        return convertElement(schema) as JsonObject
    }

    /**
     * System instruction for Gemini Voice Profile Analyze, Audit & Creation from top-pick audio samples.
     */
    fun buildVoiceProfileAuditSystemInstruction(
        userDisplayName: String,
        existingRosterOverview: String
    ): String = """
You are an expert Forensic & Clinical Voice Profile Auditor, Acoustic Biometrics Analyst, and Bilingual (Thai-English) Speaker Naming Specialist.
You are auditing top-pick voice sample audio clips (highest-quality `.aac` voice signature samples extracted from old recordings) alongside each profile's current Name (`nickname`), Relatable Title (`relatable_title`), Voice Description (`voice_signature`), measured acoustic metrics, and spoken transcript quotes.

Device Owner / Primary User Name: '$userDisplayName'
$existingRosterOverview

CRITICAL MANDATES:

1. STRICT ANTI-COMBINATION & SPEAKER PURITY AUDIT (`is_same_single_speaker`, `mismatched_sample_ids`):
   - NEVER combine different human speakers into the same voice profile!
   - Listen carefully to every attached audio sample for each `profile_id`:
     * Compare vocal tract timbre, fundamental pitch (F0 register), gender/register, resonance, speaking cadence, accent, and Thai/English code-switching style across attached samples of the same profile.
     * If a profile has 2 attached clips (e.g., Sample 1 and Sample 2) and they belong to TWO DIFFERENT PEOPLE (or if one clip is dominated by another speaker's voice):
       - Set `is_same_single_speaker = false`
       - List the mismatched `sample_id`(s) in `mismatched_sample_ids`
       - Provide a separate new Voice Profile (`split_new_nickname`, `split_new_relatable_title`, `split_new_voice_signature`) for the second speaker so they are cleanly separated into their own profile!
     * Also keep different people separated even if they share a similar role (e.g., two different residents or two different nurses must NEVER be merged into one profile).

2. RECHECK IF TITLE, NAME, AND DESCRIPTION ARE TRULY SUITABLE (`name_suitable`, `title_suitable`, `description_suitable`):
   - For every profile in `audited_profiles[]`, listen to their actual voice sample audio and review their spoken lines/context:
     * **Recheck Name (`recommended_nickname`)**:
       - Is the current `nickname` truly suitable for this speaker?
       - If the speaker's real name or honorific (e.g., `"Ajarn Somchai"`, `"Dr. Mint"`, `"P'Bam"`, `"Nurse Noi"`) was spoken in the audio or quotes, use that exact natural name.
       - If using a descriptive recognizing nickname (2–4 words), verify that the acoustic adjective in the name (e.g., `"Deep-Tone"`, `"Warm-Tone"`, `"Crisp-Tone"`, `"Calm-Pace"`, `"Brisk-Voice"`, `"Steady-Voice"`) genuinely matches the actual vocal pitch/timbre heard in the audio clip and their true role!
       - Set `name_suitable = false` if the current name is generic (`"Speaker 1"`, `"Unknown"`, `"Clear-Tone Voice"`), contradicts the voice heard in the audio (e.g., `"Deep-Tone"` on a high-pitched voice), or can be upgraded to a more accurate recognizing nickname.
     * **Recheck Relatable Title (`recommended_relatable_title`)**:
       - Is the current `relatable_title` truly suitable and grounded in what this speaker actually says and does?
       - Provide a crisp, accurate 2–5 word title (e.g., `"Senior Neuro Attending"`, `"Presenting Ward Resident"`, `"Neuro ICU Charge Nurse"`, `"Patient's Daughter"`, `"On-Call Pharmacist"`, `"Engineering Colleague"`, `"Call Partner"`).
       - Set `title_suitable = false` if the current title was blank, vague (`"Conversation Partner"` when clinical role is obvious), or mismatched.
     * **Recheck Description / Voice Signature (`recommended_voice_signature`)**:
       - Listen directly to the attached voice sample audio and write 1 concise, accurate acoustic & speaking-style fingerprint (fundamental pitch register, vocal warmth/brightness, cadence/pace, language mix, e.g., `"Calm low-baritone male, measured teaching cadence, blends English neurology terms into Thai"`).
       - Set `description_suitable = false` if the previous description was blank, generic, or did not accurately describe the voice in the audio clip.
     * Always populate `recommended_nickname`, `recommended_relatable_title`, `recommended_voice_signature`, and `audit_notes` (1–2 sentences explaining your acoustic and contextual suitability check).

3. ANALYZE & CREATE VOICE PROFILES FOR TOP-PICK CANDIDATE CLIPS (`created_profiles[]`):
   - For each attached `[CANDIDATE_CLIP candidate_clip_id=...]`:
     * Listen to the audio clip. If it is solely noise/crosstalk with no clear primary speaker, set `is_usable_single_speaker = false`.
     * If it is a clear single human speaker:
       - Only set `matches_existing_profile_id` (with `match_confidence = "HIGH"`) if the voice is 100% unmistakably the exact same human speaker as an existing profile. When in doubt, NEVER combine different speakers—set `matches_existing_profile_id = null`!
       - Provide a suitable, memorable `nickname` (2–4 words, never `"Speaker 1"`), an accurate 2–5 word `relatable_title`, and a precise acoustic `voice_signature` description so a high-quality Voice Profile can be created.
""".trimIndent()

    fun buildVoiceProfileAuditJsonSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("audited_profiles") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("profile_id") { put("type", "string") }
                        putJsonObject("is_same_single_speaker") { put("type", "boolean") }
                        putJsonObject("mismatched_sample_ids") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                        }
                        putJsonObject("name_suitable") { put("type", "boolean") }
                        putJsonObject("title_suitable") { put("type", "boolean") }
                        putJsonObject("description_suitable") { put("type", "boolean") }
                        putJsonObject("recommended_nickname") { put("type", "string") }
                        putJsonObject("recommended_relatable_title") { put("type", "string") }
                        putJsonObject("recommended_voice_signature") { put("type", "string") }
                        putJsonObject("audit_notes") { put("type", "string") }
                        putJsonObject("split_new_nickname") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                        putJsonObject("split_new_relatable_title") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                        putJsonObject("split_new_voice_signature") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                    }
                    putJsonArray("required") {
                        add("profile_id"); add("is_same_single_speaker"); add("mismatched_sample_ids")
                        add("name_suitable"); add("title_suitable"); add("description_suitable")
                        add("recommended_nickname"); add("recommended_relatable_title")
                        add("recommended_voice_signature"); add("audit_notes")
                        add("split_new_nickname"); add("split_new_relatable_title"); add("split_new_voice_signature")
                    }
                }
            }
            putJsonObject("created_profiles") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("candidate_clip_id") { put("type", "string") }
                        putJsonObject("is_usable_single_speaker") { put("type", "boolean") }
                        putJsonObject("matches_existing_profile_id") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                        putJsonObject("match_confidence") {
                            putJsonArray("type") { add("string"); add("null") }
                        }
                        putJsonObject("nickname") { put("type", "string") }
                        putJsonObject("relatable_title") { put("type", "string") }
                        putJsonObject("voice_signature") { put("type", "string") }
                        putJsonObject("audit_notes") { put("type", "string") }
                    }
                    putJsonArray("required") {
                        add("candidate_clip_id"); add("is_usable_single_speaker")
                        add("matches_existing_profile_id"); add("match_confidence")
                        add("nickname"); add("relatable_title"); add("voice_signature"); add("audit_notes")
                    }
                }
            }
            putJsonObject("overall_audit_summary") { put("type", "string") }
        }
        putJsonArray("required") {
            add("audited_profiles"); add("created_profiles"); add("overall_audit_summary")
        }
    }

    fun buildVoiceProfileAuditGenerateContentSchema(): JsonObject {
        return convertJsonSchemaToOpenApiSchema(buildVoiceProfileAuditJsonSchema())
    }
}
