package app.beyoureyes.core.vision

import app.beyoureyes.core.domain.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Test

class RoiCoordinateMapperTest {
    @Test
    fun `golden ROI coordinates cover all four rotations`() {
        val roi = NormalizedRect(0.1f, 0.2f, 0.4f, 0.8f)
        val crop = PixelRect(100, 50, 1_100, 650)
        val expected = mapOf(
            0 to PixelRect(200, 170, 500, 530),
            90 to PixelRect(300, 410, 900, 590),
            180 to PixelRect(700, 170, 1_000, 530),
            270 to PixelRect(300, 110, 900, 290),
        )

        expected.forEach { (rotation, golden) ->
            assertEquals(
                "rotation=$rotation",
                golden,
                RoiCoordinateMapper.normalizedToBufferRect(roi, crop, rotation),
            )
        }
    }

    @Test
    fun `golden ROI coordinates cover portrait landscape four-three and square crops`() {
        val roi = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)
        val goldens = mapOf(
            PixelRect(0, 0, 1_080, 1_920) to PixelRect(270, 480, 810, 1_440),
            PixelRect(0, 0, 1_920, 1_080) to PixelRect(480, 270, 1_440, 810),
            PixelRect(0, 0, 1_440, 1_080) to PixelRect(360, 270, 1_080, 810),
            PixelRect(0, 0, 1_000, 1_000) to PixelRect(250, 250, 750, 750),
        )

        goldens.forEach { (crop, golden) ->
            assertEquals(
                "crop=$crop",
                golden,
                RoiCoordinateMapper.normalizedToBufferRect(roi, crop, 0),
            )
        }
    }

    @Test
    fun `runtime frame metadata resolves the canonical ROI through the mapper`() {
        val roi = NormalizedRect(0.1f, 0.2f, 0.4f, 0.8f)

        val metadata = RuntimeFrameMetadata.resolve(
            sourceSequence = 42,
            captureTimestampNanos = 123_456,
            imageWidth = 1_200,
            imageHeight = 700,
            rotationDegrees = 90,
            imageCropRect = PixelRect(100, 50, 1_100, 650),
            uprightViewPortRoi = roi,
        )

        assertEquals(roi, metadata.uprightViewPortRoi)
        assertEquals(PixelRect(300, 410, 900, 590), metadata.bufferRoi)
        assertEquals(42, metadata.sourceSequence)
    }
}
