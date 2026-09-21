package app.beyoureyes.monitor

import app.beyoureyes.core.data.ModelPackageIdentity
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.vision.FramePixels
import app.beyoureyes.core.vision.PixelRect
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.ReferenceImageMetadata
import app.beyoureyes.core.vision.SourceFrame
import app.beyoureyes.core.vision.TargetProfile
import app.beyoureyes.core.vision.YuvPlane
import app.beyoureyes.monitor.design.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupFieldValidationTest {
    @Test
    fun `only reference-image similarity tasks require the setup check`() {
        val references = TargetProfile.ReferenceImages(
            targetId = "field_target",
            images = (1..3).map { index ->
                ReferenceImageMetadata(
                    referenceId = "reference_0$index",
                    localAssetId = "asset_$index",
                    contentSha256 = index.toString().repeat(64),
                    width = 640,
                    height = 480,
                )
            },
        )
        val objectClass = TargetProfile.ObjectClass("field_target", "目标", "target")

        assertTrue(
            requiresSimilarityFieldValidation(references, RecipeFamily.SIMILARITY_MATCH_V1),
        )
        assertTrue(
            requiresSimilarityFieldValidation(objectClass, RecipeFamily.OBJECT_DETECTION_V1),
        )
        assertFalse(requiresSimilarityFieldValidation(objectClass, RecipeFamily.SIMILARITY_MATCH_V1))
        assertFalse(requiresSimilarityFieldValidation(null, RecipeFamily.SIMILARITY_MATCH_V1))
    }

    @Test
    fun `only the current reference mode is recognized`() {
        assertTrue(storedTaskUsesReferenceImages("reference_images"))
        assertFalse(storedTaskUsesReferenceImages("quick_references"))
        assertFalse(storedTaskUsesReferenceImages("stable_references"))
        assertFalse(storedTaskUsesReferenceImages("text"))
        assertFalse(storedTaskUsesReferenceImages("structured_reading"))
    }

    @Test
    fun `camera YUV is made upright cropped to ROI and tightly packed RGB`() {
        val roi = NormalizedRect(0.25f, 0.25f, 0.75f, 1f)
        val frame = SourceFrame(
            sourceSequence = 17,
            monotonicTimeMillis = 4_200,
            capturedAtEpochMillis = 1_700_000_004_200,
            width = 4,
            height = 4,
            rotationDegrees = 90,
            cropRect = PixelRect(0, 0, 4, 4),
            pixels = FramePixels.Yuv420(
                yPlane = YuvPlane(
                    bytes = byteArrayOf(
                        32, 48, 64, 80,
                        48, 64, 80, 96,
                        64, 80, 96, 112,
                        80, 96, 112, 128.toByte(),
                    ),
                    rowStride = 4,
                    pixelStride = 1,
                ),
                uPlane = YuvPlane(ByteArray(4) { 128.toByte() }, 2, 1),
                vPlane = YuvPlane(ByteArray(4) { 128.toByte() }, 2, 1),
            ),
        )

        val normalized = SetupFieldValidationFrameNormalizer.normalize(frame, roi)
        val output = normalized.sourceFrame
        val pixels = output.pixels as FramePixels.Rgb888

        assertEquals(roi, normalized.roi)
        assertEquals(17L, output.sourceSequence)
        assertEquals(4_200L, output.monotonicTimeMillis)
        assertEquals(1_700_000_004_200L, output.capturedAtEpochMillis)
        assertEquals(2, output.width)
        assertEquals(3, output.height)
        assertEquals(0, output.rotationDegrees)
        assertEquals(PixelRect(0, 0, 2, 3), output.cropRect)
        assertEquals(output.width * 3, pixels.rowStride)
        assertEquals(output.width * output.height * 3, pixels.bytes.size)
        assertEquals(null, normalized.contractFailureOrNull())
    }

    @Test
    fun `setup copy is concise and never presents material sufficiency as accuracy`() {
        val identity = identity()

        assertEquals(
            uiText(R.string.field_validation_aim_camera),
            SetupFieldValidationStatus.Idle.userMessage(),
        )
        assertEquals(
            uiText(R.string.field_validation_aim_camera),
            SetupFieldValidationStatus.Opening(identity).userMessage(),
        )
        assertEquals(
            uiText(R.string.field_validation_checking),
            SetupFieldValidationStatus.Checking(identity, emptyFieldValidationSummary())
                .userMessage(),
        )
        assertEquals(
            uiText(R.string.field_validation_passed),
            SetupFieldValidationStatus.Passed(identity, emptyFieldValidationSummary(), 2L)
                .userMessage(),
        )
        assertEquals(
            uiText(R.string.field_validation_not_found),
            SetupFieldValidationStatus.NotFound(identity, emptyFieldValidationSummary())
                .userMessage(),
        )
        assertEquals(
            uiText(R.string.field_validation_failed),
            SetupFieldValidationStatus.Failed(identity, emptyFieldValidationSummary())
                .userMessage(),
        )
        assertEquals(
            uiText(R.string.field_validation_invalidated),
            SetupFieldValidationStatus.Invalidated(
                identity,
                identity.copy(roi = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f)),
                emptyFieldValidationSummary(),
            ).userMessage(),
        )
    }

    @Test
    fun `setup status preserves the immediate provisional signal without media`() {
        val expected = identity()
        val provisionalSummary = emptyFieldValidationSummary().copy(
            processedFrames = 1,
            presentFrames = 1,
            latestSignal = SimilarityFieldValidationSignal.PRESENT,
        )
        val setup = SimilarityFieldValidationUiState.Checking(
            expectedIdentity = expected,
            summary = provisionalSummary,
        ).toSetupStatus() as SetupFieldValidationStatus.Checking

        assertEquals(SimilarityFieldValidationSignal.PRESENT, setup.summary.latestSignal)
        assertEquals(1, setup.summary.processedFrames)
        assertEquals(null, emptyFieldValidationSummary().latestSignal)
    }

    @Test
    fun `passed start gate is bound to exact task revision package and ROI`() {
        val expected = identity()
        val passed = SetupFieldValidationStatus.Passed(expected, emptyFieldValidationSummary(), 7L)
        val notFound = SetupFieldValidationStatus.NotFound(expected, emptyFieldValidationSummary())

        assertTrue(fieldValidationPassedFor(passed, expected))
        assertFalse(fieldValidationPassedFor(notFound, expected))
        assertFalse(fieldValidationPassedFor(SetupFieldValidationStatus.Idle, expected))
        assertFalse(fieldValidationPassedFor(passed, null))
        assertFalse(fieldValidationPassedFor(passed, expected.copy(taskRevision = 8)))
        assertTrue(fieldValidationPassedForEpisode(passed, expected, 7L))
        assertFalse(fieldValidationPassedForEpisode(passed, expected, 8L))
        assertFalse(fieldValidationPassedForEpisode(passed, expected, null))
        assertFalse(
            fieldValidationPassedFor(
                passed,
                expected.copy(
                    packagePointer = pointer("2.0.0", "b".repeat(64)),
                ),
            ),
        )
        assertFalse(
            fieldValidationPassedFor(
                passed,
                expected.copy(roi = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f)),
            ),
        )
    }

    @Test
    fun `not found stays live while technical failure and invalidation are retryable`() {
        val expected = identity()
        val notFound = SetupFieldValidationStatus.NotFound(
            expected,
            emptyFieldValidationSummary(),
        )
        val failed = SetupFieldValidationStatus.Failed(
            expected,
            emptyFieldValidationSummary(),
        )
        val invalidated = SetupFieldValidationStatus.Invalidated(
            expected,
            expected.copy(taskRevision = expected.taskRevision + 1),
            emptyFieldValidationSummary(),
        )

        assertTrue(notFound.isCheckInProgress())
        assertFalse(notFound.canRetryCheck())
        assertTrue(
            SetupFieldValidationStatus.Passed(
                expected,
                emptyFieldValidationSummary(),
                2L,
            ).isCheckInProgress(),
        )
        assertFalse(failed.isCheckInProgress())
        assertTrue(failed.canRetryCheck())
        assertFalse(invalidated.isCheckInProgress())
        assertTrue(invalidated.canRetryCheck())
        assertTrue(SetupFieldValidationStatus.Opening(expected).isCheckInProgress())
        assertFalse(SetupFieldValidationStatus.Opening(expected).canRetryCheck())
    }

    private fun identity() = SimilarityFieldValidationIdentity(
        taskId = "0198f5a4-1357-7abc-8def-0123456789ab",
        taskRevision = 7,
        packagePointer = pointer(),
        roi = NormalizedRect(0f, 0f, 1f, 1f),
        targetId = "field_target",
    )

    private fun pointer(
        version: String = "1.0.0",
        manifest: String = "a".repeat(64),
    ) = ModelPackagePointer(
        ModelPackageIdentity("similarity_test_fixture", version),
        manifest,
    )
}
