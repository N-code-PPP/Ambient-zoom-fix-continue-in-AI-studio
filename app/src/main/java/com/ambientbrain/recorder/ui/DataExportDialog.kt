package com.ambientbrain.recorder.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ambientbrain.recorder.data.AppDataExporter
import com.ambientbrain.recorder.data.AppDatabase
import com.ambientbrain.recorder.data.BackupContainerKind
import com.ambientbrain.recorder.data.BackupInspection
import com.ambientbrain.recorder.data.ExportEstimate
import com.ambientbrain.recorder.data.ExportFormat
import com.ambientbrain.recorder.data.ExportPreset
import com.ambientbrain.recorder.data.ExportSelectionConfig
import com.ambientbrain.recorder.data.ExportTimeFilter
import com.ambientbrain.recorder.data.PreferencesRepo
import com.ambientbrain.recorder.data.RestoreSelectionOptions
import com.ambientbrain.recorder.data.SessionMode
import com.ambientbrain.recorder.data.SessionWithDetails
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataExportDialog(
    onDismissRequest: () -> Unit,
    initialTab: Int = 0,
    initialPreset: ExportPreset = ExportPreset.STANDARD_BACKUP,
    initialSelectedSessionIds: Set<String>? = null,
    onBackupRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    var config by remember {
        mutableStateOf(
            ExportSelectionConfig.fromPreset(initialPreset).let { base ->
                if (initialSelectedSessionIds != null) {
                    base.copy(preset = ExportPreset.CUSTOM, selectedSessionIds = initialSelectedSessionIds)
                } else {
                    base
                }
            }
        )
    }

    var allSessions by remember { mutableStateOf<List<SessionWithDetails>>(emptyList()) }
    var estimate by remember { mutableStateOf(ExportEstimate()) }
    var isEstimating by remember { mutableStateOf(true) }
    var showSessionPickerDialog by remember { mutableStateOf(false) }

    var isBusy by remember { mutableStateOf(false) }
    var progressMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    // Restore tab state
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var backupInspection by remember { mutableStateOf<BackupInspection?>(null) }
    var restoreOptions by remember { mutableStateOf(RestoreSelectionOptions()) }

    val latestConfig by rememberUpdatedState(config)

    LaunchedEffect(Unit) {
        allSessions = AppDatabase.get(context).dao().getAllSessionsWithDetailsOnce()
    }

    LaunchedEffect(config) {
        isEstimating = true
        estimate = runCatching {
            AppDataExporter.estimateExportSummary(context, config)
        }.getOrDefault(ExportEstimate())
        isEstimating = false
    }

    fun startSaveToUri(targetUri: Uri?) {
        if (targetUri == null) return
        isBusy = true
        statusMessage = null
        statusIsError = false
        progressMessage = "Preparing export..."
        scope.launch {
            runCatching {
                AppDataExporter.exportToUri(
                    context = context,
                    targetUri = targetUri,
                    config = latestConfig,
                    onProgress = { msg -> progressMessage = msg }
                )
            }.onSuccess { result ->
                statusIsError = false
                statusMessage = "✅ ${result.summary}"
                Toast.makeText(context, "Saved: ${result.fileName}", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                statusIsError = true
                statusMessage = "❌ Export failed: ${err.message ?: err::class.java.simpleName}"
            }
            progressMessage = null
            isBusy = false
        }
    }

    val createZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ExportFormat.ZIP_BUNDLE.createDocMimeType)
    ) { uri -> startSaveToUri(uri) }

    val createJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ExportFormat.SETTINGS_JSON.createDocMimeType)
    ) { uri -> startSaveToUri(uri) }

    val createMarkdownLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ExportFormat.MARKDOWN_REPORT.createDocMimeType)
    ) { uri -> startSaveToUri(uri) }

    val createCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ExportFormat.CSV_ITEMS.createDocMimeType)
    ) { uri -> startSaveToUri(uri) }

    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri ->
        if (pickedUri == null) return@rememberLauncherForActivityResult
        restoreUri = pickedUri
        backupInspection = null
        statusMessage = null
        statusIsError = false
        isBusy = true
        progressMessage = "Inspecting backup file..."
        scope.launch {
            runCatching {
                AppDataExporter.inspectBackupUri(context, pickedUri)
            }.onSuccess { info ->
                backupInspection = info
                restoreOptions = RestoreSelectionOptions(
                    restoreSettings = info.hasSettings,
                    restoreApiKeys = false, // Always explicit opt-in
                    restoreVoiceProfiles = info.voiceProfileFilesCount > 0,
                    restoreDatabaseRecords = info.hasDatabaseRecords,
                    restoreProofClips = info.proofClipFilesCount > 0,
                    restoreRawSessionAudio = false // Safe default; user can opt in
                )
            }.onFailure { err ->
                restoreUri = null
                statusIsError = true
                statusMessage = "❌ Cannot read backup: ${err.message ?: err::class.java.simpleName}"
            }
            progressMessage = null
            isBusy = false
        }
    }

    if (showSessionPickerDialog) {
        SessionExportPickerDialog(
            allSessions = allSessions,
            initiallySelectedIds = config.selectedSessionIds,
            onDismiss = { showSessionPickerDialog = false },
            onConfirm = { pickedIds ->
                config = config.copy(
                    preset = ExportPreset.CUSTOM,
                    selectedSessionIds = pickedIds
                )
                showSessionPickerDialog = false
            }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 16.dp),
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Data Export & Settings Backup",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "All data, selected parts, settings & restore",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        enabled = !isBusy,
                        onClick = onDismissRequest
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                TabRow(selectedTabIndex = activeTab) {
                    Tab(
                        selected = activeTab == 0,
                        enabled = !isBusy,
                        onClick = {
                            activeTab = 0
                            statusMessage = null
                        },
                        text = { Text("Export & Backup", maxLines = 1, fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        enabled = !isBusy,
                        onClick = {
                            activeTab = 1
                            statusMessage = null
                        },
                        text = { Text("Restore / Import", maxLines = 1, fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (activeTab == 0) {
                    ExportTabContent(
                        config = config,
                        estimate = estimate,
                        isEstimating = isEstimating,
                        totalSessionCount = allSessions.size,
                        onConfigChange = { nextConfig -> config = nextConfig },
                        onOpenSessionPicker = { showSessionPickerDialog = true }
                    )
                } else {
                    RestoreTabContent(
                        inspection = backupInspection,
                        options = restoreOptions,
                        isBusy = isBusy,
                        onOptionsChange = { restoreOptions = it },
                        onPickFile = {
                            openBackupLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/json",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        }
                    )
                }

                if (progressMessage != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = progressMessage!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (statusMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (statusIsError) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (statusIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (activeTab == 0) {
                val canExport = !isBusy && !isEstimating && estimate.hasExportableContent
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (config.format != ExportFormat.ZIP_BUNDLE) {
                        OutlinedButton(
                            enabled = canExport,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            onClick = {
                                isBusy = true
                                progressMessage = "Formatting for clipboard..."
                                scope.launch {
                                    runCatching {
                                        AppDataExporter.renderTextExportPreview(context, latestConfig)
                                    }.onSuccess { text ->
                                        if (text.length > 420_000) {
                                            statusIsError = true
                                            statusMessage = "⚠️ Output is too large for Android clipboard (${text.length / 1024} KB). Use Save or Share instead."
                                        } else {
                                            CopyFormatter.copyToClipboard(
                                                context = context,
                                                label = "Ambient Brain Export",
                                                text = text,
                                                toastMsg = "${config.format.shortLabel} copied to clipboard!"
                                            )
                                            statusIsError = false
                                            statusMessage = "✅ Copied ${config.format.shortLabel} (${AppDataExporter.formatBytesHuman(text.toByteArray().size.toLong())}) to clipboard."
                                        }
                                    }.onFailure { err ->
                                        statusIsError = true
                                        statusMessage = "❌ Copy failed: ${err.message}"
                                    }
                                    progressMessage = null
                                    isBusy = false
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }

                    OutlinedButton(
                        enabled = canExport,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            isBusy = true
                            statusMessage = null
                            statusIsError = false
                            progressMessage = "Preparing shareable file..."
                            scope.launch {
                                runCatching {
                                    AppDataExporter.exportToCacheForShare(
                                        context = context,
                                        config = latestConfig,
                                        onProgress = { msg -> progressMessage = msg }
                                    )
                                }.onSuccess { res ->
                                    statusIsError = false
                                    statusMessage = "✅ Ready: ${res.summary}"
                                    AppDataExporter.launchShareIntent(context, res)
                                }.onFailure { err ->
                                    statusIsError = true
                                    statusMessage = "❌ Share failed: ${err.message ?: err::class.java.simpleName}"
                                }
                                progressMessage = null
                                isBusy = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }

                    Button(
                        enabled = canExport,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("export_save_button"),
                        onClick = {
                            val suggestedName = AppDataExporter.buildSuggestedFileName(latestConfig)
                            when (latestConfig.format) {
                                ExportFormat.ZIP_BUNDLE -> createZipLauncher.launch(suggestedName)
                                ExportFormat.SETTINGS_JSON -> createJsonLauncher.launch(suggestedName)
                                ExportFormat.MARKDOWN_REPORT -> createMarkdownLauncher.launch(suggestedName)
                                ExportFormat.CSV_ITEMS -> createCsvLauncher.launch(suggestedName)
                            }
                        }
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                        }
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Save File", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            } else {
                val info = backupInspection
                val uri = restoreUri
                val anyRestoreChecked = restoreOptions.restoreSettings ||
                    restoreOptions.restoreVoiceProfiles ||
                    restoreOptions.restoreDatabaseRecords ||
                    restoreOptions.restoreProofClips ||
                    restoreOptions.restoreRawSessionAudio

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        enabled = !isBusy,
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(0.7f)
                    ) {
                        Text("Close", maxLines = 1)
                    }
                    Button(
                        enabled = !isBusy && info != null && uri != null && anyRestoreChecked,
                        modifier = Modifier.weight(1.5f),
                        onClick = {
                            val targetUri = uri ?: return@Button
                            isBusy = true
                            statusMessage = null
                            statusIsError = false
                            progressMessage = "Restoring selected backup..."
                            scope.launch {
                                runCatching {
                                    AppDataExporter.restoreFromUri(
                                        context = context,
                                        uri = targetUri,
                                        options = restoreOptions,
                                        onProgress = { msg -> progressMessage = msg }
                                    )
                                }.onSuccess { res ->
                                    statusIsError = false
                                    statusMessage = "✅ ${res.buildSummary()}"
                                    onBackupRestored()
                                    Toast.makeText(context, "Backup restored cleanly!", Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    statusIsError = true
                                    statusMessage = "❌ Restore failed: ${err.message ?: err::class.java.simpleName}"
                                }
                                progressMessage = null
                                isBusy = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apply Restore", fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportTabContent(
    config: ExportSelectionConfig,
    estimate: ExportEstimate,
    isEstimating: Boolean,
    totalSessionCount: Int,
    onConfigChange: (ExportSelectionConfig) -> Unit,
    onOpenSessionPicker: () -> Unit
) {
    var showDataPartsDetails by remember { mutableStateOf(true) }

    // 1. Quick Preset Selector
    Text(
        text = "1. QUICK PRESET (ALL OR SELECTED PARTS)",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            ExportPreset.STANDARD_BACKUP,
            ExportPreset.FULL_BACKUP,
            ExportPreset.SETTINGS_AND_ROSTER,
            ExportPreset.CLINICAL_NOTES_ONLY
        ).forEach { preset ->
            FilterChip(
                selected = config.preset == preset,
                onClick = {
                    val base = ExportSelectionConfig.fromPreset(preset)
                    onConfigChange(
                        base.copy(
                            timeFilter = config.timeFilter,
                            roundsOnlyFilter = config.roundsOnlyFilter,
                            selectedSessionIds = config.selectedSessionIds
                        )
                    )
                },
                label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
        if (config.preset == ExportPreset.CUSTOM) {
            FilterChip(
                selected = true,
                onClick = {},
                label = { Text("Custom", style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    // 2. Output Format / Method
    Text(
        text = "2. EXPORT FORMAT / METHOD",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ExportFormat.entries.forEach { fmt ->
            FilterChip(
                selected = config.format == fmt,
                onClick = {
                    val adjusted = when (fmt) {
                        ExportFormat.SETTINGS_JSON -> config.copy(
                            format = fmt,
                            includeSettings = true
                        )
                        ExportFormat.CSV_ITEMS -> config.copy(
                            format = fmt,
                            includeItems = true
                        )
                        else -> config.copy(format = fmt)
                    }
                    onConfigChange(adjusted)
                },
                label = { Text(fmt.shortLabel, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
    Text(
        text = config.format.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (config.format == ExportFormat.SETTINGS_JSON && config.hasAnyAudioPartSelected()) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFF59E0B).copy(alpha = 0.14f),
            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Voice profiles & audio clips require ZIP Archive format.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { onConfigChange(config.copy(format = ExportFormat.ZIP_BUNDLE)) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Use ZIP", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // 3. Scope / Session Filter (shown for non-settings formats)
    if (config.format != ExportFormat.SETTINGS_JSON) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = "3. SCOPE & SESSION FILTER",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExportTimeFilter.entries.forEach { tf ->
                FilterChip(
                    selected = config.timeFilter == tf,
                    onClick = {
                        onConfigChange(config.copy(preset = ExportPreset.CUSTOM, timeFilter = tf))
                    },
                    label = { Text(tf.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
            FilterChip(
                selected = config.roundsOnlyFilter,
                onClick = {
                    onConfigChange(config.copy(preset = ExportPreset.CUSTOM, roundsOnlyFilter = !config.roundsOnlyFilter))
                },
                label = { Text("Rounds Only", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = config.selectedSessionIds != null,
                onClick = onOpenSessionPicker,
                label = {
                    val label = config.selectedSessionIds?.let { "${it.size} Picked" } ?: "Pick Sessions ($totalSessionCount)"
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            )
        }
        if (config.selectedSessionIds != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Filtering to ${config.selectedSessionIds.size} selected session(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(
                    onClick = { onConfigChange(config.copy(selectedSessionIds = null)) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("Clear filter", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    // 4. Selectable Parts Matrix
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDataPartsDetails = !showDataPartsDetails }
    ) {
        Text(
            text = "4. SELECT PARTS TO INCLUDE",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = if (showDataPartsDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    AnimatedVisibility(visible = showDataPartsDetails) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (config.format == ExportFormat.ZIP_BUNDLE || config.format == ExportFormat.SETTINGS_JSON) {
                PartCheckboxRow(
                    title = "Settings Backup, Glossary & Speaker Roster",
                    subtitle = "Mic policy, Gemini model, thresholds, ${estimate.rosterCount} speaker(s) & custom glossary",
                    checked = config.includeSettings,
                    enabled = config.format != ExportFormat.SETTINGS_JSON,
                    onCheckedChange = { checked ->
                        onConfigChange(
                            config.copy(
                                preset = ExportPreset.CUSTOM,
                                includeSettings = checked,
                                includeApiKeys = if (!checked) false else config.includeApiKeys
                            )
                        )
                    }
                )
                if (config.includeSettings) {
                    PartCheckboxRow(
                        title = "Include Gemini API Keys & Pool (Sensitive)",
                        subtitle = if (estimate.apiKeyCount > 0) {
                            "${estimate.apiKeyCount} saved key(s) stored plaintext inside backup file"
                        } else {
                            "No Gemini API keys saved yet — add keys in Settings"
                        },
                        checked = config.includeApiKeys,
                        enabled = true,
                        indent = true,
                        badgeColor = Color(0xFFF59E0B).copy(alpha = 0.25f),
                        badgeText = "OPT-IN",
                        onCheckedChange = { checked ->
                            onConfigChange(config.copy(preset = ExportPreset.CUSTOM, includeApiKeys = checked))
                        }
                    )
                }
            }

            if (config.format == ExportFormat.ZIP_BUNDLE) {
                PartCheckboxRow(
                    title = "Enrolled Voice Profiles (.wav / .aac)",
                    subtitle = "My Voice & speaker roster reference audio clips (up to ${PreferencesRepo.MAX_VOICE_SAMPLES_PER_PROFILE} best per profile)",
                    checked = config.includeVoiceProfiles,
                    badgeText = if (estimate.voiceProfileCount > 0) {
                        "${estimate.voiceProfileCount} • ${AppDataExporter.formatBytesHuman(estimate.voiceProfileBytes)}"
                    } else "0 files",
                    onCheckedChange = { checked ->
                        onConfigChange(config.copy(preset = ExportPreset.CUSTOM, includeVoiceProfiles = checked))
                    }
                )
            }

            if (config.format != ExportFormat.SETTINGS_JSON && config.format != ExportFormat.CSV_ITEMS) {
                PartCheckboxRow(
                    title = "Sessions, Summaries & AI Reviews",
                    subtitle = "Timeline sessions, durations & key point summaries",
                    checked = config.includeSessionsAndSummaries,
                    badgeText = "${estimate.sessionCount}",
                    onCheckedChange = { checked ->
                        onConfigChange(config.copy(preset = ExportPreset.CUSTOM, includeSessionsAndSummaries = checked))
                    }
                )
                PartCheckboxRow(
                    title = "Bilingual Transcripts & Speaker Maps",
                    subtitle = "Thai/English timestamped dialogue & diarization labels",
                    checked = config.includeTranscripts,
                    badgeText = "${estimate.sentenceCount} lines",
                    onCheckedChange = { checked ->
                        onConfigChange(config.copy(preset = ExportPreset.CUSTOM, includeTranscripts = checked))
                    }
                )
                PartCheckboxRow(
                    title = "Clinical Ward Rounds & Patient Sheets",
                    subtitle = "Patient one-liners, neuro exams, plans & teaching pearls",
                    checked = config.includeClinicalRounds,
                    badgeText = "${estimate.patientCount} patients",
                    onCheckedChange = { checked ->
                        onConfigChange(config.copy(preset = ExportPreset.CUSTOM, includeClinicalRounds = checked))
                    }
                )
            }

            if (config.format != ExportFormat.SETTINGS_JSON) {
                PartCheckboxRow(
                    title = "Tasks, Notes, Decisions & Events",
                    subtitle = "Extracted checklist items, assignees, quotes & due dates",
                    checked = config.includeItems,
                    enabled = config.format != ExportFormat.CSV_ITEMS,
                    badgeText = "${estimate.itemCount}",
                    onCheckedChange = { checked ->
                        onConfigChange(config.copy(preset = ExportPreset.CUSTOM, includeItems = checked))
                    }
                )
                if (config.includeItems) {
                    PartCheckboxRow(
                        title = "Include Archived / Completed Items",
                        subtitle = "Also export older archived tasks & notes from Room",
                        checked = config.includeArchivedItems,
                        indent = true,
                        onCheckedChange = { checked ->
                            onConfigChange(config.copy(preset = ExportPreset.CUSTOM, includeArchivedItems = checked))
                        }
                    )
                }
            }

            if (config.format == ExportFormat.ZIP_BUNDLE) {
                PartCheckboxRow(
                    title = "Permanent Voice Proof Clips (.m4a)",
                    subtitle = "Sliced evidentiary audio snippets linked to tasks & notes",
                    checked = config.includeProofClips,
                    badgeText = "${estimate.proofClipCount} • ${AppDataExporter.formatBytesHuman(estimate.proofClipBytes)}",
                    onCheckedChange = { checked ->
                        onConfigChange(config.copy(preset = ExportPreset.CUSTOM, includeProofClips = checked))
                    }
                )
                PartCheckboxRow(
                    title = "Full Raw Session Audio (.aac)",
                    subtitle = "Large unpurged full-length recordings from sessions/",
                    checked = config.includeRawSessionAudio,
                    badgeColor = if (config.includeRawSessionAudio && estimate.rawAudioBytes > 50L * 1024L * 1024L) {
                        Color(0xFFF59E0B).copy(alpha = 0.25f)
                    } else null,
                    badgeText = "${estimate.rawAudioCount} • ${AppDataExporter.formatBytesHuman(estimate.rawAudioBytes)}",
                    onCheckedChange = { checked ->
                        onConfigChange(config.copy(preset = ExportPreset.CUSTOM, includeRawSessionAudio = checked))
                    }
                )
                PartCheckboxRow(
                    title = "Gemini Analysis & Token Usage Logs",
                    subtitle = "Model diagnostics, token counts & cache hit history",
                    checked = config.includeAnalysisRuns,
                    badgeText = "${estimate.analysisRunCount}",
                    onCheckedChange = { checked ->
                        onConfigChange(config.copy(preset = ExportPreset.CUSTOM, includeAnalysisRuns = checked))
                    }
                )
            }
        }
    }

    // 5. Live Estimate Summary Box
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isEstimating) "Calculating export size..." else "Ready to Export (${config.format.shortLabel})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                BadgePill(
                    text = "~${AppDataExporter.formatBytesHuman(estimate.totalEstimatedBytes)}",
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }
            val summaryParts = buildList {
                if (estimate.hasSettingsSelected) add("Settings")
                if (estimate.voiceProfileCount > 0) add("${estimate.voiceProfileCount} profile(s)")
                if (estimate.sessionCount > 0) add("${estimate.sessionCount} session(s)")
                if (estimate.patientCount > 0) add("${estimate.patientCount} patient(s)")
                if (estimate.itemCount > 0) add("${estimate.itemCount} item(s)")
                if (estimate.sentenceCount > 0) add("${estimate.sentenceCount} transcript lines")
                if (estimate.proofClipCount > 0) add("${estimate.proofClipCount} proof clip(s)")
                if (estimate.rawAudioCount > 0) add("${estimate.rawAudioCount} raw .aac")
            }
            Text(
                text = if (summaryParts.isEmpty()) {
                    "No matching items or sections selected."
                } else {
                    summaryParts.joinToString(" • ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (summaryParts.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RestoreTabContent(
    inspection: BackupInspection?,
    options: RestoreSelectionOptions,
    isBusy: Boolean,
    onOptionsChange: (RestoreSelectionOptions) -> Unit,
    onPickFile: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Restore Settings or Full/Partial Backup",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Select a previously exported Ambient Brain .zip backup archive or .json settings backup. Existing records are merged safely without deleting local data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                enabled = !isBusy,
                onClick = onPickFile,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (inspection == null) "Choose Backup File (.zip or .json)" else "Choose Another File",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (inspection != null) {
        val dateText = remember(inspection.exportedAtMs) {
            if (inspection.exportedAtMs > 0L) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(inspection.exportedAtMs))
            } else {
                "Unknown date"
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = inspection.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BadgePill(
                        text = when (inspection.containerKind) {
                            BackupContainerKind.ZIP_ARCHIVE -> "ZIP"
                            BackupContainerKind.SETTINGS_JSON -> "SETTINGS JSON"
                            BackupContainerKind.DATABASE_JSON -> "DB JSON"
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }

                Text(
                    text = buildString {
                        append("Exported: ").append(dateText)
                        if (inspection.appVersionName.isNotBlank()) append(" • v").append(inspection.appVersionName)
                        if (inspection.fileSizeBytes > 0L) append(" • ").append(AppDataExporter.formatBytesHuman(inspection.fileSizeBytes))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "Select what to restore from this backup:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                if (inspection.hasSettings) {
                    PartCheckboxRow(
                        title = "App Settings, Glossary & Speaker Roster",
                        subtitle = "${inspection.rosterCount} speaker roster entry(ies) + preferences",
                        checked = options.restoreSettings,
                        onCheckedChange = { checked ->
                            onOptionsChange(
                                options.copy(
                                    restoreSettings = checked,
                                    restoreApiKeys = if (!checked) false else options.restoreApiKeys
                                )
                            )
                        }
                    )
                    if (options.restoreSettings && inspection.apiKeysCount > 0) {
                        PartCheckboxRow(
                            title = "Import Gemini API Keys (${inspection.apiKeysCount} in backup)",
                            subtitle = "Merges backed-up API keys into local key pool",
                            checked = options.restoreApiKeys,
                            indent = true,
                            badgeText = "OPT-IN",
                            badgeColor = Color(0xFFF59E0B).copy(alpha = 0.25f),
                            onCheckedChange = { checked ->
                                onOptionsChange(options.copy(restoreApiKeys = checked))
                            }
                        )
                    }
                }

                if (inspection.voiceProfileFilesCount > 0) {
                    PartCheckboxRow(
                        title = "Voice Profiles (${inspection.voiceProfileFilesCount} .aac file)",
                        subtitle = "Restores enrolled voice reference clips",
                        checked = options.restoreVoiceProfiles,
                        onCheckedChange = { checked ->
                            onOptionsChange(options.copy(restoreVoiceProfiles = checked))
                        }
                    )
                }

                if (inspection.hasDatabaseRecords) {
                    PartCheckboxRow(
                        title = "Sessions, Rounds, Transcripts & Tasks",
                        subtitle = "${inspection.sessionsCount} session(s) • ${inspection.patientsCount} patient(s) • ${inspection.itemsCount} item(s) • ${inspection.sentencesCount} lines",
                        checked = options.restoreDatabaseRecords,
                        onCheckedChange = { checked ->
                            onOptionsChange(options.copy(restoreDatabaseRecords = checked))
                        }
                    )
                }

                if (inspection.proofClipFilesCount > 0) {
                    PartCheckboxRow(
                        title = "Permanent Proof Voice Clips (${inspection.proofClipFilesCount} .m4a)",
                        subtitle = "Evidentiary clips linked to tasks & notes",
                        checked = options.restoreProofClips,
                        onCheckedChange = { checked ->
                            onOptionsChange(options.copy(restoreProofClips = checked))
                        }
                    )
                }

                if (inspection.rawAudioFilesCount > 0) {
                    PartCheckboxRow(
                        title = "Raw Full-Session Recordings (${inspection.rawAudioFilesCount} .aac)",
                        subtitle = "Restores original session audio files to sessions/",
                        checked = options.restoreRawSessionAudio,
                        onCheckedChange = { checked ->
                            onOptionsChange(options.copy(restoreRawSessionAudio = checked))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PartCheckboxRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    indent: Boolean = false,
    badgeText: String? = null,
    badgeColor: Color? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 18.dp else 0.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp)
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(34.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!badgeText.isNullOrBlank()) {
                    BadgePill(
                        text = badgeText,
                        containerColor = badgeColor ?: MaterialTheme.colorScheme.secondaryContainer
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SessionExportPickerDialog(
    allSessions: List<SessionWithDetails>,
    initiallySelectedIds: Set<String>?,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>?) -> Unit
) {
    var selected by remember {
        mutableStateOf(initiallySelectedIds ?: allSessions.map { it.session.id }.toSet())
    }
    val dateFmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Select Sessions to Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "${selected.size} of ${allSessions.size} session(s) selected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { selected = allSessions.map { it.session.id }.toSet() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Select All", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { selected = emptySet() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (allSessions.isEmpty()) {
                    Text(
                        text = "No recorded sessions yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        items(allSessions, key = { it.session.id }) { details ->
                            val s = details.session
                            val isChecked = s.id in selected
                            val isRound = s.mode != SessionMode.EVERYDAY ||
                                s.context in setOf("NEURO_ROUND", "MEDICAL_ROUND", "CASE_CONFERENCE", "HANDOVER")
                            val rawSize = s.rawPath?.let { p -> File(p).takeIf { it.exists() }?.length() } ?: 0L
                            Surface(
                                onClick = {
                                    selected = if (isChecked) selected - s.id else selected + s.id
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isChecked) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                } else {
                                    AlertDialogDefaults.containerColor
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { c ->
                                            selected = if (c) selected + s.id else selected - s.id
                                        },
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                                        Text(
                                            text = s.title ?: if (isRound) "Medical Round" else "Session",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val meta = buildList {
                                            add(dateFmt.format(Date(s.startMs)))
                                            if (isRound) add("Round")
                                            if (details.items.isNotEmpty()) add("${details.items.size} items")
                                            if (details.sentences.isNotEmpty()) add("${details.sentences.size} lines")
                                            if (rawSize > 0L) add(AppDataExporter.formatBytesHuman(rawSize))
                                        }.joinToString(" • ")
                                        Text(
                                            text = meta,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val resultSet = if (selected.size == allSessions.size && allSessions.isNotEmpty()) null else selected
                    onConfirm(resultSet)
                }
            ) {
                Text("Apply (${selected.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
