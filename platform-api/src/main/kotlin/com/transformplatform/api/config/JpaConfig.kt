package com.transformplatform.api.config

import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * Registers JPA entity scanning and repository scanning across all com.transformplatform
 * sub-modules (platform-common, platform-core, platform-integration, platform-scheduler).
 *
 * Declared as a Spring Boot auto-configuration (not a component-scanned @Configuration)
 * so that @WebMvcTest slices, which disable auto-configuration, never load this class.
 * This prevents the "No EntityManagerFactory available" failures that occur when
 * @EnableJpaRepositories is processed in a web-layer test context.
 *
 * Ordering:
 *   after  HibernateJpaAutoConfiguration — ensures EntityManagerFactory exists first
 *   before JpaRepositoriesAutoConfiguration — our explicit basePackages take priority
 *                                             over Spring Boot's default package scan
 */
@AutoConfiguration(
    after = [HibernateJpaAutoConfiguration::class, TaskExecutionAutoConfiguration::class],
    before = [JpaRepositoriesAutoConfiguration::class],
)
@ConditionalOnBean(EntityManagerFactory::class)
@EntityScan(basePackages = ["com.transformplatform"])
@EnableJpaRepositories(basePackages = ["com.transformplatform"])
class JpaConfig
