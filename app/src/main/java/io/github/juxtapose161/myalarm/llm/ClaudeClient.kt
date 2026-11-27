package io.github.juxtapose161.myalarm.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import io.github.juxtapose161.myalarm.course.ClaudeSettings
import io.github.juxtapose161.myalarm.course.Effort
import io.github.juxtapose161.myalarm.course.Llm
import io.github.juxtapose161.myalarm.course.Prompts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/** Claude over the official Anthropic Java SDK, with structured (JSON schema) outputs. */
class ClaudeClient(private val settings: ClaudeSettings, baseUrl: String? = null) : Llm {

    class RefusedException(message: String) : Exception(message)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(settings.apiKey.trim())
        .timeout(Duration.ofSeconds(90))
        .maxRetries(2)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    /** Server-side refusal fallbacks; switched off for this client if the API rejects them. */
    @Volatile private var useFallbacks = true

    override suspend fun json(user: String, schema: Map<String, Any>, effort: Effort, maxTokens: Long): String =
        withContext(Dispatchers.IO) {
            try {
                request(user, schema, effort, maxTokens, useFallbacks)
            } catch (e: BadRequestException) {
                if (!useFallbacks) throw e
                useFallbacks = false
                request(user, schema, effort, maxTokens, false)
            }
        }

    private fun request(user: String, schema: Map<String, Any>, effort: Effort, maxTokens: Long, fallbacks: Boolean): String {
        val format = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder().putAllAdditionalProperties(schema.mapValues { JsonValue.from(it.value) }).build())
            .build()
        val builder = MessageCreateParams.builder()
            .model(settings.model.ifBlank { ClaudeSettings.DEFAULT_MODEL })
            .maxTokens(maxTokens)
            .system(Prompts.system)
            .addUserMessage(user)
            .outputConfig(
                OutputConfig.builder()
                    .effort(
                        when (effort) {
                            Effort.LOW -> OutputConfig.Effort.LOW
                            Effort.MEDIUM -> OutputConfig.Effort.MEDIUM
                            Effort.HIGH -> OutputConfig.Effort.HIGH
                        }
                    )
                    .format(format)
                    .build()
            )
        if (fallbacks) {
            // If a safety classifier declines, the server retries on a fallback model.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val message = client.messages().create(builder.build())
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) throw RefusedException("Claude declined the request")
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        if (text.isBlank()) throw IllegalStateException("Empty response (stop: ${message.stopReason().orElse(null)})")
        return text
    }

    /** A tiny request to check the key and the model. */
    suspend fun ping(): String = json(
        "Reply with ok = true.",
        mapOf(
            "type" to "object",
            "properties" to mapOf("ok" to mapOf("type" to "boolean")),
            "required" to listOf("ok"),
            "additionalProperties" to false,
        ),
        Effort.LOW, 1000,
    )
}
