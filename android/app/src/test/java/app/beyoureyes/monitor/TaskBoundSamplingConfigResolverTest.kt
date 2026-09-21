package app.beyoureyes.monitor

import app.beyoureyes.core.data.CatalogModelCard
import app.beyoureyes.core.data.ModelPackageIdentity
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.data.ResolvedSamplingMismatch
import app.beyoureyes.core.data.reference.PrivateReferenceImageProvider
import app.beyoureyes.core.domain.MonitorKind
import app.beyoureyes.core.domain.RuntimeMonitorRule
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.SamplingPolicySpec
import app.beyoureyes.core.vision.SupportedTask
import app.beyoureyes.core.vision.TargetProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskBoundSamplingConfigResolverTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `exact signed package default is ready`() {
        assertTrue(mismatches(config()).isEmpty())
    }

    @Test
    fun `stale task artifact and device fail while adaptive in-range interval remains valid`() {
        val stale = config().copy(
            taskRevision = 2,
            artifactIdentitySha256 = "d".repeat(64),
            deviceFingerprintSha256 = "e".repeat(64),
            intervalMillis = 500,
        )

        assertEquals(
            setOf(
                ResolvedSamplingMismatch.TASK_REVISION,
                ResolvedSamplingMismatch.ARTIFACT_IDENTITY,
                ResolvedSamplingMismatch.DEVICE_FINGERPRINT,
            ),
            mismatches(stale),
        )
    }

    @Test
    fun `missing signed Manifest policy requires reprepare`() {
        assertEquals(
            setOf(ResolvedSamplingMismatch.MANIFEST_POLICY_UNAVAILABLE),
            taskBoundSamplingMismatches(
                config = config(),
                expectedTaskId = TASK_ID,
                expectedTaskRevision = 1,
                expectedCatalogVersion = CATALOG_VERSION,
                expectedCapabilityId = CAPABILITY_ID,
                expectedModelProfileKey = MODEL_PROFILE_KEY,
                expectedRecipeId = RECIPE_ID,
                expectedIntentKey = INTENT_KEY,
                expectedPackagePointer = POINTER,
                expectedArtifactIdentitySha256 = ARTIFACT_IDENTITY,
                expectedDeviceFingerprintSha256 = DEVICE_FINGERPRINT,
                signedSamplingPolicy = SamplingPolicySpec(null, null, null, false),
            ),
        )
    }

    @Test
    fun `model label comes only from the signed Catalog model card`() {
        val card = CatalogModelCard(
            providerId = "google",
            providerName = "Google",
            modelName = "MediaPipe MobileNetV3 Large Image Embedder",
            modelHomeUrl = "https://ai.google.dev/edge/mediapipe/solutions/vision/image_embedder/android",
            modelKind = "general",
        )

        assertEquals(
            "MediaPipe MobileNetV3 Large Image Embedder",
            signedModelDisplayName(card),
        )
        assertNull(signedModelDisplayName(card.copy(modelName = "  ")))
    }

    @Test
    fun `verified model label cache is bound to the exact immutable package pointer`() {
        val cache = VerifiedModelDisplayNameCache()
        cache.remember(POINTER, "PP-OCRv6 Medium")

        assertEquals("PP-OCRv6 Medium", cache.find(POINTER))
        assertNull(
            cache.find(
                POINTER.copy(canonicalManifestSha256 = "f".repeat(64)),
            ),
        )
        assertNull(
            cache.find(
                POINTER.copy(
                    identity = POINTER.identity.copy(packageVersion = "2.0.0"),
                ),
            ),
        )
    }

    @Test
    fun `restored object task recreates its exact field validation spec`() {
        val profile = TargetProfile.ObjectClass(
            targetId = "cat",
            labelZhCn = "猫",
            labelEn = "cat",
        )
        val restored = RestoredMonitoringTask(
            taskId = TASK_ID,
            revision = 1,
            displayName = "找猫",
            kind = MonitorKind.OBJECT_DETECTION,
            capabilityId = "visual_target",
            supportedTask = SupportedTask.VISUAL_TARGET,
            targetId = profile.targetId,
            targetProfile = profile,
            rule = RuntimeMonitorRule.PresenceEpisode(
                samplingIntervalMillis = config().intervalMillis,
            ),
            cameraRegion = FULL_FRAME_MONITOR_REGION,
            readingSourceKind = null,
            confirmedReadingFormat = null,
            manualReadingScanRegion = null,
            readingBaselinePending = false,
            referenceImageProvider = PrivateReferenceImageProvider(temporary.root, emptyList()),
            runtimePackagePointer = POINTER,
        )

        assertTrue(storedTaskUsesFieldValidation(MonitorKind.OBJECT_DETECTION.wireValue))
        assertTrue(
            storedTaskRuntimeFamilyMatches(
                MonitorKind.OBJECT_DETECTION.wireValue,
                RecipeFamily.OBJECT_DETECTION_V1,
            ),
        )
        assertFalse(
            storedTaskRuntimeFamilyMatches(
                MonitorKind.OBJECT_DETECTION.wireValue,
                RecipeFamily.SIMILARITY_MATCH_V1,
            ),
        )

        val identity = restoredFieldValidationSpec(
            filesDir = temporary.root,
            restored = restored,
            targetProfile = profile,
        ).identity(FULL_FRAME_MONITOR_REGION)

        assertEquals(TASK_ID, identity.taskId)
        assertEquals(restored.revision, identity.taskRevision)
        assertEquals(POINTER, identity.packagePointer)
        assertEquals(profile.targetId, identity.targetId)
        assertEquals(FULL_FRAME_MONITOR_REGION, identity.roi)
    }

    private fun mismatches(config: ResolvedSamplingConfig) = taskBoundSamplingMismatches(
        config = config,
        expectedTaskId = TASK_ID,
        expectedTaskRevision = 1,
        expectedCatalogVersion = CATALOG_VERSION,
        expectedCapabilityId = CAPABILITY_ID,
        expectedModelProfileKey = MODEL_PROFILE_KEY,
        expectedRecipeId = RECIPE_ID,
        expectedIntentKey = INTENT_KEY,
        expectedPackagePointer = POINTER,
        expectedArtifactIdentitySha256 = ARTIFACT_IDENTITY,
        expectedDeviceFingerprintSha256 = DEVICE_FINGERPRINT,
        signedSamplingPolicy = POLICY,
    )

    private fun config() = ResolvedSamplingConfig.fromSignedManifestDefault(
        taskId = TASK_ID,
        taskRevision = 1,
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
        const val INTENT_KEY = "object.common.cat"
        val POINTER = ModelPackagePointer(
            ModelPackageIdentity("object_detector", "1.0.0"),
            "a".repeat(64),
        )
        val POLICY = SamplingPolicySpec(750, 250, 2_000, true)
        val ARTIFACT_IDENTITY = "b".repeat(64)
        val DEVICE_FINGERPRINT = "c".repeat(64)
    }
}
