package com.ambientbrain.recorder.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.ambientbrain.recorder.ui.CopyFormatter
import com.ambientbrain.recorder.ui.TaskCopyTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class ExportPreset(val label: String, val subtitle: String) {
    FULL_BACKUP(
        label = "All Data + Audio",
        subtitle = "Settings, voice profiles, rounds, transcripts, tasks, clips & raw .aac recordings"
    ),
    STANDARD_BACKUP(
        label = "All Data (No Raw Audio)",
        subtitle = "Settings, profiles, rounds, transcripts, tasks & permanent voice proof clips"
    ),
    CLINICAL_NOTES_ONLY(
        label = "Clinical & Notes Only",
        subtitle = "Rounds, patient sheets, bilingual transcripts & tasks (no settings or audio)"
    ),
    SETTINGS_AND_ROSTER(
        label = "Settings Backup Only",
        subtitle = "App preferences, Gemini model/policy, custom glossary & speaker roster"
    ),
    CUSTOM(
        label = "Custom Selection",
        subtitle = "Fine-grained export of selected parts and/or chosen sessions"
    )
}

enum class ExportFormat(
    val label: String,
    val shortLabel: String,
    val extension: String,
    val createDocMimeType: String,
    val shareMimeType: String,
    val description: String
) {
    ZIP_BUNDLE(
        label = "Portable Backup Bundle (.zip)",
        shortLabel = "ZIP Archive",
        extension = "zip",
        createDocMimeType = "application/zip",
        shareMimeType = "application/zip",
        description = "Complete restorable ZIP containing JSON database, settings, audio files, Markdown report & CSV"
    ),
    SETTINGS_JSON(
        label = "Settings Backup File (.json)",
        shortLabel = "Settings JSON",
        extension = "json",
        createDocMimeType = "application/json",
        shareMimeType = "application/json",
        description = "Lightweight 1-tap restorable backup of preferences, glossary & speaker roster"
    ),
    MARKDOWN_REPORT(
        label = "Readable Handover Report (.md)",
        shortLabel = "Markdown (.md)",
        extension = "md",
        createDocMimeType = "text/markdown",
        shareMimeType = "text/plain",
        description = "Human-readable ward rounds, patient blocks, tasks & bilingual speaker transcripts"
    ),
    CSV_ITEMS(
        label = "Tasks & Notes Spreadsheet (.csv)",
        shortLabel = "CSV Sheet",
        extension = "csv",
        createDocMimeType = "text/csv",
        shareMimeType = "text/csv",
        description = "Excel / Google Sheets compatible table of tasks, notes, decisions, quotes & due dates"
    )
}

enum class ExportTimeFilter(val label: String, val days: Int?) {
    ALL_TIME("All Time", null),
    TODAY("24 Hours", 1),
    LAST_7_DAYS("7 Days", 7),
    LAST_30_DAYS("30 Days", 30)
}

data class ExportSelectionConfig(
    val preset: ExportPreset = ExportPreset.STANDARD_BACKUP,
    val format: ExportFormat = ExportFormat.ZIP_BUNDLE,
    val includeSettings: Boolean = true,
    val includeApiKeys: Boolean = false,
    val includeVoiceProfiles: Boolean = true,
    val includeSessionsAndSummaries: Boolean = true,
    val includeTranscripts: Boolean = true,
    val includeClinicalRounds: Boolean = true,
    val includeItems: Boolean = true,
    val includeArchivedItems: Boolean = true,
    val includeAnalysisRuns: Boolean = true,
    val includeProofClips: Boolean = true,
    val includeRawSessionAudio: Boolean = false,
    val timeFilter: ExportTimeFilter = ExportTimeFilter.ALL_TIME,
    val roundsOnlyFilter: Boolean = false,
    val selectedSessionIds: Set<String>? = null
) {
    fun hasAnyDataPartSelected(): Boolean {
        return includeSettings ||
            includeVoiceProfiles ||
            includeSessionsAndSummaries ||
            includeTranscripts ||
            includeClinicalRounds ||
            includeItems ||
            includeAnalysisRuns ||
            includeProofClips ||
            includeRawSessionAudio
    }

    fun hasAnyAudioPartSelected(): Boolean {
        return includeVoiceProfiles || includeProofClips || includeRawSessionAudio
    }

    companion object {
        fun fromPreset(preset: ExportPreset): ExportSelectionConfig {
            return when (preset) {
                ExportPreset.FULL_BACKUP -> ExportSelectionConfig(
                    preset = preset,
                    format = ExportFormat.ZIP_BUNDLE,
                    includeSettings = true,
                    includeApiKeys = false,
                    includeVoiceProfiles = true,
                    includeSessionsAndSummaries = true,
                    includeTranscripts = true,
                    includeClinicalRounds = true,
                    includeItems = true,
                    includeArchivedItems = true,
                    includeAnalysisRuns = true,
                    includeProofClips = true,
                    includeRawSessionAudio = true
                )

                ExportPreset.STANDARD_BACKUP -> ExportSelectionConfig(
                    preset = preset,
                    format = ExportFormat.ZIP_BUNDLE,
                    includeSettings = true,
                    includeApiKeys = false,
                    includeVoiceProfiles = true,
                    includeSessionsAndSummaries = true,
                    includeTranscripts = true,
                    includeClinicalRounds = true,
                    includeItems = true,
                    includeArchivedItems = true,
                    includeAnalysisRuns = true,
                    includeProofClips = true,
                    includeRawSessionAudio = false
                )

                ExportPreset.CLINICAL_NOTES_ONLY -> ExportSelectionConfig(
                    preset = preset,
                    format = ExportFormat.MARKDOWN_REPORT,
                    includeSettings = false,
                    includeApiKeys = false,
                    includeVoiceProfiles = false,
                    includeSessionsAndSummaries = true,
                    includeTranscripts = true,
                    includeClinicalRounds = true,
                    includeItems = true,
                    includeArchivedItems = false,
                    includeAnalysisRuns = false,
                    includeProofClips = false,
                    includeRawSessionAudio = false
                )

                ExportPreset.SETTINGS_AND_ROSTER -> ExportSelectionConfig(
                    preset = preset,
                    format = ExportFormat.SETTINGS_JSON,
                    includeSettings = true,
                    includeApiKeys = false,
                    includeVoiceProfiles = false,
                    includeSessionsAndSummaries = false,
                    includeTranscripts = false,
                    includeClinicalRounds = false,
                    includeItems = false,
                    includeArchivedItems = false,
                    includeAnalysisRuns = false,
                    includeProofClips = false,
                    includeRawSessionAudio = false
                )

                ExportPreset.CUSTOM -> ExportSelectionConfig(preset = ExportPreset.CUSTOM)
            }
        }
    }
}

data class ExportEstimate(
    val sessionCount: Int = 0,
    val patientCount: Int = 0,
    val sentenceCount: Int = 0,
    val itemCount: Int = 0,
    val analysisRunCount: Int = 0,
    val voiceProfileCount: Int = 0,
    val proofClipCount: Int = 0,
    val rawAudioCount: Int = 0,
    val voiceProfileBytes: Long = 0L,
    val proofClipBytes: Long = 0L,
    val rawAudioBytes: Long = 0L,
    val estimatedStructuredBytes: Long = 0L,
    val apiKeyCount: Int = 0,
    val rosterCount: Int = 0,
    val hasSettingsSelected: Boolean = false
) {
    val totalAudioBytes: Long get() = voiceProfileBytes + proofClipBytes + rawAudioBytes
    val totalEstimatedBytes: Long get() = totalAudioBytes + estimatedStructuredBytes
    val totalAudioFilesCount: Int get() = voiceProfileCount + proofClipCount + rawAudioCount
    val hasExportableContent: Boolean
        get() = hasSettingsSelected ||
            sessionCount > 0 ||
            patientCount > 0 ||
            sentenceCount > 0 ||
            itemCount > 0 ||
            analysisRunCount > 0 ||
            totalAudioFilesCount > 0
}

data class ExportResult(
    val format: ExportFormat,
    val fileName: String,
    val bytesWritten: Long,
    val entryCount: Int,
    val summary: String,
    val cachedFile: File? = null
)

@Serializable
data class BackupCounts(
    val sessions: Int = 0,
    val segmentMaps: Int = 0,
    val speakers: Int = 0,
    val sentences: Int = 0,
    val patients: Int = 0,
    val items: Int = 0,
    val analysisRuns: Int = 0,
    val voiceProfileAudioFiles: Int = 0,
    val proofClipFiles: Int = 0,
    val rawAudioFiles: Int = 0,
    val speakerRosterCount: Int = 0,
    val apiKeyCount: Int = 0
)

@Serializable
data class BackupAudioFileManifestEntry(
    val zipPath: String,
    val category: String,
    val originalName: String,
    val sizeBytes: Long
)

@Serializable
data class BackupManifest(
    val format: String = AppDataExporter.ZIP_BACKUP_FORMAT,
    val bundleVersion: Int = 1,
    val dbSchemaVersion: Int = 6,
    val appVersionName: String = "",
    val appVersionCode: Long = 0L,
    val exportedAtMs: Long = 0L,
    val exportedAtIso: String = "",
    val deviceModel: String = "",
    val preset: String = "",
    val timeFilter: String = "",
    val roundsOnlyFilter: Boolean = false,
    val selectedSessionCount: Int = 0,
    val includesSettings: Boolean = false,
    val includesApiKeys: Boolean = false,
    val includesVoiceProfiles: Boolean = false,
    val includesSessions: Boolean = false,
    val includesTranscripts: Boolean = false,
    val includesPatients: Boolean = false,
    val includesItems: Boolean = false,
    val includesAnalysisRuns: Boolean = false,
    val includesClips: Boolean = false,
    val includesRawAudio: Boolean = false,
    val counts: BackupCounts = BackupCounts(),
    val audioFiles: List<BackupAudioFileManifestEntry> = emptyList()
)

@Serializable
data class BackupSessionDto(
    val id: String,
    val roundGroupId: String? = null,
    val eventGroupId: String? = null,
    val combinedSourceIdsJson: String? = null,
    val mode: String = SessionMode.EVERYDAY.name,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val speechMs: Long = 0L,
    val hadPhoneCall: Boolean = false,
    val callEventsJson: String? = null,
    val rawZipRelPath: String? = null,
    val rawFileName: String? = null,
    val rawBytes: Long = 0L,
    val isRawPurged: Boolean = false,
    val isPinned: Boolean = false,
    val context: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val keyPointsJson: String? = null,
    val status: String = Status.DONE.name,
    val lastError: String? = null,
    val retryCount: Int = 0,
    val nextRetryEpochMs: Long = 0L,
    val aiReviewReport: String? = null,
    val reviewedAtMs: Long = 0L,
    val reviewSourceMode: String? = null
)

@Serializable
data class BackupSegmentMapDto(
    val sessionId: String,
    val fileOffsetMs: Long,
    val wallClockMs: Long,
    val lengthMs: Long
)

@Serializable
data class BackupSpeakerDto(
    val sessionId: String,
    val speakerId: String,
    val speakerLabel: String,
    val inferredRole: String? = null,
    val isUser: Boolean = false,
    val matchedProfileId: String? = null,
    val nickname: String? = null,
    val relatableTitle: String? = null,
    val voiceSignature: String? = null,
    val isAutoEnrolled: Boolean = false
)

@Serializable
data class BackupSentenceDto(
    val sentenceUuid: String,
    val sessionId: String,
    val ordinal: Int,
    val approxMs: Long,
    val speakerId: String,
    val speakerLabel: String,
    val textOriginal: String,
    val textEn: String? = null
)

@Serializable
data class BackupPatientDto(
    val id: String,
    val sessionId: String,
    val roundGroupId: String? = null,
    val patientIdentifier: String,
    val approxStartMs: Long,
    val approxEndMs: Long,
    val oneLiner: String,
    val neuroExam: String? = null,
    val assessment: String,
    val problemsJson: String = "[]",
    val resultsJson: String = "[]",
    val pendingJson: String = "[]",
    val planJson: String = "[]",
    val teachingJson: String = "[]"
)

@Serializable
data class BackupItemDto(
    val id: String,
    val sessionId: String,
    val patientIdentifier: String? = null,
    val type: String = ItemType.TASK.name,
    val title: String,
    val details: String = "",
    val speakerId: String? = null,
    val speakerLabel: String = "",
    val assignee: String? = null,
    val isForUser: Boolean = false,
    val due: String? = null,
    val priority: String = "NORMAL",
    val quoteOriginal: String = "",
    val quoteEn: String? = null,
    val needsVerification: Boolean = false,
    val clipStartMs: Long = 0L,
    val clipEndMs: Long = 0L,
    val clipZipRelPath: String? = null,
    val clipFileName: String? = null,
    val isDone: Boolean = false,
    val isArchived: Boolean = false,
    val createdMs: Long = 0L
)

@Serializable
data class BackupAnalysisRunDto(
    val sessionId: String,
    val model: String,
    val promptVersion: String,
    val inTokens: Int,
    val outTokens: Int,
    val cachedTokens: Int = 0,
    val latencyMs: Long,
    val ok: Boolean,
    val errorMsg: String? = null,
    val atMs: Long = 0L
)

@Serializable
data class BackupDatabasePayload(
    val format: String = AppDataExporter.DB_BACKUP_FORMAT,
    val schemaVersion: Int = 6,
    val exportedAtMs: Long = 0L,
    val sessions: List<BackupSessionDto> = emptyList(),
    val segmentMaps: List<BackupSegmentMapDto> = emptyList(),
    val speakers: List<BackupSpeakerDto> = emptyList(),
    val sentences: List<BackupSentenceDto> = emptyList(),
    val patients: List<BackupPatientDto> = emptyList(),
    val items: List<BackupItemDto> = emptyList(),
    val analysisRuns: List<BackupAnalysisRunDto> = emptyList()
)

enum class BackupContainerKind {
    ZIP_ARCHIVE,
    SETTINGS_JSON,
    DATABASE_JSON
}

