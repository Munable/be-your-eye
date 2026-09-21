package app.beyoureyes.monitor

import app.beyoureyes.core.data.ModelPackageIdentity
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.data.ResolvedSamplingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeCameraConfigTest {
    @Test
    fun `raw runtime config rejects a zero-width ROI`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCameraConfig.fromRaw(
                taskId = TASK_ID,
                taskRevision = 1,
                roiLeft = 0.4f,
                roiTop = 0.2f,
                roiRight = 0.4f,
                roiBottom = 0.8f,
                viewPortWidth = 1_080,
                viewPortHeight = 1_920,
                targetRotation = 0,
                resolvedSamplingConfig = sampling(500),
            )
        }
    }

    @Test
    fun `raw runtime config rejects a zero-height ROI`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCameraConfig.fromRaw(
                taskId = TASK_ID,
                taskRevision = 1,
                roiLeft = 0.2f,
                roiTop = 0.6f,
                roiRight = 0.8f,
                roiBottom = 0.6f,
                viewPortWidth = 1_080,
                viewPortHeight = 1_920,
                targetRotation = 0,
                resolvedSamplingConfig = sampling(500),
            )
        }
    }

    @Test
    fun `resolved sampling rejects an interval outside its signed Manifest bounds`() {
        assertThrows(IllegalArgumentException::class.java) { sampling(249, 250, 2_000) }
        assertThrows(IllegalArgumentException::class.java) { sampling(2_001, 250, 2_000) }
    }

    @Test
    fun `raw runtime config carries the exact resolved signed boundaries`() {
        assertEquals(250, validConfig(250, 250, 2_000).analysisIntervalMillis)
        assertEquals(2_000, validConfig(2_000, 250, 2_000).analysisIntervalMillis)
    }

    @Test
    fun `reading sequence resumes after service process recovery`() {
        assertEquals(40, resumedSourceSequenceBase(current = 0, persisted = 40))
        assertEquals(80, resumedSourceSequenceBase(current = 80, persisted = 40))
        assertEquals(0, resumedSourceSequenceBase(current = 0, persisted = null))
        assertThrows(IllegalArgumentException::class.java) {
            resumedSourceSequenceBase(current = 0, persisted = Long.MAX_VALUE)
        }
    }

    private fun validConfig(intervalMillis: Long, minimum: Long, maximum: Long) =
        RuntimeCameraConfig.fromRaw(
        taskId = TASK_ID,
        taskRevision = 1,
        roiLeft = 0f,
        roiTop = 0f,
        roiRight = 1f,
        roiBottom = 1f,
        viewPortWidth = 1_080,
        viewPortHeight = 1_920,
        targetRotation = 0,
        resolvedSamplingConfig = sampling(intervalMillis, minimum, maximum),
    )

    private fun sampling(
        intervalMillis: Long,
        minimum: Long = 250,
        maximum: Long = 2_000,
    ) = ResolvedSamplingConfig(
        taskId = TASK_ID,
        taskRevision = 1,
        catalogVersion = "2026.08.24.1",
        capabilityId = "visual_target",
        modelProfileKey = "common_objects",
        recipeId = "object_detection_general_v1",
        intentKey = "object.common.apple",
        packagePointer = POINTER,
        artifactIdentitySha256 = "b".repeat(64),
        deviceFingerprintSha256 = "c".repeat(64),
        intervalMillis = intervalMillis,
        manifestMinimumIntervalMillis = minimum,
        manifestMaximumIntervalMillis = maximum,
        adaptiveEnabled = false,
    )

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000000"
        val POINTER = ModelPackagePointer(
            ModelPackageIdentity("object_detector", "1.0.0"),
            "a".repeat(64),
        )
    }
}
