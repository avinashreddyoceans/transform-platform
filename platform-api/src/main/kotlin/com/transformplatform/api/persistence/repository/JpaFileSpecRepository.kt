package com.transformplatform.api.persistence.repository

import com.transformplatform.api.persistence.entity.FileSpecJpaEntity
import org.springframework.data.jpa.repository.JpaRepository

interface JpaFileSpecRepository : JpaRepository<FileSpecJpaEntity, String> {

    fun findByFormat(format: String): List<FileSpecJpaEntity>

    fun findByNameAndVersion(name: String, version: String): FileSpecJpaEntity?

    fun existsByNameAndVersion(name: String, version: String): Boolean
}
