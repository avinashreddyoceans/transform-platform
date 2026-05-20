package com.transformplatform.api.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnTransformer
import java.time.Instant

// ── ProfileJpaEntity ──────────────────────────────────────────────────────────
//
// JPA representation of the `profiles` table.
//
// JSONB columns (window_config, actions, tags) are stored as Strings here and
// converted to/from domain objects (WindowConfig, List<Action>, Map<String,String>)
// by JpaProfileRepositoryAdapter using the application-configured ObjectMapper.
//
// Column → field mapping:
//   short_description  → name           (human-readable label; explicit @Column override)
//   client_id          → clientId
//   window_config      → windowConfig   (JSONB)
//   actions            → actions        (JSONB array)
//   tags               → tags           (JSONB)
//
// JSONB binding strategy:
//   Hibernate 6.x + PostgreSQL does not implicitly cast VARCHAR → JSONB.
//   @ColumnTransformer adds the explicit ::jsonb cast on write and ::text cast on
//   read so that Hibernate can bind/read String values to/from JSONB columns without
//   requiring @JdbcTypeCode(SqlTypes.JSON), which in Hibernate 6.4 uses setString()
//   (VARCHAR binding) and is rejected by PostgreSQL for JSONB columns.

@Entity
@Table(name = "profiles")
class ProfileJpaEntity {

    @Id
    var id: String = ""

    // Mapped to `short_description` column (renamed from `name` in schema v2).
    // The domain object still uses Profile.name; only the DB column is renamed.
    @Column(name = "short_description")
    var name: String = ""

    @Column(name = "client_id")
    var clientId: String = ""

    var description: String = ""

    // VARCHAR — matches ProfileStatus enum name
    var status: String = "DRAFT"

    var version: Int = 1

    // ── JSONB columns ──────────────────────────────────────────────────────────
    // Stored as JSON text; converted to domain types in the adapter layer.
    // @ColumnTransformer adds PostgreSQL-specific ?::jsonb cast on write so Hibernate
    // binds the String as JSONB rather than VARCHAR.

    @ColumnTransformer(read = "window_config::text", write = "?::jsonb")
    @Column(name = "window_config")
    var windowConfig: String = "{}"

    @ColumnTransformer(read = "actions::text", write = "?::jsonb")
    @Column(name = "actions")
    var actions: String = "[]"

    @ColumnTransformer(read = "tags::text", write = "?::jsonb")
    @Column(name = "tags")
    var tags: String = "{}"

    // ── Audit fields ──────────────────────────────────────────────────────────

    @Column(name = "created_by")
    var createdBy: String = "system"

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_by")
    var updatedBy: String = "system"

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
}
