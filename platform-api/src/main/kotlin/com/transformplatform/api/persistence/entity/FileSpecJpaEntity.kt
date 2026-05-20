package com.transformplatform.api.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnTransformer
import java.time.Instant

@Entity
@Table(name = "file_specs")
class FileSpecJpaEntity {

    @Id
    @Column(name = "id", nullable = false, length = 255)
    var id: String = ""

    @Column(name = "name", nullable = false, length = 255)
    var name: String = ""

    @Column(name = "description", nullable = false)
    var description: String = ""

    @Column(name = "version", nullable = false, length = 50)
    var version: String = "1.0"

    /** FileFormat enum name */
    @Column(name = "format", nullable = false, length = 50)
    var format: String = ""

    @Column(name = "encoding", nullable = false, length = 50)
    var encoding: String = "UTF-8"

    @Column(name = "has_header", nullable = false)
    var hasHeader: Boolean = false

    @Column(name = "delimiter", length = 10)
    var delimiter: String? = null

    @Column(name = "record_separator", nullable = false, length = 10)
    var recordSeparator: String = "\n"

    @Column(name = "skip_lines_count", nullable = false)
    var skipLinesCount: Int = 0

    /** List<FieldSpec> serialised as JSON */
    @ColumnTransformer(read = "fields::text", write = "?::jsonb")
    @Column(name = "fields", nullable = false)
    var fields: String = "[]"

    /** List<ValidationRule> serialised as JSON */
    @ColumnTransformer(read = "validation_rules::text", write = "?::jsonb")
    @Column(name = "validation_rules", nullable = false)
    var validationRules: String = "[]"

    /** List<CorrectionRule> serialised as JSON */
    @ColumnTransformer(read = "correction_rules::text", write = "?::jsonb")
    @Column(name = "correction_rules", nullable = false)
    var correctionRules: String = "[]"

    /** OutputSpec? serialised as JSON (null when no output transformation configured) */
    @ColumnTransformer(read = "output_spec::text", write = "?::jsonb")
    @Column(name = "output_spec")
    var outputSpec: String? = null

    /** Map<String, String> serialised as JSON */
    @ColumnTransformer(read = "metadata::text", write = "?::jsonb")
    @Column(name = "metadata", nullable = false)
    var metadata: String = "{}"

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @Column(name = "created_by", nullable = false, length = 255)
    var createdBy: String = "system"
}
