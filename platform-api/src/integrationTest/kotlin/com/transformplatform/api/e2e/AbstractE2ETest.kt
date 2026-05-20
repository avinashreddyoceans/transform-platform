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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Base class for all end-to-end integration tests.
 *
 * Uses the **Singleton Container pattern**: containers are started once in the
 * companion object `init` block and are never stopped by the JUnit 5 extension
 * (no `@Container` annotation).  This guarantees:
 *
 *  1. The same container ports are used for ALL test classes in one Gradle run.
 *  2. Spring's context cache works correctly — the context created for the
 *     first test class is reused by subsequent classes at the same DB URL.
 *
 * Containers started:
 *  - **PostgreSQL 15** — schema initialised by `db/schema.sql` (IF NOT EXISTS,
 *    so the script is safe to re-run when containers are reused across runs).
 *  - **MinIO** — S3-compatible archival store used by [S3ArchivalService].
 *
 * [DynamicRouteManager] and [KafkaTemplate] are replaced with MockK mocks so
 * that no real Camel or Kafka brokers are needed.
 */
@SpringBootTest(
    classes = [TransformPlatformApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("integration-test")
abstract class AbstractE2ETest {

    // ── Mocked Camel route manager ─────────────────────────────────────────────
    // Prevents real SFTP / FTP / S3 connections during tests.

    @MockkBean(relaxed = true)
    lateinit var dynamicRouteManager: DynamicRouteManager

    // ── Mocked KafkaTemplate ───────────────────────────────────────────────────
    // KafkaAutoConfiguration is excluded in the integration-test profile, so no
    // real KafkaTemplate bean is created. KafkaRecordWriter depends on it — this
    // mock satisfies the dependency without needing a real Kafka broker.

    @MockkBean(relaxed = true)
    lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setupMocks() {
        // Ensure route manager calls are silent no-ops
        every { dynamicRouteManager.activateRoute(any()) } just Runs
        every { dynamicRouteManager.deactivateRoute(any()) } just Runs
        every { dynamicRouteManager.removeRoute(any()) } just Runs
        every { dynamicRouteManager.reloadRoute(any()) } just Runs
    }

    /**
     * Truncate all business tables before each test.
     *
     * Containers are reused across test runs (withReuse=true), so data from
     * a previous ./gradlew integrationTest invocation would persist and cause
     * unique-constraint conflicts (e.g. spec name/version already exists).
     * Truncating here gives each test method a clean slate.
     */
    @BeforeEach
    fun cleanAllTables() {
        // Truncate in a single statement; CASCADE handles FK-dependent tables.
        jdbcTemplate.execute(
            """
            TRUNCATE TABLE
                workflow_step_executions,
                window_action_executions,
                file_log,
                window_events,
                windows,
                file_specs,
                profiles,
                downloaded_files,
                service_integrations
            RESTART IDENTITY CASCADE
            """.trimIndent(),
        )
    }

    companion object {

        // ── Singleton containers (started once per JVM, never stopped by JUnit) ──
        //
        // NOT annotated with @Container so the JUnit 5 Testcontainers extension
        // does not manage their lifecycle.  Starting them here (init block) means
        // they are alive for the entire Gradle test run — all subclasses share the
        // same ports, so the Spring context cache works correctly across classes.

        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("postgres:15-alpine"),
        ).apply {
            withDatabaseName("transform_platform_test")
            withUsername("test_user")
            withPassword("test_pass")
            // Idempotent IF NOT EXISTS schema — safe to re-run on container reuse.
            withInitScript("db/schema.sql")
            withReuse(true)
        }

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

        init {
            // Start both containers once.  withReuse(true) returns immediately if
            // a matching container is already running (subsequent Gradle runs).
            postgres.start()
            minio.start()
        }

        // ── Dynamic Spring Boot properties ────────────────────────────────────
        // Override datasource + MinIO URLs with the container-assigned ports.

        @DynamicPropertySource
        @JvmStatic
        fun registerContainerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("transform-platform.minio.endpoint") {
                "http://${minio.host}:${minio.getMappedPort(9000)}"
            }
            registry.add("transform-platform.minio.access-key") { "minioadmin" }
            registry.add("transform-platform.minio.secret-key") { "minioadmin" }
            registry.add("transform-platform.minio.downloads-bucket") { "transform-downloads-test" }
        }
    }
}
