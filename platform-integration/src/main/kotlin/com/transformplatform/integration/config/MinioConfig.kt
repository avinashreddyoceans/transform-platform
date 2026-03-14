package com.transformplatform.integration.config

import io.minio.MinioClient
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// ── Configuration Properties ──────────────────────────────────────────────────

@ConfigurationProperties(prefix = "transform-platform.minio")
data class MinioProperties(
    /** S3-API endpoint for MinIO (e.g. http://localhost:9000). */
    val endpoint: String = "http://localhost:9000",
    val accessKey: String = "minioadmin",
    val secretKey: String = "minioadmin",
    /** Bucket where all downloaded files are stored. */
    val downloadsBucket: String = "transform-downloads",
)

// ── Bean ──────────────────────────────────────────────────────────────────────

@Configuration
@EnableConfigurationProperties(MinioProperties::class)
class MinioConfig {

    @Bean
    fun minioClient(props: MinioProperties): MinioClient = MinioClient.builder()
        .endpoint(props.endpoint)
        .credentials(props.accessKey, props.secretKey)
        .build()
}
