package com.ambientbrain.recorder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.ambientbrain.recorder.data.ItemEntity
import com.ambientbrain.recorder.data.PatientBlockEntity
import com.ambientbrain.recorder.data.PatientPlanOrder
import com.ambientbrain.recorder.data.SessionWithDetails
import kotlinx.serialization.json.Json

enum class TaskCopyTemplate(val label: String) {
    WARD_BY_PATIENT("Ward Checklist (Grouped by Patient)"),
    MARKDOWN("Markdown Checklist (- [ ])"),
    PLAIN_BULLETS("Plain Bullet List (•)")
}

object CopyFormatter {

    private val json = Json { ignoreUnknownKeys = true }

    fun copyToClipboard(context: Context, label: String, text: String, toastMsg: String = "Copied to clipboard") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    }

    fun shareText(context: Context, subject: String, text: String) {
        if (text.isBlank()) return
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(intent, subject.ifBlank { "Share Notes" }))
        }
    }

    fun formatSingleItem(item: ItemEntity): String {
        val sb = StringBuilder()
        val prefix = if (item.type.name == "TASK") {
            if (item.isDone) "[x] " else "[ ] "
        } else {
            "[${item.type.name}] "
        }
        sb.append(prefix).append(item.title)
        if (!item.patientIdentifier.isNullOrBlank()) {
            sb.append(" (Patient: ").append(item.patientIdentifier).append(")")
        }
        if (item.details.isNotBlank()) {
            sb.append("\n  Details: ").append(item.details)
        }
        val meta = buildList {
            if (!item.assignee.isNullOrBlank()) add("Assignee: ${item.assignee}")
            if (!item.due.isNullOrBlank()) add("Due: ${item.due}")
            if (item.speakerLabel.isNotBlank()) add("Speaker: ${item.speakerLabel}")
            if (item.needsVerification) add("⚠️ Verify dose/order")
        }
        if (meta.isNotEmpty()) {
            sb.append("\n  ").append(meta.joinToString(" • "))
        }
        if (item.quoteOriginal.isNotBlank()) {
            sb.append("\n  \"${item.quoteOriginal}\"")
            if (!item.quoteEn.isNullOrBlank() && item.quoteEn != item.quoteOriginal) {
                sb.append(" (${item.quoteEn})")
            }
        }
        return sb.toString()
    }

    fun formatItemsList(items: List<ItemEntity>, template: TaskCopyTemplate): String {
        if (items.isEmpty()) return "No tasks selected."
        return when (template) {
            TaskCopyTemplate.WARD_BY_PATIENT -> {
                val grouped = items.groupBy { it.patientIdentifier?.takeIf { p -> p.isNotBlank() } ?: "General / Non-Patient" }
                buildString {
                    grouped.entries.forEachIndexed { idx, (patient, list) ->
                        if (idx > 0) append("\n\n")
                        append("🩺 ").append(patient).append("\n")
                        list.forEach { item ->
                            val check = if (item.isDone) "[x]" else "[ ]"
                            val verify = if (item.needsVerification) " ⚠️[VERIFY]" else ""
                            val who = item.assignee?.let { " — $it" }.orEmpty()
                            val due = item.due?.let { " ($it)" }.orEmpty()
                            append("- $check ${item.title}$verify$who$due\n")
                            if (item.details.isNotBlank()) {
                                append("    ${item.details}\n")
                            }
                        }
                    }
                }.trim()
            }

            TaskCopyTemplate.MARKDOWN -> {
                buildString {
                    items.forEach { item ->
                        val check = if (item.isDone) "[x]" else "[ ]"
                        val pat = item.patientIdentifier?.let { "**[$it]** " }.orEmpty()
                        val who = item.assignee?.let { " *(Assignee: $it)*" }.orEmpty()
                        val due = item.due?.let { " — Due: $it" }.orEmpty()
                        val verify = if (item.needsVerification) " ⚠️" else ""
                        append("- $check $pat${item.title}$verify$who$due\n")
                    }
                }.trim()
            }

            TaskCopyTemplate.PLAIN_BULLETS -> {
                buildString {
                    items.forEach { item ->
                        val pat = item.patientIdentifier?.let { "[$it] " }.orEmpty()
                        val due = item.due?.let { " ($it)" }.orEmpty()
                        append("• $pat${item.title}$due\n")
                    }
                }.trim()
            }
        }
    }

    fun formatPatientBlock(patient: PatientBlockEntity): String {
        val problems = decodeStringList(patient.problemsJson)
        val results = decodeStringList(patient.resultsJson)
        val pending = decodeStringList(patient.pendingJson)
        val plans = decodePlans(patient.planJson)
        val teaching = decodeStringList(patient.teachingJson)

        return buildString {
            append("=== Patient: ").append(patient.patientIdentifier).append(" ===\n")
            if (patient.oneLiner.isNotBlank()) {
                append("Summary: ").append(patient.oneLiner).append("\n")
            }
            if (!patient.neuroExam.isNullOrBlank()) {
                append("Neuro Exam: ").append(patient.neuroExam).append("\n")
            }
            if (patient.assessment.isNotBlank()) {
                append("Assessment: ").append(patient.assessment).append("\n")
            }
            if (problems.isNotEmpty()) {
                append("Problems: ").append(problems.joinToString("; ")).append("\n")
            }
            if (results.isNotEmpty()) {
                append("Results: ").append(results.joinToString("; ")).append("\n")
            }
            if (pending.isNotEmpty()) {
                append("Pending: ").append(pending.joinToString("; ")).append("\n")
            }
            if (plans.isNotEmpty()) {
                append("Plan:\n")
                plans.forEach { order ->
                    val flag = if (order.needsVerification) " [⚠️ VERIFY]" else ""
                    append("  - [${order.category}] ${order.text}$flag\n")
                }
            }
            if (teaching.isNotEmpty()) {
                append("Teaching Pearls:\n")
                teaching.forEach { append("  * $it\n") }
            }
        }.trim()
    }

    fun formatEntireRound(details: SessionWithDetails): String {
        return buildString {
            append("# ").append(details.session.title ?: "Medical / Neurology Round").append("\n")
            if (!details.session.summary.isNullOrBlank()) {
                append(details.session.summary).append("\n\n")
            }
            if (details.patients.isNotEmpty()) {
                details.patients.forEach { p ->
                    append(formatPatientBlock(p)).append("\n\n")
                }
            }
            if (details.items.isNotEmpty()) {
                append("=== Action Items & Notes ===\n")
                append(formatItemsList(details.items, TaskCopyTemplate.WARD_BY_PATIENT))
            }
        }.trim()
    }

    fun decodeStringList(rawJson: String?): List<String> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(rawJson) }.getOrDefault(emptyList())
    }

    fun decodePlans(rawJson: String?): List<PatientPlanOrder> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<PatientPlanOrder>>(rawJson) }.getOrDefault(emptyList())
    }
}
