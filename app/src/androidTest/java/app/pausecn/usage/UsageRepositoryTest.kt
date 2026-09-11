package app.pausecn.usage

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pausecn.data.PauseDatabase
import app.pausecn.data.TargetRuleEntity
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageRepositoryTest {
    private lateinit var database: PauseDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), PauseDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun disablingOrClearingWhileAQueryIsRunningRejectsItsRowsAndFloorBlocksOldHistory() = runBlocking {
        val clock = AtomicLong(1_000)
        val zone = { ZoneId.of("UTC") }
        val oldSignals = listOf(
            UsageSignal(2_000, UsageEventKind.RESUMED, APP, "Activity"),
            UsageSignal(3_000, UsageEventKind.PAUSED, APP, "Activity"),
        )
        database.targetRuleDao().upsert(TargetRuleEntity(APP, "应用甲"))
        val immediate = RecordingSource(oldSignals)
        val initializer = UsageRepository(database, immediate, clock::get, zone,
            elapsed = clock::get, bootId = { "boot:test" })
        initializer.setEnabled(true)
        assertEquals("EMPTY", initializer.refresh(30))

        clock.set(HOUR + 1_000)
        val disablingSource = BlockingSource(oldSignals)
        val disablingRepository = UsageRepository(database, disablingSource, clock::get, zone,
            elapsed = clock::get, bootId = { "boot:test" })
        val disabledRequest = async(Dispatchers.Default) { disablingRepository.refresh(30) }
        assertTrue(disablingSource.entered.await(5, TimeUnit.SECONDS))
        disablingRepository.setEnabled(false)
        disablingSource.release.countDown()
        assertEquals("STALE", disabledRequest.await())
        assertEquals(0, database.usageDao().hourCount())

        clock.set(2 * HOUR)
        initializer.setEnabled(true)
        initializer.refresh(30)
        clock.set(3 * HOUR)
        val clearingSource = BlockingSource(oldSignals)
        val clearingRepository = UsageRepository(database, clearingSource, clock::get, zone,
            elapsed = clock::get, bootId = { "boot:test" })
        val clearedRequest = async(Dispatchers.Default) { clearingRepository.refresh(30) }
        assertTrue(clearingSource.entered.await(5, TimeUnit.SECONDS))
        val clearAt = clock.get()
        database.withTransaction { clearUsageData(database, clearAt, reset = false) }
        clearingSource.release.countDown()
        assertEquals("STALE", clearedRequest.await())
        assertEquals(0, database.usageDao().hourCount())

        val afterClear = RecordingSource(oldSignals)
        val afterClearRepository = UsageRepository(database, afterClear, clock::get, zone,
            elapsed = clock::get, bootId = { "boot:test" })
        clock.set(clearAt + 1_000)
        afterClearRepository.refresh(30)
        clock.set(clearAt + HOUR + 1_000)
        afterClearRepository.refresh(30)

        assertTrue(afterClear.windows.isNotEmpty())
        assertTrue(afterClear.windows.all { it.start >= clearAt })
        val rows = database.usageDao().hours(Long.MIN_VALUE, Long.MAX_VALUE)
        assertTrue(rows.all { it.evaluatedFrom >= clearAt })
        assertTrue(rows.none { (it.foregroundMs ?: 0L) > 0L })
    }

    private class RecordingSource(private val signals: List<UsageSignal>) : UsageSource {
        val windows = mutableListOf<UsageSpan>()
        override fun hasPermission() = true
        override suspend fun read(window: UsageSpan, packages: Set<String>): UsageRead {
            windows += window
            return UsageRead(UsageReadStatus.AVAILABLE, window, signals)
        }
    }

    private class BlockingSource(private val signals: List<UsageSignal>) : UsageSource {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun hasPermission() = true
        override suspend fun read(window: UsageSpan, packages: Set<String>): UsageRead {
            entered.countDown()
            while (true) {
                try {
                    release.await()
                    return UsageRead(UsageReadStatus.AVAILABLE, window, signals)
                } catch (_: InterruptedException) {
                    // Deliberately ignore cancellation so the post-query revision check is exercised.
                }
            }
        }
    }

    private companion object {
        const val APP = "app.a"
        const val HOUR = 3_600_000L
    }
}
