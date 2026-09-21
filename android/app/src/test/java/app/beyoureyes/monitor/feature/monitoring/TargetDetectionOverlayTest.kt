package app.beyoureyes.monitor.feature.monitoring

import app.beyoureyes.core.domain.Detection
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetDetectionOverlayTest {
    @Test
    fun `detection observations expose their real boxes to the camera overlay`() {
        val expected = Detection(
            label = "delivery_truck",
            score = 0.91f,
            box = NormalizedRect(0.2f, 0.3f, 0.7f, 0.8f),
        )

        assertEquals(
            listOf(expected),
            visibleTargetDetections(
                Observation.Detections(listOf(expected), sourceSequence = 7),
                targetId = "delivery_truck",
            ),
        )
    }

    @Test
    fun `overlay filters detections to the configured target identity`() {
        val target = Detection(
            label = "delivery_truck",
            score = 0.91f,
            box = NormalizedRect(0.2f, 0.3f, 0.7f, 0.8f),
        )
        val unrelated = target.copy(label = "person")

        assertEquals(
            listOf(target),
            visibleTargetDetections(
                Observation.Detections(listOf(unrelated, target), sourceSequence = 7),
                targetId = "delivery_truck",
            ),
        )
        assertTrue(
            visibleTargetDetections(
                Observation.Detections(listOf(target), sourceSequence = 7),
                targetId = null,
            ).isEmpty(),
        )
    }

    @Test
    fun `state and unavailable observations never invent localization boxes`() {
        assertTrue(
            visibleTargetDetections(
                Observation.State("reference:present", 0.9f, sourceSequence = 8, stableFrameCount = 3),
                targetId = "reference",
            ).isEmpty(),
        )
        assertTrue(visibleTargetDetections(null, targetId = "delivery_truck").isEmpty())
    }
}
