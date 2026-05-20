package com.transformplatform.api.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.transformplatform.api.service.SpecService
import com.transformplatform.api.service.profile.ProfileService
import com.transformplatform.common.domain.profile.Profile
import com.transformplatform.common.domain.profile.ProfileStatus
import com.transformplatform.integration.repository.ServiceIntegrationRepository
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import com.transformplatform.scheduler.repository.WorkflowExecutionRepository
import mu.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

// ── PlatformToolSet ───────────────────────────────────────────────────────────
//
// Defines the tools Claude can call during an AI assistant session.
// Each tool wraps an existing platform service — no new business logic lives here.
//
// Tools are intentionally READ-HEAVY for safety:
//   Query tools:  list_profiles, get_profile, list_file_specs,
//                 list_integrations, list_windows, get_executions
//   Write tools:  create_profile, enable_profile, disable_profile
//
// Write tools produce a structured config that is shown to the user for
// review. Claude explains the config before calling the write tool so users
// understand what is about to happen.
//
// Adding a new tool:
//   1. Add an AnthropicTool constant to `all` list
//   2. Add a branch in `execute()` that calls the appropriate service
//
// Tool name convention: snake_case, verb_noun pattern

@Component
class PlatformToolSet(
    private val profileService: ProfileService,
    private val specService: SpecService,
    private val integrationRepo: ServiceIntegrationRepository,
    private val windowRepo: WindowInstanceRepository,
    private val execRepo: WorkflowExecutionRepository,
    private val objectMapper: ObjectMapper,
) {

    // ── Tool registry ─────────────────────────────────────────────────────────

    val all: List<AnthropicTool> = listOf(
        listProfilesTool,
        getProfileTool,
        listFileSpecsTool,
        listIntegrationsTool,
        listWindowsTool,
        getExecutionsTool,
        createProfileTool,
        enableProfileTool,
        disableProfileTool,
    )

    // ── Tool dispatch ─────────────────────────────────────────────────────────

    /**
     * Execute a tool call and return the result as a JSON string.
     * Exceptions are caught and returned as error strings so Claude can
     * explain the failure to the user rather than crashing the session.
     */
    fun execute(toolName: String, input: Map<String, Any>): String {
        log.info { "PlatformTools: executing tool '$toolName' with input=$input" }
        return runCatching {
            when (toolName) {
                "list_profiles" -> listProfiles(input)
                "get_profile" -> getProfile(input)
                "list_file_specs" -> listFileSpecs()
                "list_integrations" -> listIntegrations()
                "list_windows" -> listWindows(input)
                "get_executions" -> getExecutions(input)
                "create_profile" -> createProfile(input)
                "enable_profile" -> enableProfile(input)
                "disable_profile" -> disableProfile(input)
                else -> """{"error": "Unknown tool: $toolName"}"""
            }
        }.getOrElse { ex ->
            log.error(ex) { "PlatformTools: tool '$toolName' threw: ${ex.message}" }
            objectMapper.writeValueAsString(mapOf("error" to (ex.message ?: "Unknown error")))
        }
    }

    // ── Tool implementations ──────────────────────────────────────────────────

    private fun listProfiles(input: Map<String, Any>): String {
        val status = (input["status"] as? String)?.let {
            runCatching { ProfileStatus.valueOf(it.uppercase()) }.getOrNull()
        }
        val clientId = input["client_id"] as? String
        val profiles = profileService.findAll(clientId = clientId, status = status)
        val result = profiles.map { p ->
            mapOf(
                "id" to p.id,
                "name" to p.name,
                "clientId" to p.clientId,
                "status" to p.status.name,
                "version" to p.version,
                "description" to p.description,
                "openTrigger" to p.windowConfig.openTrigger::class.simpleName,
                "closeTrigger" to p.windowConfig.closeTrigger::class.simpleName,
                "actionCount" to p.actions.size,
                "tags" to p.tags,
            )
        }
        return objectMapper.writeValueAsString(mapOf("profiles" to result, "count" to result.size))
    }

    private fun getProfile(input: Map<String, Any>): String {
        val id = input["profile_id"] as? String ?: return """{"error": "profile_id is required"}"""
        val profile = profileService.findById(id)
        return objectMapper.writeValueAsString(profile)
    }

    private fun listFileSpecs(): String {
        val specs = specService.listSpecs(format = null, page = 0, size = Int.MAX_VALUE)
        val result = specs.map { s ->
            mapOf(
                "id" to s.id,
                "name" to s.name,
                "format" to s.format.name,
                "description" to (s.description ?: ""),
                "fieldCount" to s.fieldCount,
            )
        }
        return objectMapper.writeValueAsString(mapOf("fileSpecs" to result, "count" to result.size))
    }

    private fun listIntegrations(): String {
        val integrations = integrationRepo.findAll()
        val result = integrations.map { i ->
            mapOf(
                "id" to i.id,
                "type" to i.type.name,
                "enabled" to i.isEnabled,
                "userId" to i.userId,
                "description" to i.shortDescription,
            )
        }
        return objectMapper.writeValueAsString(mapOf("integrations" to result, "count" to result.size))
    }

    private fun listWindows(input: Map<String, Any>): String {
        val profileId = input["profile_id"] as? String
        val statusFilter = input["status"] as? String

        val windows = if (profileId != null) {
            windowRepo.findByProfileId(profileId)
        } else {
            windowRepo.findAll().take(50)
        }

        val filtered = if (statusFilter != null) {
            windows.filter { it.status.name.equals(statusFilter, ignoreCase = true) }
        } else {
            windows
        }

        val result = filtered.map { w ->
            mapOf(
                "id" to w.id,
                "profileId" to w.profileId,
                "status" to w.status.name,
                "openedAt" to w.openedAt?.toString(),
                "closedAt" to w.closedAt?.toString(),
                "arrivedFileCount" to w.arrivedFileHandles.size,
            )
        }
        return objectMapper.writeValueAsString(mapOf("windows" to result, "count" to result.size))
    }

    private fun getExecutions(input: Map<String, Any>): String {
        val windowId = input["window_id"] as? String
        val profileId = input["profile_id"] as? String

        val executions = when {
            windowId != null -> execRepo.findByWindowId(windowId)
            profileId != null -> execRepo.findByProfileId(profileId, limit = 20)
            else -> execRepo.findAll(limit = 20)
        }

        val result = executions.map { e ->
            mapOf(
                "id" to e.id,
                "windowId" to e.windowInstanceId,
                "actionName" to e.actionName,
                "status" to e.status.name,
                "totalRecords" to e.totalRecordsProcessed,
                "startedAt" to e.startedAt?.toString(),
                "completedAt" to e.completedAt?.toString(),
                "durationMs" to e.durationMs,
                "errorMessage" to e.errorMessage,
                "steps" to e.stepExecutions.map { s ->
                    mapOf(
                        "name" to s.stepName,
                        "status" to s.status.name,
                        "records" to s.recordsProcessed,
                        "attempt" to s.attempt,
                        "error" to s.errorMessage,
                        "summary" to s.outputSummary,
                    )
                },
            )
        }
        return objectMapper.writeValueAsString(mapOf("executions" to result, "count" to result.size))
    }

    private fun createProfile(input: Map<String, Any>): String {
        // Deserialize through ObjectMapper so WindowTrigger @JsonTypeInfo polymorphism
        // is handled correctly (same as the REST controller path).
        val json = objectMapper.writeValueAsString(input)
        val request = objectMapper.readValue(
            json,
            com.transformplatform.api.controller.profile.CreateProfileRequest::class.java,
        )
        // Mirror the ProfileController.createProfile() logic exactly
        val profile = Profile(
            name = request.name,
            clientId = request.clientId,
            description = request.description,
            windowConfig = request.windowConfig,
            actions = request.actions,
            tags = request.tags,
            createdBy = request.createdBy.ifBlank { "ai-assistant" },
            updatedBy = request.createdBy.ifBlank { "ai-assistant" },
        )
        val created = profileService.create(profile)
        return objectMapper.writeValueAsString(
            mapOf(
                "success" to true,
                "profileId" to created.id,
                "name" to created.name,
                "status" to created.status.name,
                "message" to "Profile '${created.name}' created successfully with id=${created.id}. " +
                    "It is in DRAFT status. Call enable_profile to activate it.",
            ),
        )
    }

    private fun enableProfile(input: Map<String, Any>): String {
        val id = input["profile_id"] as? String ?: return """{"error": "profile_id is required"}"""
        val (profile, _) = profileService.enable(id)
        return objectMapper.writeValueAsString(
            mapOf(
                "success" to true,
                "profileId" to profile.id,
                "status" to profile.status.name,
                "message" to "Profile '${profile.name}' is now ENABLED. " +
                    "Windows will open according to the configured schedule.",
            ),
        )
    }

    private fun disableProfile(input: Map<String, Any>): String {
        val id = input["profile_id"] as? String ?: return """{"error": "profile_id is required"}"""
        val profile = profileService.disable(id)
        return objectMapper.writeValueAsString(
            mapOf(
                "success" to true,
                "profileId" to profile.id,
                "message" to "Profile '${profile.name}' is now DISABLED. No new windows will open.",
            ),
        )
    }
}

