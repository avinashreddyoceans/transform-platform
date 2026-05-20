package com.transformplatform.api.persistence.repository

import com.transformplatform.api.persistence.entity.WorkflowExecutionJpaEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface JpaWorkflowExecutionRepository : JpaRepository<WorkflowExecutionJpaEntity, String> {

    // Derived query uses entity field name `windowId` (mapped to DB column `window_id`)
    fun findByWindowIdOrderByLastUpdatedAtDesc(windowId: String): List<WorkflowExecutionJpaEntity>

    fun findByProfileIdOrderByLastUpdatedAtDesc(profileId: String, pageable: Pageable): List<WorkflowExecutionJpaEntity>

    fun findByStatusIn(statuses: List<String>): List<WorkflowExecutionJpaEntity>

    @Query("SELECT e FROM WorkflowExecutionJpaEntity e ORDER BY e.lastUpdatedAt DESC")
    fun findAllPaged(pageable: Pageable): List<WorkflowExecutionJpaEntity>

    /**
     * Executions in a given status whose [startedAt] is before [before].
     * Used by WorkflowMonitorService to detect executions that have been RUNNING
     * for longer than the configured stuck-threshold, indicating a hung process.
     */
    fun findByStatusAndStartedAtBefore(status: String, before: Instant): List<WorkflowExecutionJpaEntity>
}
