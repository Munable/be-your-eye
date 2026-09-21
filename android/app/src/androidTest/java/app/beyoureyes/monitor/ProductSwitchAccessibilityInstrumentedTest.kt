package app.beyoureyes.monitor

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.monitor.design.ProductSwitch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductSwitchAccessibilityInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchExposesItsSettingName() {
        var requested = false
        compose.setContent {
            BeYourEyeTheme {
                ProductSwitch(
                    checked = false,
                    onCheckedChange = { requested = it },
                    accessibilityLabel = "本机通知",
                    modifier = Modifier.testTag("switch-under-test"),
                )
            }
        }

        compose.onNodeWithContentDescription("本机通知").performClick()
        compose.runOnIdle { assertTrue(requested) }
    }
}
