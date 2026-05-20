package com.transformplatform.api.ai

import mu.KotlinLogging
import org.springframework.stereotype.Service

private val log = KotlinLogging.logger {}

// ── AiAssistantService ────────────────────────────────────────────────────────
//
// Implements the agentic loop:
//
//   1. Send user message + platform tools to Claude
//   2. If stop_reason == "tool_use": execute the tool calls, re-send with results
//   3. Repeat until stop_reason == "end_turn" or maxIterations reached
//
// This is a stateless service — conversation history is held by the caller
// (AiAssistantController) across HTTP requests in a session.
//
// Max iterations:
//   Prevents infinite loops if the model repeatedly requests tools.
//   3 iterations is enough for: look up specs → look up integrations → create profile.
//   Complex multi-step workflows (e.g. audit report) may need 5-6.
//
// Error handling:
//   Tool errors are returned as tool_result { "is_error": true } so Claude can
//   explain the failure gracefully rather than throwing an HTTP 500.

@Service
class AiAssistantService(
    private val anthropicClient: AnthropicClient,
    private val platformToolSet: PlatformToolSet,
) {
    companion object {
        const val MAX_ITERATIONS = 6
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Process a single user turn and return Claude's final text response.
     *
     * @param userMessage   The user's current message.
     * @param history       Previous turns in the conversation (user + assistant).
     *                      Pass empty list for first message.
     * @return The assistant's final text response after all tool calls complete.
     */
    fun chat(userMessage: String, history: List<AnthropicMessage> = emptyList()): AiChatResult {
        val messages = history.toMutableList()
        messages.add(AnthropicMessage.user(userMessage))

        val toolsUsed = mutableListOf<String>()
        var iterations = 0

        while (iterations < MAX_ITERATIONS) {
            iterations++
            log.debug { "AiAssistant: iteration $iterations/$MAX_ITERATIONS, messages=${messages.size}" }

            val response = anthropicClient.send(
                messages = messages,
                tools = platformToolSet.all,
                systemPrompt = SYSTEM_PROMPT,
            )

            if (response.isEndTurn || !response.isToolUse) {
                // Final text response from Claude
                val finalText = response.textContent
                log.info {
                    "AiAssistant: done after $iterations iteration(s), " +
                        "toolsUsed=${toolsUsed.joinToString()}, " +
                        "responseLength=${finalText.length}"
                }
                return AiChatResult(
                    response = finalText,
                    toolsUsed = toolsUsed.toList(),
                    iterations = iterations,
                    updatedHistory = messages + AnthropicMessage.assistant(response.contentBlocks),
                )
            }

            // ── Tool use: execute all tool calls, collect results ─────────────
            val toolCalls = response.toolUseCalls
            log.info { "AiAssistant: Claude requested ${toolCalls.size} tool call(s): ${toolCalls.map { it.name }}" }

            val toolResults = toolCalls.map { call ->
                toolsUsed.add(call.name)
                val result = platformToolSet.execute(call.name, call.input)
                log.debug { "AiAssistant: tool '${call.name}' result (first 200 chars): ${result.take(200)}" }
                ToolResultBlock(toolUseId = call.id, content = result)
            }

            // Add assistant turn (with tool_use blocks) + user turn (with tool_result blocks)
            messages.add(AnthropicMessage.assistant(response.contentBlocks))
            messages.add(AnthropicMessage.toolResults(toolResults))
        }

        log.warn { "AiAssistant: reached max iterations ($MAX_ITERATIONS) without end_turn" }
        return AiChatResult(
            response = "I reached the maximum number of steps while processing your request. " +
                "Please try a more specific question, or break the task into smaller parts.",
            toolsUsed = toolsUsed.toList(),
            iterations = iterations,
            updatedHistory = messages,
        )
    }
}

// ── Result type ───────────────────────────────────────────────────────────────

data class AiChatResult(
    /** Claude's final text response. */
    val response: String,
    /** Names of tools Claude called during this turn. */
    val toolsUsed: List<String>,
    /** How many iterations the agentic loop ran. */
    val iterations: Int,
    /** Full updated conversation history (for stateful multi-turn sessions). */
    val updatedHistory: List<AnthropicMessage>,
)

// ── System prompt ─────────────────────────────────────────────────────────────
//
// Describes the platform to Claude so it can answer questions and generate
// correct configs without hallucinating field names or types.
//
// Kept in this file to keep system prompt and service co-located.
// Use triple-quoted strings for readability; leading whitespace trimmed.

private val SYSTEM_PROMPT = """
You are an intelligent assistant for the Transform Platform — a financial data processing system.

## What the platform does
The platform ingests financial files (e.g., CAMT.053 bank statements) via SFTP, S3, or FTP integrations,
parses them using configurable FileSpecs, validates the records, and publishes them to Kafka topics
for downstream consumers.

## Core concepts

### Profile
A Profile is the complete configuration for a client's data workflow:
- windowConfig: defines WHEN processing windows open and close
- actions: defines WHAT happens at each lifecycle event

### WindowConfig (openTrigger / closeTrigger)
Controls the timing of processing windows. Trigger types:
- TIME_BASED: opens/closes on a cron schedule
  Example: {"type": "TIME_BASED", "openCron": "0 9 * * 1-5", "closeCron": "0 17 * * 1-5", "timeZone": "Europe/London"}
- FILE_ARRIVAL: opens when a file arrives on a watched integration
  Example: {"type": "FILE_ARRIVAL", "integrationId": "<id>", "filePattern": "*.xml", "pollInterval": "PT1M"}
- MANUAL: opens only when explicitly triggered via API

### Actions and WorkflowSteps
Actions define what happens at lifecycle events (ON_FILE_ARRIVED, ON_CLOSING, ON_OPEN, ON_ERROR).
Each action has a list of steps that execute in order:

Step types:
- PARSE_FILE: parse an arrived file using a FileSpec and publish records to Kafka
  Config: {"fileSpecId": "<id>", "integrationId": "<id>"}
- VALIDATE: validate the parsed records
  Config: {"rejectOnError": false}
- NOTIFY: send a notification (currently KAFKA channel supported)
  Config: {"channel": "KAFKA"}
- GENERATE_FILE: generate an output file
- DELIVER_FILE: deliver a file to a remote system

### RetryPolicy (per step, optional)
Each step has a retry policy. Defaults to 3 attempts.
Custom: {"maxAttempts": 5, "initialDelay": "PT1S", "backoffMultiplier": 2.0, "maxDelay": "PT30S"}

### FileSpec
Defines how to parse a specific file format. Each spec has fields with XPath or positional mappings.
Use list_file_specs to see what specs are available.

### Integration
A configured connection to an SFTP server, FTP server, or S3 bucket.
Use list_integrations to see available integrations.

## How to answer queries
- For questions about current state ("how many profiles?", "did the workflow fail?"), use the read tools.
- For "create a profile that...", first list relevant file specs and integrations so you have real IDs,
  then generate the full Profile JSON config and explain it to the user before calling create_profile.
- Always show the user what you're about to create BEFORE calling any write tools.
- After creating resources, confirm what was created and what the next steps are (e.g., enable the profile).
- Format JSON configs in code blocks when explaining them to the user.
- Be concise: users are engineers, not end-users.

## Important constraints
- You cannot create FileSpecs via chat (they require sample file uploads) — redirect users to the API.
- You cannot create Integrations via chat (they require credentials) — redirect users to the API.
- Profile IDs and FileSpec IDs are UUIDs. Always look them up rather than guessing.
""".trimIndent()
