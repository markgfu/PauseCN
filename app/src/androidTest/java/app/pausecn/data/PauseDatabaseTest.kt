package app.pausecn.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PauseDatabaseTest {
    private lateinit var database: PauseDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            PauseDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun retentionDeletesExpiredRowsAndKeepsRecentRows() = runBlocking {
        val dao = database.interventionEventDao()
        dao.insert(event(at = 100))
        dao.insert(event(at = 200))
        dao.insert(event(at = 300))

        assertEquals(1, dao.deleteOlderThan(200))
        assertEquals(
            2,
            dao.observeStatsBetween(
                Long.MIN_VALUE,
                Long.MAX_VALUE,
                InterventionOutcome.EXITED.name,
                InterventionOutcome.CONTINUED.name,
                InterventionOutcome.DISMISSED.name,
                InterventionOutcome.DISPLAY_FAILED.name,
            ).first().total,
        )
    }

    @Test
    fun repositoryEnforcesSelectedRetentionOnEveryWrite() = runBlocking {
        val dayMs = 24L * 60 * 60_000
        val now = 400 * dayMs
        val dao = database.interventionEventDao()
        dao.insert(event(at = now - 31 * dayMs, purpose = "expired"))
        dao.insert(event(at = now - 29 * dayMs, purpose = "kept"))

        val repository = PauseRepository(
            ApplicationProvider.getApplicationContext(),
            database,
        )
        val result = repository.beginIntervention(
            packageName = "example.app",
            appLabel = "Example",
            retentionDays = 30,
            nowEpochMs = now,
        )
        assertEquals(true, repository.completeIntervention(result.eventId, InterventionOutcome.EXITED))
        assertEquals(false, repository.completeIntervention(result.eventId, InterventionOutcome.CONTINUED, "重复"))
        repository.recordIntervention(
            packageName = "failed.app",
            appLabel = "Failed",
            outcome = InterventionOutcome.DISPLAY_FAILED,
            retentionDays = 30,
            nowEpochMs = now + 1,
        )
        val safetyDismissed = repository.beginIntervention(
            packageName = "safety.app",
            appLabel = "Safety",
            retentionDays = 30,
            nowEpochMs = now + 2,
        )
        assertEquals(true, repository.discardIntervention(safetyDismissed.eventId))
        assertEquals(false, repository.discardIntervention(safetyDismissed.eventId))
        val serviceDestroyed = repository.beginIntervention(
            packageName = "destroyed.app",
            appLabel = "Destroyed",
            retentionDays = 30,
            nowEpochMs = now + 3,
        )
        assertEquals(true, repository.completeIntervention(serviceDestroyed.eventId, InterventionOutcome.DISMISSED))
        repository.beginIntervention(
            packageName = "killed.app",
            appLabel = "Killed",
            retentionDays = 30,
            nowEpochMs = now + 4,
        )
        assertEquals(1, repository.recoverInterruptedInterventions())
        assertEquals(0, repository.recoverInterruptedInterventions())
        val serviceSessionId = repository.beginServiceSession(
            retentionDays = 30,
            nowEpochMs = now,
            nowElapsedMs = 100,
        )
        assertTrue(repository.recordServiceHeartbeat(serviceSessionId, now + 5_000, nowElapsedMs = 5_100))

        assertEquals(1, result.pruneResult.expiredRows)
        assertEquals(0, result.pruneResult.overflowRows)
        val events = dao.getAllForExport()
        assertEquals(
            listOf(
                InterventionOutcome.CONTINUED.name,
                InterventionOutcome.EXITED.name,
                InterventionOutcome.DISPLAY_FAILED.name,
                InterventionOutcome.DISMISSED.name,
                InterventionOutcome.DISMISSED.name,
            ),
            events.map { it.outcome },
        )
        val stats = repository.observeStatsSince(Long.MIN_VALUE).first()
        assertEquals(5, stats.total)
        assertEquals(1, stats.exited)
        assertEquals(1, stats.continued)
        assertEquals(2, stats.dismissed)
        assertEquals(1, stats.displayFailed)
        assertEquals(80, stats.triggerSuccessRate)
        dao.insert(event(at = now + dayMs, purpose = "future-after-clock-correction"))
        val boundedStats = repository.observeStatsSince(Long.MIN_VALUE, now + dayMs).first()
        assertEquals(5, boundedStats.total)
        assertEquals(1, boundedStats.exited)
        assertEquals(1, boundedStats.continued)
        assertEquals(2, boundedStats.dismissed)
        assertEquals(1, boundedStats.displayFailed)

        val currentMinuteStart = now + 2 * dayMs
        val currentMinuteEndExclusive = currentMinuteStart + 60_000
        val liveEmissions = Channel<StatsSnapshot>(Channel.UNLIMITED)
        val liveCollection = launch {
            repository.observeStatsSince(currentMinuteStart, currentMinuteEndExclusive)
                .collect(liveEmissions::send)
        }
        fun assertAtomicSnapshot(expected: StatsSnapshot, actual: StatsSnapshot) {
            assertEquals(expected, actual)
            assertTrue(
                actual.exited + actual.continued + actual.dismissed + actual.displayFailed <= actual.total,
            )
        }

        assertAtomicSnapshot(StatsSnapshot(), withTimeout(5_000) { liveEmissions.receive() })
        dao.insert(event(at = currentMinuteStart, purpose = "inclusive-lower-bound"))
        assertAtomicSnapshot(
            StatsSnapshot(total = 1, continued = 1),
            withTimeout(5_000) { liveEmissions.receive() },
        )
        dao.insert(
            event(at = currentMinuteStart + 1, purpose = "unknown-outcome")
                .copy(outcome = "FUTURE_UNKNOWN_OUTCOME"),
        )
        assertAtomicSnapshot(
            StatsSnapshot(total = 2, continued = 1),
            withTimeout(5_000) { liveEmissions.receive() },
        )
        dao.insert(event(at = currentMinuteEndExclusive - 1, purpose = "inside-upper-bound"))
        assertAtomicSnapshot(
            StatsSnapshot(total = 3, continued = 2),
            withTimeout(5_000) { liveEmissions.receive() },
        )
        dao.insert(event(at = currentMinuteEndExclusive, purpose = "exclusive-upper-bound"))
        assertAtomicSnapshot(
            StatsSnapshot(total = 3, continued = 2),
            withTimeout(5_000) { liveEmissions.receive() },
        )
        assertEquals(
            StatsSnapshot(total = 3, continued = 2),
            repository.observeStatsSince(currentMinuteStart, currentMinuteEndExclusive).first(),
        )
        liveCollection.cancelAndJoin()

        assertEquals(
            listOf(ServiceSessionEntity(serviceSessionId, now, 100, now + 5_000, 5_100, 1, 5_000)),
            database.serviceSessionDao().getAllForExport(),
        )
        database.serviceSessionDao().deleteAll()
        assertEquals(false, repository.recordServiceHeartbeat(serviceSessionId, now + 6_000, nowElapsedMs = 6_100))
    }

    @Test
    fun rowCapKeepsNewestEventsDeterministically() = runBlocking {
        val dao = database.interventionEventDao()
        val repository = PauseRepository(
            ApplicationProvider.getApplicationContext(),
            database,
            maxHistoryRows = 2,
        )
        repository.recordIntervention("example.app", "Example", InterventionOutcome.CONTINUED, "old", nowEpochMs = 100)
        repository.recordIntervention("example.app", "Example", InterventionOutcome.CONTINUED, "middle", nowEpochMs = 200)
        val result = repository.recordIntervention(
            "example.app",
            "Example",
            InterventionOutcome.CONTINUED,
            "new",
            nowEpochMs = 300,
        )

        assertEquals(1, result.overflowRows)
        val recent = dao.observeRecent(limit = 10).first()
        assertEquals(listOf("new", "middle"), recent.map { it.purpose })
    }

    @Test
    fun targetsAndHistoryCanBeDeletedWithoutDroppingTheDatabase() = runBlocking {
        database.targetRuleDao().upsert(TargetRuleEntity("example.app", "Example"))
        database.interventionEventDao().insert(event(at = 100))
        database.serviceSessionDao().insert(
            ServiceSessionEntity(
                connectedAtEpochMs = 100,
                connectedAtElapsedMs = 10,
                lastHeartbeatAtEpochMs = 100,
                lastHeartbeatAtElapsedMs = 10,
            ),
        )

        database.targetRuleDao().deleteAll()
        database.interventionEventDao().deleteAll()
        database.serviceSessionDao().deleteAll()

        assertEquals(emptyList<TargetRuleEntity>(), database.targetRuleDao().observeAll().first())
        assertEquals(
            0,
            database.interventionEventDao().observeStatsBetween(
                Long.MIN_VALUE,
                Long.MAX_VALUE,
                InterventionOutcome.EXITED.name,
                InterventionOutcome.CONTINUED.name,
                InterventionOutcome.DISMISSED.name,
                InterventionOutcome.DISPLAY_FAILED.name,
            ).first().total,
        )
        assertEquals(emptyList<ServiceSessionEntity>(), database.serviceSessionDao().getAllForExport())

        val recoveryHealthStore = DatabaseHealthStore(
            ApplicationProvider.getApplicationContext(),
            "recovery-failure-${System.nanoTime()}",
        )
        var recoveryAttempts = 0
        val failingRecoveryMonitor = DatabaseHealthMonitor(database, recoveryHealthStore) {
            recoveryAttempts += 1
            error("synthetic recovery failure")
        }
        assertFalse(failingRecoveryMonitor.checkDatabase())
        assertEquals(1, recoveryAttempts)
        assertEquals(
            DatabaseFailureReason.RECOVERY_FAILED,
            (recoveryHealthStore.state.value as DatabaseHealthState.Unavailable).reason,
        )
        recoveryHealthStore.markHealthy()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "preserve-corruption-${System.nanoTime()}.db"
        val healthStore = DatabaseHealthStore(context, "preserve-corruption-${System.nanoTime()}")
        var onDiskDatabase: PauseDatabase? = null
        try {
            onDiskDatabase = Room.databaseBuilder(context, PauseDatabase::class.java, databaseName)
                .openHelperFactory(PreservingCorruptionOpenHelperFactory(healthStore))
                .allowMainThreadQueries()
                .build()
            onDiskDatabase.targetRuleDao().upsert(TargetRuleEntity("preserved.app", "Preserved"))
            onDiskDatabase.close()
            onDiskDatabase = null

            val databaseFile = context.getDatabasePath(databaseName)
            val corruptBytes = "pausecn-corruption-preservation".toByteArray()
            databaseFile.writeBytes(corruptBytes)

            onDiskDatabase = Room.databaseBuilder(context, PauseDatabase::class.java, databaseName)
                .openHelperFactory(PreservingCorruptionOpenHelperFactory(healthStore))
                .allowMainThreadQueries()
                .build()
            val openFailure = runCatching {
                onDiskDatabase.openHelper.writableDatabase
            }.exceptionOrNull()

            assertNotNull(openFailure)
            assertTrue(healthStore.state.value is DatabaseHealthState.Unavailable)
            assertEquals(
                DatabaseFailureReason.CORRUPTION_DETECTED,
                (healthStore.state.value as DatabaseHealthState.Unavailable).reason,
            )
            assertArrayEquals(corruptBytes, databaseFile.readBytes())
        } finally {
            runCatching { onDiskDatabase?.close() }
            context.deleteDatabase(databaseName)
            healthStore.markHealthy()
        }
    }

    @Test
    fun exportQueriesAndVersionOneMigrationPreserveStableData() = runBlocking {
        database.targetRuleDao().upsert(TargetRuleEntity("z.app", "Zulu\u202e\nName", createdAtEpochMs = 2))
        database.targetRuleDao().upsert(TargetRuleEntity("a.app", "Alpha", createdAtEpochMs = 1))
        database.interventionEventDao().insert(
            event(at = 300, purpose = "later", appLabel = "History\u202e\nName"),
        )
        database.interventionEventDao().insert(event(at = 100, purpose = "earlier"))

        assertEquals(listOf("a.app", "z.app"), database.targetRuleDao().getAllForExport().map { it.packageName })
        assertEquals(listOf("earlier", "later"), database.interventionEventDao().getAllForExport().map { it.purpose })
        val repository = PauseRepository(ApplicationProvider.getApplicationContext(), database)
        assertEquals("Zulu Name", repository.targets.first().last().label)
        assertEquals("History Name", repository.recentEvents.first().first().appLabel)
        val safeExport = repository.loadExportData()
        assertEquals("Zulu Name", safeExport.targets.last().label)
        assertEquals("History Name", safeExport.events.last().appLabel)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacyName = "migration-1-2-${System.nanoTime()}.db"
        val legacyFile = context.getDatabasePath(legacyName)
        legacyFile.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(
                legacyFile,
                null as SQLiteDatabase.CursorFactory?,
            ).use { legacy ->
                legacy.execSQL(
                    """CREATE TABLE target_rules (
                        packageName TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL
                    )""".trimIndent(),
                )
                legacy.execSQL(
                    """CREATE TABLE intervention_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        appLabel TEXT NOT NULL,
                        occurredAtEpochMs INTEGER NOT NULL,
                        outcome TEXT NOT NULL,
                        purpose TEXT
                    )""".trimIndent(),
                )
                legacy.execSQL(
                    "CREATE INDEX index_intervention_events_occurredAtEpochMs " +
                        "ON intervention_events (occurredAtEpochMs)",
                )
                legacy.execSQL(
                    "CREATE INDEX index_intervention_events_packageName ON intervention_events (packageName)",
                )
                legacy.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                legacy.execSQL(
                    "INSERT INTO room_master_table (id, identity_hash) VALUES(42, '85e5ba1876f6b1d397379a9c745f59a7')",
                )
                legacy.execSQL(
                    "INSERT INTO target_rules VALUES('legacy.app', 'Legacy', 1, 10)",
                )
                legacy.execSQL(
                    "INSERT INTO intervention_events " +
                        "(packageName, appLabel, occurredAtEpochMs, outcome, purpose) " +
                        "VALUES('legacy.app', 'Legacy', 20, 'CONTINUED', '升级保留')",
                )
                legacy.version = 1
            }

            val migrated = Room.databaseBuilder(context, PauseDatabase::class.java, legacyName)
                .addMigrations(*PAUSE_DATABASE_MIGRATIONS)
                .build()
            try {
                assertEquals("legacy.app", migrated.targetRuleDao().getAllForExport().single().packageName)
                val migratedEvent = migrated.interventionEventDao().getAllForExport().single()
                assertEquals("升级保留", migratedEvent.purpose)
                assertEquals(null, migratedEvent.triggerLatencyMs)
                val sessionId = migrated.serviceSessionDao().insert(
                    ServiceSessionEntity(
                        connectedAtEpochMs = 30,
                        connectedAtElapsedMs = 3,
                        lastHeartbeatAtEpochMs = 30,
                        lastHeartbeatAtElapsedMs = 3,
                    ),
                )
                assertTrue(sessionId > 0)
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(legacyName)
        }
    }

    @Test
    fun versionTwoMigrationAddsAiTablesAndPreservesTargetsAndHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacyName = "migration-2-3-${System.nanoTime()}.db"
        val legacyFile = context.getDatabasePath(legacyName)
        legacyFile.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(
                legacyFile,
                null as SQLiteDatabase.CursorFactory?,
            ).use { legacy ->
                legacy.execSQL(
                    """CREATE TABLE target_rules (
                        packageName TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL
                    )""".trimIndent(),
                )
                legacy.execSQL(
                    """CREATE TABLE intervention_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        appLabel TEXT NOT NULL,
                        occurredAtEpochMs INTEGER NOT NULL,
                        outcome TEXT NOT NULL,
                        purpose TEXT,
                        triggerLatencyMs INTEGER
                    )""".trimIndent(),
                )
                legacy.execSQL(
                    "CREATE INDEX index_intervention_events_occurredAtEpochMs " +
                        "ON intervention_events (occurredAtEpochMs)",
                )
                legacy.execSQL(
                    "CREATE INDEX index_intervention_events_packageName ON intervention_events (packageName)",
                )
                legacy.execSQL(
                    """CREATE TABLE service_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        connectedAtEpochMs INTEGER NOT NULL,
                        connectedAtElapsedMs INTEGER NOT NULL,
                        lastHeartbeatAtEpochMs INTEGER NOT NULL,
                        lastHeartbeatAtElapsedMs INTEGER NOT NULL,
                        heartbeatCount INTEGER NOT NULL,
                        maxHeartbeatGapMs INTEGER NOT NULL
                    )""".trimIndent(),
                )
                legacy.execSQL(
                    "CREATE INDEX index_service_sessions_connectedAtEpochMs " +
                        "ON service_sessions (connectedAtEpochMs)",
                )
                legacy.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                legacy.execSQL(
                    "INSERT INTO room_master_table (id, identity_hash) " +
                        "VALUES(42, 'cd24479f4522e78bb97d2dac903bc828')",
                )
                legacy.execSQL("INSERT INTO target_rules VALUES('legacy.app', 'Legacy', 1, 10)")
                legacy.execSQL(
                    "INSERT INTO intervention_events " +
                        "(packageName, appLabel, occurredAtEpochMs, outcome, purpose, triggerLatencyMs) " +
                        "VALUES('legacy.app', 'Legacy', 20, 'CONTINUED', '迁移保留', 25)",
                )
                legacy.execSQL(
                    "INSERT INTO service_sessions " +
                        "(connectedAtEpochMs, connectedAtElapsedMs, lastHeartbeatAtEpochMs, " +
                        "lastHeartbeatAtElapsedMs, heartbeatCount, maxHeartbeatGapMs) " +
                        "VALUES(30, 3, 40, 13, 1, 10)",
                )
                legacy.version = 2
            }

            val migrated = Room.databaseBuilder(context, PauseDatabase::class.java, legacyName)
                .addMigrations(*PAUSE_DATABASE_MIGRATIONS)
                .build()
            try {
                assertEquals("legacy.app", migrated.targetRuleDao().getAllForExport().single().packageName)
                val event = migrated.interventionEventDao().getAllForExport().single()
                assertEquals("迁移保留", event.purpose)
                assertEquals(25L, event.triggerLatencyMs)
                assertEquals(1, migrated.serviceSessionDao().getAllForExport().single().heartbeatCount)

                migrated.aiDao().save(app.pausecn.ai.AiConfig(style = "迁移后可写"))
                assertEquals("迁移后可写", migrated.aiDao().config()?.style)
                assertTrue(migrated.aiDao().phrases().isEmpty())
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(legacyName)
        }
    }

    @Test
    fun versionThreeMigrationAddsProfileAndPreservesExistingData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacyName = "migration-3-4-${System.nanoTime()}.db"
        val legacyFile = context.getDatabasePath(legacyName)
        legacyFile.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(legacyFile, null as SQLiteDatabase.CursorFactory?).use { legacy ->
                legacy.execSQL("CREATE TABLE target_rules (packageName TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, enabled INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL)")
                legacy.execSQL("CREATE TABLE intervention_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageName TEXT NOT NULL, appLabel TEXT NOT NULL, occurredAtEpochMs INTEGER NOT NULL, outcome TEXT NOT NULL, purpose TEXT, triggerLatencyMs INTEGER)")
                legacy.execSQL("CREATE INDEX index_intervention_events_occurredAtEpochMs ON intervention_events (occurredAtEpochMs)")
                legacy.execSQL("CREATE INDEX index_intervention_events_packageName ON intervention_events (packageName)")
                legacy.execSQL("CREATE TABLE service_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, connectedAtEpochMs INTEGER NOT NULL, connectedAtElapsedMs INTEGER NOT NULL, lastHeartbeatAtEpochMs INTEGER NOT NULL, lastHeartbeatAtElapsedMs INTEGER NOT NULL, heartbeatCount INTEGER NOT NULL, maxHeartbeatGapMs INTEGER NOT NULL)")
                legacy.execSQL("CREATE INDEX index_service_sessions_connectedAtEpochMs ON service_sessions (connectedAtEpochMs)")
                legacy.execSQL("CREATE TABLE ai_config (id INTEGER NOT NULL PRIMARY KEY, instanceId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL, styleVersion INTEGER NOT NULL, enabled INTEGER NOT NULL, style TEXT NOT NULL, model TEXT NOT NULL, manualPhrase TEXT NOT NULL)")
                legacy.execSQL("CREATE TABLE ai_phrases (id TEXT NOT NULL PRIMARY KEY, scene TEXT NOT NULL, text TEXT NOT NULL, styleVersion INTEGER NOT NULL, approved INTEGER NOT NULL)")
                legacy.execSQL("CREATE TABLE ai_requests (id TEXT NOT NULL PRIMARY KEY, day TEXT NOT NULL, startedAt INTEGER NOT NULL, status TEXT NOT NULL, totalTokens INTEGER NOT NULL)")
                legacy.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                legacy.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES(42, '9087f0e5c4f4b1cd9770d920fabca4c3')")
                legacy.execSQL("INSERT INTO target_rules VALUES('legacy.app', 'Legacy', 1, 10)")
                legacy.execSQL("INSERT INTO intervention_events (packageName, appLabel, occurredAtEpochMs, outcome, purpose, triggerLatencyMs) VALUES('legacy.app', 'Legacy', 20, 'CONTINUED', '画像迁移保留', 25)")
                legacy.execSQL("INSERT INTO service_sessions (connectedAtEpochMs, connectedAtElapsedMs, lastHeartbeatAtEpochMs, lastHeartbeatAtElapsedMs, heartbeatCount, maxHeartbeatGapMs) VALUES(30, 3, 40, 13, 1, 10)")
                legacy.execSQL("INSERT INTO ai_config VALUES(1, 'legacy-instance', 2, 3, 1, '旧风格', 'deepseek-v4-flash', '旧短句')")
                legacy.execSQL("INSERT INTO ai_phrases VALUES('phrase-1', 'ORDINARY', '迁移保留短句', 3, 1)")
                legacy.execSQL("INSERT INTO ai_requests VALUES('request-1', '2026-09-08', 50, 'SUCCEEDED', 7)")
                legacy.version = 3
            }

            val migrated = Room.databaseBuilder(context, PauseDatabase::class.java, legacyName)
                .addMigrations(*PAUSE_DATABASE_MIGRATIONS)
                .build()
            try {
                assertEquals("legacy.app", migrated.targetRuleDao().getAllForExport().single().packageName)
                assertEquals("画像迁移保留", migrated.interventionEventDao().getAllForExport().single().purpose)
                assertEquals(1, migrated.serviceSessionDao().getAllForExport().single().heartbeatCount)
                assertEquals("旧风格", migrated.aiDao().config()?.style)
                assertEquals("迁移保留短句", migrated.aiDao().phrases().single().text)
                assertEquals(7, migrated.aiDao().tokens("2026-09-08"))
                assertEquals(null, migrated.aiDao().profile())

                val profile = app.pausecn.ai.UserProfile(goal = "迁移后可写", updatedAtEpochMs = 60)
                migrated.aiDao().saveProfile(profile)
                assertEquals(profile, migrated.aiDao().profile())
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(legacyName)
        }
    }

    private fun event(
        at: Long,
        purpose: String? = null,
        appLabel: String = "Example",
    ) = InterventionEventEntity(
        packageName = "example.app",
        appLabel = appLabel,
        occurredAtEpochMs = at,
        outcome = InterventionOutcome.CONTINUED.name,
        purpose = purpose,
    )
}
