package app.pausecn.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.datastore.dataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDataExporterTest {
    @Test
    fun exporterWritesDecryptableCompleteFileThroughContentResolver() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PauseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = PauseRepository(context, database)
        val dataStoreName = "export-settings-${System.nanoTime()}"
        val settingsStore = SettingsStore(context, dataStoreName)
        repository.setTarget(InstalledApp("example.export", "导出示例"), true)
        repository.recordIntervention(
            packageName = "example.export",
            appLabel = "导出示例",
            outcome = InterventionOutcome.CONTINUED,
            purpose = "完成测试",
            triggerLatencyMs = 456,
        )
        val serviceSessionId = repository.beginServiceSession(nowEpochMs = 1_000, nowElapsedMs = 100)
        repository.recordServiceHeartbeat(serviceSessionId, nowEpochMs = 2_000, nowElapsedMs = 1_100)
        val uri = requireNotNull(
            context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "pausecn-export-test-${System.nanoTime()}.pausecn")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/PauseCnTests")
                },
            ),
        )

        try {
            val exporter = LocalDataExporter(context, repository, settingsStore)
            val rejectedPassphrase = "12345678901".toCharArray()
            val rejection = runCatching {
                exporter.export(
                    uri = uri,
                    passphrase = rejectedPassphrase,
                    appVersion = "test",
                )
            }
            assertTrue(rejection.exceptionOrNull() is IllegalArgumentException)
            assertTrue(rejectedPassphrase.all { it == '\u0000' })

            val acceptedPassphrase = "test-password".toCharArray()
            exporter.export(
                uri = uri,
                passphrase = acceptedPassphrase,
                appVersion = "test",
            )
            assertTrue(acceptedPassphrase.all { it == '\u0000' })

            val envelope = requireNotNull(context.contentResolver.openInputStream(uri))
                .bufferedReader()
                .use { it.readText() }
            assertTrue(envelope.startsWith("{\"format\":\"app.pausecn.encrypted-export\""))
            val payload = decryptLocalDataExport(envelope, "test-password".toCharArray())
            assertTrue(payload.contains("\"packageName\":\"example.export\""))
            assertTrue(payload.contains("\"purpose\":\"完成测试\""))
            assertTrue(payload.contains("\"triggerLatencyMs\":456"))
            assertTrue(payload.contains("\"connectedAtEpochMs\":1000"))
            assertTrue(payload.contains("\"lastHeartbeatAtEpochMs\":2000"))
            assertTrue(payload.contains("\"lastHeartbeatAtElapsedMs\":1100"))
            assertTrue(payload.contains("\"heartbeatCount\":1"))
            assertTrue(payload.contains("\"maxHeartbeatGapMs\":1000"))
            assertEquals(1, database.targetRuleDao().getAllForExport().size)
        } finally {
            context.contentResolver.delete(uri, null, null)
            settingsStore.close()
            context.dataStoreFile("$dataStoreName.preferences_pb").delete()
            database.close()
        }
    }
}
