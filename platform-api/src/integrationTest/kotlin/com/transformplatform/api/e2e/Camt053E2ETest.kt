package com.transformplatform.api.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

// ── Camt053E2ETest ────────────────────────────────────────────────────────────
//
// End-to-end validation of the full CAMT053 file processing pipeline:
//
//   REST API (create spec) → REST API (create profile) → REST API (enable)
//       → REST API (open window) → REST API (submit file)
//       → MinIO upload → XmlFileParser → TransformationPipeline
//       → KafkaRecordWriter (mocked) → workflow execution recorded
//       → window CLOSED → file log PROCESSED
//
// This test gives confidence that:
//   1. The XmlFileParser handles namespace-qualified ISO 20022 XML correctly
//      (via local-name() XPath to bypass namespace resolution)
//   2. The FileSpec → Profile → Window → Action chain persists and runs end-to-end
//   3. The FileArrival close trigger fires and invokes ON_FILE_ARRIVED actions
//   4. The PARSE_FILE workflow step reads from MinIO and streams through the pipeline
//   5. WorkflowExecution records capture the correct record count (15) and COMPLETED status
//   6. The file ledger (FileLog) transitions to PROCESSED
//
// Kafka is mocked so no broker is needed; records are still parsed and counted.
// MinIO is a real Testcontainers container (started by AbstractE2ETest).

@DisplayName("CAMT053 File Parsing — End to End")
class Camt053E2ETest : AbstractE2ETest() {

