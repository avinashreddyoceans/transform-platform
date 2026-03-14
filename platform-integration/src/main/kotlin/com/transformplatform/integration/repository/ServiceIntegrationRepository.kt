package com.transformplatform.integration.repository

import com.transformplatform.integration.model.IntegrationType
import com.transformplatform.integration.model.ServiceIntegration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ServiceIntegrationRepository : JpaRepository<ServiceIntegration, String> {

    /** All enabled integrations — used by DynamicRouteManager on startup. */
    fun findAllByIsEnabledTrue(): List<ServiceIntegration>

    /** All integrations for a given user. */
    fun findAllByUserId(userId: String): List<ServiceIntegration>

    /** All integrations of a given connector type. */
    fun findAllByType(type: IntegrationType): List<ServiceIntegration>

    fun existsByShortDescriptionAndUserId(shortDescription: String, userId: String): Boolean
}
