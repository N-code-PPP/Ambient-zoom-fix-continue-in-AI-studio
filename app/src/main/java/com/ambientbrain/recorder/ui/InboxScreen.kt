package com.ambientbrain.recorder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import java.util.UUID
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ambientbrain.recorder.data.ItemEntity
import com.ambientbrain.recorder.data.ItemType
import com.ambientbrain.recorder.data.SessionWithDetails
import com.ambientbrain.recorder.data.TranscriptLangMode

private enum class InboxFilter(val label: String) {
    ALL("All"),
    TASKS_ONLY("Tasks (To-Do)"),
    MY_TASKS("My Tasks"),
    URGENT("Urgent"),
    VERIFY("⚠️ Needs Verify"),
    NOTES("Notes & Decisions")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun InboxScreen(
    items: List<ItemEntity>,
    transcriptLangMode: TranscriptLangMode,
    playbackController: AudioPlaybackController,
    allSessions: List<SessionWithDetails> = emptyList(),
    onReanalyzeSession: (String) -> Unit = {},
    onToggleDone: (ItemEntity) -> Unit,
    onArchiveItem: (ItemEntity) -> Unit,
    onUnarchiveItem: (ItemEntity) -> Unit = {},
    onArchiveCompleted: () -> Unit = {},
    onUpdateItem: (ItemEntity) -> Unit = {},
    onAddNewItem: (ItemEntity) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchQuery by remember { mutableStateOf("") }
    var hideCompleted by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(InboxFilter.ALL) }
    var isSelectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showCopySheet by remember { mutableStateOf(false) }
    var showAddNoteDialog by remember { mutableStateOf(false) }
    var itemBeingEdited by remember { mutableStateOf<ItemEntity?>(null) }
    var viewingTranscriptSessionId by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = viewingTranscriptSessionId != null) {
        viewingTranscriptSessionId = null
    }
    BackHandler(enabled = showCopySheet && viewingTranscriptSessionId == null) {
        showCopySheet = false
    }
    BackHandler(enabled = showAddNoteDialog && viewingTranscriptSessionId == null && !showCopySheet) {
        showAddNoteDialog = false
    }
    BackHandler(enabled = itemBeingEdited != null && viewingTranscriptSessionId == null && !showCopySheet && !showAddNoteDialog) {
        itemBeingEdited = null
    }
    BackHandler(enabled = (isSelectMode || selectedIds.isNotEmpty()) && viewingTranscriptSessionId == null && !showCopySheet && !showAddNoteDialog && itemBeingEdited == null) {
        isSelectMode = false
        selectedIds.clear()
    }

    val filteredItems = remember(items, selectedFilter, searchQuery, hideCompleted) {
        val priorityWeight: (String) -> Int = { p ->
            when (p.uppercase()) {
                "URGENT" -> 3
                "HIGH" -> 2
                else -> 1
            }
        }
        val q = searchQuery.trim().lowercase()
        items.filter { item ->
            if (hideCompleted && item.isDone) return@filter false
            val matchesFilter = when (selectedFilter) {
                InboxFilter.ALL -> true
                InboxFilter.TASKS_ONLY -> item.type == ItemType.TASK
                InboxFilter.MY_TASKS -> item.isForUser || item.assignee?.contains("me", ignoreCase = true) == true
                InboxFilter.URGENT -> item.priority == "URGENT" || item.priority == "HIGH"
                InboxFilter.VERIFY -> item.needsVerification
                InboxFilter.NOTES -> item.type != ItemType.TASK
            }
            if (!matchesFilter) return@filter false
            if (q.isEmpty()) true
            else {
                item.title.lowercase().contains(q) ||
                    item.details.lowercase().contains(q) ||
                    (item.patientIdentifier?.lowercase()?.contains(q) == true) ||
                    item.speakerLabel.lowercase().contains(q) ||
                    (item.assignee?.lowercase()?.contains(q) == true) ||
                    item.quoteOriginal.lowercase().contains(q) ||
                    (item.quoteEn?.lowercase()?.contains(q) == true)
            }
        }.sortedWith(
            compareBy<ItemEntity> { it.isDone }
                .thenByDescending { priorityWeight(it.priority) }
                .thenByDescending { it.needsVerification }
                .thenByDescending { it.createdMs }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (filteredItems.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { showCopySheet = true },
                        icon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        text = {
                            val label = if (selectedIds.isNotEmpty()) {
                                "Copy / Share (${selectedIds.size})"
                            } else {
                                "Copy / Share"
                            }
                            Text(label)
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("inbox_copy_fab")
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = { showAddNoteDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("+ Note / Task") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("inbox_add_fab")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val doneCount = remember(items) { items.count { it.isDone } }
            val filterCounts = remember(items, hideCompleted) {
                val base = if (hideCompleted) items.filter { !it.isDone } else items
                mapOf(
                    InboxFilter.ALL to base.size,
                    InboxFilter.TASKS_ONLY to base.count { it.type == ItemType.TASK },
                    InboxFilter.MY_TASKS to base.count { it.isForUser || it.assignee?.contains("me", ignoreCase = true) == true },
                    InboxFilter.URGENT to base.count { it.priority == "URGENT" || it.priority == "HIGH" },
                    InboxFilter.VERIFY to base.count { it.needsVerification },
                    InboxFilter.NOTES to base.count { it.type != ItemType.TASK }
                )
            }

            // Search & Quick Action Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    placeholder = { Text("Search tasks, patient, or quote...", maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("inbox_search_input")
                )

                OutlinedButton(
                    onClick = {
                        isSelectMode = !isSelectMode
                        if (!isSelectMode) selectedIds.clear()
                    },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("inbox_select_toggle")
                ) {
                    Icon(
                        imageVector = if (isSelectMode || selectedIds.isNotEmpty()) Icons.Default.Close else Icons.Default.Checklist,
                        contentDescription = if (isSelectMode) "Cancel selection" else "Select items",
                        tint = if (isSelectMode || selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (doneCount > 0) {
                    OutlinedButton(
                        onClick = onArchiveCompleted,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("inbox_clear_done_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = "Archive all completed tasks",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Filter Chips Row with dynamic item counts
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InboxFilter.entries.forEach { filter ->
                    val count = filterCounts[filter] ?: 0
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text("${filter.label} ($count)") }
                    )
                }
                if (doneCount > 0 || hideCompleted) {
                    FilterChip(
                        selected = hideCompleted,
                        onClick = { hideCompleted = !hideCompleted },
                        label = { Text(if (hideCompleted) "Done Hidden ($doneCount)" else "Hide Done ($doneCount)") }
                    )
                }
            }

            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "No items matching '$searchQuery'" else "No tasks or notes in this view yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Speak naturally in Thai + English medical terms during rounds or everyday conversations. Tasks and permanent voice-proof clips appear here automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showAddNoteDialog = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Note or Task Manually")
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        val isSelected = selectedIds.contains(item.id)
                        TaskOrNoteCard(
                            item = item,
                            isSelected = isSelected,
                            isSelectMode = isSelectMode || selectedIds.isNotEmpty(),
                            transcriptLangMode = transcriptLangMode,
                            playbackController = playbackController,
                            onOpenSessionTranscript = {
                                viewingTranscriptSessionId = item.sessionId
                            },
                            onToggleDone = { onToggleDone(item) },
                            onMarkVerified = {
                                onUpdateItem(item.copy(needsVerification = false))
                            },
                            onEdit = {
                                itemBeingEdited = item
                            },
                            onArchive = {
                                onArchiveItem(item)
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    val res = snackbarHostState.showSnackbar(
                                        message = "Archived \"${item.title.take(28)}\"",
                                        actionLabel = "Undo",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (res == SnackbarResult.ActionPerformed) {
                                        onUnarchiveItem(item)
                                    }
                                }
                            },
                            onCopySingle = {
                                CopyFormatter.copyToClipboard(
                                    context = context,
                                    label = item.title,
                                    text = CopyFormatter.formatSingleItem(item),
                                    toastMsg = "Task copied"
                                )
                            },
                            onToggleSelect = {
                                if (isSelected) selectedIds.remove(item.id) else selectedIds.add(item.id)
                                if (selectedIds.isNotEmpty()) isSelectMode = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCopySheet) {
        val targetItems = if (selectedIds.isNotEmpty()) {
            filteredItems.filter { selectedIds.contains(it.id) }
        } else {
            filteredItems.filter { !it.isDone }
        }

        ModalBottomSheet(onDismissRequest = { showCopySheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Copy or Share ${targetItems.size} Item(s) As...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TaskCopyTemplate.entries.forEach { template ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                val formatted = CopyFormatter.formatItemsList(targetItems, template)
                                CopyFormatter.copyToClipboard(
                                    context = context,
                                    label = template.label,
                                    text = formatted,
                                    toastMsg = "Copied (${template.label})"
                                )
                                selectedIds.clear()
                                isSelectMode = false
                                showCopySheet = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(template.label, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                val formatted = CopyFormatter.formatItemsList(targetItems, template)
                                CopyFormatter.shareText(
                                    context = context,
                                    subject = "Clinical Tasks & Notes",
                                    text = formatted
                                )
                                selectedIds.clear()
                                isSelectMode = false
                                showCopySheet = false
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share ${template.label}", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showAddNoteDialog) {
        CreateNoteDialog(
            onDismissRequest = { showAddNoteDialog = false },
            onSave = { title, details, type, priority ->
                val manualSessionId = "manual_" + UUID.randomUUID().toString()
                val item = ItemEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = manualSessionId,
                    patientIdentifier = null,
                    type = type,
                    title = title,
                    details = details,
                    speakerId = "USER",
                    speakerLabel = "Manual Entry",
                    assignee = "Me",
                    isForUser = true,
                    due = null,
                    priority = priority,
                    quoteOriginal = title,
                    quoteEn = null,
                    needsVerification = false,
                    clipStartMs = 0L,
                    clipEndMs = 0L,
                    clipPath = null
                )
                onAddNewItem(item)
                showAddNoteDialog = false
            }
        )
    }

    itemBeingEdited?.let { editing ->
        var editTitle by remember(editing.id) { mutableStateOf(editing.title) }
        var editDetails by remember(editing.id) { mutableStateOf(editing.details) }
        var editAssignee by remember(editing.id) { mutableStateOf(editing.assignee.orEmpty()) }
        var editDue by remember(editing.id) { mutableStateOf(editing.due.orEmpty()) }
        var editNeedsVerification by remember(editing.id) { mutableStateOf(editing.needsVerification) }
        AlertDialog(
            onDismissRequest = { itemBeingEdited = null },
            title = { Text("Edit & Verify Item", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editDetails,
                        onValueChange = { editDetails = it },
                        label = { Text("Details / Clinical Dose") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editAssignee,
                        onValueChange = { editAssignee = it },
                        label = { Text("Assignee (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editDue,
                        onValueChange = { editDue = it },
                        label = { Text("Due / Deadline (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = !editNeedsVerification,
                            onCheckedChange = { checked -> editNeedsVerification = !checked }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Marked as clinically verified", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = editTitle.isNotBlank(),
                    onClick = {
                        onUpdateItem(
                            editing.copy(
                                title = editTitle.trim(),
                                details = editDetails.trim(),
                                assignee = editAssignee.trim().ifBlank { null },
                                due = editDue.trim().ifBlank { null },
                                needsVerification = editNeedsVerification
                            )
                        )
                        itemBeingEdited = null
                    }
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemBeingEdited = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    val viewingDetails = remember(allSessions, viewingTranscriptSessionId) {
        allSessions.firstOrNull { it.session.id == viewingTranscriptSessionId }
    }
    if (viewingDetails != null) {
        val scope = rememberCoroutineScope()
        SessionTranscriptSheet(
            details = viewingDetails,
            defaultLangMode = transcriptLangMode,
            playbackController = playbackController,
            onDismiss = { viewingTranscriptSessionId = null },
            onReanalyze = { sessionId ->
                onReanalyzeSession(sessionId)
            },
            onRenameSpeaker = { sessionId, speakerId, newLabel ->
                scope.launch {
                    val db = com.ambientbrain.recorder.data.AppDatabase.get(context).dao()
                    db.renameSpeakerAcrossSession(sessionId, speakerId, newLabel)
                }
            },
            onTogglePin = { sessionId, pinned ->
                scope.launch {
                    val db = com.ambientbrain.recorder.data.AppDatabase.get(context).dao()
                    db.setSessionPinned(sessionId, pinned)
                }
            }
        )
    }
}

@Composable
private fun CreateNoteDialog(
    onDismissRequest: () -> Unit,
    onSave: (title: String, details: String, type: ItemType, priority: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ItemType.TASK) }
    var priority by remember { mutableStateOf("NORMAL") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text("Create Quick Note / Task", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title / Action item *") },
                    placeholder = { Text("e.g. Order MRI brain for Bed 12") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Details / Clinical Notes") },
                    placeholder = { Text("Additional findings or context...") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Type:", style = MaterialTheme.typography.labelMedium)
                    FilterChip(
                        selected = type == ItemType.TASK,
                        onClick = { type = ItemType.TASK },
                        label = { Text("Task") }
                    )
                    FilterChip(
                        selected = type == ItemType.NOTE,
                        onClick = { type = ItemType.NOTE },
                        label = { Text("Note") }
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Priority:", style = MaterialTheme.typography.labelMedium)
                    FilterChip(
                        selected = priority == "NORMAL",
                        onClick = { priority = "NORMAL" },
                        label = { Text("Normal") }
                    )
                    FilterChip(
                        selected = priority == "HIGH",
                        onClick = { priority = "HIGH" },
                        label = { Text("High") }
                    )
                    FilterChip(
                        selected = priority == "URGENT",
                        onClick = { priority = "URGENT" },
                        label = { Text("Urgent") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onSave(title.trim(), details.trim(), type, priority)
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskOrNoteCard(
    item: ItemEntity,
    isSelected: Boolean,
    isSelectMode: Boolean,
    transcriptLangMode: TranscriptLangMode,
    playbackController: AudioPlaybackController,
    onOpenSessionTranscript: () -> Unit,
    onToggleDone: () -> Unit,
    onMarkVerified: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onCopySingle: () -> Unit,
    onToggleSelect: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            }
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.secondary
            else if (item.needsVerification) Color(0xFFF59E0B).copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (isSelectMode || isSelected) onToggleSelect() },
                onLongClick = onToggleSelect
            )
    ) {
        Column {
            // Urgent Clinical Verification Banner (Prevents medical errors at bedside)
            if (item.needsVerification) {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = Color(0xFF451A03),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "⚠️ Verify dose & clinical orders",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFDE68A),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onMarkVerified,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = "Mark verified",
                                tint = Color(0xFFFDE68A),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Verify",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFDE68A)
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (item.type == ItemType.TASK) {
                        Checkbox(
                            checked = item.isDone,
                            onCheckedChange = { onToggleDone() },
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    } else {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.padding(end = 8.dp, top = 2.dp)
                        ) {
                            Text(
                                text = item.type.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (item.isDone) TextDecoration.LineThrough else TextDecoration.None
                        )
                        if (item.details.isNotBlank()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = item.details,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit item",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onCopySingle, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy item",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onArchive, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Archive item",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Metadata Badges (Patient, Speaker, Assignee, Due, Priority)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!item.patientIdentifier.isNullOrBlank()) {
                        BadgePill(
                            text = "🛏️ ${item.patientIdentifier}",
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    }
                    if (item.speakerLabel.isNotBlank()) {
                        BadgePill(
                            text = "🗣️ ${item.speakerLabel}",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    }
                    if (!item.assignee.isNullOrBlank()) {
                        BadgePill(
                            text = "👤 ${item.assignee}",
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    }
                    if (!item.due.isNullOrBlank()) {
                        BadgePill(
                            text = "⏰ ${item.due}",
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    }
                    if (item.priority == "URGENT" || item.priority == "HIGH") {
                        BadgePill(
                            text = "🔥 ${item.priority}",
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    }
                }

                // Verbatim Bilingual Quote Block with Left Accent Bar
                if (item.quoteOriginal.isNotBlank() || !item.quoteEn.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                if (transcriptLangMode != TranscriptLangMode.ENGLISH_ONLY && item.quoteOriginal.isNotBlank()) {
                                    Text(
                                        text = "“${item.quoteOriginal}”",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (transcriptLangMode != TranscriptLangMode.ORIGINAL_ONLY && !item.quoteEn.isNullOrBlank()) {
                                    if (transcriptLangMode == TranscriptLangMode.BILINGUAL_TH_EN) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                    }
                                    Text(
                                        text = "EN: “${item.quoteEn}”",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Permanent Voice Proof Player Button
                if (!item.clipPath.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    VoiceProofClipButton(
                        clipPath = item.clipPath,
                        clipDurationMs = (item.clipEndMs - item.clipStartMs).coerceAtLeast(6_000L),
                        playbackController = playbackController
                    )
                }

                // Link to Parent Session Transcript & Details
                if (item.sessionId.isNotBlank() && !item.sessionId.startsWith("manual_")) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenSessionTranscript,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("📜 View Session Full Transcript", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun BadgePill(
    text: String,
    containerColor: Color
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
