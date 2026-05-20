package com.transformplatform.api.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

// ── WindowLifecycleE2ETest ────────────────────────────────────────────────────
//
// End-to-end tests for the new window lifecycle management endpoints:
//
//   POST /api/windows/{id}/close      — force close (operator override)
//   POST /api/windows/{id}/reprocess  — re-run ON_CLOSING actions
//   GET  /api/windows/{id}/events     — paginated event list
//   POST /api/profiles/{id}/validate  — validate config without saving
//
// All four tests share the same setup: create spec → create profile → enable
// → get PENDING window → open it.  From that OPEN window state each test
// exercises a different lifecycle operation.

@DisplayName("Window Lifecycle — Force Close, Reprocess, Events, Validate")
class WindowLifecycleE2ETest : AbstractE2ETest() {

    // ── Shared profile + window helpers ───────────────────────────────────────

    private fun createSpecAndProfile(): Pair<String, String> {
        // Minimal CSV FileSpec (required by PARSE_FILE step in reprocess test)
        val specRequest = mapOf(
            "name" to "lifecycle-test-csv-spec",
            "version" to "1.0",
            "format" to "CSV",
            "encoding" to "UTF-8",
            "hasHeader" to true,
            "delimiter" to ",",
            "fields" to listOf(
                mapOf("name" to "id", "type" to "STRING", "required" to false, "nullable" to true),
                mapOf("name" to "value", "type" to "STRING", "required" to false, "nullable" to true),
            ),
            "validationRules" to emptyList<Any>(),
            "correctionRules" to emptyList<Any>(),
        )

        @Suppress("UNCHECKED_CAST")
        val specBody = restTemplate.postForEntity("/api/v1/specs", specRequest, Map::class.java)
            .body!! as Map<String, Any>
        val specId = specBody["id"] as String

        val profileRequest = mapOf(
            "name" to "lifecycle-test-profile",
            "clientId" to "lifecycle-test-client",
            "description" to "Profile for WindowLifecycleE2ETest",
            "windowConfig" to mapOf(
                "openTrigger" to mapOf("type" to "MANUAL"),
                "closeTrigger" to mapOf("type" to "MANUAL"),
            ),
            "actions" to listOf(
                mapOf(
                    "name" to "On Closing — parse and publish",
                    "condition" to "ON_CLOSING",
                    "executionOrder" to 10,
                    "continueOnFailure" to true,
                    "enabled" to true,
                    "steps" to listOf(
                        mapOf(
                            "name" to "Parse CSV",
                            "stepType" to "PARSE_FILE",
                            "executionOrder" to 10,
                            "enabled" to true,
                            "config" to mapOf(
                                "fileSpecId" to specId,
                                "topic" to "lifecycle.test.events",
                                "failOnParseError" to false,
                            ),
                        ),
                    ),
                ),
            ),
            "createdBy" to "lifecycle-e2e-test",
        )

        @Suppress("UNCHECKED_CAST")
        val profileBody = restTemplate.postForEntity("/api/profiles", profileRequest, Map::class.java)
            .body!! as Map<String, Any>
        val profileId = profileBody["id"] as String

        // Enable profile → creates PENDING window
        restTemplate.postForEntity("/api/profiles/$profileId/enable", null, Map::class.java)

        return Pair(profileId, specId)
    }

    private fun getPendingWindowId(profileId: String): String {
        @Suppress("UNCHECKED_CAST")
        val windows = restTemplate.getForEntity(
            "/api/profiles/$profileId/windows",
            List::class.java,
        ).body!! as List<Map<String, Any>>

        assertThat(windows).isNotEmpty
        return windows[0]["id"] as String
    }

    private fun openWindow(windowId: String) {
        val resp = restTemplate.postForEntity("/api/windows/$windowId/open", null, Map::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body!!["status"]).isEqualTo("OPEN")
    }

    // ── Test 1: Force close ───────────────────────────────────────────────────

    @Test
    @DisplayName("force close an OPEN window via POST /close and verify FORCE_CLOSED status")
    fun `force close transitions OPEN window to FORCE_CLOSED`() {
        val (profileId, _) = createSpecAndProfile()
        val windowId = getPendingWindowId(profileId)
        openWindow(windowId)

        // Verify it's OPEN
        val beforeDetail = restTemplate.getForEntity("/api/windows/$windowId", Map::class.java).body!!
        assertThat(beforeDetail["status"]).isEqualTo("OPEN")

        // Force close
        val closeResp = restTemplate.postForEntity("/api/windows/$windowId/close", null, Map::class.java)
        assertThat(closeResp.statusCode)
            .describedAs("force-close should return 200 OK")
            .isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val closeBody = closeResp.body!! as Map<String, Any>
        assertThat(closeBody["windowId"]).isEqualTo(windowId)
        assertThat(closeBody["status"]).isEqualTo("FORCE_CLOSED")

        // Verify persisted
        @Suppress("UNCHECKED_CAST")
        val afterDetail = restTemplate.getForEntity("/api/windows/$windowId", Map::class.java)
            .body!! as Map<String, Any>
        assertThat(afterDetail["status"])
            .describedAs("Window must be FORCE_CLOSED after the force-close API call")
            .isEqualTo("FORCE_CLOSED")
    }