// ── Tool definitions ──────────────────────────────────────────────────────────
// Kept outside the class to keep the file readable.
// Input schemas use JSON Schema draft-07 (required by Anthropic tool_use API).

private val listProfilesTool = AnthropicTool(
    name = "list_profiles",
    description = "List all profiles in the platform, with optional filters. Returns id, name, status, trigger types, action count.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "status" to mapOf(
                "type" to "string",
                "description" to "Filter by status. One of: DRAFT, ENABLED, DISABLED, DELETED",
                "enum" to listOf("DRAFT", "ENABLED", "DISABLED", "DELETED"),
            ),
            "client_id" to mapOf(
                "type" to "string",
                "description" to "Filter by client ID",
            ),
        ),
        "required" to emptyList<String>(),
    ),
)

private val getProfileTool = AnthropicTool(
    name = "get_profile",
    description = "Get the full detail of a profile including windowConfig, all actions, and workflow steps.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "profile_id" to mapOf("type" to "string", "description" to "The profile ID (UUID)"),
        ),
        "required" to listOf("profile_id"),
    ),
)

private val listFileSpecsTool = AnthropicTool(
    name = "list_file_specs",
    description = "List all file specs (parsers) available in the platform. Returns id, name, format (XML, CSV, etc), field count.",
    inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "required" to emptyList<String>()),
)