data class BackupInspection(
    val containerKind: BackupContainerKind,
    val displayName: String,
    val fileSizeBytes: Long,
    val exportedAtMs: Long,
    val appVersionName: String,
    val hasSettings: Boolean,
    val settingsPayload: SettingsBackupPayload? = null,
    val apiKeysCount: Int = 0,
    val rosterCount: Int = 0,
    val sessionsCount: Int = 0,
    val sentencesCount: Int = 0,
    val patientsCount: Int = 0,
    val itemsCount: Int = 0,
    val analysisRunsCount: Int = 0,
    val voiceProfileFilesCount: Int = 0,
    val proofClipFilesCount: Int = 0,
    val rawAudioFilesCount: Int = 0
) {
    val hasDatabaseRecords: Boolean
        get() = sessionsCount > 0 || sentencesCount > 0 || patientsCount > 0 || itemsCount > 0 || analysisRunsCount > 0
    val hasAudioFiles: Boolean
        get() = voiceProfileFilesCount > 0 || proofClipFilesCount > 0 || rawAudioFilesCount > 0
}

data class RestoreSelectionOptions(
    val restoreSettings: Boolean = true,
    val restoreApiKeys: Boolean = false,
    val restoreVoiceProfiles: Boolean = true,
    val restoreDatabaseRecords: Boolean = true,
    val restoreProofClips: Boolean = true,
    val restoreRawSessionAudio: Boolean = false
)

data class RestoreResult(
    val settingsApplied: Boolean,
    val rosterMergedCount: Int,
    val apiKeysRestored: Boolean,
    val voiceProfilesRestored: Int,
    val sessionsRestored: Int,
    val itemsRestored: Int,
    val patientsRestored: Int,
    val sentencesRestored: Int,
    val clipsRestored: Int,
    val rawAudioRestored: Int
) {
    fun buildSummary(): String {
        val parts = mutableListOf<String>()
        if (settingsApplied) {
            val extra = buildList {
                if (rosterMergedCount > 0) add("$rosterMergedCount roster")
                if (apiKeysRestored) add("API keys")
            }.joinToString(", ")
            parts.add(if (extra.isNotBlank()) "Settings ($extra)" else "Settings")
        }
        if (voiceProfilesRestored > 0) parts.add("$voiceProfilesRestored voice profile(s)")
        if (sessionsRestored > 0) parts.add("$sessionsRestored session(s)")
        if (patientsRestored > 0) parts.add("$patientsRestored patient sheet(s)")
        if (itemsRestored > 0) parts.add("$itemsRestored task/note(s)")
        if (sentencesRestored > 0) parts.add("$sentencesRestored transcript line(s)")
        if (clipsRestored > 0) parts.add("$clipsRestored voice proof(s)")
        if (rawAudioRestored > 0) parts.add("$rawAudioRestored raw audio file(s)")
        return if (parts.isEmpty()) "No matching data selected to restore." else "Restored: ${parts.joinToString(" • ")}"
    }
}

object AppDataExporter {
    const val ZIP_BACKUP_FORMAT = "ambient_brain_backup_zip_v1"
    const val DB_BACKUP_FORMAT = "ambient_brain_database_v1"
    private const val ZIP_SETTINGS_PATH = "settings/settings_backup.json"
    private const val ZIP_DB_PATH = "database/export_data.json"
    private const val ZIP_MANIFEST_PATH = "manifest.json"
    private const val ZIP_MARKDOWN_PATH = "readable/ward_rounds_and_notes.md"
    private const val ZIP_CSV_PATH = "readable/tasks_items.csv"
    private const val STREAM_BUFFER_SIZE = 16_384

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prettyJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private data class BundledAudioFile(
        val file: File,
        val zipPath: String,
        val category: String,
        val recordedAtMs: Long = 0L
    )

    private data class ExportDataset(
        val filteredSessions: List<SessionWithDetails>,
        val exportSessions: List<SessionEntity>,
        val segmentMaps: List<SegmentMapEntity>,
        val speakers: List<SessionSpeakerEntity>,
        val sentences: List<TranscriptSentenceEntity>,
        val patients: List<PatientBlockEntity>,
        val items: List<ItemEntity>,
        val analysisRuns: List<AnalysisRunEntity>,
        val voiceProfileFiles: List<BundledAudioFile>,
        val proofClipFiles: List<BundledAudioFile>,
        val rawAudioFiles: List<BundledAudioFile>,
        val sessionZipPathById: Map<String, String>,
        val clipZipPathByItemId: Map<String, String>,
        val settingsPayload: SettingsBackupPayload?
    )

    fun formatBytesHuman(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun buildSuggestedFileName(config: ExportSelectionConfig, nowMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(nowMs))
        val prefix = when {
            config.format == ExportFormat.SETTINGS_JSON -> "ambient_brain_settings"
            config.format == ExportFormat.MARKDOWN_REPORT -> "ambient_brain_report"
            config.format == ExportFormat.CSV_ITEMS -> "ambient_brain_tasks"
            config.preset == ExportPreset.FULL_BACKUP -> "ambient_brain_full_backup"
            config.preset == ExportPreset.SETTINGS_AND_ROSTER -> "ambient_brain_settings_bundle"
            config.selectedSessionIds != null -> "ambient_brain_selected_${config.selectedSessionIds.size}sessions"
            else -> "ambient_brain_backup"
        }
        return "${prefix}_$stamp.${config.format.extension}"
    }

