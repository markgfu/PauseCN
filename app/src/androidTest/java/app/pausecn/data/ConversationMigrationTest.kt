package app.pausecn.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(PauseDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationFourToFivePreservesExistingRowsAndCreatesEmptyConversationTables() {
        val name = "conversation-migration-${System.nanoTime()}"
        helper.createDatabase(name, 4).use { legacy ->
            legacy.execSQL("INSERT INTO target_rules VALUES('legacy.app', 'Legacy', 1, 10)")
            legacy.execSQL("INSERT INTO ai_config VALUES(1, 'legacy-instance', 2, 3, 1, '旧风格', 'deepseek-v4-flash', '旧短句')")
            legacy.execSQL("INSERT INTO ai_phrases VALUES('phrase-1', 'ORDINARY', '迁移保留短句', 3, 1)")
        }

        helper.runMigrationsAndValidate(name, 5, true, MIGRATION_4_5).use { migrated ->
            migrated.query("SELECT packageName FROM target_rules").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy.app", cursor.getString(0))
            }
            migrated.query("SELECT style FROM ai_config").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧风格", cursor.getString(0))
            }
            migrated.query("SELECT text FROM ai_phrases").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("迁移保留短句", cursor.getString(0))
            }
            listOf("conversation_config", "conversation_messages", "user_memories", "phrase_feedback")
                .forEach { table ->
                    migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                }
        }
    }

    @Test
    fun migrationFiveToSixPreservesRowsWithoutGrantingIndependentRetention() {
        val name = "conversation-migration-5-6-${System.nanoTime()}"
        helper.createDatabase(name, 5).use { legacy ->
            legacy.execSQL("INSERT INTO conversation_messages VALUES('source', 'session', 'app.a', 'USER', '原话', 100, 1000, 1)")
            legacy.execSQL("INSERT INTO user_memories VALUES('memory', 'source', 'app.a', '旧记忆', 'GOAL', 1, 'revision', 100, 1000)")
            legacy.execSQL("INSERT INTO personalized_phrases VALUES('phrase', 'app.a', 10, 'ORDINARY', '旧提醒', 'STABLE', 'instance', 2, 3, 4, 'profile', 'fingerprint', 100, 1000, 0, 0)")
        }

        helper.runMigrationsAndValidate(name, 6, true, MIGRATION_5_6).use { migrated ->
            migrated.query("SELECT text, independent FROM user_memories WHERE id = 'memory'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧记忆", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            migrated.query("SELECT text, contextJson FROM personalized_phrases WHERE id = 'phrase'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧提醒", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
        }
    }

    @Test
    fun migrationSixToSevenPreservesExistingRowsAndAddsNoUsageAuthorization() {
        val name = "usage-migration-6-7-${System.nanoTime()}"
        helper.createDatabase(name, 6).use { legacy ->
            legacy.execSQL("INSERT INTO target_rules VALUES('legacy.app', 'Legacy', 1, 10)")
            legacy.execSQL("INSERT INTO ai_config VALUES(1, 'legacy-instance', 2, 3, 1, '旧风格', 'deepseek-v4-flash', '旧短句')")
            legacy.execSQL("INSERT INTO user_profile VALUES(1, '旧目标', '旧偏好', 'revision', 20)")
        }

        helper.runMigrationsAndValidate(name, 7, true, MIGRATION_6_7).use { migrated ->
            migrated.query("SELECT label FROM target_rules WHERE packageName = 'legacy.app'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy", cursor.getString(0))
            }
            migrated.query("SELECT goal FROM user_profile WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧目标", cursor.getString(0))
            }
            listOf("usage_config", "usage_periods", "usage_hours", "pause_displays").forEach { table ->
                migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        }
    }

    @Test
    fun migrationSevenToEightPreservesRowsAndAddsNoReportAuthorization() {
        val name = "report-migration-7-8-${System.nanoTime()}"
        helper.createDatabase(name, 7).use { legacy ->
            legacy.execSQL("INSERT INTO target_rules VALUES('legacy.app', 'Legacy', 1, 10)")
        }

        helper.runMigrationsAndValidate(name, 8, true, MIGRATION_7_8).use { migrated ->
            migrated.query("SELECT label FROM target_rules WHERE packageName = 'legacy.app'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy", cursor.getString(0))
            }
            migrated.query("SELECT COUNT(*) FROM report_config").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrationEightToNinePreservesRowsAndInvalidatesCacheOnlyOnSourceDeletion() {
        val name = "report-cache-migration-8-9-${System.nanoTime()}"
        helper.createDatabase(name, 8).use { legacy ->
            legacy.execSQL("INSERT INTO target_rules VALUES('legacy.app', 'Legacy', 1, 10)")
            legacy.execSQL("INSERT INTO intervention_events (packageName, appLabel, occurredAtEpochMs, outcome, purpose, triggerLatencyMs) VALUES('legacy.app', 'Legacy', 20, 'CONTINUED', NULL, 5)")
        }

        helper.runMigrationsAndValidate(name, 9, true, MIGRATION_8_9).use { migrated ->
            migrated.query("SELECT label FROM target_rules WHERE packageName = 'legacy.app'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy", cursor.getString(0))
            }
            migrated.execSQL("INSERT INTO report_cache VALUES('DAY|0|1|UTC', 'fingerprint', '{}', 1, 1000, '', '', -1, '')")
            migrated.execSQL("INSERT INTO intervention_events (packageName, appLabel, occurredAtEpochMs, outcome, purpose, triggerLatencyMs) VALUES('legacy.app', 'Legacy', 30, 'SHOWN', NULL, 5)")
            migrated.query("SELECT COUNT(*) FROM report_cache").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }

            migrated.execSQL("DELETE FROM intervention_events WHERE occurredAtEpochMs = 20")
            migrated.query("SELECT COUNT(*) FROM report_cache").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrationNineToTenPreservesReportRowsAndAddsNoSettingsAuthorizationOrBasis() {
        val name = "rule-suggestion-migration-9-10-${System.nanoTime()}"
        helper.createDatabase(name, 9).use { legacy ->
            legacy.execSQL("INSERT INTO target_rules VALUES('legacy.app', 'Legacy', 1, 10)")
            legacy.execSQL("INSERT INTO report_config VALUES(1, 1, 0, 0, 0, 0, 7)")
            legacy.execSQL("INSERT INTO report_cache VALUES('DAY|0|1|UTC', 'fingerprint', '{}', 1, 1000, '', '', 7, '')")
        }

        helper.runMigrationsAndValidate(name, 10, true, MIGRATION_9_10).use { migrated ->
            migrated.query("SELECT label FROM target_rules WHERE packageName = 'legacy.app'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy", cursor.getString(0))
            }
            migrated.query("SELECT enabled, useSettings FROM report_config WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
            }
            migrated.query("SELECT fingerprint, ruleBasisJson FROM report_cache WHERE slot = 'DAY|0|1|UTC'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("fingerprint", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
        }
    }

    @Test
    fun migrationTenToElevenPreservesRowsAndAddsNoAutomaticReportAuthorization() {
        val name = "automatic-report-migration-10-11-${System.nanoTime()}"
        helper.createDatabase(name, 10).use { legacy ->
            legacy.execSQL("INSERT INTO target_rules VALUES('legacy.app', 'Legacy', 1, 10)")
            legacy.execSQL("INSERT INTO report_config VALUES(1, 1, 0, 0, 0, 0, 7, 1)")
        }

        helper.runMigrationsAndValidate(name, 11, true, MIGRATION_10_11).use { migrated ->
            migrated.query("SELECT label FROM target_rules WHERE packageName = 'legacy.app'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy", cursor.getString(0))
            }
            migrated.query("SELECT useSettings FROM report_config WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM automatic_report").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
}
