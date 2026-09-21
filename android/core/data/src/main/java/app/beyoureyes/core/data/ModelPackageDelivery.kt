package app.beyoureyes.core.data

import androidx.work.ListenableWorker
import androidx.work.workDataOf
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class FixedHttpsRequest(
    val url: String,
    val rangeStartBytes: Long? = null,
    val accept: String = "application/octet-stream",
)

class FixedHttpsResponse(
    val statusCode: Int,
    val finalUrl: String,
    val contentLengthBytes: Long?,
    val contentRange: String?,
    val body: InputStream,
) : AutoCloseable {
    override fun close() = body.close()
}

fun interface FixedHttpsTransport {
    @Throws(IOException::class)
    fun execute(request: FixedHttpsRequest): FixedHttpsResponse
}

/** Production transport: TLS is platform validated and redirects are always surfaced as errors. */
class UrlConnectionFixedHttpsTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) : FixedHttpsTransport {
    init {
        require(connectTimeoutMillis in 1..120_000)
        require(readTimeoutMillis in 1..300_000)
    }

    override fun execute(request: FixedHttpsRequest): FixedHttpsResponse {
        requireFixedHttpsUrl(request.url, "$.download.url")
        require(request.rangeStartBytes == null || request.rangeStartBytes >= 0)
        val connection = (URL(request.url).openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            useCaches = false
            setRequestProperty("Accept", request.accept)
            setRequestProperty("Accept-Encoding", "identity")
            request.rangeStartBytes?.let { setRequestProperty("Range", "bytes=$it-") }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else {
                connection.errorStream ?: ByteArrayInputStream(byteArrayOf())
            }
            FixedHttpsResponse(
                statusCode = status,
                finalUrl = connection.url.toString(),
                contentLengthBytes = connection.getHeaderFieldLong("Content-Length", -1L)
                    .takeIf { it >= 0 },
                contentRange = connection.getHeaderField("Content-Range"),
                body = object : FilterInputStream(stream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            connection.disconnect()
                        }
                    }
                },
            )
        } catch (error: Exception) {
            connection.disconnect()
            if (error is IOException) throw error
            throw IOException("HTTPS request failed", error)
        }
    }
}

enum class MetadataFetchFailure {
    INSECURE_OR_INVALID_URL,
    REDIRECT_OR_URL_DRIFT,
    RESPONSE_TOO_LARGE,
    HTTP_REJECTED,
    NETWORK_IO,
}

sealed interface MetadataFetchResult {
    data class Fetched(val bytes: ByteArray) : MetadataFetchResult
    data class Retryable(val reason: MetadataFetchFailure) : MetadataFetchResult
    data class Rejected(val reason: MetadataFetchFailure) : MetadataFetchResult
}

/** Fetches signed metadata from the configured HTTPS Catalog origin. */
class SignedMetadataHttpClient(
    private val transport: FixedHttpsTransport = UrlConnectionFixedHttpsTransport(),
) {
    fun fetchCatalog(url: String): MetadataFetchResult = fetch(url, MAX_SIGNED_CATALOG_BYTES)

    fun fetchManifest(entry: CatalogPackageEntry): MetadataFetchResult =
        fetch(entry.manifestUrl, MAX_SIGNED_MANIFEST_BYTES)

    private fun fetch(url: String, maximumBytes: Int): MetadataFetchResult {
        try {
            requireFixedHttpsUrl(url, "$.metadata_url")
        } catch (_: MetadataVerificationException) {
            return MetadataFetchResult.Rejected(MetadataFetchFailure.INSECURE_OR_INVALID_URL)
        }
        val response = try {
            transport.execute(FixedHttpsRequest(url, accept = "application/json"))
        } catch (_: IOException) {
            return MetadataFetchResult.Retryable(MetadataFetchFailure.NETWORK_IO)
        } catch (_: Exception) {
            return MetadataFetchResult.Rejected(MetadataFetchFailure.INSECURE_OR_INVALID_URL)
        }
        response.use {
            if (response.finalUrl != url || response.statusCode in 300..399) {
                return MetadataFetchResult.Rejected(MetadataFetchFailure.REDIRECT_OR_URL_DRIFT)
            }
            if (response.statusCode !in 200..299) {
                return if (response.statusCode == 408 || response.statusCode == 429 || response.statusCode >= 500) {
                    MetadataFetchResult.Retryable(MetadataFetchFailure.HTTP_REJECTED)
                } else {
                    MetadataFetchResult.Rejected(MetadataFetchFailure.HTTP_REJECTED)
                }
            }
            if (response.contentLengthBytes?.let { it <= 0 || it > maximumBytes } == true) {
                return MetadataFetchResult.Rejected(MetadataFetchFailure.RESPONSE_TOO_LARGE)
            }
            return try {
                val output = ByteArrayOutputStream(response.contentLengthBytes?.toInt() ?: 8_192)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = response.body.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > maximumBytes) {
                        return MetadataFetchResult.Rejected(MetadataFetchFailure.RESPONSE_TOO_LARGE)
                    }
                    output.write(buffer, 0, count)
                }
                if (total == 0) MetadataFetchResult.Rejected(MetadataFetchFailure.RESPONSE_TOO_LARGE)
                else MetadataFetchResult.Fetched(output.toByteArray())
            } catch (_: IOException) {
                MetadataFetchResult.Retryable(MetadataFetchFailure.NETWORK_IO)
            }
        }
    }
}

