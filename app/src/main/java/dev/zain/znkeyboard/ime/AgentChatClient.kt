package dev.zain.znkeyboard.ime

import android.util.Log
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.constants.AgentDefaults
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Chat completions for rewrites, voice and the reading translation, off the main thread. */
internal object AgentChatClient {
    private const val TAG = "ZnKeyboardAgent"
    private const val CJK_START = 0x2E80
    private val STRUCTURED_OUTPUT_TEXT_FIELD = AgentDefaults.STRUCTURED_OUTPUT_TEXT_FIELD
    private val THINK_BLOCK_REGEX = Regex("(?is)<think>.*?</think>")
    private val REASONING_BLOCK_REGEX = Regex("(?is)<reasoning>.*?</reasoning>")

    private fun t(chinese: Boolean, zh: String, en: String) = if (chinese) zh else en

    /** [userContent] is the user message: plain text, or a JSONArray of content parts (text, audio). */
    fun request(
        providerSettings: KeyboardSettings.AgentProviderSettings,
        systemPrompt: String,
        userContent: Any,
        maxTokens: Int,
    ): String {
        val endpoint = buildChatCompletionsUrl(providerSettings)
        var structuredOutputError: IOException? = null
        val outputModes = listOf(StructuredOutputMode.StandardJsonSchema, StructuredOutputMode.JsonObject)

        for (outputMode in outputModes) {
            try {
                return withBusyRetries {
                    executeChatCompletionRequest(
                        endpoint = endpoint,
                        providerSettings = providerSettings,
                        systemPrompt = systemPrompt,
                        userContent = userContent,
                        maxTokens = maxTokens,
                        outputMode = outputMode,
                    )
                }
            } catch (error: LlmHttpException) {
                if (
                    outputMode != StructuredOutputMode.JsonObject &&
                    error.isStructuredOutputUnsupported()
                ) {
                    structuredOutputError = error
                    Log.w(TAG, "Structured output mode $outputMode unsupported; trying compatibility fallback.")
                    continue
                }
                throw error
            }
        }

        throw structuredOutputError ?: IOException("LLM request failed before returning structured output.")
    }
    /**
     * Runs [request] (on the request's background thread), trying again after a short wait when the
     * provider answers busy (429, 5xx; DeepSeek's 503 "Service is too busy" at peak times): those pass
     * within seconds, so the user rarely sees them. Anything else fails at once.
     */
    private inline fun <T> withBusyRetries(request: () -> T): T {
        for (delayMs in AgentDefaults.BUSY_RETRY_DELAYS_MS) {
            try {
                return request()
            } catch (error: LlmHttpException) {
                if (!error.isBusy()) throw error
                Log.w(TAG, "Provider busy (HTTP ${error.responseCode}); trying again in $delayMs ms.")
                Thread.sleep(delayMs)
            }
        }
        return request()
    }
    private fun executeChatCompletionRequest(
        endpoint: URL,
        providerSettings: KeyboardSettings.AgentProviderSettings,
        systemPrompt: String,
        userContent: Any,
        maxTokens: Int,
        outputMode: StructuredOutputMode,
    ): String {
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = AgentDefaults.CONNECT_TIMEOUT_MS
            // A whole answer in one piece: a long translation or a 90-second recording takes a while.
            readTimeout = AgentDefaults.REQUEST_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${providerSettings.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        val body = JSONObject()
            .put("model", providerSettings.model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userContent)),
            )
            .put("temperature", 0.4)
            .put("max_tokens", maxTokens)
            .put("response_format", structuredResponseFormatFor(outputMode))
            .applyReasoningSettings(providerSettings)

