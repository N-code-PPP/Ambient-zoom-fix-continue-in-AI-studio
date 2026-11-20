package com.ambientbrain.recorder.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ambientbrain.recorder.ai.DuplicateProfileSuggestion
import com.ambientbrain.recorder.ai.GeminiInteractionsClient
import com.ambientbrain.recorder.ai.MedicalGlossaryManager
import com.ambientbrain.recorder.ai.SpeakerEmbeddingEngine
import com.ambientbrain.recorder.ai.VoiceExtractionTimeFrame
import com.ambientbrain.recorder.ai.VoiceProfileAutoManager
import com.ambientbrain.recorder.data.AnalysisUsageSummary
import com.ambientbrain.recorder.data.ApiKeyHealth
import com.ambientbrain.recorder.data.AppDataExporter
import com.ambientbrain.recorder.data.AppDatabase
import com.ambientbrain.recorder.data.ClipSlicer
import com.ambientbrain.recorder.data.ExportPreset
import com.ambientbrain.recorder.data.GlossaryTermEntry
import com.ambientbrain.recorder.data.MicSharingMode
import com.ambientbrain.recorder.data.ModelAllowanceEntry
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.SpeakerProfileEntry
import com.ambientbrain.recorder.data.TranscriptLangMode
import com.ambientbrain.recorder.data.VoiceProfileSample
import com.ambientbrain.recorder.service.MeetingMp4Muxer
import com.ambientbrain.recorder.service.ShizukuAdbManager
import com.ambientbrain.recorder.service.VoiceProfileRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: PreferencesRepo,
    tokensToday: Long,
    cachedTokensToday: Long = 0L,
    externalSettingsSyncVersion: Int = 0,
    playbackController: AudioPlaybackController? = null,
    onSettingsSaved: () -> Unit,
    onSettingsRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(prefs.geminiApiKey) }
    var showApiKeyPlaintext by remember { mutableStateOf(false) }
    var savedKeys by remember { mutableStateOf(prefs.getSavedApiKeys()) }
    var selectedModel by remember { mutableStateOf(prefs.geminiModel) }
    var fallbackModel by remember { mutableStateOf(prefs.fallbackGeminiModel) }
    var keyHealthMap by remember { mutableStateOf(prefs.getApiKeyHealthMap()) }
    var allowanceEntries by remember { mutableStateOf(prefs.getCachedModelAllowance()) }
    var isCheckingAllowance by remember { mutableStateOf(false) }
    var allowanceProgress by remember { mutableStateOf<String?>(null) }
    var micSharingMode by remember { mutableStateOf(prefs.micSharingMode) }
    var langMode by remember { mutableStateOf(prefs.transcriptLangMode) }
    var autoDetectRounds by remember { mutableStateOf(prefs.autoDetectRounds) }
    var autoRecognizeVoiceProfiles by remember { mutableStateOf(prefs.autoRecognizeVoiceProfiles) }
    var autoMergeContinuousFiles by remember { mutableStateOf(prefs.autoMergeContinuousFiles) }
    var autoMergeGapMinutes by remember { mutableIntStateOf(prefs.autoMergeGapMinutes) }
    var defaultReviewSourceIsAudio by remember { mutableStateOf(prefs.defaultReviewSourceIsAudio) }
    var minMeaningfulSpeechSec by remember { mutableFloatStateOf(prefs.minMeaningfulSpeechSeconds.toFloat()) }
    var silenceSplitSec by remember { mutableFloatStateOf(prefs.silenceSplitSeconds.toFloat()) }
    var roundSilenceSplitSec by remember { mutableFloatStateOf(prefs.roundSilenceSplitSeconds.toFloat()) }
    var retentionDays by remember { mutableIntStateOf(prefs.retentionDays) }
    var meetingVideoSaveFolder by remember { mutableStateOf(prefs.meetingVideoSaveFolder) }
    var meetingVideoCustomTreeUri by remember { mutableStateOf(prefs.meetingVideoCustomTreeUri) }
    var meetingVideoCustomFolderLabel by remember { mutableStateOf(prefs.meetingVideoCustomFolderLabel) }
    var userDisplayName by remember { mutableStateOf(prefs.userDisplayName) }
    var customGlossary by remember { mutableStateOf(prefs.customGlossaryTerms) }
    var autoAddGlossaryTerms by remember { mutableStateOf(prefs.autoAddGlossaryTerms) }
    var structuredGlossary by remember { mutableStateOf(prefs.getStructuredGlossaryEntries()) }
    var glossarySearchQuery by remember { mutableStateOf("") }
    var showAllGlossaryTerms by remember { mutableStateOf(false) }
    var isScanningGlossary by remember { mutableStateOf(false) }
    var showAddEditGlossaryDialog by remember { mutableStateOf(false) }
    var editingGlossaryEntry by remember { mutableStateOf<GlossaryTermEntry?>(null) }
    val builtInGlossaryStats = remember(context) { MedicalGlossaryManager.getBuiltInStats(context) }

    var apiTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingSingle by remember { mutableStateOf(false) }
    var isTestingAll by remember { mutableStateOf(false) }
    var allKeysTestResults by remember { mutableStateOf<String?>(null) }
    var isRecordingProfile by remember { mutableStateOf(false) }
    var profileSecRecorded by remember { mutableIntStateOf(0) }
    var myVoiceSamples by remember { mutableStateOf(prefs.getMyVoiceSamples()) }
    var hasProfile by remember { mutableStateOf(myVoiceSamples.isNotEmpty()) }
    var speakerRoster by remember { mutableStateOf(prefs.getSpeakerRoster()) }
    var duplicateSuggestions by remember { mutableStateOf<List<DuplicateProfileSuggestion>>(emptyList()) }
    var dismissedDuplicatePairs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var recordingSpeakerId by remember { mutableStateOf<String?>(null) }
    var speakerClipSecRecorded by remember { mutableIntStateOf(0) }
    var selectedExtractTimeFrame by remember { mutableStateOf(VoiceExtractionTimeFrame.ALL) }
    var customExtractStartDate by remember { mutableStateOf<LocalDate?>(null) }
    var customExtractEndDate by remember { mutableStateOf<LocalDate?>(null) }
    var showCustomTimeFrameDialog by remember { mutableStateOf(false) }
    var pickingCustomDateSlot by remember { mutableIntStateOf(0) } // 0 = none, 1 = start, 2 = end
    var isExtractingOldVoices by remember { mutableStateOf(false) }
    var oldVoiceExtractProgress by remember { mutableStateOf(0 to 0) }
    var oldVoiceExtractStatusText by remember { mutableStateOf<String?>(null) }
    var oldVoiceExtractSummary by remember { mutableStateOf<VoiceProfileAutoManager.BatchVoiceExtractionSummary?>(null) }
    var isAuditingVoicesWithGemini by remember { mutableStateOf(false) }
    var geminiVoiceAuditStatusText by remember { mutableStateOf<String?>(null) }
    var geminiVoiceAuditSummary by remember { mutableStateOf<VoiceProfileAutoManager.GeminiVoiceProfileAuditSummary?>(null) }
    var showAddSpeakerDialog by remember { mutableStateOf(false) }
    var editingSpeaker by remember { mutableStateOf<SpeakerProfileEntry?>(null) }
    var mergingSpeaker by remember { mutableStateOf<SpeakerProfileEntry?>(null) }
    var viewingMyVoiceSamples by remember { mutableStateOf(false) }
    var viewingSpeakerSamples by remember { mutableStateOf<SpeakerProfileEntry?>(null) }
    var showAdbDiagnosticsDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportInitialTab by remember { mutableIntStateOf(0) }
    var exportInitialPreset by remember { mutableStateOf(ExportPreset.STANDARD_BACKUP) }
    var diagState by remember { mutableStateOf(ShizukuAdbManager.checkDiagnostics(context)) }

    val meetingFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                true
            }.getOrDefault(false)
            if (!persisted) {
                Toast.makeText(
                    context,
                    "Android denied persistent write access to that folder. Choose a standard Downloads or Documents subfolder instead.",
                    Toast.LENGTH_LONG
                ).show()
                return@rememberLauncherForActivityResult
            }
            val readableLabel = MeetingMp4Muxer.resolveReadableSafLabel(treeUri)
            meetingVideoCustomTreeUri = treeUri.toString()
            meetingVideoCustomFolderLabel = readableLabel
            prefs.meetingVideoCustomTreeUri = treeUri.toString()
            prefs.meetingVideoCustomFolderLabel = readableLabel
            Toast.makeText(
                context,
                "Meeting MP4 save folder set to $readableLabel",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // AI Usage & Performance Dashboard state
    var usageRangeDays by remember { mutableIntStateOf(1) } // 1 = Today, 7 = 7 Days, 0 = All Time
    val appDao = remember(context) { AppDatabase.get(context).dao() }
    val usageSinceMs = remember(usageRangeDays) {
        when (usageRangeDays) {
            1 -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            7 -> System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
            else -> 0L
        }
    }
    val usageSummary by remember(appDao, usageSinceMs) {
        appDao.observeAnalysisUsageSince(usageSinceMs)
    }.collectAsState(initial = AnalysisUsageSummary())
    val modelUsageBreakdown by remember(appDao, usageSinceMs) {
        appDao.observeModelUsageBreakdownSince(usageSinceMs)
    }.collectAsState(initial = emptyList())
    val recentFailedRuns by remember(appDao) {
        appDao.observeRecentFailedAnalysisRuns(3)
    }.collectAsState(initial = emptyList())

    fun commitPendingEdits() {
        if (apiKey.isNotBlank()) {
            prefs.geminiApiKey = apiKey.trim()
        }
        prefs.geminiModel = selectedModel
        prefs.fallbackGeminiModel = fallbackModel
        prefs.micSharingMode = micSharingMode
        prefs.transcriptLangMode = langMode
        prefs.autoDetectRounds = autoDetectRounds
        prefs.autoRecognizeVoiceProfiles = autoRecognizeVoiceProfiles
        prefs.autoMergeContinuousFiles = autoMergeContinuousFiles
        prefs.autoMergeGapMinutes = autoMergeGapMinutes
        prefs.defaultReviewSourceIsAudio = defaultReviewSourceIsAudio
        prefs.minMeaningfulSpeechSeconds = minMeaningfulSpeechSec.toInt()
        prefs.silenceSplitSeconds = silenceSplitSec.toInt()
        prefs.roundSilenceSplitSeconds = roundSilenceSplitSec.toInt()
        prefs.retentionDays = retentionDays
        prefs.meetingVideoSaveFolder = meetingVideoSaveFolder
        prefs.meetingVideoCustomTreeUri = meetingVideoCustomTreeUri
        prefs.meetingVideoCustomFolderLabel = meetingVideoCustomFolderLabel
        prefs.userDisplayName = userDisplayName
        prefs.customGlossaryTerms = customGlossary
        prefs.autoAddGlossaryTerms = autoAddGlossaryTerms
    }

    fun reloadFromPrefs() {
        apiKey = prefs.geminiApiKey
        savedKeys = prefs.getSavedApiKeys()
        selectedModel = prefs.geminiModel
        fallbackModel = prefs.fallbackGeminiModel
        keyHealthMap = prefs.getApiKeyHealthMap()
        allowanceEntries = prefs.getCachedModelAllowance()
        micSharingMode = prefs.micSharingMode
        langMode = prefs.transcriptLangMode
        autoDetectRounds = prefs.autoDetectRounds
        autoRecognizeVoiceProfiles = prefs.autoRecognizeVoiceProfiles
        autoMergeContinuousFiles = prefs.autoMergeContinuousFiles
        autoMergeGapMinutes = prefs.autoMergeGapMinutes
        defaultReviewSourceIsAudio = prefs.defaultReviewSourceIsAudio
        minMeaningfulSpeechSec = prefs.minMeaningfulSpeechSeconds.toFloat()
        silenceSplitSec = prefs.silenceSplitSeconds.toFloat()
        roundSilenceSplitSec = prefs.roundSilenceSplitSeconds.toFloat()
        retentionDays = prefs.retentionDays
        meetingVideoSaveFolder = prefs.meetingVideoSaveFolder
        meetingVideoCustomTreeUri = prefs.meetingVideoCustomTreeUri
        meetingVideoCustomFolderLabel = prefs.meetingVideoCustomFolderLabel
        userDisplayName = prefs.userDisplayName
        customGlossary = prefs.customGlossaryTerms
        autoAddGlossaryTerms = prefs.autoAddGlossaryTerms
        structuredGlossary = prefs.getStructuredGlossaryEntries()
        myVoiceSamples = prefs.getMyVoiceSamples()
        hasProfile = myVoiceSamples.isNotEmpty()
        speakerRoster = prefs.getSpeakerRoster()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                diagState = ShizukuAdbManager.checkDiagnostics(context)
                savedKeys = prefs.getSavedApiKeys()
                selectedModel = prefs.geminiModel
                fallbackModel = prefs.fallbackGeminiModel
                keyHealthMap = prefs.getApiKeyHealthMap()
                allowanceEntries = prefs.getCachedModelAllowance()
                myVoiceSamples = prefs.getMyVoiceSamples()
                hasProfile = myVoiceSamples.isNotEmpty()
                speakerRoster = prefs.getSpeakerRoster()
                autoRecognizeVoiceProfiles = prefs.autoRecognizeVoiceProfiles
                autoAddGlossaryTerms = prefs.autoAddGlossaryTerms
                structuredGlossary = prefs.getStructuredGlossaryEntries()
                meetingVideoSaveFolder = prefs.meetingVideoSaveFolder
                meetingVideoCustomTreeUri = prefs.meetingVideoCustomTreeUri
                meetingVideoCustomFolderLabel = prefs.meetingVideoCustomFolderLabel
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val rosterVoiceprintKey = remember(speakerRoster) {
        speakerRoster.joinToString("|") { spk ->
            val samples = spk.existingSamples()
            "${spk.id}:${samples.size}:${samples.count { it.embedding.isNotEmpty() }}"
        }
    }
    LaunchedEffect(rosterVoiceprintKey) {
        val (backfilled, latestRoster, pairs) = withContext(Dispatchers.IO) {
            val count = VoiceProfileAutoManager.ensureSampleEmbeddingsBackfilled(
                context = context,
                prefs = prefs,
                maxToCompute = 16
            )
            val updatedRoster = prefs.getSpeakerRoster()
            val foundPairs = SpeakerEmbeddingEngine.findPossibleDuplicatePairs(updatedRoster)
            Triple(count, updatedRoster, foundPairs)
        }
        if (backfilled > 0) {
            myVoiceSamples = prefs.getMyVoiceSamples()
            hasProfile = myVoiceSamples.isNotEmpty()
            speakerRoster = latestRoster
        }
        duplicateSuggestions = pairs
    }

    LaunchedEffect(externalSettingsSyncVersion) {
        if (externalSettingsSyncVersion > 0) {
            reloadFromPrefs()
        }
    }

    // Auto-saves on Edit: Automatically commits changes when leaving the Settings screen
    DisposableEffect(Unit) {
        onDispose {
            commitPendingEdits()
        }
    }

    if (showAdbDiagnosticsDialog) {
        AdbDiagnosticsDialog(
            onDismissRequest = {
                showAdbDiagnosticsDialog = false
                diagState = ShizukuAdbManager.checkDiagnostics(context)
            }
        )
    }

    if (showExportDialog) {
        DataExportDialog(
            onDismissRequest = { showExportDialog = false },
            initialTab = exportInitialTab,
            initialPreset = exportInitialPreset,
            onBackupRestored = {
                reloadFromPrefs()
                onSettingsRestored()
            }
        )
    }

    if (viewingMyVoiceSamples) {
        VoiceProfileSamplesDialog(
            prefs = prefs,
            isUserProfile = true,
            fallbackProfileName = userDisplayName.ifBlank { "My Voice" },
            fallbackRelatableTitle = "Device Owner / Primary User",
            playbackController = playbackController,
            onSamplesChanged = {
                myVoiceSamples = prefs.getMyVoiceSamples()
                hasProfile = myVoiceSamples.isNotEmpty()
            },
            onDismissRequest = { viewingMyVoiceSamples = false }
        )
    }

    viewingSpeakerSamples?.let { targetSpeaker ->
        VoiceProfileSamplesDialog(
            prefs = prefs,
            isUserProfile = false,
            speakerProfileId = targetSpeaker.id,
            fallbackProfileName = targetSpeaker.effectiveNickname(),
            fallbackRelatableTitle = targetSpeaker.effectiveRelatableTitle(),
            fallbackVoiceSignature = targetSpeaker.voiceSignature.ifBlank { targetSpeaker.voiceHint },
            playbackController = playbackController,
            onSamplesChanged = {
                speakerRoster = prefs.getSpeakerRoster()
            },
            onDismissRequest = { viewingSpeakerSamples = null }
        )
    }

    if (showAddEditGlossaryDialog) {
        val editing = editingGlossaryEntry
        var termInput by remember(editing?.id) { mutableStateOf(editing?.term.orEmpty()) }
        var thaiPhoneticInput by remember(editing?.id) { mutableStateOf(editing?.thaiPhonetic.orEmpty()) }
        var categoryInput by remember(editing?.id) {
            mutableStateOf(editing?.category ?: GlossaryTermEntry.CATEGORY_DRUG)
        }
        var notesInput by remember(editing?.id) { mutableStateOf(editing?.notes.orEmpty()) }

        AlertDialog(
            onDismissRequest = {
                showAddEditGlossaryDialog = false
                editingGlossaryEntry = null
            },
            title = {
                Text(if (editing != null) "Edit Glossary Term" else "Add Medical / Neurology Term")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Manually added or edited terms are permanently protected from auto-pruning and injected into every Gemini prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = termInput,
                        onValueChange = { termInput = it },
                        label = { Text("Canonical Term (EN / Abbrev) *") },
                        placeholder = { Text("e.g., Cenobamate, MOGAD, Neuro ICU 4A") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = thaiPhoneticInput,
                        onValueChange = { thaiPhoneticInput = it },
                        label = { Text("Thai Phonetic / Spoken Form (Optional)") },
                        placeholder = { Text("e.g., ซีโนบาเมต, โมแกด") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Category:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        GlossaryTermEntry.ALL_CATEGORIES.forEach { cat ->
                            FilterChip(
                                selected = categoryInput == cat,
                                onClick = { categoryInput = cat },
                                label = {
                                    Text(
                                        GlossaryTermEntry.categoryDisplayLabel(cat),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = notesInput,
                        onValueChange = { notesInput = it },
                        label = { Text("Clinical Note / Expansion (Optional)") },
                        placeholder = { Text("e.g., AED; 3rd-line focal epilepsy") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = termInput.isNotBlank(),
                    onClick = {
                        val now = System.currentTimeMillis()
                        val entry = GlossaryTermEntry(
                            id = editing?.id ?: UUID.randomUUID().toString().take(8),
                            term = termInput.trim(),
                            thaiPhonetic = thaiPhoneticInput.trim(),
                            category = categoryInput,
                            notes = notesInput.trim(),
                            isAutoAdded = false,
                            seenCount = editing?.seenCount ?: 1,
                            createdAtMs = editing?.createdAtMs ?: now,
                            lastSeenAtMs = now,
                            sourceSessionId = editing?.sourceSessionId
                        )
                        structuredGlossary = prefs.upsertGlossaryEntry(entry)
                        showAddEditGlossaryDialog = false
                        editingGlossaryEntry = null
                        Toast.makeText(context, "Saved glossary term '${entry.term}'", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(if (editing != null) "Save" else "Add Term")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddEditGlossaryDialog = false
                        editingGlossaryEntry = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddSpeakerDialog) {
        var newNickname by remember { mutableStateOf("") }
        var newRelatableTitle by remember { mutableStateOf("") }
        var newVoiceSignature by remember { mutableStateOf("") }
        var newPinned by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddSpeakerDialog = false },
            title = { Text("Add Voice Profile to Roster") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Gemini matches speakers across recordings using saved nicknames, relatable titles, acoustic signatures, and voice sample clips.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newNickname,
                        onValueChange = { newNickname = it },
                        label = { Text("Recognizing Nickname *") },
                        placeholder = { Text("e.g., Dr. Arthit, Nurse Pim, Deep-Tone Attending") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRelatableTitle,
                        onValueChange = { newRelatableTitle = it },
                        label = { Text("Relatable Title / Role (Optional)") },
                        placeholder = { Text("e.g., Senior Neuro Attending, Charge Nurse, Mom") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newVoiceSignature,
                        onValueChange = { newVoiceSignature = it },
                        label = { Text("Voice Signature / Notes (Optional)") },
                        placeholder = { Text("e.g., Warm calm baritone, mixes English terms") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { newPinned = !newPinned }
                    ) {
                        Checkbox(
                            checked = newPinned,
                            onCheckedChange = { newPinned = it }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Pin profile (prioritize attachment & never auto-prune)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = newNickname.isNotBlank(),
                    onClick = {
                        val cleanNick = newNickname.trim()
                        val cleanTitle = newRelatableTitle.trim()
                        val cleanSig = newVoiceSignature.trim()
                        speakerRoster = prefs.upsertSpeakerProfile(
                            SpeakerProfileEntry(
                                displayName = cleanNick,
                                nickname = cleanNick,
                                role = cleanTitle,
                                relatableTitle = cleanTitle,
                                voiceHint = cleanSig,
                                voiceSignature = cleanSig,
                                isAutoDiscovered = false,
                                isPinned = newPinned
                            )
                        )
                        showAddSpeakerDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSpeakerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    editingSpeaker?.let { target ->
        var editNick by remember(target.id) { mutableStateOf(target.effectiveNickname()) }
        var editTitle by remember(target.id) { mutableStateOf(target.effectiveRelatableTitle()) }
        var editSig by remember(target.id) { mutableStateOf(target.voiceSignature.ifBlank { target.voiceHint }) }
        var editPinned by remember(target.id) { mutableStateOf(target.isPinned) }
        AlertDialog(
            onDismissRequest = { editingSpeaker = null },
            title = { Text("Edit Voice Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val clipInfo = if (target.sampleDurationMs > 0L) {
                        " • Sample ${formatAudioDuration(target.sampleDurationMs)}"
                    } else ""
                    Text(
                        text = "${if (target.isAutoDiscovered) "Auto-discovered" else "Manual"} profile • Heard ${target.sessionsHeardCount}x$clipInfo",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = editNick,
                        onValueChange = { editNick = it },
                        label = { Text("Recognizing Nickname *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Relatable Title / Role") },
                        placeholder = { Text("e.g., Senior Neuro Attending, Charge Nurse") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editSig,
                        onValueChange = { editSig = it },
                        label = { Text("Voice Signature / Notes") },
                        placeholder = { Text("Pitch, pace, vocal traits...") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editPinned = !editPinned }
                    ) {
                        Checkbox(
                            checked = editPinned,
                            onCheckedChange = { editPinned = it }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Pin profile (prioritize attachment & protect from auto-pruning)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = editNick.isNotBlank(),
                    onClick = {
                        val cleanNick = editNick.trim()
                        val cleanTitle = editTitle.trim()
                        val cleanSig = editSig.trim()
                        speakerRoster = prefs.updateSpeakerProfileById(target.id) { prev ->
                            prev.copy(
                                displayName = cleanNick,
                                nickname = cleanNick,
                                role = cleanTitle,
                                relatableTitle = cleanTitle,
                                voiceHint = cleanSig,
                                voiceSignature = cleanSig,
                                isPinned = editPinned,
                                isAutoDiscovered = false
                            )
                        }
                        editingSpeaker = null
                        Toast.makeText(context, "Updated profile: $cleanNick", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSpeaker = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    mergingSpeaker?.let { sourceSpk ->
        val candidates = speakerRoster.filter { it.id != sourceSpk.id }
        AlertDialog(
            onDismissRequest = { mergingSpeaker = null },
            title = { Text("Merge Duplicate Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Select the target profile to merge '${sourceSpk.effectiveNickname()}' into. Encounter counts will sum, the best voice clip is preserved, and the duplicate is removed:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        candidates.forEach { targetSpk ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        speakerRoster = VoiceProfileAutoManager.mergeProfiles(
                                            context = context,
                                            sourceProfileId = sourceSpk.id,
                                            targetProfileId = targetSpk.id
                                        )
                                        mergingSpeaker = null
                                        Toast.makeText(
                                            context,
                                            "Merged '${sourceSpk.effectiveNickname()}' into '${targetSpk.effectiveNickname()}'",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = targetSpk.effectiveNickname(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val sub = listOfNotNull(
                                        targetSpk.effectiveRelatableTitle().takeIf { it.isNotBlank() },
                                        "Heard ${targetSpk.sessionsHeardCount}x"
                                    ).joinToString(" • ")
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { mergingSpeaker = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    val a11yBadgeText = when {
        diagState.accessibilityServiceBound && diagState.accessibilityOverlayAttached -> "BOUND + OVERLAY"
        diagState.accessibilityServiceBound -> "BOUND"
        diagState.accessibilitySettingsEnabled -> "REBIND NEEDED"
        else -> "OFF"
    }
    val micOpBadgeText = when {
        diagState.appOpsRecordAudioAllowed -> "ALLOWED (24/7)"
        diagState.isRecordAudioForegroundOnly -> "FOREGROUND ONLY"
        else -> "RESTRICTED"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings & Device Health",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // 1. Device Health & 24/7 Resilience Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "24/7 Background Capture & Device Health",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Item: Accessibility Service Binding & Overlay
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Accessibility Service & Silent Overlay", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Zero-notification concurrent mic capture during calls", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BadgePill(
                        text = a11yBadgeText,
                        containerColor = if (diagState.accessibilityServiceBound) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    )
                }

                // Item: AppOps RECORD_AUDIO Mode
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Background Mic Permission (AppOps)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Needs MODE_ALLOWED via Shizuku/ADB for background audio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BadgePill(
                        text = micOpBadgeText,
                        containerColor = if (diagState.appOpsRecordAudioAllowed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    )
                }

                // Item: Battery Optimization
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Battery Optimization Exemption", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Protects 24/7 background capture from system suspension", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BadgePill(
                        text = if (diagState.batteryOptimizationExempt) "EXEMPT" else "RESTRICTED",
                        containerColor = if (diagState.batteryOptimizationExempt) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    )
                }

                // Item: Deep ADB / Shizuku Shield (Self-Heal, PROJECT_MEDIA, Freezer & Phantom)
                val deepShieldBadgeText = when {
                    diagState.hasDeepPrivilegesReady && diagState.isPhantomFreezerShielded -> "FULL SHIELD"
                    diagState.isFullyAdbOptimized -> "CORE ONLY"
                    else -> "SETUP NEEDED"
                }
                val deepShieldContainer = when {
                    diagState.hasDeepPrivilegesReady && diagState.isPhantomFreezerShielded -> MaterialTheme.colorScheme.primaryContainer
                    diagState.isFullyAdbOptimized -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Deep ADB / Shizuku Shield", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Self-Heal: ${if (diagState.runtimeWriteSecureSettings) "ON" else "OFF"} • Media Op: ${if (diagState.appOpsProjectMedia) "ALLOW" else "STD"} • Freezer/Phantom: ${if (diagState.isPhantomFreezerShielded) "BYPASSED" else "PARTIAL"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BadgePill(
                        text = deepShieldBadgeText,
                        containerColor = deepShieldContainer
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Mic Sharing & Auto-Yield Policy Selector
                Text(
                    text = "Mic Sharing & Conflict Policy (Zero-Notification)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MicSharingMode.entries.forEach { mode ->
                        FilterChip(
                            selected = micSharingMode == mode,
                            onClick = {
                                micSharingMode = mode
                                prefs.micSharingMode = mode
                            },
                            label = { Text(mode.shortLabel) }
                        )
                    }
                }
                Text(
                    text = micSharingMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Item: Gemini Tokens
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Gemini Tokens Used Today", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Single-pass audio & structure extraction", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BadgePill(
                        text = "$tokensToday tokens",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                }

                // Item: Gemini Prefix Context Cache
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Thai Medical Glossary Context Cache", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Prefix KV-cache hits (75-95% token discount)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BadgePill(
                        text = "$cachedTokensToday cached",
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }

                Button(
                    onClick = { showAdbDiagnosticsDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "🛡️ Open ADB & Shizuku Diagnostics",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Accessibility", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Battery", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("App Info", maxLines = 1)
                    }
                }
            }
        }

        // 2. Gemini API Key Pool, Health & Model Allowance (Direct Google AI Studio)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Gemini API Key Pool",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BadgePill(
                        text = if (savedKeys.isNotEmpty()) {
                            "${savedKeys.size} Key${if (savedKeys.size == 1) "" else "s"} Active"
                        } else {
                            "Setup Required"
                        },
                        containerColor = if (savedKeys.isNotEmpty()) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    )
                }

                Text(
                    text = "Uses your Google AI Studio API keys (aistudio.google.com/apikey). Requests automatically prioritize healthy keys, apply per-model cooldowns on 429 limits, and fail over to your Fallback Model when needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Key Input with show/hide visibility toggle
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Enter Google AI Studio API Key") },
                    placeholder = { Text("AIzaSy...") },
                    visualTransformation = if (showApiKeyPlaintext) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showApiKeyPlaintext = !showApiKeyPlaintext }) {
                            Icon(
                                imageVector = if (showApiKeyPlaintext) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showApiKeyPlaintext) "Hide API key" else "Show API key"
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Clean equal-width button row (Test & Save Key | Test All) — no clipping or awkward wrapping
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        enabled = !isTestingSingle && !isTestingAll && !isCheckingAllowance && apiKey.isNotBlank(),
                        onClick = {
                            val candidate = apiKey.trim()
                            isTestingSingle = true
                            apiTestStatus = "Testing key connection..."
                            scope.launch {
                                val res = GeminiInteractionsClient().testApiKey(candidate, selectedModel)
                                res.fold(
                                    onSuccess = { replyMsg ->
                                        val existingPool = prefs.getSavedApiKeys()
                                        prefs.recordApiKeySuccess(candidate, selectedModel)
                                        keyHealthMap = prefs.getApiKeyHealthMap()
                                        if (existingPool.contains(candidate)) {
                                            apiTestStatus = "⚠️ Key is already in the pool (${existingPool.size} total keys)\n$replyMsg"
                                        } else {
                                            prefs.addApiKey(candidate)
                                            prefs.geminiApiKey = candidate
                                            savedKeys = prefs.getSavedApiKeys()
                                            apiKey = ""
                                            apiTestStatus = "✅ Connected & saved to API pool! (${savedKeys.size} total keys)\n$replyMsg"
                                        }
                                    },
                                    onFailure = { err ->
                                        if (prefs.getSavedApiKeys().contains(candidate)) {
                                            val cls = GeminiInteractionsClient.classifyApiFailure(err)
                                            prefs.recordApiKeyFailure(candidate, selectedModel, cls.category, err.message.orEmpty())
                                            keyHealthMap = prefs.getApiKeyHealthMap()
                                        }
                                        apiTestStatus = "❌ Failed: ${err.message}\n(Not saved: at least 1 successful test required)"
                                    }
                                )
                                isTestingSingle = false
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Text(
                            text = if (isTestingSingle) "Testing..." else "Test & Save Key",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (savedKeys.isNotEmpty()) {
                        OutlinedButton(
                            enabled = !isTestingAll && !isTestingSingle && !isCheckingAllowance,
                            onClick = {
                                isTestingAll = true
                                allKeysTestResults = "Testing all ${savedKeys.size} saved key(s) on $selectedModel..."
                                scope.launch {
                                    val client = GeminiInteractionsClient()
                                    var passed = 0
                                    var failed = 0
                                    val details = mutableListOf<String>()

                                    for ((idx, k) in savedKeys.withIndex()) {
                                        val mask = if (k.length > 8) "${k.take(6)}...${k.takeLast(4)}" else "***"
                                        val res = client.testApiKey(k, selectedModel)
                                        if (res.isSuccess) {
                                            passed++
                                            prefs.recordApiKeySuccess(k, selectedModel)
                                            details.add("✅ Key #${idx + 1} ($mask): OK")
                                        } else {
                                            failed++
                                            val err = res.exceptionOrNull()
                                            val errStr = err?.message?.take(60) ?: "Error"
                                            if (err != null) {
                                                val cls = GeminiInteractionsClient.classifyApiFailure(err)
                                                prefs.recordApiKeyFailure(k, selectedModel, cls.category, err.message.orEmpty())
                                            }
                                            details.add("❌ Key #${idx + 1} ($mask): $errStr")
                                        }
                                    }
                                    keyHealthMap = prefs.getApiKeyHealthMap()

                                    allKeysTestResults = if (failed == 0) {
                                        "✅ All $passed/${savedKeys.size} saved key(s) verified and working!"
                                    } else {
                                        "⚠️ $passed/${savedKeys.size} passed, $failed failed:\n" + details.joinToString("\n")
                                    }
                                    isTestingAll = false
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Text(
                                text = if (isTestingAll) "Testing All..." else "Test All (${savedKeys.size})",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (apiTestStatus != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = apiTestStatus!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                apiTestStatus!!.startsWith("✅") -> MaterialTheme.colorScheme.primary
                                apiTestStatus!!.startsWith("⚠️") -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                if (allKeysTestResults != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = allKeysTestResults!!,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // Saved API Key Pool List with Live Per-Key Health & Cooldowns
                if (savedKeys.isNotEmpty()) {
                    val nowMs = System.currentTimeMillis()
                    val hasAnyHealthWarnings = savedKeys.any { k ->
                        val h = keyHealthMap[PreferencesRepo.apiKeyFingerprint(k)]
                        h != null && (
                            h.errorCount > 0 ||
                                h.status == ApiKeyHealth.STATUS_AUTH_ERROR ||
                                h.anyActiveCooldownUntil(nowMs) > nowMs
                            )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Saved Keys in Pool (${savedKeys.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (hasAnyHealthWarnings) {
                                TextButton(
                                    onClick = {
                                        prefs.resetAllApiKeyHealth()
                                        keyHealthMap = prefs.getApiKeyHealthMap()
                                        Toast.makeText(context, "API key health & cooldowns reset", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Reset Health", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                                }
                            } else if (savedKeys.size > 1) {
                                BadgePill(
                                    text = "Smart Load-Balance",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            }
                        }
                    }

                    savedKeys.forEachIndexed { index, savedKey ->
                        val health = keyHealthMap[PreferencesRepo.apiKeyFingerprint(savedKey)]
                        val modelCooldownUntil = health?.activeCooldownUntilForModel(selectedModel, nowMs) ?: 0L
                        val anyCooldownUntil = health?.anyActiveCooldownUntil(nowMs) ?: 0L
                        val effectiveCooldown = if (modelCooldownUntil > nowMs) modelCooldownUntil else anyCooldownUntil
                        val (healthText, healthColor) = when {
                            health == null || health.status == ApiKeyHealth.STATUS_UNTESTED ->
                                "Untested" to MaterialTheme.colorScheme.surfaceVariant
                            health.status == ApiKeyHealth.STATUS_AUTH_ERROR ->
                                "Invalid" to MaterialTheme.colorScheme.errorContainer
                            effectiveCooldown > nowMs -> {
                                val minLeft = ((effectiveCooldown - nowMs + 59_999L) / 60_000L).coerceAtLeast(1L)
                                "CD ${minLeft}m" to Color(0xFFF59E0B).copy(alpha = 0.25f)
                            }
                            health.status == ApiKeyHealth.STATUS_HEALTHY ->
                                "OK (${health.successCount})" to Color(0xFF10B981).copy(alpha = 0.22f)
                            else ->
                                "Warn (${health.successCount}/${health.errorCount})" to Color(0xFFF59E0B).copy(alpha = 0.22f)
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "#${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                val masked = if (savedKey.length > 10) {
                                    "${savedKey.take(6)}...${savedKey.takeLast(4)}"
                                } else "••••••••"
                                Text(
                                    text = masked,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                BadgePill(
                                    text = healthText,
                                    containerColor = healthColor
                                )
                                OutlinedButton(
                                    enabled = !isTestingSingle && !isTestingAll && !isCheckingAllowance,
                                    onClick = {
                                        isTestingSingle = true
                                        val mask = if (savedKey.length > 10) "${savedKey.take(6)}...${savedKey.takeLast(4)}" else "Key #${index + 1}"
                                        apiTestStatus = "Testing Key #${index + 1} ($mask) on $selectedModel..."
                                        scope.launch {
                                            val res = GeminiInteractionsClient().testApiKey(savedKey, selectedModel)
                                            res.fold(
                                                onSuccess = { replyMsg ->
                                                    prefs.recordApiKeySuccess(savedKey, selectedModel)
                                                    keyHealthMap = prefs.getApiKeyHealthMap()
                                                    apiTestStatus = "✅ Key #${index + 1} ($mask) OK!\n$replyMsg"
                                                },
                                                onFailure = { err ->
                                                    val cls = GeminiInteractionsClient.classifyApiFailure(err)
                                                    prefs.recordApiKeyFailure(savedKey, selectedModel, cls.category, err.message.orEmpty())
                                                    keyHealthMap = prefs.getApiKeyHealthMap()
                                                    apiTestStatus = "❌ Key #${index + 1} ($mask) failed: ${err.message}"
                                                }
                                            )
                                            isTestingSingle = false
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(
                                        text = "Test",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                IconButton(
                                    enabled = !isTestingSingle && !isTestingAll && !isCheckingAllowance,
                                    onClick = {
                                        prefs.removeApiKey(savedKey)
                                        savedKeys = prefs.getSavedApiKeys()
                                        keyHealthMap = prefs.getApiKeyHealthMap()
                                        if (apiKey.trim() == savedKey) {
                                            apiKey = ""
                                        }
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete Key #${index + 1}",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Primary Model Selector + Live Allowance Probe
                val allowanceById = remember(allowanceEntries) {
                    allowanceEntries.associateBy { it.modelId }
                }
                val displayedModels = remember(allowanceEntries, selectedModel) {
                    buildList {
                        addAll(PreferencesRepo.SUPPORTED_MODELS)
                        allowanceEntries.forEach { entry ->
                            if (entry.modelId !in this) add(entry.modelId)
                        }
                        if (selectedModel.isNotBlank() && selectedModel !in this) {
                            add(selectedModel)
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Primary AI Model",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        val checkedAt = allowanceEntries.firstOrNull()?.checkedAtMs ?: 0L
                        val subLabel = if (checkedAt > 0L) {
                            val readyCnt = allowanceEntries.count { it.status == ModelAllowanceEntry.STATUS_AVAILABLE }
                            val paidCnt = allowanceEntries.count { it.status == ModelAllowanceEntry.STATUS_PAID_ONLY }
                            val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                            "Verified ${fmt.format(Date(checkedAt))} • $readyCnt ready, $paidCnt paid-only"
                        } else {
                            "Tap 'Check Allowance' to verify live model access on your key pool"
                        }
                        Text(
                            text = subLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = savedKeys.isNotEmpty() && !isCheckingAllowance && !isTestingSingle && !isTestingAll,
                        onClick = {
                            isCheckingAllowance = true
                            allowanceProgress = "Checking available Gemini models..."
                            scope.launch {
                                val entries = GeminiInteractionsClient().checkAvailableModelsAllowance(
                                    apiKeys = savedKeys,
                                    probeQuota = true,
                                    onProgress = { msg ->
                                        scope.launch(Dispatchers.Main) {
                                            allowanceProgress = msg
                                        }
                                    }
                                )
                                prefs.saveCachedModelAllowance(entries)
                                allowanceEntries = entries
                                allowanceProgress = null
                                isCheckingAllowance = false
                                val readyCnt = entries.count { it.status == ModelAllowanceEntry.STATUS_AVAILABLE }
                                Toast.makeText(
                                    context,
                                    if (entries.isNotEmpty()) {
                                        "$readyCnt/${entries.size} model(s) ready on your current API key allowance"
                                    } else {
                                        "Could not verify model allowance — check key & internet"
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        if (isCheckingAllowance) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Check Live Model Allowance",
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isCheckingAllowance) "Checking..." else "Check Allowance",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                if (allowanceProgress != null) {
                    Text(
                        text = allowanceProgress!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    displayedModels.forEach { model ->
                        val entry = allowanceById[model]
                        val chipLabel = when (entry?.status) {
                            ModelAllowanceEntry.STATUS_AVAILABLE ->
                                if (entry.latencyMs > 0L) "✓ $model (${entry.latencyMs}ms)" else "✓ $model"
                            ModelAllowanceEntry.STATUS_QUOTA_EXCEEDED ->
                                "⏱ $model [429]"
                            ModelAllowanceEntry.STATUS_PAID_ONLY ->
                                "💳 $model [Paid]"
                            ModelAllowanceEntry.STATUS_UNAVAILABLE ->
                                "✕ $model [N/A]"
                            ModelAllowanceEntry.STATUS_LISTED ->
                                "$model [Listed]"
                            else -> {
                                val billingBadge = PreferencesRepo.getModelBillingBadge(model)
                                if (model == PreferencesRepo.DEFAULT_MODEL) {
                                    "$model [$billingBadge] (Rec)"
                                } else {
                                    "$model [$billingBadge]"
                                }
                            }
                        }
                        FilterChip(
                            selected = selectedModel == model,
                            onClick = {
                                selectedModel = model
                                prefs.geminiModel = model
                            },
                            label = { Text(chipLabel, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                val selectedAllowance = allowanceById[selectedModel]
                Text(
                    text = buildString {
                        append(PreferencesRepo.getModelShortDescription(selectedModel))
                        if (selectedAllowance != null && selectedAllowance.detail.isNotBlank()) {
                            append(" • Live: ")
                            append(selectedAllowance.detail)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val showPaidWarning = selectedAllowance?.status == ModelAllowanceEntry.STATUS_PAID_ONLY ||
                    (selectedAllowance?.status != ModelAllowanceEntry.STATUS_AVAILABLE && !PreferencesRepo.isModelFreeTierEligible(selectedModel))
                if (showPaidWarning) {
                    Text(
                        text = "⚠️ '$selectedModel' requires a paid Google AI Studio billing tier. Free-tier keys work best with 'gemini-4-hydrogen', 'gemini-3.8-flash', or 'gemini-3.5-flash-lite'.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Configurable Auto Fallback Model (triggered on 429 / Quota / Paid-only / 404)
                Text(
                    text = "Auto Fallback Model (on Quota / 429)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "If all API keys hit rate limits or tier restrictions on your Primary Model, Ambient Brain automatically fails over to this model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val fallbackCandidates = remember(allowanceEntries, fallbackModel) {
                    buildList {
                        add("gemini-3.5-flash-lite")
                        add("gemini-4-hydrogen")
                        add("gemini-3.8-flash")
                        add("gemini-3.1-flash-lite")
                        add("gemini-2.5-flash-lite")
                        allowanceEntries
                            .filter { it.status == ModelAllowanceEntry.STATUS_AVAILABLE }
                            .forEach { if (it.modelId !in this) add(it.modelId) }
                        if (fallbackModel != PreferencesRepo.FALLBACK_MODEL_NONE && fallbackModel !in this) {
                            add(fallbackModel)
                        }
                        add(PreferencesRepo.FALLBACK_MODEL_NONE)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    fallbackCandidates.forEach { candidate ->
                        val label = when {
                            candidate == PreferencesRepo.FALLBACK_MODEL_NONE -> "Disabled"
                            candidate == PreferencesRepo.DEFAULT_FALLBACK_MODEL -> "$candidate (Rec)"
                            else -> candidate
                        }
                        FilterChip(
                            selected = fallbackModel == candidate,
                            onClick = {
                                fallbackModel = candidate
                                prefs.fallbackGeminiModel = candidate
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
                if (fallbackModel == selectedModel && fallbackModel != PreferencesRepo.FALLBACK_MODEL_NONE) {
                    Text(
                        text = "Tip: Pick a different model than your Primary Model (e.g., gemini-3.5-flash-lite) so quota failover uses an independent bucket.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "Default Transcript Language Mode (Adjustable)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TranscriptLangMode.entries.forEach { mode ->
                        FilterChip(
                            selected = langMode == mode,
                            onClick = {
                                langMode = mode
                                prefs.transcriptLangMode = mode
                            },
                            label = { Text(mode.label) }
                        )
                    }
                }
            }
        }

        // 2b. AI Usage, Latency & Pool Health Dashboard
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "AI Usage & Performance",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val nowDashMs = System.currentTimeMillis()
                    val readyKeys = savedKeys.count { k ->
                        val h = keyHealthMap[PreferencesRepo.apiKeyFingerprint(k)]
                        h == null || (h.status != ApiKeyHealth.STATUS_AUTH_ERROR && !h.isCoolingDownForModel(selectedModel, nowDashMs))
                    }
                    BadgePill(
                        text = if (savedKeys.isEmpty()) "No Keys" else "$readyKeys/${savedKeys.size} Keys Ready",
                        containerColor = if (savedKeys.isNotEmpty() && readyKeys > 0) {
                            Color(0xFF10B981).copy(alpha = 0.22f)
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(1 to "Today", 7 to "7 Days", 0 to "All Time").forEach { (days, label) ->
                        FilterChip(
                            selected = usageRangeDays == days,
                            onClick = { usageRangeDays = days },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                val effectiveTokens = if (usageRangeDays == 1 && usageSummary.totalCalls == 0 && tokensToday > 0L) {
                    tokensToday
                } else {
                    usageSummary.totalInTokens + usageSummary.totalOutTokens
                }
                val effectiveCached = if (usageRangeDays == 1 && usageSummary.totalCalls == 0 && cachedTokensToday > 0L) {
                    cachedTokensToday
                } else {
                    usageSummary.totalCachedTokens
                }
                val successRatePct = if (usageSummary.totalCalls > 0) {
                    ((usageSummary.successCalls * 100f) / usageSummary.totalCalls.toFloat()).toInt()
                } else 0
                val latencyLabel = when {
                    usageSummary.avgLatencyMs <= 0L -> "—"
                    usageSummary.avgLatencyMs >= 1000L -> String.format(Locale.US, "%.1fs", usageSummary.avgLatencyMs / 1000f)
                    else -> "${usageSummary.avgLatencyMs}ms"
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Calls", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "${usageSummary.totalCalls}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (usageSummary.totalCalls > 0) "$successRatePct% OK" else "No calls",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (usageSummary.totalCalls > 0 && successRatePct >= 80) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = formatCompactTokens(effectiveTokens),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (effectiveCached > 0L) "${formatCompactTokens(effectiveCached)} cached" else "In + Out",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Avg Latency", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = latencyLabel,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (usageSummary.failedCalls > 0) "${usageSummary.failedCalls} failed" else "Healthy",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (usageSummary.failedCalls > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (modelUsageBreakdown.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Model Breakdown",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    modelUsageBreakdown.forEach { row ->
                        val rowLat = when {
                            row.avgLatencyMs <= 0L -> "—"
                            row.avgLatencyMs >= 1000L -> String.format(Locale.US, "%.1fs", row.avgLatencyMs / 1000f)
                            else -> "${row.avgLatencyMs}ms"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = row.model,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${row.successCalls}/${row.calls} OK • ${formatCompactTokens(row.totalTokens)} • $rowLat",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                if (recentFailedRuns.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Recent Analysis Errors (${recentFailedRuns.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    val errTimeFmt = remember { SimpleDateFormat("MMM d HH:mm", Locale.getDefault()) }
                    recentFailedRuns.forEach { run ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "${run.model} • ${errTimeFmt.format(Date(run.atMs))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = run.errorMsg.orEmpty().take(140),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Speaker Labeling & Auto Voice Profiles
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Speaker Labeling & Auto Voice Profiles",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    BadgePill(
                        text = "Up to ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} Best / Profile",
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }

                OutlinedTextField(
                    value = userDisplayName,
                    onValueChange = { userDisplayName = it },
                    label = { Text("Your Speaker Label (e.g., Dr. Somchai (Me))") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val myBestSample = myVoiceSamples.firstOrNull()
                val myBestFile = myBestSample?.filePath?.let { File(it) }
                val isPlayingMyVoice = playbackController != null &&
                    myBestFile != null &&
                    playbackController.activeFilePath == myBestFile.absolutePath &&
                    playbackController.isPlaying

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BadgePill(
                        text = "My Voice: ${myVoiceSamples.size}/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE}",
                        containerColor = if (myVoiceSamples.isNotEmpty()) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    if (myBestSample != null) {
                        BadgePill(
                            text = "Best Q${myBestSample.qualityScore.toInt()} • ${voiceQualityTierLabel(myBestSample.qualityScore)}",
                            containerColor = voiceQualityBadgeColor(myBestSample.qualityScore)
                        )
                    }
                }

                Text(
                    text = if (myVoiceSamples.isNotEmpty()) {
                        "✅ ${myVoiceSamples.size} best-quality 'My Voice' clip(s) saved. Auto-recognition also keeps your clearest solo clips from recordings (top ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} retained)."
                    } else {
                        "Record a 12-second voice sample in Thai + English medical terms so Gemini accurately identifies your voice across rounds."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isRecordingProfile) {
                    LinearProgressIndicator(
                        progress = { (profileSecRecorded / 12f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Button(
                    enabled = !isRecordingProfile && recordingSpeakerId == null,
                    onClick = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasMic) {
                            Toast.makeText(context, "Microphone permission is required to record your voice profile.", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        if (playbackController?.isPlaying == true) {
                            playbackController.stop()
                        }
                        isRecordingProfile = true
                        profileSecRecorded = 0
                        scope.launch {
                            val newSampleId = UUID.randomUUID().toString().take(8)
                            val outFile = prefs.getMyVoiceSampleWavFile(newSampleId)
                            val ok = VoiceProfileRecorder.recordVoiceClipToFile(
                                outFile = outFile,
                                durationSeconds = 12,
                                onProgressSec = { sec -> profileSecRecorded = sec }
                            )
                            isRecordingProfile = false
                            if (ok && outFile.exists() && outFile.length() >= 1024L) {
                                val (metrics, embedding) = withContext(Dispatchers.IO) {
                                    ClipSlicer.analyzeAdtsSampleQuality(outFile) to
                                        SpeakerEmbeddingEngine.extractEmbeddingFromFile(context, outFile)
                                }
                                val manualScore = (metrics.score + 10f).coerceIn(10f, 100f)
                                val candidate = VoiceProfileSample(
                                    id = newSampleId,
                                    filePath = outFile.absolutePath,
                                    durationMs = metrics.durationMs.takeIf { it > 0L } ?: 12_000L,
                                    qualityScore = manualScore,
                                    snrDb = metrics.snrDb,
                                    speechRatio = metrics.speechRatio,
                                    createdAtMs = System.currentTimeMillis(),
                                    sourceSessionId = null,
                                    isManual = true,
                                    embedding = embedding
                                )
                                val (accepted, updatedList) = prefs.addMyVoiceSample(candidate)
                                myVoiceSamples = updatedList
                                hasProfile = updatedList.isNotEmpty()
                                val tier = voiceQualityTierLabel(manualScore)
                                Toast.makeText(
                                    context,
                                    if (accepted) {
                                        "My Voice sample saved (Q${manualScore.toInt()} • $tier, ${updatedList.size}/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE})!"
                                    } else {
                                        "Sample recorded (Q${manualScore.toInt()}), scored below your existing ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} best clips."
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                runCatching { outFile.delete() }
                                Toast.makeText(context, "Could not record voice profile — check mic access.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("record_my_voice_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRecordingProfile) {
                            "Recording... (${profileSecRecorded}s / 12s)"
                        } else if (hasProfile) {
                            "Record New 12s 'My Voice' Sample"
                        } else {
                            "Record 12s 'My Voice' Profile"
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (myVoiceSamples.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (playbackController != null && myBestFile != null && myBestFile.exists()) {
                            OutlinedButton(
                                enabled = !isRecordingProfile,
                                onClick = {
                                    playbackController.togglePlayFile(
                                        filePath = myBestFile.absolutePath,
                                        title = "My Voice (${userDisplayName.ifBlank { "Me" }})"
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlayingMyVoice) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlayingMyVoice) "Stop My Voice" else "Play Best My Voice",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isPlayingMyVoice) "Stop" else "Play Best",
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        OutlinedButton(
                            enabled = !isRecordingProfile,
                            onClick = { viewingMyVoiceSamples = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1.35f)
                                .height(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "View & Export My Voice Samples",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Samples & Export (${myVoiceSamples.size}/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE})",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Recognize Voice Profiles from Recordings",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Evaluates multiple solo speech windows per speaker, scores acoustic clarity & on-device voiceprint consistency, keeps the top ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} best samples per profile, and pre-matches voices before Gemini.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoRecognizeVoiceProfiles,
                        onCheckedChange = {
                            autoRecognizeVoiceProfiles = it
                            prefs.autoRecognizeVoiceProfiles = it
                        },
                        modifier = Modifier.testTag("settings_auto_voice_profiles_switch")
                    )
                }

                if (pickingCustomDateSlot != 0) {
                    val existingDate = if (pickingCustomDateSlot == 1) {
                        customExtractStartDate ?: LocalDate.now(ZoneId.systemDefault()).minusDays(7)
                    } else {
                        customExtractEndDate ?: LocalDate.now(ZoneId.systemDefault())
                    }
                    val initialUtcMs = remember(pickingCustomDateSlot, existingDate) {
                        existingDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    }
                    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMs)
                    DatePickerDialog(
                        onDismissRequest = { pickingCustomDateSlot = 0 },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pickerState.selectedDateMillis?.let { pickedMs ->
                                        val pickedDate = Instant.ofEpochMilli(pickedMs)
                                            .atZone(ZoneOffset.UTC)
                                            .toLocalDate()
                                        if (pickingCustomDateSlot == 1) {
                                            customExtractStartDate = pickedDate
                                            if (customExtractEndDate == null || customExtractEndDate!!.isBefore(pickedDate)) {
                                                customExtractEndDate = pickedDate
                                            }
                                        } else {
                                            customExtractEndDate = pickedDate
                                            if (customExtractStartDate == null || customExtractStartDate!!.isAfter(pickedDate)) {
                                                customExtractStartDate = pickedDate
                                            }
                                        }
                                        selectedExtractTimeFrame = VoiceExtractionTimeFrame.CUSTOM_RANGE
                                    }
                                    pickingCustomDateSlot = 0
                                }
                            ) {
                                Text("Confirm")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pickingCustomDateSlot = 0 }) {
                                Text("Cancel")
                            }
                        }
                    ) {
                        DatePicker(state = pickerState)
                    }
                }

                if (showCustomTimeFrameDialog) {
                    val dateFmt = remember { DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()) }
                    val today = remember { LocalDate.now(ZoneId.systemDefault()) }
                    val effectiveStart = customExtractStartDate ?: today.minusDays(7)
                    val effectiveEnd = customExtractEndDate ?: today
                    AlertDialog(
                        onDismissRequest = { showCustomTimeFrameDialog = false },
                        title = { Text("Select Custom Recording Time Frame") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "Choose the start and end dates of old recordings to scan for voice signatures:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = { pickingCustomDateSlot = 1 },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Start Date: ${effectiveStart.format(dateFmt)}")
                                }
                                OutlinedButton(
                                    onClick = { pickingCustomDateSlot = 2 },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("End Date: ${effectiveEnd.format(dateFmt)}")
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (customExtractStartDate == null) customExtractStartDate = effectiveStart
                                    if (customExtractEndDate == null) customExtractEndDate = effectiveEnd
                                    selectedExtractTimeFrame = VoiceExtractionTimeFrame.CUSTOM_RANGE
                                    showCustomTimeFrameDialog = false
                                }
                            ) {
                                Text("Apply Time Frame")
                            }
                        },
                        dismissButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = {
                                        selectedExtractTimeFrame = VoiceExtractionTimeFrame.ALL
                                        showCustomTimeFrameDialog = false
                                    }
                                ) {
                                    Text("All Recordings")
                                }
                                TextButton(onClick = { showCustomTimeFrameDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    )
                }

                // Retroactive Voice Profile Extraction from Old Recorded Audio (Time-Frame Selectable + Gemini Top-Pick Audit)
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val activeTimeFrameLabel = remember(selectedExtractTimeFrame, customExtractStartDate, customExtractEndDate) {
                        VoiceProfileAutoManager.resolveTimeFrameBounds(
                            timeFrame = selectedExtractTimeFrame,
                            customStartLocalDate = customExtractStartDate,
                            customEndLocalDate = customExtractEndDate
                        ).third
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Recording Time Frame for Voice Extraction:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        BadgePill(
                            text = activeTimeFrameLabel,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_extract_timeframe_row")
                    ) {
                        VoiceExtractionTimeFrame.values().forEach { tf ->
                            val isSelected = selectedExtractTimeFrame == tf
                            val chipText = if (tf == VoiceExtractionTimeFrame.CUSTOM_RANGE &&
                                isSelected && (customExtractStartDate != null || customExtractEndDate != null)
                            ) {
                                activeTimeFrameLabel
                            } else {
                                tf.label
                            }
                            FilterChip(
                                selected = isSelected,
                                enabled = !isExtractingOldVoices && !isAuditingVoicesWithGemini,
                                onClick = {
                                    if (tf == VoiceExtractionTimeFrame.CUSTOM_RANGE) {
                                        showCustomTimeFrameDialog = true
                                    } else {
                                        selectedExtractTimeFrame = tf
                                    }
                                },
                                label = {
                                    Text(
                                        text = chipText,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("settings_extract_timeframe_${tf.name.lowercase()}")
                            )
                        }
                    }

                    FilledTonalButton(
                        enabled = !isExtractingOldVoices && !isAuditingVoicesWithGemini && !isRecordingProfile && recordingSpeakerId == null,
                        onClick = {
                            isExtractingOldVoices = true
                            oldVoiceExtractProgress = 0 to 0
                            oldVoiceExtractStatusText = "Preparing to scan recordings ($activeTimeFrameLabel)..."
                            oldVoiceExtractSummary = null
                            scope.launch {
                                try {
                                    val summary = VoiceProfileAutoManager.extractVoiceProfilesFromAllOldRecordings(
                                        context = context,
                                        includeUnlabeledScan = true,
                                        timeFrame = selectedExtractTimeFrame,
                                        customStartLocalDate = customExtractStartDate,
                                        customEndLocalDate = customExtractEndDate,
                                        onProgress = { cur, total, label ->
                                            scope.launch {
                                                oldVoiceExtractProgress = cur to total
                                                oldVoiceExtractStatusText = label
                                            }
                                        }
                                    )
                                    val updatedMy = prefs.getMyVoiceSamples()
                                    val updatedRoster = prefs.getSpeakerRoster()
                                    val foundPairs = withContext(Dispatchers.IO) {
                                        SpeakerEmbeddingEngine.findPossibleDuplicatePairs(updatedRoster)
                                    }
                                    myVoiceSamples = updatedMy
                                    hasProfile = updatedMy.isNotEmpty()
                                    speakerRoster = updatedRoster
                                    duplicateSuggestions = foundPairs
                                    oldVoiceExtractSummary = summary
                                    val toastText = if (summary.sessionsScanned + summary.standaloneFilesScanned == 0) {
                                        "No audio recordings found in '$activeTimeFrameLabel'."
                                    } else {
                                        "Scanned ${summary.sessionsScanned + summary.standaloneFilesScanned} recording(s) ($activeTimeFrameLabel): " +
                                            "${summary.profilesMatched} matched, ${summary.profilesCreated} new, " +
                                            "${summary.samplesAddedOrUpgraded} sample(s) saved."
                                    }
                                    Toast.makeText(context, toastText, Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Old audio voice extraction failed: ${e.message ?: "unknown error"}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } finally {
                                    isExtractingOldVoices = false
                                    oldVoiceExtractStatusText = null
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("settings_extract_old_voices_button")
                    ) {
                        if (isExtractingOldVoices) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        val (cur, tot) = oldVoiceExtractProgress
                        Text(
                            text = if (isExtractingOldVoices) {
                                if (tot > 0) "Extracting Old Audio ($cur/$tot)..." else "Scanning Old Audio..."
                            } else if (selectedExtractTimeFrame == VoiceExtractionTimeFrame.ALL) {
                                "Extract Voices from Old Recordings (All)"
                            } else {
                                "Extract Voices from Old Recordings ($activeTimeFrameLabel)"
                            },
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (isExtractingOldVoices) {
                        val (cur, tot) = oldVoiceExtractProgress
                        if (tot > 0) {
                            LinearProgressIndicator(
                                progress = { (cur.toFloat() / tot.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                            )
                        }
                        oldVoiceExtractStatusText?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(
                            text = "Offline scan: mines 48-D voiceprints & saves up to ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} best-quality voice signature clips per profile from recordings in the selected time frame, separating distinct speakers and rechecking Name/Title/Description suitability.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Helper lambda to launch Gemini Top-Pick Voice Profile Audit & Creation
                    val runGeminiTopPickVoiceAudit: () -> Unit = {
                        commitPendingEdits()
                        isAuditingVoicesWithGemini = true
                        geminiVoiceAuditStatusText = "Preparing top-pick voice sample audios for Gemini..."
                        geminiVoiceAuditSummary = null
                        scope.launch {
                            try {
                                val auditRes = VoiceProfileAutoManager.sendTopPickAudiosToGeminiForVoiceProfileAudit(
                                    context = context,
                                    onProgress = { msg ->
                                        scope.launch {
                                            geminiVoiceAuditStatusText = msg
                                        }
                                    }
                                )
                                val updatedMy = prefs.getMyVoiceSamples()
                                val updatedRoster = prefs.getSpeakerRoster()
                                val foundPairs = withContext(Dispatchers.IO) {
                                    SpeakerEmbeddingEngine.findPossibleDuplicatePairs(updatedRoster)
                                }
                                myVoiceSamples = updatedMy
                                hasProfile = updatedMy.isNotEmpty()
                                speakerRoster = updatedRoster
                                duplicateSuggestions = foundPairs
                                geminiVoiceAuditSummary = auditRes
                                Toast.makeText(
                                    context,
                                    "Gemini audited ${auditRes.profilesAudited} profile(s) (${auditRes.topPickAudiosSent} top-pick clips): " +
                                        "${auditRes.profilesRefined} refined, ${auditRes.newProfilesCreated} created!",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Gemini Voice Profile Audit failed: ${e.message ?: "unknown error"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                isAuditingVoicesWithGemini = false
                                geminiVoiceAuditStatusText = null
                            }
                        }
                    }

                    oldVoiceExtractSummary?.let { summary ->
                        if (!isExtractingOldVoices) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = "Old Audio Scan Complete • ${summary.timeFrameLabel} (${summary.sessionsScanned} sessions, ${summary.standaloneFilesScanned} files)",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            Text(
                                                text = "Matched: ${summary.profilesMatched} • New profiles: ${summary.profilesCreated} • Top-10 samples added/upgraded: ${summary.samplesAddedOrUpgraded}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            if (summary.contaminatedSamplesSeparated > 0 || summary.profilesMetadataRechecked > 0) {
                                                Text(
                                                    text = "Speaker separation & suitability check: ${summary.contaminatedSamplesSeparated} mixed sample(s) separated • ${summary.profilesMetadataRechecked} profile Name/Title/Description(s) rechecked.",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            if (summary.skippedOutOfTimeFrame > 0) {
                                                Text(
                                                    text = "${summary.skippedOutOfTimeFrame} recording(s) outside '${summary.timeFrameLabel}' were skipped.",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { oldVoiceExtractSummary = null },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Dismiss scan summary",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    // Post-Extraction Action Button: Send Top-Pick Audios with Best Voice Sample Quality to Gemini
                                    Button(
                                        enabled = !isAuditingVoicesWithGemini && !isExtractingOldVoices && !isRecordingProfile && recordingSpeakerId == null,
                                        onClick = runGeminiTopPickVoiceAudit,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .testTag("settings_gemini_audit_extracted_voices_button")
                                    ) {
                                        if (isAuditingVoicesWithGemini) {
                                            CircularProgressIndicator(
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isAuditingVoicesWithGemini) {
                                                "Sending Top-Pick Audios to Gemini..."
                                            } else {
                                                "Send Top-Pick Audios to Gemini to Audit & Create Profiles"
                                            },
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Text(
                                        text = "Sends top-pick highest-quality voice samples from each profile to Gemini to verify single-speaker purity (never combining different speakers), audit & refine Name / Title / Description suitability, and create accurate Voice Profiles.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Also show the Gemini Top-Pick Audit button whenever the roster already has voice samples (even before/after dismissing the scan card)
                    val hasAnyRosterSamples = remember(speakerRoster) {
                        speakerRoster.any { it.existingSamples().isNotEmpty() }
                    }
                    if (oldVoiceExtractSummary == null && hasAnyRosterSamples && !isExtractingOldVoices) {
                        OutlinedButton(
                            enabled = !isAuditingVoicesWithGemini && !isRecordingProfile && recordingSpeakerId == null,
                            onClick = runGeminiTopPickVoiceAudit,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .testTag("settings_gemini_audit_roster_button")
                        ) {
                            if (isAuditingVoicesWithGemini) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(15.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isAuditingVoicesWithGemini) {
                                    "Auditing Top-Pick Voice Samples with Gemini..."
                                } else {
                                    "Send Top-Pick Audios to Gemini (Audit & Refine Profiles)"
                                },
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (isAuditingVoicesWithGemini) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                        )
                        geminiVoiceAuditStatusText?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    geminiVoiceAuditSummary?.let { auditSummary ->
                        if (!isAuditingVoicesWithGemini) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Gemini Top-Pick Voice Profile Audit Complete",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "Audited ${auditSummary.profilesAudited} profile(s) • ${auditSummary.topPickAudiosSent} top-pick clip(s) • " +
                                                    "${auditSummary.profilesRefined} refined • ${auditSummary.newProfilesCreated} created • " +
                                                    "${auditSummary.mixedProfilesSeparated} mixed speaker(s) separated",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = { geminiVoiceAuditSummary = null },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Dismiss Gemini audit summary",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    if (auditSummary.overallAuditSummary.isNotBlank()) {
                                        Text(
                                            text = auditSummary.overallAuditSummary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    auditSummary.items.take(8).forEach { item ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                val nameHeader = if (item.wasNameRefined && !item.previousName.equals(item.finalName, ignoreCase = true)) {
                                                    "${item.previousName} → ${item.finalName}"
                                                } else {
                                                    item.finalName
                                                }
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = nameHeader,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    BadgePill(
                                                        text = when {
                                                            item.isNewlyCreated -> "New Profile"
                                                            item.wasSeparatedFromMixedSpeaker -> "Speaker Separated"
                                                            item.wasNameRefined || item.wasTitleRefined || item.wasDescriptionRefined -> "Refined"
                                                            else -> "Verified Pure"
                                                        },
                                                        containerColor = if (item.wasSeparatedFromMixedSpeaker || item.isNewlyCreated) {
                                                            MaterialTheme.colorScheme.tertiaryContainer
                                                        } else {
                                                            MaterialTheme.colorScheme.secondaryContainer
                                                        }
                                                    )
                                                }
                                                if (item.finalTitle.isNotBlank()) {
                                                    val titleLine = if (item.wasTitleRefined && item.previousTitle.isNotBlank() &&
                                                        !item.previousTitle.equals(item.finalTitle, ignoreCase = true)
                                                    ) {
                                                        "Title: ${item.previousTitle} → ${item.finalTitle}"
                                                    } else {
                                                        "Title: ${item.finalTitle}"
                                                    }
                                                    Text(
                                                        text = titleLine,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                if (item.finalSignature.isNotBlank()) {
                                                    Text(
                                                        text = "Voice: ${item.finalSignature}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                if (item.auditNotes.isNotBlank()) {
                                                    Text(
                                                        text = item.auditNotes,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                val importVoiceProfileZipLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        runCatching {
                            AppDataExporter.importVoiceProfileZipFromUri(
                                context = context,
                                zipUri = uri
                            )
                        }.onSuccess { res ->
                            speakerRoster = prefs.getSpeakerRoster()
                            myVoiceSamples = prefs.getMyVoiceSamples()
                            hasProfile = prefs.hasMyVoiceProfile()
                            Toast.makeText(context, res.summaryMessage, Toast.LENGTH_LONG).show()
                        }.onFailure { err ->
                            Toast.makeText(
                                context,
                                "Voice Profile ZIP import failed: ${err.message ?: "Unknown error"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Recognized Voice Profiles & Roster (${speakerRoster.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Each profile retains up to ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} highest-quality clips (lossless 16 kHz .wav & 64 kbps .aac) with 48-D voiceprints, and exports/imports as portable .voiceprofile.zip bundles.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                importVoiceProfileZipLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/octet-stream"
                                    )
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("settings_import_voice_profile_zip_button")
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = "Import .voiceprofile.zip", modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Import .zip", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = { showAddSpeakerDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("add_speaker_roster_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Speaker", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                        }
                    }
                }

                // On-device Voiceprint Duplicate Profile Merge Suggestions
                val visibleDuplicates = remember(duplicateSuggestions, dismissedDuplicatePairs) {
                    duplicateSuggestions.filter { sug ->
                        "${sug.primaryProfile.id}:${sug.duplicateProfile.id}" !in dismissedDuplicatePairs
                    }
                }
                if (visibleDuplicates.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallMerge,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Possible Duplicate Voice Profiles (${visibleDuplicates.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            visibleDuplicates.forEach { sug ->
                                val pairKey = "${sug.primaryProfile.id}:${sug.duplicateProfile.id}"
                                val simPct = (sug.similarity * 100f).toInt().coerceIn(1, 100)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${sug.primaryProfile.effectiveNickname()} ↔ ${sug.duplicateProfile.effectiveNickname()}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "$simPct% voiceprint similarity",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                val updated = withContext(Dispatchers.IO) {
                                                    VoiceProfileAutoManager.mergeProfiles(
                                                        context = context,
                                                        sourceProfileId = sug.duplicateProfile.id,
                                                        targetProfileId = sug.primaryProfile.id
                                                    )
                                                }
                                                speakerRoster = updated
                                                Toast.makeText(
                                                    context,
                                                    "Merged '${sug.duplicateProfile.effectiveNickname()}' into '${sug.primaryProfile.effectiveNickname()}'",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Merge", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                                    }
                                    TextButton(
                                        onClick = { dismissedDuplicatePairs = dismissedDuplicatePairs + pairKey },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Dismiss", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }

                if (speakerRoster.isEmpty()) {
                    Text(
                        text = "No voice profiles yet. Analyze any conversation with Auto-Recognize enabled, tap '+ Add', or rename a speaker in any transcript.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    speakerRoster.forEach { spk ->
                        val spkSamples = spk.existingSamples()
                        val bestSample = spkSamples.firstOrNull()
                        val bestFile = bestSample?.filePath?.let { File(it) }
                        val hasClip = bestFile != null && bestFile.exists() && bestFile.length() >= 1024L
                        val isRecordingThis = recordingSpeakerId == spk.id
                        val isPlayingThis = playbackController != null &&
                            bestFile != null &&
                            playbackController.activeFilePath == bestFile.absolutePath &&
                            playbackController.isPlaying

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                if (spk.isPinned) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = spk.effectiveNickname(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val titleText = spk.effectiveRelatableTitle()
                                        if (titleText.isNotBlank()) {
                                            Text(
                                                text = titleText,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        val sigText = spk.voiceSignature.ifBlank { spk.voiceHint }
                                        if (sigText.isNotBlank()) {
                                            Text(
                                                text = sigText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        val bestMeta = if (bestSample != null) {
                                            val durLabel = if (bestSample.durationMs > 0L) {
                                                " (${formatAudioDuration(bestSample.durationMs)})"
                                            } else ""
                                            " • Best Q${bestSample.qualityScore.toInt()}$durLabel"
                                        } else ""
                                        Text(
                                            text = "Heard ${spk.sessionsHeardCount}x • ${spkSamples.size}/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} samples$bestMeta",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        BadgePill(
                                            text = if (spk.isAutoDiscovered) "Auto" else "Manual",
                                            containerColor = if (spk.isAutoDiscovered) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
                                        )
                                        if (bestSample != null) {
                                            BadgePill(
                                                text = "Q${bestSample.qualityScore.toInt()}",
                                                containerColor = voiceQualityBadgeColor(bestSample.qualityScore)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                speakerRoster = prefs.updateSpeakerProfileById(spk.id) { prev ->
                                                    prev.copy(isPinned = !prev.isPinned)
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PushPin,
                                                contentDescription = if (spk.isPinned) "Unpin profile" else "Pin profile",
                                                tint = if (spk.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { editingSpeaker = spk },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit voice profile ${spk.effectiveNickname()}",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                if (isRecordingThis) {
                                    LinearProgressIndicator(
                                        progress = { (speakerClipSecRecorded / 10f).coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                // Row 1: Play Best + View & Export Samples (X/10)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (hasClip && playbackController != null && bestFile != null) {
                                        OutlinedButton(
                                            enabled = !isRecordingThis,
                                            onClick = {
                                                playbackController.togglePlayFile(
                                                    filePath = bestFile.absolutePath,
                                                    title = spk.formattedLabel()
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(38.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isPlayingThis) Icons.Default.Stop else Icons.Default.PlayArrow,
                                                contentDescription = if (isPlayingThis) "Stop" else "Play best clip",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isPlayingThis) "Stop" else "Play",
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    OutlinedButton(
                                        enabled = !isRecordingThis,
                                        onClick = { viewingSpeakerSamples = spk },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.GraphicEq,
                                            contentDescription = "View & Export samples",
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Samples & Export (${spkSamples.size}/${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE})",
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Row 2: Record New 10s Sample + Merge + Delete
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        enabled = !isRecordingProfile && recordingSpeakerId == null,
                                        onClick = {
                                            val hasMic = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (!hasMic) {
                                                Toast.makeText(context, "Microphone permission is required to record a speaker clip.", Toast.LENGTH_LONG).show()
                                                return@OutlinedButton
                                            }
                                            if (playbackController?.isPlaying == true) {
                                                playbackController.stop()
                                            }
                                            recordingSpeakerId = spk.id
                                            speakerClipSecRecorded = 0
                                            scope.launch {
                                                val newSampleId = UUID.randomUUID().toString().take(8)
                                                val outFile = prefs.getSpeakerSampleWavFile(spk.id, newSampleId)
                                                val ok = VoiceProfileRecorder.recordVoiceClipToFile(
                                                    outFile = outFile,
                                                    durationSeconds = 10,
                                                    onProgressSec = { sec -> speakerClipSecRecorded = sec }
                                                )
                                                if (ok && outFile.exists() && outFile.length() >= 1024L) {
                                                    val (metrics, embedding) = withContext(Dispatchers.IO) {
                                                        ClipSlicer.analyzeAdtsSampleQuality(outFile) to
                                                            SpeakerEmbeddingEngine.extractEmbeddingFromFile(context, outFile)
                                                    }
                                                    val manualScore = (metrics.score + 10f).coerceIn(10f, 100f)
                                                    val newSample = VoiceProfileSample(
                                                        id = newSampleId,
                                                        filePath = outFile.absolutePath,
                                                        durationMs = metrics.durationMs.takeIf { it > 0L } ?: 10_000L,
                                                        qualityScore = manualScore,
                                                        snrDb = metrics.snrDb,
                                                        speechRatio = metrics.speechRatio,
                                                        createdAtMs = System.currentTimeMillis(),
                                                        sourceSessionId = null,
                                                        isManual = true,
                                                        embedding = embedding
                                                    )
                                                    val (accepted, _) = prefs.addSpeakerVoiceSample(spk.id, newSample)
                                                    speakerRoster = prefs.getSpeakerRoster()
                                                    val tier = voiceQualityTierLabel(manualScore)
                                                    Toast.makeText(
                                                        context,
                                                        if (accepted) {
                                                            "Saved sample for ${spk.effectiveNickname()} (Q${manualScore.toInt()} • $tier)"
                                                        } else {
                                                            "Sample (Q${manualScore.toInt()}) scored below existing ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} best clips."
                                                        },
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    runCatching { outFile.delete() }
                                                    Toast.makeText(context, "Recording failed — please try again", Toast.LENGTH_SHORT).show()
                                                }
                                                recordingSpeakerId = null
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp)
                                    ) {
                                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isRecordingThis) "Rec ${speakerClipSecRecorded}s/10s"
                                            else if (hasClip) "Record +10s Sample"
                                            else "Record 10s Sample",
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }

                                    if (speakerRoster.size >= 2) {
                                        TextButton(
                                            enabled = !isRecordingThis,
                                            onClick = { mergingSpeaker = spk },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(38.dp)
                                        ) {
                                            Text("Merge...", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                        }
                                    }

                                    IconButton(
                                        enabled = !isRecordingThis,
                                        onClick = {
                                            val activePath = playbackController?.activeFilePath
                                            if (activePath != null && spkSamples.any { it.filePath == activePath }) {
                                                playbackController.stop()
                                            }
                                            speakerRoster = prefs.removeSpeakerProfile(spk.id)
                                        },
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Remove speaker ${spk.effectiveNickname()}",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Round Detection, Silence Thresholds & Auto-Purge
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Round Detection, Speech Gate & Retention",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatically Detect Medical Rounds", fontWeight = FontWeight.Medium)
                        Text(
                            "Promotes clinical discussions to Round Mode & stitches consecutive beds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoDetectRounds,
                        onCheckedChange = {
                            autoDetectRounds = it
                            prefs.autoDetectRounds = it
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Merge Related Continuous Files", fontWeight = FontWeight.Medium)
                        Text(
                            "Automatically groups consecutive recordings into one unified reviewed event when gap ≤ ${autoMergeGapMinutes}m or same round",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoMergeContinuousFiles,
                        onCheckedChange = {
                            autoMergeContinuousFiles = it
                            prefs.autoMergeContinuousFiles = it
                        },
                        modifier = Modifier.testTag("settings_auto_merge_switch")
                    )
                }

                Text(
                    text = "Continuous Merge Time Gap: ${autoMergeGapMinutes} min",
                    style = MaterialTheme.typography.labelMedium
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30, 45).forEach { mins ->
                        FilterChip(
                            selected = autoMergeGapMinutes == mins,
                            onClick = {
                                autoMergeGapMinutes = mins
                                prefs.autoMergeGapMinutes = mins
                            },
                            label = { Text("${mins}m") }
                        )
                    }
                }

                Text(
                    text = "Default AI Review / Auto-Merge Input Source:",
                    style = MaterialTheme.typography.labelMedium
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !defaultReviewSourceIsAudio,
                        onClick = {
                            defaultReviewSourceIsAudio = false
                            prefs.defaultReviewSourceIsAudio = false
                        },
                        label = { Text("Extracted Info Only (Fast)") }
                    )
                    FilterChip(
                        selected = defaultReviewSourceIsAudio,
                        onClick = {
                            defaultReviewSourceIsAudio = true
                            prefs.defaultReviewSourceIsAudio = true
                        },
                        label = { Text("Re-analyze Audio Files") }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "Shortest Meaningful Record Length: ${minMeaningfulSpeechSec.toInt()} sec",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Recordings with less than ${minMeaningfulSpeechSec.toInt()}s of detected speech (Silero VAD) are discarded instead of saved or sent to Gemini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = minMeaningfulSpeechSec,
                    onValueChange = { minMeaningfulSpeechSec = it },
                    onValueChangeFinished = {
                        prefs.minMeaningfulSpeechSeconds = minMeaningfulSpeechSec.toInt()
                    },
                    valueRange = 1f..60f
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5, 10, 15, 30, 60).forEach { sec ->
                        FilterChip(
                            selected = minMeaningfulSpeechSec.toInt() == sec,
                            onClick = {
                                minMeaningfulSpeechSec = sec.toFloat()
                                prefs.minMeaningfulSpeechSeconds = sec
                            },
                            label = { Text("${sec}s") }
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text("Everyday Silence Split: ${silenceSplitSec.toInt()} seconds", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = silenceSplitSec,
                    onValueChange = { silenceSplitSec = it },
                    onValueChangeFinished = {
                        prefs.silenceSplitSeconds = silenceSplitSec.toInt()
                    },
                    valueRange = 30f..180f
                )

                Text("Round Mode Silence Split: ${(roundSilenceSplitSec / 60f).toInt()} min (${roundSilenceSplitSec.toInt()}s)", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = roundSilenceSplitSec,
                    onValueChange = { roundSilenceSplitSec = it },
                    onValueChangeFinished = {
                        prefs.roundSilenceSplitSeconds = roundSilenceSplitSec.toInt()
                    },
                    valueRange = 120f..600f
                )

                Text("Auto-Purge Raw Session Audio After (Clips Kept Forever):", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 7, 14, 30).forEach { days ->
                        FilterChip(
                            selected = retentionDays == days,
                            onClick = {
                                retentionDays = days
                                prefs.retentionDays = days
                            },
                            label = { Text("${days}d") }
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                val effectiveVideoFolderLabel = remember(
                    meetingVideoSaveFolder,
                    meetingVideoCustomTreeUri,
                    meetingVideoCustomFolderLabel
                ) {
                    if (meetingVideoCustomTreeUri.isNotBlank()) {
                        meetingVideoCustomFolderLabel.ifBlank { "Custom Folder" }
                    } else {
                        meetingVideoSaveFolder
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Meeting & Isolated App MP4 Save Location",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = "Zoom, Webex, and other isolated app MP4 recordings are saved into a normal File Explorer folder (default: Download/AmbientBrain).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (meetingVideoCustomTreeUri.isNotBlank()) {
                                    "Active Custom Folder (Android Folder Picker)"
                                } else {
                                    "Active Public Folder (File Explorer)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Internal Storage / $effectiveVideoFolderLabel",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val folderPresets = listOf(
                        PreferencesRepo.DEFAULT_MEETING_VIDEO_FOLDER to "Download/AmbientBrain (Default)",
                        "Download/Meetings" to "Download/Meetings",
                        "Download" to "Download",
                        "Movies/AmbientBrain" to "Movies/AmbientBrain"
                    )
                    folderPresets.forEach { (folderPath, label) ->
                        val isPresetSelected = meetingVideoCustomTreeUri.isBlank() &&
                            PreferencesRepo.normalizePublicVideoFolder(meetingVideoSaveFolder) == folderPath
                        FilterChip(
                            selected = isPresetSelected,
                            onClick = {
                                meetingVideoSaveFolder = folderPath
                                meetingVideoCustomTreeUri = ""
                                meetingVideoCustomFolderLabel = ""
                                prefs.meetingVideoSaveFolder = folderPath
                                prefs.meetingVideoCustomTreeUri = ""
                                prefs.meetingVideoCustomFolderLabel = ""
                                Toast.makeText(context, "MP4 folder set to $folderPath", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = meetingVideoSaveFolder,
                        onValueChange = { meetingVideoSaveFolder = it },
                        label = { Text("Subfolder (e.g., Download/AmbientBrain)") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            val normalized = PreferencesRepo.normalizePublicVideoFolder(meetingVideoSaveFolder)
                            meetingVideoSaveFolder = normalized
                            meetingVideoCustomTreeUri = ""
                            meetingVideoCustomFolderLabel = ""
                            prefs.meetingVideoSaveFolder = normalized
                            prefs.meetingVideoCustomTreeUri = ""
                            prefs.meetingVideoCustomFolderLabel = ""
                            Toast.makeText(context, "Saved MP4 folder: $normalized", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Apply", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { meetingFolderPickerLauncher.launch(null) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Pick Any Folder...",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (meetingVideoCustomTreeUri.isNotBlank() ||
                        PreferencesRepo.normalizePublicVideoFolder(meetingVideoSaveFolder) != PreferencesRepo.DEFAULT_MEETING_VIDEO_FOLDER
                    ) {
                        OutlinedButton(
                            onClick = {
                                meetingVideoSaveFolder = PreferencesRepo.DEFAULT_MEETING_VIDEO_FOLDER
                                meetingVideoCustomTreeUri = ""
                                meetingVideoCustomFolderLabel = ""
                                prefs.meetingVideoSaveFolder = PreferencesRepo.DEFAULT_MEETING_VIDEO_FOLDER
                                prefs.meetingVideoCustomTreeUri = ""
                                prefs.meetingVideoCustomFolderLabel = ""
                                Toast.makeText(
                                    context,
                                    "Reset MP4 folder to ${PreferencesRepo.DEFAULT_MEETING_VIDEO_FOLDER}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Reset Default", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // 5. Medical & Neurology Glossary (Built-in Compact Index + Auto-Add + Structured Terms + Legacy Notes)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Medical & Neurology Glossary",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    BadgePill(
                        text = "${builtInGlossaryStats.totalEntriesCount} built-in • ${structuredGlossary.size} saved",
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }

                val savedPct = if (builtInGlossaryStats.rawBytes > 0) {
                    (((builtInGlossaryStats.rawBytes - builtInGlossaryStats.compactBytes).coerceAtLeast(0) * 100) / builtInGlossaryStats.rawBytes)
                } else 0
                Text(
                    text = "Prefix-cache-stable Thai/EN neurology reference (${builtInGlossaryStats.phoneticMappingsCount} phonetics, ${builtInGlossaryStats.neurologyTermsCount} clinical terms; ~${savedPct}% more compact than raw JSON).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Add Medical / Neurology Terms",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Learns new drugs, diagnoses, scales, Thai phonetics & local ward names discovered by Gemini during rounds (up to ${MedicalGlossaryManager.MAX_AUTO_STORED_ENTRIES} auto terms; manual terms never auto-prune).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoAddGlossaryTerms,
                        onCheckedChange = {
                            autoAddGlossaryTerms = it
                            prefs.autoAddGlossaryTerms = it
                        },
                        modifier = Modifier.testTag("settings_auto_glossary_switch")
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            editingGlossaryEntry = null
                            showAddEditGlossaryDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("settings_add_glossary_term_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Term", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }

                    FilledTonalButton(
                        enabled = !isScanningGlossary,
                        onClick = {
                            isScanningGlossary = true
                            scope.launch {
                                try {
                                    val result = MedicalGlossaryManager.scanExistingSessionsForGlossaryTerms(
                                        context = context,
                                        prefs = prefs
                                    )
                                    structuredGlossary = prefs.getStructuredGlossaryEntries()
                                    val msg = when {
                                        result.addedCount > 0 || result.updatedCount > 0 ->
                                            "Scanned past rounds: +${result.addedCount} added, ${result.updatedCount} updated."
                                        else ->
                                            "Past rounds scanned — no new medical/neurology terms outside current glossary."
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Glossary scan failed: ${e.message ?: "unknown error"}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } finally {
                                    isScanningGlossary = false
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .height(40.dp)
                            .testTag("settings_scan_glossary_button")
                    ) {
                        if (isScanningGlossary) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(15.dp)
                            )
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isScanningGlossary) "Scanning..." else "Scan Past Rounds",
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                if (structuredGlossary.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    if (structuredGlossary.size > 5) {
                        OutlinedTextField(
                            value = glossarySearchQuery,
                            onValueChange = { glossarySearchQuery = it },
                            placeholder = { Text("Filter ${structuredGlossary.size} saved terms...", style = MaterialTheme.typography.bodySmall) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = if (glossarySearchQuery.isNotBlank()) {
                                {
                                    IconButton(onClick = { glossarySearchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear glossary search", modifier = Modifier.size(16.dp))
                                    }
                                }
                            } else null,
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    val filteredGlossary = remember(structuredGlossary, glossarySearchQuery) {
                        val q = glossarySearchQuery.trim().lowercase()
                        if (q.isBlank()) {
                            structuredGlossary
                        } else {
                            structuredGlossary.filter { entry ->
                                entry.term.lowercase().contains(q) ||
                                    entry.thaiPhonetic.lowercase().contains(q) ||
                                    entry.notes.lowercase().contains(q) ||
                                    GlossaryTermEntry.categoryDisplayLabel(entry.category).lowercase().contains(q)
                            }
                        }
                    }

                    val visibleGlossary = if (showAllGlossaryTerms || glossarySearchQuery.isNotBlank()) {
                        filteredGlossary
                    } else {
                        filteredGlossary.take(8)
                    }

                    if (visibleGlossary.isEmpty()) {
                        Text(
                            text = "No saved glossary terms match '$glossarySearchQuery'.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            visibleGlossary.forEach { entry ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(
                                        1.dp,
                                        if (!entry.isAutoAdded) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = entry.term,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                BadgePill(
                                                    text = if (entry.isAutoAdded) "Auto" else "Manual",
                                                    containerColor = if (entry.isAutoAdded) {
                                                        MaterialTheme.colorScheme.tertiaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.secondaryContainer
                                                    }
                                                )
                                            }
                                            if (entry.thaiPhonetic.isNotBlank()) {
                                                Text(
                                                    text = "TH: ${entry.thaiPhonetic}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            val metaText = buildString {
                                                append(GlossaryTermEntry.categoryDisplayLabel(entry.category))
                                                append(" • Seen ${entry.seenCount}x")
                                                if (entry.notes.isNotBlank()) {
                                                    append(" • ${entry.notes}")
                                                }
                                            }
                                            Text(
                                                text = metaText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        if (entry.isAutoAdded) {
                                            IconButton(
                                                onClick = {
                                                    structuredGlossary = prefs.promoteGlossaryEntryToManual(entry.id)
                                                    Toast.makeText(
                                                        context,
                                                        "Pinned '${entry.term}' as manual term",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "Pin as manual term",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }

                                        IconButton(
                                            onClick = {
                                                editingGlossaryEntry = entry
                                                showAddEditGlossaryDialog = true
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit glossary term ${entry.term}",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                structuredGlossary = prefs.removeGlossaryEntry(entry.id)
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete glossary term ${entry.term}",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (filteredGlossary.size > 8 && glossarySearchQuery.isBlank()) {
                            TextButton(
                                onClick = { showAllGlossaryTerms = !showAllGlossaryTerms },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(
                                    if (showAllGlossaryTerms) "Show Fewer"
                                    else "Show All (${filteredGlossary.size} terms)"
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "Additional Free-Text Glossary Notes (Optional):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = customGlossary,
                    onValueChange = { customGlossary = it },
                    placeholder = { Text("Add ward names, attending names, or custom hospital shorthand...") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 6. Backup & Data Exportation (All Data, Selected Parts, Settings Backup & Restore)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Data Exportation & Settings Backup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    BadgePill(
                        text = "ZIP • JSON • MD • CSV",
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }
                Text(
                    text = "Export all data or cherry-pick specific parts (settings, glossary, speaker roster, voice profiles, ward rounds, transcripts, tasks, proof clips & raw recordings) or restore a saved backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = {
                        commitPendingEdits()
                        exportInitialTab = 0
                        exportInitialPreset = ExportPreset.STANDARD_BACKUP
                        showExportDialog = true
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_open_export_button")
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export All or Selected Parts...", fontWeight = FontWeight.Bold)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            commitPendingEdits()
                            exportInitialTab = 0
                            exportInitialPreset = ExportPreset.SETTINGS_AND_ROSTER
                            showExportDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_export_settings_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Settings Backup", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = {
                            commitPendingEdits()
                            exportInitialTab = 1
                            exportInitialPreset = ExportPreset.STANDARD_BACKUP
                            showExportDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_restore_backup_button")
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Restore Backup", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Button(
            onClick = {
                commitPendingEdits()
                onSettingsSaved()
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Save Settings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private fun formatCompactTokens(tokens: Long): String = when {
    tokens >= 1_000_000L -> String.format(Locale.US, "%.2fM", tokens / 1_000_000.0)
    tokens >= 1_000L -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
    else -> tokens.coerceAtLeast(0L).toString()
}

