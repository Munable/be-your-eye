package app.beyoureyes.monitor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.monitor.feature.monitoring.CameraTags
import app.beyoureyes.monitor.feature.monitoring.ReadingRoiEducationCard
import app.beyoureyes.monitor.feature.monitoring.ReadingRoiEducationTags
import app.beyoureyes.monitor.feature.monitoring.ReadingTargetOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingRoiOverlayInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aTightBoxAroundAShortNumericLineIsKept() {
        var region by mutableStateOf<NormalizedRect?>(null)
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        compose.setContent {
            BeYourEyeTheme {
                ReadingTargetOverlay(
                    automaticBox = null,
                    manualRegion = region,
                    editable = true,
                    onManualRegionChanged = { region = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag(CameraTags.READING_TARGET_OVERLAY).performTouchInput {
            down(Offset(40f * density, 80f * density))
            moveTo(Offset(140f * density, 104f * density))
            up()
        }
        compose.runOnIdle { assertNotNull(region) }
    }

    @Test
    fun directDragDrawsImmediatelyAndBothCornerHandlesResize() {
        var region by mutableStateOf<NormalizedRect?>(null)
        compose.setContent {
            BeYourEyeTheme {
                Box(Modifier.fillMaxSize()) {
                    ReadingTargetOverlay(
                        automaticBox = null,
                        manualRegion = region,
                        editable = true,
                        onManualRegionChanged = { region = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        val overlay = compose.onNodeWithTag(CameraTags.READING_TARGET_OVERLAY)

        // No clock advance or long-press: the first pointer movement must start the box.
        overlay.performTouchInput {
            down(percentOffset(0.1f, 0.2f))
            moveTo(percentOffset(0.6f, 0.7f))
            up()
        }
        compose.runOnIdle {
            assertRectEquals(NormalizedRect(0.1f, 0.2f, 0.6f, 0.7f), region)
        }

        overlay.performTouchInput {
            down(percentOffset(0.1f, 0.2f))
            moveTo(percentOffset(0.2f, 0.3f))
            up()
        }
        compose.runOnIdle {
            assertRectEquals(NormalizedRect(0.2f, 0.3f, 0.6f, 0.7f), region)
        }

        overlay.performTouchInput {
            down(percentOffset(0.6f, 0.7f))
            moveTo(percentOffset(0.8f, 0.9f))
            up()
        }
        compose.runOnIdle {
            assertRectEquals(NormalizedRect(0.2f, 0.3f, 0.8f, 0.9f), region)
        }
    }

    @Test
    fun talkBackActionsMoveBothReadingHandles() {
        var region by mutableStateOf(NormalizedRect(0.2f, 0.25f, 0.8f, 0.75f))
        compose.setContent {
            BeYourEyeTheme {
                ReadingTargetOverlay(
                    automaticBox = null,
                    manualRegion = region,
                    editable = true,
                    onManualRegionChanged = { region = it ?: region },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val actions = compose.onNodeWithTag(CameraTags.READING_TARGET_OVERLAY)
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(8, actions.size)
        compose.runOnIdle { check(actions.first().action()) }
        compose.runOnIdle { assertEquals(0.17f, region.left, 0.002f) }
        compose.runOnIdle { check(actions[5].action()) }
        compose.runOnIdle { assertEquals(0.83f, region.right, 0.002f) }
    }

    @Test
    fun setupExplainsTheAccuracyTradeoffAndFixedPhoneMount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            BeYourEyeTheme {
                ReadingRoiEducationCard()
            }
        }

        compose.onNodeWithTag(ReadingRoiEducationTags.CARD).assertExists()
        compose.onNodeWithText(context.getString(R.string.reading_roi_body))
            .assertExists()
        compose.onNodeWithText(context.getString(R.string.reading_roi_fixed_phone)).assertExists()
    }

    @Test
    fun firstDragDismissesTheTutorialEvenIfTheBoxIsLaterCleared() {
        var region by mutableStateOf<NormalizedRect?>(null)
        var tutorialDismissed by mutableStateOf(false)
        compose.setContent {
            BeYourEyeTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(180.dp)) {
                        ReadingTargetOverlay(
                            automaticBox = null,
                            manualRegion = region,
                            editable = true,
                            onInteractionStarted = { tutorialDismissed = true },
                            onManualRegionChanged = { region = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (!tutorialDismissed) ReadingRoiEducationCard()
                }
            }
        }

        compose.onNodeWithTag(CameraTags.READING_TARGET_OVERLAY).performTouchInput {
            down(percentOffset(0.2f, 0.3f))
            moveTo(percentOffset(0.7f, 0.6f))
            up()
        }
        compose.runOnIdle { region = null }

        compose.onNodeWithTag(ReadingRoiEducationTags.CARD).assertDoesNotExist()
    }

    private fun assertRectEquals(expected: NormalizedRect, actual: NormalizedRect?) {
        requireNotNull(actual)
        assertEquals(expected.left, actual.left, 0.002f)
        assertEquals(expected.top, actual.top, 0.002f)
        assertEquals(expected.right, actual.right, 0.002f)
        assertEquals(expected.bottom, actual.bottom, 0.002f)
    }
}
