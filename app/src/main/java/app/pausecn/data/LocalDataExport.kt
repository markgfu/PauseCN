package app.pausecn.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class LocalDataExportSnapshot(
    val formatVersion: Int = 6,
    val exportedAtEpochMs: Long,
    val appVersion: String,
    val settings: SettingsSnapshot,
    val targets: List<TargetRuleEntity>,
    val events: List<InterventionEventEntity>,
    val serviceSessions: List<ServiceSessionEntity> = emptyList(),
    val options: LocalExportOptions = LocalExportOptions(),
    val ai: AiExportData? = null,
    val usage: app.pausecn.usage.UsageExportData? = null,
    val reports: app.pausecn.reports.ReportExportData? = null,
    val appCategories: List<AppCategoryRow> = emptyList(),
)

class LocalDataExporter(
    private val context: Context,
    private val repository: PauseRepository,
    private val settingsStore: SettingsStore,
) {
    suspend fun export(uri: Uri, passphrase: CharArray, appVersion: String, options: LocalExportOptions = LocalExportOptions()) = withContext(Dispatchers.IO) {
        var openedOutput = false
        try {
            require(hasMinimumExportPassphraseLength(passphrase)) {
                "导出密码至少需要 12 个 Unicode 码点，且代理对必须完整"
            }
            val settings = settingsStore.settings.first()
            val capture = repository.captureExport(options, settings.historyRetentionDays)
            val exportData = capture.data
            val validUntil = minOf(exportData.ai?.validUntil() ?: Long.MAX_VALUE,
                exportData.usage?.validUntil ?: Long.MAX_VALUE, exportData.reports?.validUntil ?: Long.MAX_VALUE)
            val snapshot = LocalDataExportSnapshot(
                exportedAtEpochMs = System.currentTimeMillis(),
                appVersion = appVersion,
                settings = settings,
                targets = exportData.targets,
                events = exportData.events,
                serviceSessions = exportData.serviceSessions,
                options = options, ai = exportData.ai, usage = exportData.usage, reports = exportData.reports,
                appCategories = exportData.appCategories,
            )
            val encrypted = encryptLocalDataExport(encodeLocalDataExport(snapshot), passphrase)
            val expectedBytes = encrypted.toByteArray(StandardCharsets.UTF_8)
            check(settingsStore.settings.first().historyRetentionDays == settings.historyRetentionDays) { "导出期间保留期已改变" }
            repository.validateExport(capture, validUntil)
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("无法打开所选导出文件")
            openedOutput = true
            output.buffered().use { stream ->
                stream.write(expectedBytes)
                stream.flush()
            }

            val persistedBytes = context.contentResolver.openInputStream(uri)
                ?.buffered()
                ?.use { it.readBytes() }
                ?: error("无法重新打开所选导出文件进行校验")
            check(MessageDigest.isEqual(expectedBytes, persistedBytes)) {
                "导出文件写入后校验失败"
            }
            check(settingsStore.settings.first().historyRetentionDays == settings.historyRetentionDays) { "导出期间保留期已改变" }
            repository.validateExport(capture, validUntil)
        } catch (error: Exception) {
            if (openedOutput) {
                // Only this call's output URI, never a directory or a previously unrelated file.
                // Providers may already have copied data elsewhere; external copies cannot be recalled.
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.flush() } }
            }
            throw error
        } finally {
            passphrase.fill('\u0000')
        }
    }

    companion object {
        const val MIN_PASSPHRASE_LENGTH = 12
    }
}

