package app.pausecn.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReasonMemoryTest {
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
    fun memoriesAggregateOnlyCompletedContinuesAndRemainIndependentPerApp() = runBlocking {
        val dao = database.interventionEventDao()
        dao.insert(event(APP_A, "应用甲", 100, InterventionOutcome.CONTINUED, "回消息"))
        dao.insert(event(APP_A, "应用甲", 200, InterventionOutcome.CONTINUED, "回消息"))
        dao.insert(event(APP_A, "应用甲", 250, InterventionOutcome.CONTINUED, "查会议地点"))
        dao.insert(event(APP_A, "应用甲", 300, InterventionOutcome.SHOWN, "未完成理由"))
        dao.insert(event(APP_A, "应用甲", 350, InterventionOutcome.EXITED, "退出理由"))
        dao.insert(event(APP_B, "应用乙", 400, InterventionOutcome.CONTINUED, "回消息"))
        dao.insert(event(APP_A, "应用甲", 50, InterventionOutcome.CONTINUED, "范围外"))

        val memories = dao.observeReasonMemories(since = 100, until = 500).first()
        val byAppAndText = memories.associateBy { it.packageName to it.text }

        assertEquals(3, memories.size)
        assertEquals(ReasonMemory(APP_A, "应用甲", "回消息", uses = 2, lastUsedAt = 200), byAppAndText[APP_A to "回消息"])
        assertEquals(ReasonMemory(APP_A, "应用甲", "查会议地点", uses = 1, lastUsedAt = 250), byAppAndText[APP_A to "查会议地点"])
        assertEquals(ReasonMemory(APP_B, "应用乙", "回消息", uses = 1, lastUsedAt = 400), byAppAndText[APP_B to "回消息"])
        assertFalse(memories.any { it.text in setOf("未完成理由", "退出理由", "范围外") })
    }

    @Test
    fun forgettingClearsOnlySourcePurposesWhilePreservingOutcomesAndCounts() = runBlocking {
        val dao = database.interventionEventDao()
        dao.insert(event(APP_A, "应用甲", 100, InterventionOutcome.CONTINUED, "回消息"))
        dao.insert(event(APP_A, "应用甲", 200, InterventionOutcome.CONTINUED, "回消息"))
        dao.insert(event(APP_A, "应用甲", 300, InterventionOutcome.CONTINUED, "其他理由"))
        dao.insert(event(APP_B, "应用乙", 400, InterventionOutcome.CONTINUED, "回消息"))

        dao.forgetReason(APP_A, "回消息")

        val events = dao.getAllForExport()
        assertEquals(4, events.size)
        assertEquals(4, events.count { it.outcome == InterventionOutcome.CONTINUED.name })
        assertEquals(2, events.count { it.packageName == APP_A && it.purpose == null })
        assertEquals("其他理由", events.single { it.packageName == APP_A && it.occurredAtEpochMs == 300L }.purpose)
        assertEquals("回消息", events.single { it.packageName == APP_B }.purpose)

        val afterForget = dao.observeReasonMemories(since = 0, until = 500).first()
        assertNull(afterForget.firstOrNull { it.packageName == APP_A && it.text == "回消息" })
        assertEquals(1, afterForget.single { it.packageName == APP_A }.uses)
        assertEquals(1, afterForget.single { it.packageName == APP_B }.uses)
    }

    private fun event(
        packageName: String,
        appLabel: String,
        at: Long,
        outcome: InterventionOutcome,
        purpose: String?,
    ) = InterventionEventEntity(
        packageName = packageName,
        appLabel = appLabel,
        occurredAtEpochMs = at,
        outcome = outcome.name,
        purpose = purpose,
    )

    private companion object {
        const val APP_A = "example.alpha"
        const val APP_B = "example.beta"
    }
}
