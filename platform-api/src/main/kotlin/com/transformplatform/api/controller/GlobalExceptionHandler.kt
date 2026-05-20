package com.transformplatform.api.controller

import mu.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

private val log = KotlinLogging.logger {}

// ── GlobalExceptionHandler ────────────────────────────────────────────────────
//
// Centralised error-to-HTTP mapping for all REST controllers.
//
// Spring's default behaviour for unhandled exceptions is to delegate to
// BasicErrorController which returns a generic 500 JSON body with almost no
// useful information.  Each mapping here converts a specific exception type to
// the appropriate 4xx / 5xx status and a structured { "error": "…" } body that
// callers can depend on.
//
// Ordering:
//   More specific exception types are listed first.  Spring picks the most
//   specific handler that matches the thrown exception.

@RestControllerAdvice
class GlobalExceptionHandler {

    // ── 404 Not Found ──────────────────────────────────────────────────────────

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<ErrorBody> {
        log.debug { "Not found: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorBody(ex.message ?: "Resource not found"))
    }

    // ── 400 Bad Request ────────────────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<ErrorBody> {
        log.debug { "Bad request: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorBody(ex.message ?: "Bad request"))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorBody> {
        val msg = ex.bindingResult.fieldErrors
            .joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
            .ifBlank { "Validation failed" }
        log.debug { "Validation error: $msg" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorBody(msg))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ResponseEntity<ErrorBody> {
        // Strip the verbose Jackson internals from the message — surface only the
        // human-readable part before the first newline / "at [Source:" section.
        val clean = ex.message
            ?.substringBefore("\n at [Source:")
            ?.substringBefore("; nested exception")
            ?.take(300)
            ?: "Request body could not be parsed"
        log.debug { "Unreadable request body: $clean" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorBody(clean))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ErrorBody> {
        val msg = "Invalid value '${ex.value}' for parameter '${ex.name}'"
        log.debug { msg }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorBody(msg))
    }

    // ── 409 Conflict ───────────────────────────────────────────────────────────

    /**
     * Handles business-logic conflicts raised explicitly by service code.
     * Example: enabling a profile that is already ENABLED.
     */
    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(ex: IllegalStateException): ResponseEntity<ErrorBody> {
        log.debug { "Conflict: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorBody(ex.message ?: "Conflict"))
    }

    /**
     * Handles database unique-constraint and FK violations.
     *
     * Spring wraps the JDBC [org.postgresql.util.PSQLException] inside
     * [DataIntegrityViolationException].  We extract the detail from the root
     * cause and map well-known constraint names to user-friendly messages.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(ex: DataIntegrityViolationException): ResponseEntity<ErrorBody> {
        val rootMsg = ex.rootCause?.message ?: ex.message ?: ""
        val friendly = when {
            rootMsg.contains("uq_profile_desc_client") ->
                "A profile with this name already exists for this client."
            rootMsg.contains("uq_") || rootMsg.contains("unique constraint") ->
                "A record with these values already exists (unique constraint violation)."
            rootMsg.contains("foreign key") || rootMsg.contains("fk_") ->
                "A referenced record does not exist (foreign key constraint violation)."
            else ->
                "The request violates a database constraint."
        }
        log.warn { "Data integrity violation: $rootMsg" }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorBody(friendly))
    }

    // ── 500 Internal Server Error (catch-all) ─────────────────────────────────

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorBody> {
        log.error(ex) { "Unexpected error: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorBody("An unexpected error occurred. Please try again or contact support."))
    }
}

// ── Response body ─────────────────────────────────────────────────────────────

data class ErrorBody(val error: String)