        return try {
            connection.outputStream.writer(Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            if (responseCode !in 200..299) {
                throw LlmHttpException(
                    responseCode = responseCode,
                    responseText = responseText,
                    detailMessage = "LLM request failed ($responseCode): ${responseText.take(AgentDefaults.ERROR_PREVIEW_CHARS)}",
                )
            }

            extractChatCompletionContent(
                responseJson = JSONObject(responseText),
                includeReasoningText = providerSettings.reasoningTextEnabled,
            )
        } finally {
            connection.disconnect()
        }
    }
    private fun extractChatCompletionContent(
        responseJson: JSONObject,
        includeReasoningText: Boolean,
    ): String {
        val choice = responseJson
                .getJSONArray("choices")
                .getJSONObject(0)

        // A cut-off answer is half a JSON object; say why instead of reporting a parse error.
        if (choice.optString("finish_reason") == "length") throw TooLongException()

        val message = choice.optJSONObject("message")
            ?: throw IOException("LLM response did not include a message.")

        val refusal = message.optNullableString("refusal")
        if (!refusal.isNullOrBlank()) {
            throw RefusedException()
        }

        val structuredPayload = parseStructuredOutput(message.opt("content"), includeReasoningText)
        val text = structuredPayload
            .optString(STRUCTURED_OUTPUT_TEXT_FIELD)
            .let(AsciiTextSanitizer::clean)
            .trim()

        if (text.isBlank() || text.equals("null", ignoreCase = true)) {
            val finishReason = choice.optString("finish_reason", "unknown")
            throw EmptyModelTextException("No text returned by the model. Finish reason: $finishReason.")
        }

        return text
    }
    private fun parseStructuredOutput(content: Any?, includeReasoningText: Boolean): JSONObject {
        return when (content) {
            null, JSONObject.NULL -> throw IOException("LLM response did not include structured content.")
            is JSONObject -> content
            is String -> parseStructuredOutputJson(content, includeReasoningText)
            is JSONArray -> parseStructuredOutputJson(extractTextFromContentArray(content), includeReasoningText)
            else -> throw IOException("LLM response content was not a structured JSON object.")
        }
    }
    private fun JSONObject.optNullableString(name: String): String? {
        val value = opt(name)
        if (value == null || value == JSONObject.NULL) return null
        return value.toString()
            .trim()
            .takeUnless { it.equals("null", ignoreCase = true) }
    }
    private fun parseStructuredOutputJson(rawContent: String, includeReasoningText: Boolean): JSONObject {
        val jsonText = rawContent
            .stripReasoningBlocks(includeReasoningText)
            .trim()
        if (jsonText.isBlank()) {
            throw IOException("LLM response did not include structured JSON.")
        }

        try {
            return JSONObject(jsonText)
        } catch (error: JSONException) {
            throw IOException("LLM response was not valid structured JSON.", error)
        }
    }
    private fun String.stripReasoningBlocks(includeReasoningText: Boolean): String {
        if (includeReasoningText) return this

        return replace(THINK_BLOCK_REGEX, "")
            .replace(REASONING_BLOCK_REGEX, "")
    }
    private fun extractTextFromContentArray(content: JSONArray): String {
        return buildString {
            for (index in 0 until content.length()) {
                when (val part = content.opt(index)) {
                    is String -> append(part)
                    is JSONObject -> append(part.optString("text"))
                }
            }
        }
    }
    /**
     * Room for the answer: a CJK character can cost a whole token while Latin text packs several
     * characters per token, so CJK counts in full. Translation can lengthen text, hence the margin.
     */
    fun maxTokensFor(text: String): Int {
        val cjk = text.count { it.code >= CJK_START }
        val estimatedOutputTokens = cjk + (text.length - cjk) / AgentDefaults.CHARS_PER_OUTPUT_TOKEN_ESTIMATE
        return (estimatedOutputTokens * 3 / 2 + AgentDefaults.REWRITE_TOKEN_MARGIN)
            .coerceIn(AgentDefaults.MIN_REWRITE_LLM_TOKENS, AgentDefaults.MAX_REWRITE_LLM_TOKENS)
    }
    private fun structuredResponseFormatFor(outputMode: StructuredOutputMode): JSONObject {
        if (outputMode == StructuredOutputMode.JsonObject) {
            return JSONObject().put("type", "json_object")
        }

        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put(
                        STRUCTURED_OUTPUT_TEXT_FIELD,
                        JSONObject()
                            .put("type", "string")
                            .put(
                                "description",
                                "The final rewritten text only, without labels, explanations, or prefaces.",
                            ),
                    ),
            )
            .put("required", JSONArray().put(STRUCTURED_OUTPUT_TEXT_FIELD))

