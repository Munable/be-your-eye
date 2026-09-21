package app.beyoureyes.monitor

import androidx.compose.ui.geometry.Offset
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.monitor.feature.monitoring.ReadingBoxDragMode
import app.beyoureyes.monitor.feature.monitoring.normalizedDragRegion
import app.beyoureyes.monitor.feature.monitoring.nudgeManualRegion
import app.beyoureyes.monitor.feature.monitoring.readingBoxDragMode
import app.beyoureyes.monitor.feature.monitoring.resizeManualRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingViewportRoiTest {
    @Test
    fun `drag coordinates map directly into the shared CameraX ViewPort`() {
        assertEquals(
            NormalizedRect(0.1f, 0.2f, 0.5f, 0.6f),
            normalizedDragRegion(
                start = Offset(100f, 200f),
                end = Offset(500f, 600f),
                width = 1_000,
                height = 1_000,
                minimumPixels = 48f,
            ),
        )
        assertEquals(
            NormalizedRect(0.1f, 0.2f, 0.5f, 0.6f),
            normalizedDragRegion(
                start = Offset(500f, 600f),
                end = Offset(100f, 200f),
                width = 1_000,
                height = 1_000,
                minimumPixels = 48f,
            ),
        )
    }

    @Test
    fun `a manual ViewPort ROI requires forty eight pixels on both axes`() {
        assertNull(
            normalizedDragRegion(
                start = Offset(10f, 10f),
                end = Offset(57f, 200f),
                width = 1_000,
                height = 1_000,
                minimumPixels = 48f,
            ),
        )
    }

    @Test
    fun `only the top-left and bottom-right handles resize an existing ROI`() {
        val region = NormalizedRect(0.2f, 0.25f, 0.8f, 0.75f)

        assertEquals(
            ReadingBoxDragMode.TOP_LEFT,
            readingBoxDragMode(Offset(205f, 255f), region, 1_000, 1_000, 30f),
        )
        assertEquals(
            ReadingBoxDragMode.BOTTOM_RIGHT,
            readingBoxDragMode(Offset(795f, 745f), region, 1_000, 1_000, 30f),
        )
        assertEquals(
            ReadingBoxDragMode.NEW,
            readingBoxDragMode(Offset(500f, 500f), region, 1_000, 1_000, 30f),
        )
    }

    @Test
    fun `corner handles resize immediately while keeping the opposite corner fixed`() {
        val region = NormalizedRect(0.2f, 0.25f, 0.8f, 0.75f)

        assertEquals(
            NormalizedRect(0.1f, 0.15f, 0.8f, 0.75f),
            resizeManualRegion(
                region,
                ReadingBoxDragMode.TOP_LEFT,
                Offset(100f, 150f),
                1_000,
                1_000,
            ),
        )
        assertEquals(
            NormalizedRect(0.2f, 0.25f, 0.9f, 0.95f),
            resizeManualRegion(
                region,
                ReadingBoxDragMode.BOTTOM_RIGHT,
                Offset(900f, 950f),
                1_000,
                1_000,
            ),
        )
    }

    @Test
    fun `corner resize clamps to the viewport and preserves minimum dimensions`() {
        val region = NormalizedRect(0.2f, 0.25f, 0.8f, 0.75f)

        assertEquals(
            NormalizedRect(0f, 0f, 0.8f, 0.75f),
            resizeManualRegion(
                region,
                ReadingBoxDragMode.TOP_LEFT,
                Offset(-100f, -100f),
                1_000,
                1_000,
            ),
        )
        assertEquals(
            NormalizedRect(0.2f, 0.25f, 0.248f, 0.298f),
            resizeManualRegion(
                region,
                ReadingBoxDragMode.BOTTOM_RIGHT,
                Offset(201f, 251f),
                1_000,
                1_000,
            ),
        )
    }

    @Test
    fun `accessibility actions move each handle and preserve a usable region`() {
        val region = NormalizedRect(0.2f, 0.25f, 0.8f, 0.75f)

        assertEquals(
            NormalizedRect(0.17f, 0.25f, 0.8f, 0.75f),
            nudgeManualRegion(region, ReadingBoxDragMode.TOP_LEFT, -0.03f, 0f),
        )
        assertEquals(
            NormalizedRect(0.2f, 0.25f, 0.83f, 0.75f),
            nudgeManualRegion(region, ReadingBoxDragMode.BOTTOM_RIGHT, 0.03f, 0f),
        )
        assertEquals(
            NormalizedRect(0.2f, 0.25f, 0.248f, 0.75f),
            nudgeManualRegion(region, ReadingBoxDragMode.BOTTOM_RIGHT, -1f, 0f),
        )
    }
}
