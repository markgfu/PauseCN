package app.pausecn.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AiCompletion(val content: String, val totalTokens: Int)
class AiRequestException(val publicMessage: String, val diagnosticCode: String? = null) : Exception(publicMessage)

fun interface AiTransport {
    suspend fun complete(key: String, model: String, system: String, user: String): AiCompletion
}

/** Fixed origin, no redirects, retries, logs, tools, SDK telemetry or raw server error messages. */
class DeepSeekClient : AiTransport {
    override suspend fun complete(key: String, model: String, system: String, user: String): AiCompletion =
        withTimeout(60_000) {
            require(model in MODELS && system.length + user.length <= 12_000)
            val payload = JSONObject().put("model", model)
                .put("stream", false).put("max_tokens", 2_000)
                .put("thinking", JSONObject().put("type", "disabled"))
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))).toString()
            suspendCancellableCoroutine { continuation ->
                val connectionRef = AtomicReference<HttpsURLConnection?>()
                val future = executor.submit {
                    try {
                        val connection = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpsURLConnection
                        connectionRef.set(connection)
                        if (!continuation.isActive) return@submit
                        connection.requestMethod = "POST"
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 10_000
                        connection.doOutput = true
                        connection.setRequestProperty("Authorization", "Bearer $key")
                        connection.setRequestProperty("Content-Type", "application/json")
                        val bytes = payload.toByteArray(Charsets.UTF_8)
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                        val code = connection.responseCode
                        if (code != 200) throw AiRequestException(when (code) {
                            401, 402, 403 -> "请检查 Key、账户权限或余额；不会自动重试。"
                            429 -> "服务限流，请稍后手动重试。"
                            else -> "请求未成功（HTTP $code），不会自动重试。"
                        })
                        val response = connection.inputStream.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 128 * 1024) throw AiRequestException("返回内容过大，已丢弃。")
                                output.write(buffer, 0, count)
                            }
                            val bounded = output.toByteArray()
                            JSONObject(bounded.toString(Charsets.UTF_8))
                        }
                        val choice = response.getJSONArray("choices").getJSONObject(0)
                        if (choice.getString("finish_reason") != "stop") throw AiRequestException("返回不完整，未应用任何文案。")
                        val content = choice.getJSONObject("message").getString("content")
                        if (content.isBlank() || content.length > 16_000) throw AiRequestException("返回内容为空或过长。")
                        val tokens = response.optJSONObject("usage")?.optInt("total_tokens", 0)?.coerceAtLeast(0) ?: 0
                        if (continuation.isActive) continuation.resume(AiCompletion(content, tokens))
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (error is AiRequestException) error else AiRequestException("网络或返回格式异常；可能已计费，请自行决定是否重试。"),
                        )
                    } finally {
                        connectionRef.getAndSet(null)?.disconnect()
                    }
                }
                continuation.invokeOnCancellation {
                    future.cancel(true)
                    // Disconnect away from the UI thread; old results still face repository epoch checks.
                    executor.execute { connectionRef.getAndSet(null)?.disconnect() }
                }
            }
        }

    companion object {
        val MODELS = setOf("deepseek-v4-flash", "deepseek-v4-pro")
        private val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "pause-ai-http").apply { isDaemon = true }
        }
    }
}
