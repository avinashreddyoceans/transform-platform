package com.transformplatform.api.controller

import com.transformplatform.api.dto.CreateSpecRequest
import com.transformplatform.api.dto.SpecResponse
import com.transformplatform.api.service.SpecService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/specs")
@Tag(name = "Spec Management", description = "Create and manage file format specifications")
class SpecController(private val specService: SpecService) {

    @PostMapping
    @Operation(summary = "Create a new file spec", description = "Register a file format specification to drive parsing and validation")
    fun createSpec(@Valid @RequestBody request: CreateSpecRequest): ResponseEntity<SpecResponse> {
        log.info { "Creating spec: ${request.name}" }
        val spec = specService.createSpec(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(spec)
    }

    @GetMapping
    @Operation(summary = "List all specs")
    fun listSpecs(
        @RequestParam(required = false) format: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<List<SpecResponse>> {
        return ResponseEntity.ok(specService.listSpecs(format, page, size))
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get spec by ID")
    fun getSpec(@PathVariable id: String): ResponseEntity<SpecResponse> {
        return ResponseEntity.ok(specService.getSpec(id))
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing spec")
    fun updateSpec(
        @PathVariable id: String,
        @Valid @RequestBody request: CreateSpecRequest
    ): ResponseEntity<SpecResponse> {
        return ResponseEntity.ok(specService.updateSpec(id, request))
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a spec")
    fun deleteSpec(@PathVariable id: String): ResponseEntity<Void> {
        specService.deleteSpec(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/validate")
    @Operation(summary = "Validate a spec definition", description = "Checks the spec is complete and valid without saving")
    fun validateSpec(@PathVariable id: String): ResponseEntity<Map<String, Any>> {
        val result = specService.validateSpec(id)
        return ResponseEntity.ok(result)
    }
}