    @Test
    @DisplayName("parse all 15 CAMT053 Ntry elements and close window via FILE_ARRIVAL trigger")
    fun `camt053 file parses end to end through the full workflow pipeline`() {
        // ── Step 1: Register the CAMT053 FileSpec ─────────────────────────────
        //
        // CAMT053 uses the ISO 20022 default namespace:
        //   xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"
        //
        // The XmlFileParser is namespace-aware.  Without a NamespaceContext,
        // unprefixed XPath names only match elements with no namespace.
        // We use local-name() predicates to match elements regardless of namespace.
        //
        //   recordXPath: //*[local-name()='Ntry']
        //     → selects all <Ntry> elements anywhere in the document
        //
        //   field paths (relative to each Ntry element):
        //     *[local-name()='NtryRef']                        → entry reference
        //     *[local-name()='Amt']                            → amount (text content)
        //     *[local-name()='CdtDbtInd']                      → CRDT / DBIT
        //     *[local-name()='Sts']                            → BOOK etc.
        //     *[local-name()='BookgDt']/*[local-name()='DtTm'] → booking timestamp
        //     *[local-name()='BkTxCd']/*[local-name()='Prtry']/*[local-name()='Cd'] → tx code

        val specRequest = mapOf(
            "name" to "CAMT053 Bank Statement Entries",
            "description" to "ISO 20022 CAMT.053 bank-to-customer statement — Ntry level records",
            "version" to "1.0",
            "format" to "ISO20022",
            "encoding" to "UTF-8",
            "metadata" to mapOf("recordXPath" to "//*[local-name()='Ntry']"),
            "fields" to listOf(
                mapOf(
                    "name" to "entryRef",
                    "type" to "STRING",
                    "required" to false,
                    "nullable" to true,
                    "path" to "*[local-name()='NtryRef']",
                    "description" to "Entry reference number",
                ),
                mapOf(
                    "name" to "amount",
                    "type" to "STRING",
                    "required" to false,
                    "nullable" to true,
                    "path" to "*[local-name()='Amt']",
                    "description" to "Transaction amount",
                ),
                mapOf(
                    "name" to "creditDebitIndicator",
                    "type" to "STRING",
                    "required" to false,
                    "nullable" to true,
                    "path" to "*[local-name()='CdtDbtInd']",
                    "description" to "CRDT = credit, DBIT = debit",
                ),
                mapOf(
                    "name" to "status",
                    "type" to "STRING",
                    "required" to false,
                    "nullable" to true,
                    "path" to "*[local-name()='Sts']",
                    "description" to "Entry status (BOOK, PDNG, etc.)",
                ),
                mapOf(
                    "name" to "bookingDateTime",
                    "type" to "STRING",
                    "required" to false,
                    "nullable" to true,
                    "path" to "*[local-name()='BookgDt']/*[local-name()='DtTm']",
                    "description" to "Booking date/time",
                ),
                mapOf(
                    "name" to "transactionCode",
                    "type" to "STRING",
                    "required" to false,
                    "nullable" to true,
                    "path" to "*[local-name()='BkTxCd']/*[local-name()='Prtry']/*[local-name()='Cd']",
                    "description" to "Proprietary transaction code",
                ),
            ),
            "validationRules" to emptyList<Any>(),
            "correctionRules" to emptyList<Any>(),
        )

        val specResp = restTemplate.postForEntity("/api/v1/specs", specRequest, Map::class.java)
        assertThat(specResp.statusCode)
            .describedAs("Spec creation should return 201 CREATED")
            .isEqualTo(HttpStatus.CREATED)

        @Suppress("UNCHECKED_CAST")
        val specBody = specResp.body!! as Map<String, Any>
        val specId = specBody["id"] as String
        assertThat(specId).isNotBlank()
        assertThat(specBody["fieldCount"]).isEqualTo(6)

        // ── Step 2: Create a profile with FILE_ARRIVAL open + close triggers ──
        //
        // openTrigger  = FILE_ARRIVAL  → createNextPendingWindow() creates a
        //                               PENDING window (scheduledOpenAt = null)
        //                               which we open manually in step 5.
        //
        // closeTrigger = FILE_ARRIVAL  → FileArrivalTriggerEvaluator.evaluateOnEvent()
        //                               returns FIRE_AND_PURGE when the submitted file
        //                               matches the pattern, triggering the workflow.
        //
        // action condition = ON_FILE_ARRIVED → WorkflowOrchestratorImpl runs it
        //                               via executeOnFileArrived().

        val profileRequest = mapOf(
            "name" to "CAMT053 Daily Statement Processing",
            "clientId" to "e2e-test-client",
            "description" to "E2E validation profile for ISO 20022 CAMT053 parsing",
            "windowConfig" to mapOf(
                "openTrigger" to mapOf(
                    "type" to "FILE_ARRIVAL",
                    "integrationId" to "manual",
                    "filePattern" to "*.xml",
                ),
                "closeTrigger" to mapOf(
                    "type" to "FILE_ARRIVAL",
                    "integrationId" to "manual",
                    "filePattern" to "*.xml",
                ),
            ),
            "actions" to listOf(
                mapOf(
                    "name" to "Parse and Publish CAMT053 Entries",
                    "condition" to "ON_FILE_ARRIVED",
                    "executionOrder" to 10,
                    "continueOnFailure" to false,
                    "enabled" to true,
                    "steps" to listOf(
                        mapOf(
                            "name" to "Parse CAMT053 via XmlFileParser",
                            "stepType" to "PARSE_FILE",
                            "executionOrder" to 10,
                            "enabled" to true,
                            "config" to mapOf(
                                "fileSpecId" to specId,
                                "topic" to "bank.statement.entries",
                                "failOnParseError" to false,
                            ),
                        ),
                    ),
                ),
            ),
            "createdBy" to "camt053-e2e-test",
        )

        val profileResp = restTemplate.postForEntity("/api/profiles", profileRequest, Map::class.java)
        assertThat(profileResp.statusCode)
            .describedAs("Profile creation should return 201 CREATED")
            .isEqualTo(HttpStatus.CREATED)

        @Suppress("UNCHECKED_CAST")
        val profileBody = profileResp.body!! as Map<String, Any>
        val profileId = profileBody["id"] as String
        assertThat(profileId).isNotBlank()
        assertThat(profileBody["status"]).isEqualTo("DRAFT")

        // ── Step 3: Enable the profile ─────────────────────────────────────────
        //
        // For FILE_ARRIVAL openTrigger, WindowSchedulingService.createNextPendingWindow()
        // creates a PENDING window with scheduledOpenAt = null.

        val enableResp = restTemplate.postForEntity(
            "/api/profiles/$profileId/enable",
            null,
            Map::class.java,
        )
        assertThat(enableResp.statusCode)
            .describedAs("Profile enable should return 200 OK")
            .isEqualTo(HttpStatus.OK)
        assertThat(enableResp.body!!["status"]).isEqualTo("ENABLED")

        // ── Step 4: Get the PENDING window created on enable ───────────────────

        val windowsResp = restTemplate.getForEntity(
            "/api/profiles/$profileId/windows",
            List::class.java,
        )
        assertThat(windowsResp.statusCode).isEqualTo(HttpStatus.OK)
        val windows = windowsResp.body!!
        assertThat(windows)
            .withFailMessage("Expected a PENDING window to exist after profile enable")
            .isNotEmpty

        @Suppress("UNCHECKED_CAST")
        val windowSummary = windows[0] as Map<String, Any>
        val windowId = windowSummary["id"] as String
        assertThat(windowId).isNotBlank()
        assertThat(windowSummary["status"]).isEqualTo("PENDING")

        // ── Step 5: Open the window (PENDING → OPEN) ───────────────────────────
        //
        // WindowController.openWindow() calls WindowStateService.open() which
        // transitions the window and sets openedAt.

        val openResp = restTemplate.postForEntity(
            "/api/windows/$windowId/open",
            null,
            Map::class.java,
        )
        assertThat(openResp.statusCode)
            .describedAs("Manual window open should return 200 OK")
            .isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val openBody = openResp.body!! as Map<String, Any>
        assertThat(openBody["status"]).isEqualTo("OPEN")
        assertThat(openBody["windowId"]).isEqualTo(windowId)

        // ── Step 6: Submit the CAMT053 XML file ───────────────────────────────
        //
        // FileLogController.submitFileToWindow() performs the full E2E pipeline:
        //   1. Upload file bytes to MinIO via S3ArchivalService.archive()
        //   2. Create FileLog entry (status = RECEIVED)
        //   3. FileArrivalHandlerService.onFileArrived():
        //      a. Build FileHandle with MinIO storage key
        //      b. Add FileHandle to window.arrivedFileHandles
        //      c. Evaluate FILE_ARRIVAL closeTrigger → FIRE_AND_PURGE
        //      d. WorkflowOrchestratorImpl.executeOnFileArrived()
        //         → PARSE_FILE step: retrieve from MinIO, parse XML, write to Kafka (mocked)
        //      e. Transition window: OPEN → CLOSING → CLOSED
        //      f. Update FileLog status → PROCESSED

        val camt053Bytes = Camt053E2ETest::class.java.classLoader
            .getResourceAsStream("fixtures/camt053_sample_file.xml")!!
            .readBytes()

        // ByteArrayResource with getFilename() override — Spring MVC uses this
        // for Content-Disposition; MultipartFile.originalFilename returns "camt053_sample_file.xml".
        val fileResource = object : ByteArrayResource(camt053Bytes) {
            override fun getFilename() = "camt053_sample_file.xml"
        }

        val multipartHeaders = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
        }
        val multipartBody = LinkedMultiValueMap<String, Any>().apply {
            add("file", fileResource)
        }