enum class ModelDeliveryFailure {
    METADATA_GATE_INVALID,
    INSECURE_ARTIFACT_URL,
    REDIRECT_OR_URL_DRIFT,
    HTTP_REJECTED,
    RANGE_RESPONSE_INVALID,
    ARTIFACT_SIZE_MISMATCH,
    ARTIFACT_SHA256_MISMATCH,
    SELF_TEST_FAILED,
    NETWORK_IO,
    LOCAL_IO,
    STORE_REJECTED,
}

/**
 * Runs against the exact fully downloaded, hash-checked files before they enter the immutable
 * package store. The store gate may only say selfTestPassed=true when this callback succeeds.
 */
fun interface ModelPackageArtifactSelfTest {
    fun run(
        verifiedManifest: VerifiedManifestDocument,
        artifactFilesByRole: Map<String, File>,
    ): Boolean
}

sealed interface ModelPackageDeliveryResult {
    data class Staged(
        val pointer: ModelPackagePointer,
        val alreadyPresent: Boolean,
    ) : ModelPackageDeliveryResult {
        val identity: ModelPackageIdentity get() = pointer.identity
    }

    data class Retryable(val reason: ModelDeliveryFailure) : ModelPackageDeliveryResult
    data class Rejected(
        val reason: ModelDeliveryFailure,
        val storeReasons: Set<ModelPackageRejection> = emptySet(),
    ) : ModelPackageDeliveryResult
}

data class ModelPackageDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    init {
        require(totalBytes > 0)
        require(downloadedBytes in 0..totalBytes)
    }
}

/**
 * Blocking, deterministic package delivery core intended to run from a WorkManager IO worker.
 * Download and staging never activate a package; activation remains an explicit upper-gate step.
 */
