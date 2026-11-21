package com.ambientbrain.recorder.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies Room schema export (`app/schemas/.../6.json`), migration chain integrity (v1 -> v6),
 * data preservation across all tables + FTS4 triggers, and fail-loud behavior (no silent wipe)
 * when a migration path is missing or downgraded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val openedDbs = mutableListOf<AppDatabase>()
    private val createdDbNames = mutableListOf<String>()

    @After
    fun tearDown() {
        openedDbs.forEach { runCatching { it.close() } }
        openedDbs.clear()
        AppDatabase.clearInstanceForTesting()
        createdDbNames.forEach { context.deleteDatabase(it) }
        createdDbNames.clear()
        context.deleteDatabase(AppDatabase.DB_NAME)
    }

    @Test
    fun schemaV6_exportedJsonMatchesAppDatabaseAndSupportsFtsAndForeignKeys() = runBlocking {
        val dbName = registerDb("migration_test_v6_schema.db")

        // Reads app/schemas/com.ambientbrain.recorder.data.AppDatabase/6.json via MigrationTestHelper
        helper.createDatabase(dbName, AppDatabase.DB_VERSION).close()

        val roomDb = openRoomDb(dbName)
        val dao = roomDb.dao()

        val session = SessionEntity(
            id = "sess_v6",
            mode = SessionMode.ROUND_MANUAL,
            startMs = 1_700_000_000_000L,
            endMs = 1_700_000_120_000L,
            speechMs = 45_000L,
            status = Status.DONE,
            title = "Neuro Ward Round",
            summary = "Reviewed Bed 4 stroke workup"
        )
        dao.insertSessionWithSegmentMaps(
            session = session,
            segmentMaps = listOf(
                SegmentMapEntity("sess_v6", 0L, 1_700_000_000_000L, 45_000L)
            )
        )
        dao.insertSpeakers(
            listOf(
                SessionSpeakerEntity(
                    sessionId = "sess_v6",
                    speakerId = "SPK_1",
                    speakerLabel = "Dr. Chen",
                    matchedProfileId = "prof_1",
                    isAutoEnrolled = true
                )
            )
        )
        dao.insertSentences(
            listOf(
                TranscriptSentenceEntity(
                    sentenceUuid = "sent_v6_1",
                    sessionId = "sess_v6",
                    ordinal = 0,
                    approxMs = 12_000L,
                    speakerId = "SPK_1",
                    speakerLabel = "Dr. Chen",
                    textOriginal = "Check serum potassium and repeat CT head",
                    textEn = "Check serum potassium and repeat CT head"
                )
            )
        )

        // Verify FTS4 search works against exported v6 schema
        val ftsHits = dao.searchTranscriptSentences("potassium")
        assertEquals(1, ftsHits.size)
        assertEquals("sent_v6_1", ftsHits.first().sentenceUuid)

        // Verify @Upsert updates parent session in-place without triggering ON DELETE CASCADE
        dao.upsertSession(session.copy(title = "Neuro Ward Round (Updated)"))
        val details = dao.getSessionWithDetailsOnce("sess_v6")
        assertNotNull(details)
        assertEquals("Neuro Ward Round (Updated)", details!!.session.title)
        assertEquals(1, dao.getSegmentMapForSession("sess_v6").size)
        assertEquals(1, details.speakers.size)
        assertEquals(1, details.sentences.size)
    }

    @Test
    fun allMigrations_formContinuousChainFrom1ToCurrentVersion() {
        val migrations = AppDatabase.ALL_MIGRATIONS.sortedBy { it.startVersion }
        assertEquals(AppDatabase.DB_VERSION - 1, migrations.size)
        for (i in migrations.indices) {
            val expectedStart = i + 1
            val expectedEnd = i + 2
            assertEquals(expectedStart, migrations[i].startVersion)
            assertEquals(expectedEnd, migrations[i].endVersion)
        }
    }

    @Test
    fun migrateAll_1To6_preservesAllUserDataAndValidatesRoomSchema() = runBlocking {
        val dbName = registerDb("migration_test_1_to_6.db")
        createSeededHistoricalDatabase(dbName, targetVersion = 1)

        val roomDb = openRoomDb(dbName)
        val dao = roomDb.dao()

        // Room's onValidateSchema has already verified every table, column, FK, index, and FTS4 table.
        val details = dao.getSessionWithDetailsOnce("sess_legacy_1")
        assertNotNull("Legacy session must survive v1->v6 migration", details)
        val s = details!!.session
        assertEquals("Legacy Stroke Round", s.title)
        assertEquals("Existing clinical notes must remain intact", s.summary)
        assertEquals(Status.DONE, s.status)
        assertEquals(0, s.retryCount)
        assertEquals(0L, s.nextRetryEpochMs)
        assertNull(s.eventGroupId)
        assertNull(s.combinedSourceIdsJson)
        assertNull(s.aiReviewReport)
        assertEquals(0L, s.reviewedAtMs)
        assertNull(s.reviewSourceMode)

        // Child tables
        val segs = dao.getSegmentMapForSession("sess_legacy_1")
        assertEquals(1, segs.size)
        assertEquals(1_690_000_000_000L, segs.first().wallClockMs)

        assertEquals(1, details.speakers.size)
        val spk = details.speakers.first()
        assertEquals("Dr. Legacy", spk.speakerLabel)
        assertNull(spk.matchedProfileId)
        assertNull(spk.nickname)
        assertFalse(spk.isAutoEnrolled)

        assertEquals(1, details.sentences.size)
        assertEquals("Start aspirin 325mg stat", details.sentences.first().textOriginal)

        // FTS4 index and triggers survive onPreMigrate/onPostMigrate
        val ftsHits = dao.searchTranscriptSentences("aspirin")
        assertEquals(1, ftsHits.size)

        assertEquals(1, details.patients.size)
        assertEquals("Bed 7", details.patients.first().patientIdentifier)

        assertEquals(1, details.items.size)
        assertEquals("Order STAT head CT", details.items.first().title)

        val runs = dao.getAllAnalysisRunsOnce()
        assertEquals(1, runs.size)
        assertEquals(0, runs.first().cachedTokens)

        // Verify index created in MIGRATION_3_4 exists
        val writable = roomDb.openHelper.writableDatabase
        writable.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_sessions_eventGroupId'"
        ).use { cursor ->
            assertTrue("index_sessions_eventGroupId must exist after v3->v4", cursor.moveToFirst())
        }
    }

    @Test
    fun migrateFromEachHistoricalVersion_2Through5To6_validatesAndPreservesData() = runBlocking {
        for (startVersion in 2..5) {
            val dbName = registerDb("migration_test_${startVersion}_to_6.db")
            createSeededHistoricalDatabase(dbName, targetVersion = startVersion)

            val roomDb = openRoomDb(dbName)
            val dao = roomDb.dao()
            val details = dao.getSessionWithDetailsOnce("sess_legacy_1")
            assertNotNull("Session must survive v$startVersion->v6 migration", details)
            assertEquals("Legacy Stroke Round", details!!.session.title)
            assertEquals(1, details.sentences.size)
            assertEquals(1, details.patients.size)
            assertEquals(1, details.items.size)
            assertEquals(1, dao.getSegmentMapForSession("sess_legacy_1").size)
            roomDb.close()
        }
    }

    @Test
    fun missingUpgradeMigration_failsLoudlyAndLeavesOnDiskDataIntact() {
        val dbName = registerDb("migration_test_missing_upgrade.db")
        createSeededHistoricalDatabase(dbName, targetVersion = 5)

        // Deliberately omit MIGRATION_5_6 (and no fallbackToDestructiveMigration)
        val incompleteDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()
        openedDbs.add(incompleteDb)

        try {
            incompleteDb.openHelper.writableDatabase
            fail("Expected IllegalStateException when MIGRATION_5_6 is missing")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "Error message should mention missing migration: ${expected.message}",
                expected.message.orEmpty().contains("migration", ignoreCase = true)
            )
        } finally {
            incompleteDb.close()
        }

        // Critical invariant: raw SQLite file must STILL contain 100% of user data untouched
        assertRawRowCountsIntact(dbName)
    }

    @Test
    fun appDatabaseGet_downgradeFailsLoudlyWithoutWipingUserData() {
        val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        dbFile.parentFile?.mkdirs()

        // Create a valid v6 DB via exported schema, seed a session, then simulate a newer v7 DB on disk
        helper.createDatabase(AppDatabase.DB_NAME, AppDatabase.DB_VERSION).apply {
            execSQL(
                """
                INSERT INTO sessions (
                    id, mode, startMs, endMs, speechMs, hadPhoneCall, rawBytes, isRawPurged, isPinned,
                    title, summary, status, retryCount, nextRetryEpochMs, reviewedAtMs
                ) VALUES (
                    'sess_v7_future', 'ROUND_MANUAL', 1700000000000, 1700000060000, 30000, 0, 1024, 0, 1,
                    'Critical Patient Handover', 'Do not wipe on downgrade', 'DONE', 0, 0, 0
                )
                """.trimIndent()
            )
            execSQL("PRAGMA user_version = ${AppDatabase.DB_VERSION + 1}")
            close()
        }

        val prodDb = AppDatabase.get(context)
        try {
            prodDb.openHelper.writableDatabase
            fail("Expected IllegalStateException on DB downgrade; destructive fallback must remain disabled")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "Expected missing downgrade migration error, got: ${expected.message}",
                expected.message.orEmpty().contains("migration", ignoreCase = true)
            )
        } finally {
            AppDatabase.clearInstanceForTesting()
        }

        // Verify the on-disk DB was NOT dropped by AppDatabase.get()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT title FROM sessions WHERE id = 'sess_v7_future'", null).use { c ->
                assertTrue("Session row must remain intact on disk after blocked downgrade", c.moveToFirst())
                assertEquals("Critical Patient Handover", c.getString(0))
            }
        }
    }

    private fun registerDb(name: String): String {
        context.deleteDatabase(name)
        createdDbNames.add(name)
        return name
    }

    private fun openRoomDb(dbName: String): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { openedDbs.add(it) }
    }

    private fun assertRawRowCountsIntact(dbName: String) {
        val dbFile = context.getDatabasePath(dbName)
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            fun count(table: String): Int =
                raw.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            assertEquals(1, count("sessions"))
            assertEquals(1, count("segment_map"))
            assertEquals(1, count("speakers"))
            assertEquals(1, count("transcript_sentences"))
            assertEquals(1, count("patients"))
            assertEquals(1, count("items"))
            assertEquals(1, count("analysis_runs"))
        }
    }

    /**
     * Builds a historical SQLite database at [targetVersion] (1..5) matching v1 DDL plus any
     * migrations up to [targetVersion], seeded with realistic rows across all 8 tables.
     */
    private fun createSeededHistoricalDatabase(dbName: String, targetVersion: Int) {
        require(targetVersion in 1..5)
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")

            // Exact v1 schema (v6 schema minus columns/index added in MIGRATION_1_2 .. MIGRATION_5_6)
            db.execSQL(
                "CREATE TABLE `sessions` (`id` TEXT NOT NULL, `roundGroupId` TEXT, `mode` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `speechMs` INTEGER NOT NULL, `hadPhoneCall` INTEGER NOT NULL, `callEventsJson` TEXT, `rawPath` TEXT, `rawBytes` INTEGER NOT NULL, `isRawPurged` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, `context` TEXT, `title` TEXT, `summary` TEXT, `keyPointsJson` TEXT, `status` TEXT NOT NULL, `lastError` TEXT, PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX `index_sessions_mode` ON `sessions` (`mode`)")
            db.execSQL("CREATE INDEX `index_sessions_startMs` ON `sessions` (`startMs`)")
            db.execSQL("CREATE INDEX `index_sessions_roundGroupId` ON `sessions` (`roundGroupId`)")
            db.execSQL("CREATE INDEX `index_sessions_status` ON `sessions` (`status`)")

            db.execSQL(
                "CREATE TABLE `segment_map` (`sessionId` TEXT NOT NULL, `fileOffsetMs` INTEGER NOT NULL, `wallClockMs` INTEGER NOT NULL, `lengthMs` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `fileOffsetMs`), FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX `index_segment_map_sessionId` ON `segment_map` (`sessionId`)")

            db.execSQL(
                "CREATE TABLE `speakers` (`sessionId` TEXT NOT NULL, `speakerId` TEXT NOT NULL, `speakerLabel` TEXT NOT NULL, `inferredRole` TEXT, `isUser` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `speakerId`), FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX `index_speakers_sessionId` ON `speakers` (`sessionId`)")

            db.execSQL(
                "CREATE TABLE `transcript_sentences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sentenceUuid` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `approxMs` INTEGER NOT NULL, `speakerId` TEXT NOT NULL, `speakerLabel` TEXT NOT NULL, `textOriginal` TEXT NOT NULL, `textEn` TEXT, FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX `index_transcript_sentences_sessionId` ON `transcript_sentences` (`sessionId`)")
            db.execSQL("CREATE INDEX `index_transcript_sentences_speakerId` ON `transcript_sentences` (`speakerId`)")

            db.execSQL(
                "CREATE VIRTUAL TABLE `transcript_fts` USING FTS4(`textOriginal` TEXT NOT NULL, `textEn` TEXT, content=`transcript_sentences`)"
            )
            db.execSQL("CREATE TRIGGER room_fts_content_sync_transcript_fts_BEFORE_UPDATE BEFORE UPDATE ON `transcript_sentences` BEGIN DELETE FROM `transcript_fts` WHERE `docid`=OLD.`rowid`; END")
            db.execSQL("CREATE TRIGGER room_fts_content_sync_transcript_fts_BEFORE_DELETE BEFORE DELETE ON `transcript_sentences` BEGIN DELETE FROM `transcript_fts` WHERE `docid`=OLD.`rowid`; END")
            db.execSQL("CREATE TRIGGER room_fts_content_sync_transcript_fts_AFTER_UPDATE AFTER UPDATE ON `transcript_sentences` BEGIN INSERT INTO `transcript_fts`(`docid`, `textOriginal`, `textEn`) VALUES (NEW.`rowid`, NEW.`textOriginal`, NEW.`textEn`); END")
            db.execSQL("CREATE TRIGGER room_fts_content_sync_transcript_fts_AFTER_INSERT AFTER INSERT ON `transcript_sentences` BEGIN INSERT INTO `transcript_fts`(`docid`, `textOriginal`, `textEn`) VALUES (NEW.`rowid`, NEW.`textOriginal`, NEW.`textEn`); END")

            db.execSQL(
                "CREATE TABLE `patients` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `roundGroupId` TEXT, `patientIdentifier` TEXT NOT NULL, `approxStartMs` INTEGER NOT NULL, `approxEndMs` INTEGER NOT NULL, `oneLiner` TEXT NOT NULL, `neuroExam` TEXT, `assessment` TEXT NOT NULL, `problemsJson` TEXT NOT NULL, `resultsJson` TEXT NOT NULL, `pendingJson` TEXT NOT NULL, `planJson` TEXT NOT NULL, `teachingJson` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX `index_patients_sessionId` ON `patients` (`sessionId`)")
            db.execSQL("CREATE INDEX `index_patients_roundGroupId` ON `patients` (`roundGroupId`)")

            db.execSQL(
                "CREATE TABLE `items` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `patientIdentifier` TEXT, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `details` TEXT NOT NULL, `speakerId` TEXT, `speakerLabel` TEXT NOT NULL, `assignee` TEXT, `isForUser` INTEGER NOT NULL, `due` TEXT, `priority` TEXT NOT NULL, `quoteOriginal` TEXT NOT NULL, `quoteEn` TEXT, `needsVerification` INTEGER NOT NULL, `clipStartMs` INTEGER NOT NULL, `clipEndMs` INTEGER NOT NULL, `clipPath` TEXT, `isDone` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, `createdMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX `index_items_sessionId` ON `items` (`sessionId`)")
            db.execSQL("CREATE INDEX `index_items_type` ON `items` (`type`)")
            db.execSQL("CREATE INDEX `index_items_isDone` ON `items` (`isDone`)")

            db.execSQL(
                "CREATE TABLE `analysis_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, `model` TEXT NOT NULL, `promptVersion` TEXT NOT NULL, `inTokens` INTEGER NOT NULL, `outTokens` INTEGER NOT NULL, `latencyMs` INTEGER NOT NULL, `ok` INTEGER NOT NULL, `errorMsg` TEXT, `atMs` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX `index_analysis_runs_sessionId` ON `analysis_runs` (`sessionId`)")

            // Seed v1 rows across all tables
            db.execSQL(
                """
                INSERT INTO sessions (
                    id, roundGroupId, mode, startMs, endMs, speechMs, hadPhoneCall, callEventsJson,
                    rawPath, rawBytes, isRawPurged, isPinned, context, title, summary, keyPointsJson, status, lastError
                ) VALUES (
                    'sess_legacy_1', 'rg_1', 'ROUND_MANUAL', 1690000000000, 1690000060000, 42000, 0, NULL,
                    '/tmp/legacy.aac', 20480, 0, 1, 'NEURO_ROUND', 'Legacy Stroke Round',
                    'Existing clinical notes must remain intact', '["Order STAT head CT"]', 'DONE', NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO segment_map (sessionId, fileOffsetMs, wallClockMs, lengthMs) VALUES ('sess_legacy_1', 0, 1690000000000, 42000)"
            )
            db.execSQL(
                "INSERT INTO speakers (sessionId, speakerId, speakerLabel, inferredRole, isUser) VALUES ('sess_legacy_1', 'SPK_1', 'Dr. Legacy', 'Attending', 0)"
            )
            db.execSQL(
                "INSERT INTO transcript_sentences (sentenceUuid, sessionId, ordinal, approxMs, speakerId, speakerLabel, textOriginal, textEn) VALUES ('sent_legacy_1', 'sess_legacy_1', 0, 5000, 'SPK_1', 'Dr. Legacy', 'Start aspirin 325mg stat', 'Start aspirin 325mg stat')"
            )
            db.execSQL(
                """
                INSERT INTO patients (
                    id, sessionId, roundGroupId, patientIdentifier, approxStartMs, approxEndMs,
                    oneLiner, neuroExam, assessment, problemsJson, resultsJson, pendingJson, planJson, teachingJson
                ) VALUES (
                    'pat_1', 'sess_legacy_1', 'rg_1', 'Bed 7', 0, 40000,
                    '68M acute right MCA stroke', 'Left hemiparesis', 'Ischemic stroke',
                    '["Stroke"]', '["CT pending"]', '["CTA"]', '[]', '[]'
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO items (
                    id, sessionId, patientIdentifier, type, title, details, speakerId, speakerLabel,
                    assignee, isForUser, due, priority, quoteOriginal, quoteEn, needsVerification,
                    clipStartMs, clipEndMs, clipPath, isDone, isArchived, createdMs
                ) VALUES (
                    'item_1', 'sess_legacy_1', 'Bed 7', 'TASK', 'Order STAT head CT', 'Before tPA window closes',
                    'SPK_1', 'Dr. Legacy', 'Me', 1, 'Today', 'HIGH', 'Start aspirin 325mg stat',
                    'Start aspirin 325mg stat', 0, 0, 10000, NULL, 0, 0, 1690000005000
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO analysis_runs (sessionId, model, promptVersion, inTokens, outTokens, latencyMs, ok, errorMsg, atMs) VALUES ('sess_legacy_1', 'gemini-2.5-flash', 'v1', 1200, 450, 1800, 1, NULL, 1690000070000)"
            )

            // Apply sequential DDL steps up to targetVersion
            if (targetVersion >= 2) {
                db.execSQL("ALTER TABLE analysis_runs ADD COLUMN cachedTokens INTEGER NOT NULL DEFAULT 0")
            }
            if (targetVersion >= 3) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN nextRetryEpochMs INTEGER NOT NULL DEFAULT 0")
            }
            if (targetVersion >= 4) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN eventGroupId TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN combinedSourceIdsJson TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_eventGroupId ON sessions(eventGroupId)")
            }
            if (targetVersion >= 5) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN aiReviewReport TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN reviewedAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN reviewSourceMode TEXT")
            }

            db.version = targetVersion
        }
    }
}