private val listIntegrationsTool = AnthropicTool(
    name = "list_integrations",
    description = "List all configured integrations (SFTP, FTP, S3 connections). Returns id, type, enabled status, description.",
    inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "required" to emptyList<String>()),
)

private val listWindowsTool = AnthropicTool(
    name = "list_windows",
    description = "List windows (processing sessions) for a profile or the most recent 50 overall. " +
        "Includes status, open/close times, file count.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "profile_id" to mapOf("type" to "string", "description" to "Filter by profile ID"),
            "status" to mapOf(
                "type" to "string",
                "description" to "Filter by window status: PENDING, OPEN, CLOSING, CLOSED, FORCE_CLOSED",
            ),
        ),
        "required" to emptyList<String>(),
    ),
)

private val getExecutionsTool = AnthropicTool(
    name = "get_executions",
    description = "Get workflow execution history with step details, record counts, errors. Filter by window_id or profile_id.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "window_id" to mapOf("type" to "string", "description" to "Get executions for a specific window"),
            "profile_id" to mapOf("type" to "string", "description" to "Get recent executions for a profile"),
        ),
        "required" to emptyList<String>(),
    ),
)

private val createProfileTool = AnthropicTool(
    name = "create_profile",
    description = """
        Create a new profile. The profile is created in DRAFT status — call enable_profile to activate it.

        windowConfig.openTrigger must be one of:
          - {"type": "TIME_BASED", "openCron": "0 9 * * 1-5", "closeCron": "0 17 * * 1-5", "timeZone": "Europe/London"}
          - {"type": "FILE_ARRIVAL", "integrationId": "<id>", "filePattern": "*.xml", "pollInterval": "PT1M"}
          - {"type": "MANUAL"}

        closeTrigger follows the same pattern.

        actions[].condition must be one of: ON_FILE_ARRIVED, ON_CLOSING, ON_OPEN, ON_ERROR
        actions[].steps[].stepType must be one of: PARSE_FILE, VALIDATE, NOTIFY, GENERATE_FILE, DELIVER_FILE

        Example step configs:
          PARSE_FILE: {"fileSpecId": "<id>", "integrationId": "<id>"}
          VALIDATE:   {"rejectOnError": false}
          NOTIFY:     {"channel": "KAFKA"}
    """.trimIndent(),
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string"),
            "clientId" to mapOf("type" to "string"),
            "description" to mapOf("type" to "string"),
            "windowConfig" to mapOf("type" to "object", "description" to "WindowConfig with openTrigger and closeTrigger"),
            "actions" to mapOf("type" to "array", "description" to "List of Action objects"),
            "tags" to mapOf("type" to "object", "description" to "Optional key-value tags"),
            "createdBy" to mapOf("type" to "string"),
        ),
        "required" to listOf("name", "clientId", "windowConfig"),
    ),
)

private val enableProfileTool = AnthropicTool(
    name = "enable_profile",
    description = "Enable a DRAFT or DISABLED profile. This activates the Quartz scheduler and creates the first pending window.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "profile_id" to mapOf("type" to "string", "description" to "The profile ID to enable"),
            "enabled_by" to mapOf("type" to "string", "description" to "Who is enabling the profile"),
        ),
        "required" to listOf("profile_id"),
    ),
)

private val disableProfileTool = AnthropicTool(
    name = "disable_profile",
    description = "Disable an ENABLED profile. Stops new windows from opening. In-flight windows complete normally.",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "profile_id" to mapOf("type" to "string", "description" to "The profile ID to disable"),
            "disabled_by" to mapOf("type" to "string", "description" to "Who is disabling the profile"),
        ),
        "required" to listOf("profile_id"),
    ),
)