    fun clearOldExportCache(context: Context, maxAgeHours: Long = 6L) {
        runCatching {
            val dir = File(context.cacheDir, "exports")
            if (!dir.exists()) return
            val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(maxAgeHours)
            dir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) {
                    f.delete()
                }
            }
        }
    }

    suspend fun estimateExportSummary(
        context: Context,
        config: ExportSelectionConfig
    ): ExportEstimate = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dataset = collectDataset(appContext, config)
        val settingsPayload = dataset.settingsPayload

        val voiceBytes = dataset.voiceProfileFiles.sumOf { it.file.length() }
        val clipBytes = dataset.proofClipFiles.sumOf { it.file.length() }
        val rawBytes = dataset.rawAudioFiles.sumOf { it.file.length() }

        val estStructuredBytes = when (config.format) {
            ExportFormat.SETTINGS_JSON -> {
                if (config.includeSettings) {
                    (settingsPayload?.let { prettyJson.encodeToString(it).toByteArray(Charsets.UTF_8).size.toLong() } ?: 512L)
                } else 0L
            }
            ExportFormat.CSV_ITEMS -> {
                dataset.items.sumOf { 240L + it.title.length + it.details.length + it.quoteOriginal.length }
            }
            ExportFormat.MARKDOWN_REPORT -> {
                val sentenceBytes = dataset.sentences.sumOf { (it.textOriginal.length + (it.textEn?.length ?: 0) + 48).toLong() }
                val patientBytes = dataset.patients.sumOf { (it.oneLiner.length + it.assessment.length + it.planJson.length + 180).toLong() }
                val itemBytes = dataset.items.sumOf { (it.title.length + it.details.length + 140).toLong() }
                (sentenceBytes + patientBytes + itemBytes + 1024L).coerceAtLeast(if (dataset.filteredSessions.isNotEmpty() || dataset.items.isNotEmpty()) 512L else 0L)
            }
            ExportFormat.ZIP_BUNDLE -> {
                val dbTextEst =
                    dataset.exportSessions.size * 650L +
                        dataset.segmentMaps.size * 80L +
                        dataset.speakers.size * 110L +
                        dataset.sentences.sumOf { (it.textOriginal.length + (it.textEn?.length ?: 0) + 140).toLong() } +
                        dataset.patients.sumOf { (it.oneLiner.length + it.assessment.length + it.planJson.length + 280).toLong() } +
                        dataset.items.sumOf { (it.title.length + it.details.length + it.quoteOriginal.length + 260).toLong() } +
                        dataset.analysisRuns.size * 160L
                val compressedText = (dbTextEst / 3L).coerceAtLeast(if (dbTextEst > 0) 512L else 0L)
                val settingsSize = if (config.includeSettings) 2_048L else 0L
                compressedText + settingsSize
            }
        }

        val effectiveSessionCount = when {
            config.format == ExportFormat.SETTINGS_JSON || config.format == ExportFormat.CSV_ITEMS -> 0
            config.includeSessionsAndSummaries || config.includeTranscripts || config.includeClinicalRounds || config.includeRawSessionAudio -> dataset.filteredSessions.size
            else -> 0
        }

        ExportEstimate(
            sessionCount = effectiveSessionCount,
            patientCount = if (config.format == ExportFormat.SETTINGS_JSON || config.format == ExportFormat.CSV_ITEMS) 0 else dataset.patients.size,
            sentenceCount = if (config.format == ExportFormat.SETTINGS_JSON || config.format == ExportFormat.CSV_ITEMS) 0 else dataset.sentences.size,
            itemCount = if (config.format == ExportFormat.SETTINGS_JSON) 0 else dataset.items.size,
            analysisRunCount = if (config.format == ExportFormat.ZIP_BUNDLE) dataset.analysisRuns.size else 0,
            voiceProfileCount = if (config.format == ExportFormat.ZIP_BUNDLE) dataset.voiceProfileFiles.size else 0,
            proofClipCount = if (config.format == ExportFormat.ZIP_BUNDLE) dataset.proofClipFiles.size else 0,
            rawAudioCount = if (config.format == ExportFormat.ZIP_BUNDLE) dataset.rawAudioFiles.size else 0,
            voiceProfileBytes = if (config.format == ExportFormat.ZIP_BUNDLE) voiceBytes else 0L,
            proofClipBytes = if (config.format == ExportFormat.ZIP_BUNDLE) clipBytes else 0L,
            rawAudioBytes = if (config.format == ExportFormat.ZIP_BUNDLE) rawBytes else 0L,
            estimatedStructuredBytes = estStructuredBytes,
            apiKeyCount = settingsPayload?.let {
                (it.savedApiKeysPool + listOf(it.geminiApiKey)).filter { k -> k.isNotBlank() }.distinct().size
            } ?: 0,
            rosterCount = settingsPayload?.speakerRoster?.size ?: 0,
            hasSettingsSelected = config.includeSettings && (config.format == ExportFormat.ZIP_BUNDLE || config.format == ExportFormat.SETTINGS_JSON)
        )
    }

    suspend fun exportToUri(
        context: Context,
        targetUri: Uri,
        config: ExportSelectionConfig,
        onProgress: (String) -> Unit = {}
    ): ExportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val fileName = resolveDisplayNameFromUri(appContext, targetUri) ?: buildSuggestedFileName(config)
        val out = appContext.contentResolver.openOutputStream(targetUri)
            ?: throw IllegalStateException("Could not open selected destination for writing.")
        out.use { stream ->
            writeExportToStream(appContext, stream, fileName, config, onProgress)
        }
    }

    suspend fun exportToCacheForShare(
        context: Context,
        config: ExportSelectionConfig,
        onProgress: (String) -> Unit = {}
    ): ExportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        clearOldExportCache(appContext)
        val exportDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val fileName = buildSuggestedFileName(config)
        val outFile = File(exportDir, fileName)
        if (outFile.exists()) outFile.delete()

        val result = FileOutputStream(outFile).use { fos ->
            writeExportToStream(appContext, fos, fileName, config, onProgress)
        }
        result.copy(cachedFile = outFile)
    }

    suspend fun renderTextExportPreview(
        context: Context,
        config: ExportSelectionConfig
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = PreferencesRepo(appContext)
        when (config.format) {
            ExportFormat.SETTINGS_JSON -> prefs.encodeSettingsBackupJson(includeApiKeys = config.includeApiKeys)
            ExportFormat.MARKDOWN_REPORT -> buildMarkdownReport(appContext, collectDataset(appContext, config), prefs)
            ExportFormat.CSV_ITEMS -> buildItemsCsv(collectDataset(appContext, config).items)
            ExportFormat.ZIP_BUNDLE -> buildMarkdownReport(appContext, collectDataset(appContext, config), prefs)
        }
    }

    fun launchShareIntent(context: Context, result: ExportResult) {
        val file = result.cachedFile ?: return
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = result.format.shareMimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Ambient Brain Export — ${result.fileName}")
            putExtra(Intent.EXTRA_TEXT, result.summary)
            clipData = ClipData.newRawUri(result.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share ${result.format.shortLabel}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    @Serializable
    data class ExportedVoiceSampleMeta(
        val rank: Int = 1,
        val fileName: String,
        val durationMs: Long = 0L,
        val qualityScore: Float = 75f,
        val snrDb: Float = 15f,
        val speechRatio: Float = 0.65f,
        val isManual: Boolean = true,
        val createdAtIso: String = "",
        val sourceSessionId: String? = null,
        val id: String = "",
        val createdAtMs: Long = 0L,
        val embedding: List<Float> = emptyList(),
        val audioFormat: String = "WAV"
    )

    @Serializable
    data class ExportedVoiceProfileSummary(
        val format: String = "ambient_brain_voice_profile_v2",
        val profileId: String = "",
        val profileName: String,
        val relatableTitle: String = "",
        val voiceSignature: String = "",
        val aliases: List<String> = emptyList(),
        val isUser: Boolean = false,
        val isPinned: Boolean = false,
        val sessionsHeardCount: Int = 1,
        val centroidEmbedding: List<Float> = emptyList(),
        val exportedAtIso: String = "",
        val sampleCount: Int = 0,
        val samples: List<ExportedVoiceSampleMeta> = emptyList()
    )

    data class VoiceProfileImportResult(
        val profileId: String,
        val profileName: String,
        val relatableTitle: String,
        val voiceSignature: String,
        val isUser: Boolean,
        val importedSamplesCount: Int,
        val skippedOutlierCount: Int,
        val summaryMessage: String
    )

    fun buildSafeProfileFileName(
        profileName: String,
        suffix: String,
        extension: String
    ): String {
        val cleanedBase = profileName
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "voice_profile" }
        val cleanSuffix = suffix.trim().trim('_')
        val stem = if (cleanSuffix.isNotBlank()) "${cleanedBase}_${cleanSuffix}" else cleanedBase
        return "$stem.$extension"
    }

    /**
     * Exports a single recorded voice profile sample to a SAF destination [targetUri],
     * preserving lossless `.wav` directly or remuxing ADTS `.aac` into universally playable `.m4a` (MPEG-4).
     */
    suspend fun exportVoiceSampleToUri(
        context: Context,
        sampleFile: File,
        targetUri: Uri
    ): Long = withContext(Dispatchers.IO) {
        if (!sampleFile.exists() || sampleFile.length() == 0L) {
            throw IllegalStateException("Voice sample file is missing on disk.")
        }
        val appContext = context.applicationContext
        val tmpM4a = File(appContext.cacheDir, "tmp_voice_export_${System.currentTimeMillis()}.m4a")
        val sourceToWrite = if (
            ClipSlicer.isWavFile(sampleFile) ||
            sampleFile.name.endsWith(".m4a", ignoreCase = true) ||
            sampleFile.name.endsWith(".flac", ignoreCase = true)
        ) {
            sampleFile
        } else if (ClipSlicer.convertAdtsToM4a(sampleFile, tmpM4a)) {
            tmpM4a
        } else {
            sampleFile
        }
        try {
            val out = appContext.contentResolver.openOutputStream(targetUri, "w")
                ?: throw IllegalStateException("Unable to open destination file for writing.")
            var bytes = 0L
            out.use { os ->
                FileInputStream(sourceToWrite).use { fis ->
                    val buf = ByteArray(STREAM_BUFFER_SIZE)
                    while (true) {
                        val r = fis.read(buf)
                        if (r <= 0) break
                        os.write(buf, 0, r)
                        bytes += r
                    }
                    os.flush()
                }
            }
            bytes
        } finally {
            runCatching { if (tmpM4a.exists()) tmpM4a.delete() }
        }
    }

    /**
     * Prepares a playable `.wav` (if lossless) or `.m4a` copy of a single voice sample in `cacheDir/exports/`
     * ready to share via [FileProvider].
     */
    suspend fun prepareVoiceSampleShareFile(
        context: Context,
        sampleFile: File,
        profileName: String,
        rankIndex: Int
    ): File = withContext(Dispatchers.IO) {
        if (!sampleFile.exists() || sampleFile.length() == 0L) {
            throw IllegalStateException("Voice sample file is missing on disk.")
        }
        val appContext = context.applicationContext
        val shareDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val rankTag = if (rankIndex == 1) "sample1_best" else "sample$rankIndex"
        if (ClipSlicer.isWavFile(sampleFile)) {
            val outWav = File(shareDir, buildSafeProfileFileName(profileName, rankTag, "wav"))
            if (outWav.exists()) outWav.delete()
            sampleFile.copyTo(outWav, overwrite = true)
            return@withContext outWav
        }

        val outM4a = File(shareDir, buildSafeProfileFileName(profileName, rankTag, "m4a"))
        if (outM4a.exists()) outM4a.delete()

        if (sampleFile.name.endsWith(".m4a", ignoreCase = true)) {
            sampleFile.copyTo(outM4a, overwrite = true)
            outM4a
        } else if (ClipSlicer.convertAdtsToM4a(sampleFile, outM4a)) {
            outM4a
        } else {
            val fallbackAac = File(shareDir, buildSafeProfileFileName(profileName, rankTag, "aac"))
            sampleFile.copyTo(fallbackAac, overwrite = true)
            fallbackAac
        }
    }

    fun launchAudioFileShareIntent(
        context: Context,
        file: File,
        subject: String,
        mimeType: String = when {
            file.name.endsWith(".wav", true) -> "audio/wav"
            file.name.endsWith(".m4a", true) -> "audio/mp4"
            file.name.endsWith(".flac", true) -> "audio/flac"
            else -> "audio/aac"
        }
    ) {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Voice Sample").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun writeProfileSamplesZipToStream(
        appContext: Context,
        profileName: String,
        relatableTitle: String,
        voiceSignature: String,
        isUser: Boolean,
        samples: List<VoiceProfileSample>,
        outputStream: OutputStream,
        profileId: String = "",
        aliases: List<String> = emptyList(),
        isPinned: Boolean = false,
        sessionsHeardCount: Int = 1
    ): Pair<Long, Int> {
        val validSamples = samples.filter { s ->
            s.filePath.isNotBlank() && File(s.filePath).let { it.exists() && it.length() >= 1024L }
        }
        if (validSamples.isEmpty()) {
            throw IllegalStateException("No recorded voice samples found for this profile.")
        }

        val tempDir = File(appContext.cacheDir, "tmp_profile_zip_${System.currentTimeMillis()}").apply { mkdirs() }
        val counting = CountingOutputStream(BufferedOutputStream(outputStream, STREAM_BUFFER_SIZE))
        val metas = mutableListOf<ExportedVoiceSampleMeta>()
        var filesWritten = 0

        try {
            ZipOutputStream(counting).use { zos ->
                validSamples.forEachIndexed { idx, sample ->
                    val rank = idx + 1
                    val srcFile = File(sample.filePath)
                    val scoreInt = sample.qualityScore.toInt().coerceIn(0, 100)
                    val suffix = if (rank == 1) "01_best_q${scoreInt}" else String.format(Locale.US, "%02d_q%d", rank, scoreInt)
                    val isWav = ClipSlicer.isWavFile(srcFile)
                    val tmpM4a = File(tempDir, buildSafeProfileFileName(profileName, suffix, "m4a"))
                    val (entryName, fileToZip, fmtLabel) = when {
                        isWav -> Triple(buildSafeProfileFileName(profileName, suffix, "wav"), srcFile, "WAV")
                        srcFile.name.endsWith(".m4a", true) -> Triple(tmpM4a.name, srcFile, "M4A")
                        ClipSlicer.convertAdtsToM4a(srcFile, tmpM4a) -> Triple(tmpM4a.name, tmpM4a, "M4A")
                        else -> Triple(buildSafeProfileFileName(profileName, suffix, "aac"), srcFile, "AAC")
                    }
                    writeFileZipEntry(zos, "samples/$entryName", fileToZip)
                    filesWritten++
                    val createdMs = sample.createdAtMs.takeIf { it > 0L } ?: srcFile.lastModified()
                    val emb = sample.embedding.ifEmpty {
                        com.ambientbrain.recorder.ai.SpeakerEmbeddingEngine.extractEmbeddingFromFile(appContext, srcFile)
                    }
                    metas.add(
                        ExportedVoiceSampleMeta(
                            rank = rank,
                            fileName = "samples/$entryName",
                            durationMs = sample.durationMs.takeIf { it > 0L } ?: ClipSlicer.estimateAdtsDurationMs(srcFile),
                            qualityScore = sample.qualityScore,
                            snrDb = sample.snrDb,
                            speechRatio = sample.speechRatio,
                            isManual = sample.isManual,
                            createdAtIso = formatIso8601Utc(createdMs),
                            sourceSessionId = sample.sourceSessionId,
                            id = sample.id,
                            createdAtMs = createdMs,
                            embedding = emb,
                            audioFormat = fmtLabel
                        )
                    )
                }

                // Compute 48-D profile centroid from sample embeddings
                val sampleEmbeddings = metas.map { it.embedding }.filter { it.size >= 32 }
                val centroid = if (sampleEmbeddings.isNotEmpty()) {
                    val dim = sampleEmbeddings.first().size
                    val sum = FloatArray(dim)
                    var count = 0
                    for (vec in sampleEmbeddings) {
                        if (vec.size == dim) {
                            for (i in 0 until dim) sum[i] += vec[i]
                            count++
                        }
                    }
                    if (count > 0) {
                        var normSq = 0.0
                        for (i in 0 until dim) {
                            sum[i] /= count.toFloat()
                            normSq += sum[i] * sum[i]
                        }
                        val norm = kotlin.math.sqrt(normSq).toFloat().coerceAtLeast(1e-6f)
                        sum.map { kotlin.math.round((it / norm) * 10_000f) / 10_000f }
                    } else emptyList()
                } else emptyList()

                val summaryObj = ExportedVoiceProfileSummary(
                    format = "ambient_brain_voice_profile_v2",
                    profileId = profileId,
                    profileName = profileName,
                    relatableTitle = relatableTitle,
                    voiceSignature = voiceSignature,
                    aliases = aliases,
                    isUser = isUser,
                    isPinned = isPinned,
                    sessionsHeardCount = sessionsHeardCount,
                    centroidEmbedding = centroid,
                    exportedAtIso = formatIso8601Utc(System.currentTimeMillis()),
                    sampleCount = metas.size,
                    samples = metas
                )
                val summaryJsonBytes = prettyJson.encodeToString(summaryObj).toByteArray(Charsets.UTF_8)
                // Write both profile.json (v2 portable bundle standard) and profile_summary.json (v1 compatibility)
                writeBytesZipEntry(zos, "profile.json", summaryJsonBytes)
                writeBytesZipEntry(zos, "profile_summary.json", summaryJsonBytes)
                zos.flush()
            }
            counting.flush()
            return counting.bytesWritten to filesWritten
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
    }

    suspend fun exportProfileSamplesZipToUri(
        context: Context,
        profileName: String,
        relatableTitle: String,
        voiceSignature: String,
        isUser: Boolean,
        samples: List<VoiceProfileSample>,
        targetUri: Uri,
        profileId: String = "",
        aliases: List<String> = emptyList(),
        isPinned: Boolean = false,
        sessionsHeardCount: Int = 1
    ): ExportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val fileName = resolveDisplayNameFromUri(appContext, targetUri)
            ?: buildSafeProfileFileName(profileName, "voiceprofile", "zip")
        val out = appContext.contentResolver.openOutputStream(targetUri, "w")
            ?: throw IllegalStateException("Unable to open destination ZIP for writing.")
        val (bytes, count) = out.use { os ->
            writeProfileSamplesZipToStream(
                appContext = appContext,
                profileName = profileName,
                relatableTitle = relatableTitle,
                voiceSignature = voiceSignature,
                isUser = isUser,
                samples = samples,
                outputStream = os,
                profileId = profileId,
                aliases = aliases,
                isPinned = isPinned,
                sessionsHeardCount = sessionsHeardCount
            )
        }
        ExportResult(
            format = ExportFormat.ZIP_BUNDLE,
            fileName = fileName,
            bytesWritten = bytes,
            entryCount = count,
            summary = "Exported $count voice sample(s) for $profileName (${formatBytesHuman(bytes)})"
        )
    }

    suspend fun prepareProfileSamplesZipForShare(
        context: Context,
        profileName: String,
        relatableTitle: String,
        voiceSignature: String,
        isUser: Boolean,
        samples: List<VoiceProfileSample>,
        profileId: String = "",
        aliases: List<String> = emptyList(),
        isPinned: Boolean = false,
        sessionsHeardCount: Int = 1
    ): ExportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val shareDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val fileName = buildSafeProfileFileName(profileName, "${stamp}.voiceprofile", "zip")
        val outFile = File(shareDir, fileName)
        if (outFile.exists()) outFile.delete()
        val (bytes, count) = FileOutputStream(outFile).use { fos ->
            writeProfileSamplesZipToStream(
                appContext = appContext,
                profileName = profileName,
                relatableTitle = relatableTitle,
                voiceSignature = voiceSignature,
                isUser = isUser,
                samples = samples,
                outputStream = fos,
                profileId = profileId,
                aliases = aliases,
                isPinned = isPinned,
                sessionsHeardCount = sessionsHeardCount
            )
        }
        ExportResult(
            format = ExportFormat.ZIP_BUNDLE,
            fileName = fileName,
            bytesWritten = bytes,
            entryCount = count,
            summary = "Exported $count voice sample(s) for $profileName (${formatBytesHuman(bytes)})",
            cachedFile = outFile
        )
    }

    /**
     * 1-Tap Portable `.voiceprofile.zip` Bundle Importer:
     * Imports a previously exported `.voiceprofile.zip` (or any `.zip` containing `profile.json`,
     * `profile_summary.json`, or `.wav`/`.m4a`/`.aac`/`.flac` voice samples).
     *
     * - Restores up to [PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE] (10) highest-quality samples
     *   in lossless 16 kHz `.wav` format (or native `.aac` if already ADTS), preserving or recomputing
     *   their acoustic quality scores and 48-D speaker voiceprints.
     * - Enforces biometric speaker consistency via [com.ambientbrain.recorder.ai.SpeakerEmbeddingEngine.areEmbeddingsBiometricallyCompatible]
     *   so different speakers are never combined into the same voice profile.
     */
    suspend fun importVoiceProfileZipFromUri(
        context: Context,
        zipUri: Uri,
        targetIsUserProfile: Boolean? = null,
        targetSpeakerProfileId: String? = null
    ): VoiceProfileImportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = PreferencesRepo(appContext)
        val displayName = resolveDisplayNameFromUri(appContext, zipUri) ?: "voice_profile.zip"
        val tempExtractDir = File(appContext.cacheDir, "tmp_vp_import_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            var parsedSummary: ExportedVoiceProfileSummary? = null
            val extractedAudioByRelPath = LinkedHashMap<String, File>()

            val rawIn = appContext.contentResolver.openInputStream(zipUri)
                ?: throw IllegalStateException("Cannot open selected .voiceprofile.zip file.")

            rawIn.use { stream ->
                ZipInputStream(BufferedInputStream(stream)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val relName = entry.name.replace('\\', '/').trimStart('/')
                        if (!entry.isDirectory && !relName.contains("..")) {
                            val lower = relName.lowercase()
                            when {
                                lower == "profile.json" || lower == "profile_summary.json" || lower.endsWith("/profile.json") || lower.endsWith("/profile_summary.json") -> {
                                    val jsonBytes = readZipEntryBytesBounded(zis, 2_000_000L)
                                    val decoded = runCatching {
                                        json.decodeFromString<ExportedVoiceProfileSummary>(jsonBytes.toString(Charsets.UTF_8))
                                    }.getOrNull()
                                    if (decoded != null && (parsedSummary == null || lower.endsWith("profile.json"))) {
                                        parsedSummary = decoded
                                    }
                                }
                                lower.endsWith(".wav") || lower.endsWith(".m4a") || lower.endsWith(".aac") ||
                                    lower.endsWith(".flac") || lower.endsWith(".mp3") || lower.endsWith(".ogg") -> {
                                    val safeLeaf = "${extractedAudioByRelPath.size + 1}_${File(relName).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
                                    val tmpAudio = File(tempExtractDir, safeLeaf)
                                    copyStreamToFile(zis, tmpAudio, entry.time)
                                    if (tmpAudio.exists() && tmpAudio.length() >= 1024L) {
                                        extractedAudioByRelPath[relName] = tmpAudio
                                    }
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (extractedAudioByRelPath.isEmpty()) {
                throw IllegalArgumentException("No audio samples (.wav, .m4a, .aac) found inside $displayName.")
            }

            val fallbackNameFromZip = displayName
                .removeSuffix(".voiceprofile.zip")
                .removeSuffix(".zip")
                .replace(Regex("_voice_samples.*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("_voiceprofile.*$", RegexOption.IGNORE_CASE), "")
                .replace('_', ' ')
                .trim()
                .ifBlank { "Imported Voice Profile" }

            val summary = parsedSummary
            val effectiveIsUser = targetIsUserProfile ?: (summary?.isUser == true)
            val rawProfileName = summary?.profileName?.trim()?.takeIf { it.isNotBlank() } ?: fallbackNameFromZip
            val rawTitle = summary?.relatableTitle?.trim().orEmpty().ifBlank {
                if (effectiveIsUser) "Self / Device Owner" else "Imported Voice Profile"
            }
            val rawSignature = summary?.voiceSignature?.trim().orEmpty()

            // Determine target speaker profile ID (preventing combining different speakers!)
            val currentRoster = prefs.getSpeakerRoster()
            val summaryCentroid = summary?.centroidEmbedding?.takeIf { it.size >= 32 }.orEmpty()

            val resolvedSpeakerId: String = when {
                effectiveIsUser -> "my_voice"
                !targetSpeakerProfileId.isNullOrBlank() -> targetSpeakerProfileId
                else -> {
                    val existingByIdOrName = currentRoster.firstOrNull { entry ->
                        (summary?.profileId?.isNotBlank() == true && entry.id == summary.profileId) ||
                            entry.effectiveNickname().equals(rawProfileName, ignoreCase = true) ||
                            entry.displayName.equals(rawProfileName, ignoreCase = true)
                    }
                    if (existingByIdOrName != null) {
                        val existingCentroid = existingByIdOrName.voiceprintCentroid()
                        val compatible = if (existingCentroid.isNotEmpty() && summaryCentroid.isNotEmpty()) {
                            com.ambientbrain.recorder.ai.SpeakerEmbeddingEngine.areEmbeddingsBiometricallyCompatible(
                                existingCentroid,
                                summaryCentroid
                            )
                        } else {
                            true
                        }
                        if (compatible) {
                            existingByIdOrName.id
                        } else {
                            "spk_${java.util.UUID.randomUUID().toString().take(8)}"
                        }
                    } else {
                        summary?.profileId?.takeIf { it.isNotBlank() }
                            ?: "spk_${java.util.UUID.randomUUID().toString().take(8)}"
                    }
                }
            }

            // Build lookup from zip entry path/leaf name to ExportedVoiceSampleMeta
            val metaByPath = summary?.samples.orEmpty().associateBy { it.fileName.replace('\\', '/').trimStart('/') }
            val metaByLeaf = summary?.samples.orEmpty().associateBy { File(it.fileName).name }

            val existingAnchorCentroid: List<Float> = if (effectiveIsUser) {
                val myEmbs = prefs.getMyVoiceSamples().map { it.embedding }.filter { it.size >= 32 }
                if (myEmbs.isNotEmpty()) myEmbs.first() else summaryCentroid
            } else {
                currentRoster.firstOrNull { it.id == resolvedSpeakerId }?.voiceprintCentroid()?.takeIf { it.isNotEmpty() }
                    ?: summaryCentroid
            }

            var anchorEmbedding = existingAnchorCentroid
            var acceptedCount = 0
            var skippedOutliers = 0
            val importedSamples = mutableListOf<VoiceProfileSample>()

            for ((relPath, tmpFile) in extractedAudioByRelPath) {
                val meta = metaByPath[relPath] ?: metaByLeaf[File(relPath).name]
                val sampleId = meta?.id?.takeIf { it.isNotBlank() }
                    ?: java.util.UUID.randomUUID().toString().take(8)

                // Convert or copy into lossless 16 kHz .wav inside filesDir/profiles/
                val destWavFile = if (effectiveIsUser) {
                    prefs.getMyVoiceSampleWavFile(sampleId)
                } else {
                    prefs.getSpeakerSampleWavFile(resolvedSpeakerId, sampleId)
                }

                val savedFile: File? = when {
                    ClipSlicer.isWavFile(tmpFile) -> {
                        val pcm = ClipSlicer.readMono16kWavPcm(tmpFile, maxSeconds = 16)
                        if (pcm.size >= 1600 && ClipSlicer.writeMono16kWavFile(pcm, destWavFile)) {
                            destWavFile
                        } else {
                            tmpFile.copyTo(destWavFile, overwrite = true)
                            destWavFile
                        }
                    }
                    else -> {
                        val pcm = ClipSlicer.decodeAudioFileToMono16kPcm(tmpFile, maxSeconds = 16)
                        if (pcm.size >= 1600 && ClipSlicer.writeMono16kWavFile(pcm, destWavFile)) {
                            destWavFile
                        } else {
                            val fallbackAac = if (effectiveIsUser) {
                                prefs.getMyVoiceSampleAudioFile(sampleId, "aac")
                            } else {
                                prefs.getSpeakerSampleAudioFile(resolvedSpeakerId, sampleId, "aac")
                            }
                            tmpFile.copyTo(fallbackAac, overwrite = true)
                            fallbackAac
                        }
                    }
                }

                if (savedFile == null || !savedFile.exists() || savedFile.length() < 1024L) continue

                val metrics = ClipSlicer.analyzeAdtsSampleQuality(savedFile)
                val embedding = meta?.embedding?.takeIf { it.size >= 32 }
                    ?: com.ambientbrain.recorder.ai.SpeakerEmbeddingEngine.extractEmbeddingFromFile(appContext, savedFile)

                // Biometric purity guard: never combine a clip from a different speaker into this profile
                if (anchorEmbedding.isNotEmpty() && embedding.isNotEmpty()) {
                    val isCompatible = com.ambientbrain.recorder.ai.SpeakerEmbeddingEngine.areEmbeddingsBiometricallyCompatible(
                        anchorEmbedding,
                        embedding
                    )
                    if (!isCompatible) {
                        runCatching { savedFile.delete() }
                        skippedOutliers++
                        continue
                    }
                } else if (anchorEmbedding.isEmpty() && embedding.isNotEmpty()) {
                    anchorEmbedding = embedding
                }

                val qualityScore = (meta?.qualityScore?.takeIf { it > 0f } ?: (metrics.score + 8f)).coerceIn(10f, 100f)
                val durationMs = meta?.durationMs?.takeIf { it > 0L }
                    ?: metrics.durationMs.takeIf { it > 0L }
                    ?: ClipSlicer.estimateAdtsDurationMs(savedFile)

                val newSample = VoiceProfileSample(
                    id = sampleId,
                    filePath = savedFile.absolutePath,
                    durationMs = durationMs,
                    qualityScore = qualityScore,
                    snrDb = meta?.snrDb?.takeIf { it > 0f } ?: metrics.snrDb,
                    speechRatio = meta?.speechRatio?.takeIf { it > 0f } ?: metrics.speechRatio,
                    createdAtMs = meta?.createdAtMs?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    sourceSessionId = meta?.sourceSessionId,
                    isManual = meta?.isManual ?: true,
                    embedding = embedding
                )

                val accepted = if (effectiveIsUser) {
                    prefs.addMyVoiceSample(newSample).first
                } else {
                    importedSamples.add(newSample)
                    true
                }
                if (accepted) acceptedCount++
            }

            if (effectiveIsUser) {
                if (rawProfileName.isNotBlank() && prefs.userDisplayName.isBlank()) {
                    prefs.userDisplayName = rawProfileName
                }
                val mySamples = prefs.getMyVoiceSamples()
                return@withContext VoiceProfileImportResult(
                    profileId = "my_voice",
                    profileName = prefs.userDisplayName.ifBlank { rawProfileName },
                    relatableTitle = rawTitle,
                    voiceSignature = rawSignature,
                    isUser = true,
                    importedSamplesCount = acceptedCount,
                    skippedOutlierCount = skippedOutliers,
                    summaryMessage = "Imported $acceptedCount lossless WAV sample(s) into My Voice (${mySamples.size}/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} kept)."
                )
            }

            // Upsert or update the target Speaker Profile in roster
            val existingProfile = prefs.getSpeakerRoster().firstOrNull { it.id == resolvedSpeakerId }
            val acousticSig = if (rawSignature.isNotBlank()) {
                rawSignature
            } else if (anchorEmbedding.isNotEmpty()) {
                com.ambientbrain.recorder.ai.SpeakerEmbeddingEngine.describeAcousticVoiceFromEmbedding(anchorEmbedding)
                    ?.buildAcousticSignatureSummary()
                    .orEmpty()
            } else {
                ""
            }

            val finalNickname = existingProfile?.effectiveNickname()?.takeIf { !targetSpeakerProfileId.isNullOrBlank() && it.isNotBlank() }
                ?: rawProfileName
            val finalTitle = existingProfile?.effectiveRelatableTitle()?.takeIf { !targetSpeakerProfileId.isNullOrBlank() && it.isNotBlank() }
                ?: rawTitle
            val finalSignature = existingProfile?.voiceSignature?.takeIf { !targetSpeakerProfileId.isNullOrBlank() && it.isNotBlank() }
                ?: acousticSig

            if (existingProfile != null) {
                for (sample in importedSamples) {
                    prefs.addSpeakerVoiceSample(existingProfile.id, sample)
                }
                prefs.updateSpeakerProfileById(existingProfile.id) { prev ->
                    prev.copy(
                        nickname = finalNickname.ifBlank { prev.effectiveNickname() },
                        displayName = finalNickname.ifBlank { prev.displayName },
                        relatableTitle = finalTitle.ifBlank { prev.effectiveRelatableTitle() },
                        role = finalTitle.ifBlank { prev.role },
                        voiceSignature = finalSignature.ifBlank { prev.voiceSignature },
                        voiceHint = finalSignature.ifBlank { prev.voiceHint },
                        aliases = (prev.aliases + summary?.aliases.orEmpty()).distinctBy { it.lowercase() },
                        isPinned = prev.isPinned || (summary?.isPinned == true)
                    )
                }
            } else {
                val topSamples = importedSamples
                    .sortedByDescending { it.qualityScore }
                    .take(PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE)
                val best = topSamples.firstOrNull()
                val newEntry = SpeakerProfileEntry(
                    id = resolvedSpeakerId,
                    displayName = finalNickname,
                    nickname = finalNickname,
                    role = finalTitle,
                    relatableTitle = finalTitle,
                    voiceHint = finalSignature,
                    voiceSignature = finalSignature,
                    audioPath = best?.filePath,
                    samples = topSamples,
                    aliases = summary?.aliases.orEmpty(),
                    isPinned = summary?.isPinned ?: false,
                    isAutoDiscovered = false,
                    sessionsHeardCount = summary?.sessionsHeardCount?.coerceAtLeast(1) ?: 1,
                    lastHeardAtMs = System.currentTimeMillis(),
                    sampleDurationMs = best?.durationMs ?: 0L,
                    sourceSessionId = best?.sourceSessionId
                )
                prefs.upsertSpeakerProfile(newEntry)
            }

            val updatedEntry = prefs.getSpeakerRoster().firstOrNull { it.id == resolvedSpeakerId }
            val keptTotal = updatedEntry?.existingSamples()?.size ?: importedSamples.size
            val outlierNote = if (skippedOutliers > 0) " ($skippedOutliers mismatched-speaker clip(s) rejected)" else ""
            VoiceProfileImportResult(
                profileId = resolvedSpeakerId,
                profileName = finalNickname,
                relatableTitle = finalTitle,
                voiceSignature = finalSignature,
                isUser = false,
                importedSamplesCount = acceptedCount,
                skippedOutlierCount = skippedOutliers,
                summaryMessage = "Imported '$finalNickname' ($acceptedCount lossless WAV sample(s), $keptTotal/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} kept)$outlierNote"
            )
        } finally {
            runCatching { tempExtractDir.deleteRecursively() }
        }
    }

    suspend fun inspectBackupUri(
        context: Context,
        uri: Uri
    ): BackupInspection = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val displayName = resolveDisplayNameFromUri(appContext, uri) ?: "backup_file"
        val fileSize = resolveFileSizeFromUri(appContext, uri)
        val rawIn = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot read selected backup file.")

        rawIn.use { stream ->
            val buffered = BufferedInputStream(stream)
            val kind = detectStreamKind(buffered)
            when (kind) {
                BackupContainerKind.ZIP_ARCHIVE -> inspectZipStream(buffered, displayName, fileSize)
                BackupContainerKind.SETTINGS_JSON -> {
                    val text = buffered.readBytes().toString(Charsets.UTF_8)
                    val settings = PreferencesRepo(appContext).decodeSettingsBackupJson(text)
                    val keysCount = if (settings.includesApiKeys) {
                        (settings.savedApiKeysPool + listOf(settings.geminiApiKey)).filter { it.isNotBlank() }.distinct().size
                    } else 0
                    BackupInspection(
                        containerKind = BackupContainerKind.SETTINGS_JSON,
                        displayName = displayName,
                        fileSizeBytes = fileSize,
                        exportedAtMs = settings.exportedAtMs,
                        appVersionName = settings.appVersionName,
                        hasSettings = true,
                        settingsPayload = settings,
                        apiKeysCount = keysCount,
                        rosterCount = settings.speakerRoster.size
                    )
                }
                BackupContainerKind.DATABASE_JSON -> {
                    val text = buffered.readBytes().toString(Charsets.UTF_8)
                    val dbPayload = json.decodeFromString<BackupDatabasePayload>(text)
                    BackupInspection(
                        containerKind = BackupContainerKind.DATABASE_JSON,
                        displayName = displayName,
                        fileSizeBytes = fileSize,
                        exportedAtMs = dbPayload.exportedAtMs,
                        appVersionName = "",
                        hasSettings = false,
                        sessionsCount = dbPayload.sessions.size,
                        sentencesCount = dbPayload.sentences.size,
                        patientsCount = dbPayload.patients.size,
                        itemsCount = dbPayload.items.size,
                        analysisRunsCount = dbPayload.analysisRuns.size
                    )
                }
            }
        }
    }

    suspend fun restoreFromUri(
        context: Context,
        uri: Uri,
        options: RestoreSelectionOptions,
        onProgress: (String) -> Unit = {}
    ): RestoreResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = PreferencesRepo(appContext)
        val dao = AppDatabase.get(appContext).dao()

        val rawIn = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open backup file for restore.")

        rawIn.use { stream ->
            val buffered = BufferedInputStream(stream)
            val kind = detectStreamKind(buffered)

            when (kind) {
                BackupContainerKind.SETTINGS_JSON -> {
                    if (!options.restoreSettings) {
                        return@withContext RestoreResult(false, 0, false, 0, 0, 0, 0, 0, 0, 0)
                    }
                    onProgress("Applying settings backup...")
                    val text = buffered.readBytes().toString(Charsets.UTF_8)
                    val payload = prefs.decodeSettingsBackupJson(text)
                    val rosterCount = prefs.importSettingsPayload(payload, restoreApiKeys = options.restoreApiKeys)
                    val keysApplied = options.restoreApiKeys && payload.includesApiKeys &&
                        (payload.geminiApiKey.isNotBlank() || payload.savedApiKeysPool.isNotEmpty())
                    RestoreResult(
                        settingsApplied = true,
                        rosterMergedCount = rosterCount,
                        apiKeysRestored = keysApplied,
                        voiceProfilesRestored = 0,
                        sessionsRestored = 0,
                        itemsRestored = 0,
                        patientsRestored = 0,
                        sentencesRestored = 0,
                        clipsRestored = 0,
                        rawAudioRestored = 0
                    )
                }

                BackupContainerKind.DATABASE_JSON -> {
                    if (!options.restoreDatabaseRecords) {
                        return@withContext RestoreResult(false, 0, false, 0, 0, 0, 0, 0, 0, 0)
                    }
                    onProgress("Importing database records...")
                    val text = buffered.readBytes().toString(Charsets.UTF_8)
                    val dbPayload = json.decodeFromString<BackupDatabasePayload>(text)
                    applyDatabaseRestore(appContext, dao, dbPayload, emptyMap(), emptyMap())
                }

                BackupContainerKind.ZIP_ARCHIVE -> {
                    val profilesDir = File(appContext.filesDir, "profiles").apply { mkdirs() }
                    val clipsDir = File(appContext.filesDir, "clips").apply { mkdirs() }
                    val sessionsDir = File(appContext.filesDir, "sessions").apply { mkdirs() }

                    var settingsPayload: SettingsBackupPayload? = null
                    var dbPayload: BackupDatabasePayload? = null
                    var profilesRestored = 0
                    var clipsRestored = 0
                    var rawRestored = 0
                    val restoredClipsByRelPath = mutableMapOf<String, File>()
                    val restoredRawByRelPath = mutableMapOf<String, File>()

                    ZipInputStream(buffered).use { zis ->
                        var entry: ZipEntry? = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name.replace('\\', '/').trimStart('/')
                            if (!entry.isDirectory && !name.contains("..")) {
                                when {
                                    name == ZIP_SETTINGS_PATH -> {
                                        if (options.restoreSettings) {
                                            onProgress("Reading settings backup...")
                                            val rawJson = readZipEntryBytesBounded(zis, maxBytes = 4_000_000L).toString(Charsets.UTF_8)
                                            settingsPayload = runCatching { prefs.decodeSettingsBackupJson(rawJson) }.getOrNull()
                                        }
                                    }

                                    name == ZIP_DB_PATH -> {
                                        if (options.restoreDatabaseRecords) {
                                            onProgress("Reading sessions, rounds & transcripts...")
                                            val rawDb = readZipEntryBytesBounded(zis, maxBytes = 64_000_000L).toString(Charsets.UTF_8)
                                            dbPayload = json.decodeFromString<BackupDatabasePayload>(rawDb)
                                        }
                                    }

                                    name.startsWith("audio/profiles/") -> {
                                        if (options.restoreVoiceProfiles) {
                                            val dest = safeResolveZipTarget(profilesDir, name)
                                            if (dest != null) {
                                                onProgress("Restoring voice profile ${dest.name}...")
                                                copyStreamToFile(zis, dest, entry.time)
                                                profilesRestored++
                                            }
                                        }
                                    }

                                    name.startsWith("audio/clips/") -> {
                                        if (options.restoreProofClips) {
                                            val dest = safeResolveZipTarget(clipsDir, name)
                                            if (dest != null) {
                                                onProgress("Restoring proof clip ${dest.name}...")
                                                copyStreamToFile(zis, dest, entry.time)
                                                clipsRestored++
                                                restoredClipsByRelPath[name] = dest
                                            }
                                        }
                                    }

                                    name.startsWith("audio/sessions/") -> {
                                        if (options.restoreRawSessionAudio) {
                                            val dest = safeResolveZipTarget(sessionsDir, name)
                                            if (dest != null) {
                                                onProgress("Restoring session audio ${dest.name}...")
                                                copyStreamToFile(zis, dest, entry.time)
                                                rawRestored++
                                                restoredRawByRelPath[name] = dest
                                            }
                                        }
                                    }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }

                    var settingsApplied = false
                    var rosterCount = 0
                    var keysApplied = false
                    if (options.restoreSettings && settingsPayload != null) {
                        val sp = settingsPayload!!
                        rosterCount = prefs.importSettingsPayload(sp, restoreApiKeys = options.restoreApiKeys)
                        settingsApplied = true
                        keysApplied = options.restoreApiKeys && sp.includesApiKeys &&
                            (sp.geminiApiKey.isNotBlank() || sp.savedApiKeysPool.isNotEmpty())
                    }

                    val dbCounts = if (options.restoreDatabaseRecords && dbPayload != null) {
                        onProgress("Merging database tables...")
                        applyDatabaseRestore(appContext, dao, dbPayload!!, restoredClipsByRelPath, restoredRawByRelPath)
                    } else {
                        RestoreResult(false, 0, false, 0, 0, 0, 0, 0, 0, 0)
                    }

                    RestoreResult(
                        settingsApplied = settingsApplied,
                        rosterMergedCount = rosterCount,
                        apiKeysRestored = keysApplied,
                        voiceProfilesRestored = profilesRestored,
                        sessionsRestored = dbCounts.sessionsRestored,
                        itemsRestored = dbCounts.itemsRestored,
                        patientsRestored = dbCounts.patientsRestored,
                        sentencesRestored = dbCounts.sentencesRestored,
                        clipsRestored = clipsRestored,
                        rawAudioRestored = rawRestored
                    )
                }
            }
        }
    }

    private suspend fun writeExportToStream(
        appContext: Context,
        outputStream: OutputStream,
        fileName: String,
        config: ExportSelectionConfig,
        onProgress: (String) -> Unit
    ): ExportResult {
        val prefs = PreferencesRepo(appContext)
        return when (config.format) {
            ExportFormat.SETTINGS_JSON -> {
                onProgress("Serializing settings & speaker roster...")
                val jsonText = prefs.encodeSettingsBackupJson(includeApiKeys = config.includeApiKeys)
                val bytes = jsonText.toByteArray(Charsets.UTF_8)
                outputStream.write(bytes)
                outputStream.flush()
                val keysMsg = if (config.includeApiKeys) " (with API keys)" else ""
                ExportResult(
                    format = ExportFormat.SETTINGS_JSON,
                    fileName = fileName,
                    bytesWritten = bytes.size.toLong(),
                    entryCount = 1,
                    summary = "Settings & Glossary JSON backup saved$keysMsg (${formatBytesHuman(bytes.size.toLong())})"
                )
            }

            ExportFormat.MARKDOWN_REPORT -> {
                onProgress("Formatting Markdown report...")
                val dataset = collectDataset(appContext, config)
                val report = buildMarkdownReport(appContext, dataset, prefs)
                val bytes = report.toByteArray(Charsets.UTF_8)
                outputStream.write(bytes)
                outputStream.flush()
                ExportResult(
                    format = ExportFormat.MARKDOWN_REPORT,
                    fileName = fileName,
                    bytesWritten = bytes.size.toLong(),
                    entryCount = dataset.filteredSessions.size + dataset.items.size,
                    summary = "Markdown report exported: ${dataset.filteredSessions.size} session(s), ${dataset.items.size} item(s)"
                )
            }

            ExportFormat.CSV_ITEMS -> {
                onProgress("Building Tasks & Notes CSV...")
                val dataset = collectDataset(appContext, config)
                val csv = buildItemsCsv(dataset.items)
                // Prefix UTF-8 BOM so Microsoft Excel renders Thai text cleanly without mojibake
                val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                val body = csv.toByteArray(Charsets.UTF_8)
                outputStream.write(bom)
                outputStream.write(body)
                outputStream.flush()
                val totalBytes = (bom.size + body.size).toLong()
                ExportResult(
                    format = ExportFormat.CSV_ITEMS,
                    fileName = fileName,
                    bytesWritten = totalBytes,
                    entryCount = dataset.items.size,
                    summary = "CSV spreadsheet exported with ${dataset.items.size} item(s) (${formatBytesHuman(totalBytes)})"
                )
            }

            ExportFormat.ZIP_BUNDLE -> {
                onProgress("Collecting selected app data...")
                val dataset = collectDataset(appContext, config)
                val counting = CountingOutputStream(BufferedOutputStream(outputStream, STREAM_BUFFER_SIZE))
                var entriesWritten = 0
                val audioManifestEntries = mutableListOf<BackupAudioFileManifestEntry>()

                ZipOutputStream(counting).use { zos ->
                    // 1. Settings backup JSON (when requested)
                    if (config.includeSettings && dataset.settingsPayload != null) {
                        onProgress("Writing settings backup...")
                        val settingsJsonBytes = prettyJson.encodeToString(dataset.settingsPayload).toByteArray(Charsets.UTF_8)
                        writeBytesZipEntry(zos, ZIP_SETTINGS_PATH, settingsJsonBytes)
                        entriesWritten++
                    }

                    // 2. Database JSON (when any DB data selected)
                    val hasDbContent = config.includeSessionsAndSummaries ||
                        config.includeTranscripts ||
                        config.includeClinicalRounds ||
                        config.includeItems ||
                        config.includeAnalysisRuns
                    if (hasDbContent) {
                        onProgress("Writing structured database JSON...")
                        val dbPayload = buildDatabasePayload(dataset, config)
                        val dbJsonBytes = prettyJson.encodeToString(dbPayload).toByteArray(Charsets.UTF_8)
                        writeBytesZipEntry(zos, ZIP_DB_PATH, dbJsonBytes)
                        entriesWritten++
                    }

                    // 3. Human-readable Markdown report & CSV inside ZIP for easy browsing on any PC/phone
                    if (dataset.filteredSessions.isNotEmpty() || dataset.items.isNotEmpty() || dataset.patients.isNotEmpty()) {
                        onProgress("Generating readable Markdown & CSV views...")
                        val mdBytes = buildMarkdownReport(appContext, dataset, prefs).toByteArray(Charsets.UTF_8)
                        writeBytesZipEntry(zos, ZIP_MARKDOWN_PATH, mdBytes)
                        entriesWritten++
                    }
                    if (config.includeItems && dataset.items.isNotEmpty()) {
                        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                        val csvBody = buildItemsCsv(dataset.items).toByteArray(Charsets.UTF_8)
                        val combinedCsv = ByteArray(bom.size + csvBody.size)
                        System.arraycopy(bom, 0, combinedCsv, 0, bom.size)
                        System.arraycopy(csvBody, 0, combinedCsv, bom.size, csvBody.size)
                        writeBytesZipEntry(zos, ZIP_CSV_PATH, combinedCsv)
                        entriesWritten++
                    }

                    // 4. Audio files (Voice Profiles, Proof Clips, Raw Session Audio)
                    val allAudio = dataset.voiceProfileFiles + dataset.proofClipFiles + dataset.rawAudioFiles
                    val totalAudio = allAudio.size
                    allAudio.forEachIndexed { idx, bundle ->
                        onProgress("Packaging audio ${idx + 1}/$totalAudio: ${bundle.file.name}...")
                        writeFileZipEntry(zos, bundle.zipPath, bundle.file, bundle.recordedAtMs)
                        entriesWritten++
                        audioManifestEntries.add(
                            BackupAudioFileManifestEntry(
                                zipPath = bundle.zipPath,
                                category = bundle.category,
                                originalName = bundle.file.name,
                                sizeBytes = bundle.file.length()
                            )
                        )
                    }

                    // 5. Manifest JSON
                    val manifest = buildManifest(appContext, config, dataset, audioManifestEntries)
                    val manifestBytes = prettyJson.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                    writeBytesZipEntry(zos, ZIP_MANIFEST_PATH, manifestBytes)
                    entriesWritten++
                }

                val writtenBytes = counting.bytesWritten
                val highlights = buildList {
                    if (config.includeSettings) add("Settings")
                    if (dataset.voiceProfileFiles.isNotEmpty()) add("${dataset.voiceProfileFiles.size} profile(s)")
                    if (dataset.filteredSessions.isNotEmpty()) add("${dataset.filteredSessions.size} session(s)")
                    if (dataset.items.isNotEmpty()) add("${dataset.items.size} item(s)")
                    if (dataset.proofClipFiles.isNotEmpty()) add("${dataset.proofClipFiles.size} clip(s)")
                    if (dataset.rawAudioFiles.isNotEmpty()) add("${dataset.rawAudioFiles.size} raw .aac")
                }.joinToString(" • ")

                ExportResult(
                    format = ExportFormat.ZIP_BUNDLE,
                    fileName = fileName,
                    bytesWritten = writtenBytes,
                    entryCount = entriesWritten,
                    summary = "ZIP backup exported (${formatBytesHuman(writtenBytes)}): ${highlights.ifBlank { "Archive ready" }}"
                )
            }
        }
    }

    private suspend fun collectDataset(
        appContext: Context,
        config: ExportSelectionConfig
    ): ExportDataset {
        val prefs = PreferencesRepo(appContext)
        val dao = AppDatabase.get(appContext).dao()

        val allSessionsWithDetails = dao.getAllSessionsWithDetailsOnce()
        val allSegmentMaps = dao.getAllSegmentMapsOnce()
        val allAnalysisRuns = dao.getAllAnalysisRunsOnce()

        val cutoffMs = config.timeFilter.days?.let { days ->
            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        }

        val filteredSessions = allSessionsWithDetails.filter { details ->
            val s = details.session
            if (config.selectedSessionIds != null && s.id !in config.selectedSessionIds) {
                return@filter false
            }
            if (cutoffMs != null && s.endMs < cutoffMs && s.startMs < cutoffMs) {
                return@filter false
            }
            if (config.roundsOnlyFilter) {
                val isRound = s.mode in setOf(SessionMode.ROUND_MANUAL, SessionMode.ROUND_AUTO) ||
                    s.context in setOf("NEURO_ROUND", "MEDICAL_ROUND", "CASE_CONFERENCE", "HANDOVER")
                if (!isRound) return@filter false
            }
            true
        }

        val filteredSessionIds = filteredSessions.mapTo(HashSet()) { it.session.id }

        val items = if (config.includeItems) {
            filteredSessions
                .flatMap { it.items }
                .filter { config.includeArchivedItems || !it.isArchived }
                .sortedByDescending { it.createdMs }
        } else {
            emptyList()
        }

        val patients = if (config.includeClinicalRounds) {
            filteredSessions
                .flatMap { it.patients }
                .sortedWith(compareBy<PatientBlockEntity> { it.sessionId }.thenBy { it.approxStartMs })
        } else {
            emptyList()
        }

        val sentences = if (config.includeTranscripts) {
            filteredSessions
                .flatMap { it.sentences }
                .sortedWith(compareBy<TranscriptSentenceEntity> { it.sessionId }.thenBy { it.ordinal })
        } else {
            emptyList()
        }

        val speakers = if (config.includeTranscripts || config.includeSessionsAndSummaries) {
            filteredSessions.flatMap { it.speakers }
        } else {
            emptyList()
        }

        val segmentMaps = if (config.includeTranscripts || config.includeSessionsAndSummaries) {
            allSegmentMaps.filter { it.sessionId in filteredSessionIds }
        } else {
            emptyList()
        }

        val analysisRuns = if (config.includeAnalysisRuns) {
            allAnalysisRuns.filter { it.sessionId in filteredSessionIds }
        } else {
            emptyList()
        }

        // Ensure every exported child entity has its parent SessionEntity included so restores never fail FK checks
        val requiredSessionIds = buildSet {
            if (config.includeSessionsAndSummaries || config.includeClinicalRounds || config.includeRawSessionAudio) {
                addAll(filteredSessionIds)
            }
            items.forEach { add(it.sessionId) }
            patients.forEach { add(it.sessionId) }
            sentences.forEach { add(it.sessionId) }
            speakers.forEach { add(it.sessionId) }
            segmentMaps.forEach { add(it.sessionId) }
        }
        val exportSessions = filteredSessions
            .map { it.session }
            .filter { it.id in requiredSessionIds }
            .sortedBy { it.startMs }

        val isUnfilteredAllScope = config.selectedSessionIds == null &&
            config.timeFilter == ExportTimeFilter.ALL_TIME &&
            !config.roundsOnlyFilter
        val sessionsById = allSessionsWithDetails.associate { it.session.id to it.session }

        // Voice Profiles (.aac — up to 10 best-quality samples per profile)
        val voiceProfileFiles = mutableListOf<BundledAudioFile>()
        if (config.includeVoiceProfiles) {
            val seenProfilePaths = HashSet<String>()
            val profilesDir = File(appContext.filesDir, "profiles")
            prefs.getMyVoiceSamples().forEach { s ->
                val f = File(s.filePath)
                if (f.exists() && f.length() > 0L && seenProfilePaths.add(f.canonicalPath)) {
                    val srcSessionStart = s.sourceSessionId?.let { sessionsById[it]?.startMs }
                    val recordedMs = srcSessionStart?.takeIf { it > 0L } ?: s.createdAtMs
                    voiceProfileFiles.add(BundledAudioFile(f, "audio/profiles/${f.name}", "profile", recordedMs))
                }
            }
            val legacyMyFile = prefs.getMyVoiceProfileFile()
            if (legacyMyFile.exists() && legacyMyFile.length() > 0L && seenProfilePaths.add(legacyMyFile.canonicalPath)) {
                voiceProfileFiles.add(BundledAudioFile(legacyMyFile, "audio/profiles/${legacyMyFile.name}", "profile"))
            }
            prefs.getSpeakerRoster().forEach { spk ->
                spk.existingSamples().forEach { s ->
                    val f = File(s.filePath)
                    if (f.exists() && f.length() > 0L && seenProfilePaths.add(f.canonicalPath)) {
                        val srcSessionStart = s.sourceSessionId?.let { sessionsById[it]?.startMs }
                        val recordedMs = srcSessionStart?.takeIf { it > 0L } ?: s.createdAtMs
                        voiceProfileFiles.add(BundledAudioFile(f, "audio/profiles/${f.name}", "profile", recordedMs))
                    }
                }
                val fallbackFile = spk.audioPath?.let { File(it) } ?: prefs.getSpeakerProfileAudioFile(spk.id)
                if (fallbackFile.exists() && fallbackFile.length() > 0L && seenProfilePaths.add(fallbackFile.canonicalPath)) {
                    voiceProfileFiles.add(BundledAudioFile(fallbackFile, "audio/profiles/${fallbackFile.name}", "profile"))
                }
            }
            profilesDir.listFiles()
                ?.filter {
                    it.isFile && it.length() > 0L && (
                        it.name.endsWith(".wav", true) ||
                            it.name.endsWith(".aac", true) ||
                            it.name.endsWith(".m4a", true)
                        )
                }
                ?.forEach { f ->
                    if (seenProfilePaths.add(f.canonicalPath)) {
                        voiceProfileFiles.add(BundledAudioFile(f, "audio/profiles/${f.name}", "profile"))
                    }
                }
        }

        // Permanent Voice Proof Clips (.m4a)
        val proofClipFiles = mutableListOf<BundledAudioFile>()
        val clipZipPathByItemId = mutableMapOf<String, String>()
        if (config.includeProofClips) {
            val seenClipPaths = HashSet<String>()
            val clipSourceItems = if (items.isNotEmpty()) items else {
                filteredSessions.flatMap { it.items }.filter { config.includeArchivedItems || !it.isArchived }
            }
            clipSourceItems.forEach { item ->
                val path = item.clipPath ?: return@forEach
                val f = File(path)
                if (f.exists() && f.length() > 0L) {
                    val zipPath = "audio/clips/${f.name}"
                    clipZipPathByItemId[item.id] = zipPath
                    if (seenClipPaths.add(f.canonicalPath)) {
                        val parentSession = sessionsById[item.sessionId]
                        val recordedMs = if (parentSession != null && parentSession.startMs > 0L) {
                            (parentSession.startMs + item.clipStartMs)
                                .coerceIn(parentSession.startMs, parentSession.endMs + 60_000L)
                        } else {
                            item.createdMs
                        }
                        proofClipFiles.add(BundledAudioFile(f, zipPath, "clip", recordedMs))
                    }
                }
            }
            if (isUnfilteredAllScope && config.includeArchivedItems) {
                File(appContext.filesDir, "clips").listFiles()
                    ?.filter { it.isFile && it.length() > 0L && (it.name.endsWith(".m4a", true) || it.name.endsWith(".aac", true)) }
                    ?.forEach { f ->
                        if (seenClipPaths.add(f.canonicalPath)) {
                            proofClipFiles.add(BundledAudioFile(f, "audio/clips/${f.name}", "clip"))
                        }
                    }
            }
        }

        // Raw Session Audio (.aac)
        val rawAudioFiles = mutableListOf<BundledAudioFile>()
        val sessionZipPathById = mutableMapOf<String, String>()
        if (config.includeRawSessionAudio) {
            val seenRawPaths = HashSet<String>()
            filteredSessions.forEach { details ->
                val s = details.session
                if (!s.combinedSourceIdsJson.isNullOrBlank()) return@forEach
                val path = s.rawPath ?: return@forEach
                val f = File(path)
                if (f.exists() && f.length() > 0L) {
                    val zipPath = "audio/sessions/${f.name}"
                    sessionZipPathById[s.id] = zipPath
                    if (seenRawPaths.add(f.canonicalPath)) {
                        rawAudioFiles.add(BundledAudioFile(f, zipPath, "session_raw", s.startMs))
                    }
                }
            }
            if (isUnfilteredAllScope) {
                File(appContext.filesDir, "sessions").listFiles()
                    ?.filter { it.isFile && it.length() > 0L && (it.name.endsWith(".aac", true) || it.name.endsWith(".m4a", true)) }
                    ?.forEach { f ->
                        if (seenRawPaths.add(f.canonicalPath)) {
                            rawAudioFiles.add(BundledAudioFile(f, "audio/sessions/${f.name}", "session_raw"))
                        }
                    }
            }
        }

        val settingsPayload = if (config.includeSettings) {
            prefs.exportSettingsPayload(includeApiKeys = config.includeApiKeys)
        } else {
            null
        }

        return ExportDataset(
            filteredSessions = filteredSessions,
            exportSessions = exportSessions,
            segmentMaps = segmentMaps,
            speakers = speakers,
            sentences = sentences,
            patients = patients,
            items = items,
            analysisRuns = analysisRuns,
            voiceProfileFiles = voiceProfileFiles,
            proofClipFiles = proofClipFiles,
            rawAudioFiles = rawAudioFiles,
            sessionZipPathById = sessionZipPathById,
            clipZipPathByItemId = clipZipPathByItemId,
            settingsPayload = settingsPayload
        )
    }

    private fun buildDatabasePayload(
        dataset: ExportDataset,
        config: ExportSelectionConfig
    ): BackupDatabasePayload {
        val includeFullSessionMeta = config.includeSessionsAndSummaries || config.includeClinicalRounds
        return BackupDatabasePayload(
            format = DB_BACKUP_FORMAT,
            schemaVersion = 6,
            exportedAtMs = System.currentTimeMillis(),
            sessions = dataset.exportSessions.map { s ->
                val rawZipRel = dataset.sessionZipPathById[s.id]
                BackupSessionDto(
                    id = s.id,
                    roundGroupId = s.roundGroupId,
                    eventGroupId = s.eventGroupId,
                    combinedSourceIdsJson = s.combinedSourceIdsJson,
                    mode = s.mode.name,
                    startMs = s.startMs,
                    endMs = s.endMs,
                    speechMs = s.speechMs,
                    hadPhoneCall = s.hadPhoneCall,
                    callEventsJson = s.callEventsJson,
                    rawZipRelPath = rawZipRel,
                    rawFileName = rawZipRel?.substringAfterLast('/') ?: s.rawPath?.let { File(it).name },
                    rawBytes = if (rawZipRel != null) s.rawBytes else 0L,
                    isRawPurged = if (rawZipRel != null) false else s.isRawPurged,
                    isPinned = s.isPinned,
                    context = s.context,
                    title = if (includeFullSessionMeta) s.title else (s.title ?: "Restored Session"),
                    summary = if (includeFullSessionMeta) s.summary else null,
                    keyPointsJson = if (includeFullSessionMeta) s.keyPointsJson else null,
                    status = s.status.name,
                    lastError = if (includeFullSessionMeta) s.lastError else null,
                    retryCount = s.retryCount,
                    nextRetryEpochMs = s.nextRetryEpochMs,
                    aiReviewReport = if (includeFullSessionMeta) s.aiReviewReport else null,
                    reviewedAtMs = if (includeFullSessionMeta) s.reviewedAtMs else 0L,
                    reviewSourceMode = if (includeFullSessionMeta) s.reviewSourceMode else null
                )
            },
            segmentMaps = dataset.segmentMaps.map {
                BackupSegmentMapDto(it.sessionId, it.fileOffsetMs, it.wallClockMs, it.lengthMs)
            },
            speakers = dataset.speakers.map {
                BackupSpeakerDto(
                    sessionId = it.sessionId,
                    speakerId = it.speakerId,
                    speakerLabel = it.speakerLabel,
                    inferredRole = it.inferredRole,
                    isUser = it.isUser,
                    matchedProfileId = it.matchedProfileId,
                    nickname = it.nickname,
                    relatableTitle = it.relatableTitle,
                    voiceSignature = it.voiceSignature,
                    isAutoEnrolled = it.isAutoEnrolled
                )
            },
            sentences = dataset.sentences.map {
                BackupSentenceDto(
                    sentenceUuid = it.sentenceUuid,
                    sessionId = it.sessionId,
                    ordinal = it.ordinal,
                    approxMs = it.approxMs,
                    speakerId = it.speakerId,
                    speakerLabel = it.speakerLabel,
                    textOriginal = it.textOriginal,
                    textEn = it.textEn
                )
            },
            patients = dataset.patients.map {
                BackupPatientDto(
                    id = it.id,
                    sessionId = it.sessionId,
                    roundGroupId = it.roundGroupId,
                    patientIdentifier = it.patientIdentifier,
                    approxStartMs = it.approxStartMs,
                    approxEndMs = it.approxEndMs,
                    oneLiner = it.oneLiner,
                    neuroExam = it.neuroExam,
                    assessment = it.assessment,
                    problemsJson = it.problemsJson,
                    resultsJson = it.resultsJson,
                    pendingJson = it.pendingJson,
                    planJson = it.planJson,
                    teachingJson = it.teachingJson
                )
            },
            items = dataset.items.map { item ->
                val clipZipRel = dataset.clipZipPathByItemId[item.id]
                BackupItemDto(
                    id = item.id,
                    sessionId = item.sessionId,
                    patientIdentifier = item.patientIdentifier,
                    type = item.type.name,
                    title = item.title,
                    details = item.details,
                    speakerId = item.speakerId,
                    speakerLabel = item.speakerLabel,
                    assignee = item.assignee,
                    isForUser = item.isForUser,
                    due = item.due,
                    priority = item.priority,
                    quoteOriginal = item.quoteOriginal,
                    quoteEn = item.quoteEn,
                    needsVerification = item.needsVerification,
                    clipStartMs = item.clipStartMs,
                    clipEndMs = item.clipEndMs,
                    clipZipRelPath = clipZipRel,
                    clipFileName = clipZipRel?.substringAfterLast('/') ?: item.clipPath?.let { File(it).name },
                    isDone = item.isDone,
                    isArchived = item.isArchived,
                    createdMs = item.createdMs
                )
            },
            analysisRuns = dataset.analysisRuns.map {
                BackupAnalysisRunDto(
                    sessionId = it.sessionId,
                    model = it.model,
                    promptVersion = it.promptVersion,
                    inTokens = it.inTokens,
                    outTokens = it.outTokens,
                    cachedTokens = it.cachedTokens,
                    latencyMs = it.latencyMs,
                    ok = it.ok,
                    errorMsg = it.errorMsg,
                    atMs = it.atMs
                )
            }
        )
    }

    private fun buildManifest(
        appContext: Context,
        config: ExportSelectionConfig,
        dataset: ExportDataset,
        audioEntries: List<BackupAudioFileManifestEntry>
    ): BackupManifest {
        val nowMs = System.currentTimeMillis()
        val pkgInfo = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0) }.getOrNull()
        val apiKeysCount = dataset.settingsPayload?.let {
            (it.savedApiKeysPool + listOf(it.geminiApiKey)).filter { k -> k.isNotBlank() }.distinct().size
        } ?: 0
        return BackupManifest(
            format = ZIP_BACKUP_FORMAT,
            bundleVersion = 1,
            dbSchemaVersion = 6,
            appVersionName = pkgInfo?.versionName.orEmpty(),
            appVersionCode = pkgInfo?.longVersionCode ?: 0L,
            exportedAtMs = nowMs,
            exportedAtIso = formatIso8601Utc(nowMs),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            preset = config.preset.name,
            timeFilter = config.timeFilter.name,
            roundsOnlyFilter = config.roundsOnlyFilter,
            selectedSessionCount = config.selectedSessionIds?.size ?: dataset.filteredSessions.size,
            includesSettings = config.includeSettings,
            includesApiKeys = config.includeSettings && config.includeApiKeys && apiKeysCount > 0,
            includesVoiceProfiles = config.includeVoiceProfiles && dataset.voiceProfileFiles.isNotEmpty(),
            includesSessions = config.includeSessionsAndSummaries && dataset.exportSessions.isNotEmpty(),
            includesTranscripts = config.includeTranscripts && dataset.sentences.isNotEmpty(),
            includesPatients = config.includeClinicalRounds && dataset.patients.isNotEmpty(),
            includesItems = config.includeItems && dataset.items.isNotEmpty(),
            includesAnalysisRuns = config.includeAnalysisRuns && dataset.analysisRuns.isNotEmpty(),
            includesClips = config.includeProofClips && dataset.proofClipFiles.isNotEmpty(),
            includesRawAudio = config.includeRawSessionAudio && dataset.rawAudioFiles.isNotEmpty(),
            counts = BackupCounts(
                sessions = dataset.exportSessions.size,
                segmentMaps = dataset.segmentMaps.size,
                speakers = dataset.speakers.size,
                sentences = dataset.sentences.size,
                patients = dataset.patients.size,
                items = dataset.items.size,
                analysisRuns = dataset.analysisRuns.size,
                voiceProfileAudioFiles = dataset.voiceProfileFiles.size,
                proofClipFiles = dataset.proofClipFiles.size,
                rawAudioFiles = dataset.rawAudioFiles.size,
                speakerRosterCount = dataset.settingsPayload?.speakerRoster?.size ?: 0,
                apiKeyCount = apiKeysCount
            ),
            audioFiles = audioEntries
        )
    }

    private suspend fun applyDatabaseRestore(
        appContext: Context,
        dao: AppDao,
        dbPayload: BackupDatabasePayload,
        restoredClipsByRelPath: Map<String, File>,
        restoredRawByRelPath: Map<String, File>
    ): RestoreResult {
        val sessionsById = dbPayload.sessions.associateBy { it.id }
        val segmentsBySession = dbPayload.segmentMaps.groupBy { it.sessionId }
        val speakersBySession = dbPayload.speakers.groupBy { it.sessionId }
        val sentencesBySession = dbPayload.sentences.groupBy { it.sessionId }
        val patientsBySession = dbPayload.patients.groupBy { it.sessionId }
        val itemsBySession = dbPayload.items.groupBy { it.sessionId }
        val runsBySession = dbPayload.analysisRuns.groupBy { it.sessionId }

        val allTargetSessionIds = (
            sessionsById.keys +
                segmentsBySession.keys +
                speakersBySession.keys +
                sentencesBySession.keys +
                patientsBySession.keys +
                itemsBySession.keys +
                runsBySession.keys
            ).distinct()

        val clipsDir = File(appContext.filesDir, "clips").apply { mkdirs() }
        var restoredSessionsCount = 0
        var restoredItemsCount = 0
        var restoredPatientsCount = 0
        var restoredSentencesCount = 0

        for (sessionId in allTargetSessionIds) {
            val dto = sessionsById[sessionId]
            val existingLocal = dao.getSessionById(sessionId)

            val restoredRawFile = dto?.rawZipRelPath?.let { restoredRawByRelPath[it] }
                ?.takeIf { it.exists() && it.length() > 0L }
            val resolvedRawPath: String?
            val resolvedRawBytes: Long
            val resolvedPurged: Boolean
            when {
                restoredRawFile != null -> {
                    if (dto != null && dto.startMs > 0L) {
                        runCatching { restoredRawFile.setLastModified(dto.startMs) }
                    }
                    resolvedRawPath = restoredRawFile.absolutePath
                    resolvedRawBytes = restoredRawFile.length()
                    resolvedPurged = false
                }
                existingLocal?.rawPath != null && File(existingLocal.rawPath).exists() -> {
                    resolvedRawPath = existingLocal.rawPath
                    resolvedRawBytes = existingLocal.rawBytes
                    resolvedPurged = existingLocal.isRawPurged
                }
                else -> {
                    resolvedRawPath = null
                    resolvedRawBytes = dto?.rawBytes ?: 0L
                    resolvedPurged = dto?.isRawPurged ?: false
                }
            }

            val sessionEntity = if (dto != null) {
                SessionEntity(
                    id = dto.id,
                    roundGroupId = dto.roundGroupId,
                    eventGroupId = dto.eventGroupId,
                    combinedSourceIdsJson = dto.combinedSourceIdsJson,
                    mode = runCatching { SessionMode.valueOf(dto.mode) }.getOrDefault(SessionMode.EVERYDAY),
                    startMs = dto.startMs,
                    endMs = dto.endMs,
                    speechMs = dto.speechMs,
                    hadPhoneCall = dto.hadPhoneCall,
                    callEventsJson = dto.callEventsJson,
                    rawPath = resolvedRawPath,
                    rawBytes = resolvedRawBytes,
                    isRawPurged = resolvedPurged,
                    isPinned = dto.isPinned,
                    context = dto.context,
                    title = dto.title,
                    summary = dto.summary ?: existingLocal?.summary,
                    keyPointsJson = dto.keyPointsJson ?: existingLocal?.keyPointsJson,
                    status = runCatching { Status.valueOf(dto.status) }.getOrDefault(Status.DONE),
                    lastError = dto.lastError,
                    retryCount = dto.retryCount,
                    nextRetryEpochMs = dto.nextRetryEpochMs,
                    aiReviewReport = dto.aiReviewReport ?: existingLocal?.aiReviewReport,
                    reviewedAtMs = maxOf(dto.reviewedAtMs, existingLocal?.reviewedAtMs ?: 0L),
                    reviewSourceMode = dto.reviewSourceMode ?: existingLocal?.reviewSourceMode
                )
            } else {
                existingLocal ?: SessionEntity(
                    id = sessionId,
                    mode = SessionMode.EVERYDAY,
                    startMs = itemsBySession[sessionId]?.minOfOrNull { it.createdMs } ?: System.currentTimeMillis(),
                    endMs = itemsBySession[sessionId]?.maxOfOrNull { it.createdMs } ?: System.currentTimeMillis(),
                    speechMs = 0L,
                    rawPath = null,
                    status = Status.DONE,
                    title = "Restored Session"
                )
            }

            val segEntities = segmentsBySession[sessionId].orEmpty().map {
                SegmentMapEntity(it.sessionId, it.fileOffsetMs, it.wallClockMs, it.lengthMs)
            }
            val spkEntities = speakersBySession[sessionId].orEmpty().map {
                SessionSpeakerEntity(
                    sessionId = it.sessionId,
                    speakerId = it.speakerId,
                    speakerLabel = it.speakerLabel,
                    inferredRole = it.inferredRole,
                    isUser = it.isUser,
                    matchedProfileId = it.matchedProfileId,
                    nickname = it.nickname,
                    relatableTitle = it.relatableTitle,
                    voiceSignature = it.voiceSignature,
                    isAutoEnrolled = it.isAutoEnrolled
                )
            }
            val sentEntities = sentencesBySession[sessionId].orEmpty().map {
                TranscriptSentenceEntity(
                    id = 0L,
                    sentenceUuid = it.sentenceUuid,
                    sessionId = it.sessionId,
                    ordinal = it.ordinal,
                    approxMs = it.approxMs,
                    speakerId = it.speakerId,
                    speakerLabel = it.speakerLabel,
                    textOriginal = it.textOriginal,
                    textEn = it.textEn
                )
            }
            val patEntities = patientsBySession[sessionId].orEmpty().map {
                PatientBlockEntity(
                    id = it.id,
                    sessionId = it.sessionId,
                    roundGroupId = it.roundGroupId,
                    patientIdentifier = it.patientIdentifier,
                    approxStartMs = it.approxStartMs,
                    approxEndMs = it.approxEndMs,
                    oneLiner = it.oneLiner,
                    neuroExam = it.neuroExam,
                    assessment = it.assessment,
                    problemsJson = it.problemsJson,
                    resultsJson = it.resultsJson,
                    pendingJson = it.pendingJson,
                    planJson = it.planJson,
                    teachingJson = it.teachingJson
                )
            }
            val itemEntities = itemsBySession[sessionId].orEmpty().map { itemDto ->
                val clipFromZip = itemDto.clipZipRelPath?.let { restoredClipsByRelPath[it] }
                if (clipFromZip != null && clipFromZip.exists()) {
                    val clipRecordedMs = if (sessionEntity.startMs > 0L) {
                        (sessionEntity.startMs + itemDto.clipStartMs)
                            .coerceIn(sessionEntity.startMs, sessionEntity.endMs + 60_000L)
                    } else {
                        itemDto.createdMs
                    }
                    if (clipRecordedMs > 0L) {
                        runCatching { clipFromZip.setLastModified(clipRecordedMs) }
                    }
                }
                val candidateLocalClip = itemDto.clipFileName?.let { File(clipsDir, it) }
                    ?.takeIf { it.exists() && it.length() > 0L }
                val fallbackLocalClip = File(clipsDir, "clip_${itemDto.id}.m4a")
                    .takeIf { it.exists() && it.length() > 0L }
                val finalClipPath = (clipFromZip ?: candidateLocalClip ?: fallbackLocalClip)?.absolutePath
                ItemEntity(
                    id = itemDto.id,
                    sessionId = itemDto.sessionId,
                    patientIdentifier = itemDto.patientIdentifier,
                    type = runCatching { ItemType.valueOf(itemDto.type) }.getOrDefault(ItemType.TASK),
                    title = itemDto.title,
                    details = itemDto.details,
                    speakerId = itemDto.speakerId,
                    speakerLabel = itemDto.speakerLabel,
                    assignee = itemDto.assignee,
                    isForUser = itemDto.isForUser,
                    due = itemDto.due,
                    priority = itemDto.priority,
                    quoteOriginal = itemDto.quoteOriginal,
                    quoteEn = itemDto.quoteEn,
                    needsVerification = itemDto.needsVerification,
                    clipStartMs = itemDto.clipStartMs,
                    clipEndMs = itemDto.clipEndMs,
                    clipPath = finalClipPath,
                    isDone = itemDto.isDone,
                    isArchived = itemDto.isArchived,
                    createdMs = itemDto.createdMs
                )
            }
            val runEntities = runsBySession[sessionId].orEmpty().map {
                AnalysisRunEntity(
                    id = 0L,
                    sessionId = it.sessionId,
                    model = it.model,
                    promptVersion = it.promptVersion,
                    inTokens = it.inTokens,
                    outTokens = it.outTokens,
                    cachedTokens = it.cachedTokens,
                    latencyMs = it.latencyMs,
                    ok = it.ok,
                    errorMsg = it.errorMsg,
                    atMs = it.atMs
                )
            }

            dao.restoreSessionBundle(
                session = sessionEntity,
                segmentMaps = segEntities,
                speakers = spkEntities,
                sentences = sentEntities,
                patients = patEntities,
                items = itemEntities,
                analysisRuns = runEntities
            )

            restoredSessionsCount++
            restoredItemsCount += itemEntities.size
            restoredPatientsCount += patEntities.size
            restoredSentencesCount += sentEntities.size
        }

        return RestoreResult(
            settingsApplied = false,
            rosterMergedCount = 0,
            apiKeysRestored = false,
            voiceProfilesRestored = 0,
            sessionsRestored = restoredSessionsCount,
            itemsRestored = restoredItemsCount,
            patientsRestored = restoredPatientsCount,
            sentencesRestored = restoredSentencesCount,
            clipsRestored = restoredClipsByRelPath.size,
            rawAudioRestored = restoredRawByRelPath.size
        )
    }

    private fun buildMarkdownReport(
        appContext: Context,
        dataset: ExportDataset,
        prefs: PreferencesRepo
    ): String {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val timeOnlyFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        return buildString {
            append("# Ambient Brain — Export Report\n\n")
            append("- **Exported:** ").append(dateFmt.format(Date())).append("\n")
            append("- **Clinician / User:** ").append(prefs.userDisplayName.ifBlank { "Dr. (Me)" }).append("\n")
            append("- **Sessions:** ").append(dataset.filteredSessions.size)
                .append(" • **Patient Blocks:** ").append(dataset.patients.size)
                .append(" • **Action Items & Notes:** ").append(dataset.items.size)
                .append(" • **Transcript Lines:** ").append(dataset.sentences.size).append("\n\n")

            if (dataset.items.isNotEmpty()) {
                append("---\n\n## Tasks, Notes, Decisions & Events\n\n")
                val activeItems = dataset.items.filter { !it.isArchived }
                val archivedItems = dataset.items.filter { it.isArchived }
                if (activeItems.isNotEmpty()) {
                    append(CopyFormatter.formatItemsList(activeItems, TaskCopyTemplate.WARD_BY_PATIENT)).append("\n\n")
                }
                if (archivedItems.isNotEmpty()) {
                    append("### Archived Items (${archivedItems.size})\n\n")
                    append(CopyFormatter.formatItemsList(archivedItems, TaskCopyTemplate.MARKDOWN)).append("\n\n")
                }
            }

            if (dataset.filteredSessions.isNotEmpty()) {
                append("---\n\n## Sessions, Clinical Rounds & Transcripts\n\n")
                dataset.filteredSessions.forEach { details ->
                    val s = details.session
                    val isRound = s.mode in setOf(SessionMode.ROUND_MANUAL, SessionMode.ROUND_AUTO) ||
                        s.context in setOf("NEURO_ROUND", "MEDICAL_ROUND", "CASE_CONFERENCE", "HANDOVER")
                    val durSec = (if (s.speechMs > 0L) s.speechMs else (s.endMs - s.startMs).coerceAtLeast(0L)) / 1000L
                    append("### ").append(s.title ?: if (isRound) "Medical / Neurology Round" else "Captured Session").append("\n\n")
                    append("- **Start:** ").append(dateFmt.format(Date(s.startMs)))
                        .append(" • **Duration:** ").append(String.format(Locale.US, "%d:%02d", durSec / 60L, durSec % 60L))
                        .append(" • **Mode:** ").append(s.mode.name)
                        .append(" • **Status:** ").append(s.status.name).append("\n")
                    if (!s.summary.isNullOrBlank()) {
                        append("- **Summary:** ").append(s.summary).append("\n")
                    }
                    val kps = CopyFormatter.decodeStringList(s.keyPointsJson)
                    if (kps.isNotEmpty()) {
                        append("\n**Key Points:**\n")
                        kps.forEach { kp -> append("- ").append(kp).append("\n") }
                    }
                    if (!s.aiReviewReport.isNullOrBlank()) {
                        append("\n**AI Clinical Review:**\n\n").append(s.aiReviewReport).append("\n")
                    }
                    if (details.patients.isNotEmpty() && dataset.patients.isNotEmpty()) {
                        append("\n**Patient Sheets (${details.patients.size}):**\n\n")
                        details.patients.forEach { p ->
                            append(CopyFormatter.formatPatientBlock(p)).append("\n\n")
                        }
                    }
                    if (details.sentences.isNotEmpty() && dataset.sentences.isNotEmpty()) {
                        append("\n**Transcript (${details.sentences.size} lines):**\n\n")
                        details.sentences.sortedBy { it.ordinal }.forEach { line ->
                            val ts = timeOnlyFmt.format(Date(s.startMs + line.approxMs))
                            append("- `[").append(ts).append("]` **").append(line.speakerLabel).append("**: ")
                                .append(line.textOriginal)
                            if (!line.textEn.isNullOrBlank() && line.textEn != line.textOriginal) {
                                append("  \n  *EN:* ").append(line.textEn)
                            }
                            append("\n")
                        }
                    }
                    append("\n---\n\n")
                }
            }

            if (dataset.filteredSessions.isEmpty() && dataset.items.isEmpty() && dataset.settingsPayload != null) {
                append("## Settings Snapshot\n\n")
                append("- **Mic Sharing Mode:** ").append(dataset.settingsPayload.micSharingMode).append("\n")
                append("- **Gemini Model:** ").append(dataset.settingsPayload.geminiModel).append("\n")
                append("- **Transcript Language:** ").append(dataset.settingsPayload.transcriptLangMode).append("\n")
                append("- **Speaker Roster Size:** ").append(dataset.settingsPayload.speakerRoster.size).append("\n")
            }
        }.trim() + "\n"
    }

    private fun buildItemsCsv(items: List<ItemEntity>): String {
        val sb = StringBuilder()
        sb.append(
            "ID,Type,Status,Archived,Priority,NeedsVerification,Patient,Title,Details,Assignee,ForMe,Due,Speaker,QuoteOriginal,QuoteEnglish,HasVoiceClip,CreatedAtISO,SessionId\n"
        )
        items.forEach { item ->
            val cols = listOf(
                item.id,
                item.type.name,
                if (item.isDone) "DONE" else "OPEN",
                item.isArchived.toString(),
                item.priority,
                item.needsVerification.toString(),
                item.patientIdentifier.orEmpty(),
                item.title,
                item.details,
                item.assignee.orEmpty(),
                item.isForUser.toString(),
                item.due.orEmpty(),
                item.speakerLabel,
                item.quoteOriginal,
                item.quoteEn.orEmpty(),
                (!item.clipPath.isNullOrBlank()).toString(),
                formatIso8601Utc(item.createdMs),
                item.sessionId
            )
            sb.append(cols.joinToString(",") { escapeCsvField(it) }).append("\n")
        }
        return sb.toString()
    }

    private fun escapeCsvField(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun inspectZipStream(
        buffered: BufferedInputStream,
        displayName: String,
        fileSize: Long
    ): BackupInspection {
        var manifest: BackupManifest? = null
        var settingsPayload: SettingsBackupPayload? = null
        var dbPayload: BackupDatabasePayload? = null
        var profilesCount = 0
        var clipsCount = 0
        var rawCount = 0

        ZipInputStream(buffered).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory && !name.contains("..")) {
                    when {
                        name == ZIP_MANIFEST_PATH -> {
                            val bytes = readZipEntryBytesBounded(zis, 2_000_000L)
                            manifest = runCatching { json.decodeFromString<BackupManifest>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                        }
                        name == ZIP_SETTINGS_PATH -> {
                            val bytes = readZipEntryBytesBounded(zis, 4_000_000L)
                            settingsPayload = runCatching { json.decodeFromString<SettingsBackupPayload>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                        }
                        name == ZIP_DB_PATH -> {
                            val bytes = readZipEntryBytesBounded(zis, 64_000_000L)
                            dbPayload = runCatching { json.decodeFromString<BackupDatabasePayload>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                        }
                        name.startsWith("audio/profiles/") -> profilesCount++
                        name.startsWith("audio/clips/") -> clipsCount++
                        name.startsWith("audio/sessions/") -> rawCount++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val effectiveManifest = manifest
        val keysCount = settingsPayload?.let {
            if (it.includesApiKeys) {
                (it.savedApiKeysPool + listOf(it.geminiApiKey)).filter { k -> k.isNotBlank() }.distinct().size
            } else 0
        } ?: effectiveManifest?.counts?.apiKeyCount ?: 0

        val rosterCount = settingsPayload?.speakerRoster?.size ?: effectiveManifest?.counts?.speakerRosterCount ?: 0

        return BackupInspection(
            containerKind = BackupContainerKind.ZIP_ARCHIVE,
            displayName = displayName,
            fileSizeBytes = fileSize,
            exportedAtMs = effectiveManifest?.exportedAtMs ?: settingsPayload?.exportedAtMs ?: dbPayload?.exportedAtMs ?: 0L,
            appVersionName = effectiveManifest?.appVersionName ?: settingsPayload?.appVersionName.orEmpty(),
            hasSettings = settingsPayload != null || effectiveManifest?.includesSettings == true,
            settingsPayload = settingsPayload,
            apiKeysCount = keysCount,
            rosterCount = rosterCount,
            sessionsCount = dbPayload?.sessions?.size ?: effectiveManifest?.counts?.sessions ?: 0,
            sentencesCount = dbPayload?.sentences?.size ?: effectiveManifest?.counts?.sentences ?: 0,
            patientsCount = dbPayload?.patients?.size ?: effectiveManifest?.counts?.patients ?: 0,
            itemsCount = dbPayload?.items?.size ?: effectiveManifest?.counts?.items ?: 0,
            analysisRunsCount = dbPayload?.analysisRuns?.size ?: effectiveManifest?.counts?.analysisRuns ?: 0,
            voiceProfileFilesCount = profilesCount,
            proofClipFilesCount = clipsCount,
            rawAudioFilesCount = rawCount
        )
    }

    private fun detectStreamKind(buffered: BufferedInputStream): BackupContainerKind {
        buffered.mark(256)
        val probe = ByteArray(256)
        val n = buffered.read(probe)
        buffered.reset()
        if (n <= 0) {
            throw IllegalArgumentException("Selected file is empty.")
        }
        // ZIP local file header signature: 0x50 0x4B ('P''K')
        if (n >= 2 && probe[0] == 0x50.toByte() && probe[1] == 0x4B.toByte()) {
            return BackupContainerKind.ZIP_ARCHIVE
        }
        val textStart = String(probe, 0, n, Charsets.UTF_8).trimStart()
        if (textStart.startsWith("{")) {
            return if (textStart.contains(DB_BACKUP_FORMAT)) {
                BackupContainerKind.DATABASE_JSON
            } else {
                BackupContainerKind.SETTINGS_JSON
            }
        }
        throw IllegalArgumentException("Unsupported backup file format. Choose an Ambient Brain .zip or .json backup.")
    }

    private fun safeResolveZipTarget(destDir: File, relativeName: String): File? {
        val cleanRel = relativeName.replace('\\', '/').trimStart('/')
        if (cleanRel.isBlank() || cleanRel.contains("..")) return null
        val leafName = File(cleanRel).name
        if (leafName.isBlank()) return null
        val target = File(destDir, leafName)
        val destCanon = destDir.canonicalPath
        val targetCanon = target.canonicalPath
        if (!targetCanon.startsWith(destCanon + File.separator)) return null
        return target
    }

    private fun writeBytesZipEntry(zos: ZipOutputStream, entryName: String, bytes: ByteArray) {
        val entry = ZipEntry(entryName)
        entry.size = bytes.size.toLong()
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun writeFileZipEntry(
        zos: ZipOutputStream,
        entryName: String,
        file: File,
        overrideTimeMs: Long = 0L
    ) {
        val entry = ZipEntry(entryName)
        entry.time = overrideTimeMs.takeIf { it > 0L } ?: file.lastModified()
        entry.size = file.length()
        zos.putNextEntry(entry)
        FileInputStream(file).use { fis ->
            val buf = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                zos.write(buf, 0, read)
            }
        }
        zos.closeEntry()
    }

    private fun copyStreamToFile(
        zis: ZipInputStream,
        destFile: File,
        entryTimeMs: Long = -1L
    ) {
        destFile.parentFile?.mkdirs()
        val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
        FileOutputStream(tmpFile).use { fos ->
            val buf = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val read = zis.read(buf)
                if (read <= 0) break
                fos.write(buf, 0, read)
            }
            fos.flush()
        }
        if (destFile.exists()) destFile.delete()
        if (!tmpFile.renameTo(destFile)) {
            tmpFile.copyTo(destFile, overwrite = true)
            tmpFile.delete()
        }
        if (entryTimeMs > 0L) {
            runCatching { destFile.setLastModified(entryTimeMs) }
        }
    }

    private fun readZipEntryBytesBounded(zis: ZipInputStream, maxBytes: Long): ByteArray {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(STREAM_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = zis.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalStateException("ZIP JSON entry exceeded safe limit (${formatBytesHuman(maxBytes)}).")
            }
            baos.write(buf, 0, read)
        }
        return baos.toByteArray()
    }

    private fun resolveDisplayNameFromUri(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun resolveFileSizeFromUri(context: Context, uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) cursor.getLong(idx) else 0L
                } else 0L
            }
        }.getOrNull() ?: 0L
    }

    private fun formatIso8601Utc(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }
}