internal fun encodeLocalDataExport(snapshot: LocalDataExportSnapshot): String = buildString {
    append('{')
    append("\"format\":\"app.pausecn.local-data\",")
    append("\"formatVersion\":").append(snapshot.formatVersion).append(',')
    append("\"exportedAtEpochMs\":").append(snapshot.exportedAtEpochMs).append(',')
    append("\"appVersion\":")
    appendJsonString(snapshot.appVersion)
    append(',')
    append("\"settings\":{")
    append("\"disclosureAccepted\":").append(snapshot.settings.disclosureAccepted).append(',')
    append("\"ageEligibilityConfirmed\":").append(snapshot.settings.ageEligibilityConfirmed).append(',')
    append("\"onboardingPreviewCompleted\":").append(snapshot.settings.onboardingPreviewCompleted).append(',')
    append("\"scheduleEnabled\":").append(snapshot.settings.schedule.enabled).append(',')
    append("\"scheduleStartMinutes\":").append(snapshot.settings.schedule.startMinutes).append(',')
    append("\"scheduleEndMinutes\":").append(snapshot.settings.schedule.endMinutes).append(',')
    append("\"activeDaysMask\":").append(snapshot.settings.schedule.activeDaysMask).append(',')
    append("\"globallyPausedUntilEpochMs\":").append(snapshot.settings.globallyPausedUntilEpochMs).append(',')
    append("\"interventionSeconds\":").append(snapshot.settings.interventionSeconds).append(',')
    append("\"temporaryPassMinutes\":").append(snapshot.settings.temporaryPassMinutes).append(',')
    append("\"historyRetentionDays\":").append(snapshot.settings.historyRetentionDays)
    append("},")
    append("\"targets\":[")
    snapshot.targets.forEachIndexed { index, target ->
        if (index > 0) append(',')
        append('{')
        append("\"packageName\":").also { appendJsonString(target.packageName) }.append(',')
        append("\"label\":").also { appendJsonString(target.label) }.append(',')
        append("\"enabled\":").append(target.enabled).append(',')
        append("\"createdAtEpochMs\":").append(target.createdAtEpochMs)
        append('}')
    }
    append("],")
    append("\"events\":[")
    snapshot.events.forEachIndexed { index, event ->
        if (index > 0) append(',')
        append('{')
        append("\"id\":").append(event.id).append(',')
        append("\"packageName\":").also { appendJsonString(event.packageName) }.append(',')
        append("\"appLabel\":").also { appendJsonString(event.appLabel) }.append(',')
        append("\"occurredAtEpochMs\":").append(event.occurredAtEpochMs).append(',')
        append("\"outcome\":").also { appendJsonString(event.outcome) }.append(',')
        append("\"purpose\":")
        event.purpose?.let(::appendJsonString) ?: append("null")
        append(',')
        append("\"triggerLatencyMs\":")
        event.triggerLatencyMs?.let { append(it) } ?: append("null")
        append('}')
    }
    append("],")
    append("\"serviceSessions\":[")
    snapshot.serviceSessions.forEachIndexed { index, session ->
        if (index > 0) append(',')
        append('{')
        append("\"id\":").append(session.id).append(',')
        append("\"connectedAtEpochMs\":").append(session.connectedAtEpochMs).append(',')
        append("\"connectedAtElapsedMs\":").append(session.connectedAtElapsedMs).append(',')
        append("\"lastHeartbeatAtEpochMs\":").append(session.lastHeartbeatAtEpochMs).append(',')
        append("\"lastHeartbeatAtElapsedMs\":").append(session.lastHeartbeatAtElapsedMs).append(',')
        append("\"heartbeatCount\":").append(session.heartbeatCount).append(',')
        append("\"maxHeartbeatGapMs\":").append(session.maxHeartbeatGapMs)
        append('}')
    }
    append(']')
    if (snapshot.formatVersion >= 4) {
        append(",\"includedCategories\":[")
        (if (snapshot.formatVersion >= 6) snapshot.options else if (snapshot.formatVersion >= 5) snapshot.options.copy(appCategories = false)
            else snapshot.options.copy(reports = false, reportInterpretations = false, appCategories = false))
            .categories().forEachIndexed { index, category ->
            if (index > 0) append(',')
            appendJsonString(category)
        }
        append(']')
        if (snapshot.options.anyAi) append(",\"ai\":").append(encodeAiExport(snapshot.ai ?: AiExportData(), snapshot.options))
        if (snapshot.options.usage) append(",\"usage\":").append(app.pausecn.usage.encodeUsageExport(
            snapshot.usage ?: app.pausecn.usage.UsageExportData(), snapshot.options))
        if (snapshot.formatVersion >= 5 && snapshot.options.reports) append(",\"reports\":").append(app.pausecn.reports.encodeReportExport(
            snapshot.reports ?: app.pausecn.reports.ReportExportData(), snapshot.options.copy(appCategories = snapshot.formatVersion >= 6 && snapshot.options.appCategories)))
        if (snapshot.formatVersion >= 6 && snapshot.options.appCategories) append(",\"appCategories\":").append(org.json.JSONArray().apply {
            snapshot.appCategories.forEach { row -> put(org.json.JSONObject().put("packageName", row.packageName)
                .put("automatic", row.automatic).put("manual", row.manual ?: org.json.JSONObject.NULL).put("effective", row.effective)) }
        })
    }
    append('}')
}

