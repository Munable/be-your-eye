package app.beyoureyes.core.domain

import org.junit.Assert.assertThrows
import org.junit.Test

class NormalizedRectTest {
    @Test
    fun `zero-width ROI is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedRect(left = 0.4f, top = 0.2f, right = 0.4f, bottom = 0.8f)
        }
    }

    @Test
    fun `zero-height ROI is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedRect(left = 0.2f, top = 0.6f, right = 0.8f, bottom = 0.6f)
        }
    }
}