class ModelPackageDeliveryCoordinator(
    private val store: ModelArtifactStore,
    private val downloadDirectory: File,
    private val transport: FixedHttpsTransport = UrlConnectionFixedHttpsTransport(),
) {
    init {
        require(downloadDirectory.isDirectory || downloadDirectory.mkdirs()) {
            "app-private model download directory is unavailable"
        }
    }

    fun stageVerifiedPackage(
        verifiedManifest: VerifiedManifestDocument,
        gateReport: ModelPackageGateReport,
        nowEpochMillis: Long,
        artifactSelfTest: ModelPackageArtifactSelfTest? = null,
        onDownloadProgress: (ModelPackageDownloadProgress) -> Unit = {},
    ): ModelPackageDeliveryResult {
        val descriptor = try {
            verifiedManifest.toStoreDescriptor()
        } catch (_: IllegalArgumentException) {
            return ModelPackageDeliveryResult.Rejected(ModelDeliveryFailure.METADATA_GATE_INVALID)
        }
        val earnedGateReport = if (artifactSelfTest == null) {
            gateReport
        } else {
            // A caller must present self-test as pending. Only this coordinator can turn it true,
            // and only after the exact downloaded bytes pass the supplied callback below.
            if (gateReport.selfTestPassed) {
                return ModelPackageDeliveryResult.Rejected(ModelDeliveryFailure.METADATA_GATE_INVALID)
            }
            gateReport.copy(selfTestPassed = true)
        }
        if (gateReport.packageDescriptorSha256 != descriptor.descriptorSha256 ||
            gateReport.catalogFreshUntilEpochMillis > verifiedManifest.catalogFreshUntilEpochMillis ||
            gateReport.runValidUntilEpochMillis > verifiedManifest.licenseRunValidUntilEpochMillis ||
            earnedGateReport.stagingRejectionReasons(descriptor, nowEpochMillis).isNotEmpty()
        ) {
            return ModelPackageDeliveryResult.Rejected(ModelDeliveryFailure.METADATA_GATE_INVALID)
        }

        val orderedArtifacts = verifiedManifest.manifest.artifacts.sortedWith(
            ArtifactComponentRoleOrdering,
        )
        val totalArtifactBytes = orderedArtifacts.sumOf { it.sizeBytes }
        when (
            val installed = store.acquireRuntimeLease(
                descriptor.pointer(),
                nowEpochMillis,
            )
        ) {
            is ModelPackageRuntimeLeaseResult.Acquired -> installed.lease.use { lease ->
                if (lease.descriptor != descriptor ||
                    !lease.canonicalManifest.copyBytes().contentEquals(
                        verifiedManifest.copyDocumentBytes(),
                    )
                ) {
                    return ModelPackageDeliveryResult.Rejected(
                        ModelDeliveryFailure.STORE_REJECTED,
                        setOf(ModelPackageRejection.IMMUTABLE_VERSION_CONFLICT),
                    )
                }
                reportDownloadProgress(
                    onDownloadProgress,
                    totalArtifactBytes,
                    totalArtifactBytes,
                )
                if (artifactSelfTest != null && !runCatching {
                        artifactSelfTest.run(
                            verifiedManifest,
                            lease.artifactFilesByRole,
                        )
                    }.getOrDefault(false)
                ) {
                    return ModelPackageDeliveryResult.Rejected(ModelDeliveryFailure.SELF_TEST_FAILED)
                }
                packageDownloadDirectory(descriptor.identity).deleteRecursively()
                return ModelPackageDeliveryResult.Staged(descriptor.pointer(), alreadyPresent = true)
            }
            is ModelPackageRuntimeLeaseResult.Rejected -> {
                if (installed.reasons != setOf(ModelPackageRejection.PACKAGE_NOT_INSTALLED)) {
                    return ModelPackageDeliveryResult.Rejected(
                        ModelDeliveryFailure.STORE_REJECTED,
                        installed.reasons,
                    )
                }
            }
        }

        val partials = linkedMapOf<String, File>()
        reportDownloadProgress(onDownloadProgress, 0, totalArtifactBytes)
        var completedArtifactBytes = 0L
        for (artifact in orderedArtifacts) {
            val artifactDescriptor = checkNotNull(descriptor.artifact(artifact.role))
            val result = downloadArtifact(
                identity = descriptor.identity,
                url = artifact.url,
                descriptor = artifactDescriptor,
                completedArtifactBytes = completedArtifactBytes,
                totalArtifactBytes = totalArtifactBytes,
                onDownloadProgress = onDownloadProgress,
            )
            when (result) {
                is ArtifactDownloadResult.Ready -> {
                    partials[artifact.role] = result.file
                    completedArtifactBytes += artifactDescriptor.sizeBytes
                    reportDownloadProgress(
                        onDownloadProgress,
                        completedArtifactBytes,
                        totalArtifactBytes,
                    )
                }
                is ArtifactDownloadResult.Retryable -> return ModelPackageDeliveryResult.Retryable(result.reason)
                is ArtifactDownloadResult.Rejected -> return ModelPackageDeliveryResult.Rejected(result.reason)
            }
        }
        if (artifactSelfTest != null) {
            val passed = runCatching {
                artifactSelfTest.run(
                    verifiedManifest,
                    java.util.Collections.unmodifiableMap(LinkedHashMap(partials)),
                )
            }.getOrDefault(false)
            if (!passed) {
                return ModelPackageDeliveryResult.Rejected(ModelDeliveryFailure.SELF_TEST_FAILED)
            }
        }
        val artifactStreams = linkedMapOf<String, InputStream>()
        try {
            partials.forEach { (role, file) -> artifactStreams[role] = FileInputStream(file) }
        } catch (_: IOException) {
            artifactStreams.values.forEach { stream -> runCatching { stream.close() } }
            return ModelPackageDeliveryResult.Retryable(ModelDeliveryFailure.LOCAL_IO)
        }
        val stageResult = store.stagePackage(
            descriptor = descriptor,
            canonicalManifestBytes = verifiedManifest.copyDocumentBytes(),
            artifactSourcesByRole = artifactStreams,
            gateReport = earnedGateReport,
            nowEpochMillis = nowEpochMillis,
        )
        return when (stageResult) {
            is ModelPackageStageResult.Staged -> {
                packageDownloadDirectory(descriptor.identity).deleteRecursively()
                ModelPackageDeliveryResult.Staged(descriptor.pointer(), stageResult.alreadyPresent)
            }
            is ModelPackageStageResult.Rejected -> {
                if (stageResult.reasons == setOf(ModelPackageRejection.IO_FAILURE)) {
                    ModelPackageDeliveryResult.Retryable(ModelDeliveryFailure.LOCAL_IO)
                } else {
                    ModelPackageDeliveryResult.Rejected(
                        ModelDeliveryFailure.STORE_REJECTED,
                        stageResult.reasons,
                    )
                }
            }
        }
    }

    /** Explicit activation after signed selection, compatibility and one package self-test. */
    fun activateStagedPackage(
        slot: ModelPackageSlot,
        pointer: ModelPackagePointer,
        nowEpochMillis: Long,
    ): ModelPackageActivationResult = store.activatePackage(slot, pointer, nowEpochMillis)

    private fun downloadArtifact(
        identity: ModelPackageIdentity,
        url: String,
        descriptor: ModelPackageArtifactDescriptor,
        completedArtifactBytes: Long,
        totalArtifactBytes: Long,
        onDownloadProgress: (ModelPackageDownloadProgress) -> Unit,
    ): ArtifactDownloadResult {
        try {
            requireFixedHttpsUrl(url, "$.artifacts.${descriptor.role}.url")
        } catch (_: MetadataVerificationException) {
            return ArtifactDownloadResult.Rejected(ModelDeliveryFailure.INSECURE_ARTIFACT_URL)
        }
        val directory = packageDownloadDirectory(identity)
        if (!directory.isDirectory && !directory.mkdirs()) {
            return ArtifactDownloadResult.Retryable(ModelDeliveryFailure.LOCAL_IO)
        }
        val partial = directory.resolve("${descriptor.role}-${descriptor.sha256}.partial")
        if (partial.exists() && (!partial.isFile || partial.length() > descriptor.sizeBytes)) {
            if (!partial.delete()) return ArtifactDownloadResult.Retryable(ModelDeliveryFailure.LOCAL_IO)
        }
        if (partial.length() == descriptor.sizeBytes) {
            if (sha256File(partial) == descriptor.sha256) {
                reportDownloadProgress(
                    onDownloadProgress,
                    completedArtifactBytes + descriptor.sizeBytes,
                    totalArtifactBytes,
                )
                return ArtifactDownloadResult.Ready(partial)
            }
            if (!partial.delete()) return ArtifactDownloadResult.Retryable(ModelDeliveryFailure.LOCAL_IO)
        }
        if (partial.length() in 1 until descriptor.sizeBytes) {
            // A process can die after the file length has advanced but before the last filesystem
            // block is durable. Re-fetch a small overlap instead of trusting that tail blindly;
            // the final whole-file SHA remains the authority for every preceding byte.
            val overlapBytes = minOf(
                RESUME_OVERLAP_BYTES,
                maxOf(1L, partial.length() / 2L),
            )
            try {
                RandomAccessFile(partial, "rw").use { file ->
                    file.setLength(partial.length() - overlapBytes)
                    file.fd.sync()
                }
            } catch (_: IOException) {
                return ArtifactDownloadResult.Retryable(ModelDeliveryFailure.LOCAL_IO)
            }
        }
        reportDownloadProgress(
            onDownloadProgress,
            completedArtifactBytes + partial.length(),
            totalArtifactBytes,
        )

        var restartedAfterIgnoredRange = false
        while (true) {
            val offset = partial.length()
            val response = try {
                transport.execute(
                    FixedHttpsRequest(
                        url = url,
                        rangeStartBytes = offset.takeIf { it > 0 },
                    ),
                )
            } catch (_: IOException) {
                return ArtifactDownloadResult.Retryable(ModelDeliveryFailure.NETWORK_IO)
            } catch (_: Exception) {
                return ArtifactDownloadResult.Rejected(ModelDeliveryFailure.INSECURE_ARTIFACT_URL)
            }
            response.use {
                if (response.finalUrl != url || response.statusCode in 300..399) {
                    return ArtifactDownloadResult.Rejected(ModelDeliveryFailure.REDIRECT_OR_URL_DRIFT)
                }
                if (offset > 0 && response.statusCode == 200 && !restartedAfterIgnoredRange) {
                    restartedAfterIgnoredRange = true
                    if (!partial.delete()) return ArtifactDownloadResult.Retryable(ModelDeliveryFailure.LOCAL_IO)
                    reportDownloadProgress(
                        onDownloadProgress,
                        completedArtifactBytes,
                        totalArtifactBytes,
                    )
                    continue
                }
                val expectedResponseBytes = descriptor.sizeBytes - offset
                if (!validArtifactResponse(response, offset, descriptor.sizeBytes)) {
                    return if (response.statusCode == 408 || response.statusCode == 429 || response.statusCode >= 500) {
                        ArtifactDownloadResult.Retryable(ModelDeliveryFailure.HTTP_REJECTED)
                    } else {
                        ArtifactDownloadResult.Rejected(
                            if (response.statusCode in 200..299) {
                                ModelDeliveryFailure.RANGE_RESPONSE_INVALID
                            } else {
                                ModelDeliveryFailure.HTTP_REJECTED
                            },
                        )
                    }
                }
                if (response.contentLengthBytes?.let { it != expectedResponseBytes } == true) {
                    return ArtifactDownloadResult.Rejected(ModelDeliveryFailure.ARTIFACT_SIZE_MISMATCH)
                }
                try {
                    RandomAccessFile(partial, "rw").use { output ->
                        output.seek(offset)
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var written = 0L
                        try {
                            while (true) {
                                val count = response.body.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                written += count
                                if (written > expectedResponseBytes) {
                                    output.setLength(0)
                                    return ArtifactDownloadResult.Rejected(
                                        ModelDeliveryFailure.ARTIFACT_SIZE_MISMATCH,
                                    )
                                }
                                output.write(buffer, 0, count)
                                reportDownloadProgress(
                                    onDownloadProgress,
                                    completedArtifactBytes + offset + written,
                                    totalArtifactBytes,
                                )
                            }
                        } finally {
                            output.fd.sync()
                        }
                    }
                } catch (_: IOException) {
                    return ArtifactDownloadResult.Retryable(ModelDeliveryFailure.NETWORK_IO)
                }
            }
            if (partial.length() < descriptor.sizeBytes) {
                return ArtifactDownloadResult.Retryable(ModelDeliveryFailure.NETWORK_IO)
            }
            if (partial.length() != descriptor.sizeBytes) {
                partial.delete()
                return ArtifactDownloadResult.Rejected(ModelDeliveryFailure.ARTIFACT_SIZE_MISMATCH)
            }
            if (sha256File(partial) != descriptor.sha256) {
                partial.delete()
                return ArtifactDownloadResult.Rejected(ModelDeliveryFailure.ARTIFACT_SHA256_MISMATCH)
            }
            return ArtifactDownloadResult.Ready(partial)
        }
    }

    private fun packageDownloadDirectory(identity: ModelPackageIdentity): File = downloadDirectory
        .resolve(identity.packageId)
        .resolve(identity.packageVersion)

    private sealed interface ArtifactDownloadResult {
        data class Ready(val file: File) : ArtifactDownloadResult
        data class Retryable(val reason: ModelDeliveryFailure) : ArtifactDownloadResult
        data class Rejected(val reason: ModelDeliveryFailure) : ArtifactDownloadResult
    }

    private fun reportDownloadProgress(
        callback: (ModelPackageDownloadProgress) -> Unit,
        downloadedBytes: Long,
        totalBytes: Long,
    ) {
        if (totalBytes <= 0) return
        try {
            callback(
                ModelPackageDownloadProgress(
                    downloadedBytes = downloadedBytes.coerceIn(0, totalBytes),
                    totalBytes = totalBytes,
                ),
            )
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A presentation callback must not invalidate the package; cancellation must stop IO.
        }
    }
}

