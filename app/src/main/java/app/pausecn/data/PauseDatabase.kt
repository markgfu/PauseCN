package app.pausecn.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.pausecn.ai.AiConfig
import app.pausecn.ai.AiPhrase
import app.pausecn.ai.AiRequestRecord
import app.pausecn.ai.AiDao
import app.pausecn.ai.UserProfile
import app.pausecn.ai.PersonalizationConfig
import app.pausecn.ai.PersonalizedPhrase
import app.pausecn.ai.ConversationConfig
import app.pausecn.ai.ConversationMessage
import app.pausecn.ai.UserMemory
import app.pausecn.ai.PhraseFeedback
import app.pausecn.ai.ConversationDao

@Database(
    entities = [TargetRuleEntity::class, InterventionEventEntity::class, ServiceSessionEntity::class,
        AiConfig::class, AiPhrase::class, AiRequestRecord::class, UserProfile::class,
        PersonalizationConfig::class, PersonalizedPhrase::class, ConversationConfig::class,
        ConversationMessage::class, UserMemory::class, PhraseFeedback::class,
        app.pausecn.usage.UsageConfig::class, app.pausecn.usage.UsagePeriod::class,
        app.pausecn.usage.UsageHourEntity::class, app.pausecn.usage.PauseDisplay::class,
        app.pausecn.reports.ReportConfig::class, app.pausecn.reports.ReportCacheRow::class,
        app.pausecn.reports.AutomaticReportState::class, AppCategoryRow::class, AppCategorySettings::class],
    version = 12,
    exportSchema = true,
)
abstract class PauseDatabase : RoomDatabase() {
    val personalizationGuard = app.pausecn.ai.PersonalizationGuard()
    val displayGeneration = java.util.concurrent.atomic.AtomicLong()
    abstract fun targetRuleDao(): TargetRuleDao
    abstract fun interventionEventDao(): InterventionEventDao
    abstract fun serviceSessionDao(): ServiceSessionDao
    abstract fun aiDao(): AiDao
    abstract fun conversationDao(): ConversationDao
    abstract fun usageDao(): app.pausecn.usage.UsageDao
    abstract fun reportDao(): app.pausecn.reports.ReportDao
    abstract fun automaticReportDao(): app.pausecn.reports.AutomaticReportDao
    abstract fun appCategoryDao(): AppCategoryDao
}

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE intervention_events ADD COLUMN triggerLatencyMs INTEGER")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS service_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                connectedAtEpochMs INTEGER NOT NULL,
                connectedAtElapsedMs INTEGER NOT NULL,
                lastHeartbeatAtEpochMs INTEGER NOT NULL,
                lastHeartbeatAtElapsedMs INTEGER NOT NULL,
                heartbeatCount INTEGER NOT NULL,
                maxHeartbeatGapMs INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_service_sessions_connectedAtEpochMs " +
                "ON service_sessions (connectedAtEpochMs)",
        )
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS ai_config (
            id INTEGER NOT NULL PRIMARY KEY, instanceId TEXT NOT NULL, privacyEpoch INTEGER NOT NULL,
            styleVersion INTEGER NOT NULL, enabled INTEGER NOT NULL, style TEXT NOT NULL,
            model TEXT NOT NULL, manualPhrase TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS ai_phrases (
            id TEXT NOT NULL PRIMARY KEY, scene TEXT NOT NULL, text TEXT NOT NULL,
            styleVersion INTEGER NOT NULL, approved INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS ai_requests (
            id TEXT NOT NULL PRIMARY KEY, day TEXT NOT NULL, startedAt INTEGER NOT NULL,
            status TEXT NOT NULL, totalTokens INTEGER NOT NULL)""")
    }
}

internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS user_profile (
            id INTEGER NOT NULL PRIMARY KEY, goal TEXT NOT NULL, preferences TEXT NOT NULL,
            revision TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS personalization_config (
            id INTEGER NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, useProfile INTEGER NOT NULL,
            useReasons INTEGER NOT NULL, epoch INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS personalized_phrases (
            id TEXT NOT NULL PRIMARY KEY, packageName TEXT NOT NULL, targetCreatedAt INTEGER NOT NULL,
            scene TEXT NOT NULL, text TEXT NOT NULL, kind TEXT NOT NULL, instanceId TEXT NOT NULL,
            privacyEpoch INTEGER NOT NULL, personalizationEpoch INTEGER NOT NULL, styleVersion INTEGER NOT NULL,
            profileRevision TEXT NOT NULL, fingerprint TEXT NOT NULL, createdAt INTEGER NOT NULL,
            expiresAt INTEGER NOT NULL, clockOffset INTEGER NOT NULL, consumed INTEGER NOT NULL)""")
    }
}

internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS conversation_config (
            id INTEGER NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, saveMessages INTEGER NOT NULL,
            useMemories INTEGER NOT NULL, epoch INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS conversation_messages (
            id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, scopePackage TEXT NOT NULL,
            role TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL,
            expiresAt INTEGER NOT NULL, aiEligible INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS user_memories (
            id TEXT NOT NULL PRIMARY KEY, sourceMessageId TEXT NOT NULL, scopePackage TEXT NOT NULL,
            text TEXT NOT NULL, kind TEXT NOT NULL, confirmed INTEGER NOT NULL, revision TEXT NOT NULL,
            createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS phrase_feedback (
            id TEXT NOT NULL PRIMARY KEY, scopePackage TEXT NOT NULL, phraseFingerprint TEXT NOT NULL,
            instruction TEXT NOT NULL, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL)""")
    }
}

internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_memories ADD COLUMN independent INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE personalized_phrases ADD COLUMN contextJson TEXT NOT NULL DEFAULT ''")
    }
}

internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS usage_config (
            id INTEGER NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, revision TEXT NOT NULL,
            floor INTEGER NOT NULL, permissionObserved INTEGER NOT NULL, lastChecked INTEGER NOT NULL,
            lastSuccess INTEGER NOT NULL, status TEXT NOT NULL, lastElapsed INTEGER NOT NULL,
            lastBootId TEXT NOT NULL, lastZoneId TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS usage_periods (
            id TEXT NOT NULL PRIMARY KEY, packageName TEXT NOT NULL, start INTEGER NOT NULL, `end` INTEGER)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS usage_hours (
            periodId TEXT NOT NULL, packageName TEXT NOT NULL, zoneId TEXT NOT NULL,
            start INTEGER NOT NULL, `end` INTEGER NOT NULL, algorithm INTEGER NOT NULL,
            localDate TEXT NOT NULL, localHour INTEGER NOT NULL, evaluatedFrom INTEGER NOT NULL,
            evaluatedTo INTEGER NOT NULL, foregroundMs INTEGER, unknownMs INTEGER NOT NULL,
            completeness TEXT NOT NULL, limitations TEXT NOT NULL, capturedAt INTEGER NOT NULL,
            PRIMARY KEY(periodId, zoneId, start, algorithm))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS pause_displays (
            id TEXT NOT NULL PRIMARY KEY, packageName TEXT NOT NULL, startedAt INTEGER NOT NULL,
            startedElapsed INTEGER NOT NULL, bootId TEXT NOT NULL, zoneId TEXT NOT NULL,
            endedAt INTEGER, endedElapsed INTEGER, status TEXT NOT NULL)""")
    }
}

internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS report_config (
            id INTEGER NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, useUsage INTEGER NOT NULL,
            useProfile INTEGER NOT NULL, useMemories INTEGER NOT NULL, useReasons INTEGER NOT NULL, epoch INTEGER NOT NULL)""")
    }
}

internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS report_cache (
            slot TEXT NOT NULL PRIMARY KEY, fingerprint TEXT NOT NULL, snapshotJson TEXT NOT NULL,
            createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, interpretationJson TEXT NOT NULL,
            aiTag TEXT NOT NULL, consentEpoch INTEGER NOT NULL, backgroundHash TEXT NOT NULL)""")
        app.pausecn.reports.installReportCacheTriggers(db)
    }
}

internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE report_config ADD COLUMN useSettings INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE report_cache ADD COLUMN ruleBasisJson TEXT NOT NULL DEFAULT ''")
    }
}

internal val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS automatic_report (
            id INTEGER NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, epoch INTEGER NOT NULL,
            lastDate TEXT NOT NULL, lastZone TEXT NOT NULL, claimId TEXT NOT NULL, status TEXT NOT NULL, updatedAt INTEGER NOT NULL)""")
    }
}

internal val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS app_categories (packageName TEXT NOT NULL PRIMARY KEY, automatic TEXT NOT NULL, manual TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS app_category_settings (id INTEGER NOT NULL PRIMARY KEY, sendToAi INTEGER NOT NULL, revision INTEGER NOT NULL)")
        // Old report snapshots have no category basis. Only disposable report caches are cleared.
        db.execSQL("DELETE FROM report_cache")
    }
}

internal val PAUSE_DATABASE_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