        val submitResp = restTemplate.postForEntity(
            "/api/windows/$windowId/submit-file",
            HttpEntity(multipartBody, multipartHeaders),
            Map::class.java,
        )
        assertThat(submitResp.statusCode)
            .describedAs("submit-file should return 200 OK")
            .isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val submitBody = submitResp.body!! as Map<String, Any>
        assertThat(submitBody["matched"])
            .describedAs("File should match the open window's FILE_ARRIVAL trigger")
            .isEqualTo(true)
        assertThat(submitBody["windowId"]).isEqualTo(windowId)
        assertThat(submitBody["fileName"]).isEqualTo("camt053_sample_file.xml")

        // The CAMT053 sample file contains 15 Ntry elements.
        // All fields are optional (required=false) so all 15 should parse successfully.
        val recordsProcessed = submitBody["recordsProcessed"] as Int
        assertThat(recordsProcessed)
            .describedAs("Should have parsed all 15 CAMT053 Ntry elements successfully")
            .isEqualTo(15)

        // ── Step 7: Verify window transitioned to CLOSED ───────────────────────

        val windowDetailResp = restTemplate.getForEntity("/api/windows/$windowId", Map::class.java)
        assertThat(windowDetailResp.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val windowDetail = windowDetailResp.body!! as Map<String, Any>
        assertThat(windowDetail["status"])
            .describedAs("Window should be CLOSED after FILE_ARRIVAL close trigger fired")
            .isEqualTo("CLOSED")
        assertThat(windowDetail["id"]).isEqualTo(windowId)

        @Suppress("UNCHECKED_CAST")
        val arrivedHandles = windowDetail["arrivedFileHandles"] as List<*>
        assertThat(arrivedHandles)
            .describedAs("Window should record exactly 1 arrived file handle")
            .hasSize(1)

        @Suppress("UNCHECKED_CAST")
        val handle = arrivedHandles[0] as Map<String, Any>
        assertThat(handle["fileName"]).isEqualTo("camt053_sample_file.xml")
        assertThat(handle["integrationId"]).isEqualTo("manual")

        // ── Step 8: Verify FileLog entry is PROCESSED ─────────────────────────

        val filesResp = restTemplate.getForEntity("/api/windows/$windowId/files", List::class.java)
        assertThat(filesResp.statusCode).isEqualTo(HttpStatus.OK)
        val fileLogs = filesResp.body!!
        assertThat(fileLogs)
            .withFailMessage("Expected exactly 1 file log entry for the submitted file")
            .hasSize(1)

        @Suppress("UNCHECKED_CAST")
        val fileLog = fileLogs[0] as Map<String, Any>
        assertThat(fileLog["fileName"]).isEqualTo("camt053_sample_file.xml")
        assertThat(fileLog["status"])
            .describedAs("FileLog should be PROCESSED after successful pipeline run")
            .isEqualTo("PROCESSED")
        assertThat(fileLog["direction"]).isEqualTo("INBOUND")
        assertThat(fileLog["windowId"]).isEqualTo(windowId)

        // ── Step 9: Verify WorkflowExecution completed successfully ────────────

        val execsResp = restTemplate.getForEntity(
            "/api/windows/$windowId/executions",
            List::class.java,
        )
        assertThat(execsResp.statusCode).isEqualTo(HttpStatus.OK)
        val executions = execsResp.body!!
        assertThat(executions)
            .withFailMessage(
                "Expected 1 workflow execution (ON_FILE_ARRIVED action) for window $windowId",
            )
            .hasSize(1)

        @Suppress("UNCHECKED_CAST")
        val execution = executions[0] as Map<String, Any>
        assertThat(execution["status"])
            .describedAs("WorkflowExecution should be COMPLETED")
            .isEqualTo("COMPLETED")
        assertThat(execution["actionName"]).isEqualTo("Parse and Publish CAMT053 Entries")
        assertThat(execution["totalRecordsProcessed"] as Int)
            .describedAs("WorkflowExecution should report 15 records from the PARSE_FILE step")
            .isEqualTo(15)
        assertThat(execution["windowInstanceId"]).isEqualTo(windowId)
    }
}
