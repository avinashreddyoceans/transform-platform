package com.transformplatform.api.persistence.repository

import com.transformplatform.api.persistence.entity.ProfileJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

// ── JpaProfileRepository ──────────────────────────────────────────────────────
//
// Spring Data JPA repository for the `profiles` table.
// This is the raw persistence layer — conversions to/from the domain
// Profile object happen in JpaProfileRepositoryAdapter.

interface JpaProfileRepository : JpaRepository<ProfileJpaEntity, String> {

    /** Find all profiles for a specific client (excludes DELETED). */
    fun findByClientIdAndStatusNot(clientId: String, status: String): List<ProfileJpaEntity>

    /** Find all profiles with a specific status. */
    fun findByStatus(status: String): List<ProfileJpaEntity>

    /** Find all non-deleted profiles, newest first. */
    @Query("SELECT p FROM ProfileJpaEntity p WHERE p.status <> 'DELETED' ORDER BY p.updatedAt DESC")
    fun findAllActive(): List<ProfileJpaEntity>

    /** Find all non-deleted profiles for a client, newest first. */
    @Query(
        """
        SELECT p FROM ProfileJpaEntity p
        WHERE p.clientId = :clientId AND p.status <> 'DELETED'
        ORDER BY p.updatedAt DESC
    """,
    )
    fun findActiveByClientId(@Param("clientId") clientId: String): List<ProfileJpaEntity>

    /** Update status directly (avoid loading the full entity for simple transitions). */
    @Modifying
    @Query("UPDATE ProfileJpaEntity p SET p.status = :status, p.updatedAt = CURRENT_TIMESTAMP WHERE p.id = :id")
    fun updateStatus(@Param("id") id: String, @Param("status") status: String): Int
}
