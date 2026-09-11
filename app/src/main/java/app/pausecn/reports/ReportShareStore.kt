package app.pausecn.reports

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import app.pausecn.PauseApplication
import app.pausecn.data.AppContainer
import app.pausecn.data.DatabaseHealthState
import app.pausecn.data.SettingsHealthState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class ReportSharePreview(val uri: Uri, val bitmap: Bitmap, val expiresAt: Long)
internal class ReportShareStore(private val container: AppContainer) {
    private val context = container.applicationContext
    private val directory = File(context.cacheDir, "report_share")
    private val entries = ConcurrentHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = ReportRepository(container.database,
        usagePermission = app.pausecn.usage.AndroidUsageSource(context)::hasPermission,
        bootId = { app.pausecn.usage.UsageClock.bootId(context) })
    val changes = merge(repository.changes, container.database.personalizationGuard.revisions.map { Unit })
    private data class Entry(val file: File, val snapshot: LocalReportSnapshot, val expires: Long, val ai: ReportDelivery? = null)
    suspend fun interpretation(snapshot: LocalReportSnapshot): ReportDelivery? = container.aiRepository.restoreReport(snapshot, repository)
    init {
        directory.mkdirs()
        // Only this provider's generated files, never recurse or follow links into another directory.
        directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[0-9a-f-]{36}\\.png")) && it.canonicalFile.parentFile == directory.canonicalFile }
            ?.forEach { it.delete() }
        scope.launch { changes.collect { entries.keys.toList().forEach { key -> if (!valid(Uri.parse(key))) discard(Uri.parse(key)) } } }
    }

    private suspend fun sourceValid(entry: Entry): Boolean {
        if (container.databaseHealthStore.state.value != DatabaseHealthState.Healthy ||
            container.settingsHealthStore.state.value != SettingsHealthState.Healthy) return false
        if (container.settingsStore.settings.first().historyRetentionDays != entry.snapshot.retentionDays) return false
        val factsCurrent = container.database.personalizationGuard.readStable { container.database.withTransaction {
            val row = container.database.reportDao().cached(entry.snapshot.facts.window.slot)
            System.currentTimeMillis() < entry.expires && row != null && row.expiresAt > System.currentTimeMillis() &&
                row.createdAt == entry.snapshot.facts.createdAt && row.fingerprint == entry.snapshot.facts.fingerprint &&
                repository.isCurrentInTransaction(entry.snapshot)
        } }
        if (!factsCurrent) return false
        return entry.ai?.let { ai ->
            container.aiRepository.reportDeliveryCurrent(ai, repository) && interpretation(entry.snapshot)?.result == ai.result
        } ?: true
    }

    suspend fun valid(uri: Uri): Boolean { return try {
        val entry = entries[uri.toString()] ?: return false
        entry.file.isFile && sourceValid(entry)
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false } }

    suspend fun create(snapshot: LocalReportSnapshot, options: ReportShareOptions): ReportSharePreview = withContext(Dispatchers.IO) {
        val file = File(directory, "${UUID.randomUUID()}.png")
        val cacheDeadline = container.database.reportDao().cached(snapshot.facts.window.slot)?.expiresAt ?: 0L
        val ai = if (options.includeAi) requireNotNull(interpretation(snapshot)) { "本期暂无有效AI解读" } else null
        val entry = Entry(file, snapshot, minOf(snapshot.facts.validUntil, cacheDeadline, ai?.request?.expiresAt ?: Long.MAX_VALUE, System.currentTimeMillis() + 10 * 60_000L), ai)
        check(sourceValid(entry)) { "报告来源已变化，请返回更新报告" }
        val model = ReportShareProjection.build(snapshot.facts, options, ai?.result)
        val bitmap = withContext(Dispatchers.Default) { ReportShareRenderer.render(model) }
        var uri: Uri? = null
        try {
            currentCoroutineContext().ensureActive()
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(sourceValid(entry)) { "生成期间来源或授权已变化，图片已丢弃" }
            currentCoroutineContext().ensureActive()
            entries.keys.toList().forEach { discard(Uri.parse(it)) }
            val readyUri = FileProvider.getUriForFile(context, "${context.packageName}.report_images", file)
            uri = readyUri
            entries[readyUri.toString()] = entry
            scope.launch { delay((entry.expires - System.currentTimeMillis()).coerceAtLeast(1)); discard(readyUri) }
            ReportSharePreview(readyUri, bitmap, entry.expires)
        } catch (failure: Throwable) {
            uri?.let(::discard); file.delete(); bitmap.recycle(); throw failure
        }
    }

    fun discard(uri: Uri) {
        entries.remove(uri.toString())?.file?.delete()
        runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    suspend fun intent(preview: ReportSharePreview): Intent {
        check(valid(preview.uri)) { "分享预览已失效，请重新生成" }
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, preview.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("停一下报告", preview.uri) }
        return Intent.createChooser(send, "选择分享应用").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** Every external read is revalidated, including after the chooser opens. No write grants. */
class ReportImageProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("只允许读取分享图片")
        val store = ((context?.applicationContext as? PauseApplication) ?: throw FileNotFoundException()).container.reportShareStore
        val allowed = runCatching { runBlocking(Dispatchers.IO) { withTimeout(3000) { store.valid(uri) } } }.getOrDefault(false)
        if (!allowed) { store.discard(uri); throw FileNotFoundException("图片已过期或来源已改变") }
        val descriptor = super.openFile(uri, mode) ?: throw FileNotFoundException("图片不可用")
        val stillAllowed = runCatching { runBlocking(Dispatchers.IO) { withTimeout(3000) { store.valid(uri) } } }.getOrDefault(false)
        if (!stillAllowed) { descriptor.close(); store.discard(uri); throw FileNotFoundException("读取期间来源改变") }
        return descriptor
    }
}
