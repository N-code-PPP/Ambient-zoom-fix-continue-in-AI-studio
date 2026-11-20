package com.ambientbrain.recorder.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class SessionMode {
    EVERYDAY,
    ROUND_MANUAL,
    ROUND_AUTO
}

enum class TranscriptLangMode(val label: String) {
    BILINGUAL_TH_EN("Thai + English (Bilingual)"),
    ORIGINAL_ONLY("Thai (Original Only)"),
    ENGLISH_ONLY("English Translation Only")
}

enum class Status {
    CAPTURING,
    QUEUED,
    ANALYZING,
    CLIPPING,
    MERGING,
    DONE,
    SKIPPED_NO_SPEECH,
    NOT_MEANINGFUL,
    FAILED_RETRYABLE,
    FAILED_PERMANENT
}

enum class ItemType {
    TASK,
    NOTE,
    DECISION,
    EVENT
}

@Serializable
data class PatientPlanOrder(
    val category: String,
    val text: String,
    @SerialName("needs_verification") val needsVerification: Boolean = false
)

@Entity(
    tableName = "sessions",
    indices = [
        Index("mode"),
        Index("startMs"),
        Index("roundGroupId"),
        Index("eventGroupId"),
        Index("status")
    ]
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val roundGroupId: String? = null,
    val eventGroupId: String? = null,
    val combinedSourceIdsJson: String? = null,
    val mode: SessionMode = SessionMode.EVERYDAY,
    val startMs: Long,
    val endMs: Long,
    val speechMs: Long,
    val hadPhoneCall: Boolean = false,
    val callEventsJson: String? = null,
    val rawPath: String? = null,
    val rawBytes: Long = 0L,
    val isRawPurged: Boolean = false,
    val isPinned: Boolean = false,
    val context: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val keyPointsJson: String? = null,
    val status: Status = Status.CAPTURING,
    val lastError: String? = null,
    val retryCount: Int = 0,
    val nextRetryEpochMs: Long = 0L,
    val aiReviewReport: String? = null,
    val reviewedAtMs: Long = 0L,
    val reviewSourceMode: String? = null
)

@Entity(
    tableName = "segment_map",
    primaryKeys = ["sessionId", "fileOffsetMs"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class SegmentMapEntity(
    val sessionId: String,
    val fileOffsetMs: Long,
    val wallClockMs: Long,
    val lengthMs: Long
)

@Entity(
    tableName = "speakers",
    primaryKeys = ["sessionId", "speakerId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class SessionSpeakerEntity(
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

@Entity(
    tableName = "transcript_sentences",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("speakerId")]
)
data class TranscriptSentenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sentenceUuid: String,
    val sessionId: String,
    val ordinal: Int,
    val approxMs: Long,
    val speakerId: String,
    val speakerLabel: String,
    val textOriginal: String,
    val textEn: String?
)

@Fts4(contentEntity = TranscriptSentenceEntity::class)
@Entity(tableName = "transcript_fts")
data class TranscriptFts(
    val textOriginal: String,
    val textEn: String?
)

@Entity(
    tableName = "patients",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("roundGroupId")]
)
data class PatientBlockEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val roundGroupId: String? = null,
    val patientIdentifier: String,
    val approxStartMs: Long,
    val approxEndMs: Long,
    val oneLiner: String,
    val neuroExam: String?,
    val assessment: String,
    val problemsJson: String,
    val resultsJson: String,
    val pendingJson: String,
    val planJson: String,
    val teachingJson: String
)

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("type"), Index("isDone")]
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val patientIdentifier: String?,
    val type: ItemType,
    val title: String,
    val details: String,
    val speakerId: String? = null,
    val speakerLabel: String,
    val assignee: String?,
    val isForUser: Boolean = false,
    val due: String?,
    val priority: String = "NORMAL",
    val quoteOriginal: String,
    val quoteEn: String?,
    val needsVerification: Boolean = false,
    val clipStartMs: Long,
    val clipEndMs: Long,
    val clipPath: String?,
    val isDone: Boolean = false,
    val isArchived: Boolean = false,
    val createdMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "analysis_runs",
    indices = [Index("sessionId")]
)
data class AnalysisRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val model: String,
    val promptVersion: String,
    val inTokens: Int,
    val outTokens: Int,
    @ColumnInfo(defaultValue = "0")
    val cachedTokens: Int = 0,
    val latencyMs: Long,
    val ok: Boolean,
    val errorMsg: String? = null,
    val atMs: Long = System.currentTimeMillis()
)

data class SessionWithDetails(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val speakers: List<SessionSpeakerEntity>,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val sentences: List<TranscriptSentenceEntity>,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val patients: List<PatientBlockEntity>,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val items: List<ItemEntity>
)

data class AnalysisUsageSummary(
    val totalCalls: Int = 0,
    val successCalls: Int = 0,
    val failedCalls: Int = 0,
    val totalInTokens: Long = 0L,
    val totalOutTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val avgLatencyMs: Long = 0L
)

data class ModelUsageBreakdown(
    val model: String,
    val calls: Int,
    val successCalls: Int,
    val totalTokens: Long,
    val avgLatencyMs: Long
)
