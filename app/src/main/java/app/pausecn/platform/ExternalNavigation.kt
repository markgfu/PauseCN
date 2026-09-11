package app.pausecn.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

enum class ExternalUriOpenResult {
    OPENED,
    INVALID_HTTPS_URL,
    UNAVAILABLE,
}

fun openHttpsUri(context: Context, rawUrl: String): ExternalUriOpenResult {
    val uri = parseValidHttpsUri(rawUrl) ?: return ExternalUriOpenResult.INVALID_HTTPS_URL
    val intent = Intent(Intent.ACTION_VIEW, uri)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        ExternalUriOpenResult.OPENED
    } catch (_: ActivityNotFoundException) {
        ExternalUriOpenResult.UNAVAILABLE
    } catch (_: SecurityException) {
        ExternalUriOpenResult.UNAVAILABLE
    } catch (_: RuntimeException) {
        ExternalUriOpenResult.UNAVAILABLE
    }
}

internal fun isValidHttpsUrl(rawUrl: String): Boolean = parseValidHttpsUri(rawUrl) != null

private fun parseValidHttpsUri(rawUrl: String): Uri? {
    val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return null
    if (!uri.isHierarchical) return null
    if (uri.scheme?.lowercase(Locale.ROOT) != "https") return null
    if (uri.host.isNullOrBlank()) return null
    return uri
}
