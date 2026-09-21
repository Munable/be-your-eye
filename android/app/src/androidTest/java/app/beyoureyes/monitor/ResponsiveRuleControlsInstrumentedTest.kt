package app.beyoureyes.monitor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsSelected
import app.beyoureyes.monitor.design.ProductDurationChips
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.monitor.design.TRIGGER_DURATION_PRESETS_SECONDS
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.monitor.feature.monitoring.TargetPresenceRuleControls
import app.beyoureyes.monitor.feature.monitoring.TargetPresenceRuleTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveRuleControlsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun customTwoSecondDurationCanBeEnteredAndIsShownAsSelected() {
        var selected by mutableIntStateOf(1)
        compose.setContent {
            BeYourEyeTheme {
                ProductDurationChips(selected, { selected = it }, testTagPrefix = "test_duration")
            }
        }
        compose.onNodeWithTag("test_duration_custom").performClick()
        compose.onNodeWithTag("duration_custom_input").performTextReplacement("2")
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_confirm))
            .performClick()
        compose.runOnIdle { assertEquals(2, selected) }
        compose.onNodeWithTag("test_duration_custom").assertIsSelected()
    }

    @Test
    fun everyRuleChoiceIsVisibleAtNarrowWidthAndLargeText() {
        compose.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity.density, fontScale = 1.3f),
            ) {
                BeYourEyeTheme {
                    TargetPresenceRuleControls(
                        rule = MonitorRule.TargetPresence(),
                        onRuleChange = {},
                        modifier = Modifier.width(280.dp),
                        targetLabel = "apple",
                    )
                }
            }
        }

        PresenceRuleKind.entries.forEach { kind ->
            compose.onNodeWithTag("${TargetPresenceRuleTags.KIND_PREFIX}_${kind.wireValue}")
                .assertIsDisplayed()
        }
        TRIGGER_DURATION_PRESETS_SECONDS.forEach { seconds ->
            compose.onNodeWithTag("${TargetPresenceRuleTags.DURATION_PREFIX}_$seconds")
                .assertIsDisplayed()
        }
    }
}
