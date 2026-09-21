package app.beyoureyes.monitor

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.core.domain.Detection
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.monitor.feature.monitoring.ActiveMonitoringTags
import app.beyoureyes.monitor.feature.monitoring.TargetDetectionOverlay
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TargetDetectionOverlayInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun detectorCoordinatesRenderAsVisibleProductGreenCorners() {
        compose.setContent {
            BeYourEyeTheme {
                TargetDetectionOverlay(
                    detections = listOf(
                        Detection(
                            label = "delivery_truck",
                            score = 0.91f,
                            box = NormalizedRect(0.2f, 0.25f, 0.8f, 0.75f),
                        ),
                    ),
                    modifier = Modifier.size(300.dp),
                )
            }
        }

        val pixels = compose.onNodeWithTag(ActiveMonitoringTags.TARGET_BOXES)
            .captureToImage()
            .toPixelMap()
        var greenPixels = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y] == ProductColors.Green) greenPixels++
            }
        }
        assertTrue("expected rendered target-box corners", greenPixels >= 40)
    }
}
