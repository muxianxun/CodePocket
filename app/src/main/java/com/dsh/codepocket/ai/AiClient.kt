package com.dsh.codepocket.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(val role: String, val content: String)

/**
 * Streaming variant: consumes the Server-Sent Events the API emits and reports partial text
 * through [onDelta].
 *
 * Why it matters: the non-streaming call leaves the panel frozen until the whole answer has
 * arrived, which is the single most annoying thing about using it. Here the text appears as
 * it is written.
 *
 * Kept as a separate top-level function rather than rewriting [AiClient.send]: the
 * non-streaming path is the one already verified on device and stays available as a fallback.
 */
suspend fun sendStreaming(
    config: AiConfig,
    history: List<ChatMessage>,
    onDelta: (String) -> Unit,
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
        val payload = JSONObject().apply {
            put("model", config.model)
            put("stream", true)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", config.systemPrompt),
                    )
                    history.forEach { message ->
                        put(
                            JSONObject()
                                .put("role", message.role)
                                .put("content", message.content),
                        )
                    }
                },
            )
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            if (config.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        }
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        if (code !in 200..299) {
            val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error("HTTP $code: ${detail.take(300)}")
        }

        val builder = StringBuilder()
        connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val delta = runCatching {
                    JSONObject(data)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?.optString("content")
                        .orEmpty()
                }.getOrDefault("")
                if (delta.isNotEmpty()) {
                    builder.append(delta)
                    onDelta(builder.toString())
                }
            }
        }
        connection.disconnect()

        if (builder.isEmpty()) {
            error("服务端没有流式返回内容（已请求 stream=true 但没收到 data: 行）—— 该接口可能不支持流式")
        }
        builder.toString()
    }
}

/** A fenced code block from an assistant reply. */
data class CodeBlock(val language: String, val code: String)

data class AiConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
        val DEFAULT_SYSTEM_PROMPT = """
            你是一个帮助用户在手机（Android）上写代码的助手。用户在一个叫「掌上代码」的 App 里，
            里面有文件管理、代码编辑器和终端（/system/bin/sh）。

            回答要求：
            1. 需要给代码时，用 ```语言 围栏代码块给出完整、可直接运行的内容，不要省略。
            2. 解释尽量简短，先给代码再给要点。
            3. 内容面向手机小屏幕，避免超长段落。
            4. 用户的工作目录是 App 的 workspace 目录，终端里可以直接 cd 进去。
        """.trimIndent()
    }
}

/** Persists the endpoint configuration locally; the key never leaves the device. */
class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ai_config", Context.MODE_PRIVATE)

    fun load(): AiConfig = AiConfig(
        baseUrl = prefs.getString("baseUrl", null) ?: AiConfig.DEFAULT_BASE_URL,
        apiKey = prefs.getString("apiKey", null) ?: "",
        model = prefs.getString("model", null) ?: AiConfig.DEFAULT_MODEL,
        systemPrompt = prefs.getString("systemPrompt", null) ?: AiConfig.DEFAULT_SYSTEM_PROMPT,
    )

    fun save(config: AiConfig) {
        prefs.edit()
            .putString("baseUrl", config.baseUrl)
            .putString("apiKey", config.apiKey)
            .putString("model", config.model)
            .putString("systemPrompt", config.systemPrompt)
            .apply()
    }
}

class AiClient {

    suspend fun chat(config: AiConfig, history: List<ChatMessage>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 180_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    if (config.apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    }
                }

                val payload = JSONObject().apply {
                    put("model", config.model)
                    put("stream", false)
                    put(
                        "messages",
                        JSONArray().apply {
                            put(
                                JSONObject()
                                    .put("role", "system")
                                    .put("content", config.systemPrompt),
                            )
                            history.forEach { message ->
                                put(
                                    JSONObject()
                                        .put("role", message.role)
                                        .put("content", message.content),
                                )
                            }
                        },
                    )
                }

                connection.outputStream.use { out ->
                    out.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                if (code !in 200..299) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP $code · ${body.take(400)}"),
                    )
                }

                val content = JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                Result.success(content)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                // nothing to release explicitly
            }
        }

    companion object {
        private val FENCE = Regex("```([a-zA-Z0-9_+-]*)\\n([\\s\\S]*?)```")

        /** Pulls out fenced code blocks so the user can push them straight into a file. */
        fun extractCodeBlocks(text: String): List<CodeBlock> =
            FENCE.findAll(text).map {
                CodeBlock(
                    language = it.groupValues[1].lowercase(),
                    code = it.groupValues[2].trimEnd('\n'),
                )
            }.toList()

        /** Maps a fence language onto a file extension so the editor highlights it. */
        fun extensionFor(language: String): String = when (language.lowercase()) {
            "python", "py", "python3" -> "py"
            "javascript", "js", "node" -> "js"
            "typescript", "ts" -> "ts"
            "java" -> "java"
            "kotlin", "kt" -> "kt"
            "c" -> "c"
            "cpp", "c++", "cxx" -> "cpp"
            "csharp", "cs" -> "cs"
            "go", "golang" -> "go"
            "rust", "rs" -> "rs"
            "shell", "sh", "bash", "zsh" -> "sh"
            "html" -> "html"
            "xml" -> "xml"
            "json" -> "json"
            "yaml", "yml" -> "yml"
            "sql" -> "sql"
            "markdown", "md" -> "md"
            else -> "txt"
        }
    }
}