        return JSONObject()
            .put("type", "json_schema")
            .put(
                "json_schema",
                JSONObject()
                    .put("name", "znkeyboard_rewrite_response")
                    .put("strict", true)
                    .put("schema", schema),
            )
    }
    private fun JSONObject.applyReasoningSettings(
        providerSettings: KeyboardSettings.AgentProviderSettings,
    ): JSONObject {
        if (providerSettings.reasoningMode == KeyboardSettings.AgentReasoningMode.Off) return this
        return applyOpenAiCompatibleReasoningSettings(providerSettings)
    }
    private fun JSONObject.applyOpenAiCompatibleReasoningSettings(
        providerSettings: KeyboardSettings.AgentProviderSettings,
    ): JSONObject {
        providerSettings.reasoningMode.effort?.let { effort ->
            put("reasoning_effort", effort)
        }
        if (providerSettings.reasoningTextEnabled) {
            put("include_reasoning", true)
            put(
                "reasoning",
                JSONObject().put("exclude", false),
            )
        }
        return this
    }
    private fun buildChatCompletionsUrl(providerSettings: KeyboardSettings.AgentProviderSettings): URL {
        val normalizedBaseUrl = providerSettings.baseUrl.trim().trimEnd('/')
        val endpoint = URL("$normalizedBaseUrl/chat/completions")
        if (endpoint.protocol != "https") {
            throw IOException("Use an HTTPS OpenAI-compatible endpoint.")
        }
        return endpoint
    }
    /** What went wrong, in words, with the HTTP status for reference; never the server's raw reply. */
    fun friendlyError(error: Throwable, chinese: Boolean): String = when (error) {
        is TooLongException -> t(chinese, "文字太长，请分段处理（最多 5000 字）。", "The text is too long; do it in parts (5,000 characters max).")
        is EmptyModelTextException -> t(chinese, "AI 没有返回结果，请再试一次。", "The AI returned nothing. Try again.")
        is LlmHttpException -> when (error.responseCode) {
            400 -> t(chinese, "请求被拒绝（HTTP 400），请检查模型名称。", "The request was rejected (HTTP 400). Check the model name.")
            401, 403 -> t(chinese, "API Key 无效或没有权限（HTTP ${error.responseCode}），请在设置里检查。", "The API key is invalid or not allowed (HTTP ${error.responseCode}). Check it in Settings.")
            404 -> t(chinese, "找不到这个模型或接口地址（HTTP 404），请在设置里检查。", "Model or API address not found (HTTP 404). Check Settings.")
            402 -> t(chinese, "额度已用完（HTTP 402），请检查账户余额。", "Out of credit (HTTP 402). Check your account.")
            // Already tried three times (withBusyRetries).
            429, in 500..599 -> t(chinese, 
                "AI 服务商暂时繁忙（HTTP ${error.responseCode}），自动重试后仍未成功。请稍后再试，或在设置里换用其他服务商。",
                "The AI provider is busy (HTTP ${error.responseCode}) and still didn't answer after a few tries. Try again shortly, or switch providers in Settings.",
            )
            else -> t(chinese, "AI 请求失败（HTTP ${error.responseCode}）。", "The AI request failed (HTTP ${error.responseCode}).")
        }
        is java.net.SocketTimeoutException -> t(chinese, "AI 服务响应超时，请稍后再试。", "The AI service took too long. Try again.")
        is java.net.UnknownHostException, is java.net.ConnectException, is javax.net.ssl.SSLException ->
            t(chinese, "无法连接 AI 服务，请检查网络。", "Couldn't reach the AI service. Check your connection.")
        is RefusedException -> t(chinese, "AI 拒绝处理这段文字。", "The AI declined this text.")
        else -> t(chinese, "AI 请求失败，请稍后再试。", "The AI request failed. Try again later.")
    }
    /** Too many requests, or the server failing or overloaded: worth trying again. */
    private fun LlmHttpException.isBusy(): Boolean = responseCode == 429 || responseCode in 500..599
    private fun LlmHttpException.isStructuredOutputUnsupported(): Boolean {
        if (responseCode !in setOf(400, 404, 422)) return false

        val normalized = responseText.lowercase(Locale.US)
        return normalized.contains("no endpoints found") ||
            normalized.contains("failed to initialize samplers") ||
            normalized.contains("response_format") ||
            normalized.contains("json_schema") ||
            normalized.contains("structured output") ||
            normalized.contains("schema")
    }
}

internal class EmptyModelTextException(message: String) : IOException(message)
/** The answer was cut off at the token limit. */
internal class TooLongException : IOException("Text is too long to rewrite.")
internal class RefusedException : IOException("The model refused the request.")
internal class LlmHttpException(
    val responseCode: Int,
    val responseText: String,
    detailMessage: String,
) : IOException(detailMessage)
private enum class StructuredOutputMode {
    StandardJsonSchema,
    JsonObject,
}
