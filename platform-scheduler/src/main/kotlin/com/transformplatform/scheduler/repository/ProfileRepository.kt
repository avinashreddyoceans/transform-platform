package com.transformplatform.scheduler.repository

import com.transformplatform.common.domain.profile.Profile
import com.transformplatform.common.domain.profile.ProfileStatus
import java.util.concurrent.ConcurrentHashMap

// ── ProfileRepository ─────────────────────────────────────────────────────────
//
// Contract for Profile persistence.
//
// Profile is a config aggregate. This repository contains only config-oriented
// operations: save, find, list. There are NO scheduling timestamp methods here —
// those live on WindowInstance (scheduledOpenAt / scheduledCloseAt).
//
// Phase 0 implementation: InMemoryProfileRepository (ConcurrentHashMap).
// Phase 1a: replace with JPA repository backed by the `profiles` table.
//
// The table structure:
//   profiles
//     id           UUID PK
//     name         VARCHAR
//     client_id    VARCHAR    (indexed — most queries are scoped per client)
//     status       VARCHAR    (DRAFT / ENABLED / DISABLED / DELETED)
//     version      INT
//     window_config JSONB     (WindowConfig sealed hierarchy)
//     actions       JSONB     (List<Action> with WorkflowSteps)
//     tags          JSONB
//     created_by   VARCHAR
//     created_at   TIMESTAMPTZ
//     updated_by   VARCHAR
//     updated_at   TIMESTAMPTZ
//
// No next_open_at, no next_close_at — those belong on windows.

interface ProfileRepository {

    fun findById(id: String): Profile?

    /** Upsert — create or replace. Returns the saved instance. */
    fun save(profile: Profile): Profile

    /**
     * Find profiles by optional filters.
     * status=null returns all non-deleted profiles.
     * Phase 1a: add pagination.
     */
    fun findAll(clientId: String? = null, status: ProfileStatus? = null): List<Profile>

    /**
     * Convenience: find all ENABLED profiles.
     * Called by WindowSchedulingService on startup to restore PENDING windows
     * for profiles that were ENABLED when the server last shut down.
     */
    fun findEnabled(): List<Profile> = findAll(status = ProfileStatus.ENABLED)
}

// ── InMemoryProfileRepository ─────────────────────────────────────────────────
//
// Temporary in-memory implementation. Thread-safe via ConcurrentHashMap.
// Replaced by JPA in Phase 1a.

class InMemoryProfileRepository : ProfileRepository {

    private val store = ConcurrentHashMap<String, Profile>()

    override fun findById(id: String): Profile? = store[id]

    override fun save(profile: Profile): Profile = profile.also { store[it.id] = it }

    override fun findAll(clientId: String?, status: ProfileStatus?): List<Profile> = store.values
        .filter { clientId == null || it.clientId == clientId }
        .filter { status == null || it.status == status }
        .filter { it.status != ProfileStatus.DELETED }
        .sortedByDescending { it.updatedAt }
}
