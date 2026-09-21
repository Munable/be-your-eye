package app.beyoureyes.core.data

import app.beyoureyes.core.vision.SamplingPolicySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedSamplingConfigTest {
    @Test
    fun `signed default binds exact task package artifacts device and bounds`() {
        val config = config()

        assertEquals(750, config.intervalMillis)
        assertTrue(config.adaptiveEnabled)
        assertTrue(
            config.exactMismatchReasons(
                expectedTaskId = TASK_ID,
                expectedTaskRevision = 3,
                expectedCatalogVersion = CATALOG_VERSION,
                expectedCapabilityId = CAPABILITY_ID,
                expectedModelProfileKey = MODEL_PROFILE_KEY,
                expectedRecipeId = RECIPE_ID,
                expectedIntentKey = INTENT_KEY,
                expectedPackagePointer = POINTER,
                expectedArtifactIdentitySha256 = ARTIFACT_IDENTITY,
                expectedDeviceFingerprintSha256 = DEVICE_FINGERPRINT,
                signedSamplingPolicy = POLICY,
            ).isEmpty(),
        )
    }

    @Test
    fun `every exact identity and signed range mismatch fails closed`() {
        val config = config()
        val otherPointer = ModelPackagePointer(
            ModelPackageIdentity("reader", "2.0.0"),
            "f".repeat(64),
        )
        val reasons = config.exactMismatchReasons(
            expectedTaskId = "01900000-0000-7000-8000-000000000001",
            expectedTaskRevision = 4,
            expectedCatalogVersion = "2026.08.23.2",
            expectedCapabilityId = "structured_reading",
            expectedModelProfileKey = "numeric_display_reading",
            expectedRecipeId = "reading_pipeline_general_v1",
            expectedIntentKey = "reading.numeric.display",
            expectedPackagePointer = otherPointer,
            expectedArtifactIdentitySha256 = "d".repeat(64),
            expectedDeviceFingerprintSha256 = "e".repeat(64),
            signedSamplingPolicy = SamplingPolicySpec(500, 500, 600, false),
        )

        assertEquals(
            setOf(
                ResolvedSamplingMismatch.TASK_ID,
                ResolvedSamplingMismatch.TASK_REVISION,
                ResolvedSamplingMismatch.CATALOG_VERSION,
                ResolvedSamplingMismatch.CAPABILITY_ID,
                ResolvedSamplingMismatch.MODEL_PROFILE_KEY,
                ResolvedSamplingMismatch.RECIPE_ID,
                ResolvedSamplingMismatch.INTENT_KEY,
                ResolvedSamplingMismatch.PACKAGE_POINTER,
                ResolvedSamplingMismatch.ARTIFACT_IDENTITY,
                ResolvedSamplingMismatch.DEVICE_FINGERPRINT,
                ResolvedSamplingMismatch.MANIFEST_BOUNDS,
                ResolvedSamplingMismatch.INTERVAL_OUT_OF_MANIFEST_RANGE,
                ResolvedSamplingMismatch.INTERVAL_NOT_SIGNED_DEFAULT,
                ResolvedSamplingMismatch.ADAPTIVE_POLICY_MISMATCH,
            ),
            reasons,
        )
    }

    @Test
    fun `missing signed policy and out of bound persisted interval are rejected`() {
        assertEquals(
            setOf(ResolvedSamplingMismatch.MANIFEST_POLICY_UNAVAILABLE),
            config().exactMismatchReasons(
                TASK_ID,
                3,
                CATALOG_VERSION,
                CAPABILITY_ID,
                MODEL_PROFILE_KEY,
                RECIPE_ID,
                INTENT_KEY,
                POINTER,
                ARTIFACT_IDENTITY,
                DEVICE_FINGERPRINT,
                SamplingPolicySpec(null, null, null, false),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            config().copy(intervalMillis = 2_001)
        }
    }

    @Test
    fun `adaptive signed policy accepts an in-range current interval`() {
        val adjusted = config().copy(intervalMillis = 500)

        assertTrue(
            adjusted.exactMismatchReasons(
                TASK_ID,
                3,
                CATALOG_VERSION,
                CAPABILITY_ID,
                MODEL_PROFILE_KEY,
                RECIPE_ID,
                INTENT_KEY,
                POINTER,
                ARTIFACT_IDENTITY,
                DEVICE_FINGERPRINT,
                POLICY,
            ).isEmpty(),
        )
    }

    @Test
    fun `non adaptive policy stays on signed default and rejects suggestions`() {
        val fixed = ResolvedSamplingConfig.fromSignedManifestDefault(
            taskId = TASK_ID,
            taskRevision = 3,
            catalogVersion = CATALOG_VERSION,
            capabilityId = CAPABILITY_ID,
            modelProfileKey = MODEL_PROFILE_KEY,
            recipeId = RECIPE_ID,
            intentKey = INTENT_KEY,
            packagePointer = POINTER,
            artifactIdentitySha256 = ARTIFACT_IDENTITY,
            deviceFingerprintSha256 = DEVICE_FINGERPRINT,
            samplingPolicy = SamplingPolicySpec(750, 250, 2_000, false),
        )

        val result = fixed.resolveAdjustment(
            SamplingIntervalAdjustmentRequest(1_000, SamplingAdjustmentReason.THERMAL_PRESSURE, 4),
        )

        assertTrue(result is SamplingIntervalAdjustment.Rejected)
        assertEquals(
            SamplingAdjustmentDisposition.REJECTED_ADAPTATION_DISABLED,
            result.diagnostic.disposition,
        )
        assertEquals(750, fixed.intervalMillis)
    }

    @Test
    fun `adaptive suggestions are directional deterministic and clamped to signed bounds`() {
        val config = config()
        val request = SamplingIntervalAdjustmentRequest(
            suggestedIntervalMillis = 5_000,
            reason = SamplingAdjustmentReason.ANALYSIS_BACKLOG,
            observedAtMonotonicMillis = 9,
        )

        val first = config.resolveAdjustment(request) as SamplingIntervalAdjustment.Ready
        val repeated = config.resolveAdjustment(request) as SamplingIntervalAdjustment.Ready
        assertEquals(first, repeated)
        assertEquals(2_000, first.config.intervalMillis)
        assertEquals(
            SamplingAdjustmentDisposition.APPLIED_CLAMPED_TO_SIGNED_BOUNDS,
            first.diagnostic.disposition,
        )

        val wrongDirection = first.config.resolveAdjustment(
            SamplingIntervalAdjustmentRequest(
                suggestedIntervalMillis = 1_000,
                reason = SamplingAdjustmentReason.THERMAL_PRESSURE,
                observedAtMonotonicMillis = 10,
            ),
        )
        assertTrue(wrongDirection is SamplingIntervalAdjustment.Rejected)
        assertEquals(
            SamplingAdjustmentDisposition.REJECTED_DIRECTION,
            wrongDirection.diagnostic.disposition,
        )

        val recovered = first.config.resolveAdjustment(
            SamplingIntervalAdjustmentRequest(
                suggestedIntervalMillis = 100,
                reason = SamplingAdjustmentReason.RECOVERY,
                observedAtMonotonicMillis = 11,
            ),
        ) as SamplingIntervalAdjustment.Ready
        assertEquals(250, recovered.config.intervalMillis)
    }

    @Test
    fun `inference latency reason can only slow the signed adaptive cadence`() {
        val slowed = config().resolveAdjustment(
            SamplingIntervalAdjustmentRequest(
                suggestedIntervalMillis = 1_000,
                reason = SamplingAdjustmentReason.INFERENCE_LATENCY,
                observedAtMonotonicMillis = 12,
            ),
        ) as SamplingIntervalAdjustment.Ready
        assertEquals(1_000, slowed.config.intervalMillis)

        val wrongDirection = config().resolveAdjustment(
            SamplingIntervalAdjustmentRequest(
                suggestedIntervalMillis = 500,
                reason = SamplingAdjustmentReason.INFERENCE_LATENCY,
                observedAtMonotonicMillis = 13,
            ),
        )
        assertTrue(wrongDirection is SamplingIntervalAdjustment.Rejected)
        assertEquals(
            SamplingAdjustmentDisposition.REJECTED_DIRECTION,
            wrongDirection.diagnostic.disposition,
        )
    }

    private fun config() = ResolvedSamplingConfig.fromSignedManifestDefault(
        taskId = TASK_ID,
        taskRevision = 3,
        catalogVersion = CATALOG_VERSION,
        capabilityId = CAPABILITY_ID,
        modelProfileKey = MODEL_PROFILE_KEY,
        recipeId = RECIPE_ID,
        intentKey = INTENT_KEY,
        packagePointer = POINTER,
        artifactIdentitySha256 = ARTIFACT_IDENTITY,
        deviceFingerprintSha256 = DEVICE_FINGERPRINT,
        samplingPolicy = POLICY,
    )

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000000"
        const val CATALOG_VERSION = "2026.08.24.1"
        const val CAPABILITY_ID = "visual_target"
        const val MODEL_PROFILE_KEY = "common_objects"
        const val RECIPE_ID = "object_detection_general_v1"
        const val INTENT_KEY = "object.common.apple"
        val POINTER = ModelPackagePointer(
            ModelPackageIdentity("object_detector", "1.2.0"),
            "a".repeat(64),
        )
        const val ARTIFACT_IDENTITY =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val DEVICE_FINGERPRINT =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        val POLICY = SamplingPolicySpec(750, 250, 2_000, true)
    }
}
