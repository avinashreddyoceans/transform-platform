package com.transformplatform.api.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.postForObject

private val log = KotlinLogging.logger {}

// ── AnthropicClient ───────────────────────────────────────────────────────────
//
// Thin HTTP wrapper around the Anthropic Messages API.
// Handles the request/response wire format so AiAssistantService can stay
// focused on the agentic loop logic.
//
// API reference: https://docs.anthropic.com/en/api/messages
//
// Configuration:
//   ai.anthropic.api-key   — ANTHROPIC_API_KEY env var (required)
//   ai.anthropic.model     — defaults to claude-3-5-haiku-20241022
//   ai.anthropic.max-tokens — defaults to 4096

@Component
class AnthropicClient(
    private val objectMapper: ObjectMapper,
    @Value("\${ai.anthropic.api-key:}") private val apiKey: String,
    @Value("\${ai.anthropic.base-url:https://api.anthropic.com}") private val baseUrl: String,
    @Value("\${ai.anthropic.model:claude-3-5-haiku-20241022}") val model: String,
    @Value("\${ai.anthropic.max-tokens:4096}") val maxTokens: Int,
) {

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MESSAGES_PATH = "/v1/messages"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Send a request to the Anthropic Messages API and return the parsed response.
     *
     * @param messages  Conversation so far (user + assistant turns).
     * @param tools     Tool definitions available to the model.
     * @param systemPrompt  System prompt describing platform context.
     * @throws AnthropicApiException if the API returns an error or is misconfigured.
     */
    fun send(messages: List<AnthropicMessage>, tools: List<AnthropicTool>, systemPrompt: String): AnthropicResponse {
        check(apiKey.isNotBlank()) {
            "ANTHROPIC_API_KEY is not configured. " +
                "Set the ai.anthropic.api-key property or the ANTHROPIC_API_KEY environment variable."
        }

        val requestBody = buildMap<String, Any> {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            put("messages", messages.map { it.toMap() })
            if (tools.isNotEmpty()) {
                put("tools", tools.map { it.toMap() })
                put("tool_choice", mapOf("type" to "auto"))
            }
        }

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("x-api-key", apiKey)
            set("anthropic-version", ANTHROPIC_VERSION)
        }

        val entity = org.springframework.http.HttpEntity(
            objectMapper.writeValueAsString(requestBody),
            headers,
        )

        log.debug {
            "AnthropicClient: POST $baseUrl$MESSAGES_PATH model=$model " +
                "messages=${messages.size} tools=${tools.size}"
        }

        return try {
            val restTemplate = RestTemplate()
            val raw = restTemplate.postForObject<String>("$baseUrl$MESSAGES_PATH", entity)
                ?: throw AnthropicApiException("Empty response from Anthropic API")

            objectMapper.readValue(raw, AnthropicRawResponse::class.java).toResponse()
        } catch (ex: org.springframework.web.client.HttpClientErrorException) {
            val body = ex.responseBodyAsString
            log.error { "AnthropicClient: HTTP ${ex.statusCode} error — $body" }
            throw AnthropicApiException("Anthropic API error ${ex.statusCode}: $body", ex)
        }
    }
}

// ── Domain types ──────────────────────────────────────────────────────────────

/** A single turn in the conversation. */
data class AnthropicMessage(
    val role: String, // "user" | "assistant"
    val content: Any, // String or List<ContentBlock>
) {
    fun toMap(): Map<String, Any> = mapOf("role" to role, "content" to content)

    companion object {
        fun user(text: String) = AnthropicMessage("user", text)
        fun assistant(contentBlocks: List<ContentBlock>) = AnthropicMessage("assistant", contentBlocks.map { it.toMap() })
        fun toolResults(results: List<ToolResultBlock>) = AnthropicMessage(
            "user",
            results.map { it.toMap() },
        )
    }
}

/** A content block in an assistant turn. */
sealed class ContentBlock {
    abstract fun toMap(): Map<String, Any>

    data class Text(val text: String) : ContentBlock() {
        override fun toMap() = mapOf("type" to "text", "text" to text)
    }

    data class ToolUse(
        val id: String,
        val name: String,
        val input: Map<String, Any>,
    ) : ContentBlock() {
        override fun toMap() = mapOf("type" to "tool_use", "id" to id, "name" to name, "input" to input)
    }
}

/** A tool_result block in a user turn (replies to tool_use blocks). */
data class ToolResultBlock(
    val toolUseId: String,
    val content: String,
    val isError: Boolean = false,
) {
    fun toMap(): Map<String, Any> = buildMap {
        put("type", "tool_result")
        put("tool_use_id", toolUseId)
        put("content", content)
        if (isError) put("is_error", true)
    }
}

/** A tool definition (function Claude can call). */
data class AnthropicTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "name" to name,
        "description" to description,
        "input_schema" to inputSchema,
    )
}

/** Parsed response from the Anthropic Messages API. */
data class AnthropicResponse(
    val id: String,
    val stopReason: String, // "end_turn" | "tool_use" | "max_tokens"
    val contentBlocks: List<ContentBlock>,
) {
    val isToolUse: Boolean get() = stopReason == "tool_use"
    val isEndTurn: Boolean get() = stopReason == "end_turn"

    val textContent: String get() = contentBlocks
        .filterIsInstance<ContentBlock.Text>()
        .joinToString("\n") { it.text }

    val toolUseCalls: List<ContentBlock.ToolUse> get() = contentBlocks
        .filterIsInstance<ContentBlock.ToolUse>()
}

class AnthropicApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// ── Raw JSON deserialization ───────────────────────────────────────────────────
// Using @JsonIgnoreProperties to be robust to new API fields Anthropic may add.

@JsonIgnoreProperties(ignoreUnknown = true)
private data class AnthropicRawResponse(
    val id: String = "",
    @JsonProperty("stop_reason") val stopReason: String = "end_turn",
    val content: List<Map<String, Any>> = emptyList(),
) {
    @Suppress("UNCHECKED_CAST")
    fun toResponse(): AnthropicResponse {
        val blocks = content.mapNotNull { block ->
            when (block["type"]) {
                "text" -> ContentBlock.Text(block["text"] as? String ?: "")
                "tool_use" -> ContentBlock.ToolUse(
                    id = block["id"] as? String ?: "",
                    name = block["name"] as? String ?: "",
                    input = (block["input"] as? Map<String, Any>) ?: emptyMap(),
                )
                else -> null
            }
        }
        return AnthropicResponse(id = id, stopReason = stopReason, contentBlocks = blocks)
    }
}