fun ModelPackageDeliveryResult.toWorkManagerResult(): ListenableWorker.Result = when (this) {
    is ModelPackageDeliveryResult.Staged -> ListenableWorker.Result.success()
    is ModelPackageDeliveryResult.Retryable -> ListenableWorker.Result.retry()
    is ModelPackageDeliveryResult.Rejected -> ListenableWorker.Result.failure(
        workDataOf("model_delivery_failure" to reason.name),
    )
}

private val ArtifactComponentRoleOrdering = compareBy<app.beyoureyes.core.vision.ArtifactComponent> { it.role }
private val CONTENT_RANGE = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$")
private const val RESUME_OVERLAP_BYTES = 64L * 1_024L

private fun validArtifactResponse(
    response: FixedHttpsResponse,
    offset: Long,
    expectedTotal: Long,
): Boolean {
    if (offset == 0L) return response.statusCode == 200 && response.contentRange == null
    if (response.statusCode != 206) return false
    val match = response.contentRange?.let(CONTENT_RANGE::matchEntire) ?: return false
    val start = match.groupValues[1].toLongOrNull() ?: return false
    val end = match.groupValues[2].toLongOrNull() ?: return false
    val total = match.groupValues[3].toLongOrNull() ?: return false
    return start == offset && end == expectedTotal - 1 && total == expectedTotal
}

private fun sha256File(file: File): String = FileInputStream(file).use { input ->
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
