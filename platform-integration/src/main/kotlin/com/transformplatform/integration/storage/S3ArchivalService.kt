package com.transformplatform.integration.storage

import com.transformplatform.integration.config.MinioProperties
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneOffset

private val log = KotlinLogging.logger {}

/**
 * Centralised S3 archival store for all downloaded files.
 *
 * For local development, this writes to MinIO (S3-compatible, running in Docker).
 * For production, point [MinioProperties.endpoint] at your AWS S3 regional endpoint
 * and supply proper IAM credentials via environment variables.
 *
 * **Storage layout:**
 * ```
 * {bucket}/{integrationId}/{yyyy-MM-dd}/{originalFileName}
 * ```
 * This makes it easy to audit or expire files by integration and date.
 *
 * **Role in the pipeline:**
 * 1. Camel SFTP/FTP/S3 consumer downloads a file to local temp.
 * 2. [FileDownloadProcessor] calls [archive] — the file lands in this store.
 * 3. A [DownloadedFile] row is inserted with `processingStatus = PENDING`.
 * 4. The transform pipeline picks up PENDING rows, fetches via [retrieve], and processes.
 */
@Service
class S3ArchivalService(
    private val minioClient: MinioClient,
    private val props: MinioProperties,
) {

    // ── Archive (upload) ──────────────────────────────────────────────────────

    /**
     * Uploads [stream] to the archival bucket under a computed object key.
     *
     * @return [ArchivalResult] containing the resolved bucket, key, and ETag (MD5 for single-part uploads).
     */
    fun archive(
        integrationId: String,
        fileName: String,
        stream: InputStream,
        sizeHint: Long = -1L,
        contentType: String = "application/octet-stream",
    ): ArchivalResult {
        ensureBucketExists()
        val key = buildKey(integrationId, fileName)

        log.debug { "Archiving to S3: bucket=${props.downloadsBucket}, key=$key" }

        val response = minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(props.downloadsBucket)
                .`object`(key)
                .stream(stream, sizeHint, PART_SIZE)
                .contentType(contentType)
                .build(),
        )

        val etag = response.etag()?.trim('"') ?: ""
        log.info { "Archived: key=$key, etag=$etag" }
        return ArchivalResult(bucket = props.downloadsBucket, key = key, etag = etag)
    }

    // ── Retrieve (download) ───────────────────────────────────────────────────

    /**
     * Opens a read stream for the object at [key].
     * The caller is responsible for closing the returned stream.
     */
    fun retrieve(key: String): InputStream {
        log.debug { "Retrieving from S3: key=$key" }
        return minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(props.downloadsBucket)
                .`object`(key)
                .build(),
        )
    }

    // ── Existence checks ──────────────────────────────────────────────────────

    fun objectExists(key: String): Boolean = try {
        minioClient.statObject(
            StatObjectArgs.builder().bucket(props.downloadsBucket).`object`(key).build(),
        )
        true
    } catch (e: ErrorResponseException) {
        if (e.errorResponse().code() == "NoSuchKey") false else throw e
    }

    /** Returns true if the downloads bucket exists (used by StartupValidator). */
    fun bucketExists(): Boolean = minioClient.bucketExists(BucketExistsArgs.builder().bucket(props.downloadsBucket).build())

    // ── Key helper ────────────────────────────────────────────────────────────

    /**
     * Builds a deterministic, collision-resistant storage key.
     * Format: `{integrationId}/{yyyy-MM-dd}/{fileName}`
     */
    fun buildKey(integrationId: String, fileName: String): String {
        val date = LocalDate.now(ZoneOffset.UTC)
        return "$integrationId/$date/$fileName"
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun ensureBucketExists() {
        val exists = minioClient.bucketExists(
            BucketExistsArgs.builder().bucket(props.downloadsBucket).build(),
        )
        if (!exists) {
            log.info { "Creating bucket: ${props.downloadsBucket}" }
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(props.downloadsBucket).build())
        }
    }

    // ── Result type ───────────────────────────────────────────────────────────

    /**
     * Result of a successful [archive] call.
     * @property bucket  Bucket where the file was stored.
     * @property key     Full object key — pass this to [retrieve] to read the file later.
     * @property etag    ETag / MD5 checksum returned by MinIO.
     */
    data class ArchivalResult(
        val bucket: String,
        val key: String,
        val etag: String,
    )

    companion object {
        /** 10 MB — MinIO's minimum part size for multipart uploads. */
        private const val PART_SIZE = 10L * 1024 * 1024
    }
}