internal fun encryptLocalDataExport(
    plainText: String,
    passphrase: CharArray,
    secureRandom: SecureRandom = SecureRandom(),
): String {
    require(hasMinimumExportPassphraseLength(passphrase))
    val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
    val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
    val keySpec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
    val keyBytes = try {
        SecretKeyFactory.getInstance(KDF_NAME).generateSecret(keySpec).encoded
    } finally {
        keySpec.clearPassword()
    }
    val cipherText = try {
        val cipher = Cipher.getInstance(CIPHER_NAME)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
    } finally {
        keyBytes.fill(0)
    }
    return buildString {
        append('{')
        append("\"format\":\"app.pausecn.encrypted-export\",")
        append("\"version\":1,")
        append("\"kdf\":\"").append(KDF_NAME).append("\",")
        append("\"iterations\":").append(PBKDF2_ITERATIONS).append(',')
        append("\"cipher\":\"").append(CIPHER_NAME).append("\",")
        append("\"salt\":\"").append(salt.toBase64()).append("\",")
        append("\"iv\":\"").append(iv.toBase64()).append("\",")
        append("\"ciphertext\":\"").append(cipherText.toBase64()).append("\"")
        append('}')
    }
}

internal fun hasMinimumExportPassphraseLength(value: CharSequence): Boolean =
    validUnicodeCodePointCount(value.length) { index -> value[index] }
        ?.let { it >= LocalDataExporter.MIN_PASSPHRASE_LENGTH } == true

internal fun hasMinimumExportPassphraseLength(value: CharArray): Boolean =
    validUnicodeCodePointCount(value.size) { index -> value[index] }
        ?.let { it >= LocalDataExporter.MIN_PASSPHRASE_LENGTH } == true

private inline fun validUnicodeCodePointCount(
    length: Int,
    charAt: (Int) -> Char,
): Int? {
    var index = 0
    var count = 0
    while (index < length) {
        val current = charAt(index)
        when {
            Character.isHighSurrogate(current) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(charAt(index + 1))) return null
                index += 2
            }
            Character.isLowSurrogate(current) -> return null
            else -> index += 1
        }
        count += 1
    }
    return count
}

internal fun decryptLocalDataExport(envelope: String, passphrase: CharArray): String {
    fun field(name: String): String {
        val marker = "\"$name\":\""
        val start = envelope.indexOf(marker).takeIf { it >= 0 } ?: error("导出文件缺少 $name")
        val valueStart = start + marker.length
        val end = envelope.indexOf('"', valueStart).takeIf { it >= 0 } ?: error("导出文件中的 $name 无效")
        return envelope.substring(valueStart, end)
    }
    val iterationsMarker = "\"iterations\":"
    val iterationsStart = envelope.indexOf(iterationsMarker).takeIf { it >= 0 }
        ?: error("导出文件缺少 iterations")
    val valueStart = iterationsStart + iterationsMarker.length
    val valueEnd = envelope.indexOf(',', valueStart).takeIf { it >= 0 } ?: error("导出文件中的 iterations 无效")
    val iterations = envelope.substring(valueStart, valueEnd).toInt()
    require(iterations in 100_000..1_000_000) { "不支持的密钥派生参数" }

    val salt = field("salt").fromBase64()
    val iv = field("iv").fromBase64()
    val cipherText = field("ciphertext").fromBase64()
    val keySpec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
    val keyBytes = try {
        SecretKeyFactory.getInstance(KDF_NAME).generateSecret(keySpec).encoded
    } finally {
        keySpec.clearPassword()
    }
    return try {
        val cipher = Cipher.getInstance(CIPHER_NAME)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
    } finally {
        keyBytes.fill(0)
    }
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
private fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)

private const val KDF_NAME = "PBKDF2WithHmacSHA256"
private const val CIPHER_NAME = "AES/GCM/NoPadding"
private const val PBKDF2_ITERATIONS = 600_000
private const val KEY_BITS = 256
private const val SALT_BYTES = 16
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