    @Test
    @DisplayName("attempting to force close an already-terminal window returns 400")
    fun `force close a CLOSED window returns 400`() {
        val (profileId, _) = createSpecAndProfile()
        val windowId = getPendingWindowId(profileId)
        openWindow(windowId)

        // First close succeeds
        restTemplate.postForEntity("/api/windows/$windowId/close", null, Map::class.java)

        // Second close on the now-terminal window should return 400
        val secondCloseResp = restTemplate.postForEntity("/api/windows/$windowId/close", null, Map::class.java)
        assertThat(secondCloseResp.statusCode)
            .describedAs("force-close on terminal window should return 400")
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    // ── Test 2: Reprocess ─────────────────────────────────────────────────────

    @Test
    @DisplayName("reprocess a FORCE_CLOSED window — re-runs ON_CLOSING actions, returns 200")
    fun `reprocess runs ON_CLOSING actions on a force-closed window`() {
        val (profileId, _) = createSpecAndProfile()
        val windowId = getPendingWindowId(profileId)
        openWindow(windowId)

        // Force close the window
        restTemplate.postForEntity("/api/windows/$windowId/close", null, Map::class.java)

        // Verify FORCE_CLOSED
        val detail = restTemplate.getForEntity("/api/windows/$windowId", Map::class.java).body!!
        assertThat(detail["status"]).isEqualTo("FORCE_CLOSED")

        // Reprocess
        val reprocessResp = restTemplate.postForEntity(
            "/api/windows/$windowId/reprocess",
            null,
            Map::class.java,
        )
        assertThat(reprocessResp.statusCode)
            .describedAs("reprocess should return 200 OK")
            .isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val reprocessBody = reprocessResp.body!! as Map<String, Any>
        assertThat(reprocessBody["windowId"]).isEqualTo(windowId)
        assertThat(reprocessBody["profileId"]).isEqualTo(profileId)
        assertThat(reprocessBody["recordsProcessed"]).isNotNull

        // A WorkflowExecution row was created for the reprocessing run
        @Suppress("UNCHECKED_CAST")
        val executions = restTemplate.getForEntity(
            "/api/windows/$windowId/executions",
            List::class.java,
        ).body!! as List<Map<String, Any>>

        assertThat(executions)
            .describedAs("At least one WorkflowExecution should be recorded after reprocess")
            .isNotEmpty
    }

    @Test
    @DisplayName("reprocess on an OPEN window returns 400")
    fun `reprocess on OPEN window returns 400`() {
        val (profileId, _) = createSpecAndProfile()
        val windowId = getPendingWindowId(profileId)
        openWindow(windowId)

        val reprocessResp = restTemplate.postForEntity(
            "/api/windows/$windowId/reprocess",
            null,
            Map::class.java,
        )
        assertThat(reprocessResp.statusCode)
            .describedAs("reprocess on OPEN window should return 400")
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    // ── Test 3: Events endpoint ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/windows/{id}/events returns 200 with events wrapper")
    fun `get window events returns 200 with total and events array`() {
        val (profileId, _) = createSpecAndProfile()
        val windowId = getPendingWindowId(profileId)
        openWindow(windowId)

        val eventsResp = restTemplate.getForEntity("/api/windows/$windowId/events", Map::class.java)
        assertThat(eventsResp.statusCode)
            .describedAs("GET /events should return 200 OK")
            .isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val eventsBody = eventsResp.body!! as Map<String, Any>
        assertThat(eventsBody["windowId"]).isEqualTo(windowId)
        assertThat(eventsBody["total"]).isEqualTo(0) // no events yet
        assertThat(eventsBody["events"]).isNotNull
    }

    // ── Test 4: Profile validate endpoint ────────────────────────────────────

    @Test
    @DisplayName("POST /api/profiles/{id}/validate returns 200 valid=true for correct config")
    fun `validate endpoint returns 200 for valid config`() {
        val (profileId, _) = createSpecAndProfile()

        val validConfig = mapOf(
            "name" to "lifecycle-test-profile",
            "clientId" to "lifecycle-test-client",
            "windowConfig" to mapOf(
                "openTrigger" to mapOf("type" to "MANUAL"),
                "closeTrigger" to mapOf("type" to "MANUAL"),
            ),
            "actions" to emptyList<Any>(),
        )

        val validateResp = restTemplate.postForEntity(
            "/api/profiles/$profileId/validate",
            validConfig,
            Map::class.java,
        )
        assertThat(validateResp.statusCode)
            .describedAs("validate should return 200 OK for valid config")
            .isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val validateBody = validateResp.body!! as Map<String, Any>
        assertThat(validateBody["valid"])
            .describedAs("valid should be true for a correct config")
            .isEqualTo(true)

        @Suppress("UNCHECKED_CAST")
        val errors = validateBody["errors"] as List<*>
        assertThat(errors).isEmpty()
    }
}
