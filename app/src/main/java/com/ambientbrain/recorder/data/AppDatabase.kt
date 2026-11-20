package com.ambientbrain.recorder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        SegmentMapEntity::class,
        SessionSpeakerEntity::class,
        TranscriptSentenceEntity::class,
        TranscriptFts::class,
        PatientBlockEntity::class,
        ItemEntity::class,
        AnalysisRunEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analysis_runs ADD COLUMN cachedTokens INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN nextRetryEpochMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN eventGroupId TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN combinedSourceIdsJson TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_eventGroupId ON sessions(eventGroupId)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN aiReviewReport TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN reviewedAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN reviewSourceMode TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE speakers ADD COLUMN matchedProfileId TEXT")
                db.execSQL("ALTER TABLE speakers ADD COLUMN nickname TEXT")
                db.execSQL("ALTER TABLE speakers ADD COLUMN relatableTitle TEXT")
                db.execSQL("ALTER TABLE speakers ADD COLUMN voiceSignature TEXT")
                db.execSQL("ALTER TABLE speakers ADD COLUMN isAutoEnrolled INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ambient_brain_v4.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
