package app.beyoureyes.monitor

import app.beyoureyes.core.data.ModelPackageArtifactDescriptor
import app.beyoureyes.core.data.ModelPackageIdentity
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.data.reference.PrivateReferenceImageProvider
import app.beyoureyes.core.domain.MonitorKind
import app.beyoureyes.core.domain.ReadingOperator
import app.beyoureyes.core.domain.ReadingSourceKind
import app.beyoureyes.core.domain.RuntimeMonitorRule
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.SamplingPolicySpec
import app.beyoureyes.core.vision.SupportedTask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MonitoringRuntimeSnapshotTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `pending baseline reading snapshot records a null confirmed format`() {
        val json = monitoringRuntimeSnapshotJson(
            config = cameraConfig(),
            task = readingTask(confirmedReadingFormat = null, configured = false),
            runtimePackage = packageSnapshot(),
        )

        assertTrue(json.contains("\"mode\":\"structured_reading\""))
        assertTrue(json.contains("\"confirmed_format\":null"))
        assertTrue(json.contains("\"baseline_pending\":true"))
        assertTrue(json.contains("\"source_kind\":\"digital_display\""))
    }

    @Test
    fun `confirmed reading snapshot keeps the exact confirmed format`() {
        val format = app.beyoureyes.core.domain.ConfirmedReadingFormat(
            kind = app.beyoureyes.core.domain.ReadingFormatKind.DECIMAL,
            fractionalDigits = 1,
            timeSegments = null,
            unit = "℃",
        )
        val json = monitoringRuntimeSnapshotJson(
            config = cameraConfig(),
            task = readingTask(confirmedReadingFormat = format, configured = true),
            runtimePackage = packageSnapshot(),
        )

        assertTrue(json.contains("\"kind\":\"decimal\""))
        assertTrue(json.contains("\"fractional_digits\":1"))
        assertTrue(json.contains("\"unit\":\"℃\""))
        assertFalse(json.contains("baseline_pending"))
    }

    private fun readingTask(
        confirmedReadingFormat: app.beyoureyes.core.domain.ConfirmedReadingFormat?,
        configured: Boolean,
    ) = RestoredMonitoringTask(
        taskId = TASK_ID,
        revision = 1,
        displayName = "温度",
        kind = MonitorKind.READING,
        capabilityId = READING_CAPABILITY_ID,
        supportedTask = SupportedTask.STRUCTURED_READING,
        targetId = TASK_ID,
        targetProfile = null,
        rule = RuntimeMonitorRule.ReadingThreshold.Single(
            operator = ReadingOperator.GT,
            thresholdDecimal = "0",
            durationMillis = 1_000,
            configured = configured,
        ),
        cameraRegion = FULL_FRAME_MONITOR_REGION,
        readingSourceKind = ReadingSourceKind.DIGITAL_DISPLAY,
        confirmedReadingFormat = confirmedReadingFormat,
        manualReadingScanRegion = null,
        readingBaselinePending = !configured,
        referenceImageProvider = PrivateReferenceImageProvider(temporary.root, emptyList()),
        runtimePackagePointer = POINTER,
    )

    private fun cameraConfig() = RuntimeCameraConfig(
        taskId = TASK_ID,
        taskRevision = 1,
        roi = FULL_FRAME_MONITOR_REGION,
        viewPortWidth = 1080,
        viewPortHeight = 1920,
        targetRotation = android.view.Surface.ROTATION_0,
        resolvedSamplingConfig = ResolvedSamplingConfig.fromSignedManifestDefault(
            taskId = TASK_ID,
            taskRevision = 1,
            catalogVersion = CATALOG_VERSION,
            capabilityId = READING_CAPABILITY_ID,
            modelProfileKey = MODEL_PROFILE_KEY,
            recipeId = RECIPE_ID,
            intentKey = INTENT_KEY,
            packagePointer = POINTER,
            artifactIdentitySha256 = ARTIFACT_IDENTITY,
            deviceFingerprintSha256 = DEVICE_FINGERPRINT,
            samplingPolicy = POLICY,
        ),
    )

    private fun packageSnapshot() = RuntimePackageSnapshot(
        pointer = POINTER,
        runtimeFamily = RecipeFamily.READING_PIPELINE_V1,
        preprocessId = "ppocr_rec_preprocess",
        adapterId = "ppocr_rec_adapter",
        artifacts = listOf(
            ModelPackageArtifactDescriptor(
                role = "primary",
                relativePath = "recognizer.mnn",
                sha256 = "d".repeat(64),
                sizeBytes = 1_024,
            ),
        ),
        artifactIdentitySha256 = ARTIFACT_IDENTITY,
    )

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000000"
        const val CATALOG_VERSION = "2026.08.24.1"
        const val MODEL_PROFILE_KEY = "numeric_reading"
        const val RECIPE_ID = "reading_pipeline_v1"
        const val INTENT_KEY = "reading.numeric"
        val POINTER = ModelPackagePointer(
            ModelPackageIdentity("reading_pipeline", "1.0.0"),
            "a".repeat(64),
        )
        val POLICY = SamplingPolicySpec(750, 250, 2_000, true)
        val ARTIFACT_IDENTITY = "b".repeat(64)
        val DEVICE_FINGERPRINT = "c".repeat(64)
    }
}
