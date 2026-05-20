package com.transformplatform.api.persistence.repository

import com.transformplatform.api.persistence.entity.WorkflowStepExecutionJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface JpaWorkflowStepExecutionRepository : JpaRepository<WorkflowStepExecutionJpaEntity, String> {

    // Derived query uses entity field name `executionId` (mapped to DB column `execution_id`)
    fun findByExecutionIdOrderByStepIndexAscAttemptAsc(executionId: String): List<WorkflowStepExecutionJpaEntity>

    @Modifying
    @Query("DELETE FROM WorkflowStepExecutionJpaEntity s WHERE s.executionId = :executionId")
    fun deleteByExecutionId(@Param("executionId") executionId: String)
}
