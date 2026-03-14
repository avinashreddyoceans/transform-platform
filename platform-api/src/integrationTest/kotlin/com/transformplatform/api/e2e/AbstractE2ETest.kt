package com.transformplatform.api.e2e

import com.ninjasquad.springmockk.MockkBean
import com.transformplatform.api.TransformPlatformApplication
import com.transformplatform.integration.camel.DynamicRouteManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Base class for all end-to-end integration tests.
 *
 * Spins up:
 *  - **PostgreSQL 15** (Testcontainers) — full schema applied via Flyway on every boot
 *  - **MinIO** (Testcontainers) — S3-compatible archival store
 *
 * [DynamicRouteManager] is replaced with a MockK mock so that no real Camel routes
 * are registered (we have no real SFTP/FTP/S3 servers in CI).  This lets us focus
 * E2E tests entirely on the REST API ↔ database ↔ MinIO flow.
 *
 * All containers are **static** (shared across all tests in the JVM) so they start
 * only once per test run.
 */
@SpringBootTest(
    classes = [TransformPlatformApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("integration-test")
@Testcontainers
abstract class AbstractE2ETest {

    // ── Mocked Camel route manager ─────────────────────────────────────────────
    // Prevents real SFTP / FTP / S3 connections during tests.

    @MockkBean(relaxed = true)
    lateinit var dynamicRouteManager: DynamicRouteManager

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @BeforeEach
    fun setupMocks() {
        // Ensure route manager calls are silent no-ops
        every { dynamicRouteManager.activateRoute(any()) } just Runs
        every { dynamicRouteManager.deactivateRoute(any()) } just Runs
        every { dynamicRouteManager.removeRoute(any()) } just Runs
        every { dynamicRouteManager.reloadRoute(any()) } just Runs
    }

    companion object {

        // ── PostgreSQL container ───────────────────────────────────────────────
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("postgres:15-alpine"),
        ).apply {
            withDatabaseName("transform_platform_test")
            withUsername("test_user")
            withPassword("test_pass")
            withReuse(true) // reuse between Gradle test tasks (faster CI)
        }

        // ── MinIO container ────────────────────────────────────────────────────
        // Used by S3ArchivalService for file storage
        @Container
        @JvmStatic
        val minio: GenericContainer<*> = GenericContainer(
            DockerImageName.parse("minio/minio:RELEASE.2024-03-21T23-13-43Z"),
        ).apply {
            withCommand("server /data --console-address :9001")
            withExposedPorts(9000)
            withEnv("MINIO_ROOT_USER", "minioadmin")
            withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            waitingFor(
                Wait.forHttp("/minio/health/live")
                    .forPort(9000)
                    .withStartupTimeout(java.time.Duration.ofSeconds(60)),
            )
            withReuse(true)
        }

        // ── Dynamic Spring Boot properties ────────────────────────────────────
        // Override datasource + MinIO URLs with the container-assigned ports.

        @DynamicPropertySource
        @JvmStatic
        fun registerContainerProperties(registry: DynamicPropertyRegistry) {
            // PostgreSQL
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            // MinIO — use the mapped port so we can hit the container from the host
            registry.add("transform-platform.minio.endpoint") {
                "http://${minio.host}:${minio.getMappedPort(9000)}"
            }
            registry.add("transform-platform.minio.access-key") { "minioadmin" }
            registry.add("transform-platform.minio.secret-key") { "minioadmin" }
            registry.add("transform-platform.minio.downloads-bucket") { "transform-downloads-test" }
        }
    }
}
