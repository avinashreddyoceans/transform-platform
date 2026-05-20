package com.transformplatform.api.controller.ai

import com.transformplatform.api.ai.AiAssistantService
import com.transformplatform.api.ai.AnthropicApiException
import com.transformplatform.api.ai.AnthropicMessage
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

private val log = KotlinLogging.logger {}

// ── AiAssistantController ─────────────────────────────────────────────────────
//
// REST API for the AI assistant natural-language interface.
//
// Routes:
//   POST /api/ai/chat       — single-turn or multi-turn chat
//
// Multi-turn usage:
//   Send the full conversation history in `history` on each request.
//   The response includes `updatedHistory` — pass this back on the next request
//   to maintain context across turns.
//   Alternatively, store history server-side and use a session ID (Phase 2).
//
// Authentication:
//   Uses the same security filter chain as other /api/** endpoints.
//   In development (AUTH_DISABLED=true) no token is required.

@RestController
@RequestMapping("/api/ai")
class AiAssistantController(
    private val assistantService: AiAssistantService,
) {

    // ── Chat ──────────────────────────────────────────────────────────────────

    @PostMapping("/chat")
    fun chat(@RequestBody request: AiChatRequest): ResponseEntity<AiChatResponse> {
        log.info {
            "AI chat: message='${request.message.take(80)}${if (request.message.length > 80) "…" else ""}' " +
                "historyTurns=${request.history.size}"
        }

        return try {
            val result = assistantService.chat(
                userMessage = request.message,
                history = request.history.map { h ->
                    AnthropicMessage(role = h.role, content = h.content)
                },
            )

            ResponseEntity.ok(
                AiChatResponse(
                    response = result.response,
                    toolsUsed = result.toolsUsed,
                    iterations = result.iterations,
                    updatedHistory = result.updatedHistory.map { msg ->
                        ConversationTurn(
                            role = msg.role,
                            content = msg.content,
                        )
                    },
                    timestamp = Instant.now(),
                ),
            )
        } catch (ex: AnthropicApiException) {
            log.error(ex) { "AI chat: Anthropic API error — ${ex.message}" }
            ResponseEntity.status(503).body(
                AiChatResponse(
                    response = "The AI service is temporarily unavailable: ${ex.message}. " +
                        "Please check that ANTHROPIC_API_KEY is configured and try again.",
                    toolsUsed = emptyList(),
                    iterations = 0,
                    updatedHistory = request.history,
                    timestamp = Instant.now(),
                    error = ex.message,
                ),
            )
        } catch (ex: IllegalStateException) {
            // ANTHROPIC_API_KEY not configured
            log.warn { "AI chat: ${ex.message}" }
            ResponseEntity.status(503).body(
                AiChatResponse(
                    response = ex.message ?: "AI assistant is not configured.",
                    toolsUsed = emptyList(),
                    iterations = 0,
                    updatedHistory = request.history,
                    timestamp = Instant.now(),
                    error = ex.message,
                ),
            )
        }
    }
}

// ── DTOs ──────────────────────────────────────────────────────────────────────

data class AiChatRequest(
    /** The user's message. */
    val message: String,
    /**
     * Previous turns in the conversation.
     * Pass empty list for first message; pass [AiChatResponse.updatedHistory]
     * from the previous response to continue the conversation.
     */
    val history: List<ConversationTurn> = emptyList(),
)

data class AiChatResponse(
    /** Claude's final natural-language response. */
    val response: String,
    /** Names of platform tools Claude called to answer the question. */
    val toolsUsed: List<String>,
    /** How many agentic loop iterations ran (diagnostic). */
    val iterations: Int,
    /**
     * Full updated conversation history including this turn.
     * Pass this back in [AiChatRequest.history] to continue the conversation.
     */
    val updatedHistory: List<ConversationTurn>,
    val timestamp: Instant,
    /** Non-null if an error occurred (response will contain a user-friendly message). */
    val error: String? = null,
)

/**
 * A single turn in the conversation.
 * role: "user" | "assistant"
 * content: String (simple text) or List<*> (tool use/result blocks — opaque to the client).
 */
data class ConversationTurn(
    val role: String,
    val content: Any,
)
