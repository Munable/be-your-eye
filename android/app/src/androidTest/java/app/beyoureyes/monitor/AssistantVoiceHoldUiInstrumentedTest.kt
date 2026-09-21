package app.beyoureyes.monitor

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.monitor.feature.assistant.AssistantVoiceState
import app.beyoureyes.monitor.feature.assistant.VoiceHoldButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantVoiceHoldUiInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tapAndHoldHaveExactlyOneActionEach() {
        var taps = 0
        var presses = 0
        var releases = 0
        var cancels = 0
        compose.setContent {
            BeYourEyeTheme {
                VoiceHoldButton(
                    enabled = true,
                    state = AssistantVoiceState.IDLE,
                    modifier = Modifier.testTag("voice-hold-under-test"),
                    onTapToType = { taps++ },
                    onPress = { presses++; true },
                    onRelease = { releases++ },
                    onCancel = { cancels++ },
                )
            }
        }

        compose.onNodeWithTag("voice-hold-under-test").performClick()
        compose.runOnIdle {
            assertEquals(1, taps)
            assertEquals(0, presses)
        }

        compose.onNodeWithTag("voice-hold-under-test").performTouchInput {
            down(Offset(center.x, center.y))
            advanceEventTime(750)
            up()
        }
        compose.runOnIdle {
            assertEquals(1, taps)
            assertEquals(1, presses)
            assertEquals(1, releases)
            assertEquals(0, cancels)
        }
    }

    @Test
    fun talkBackCanStartThenStopAndSendVoice() {
        var state by mutableStateOf(AssistantVoiceState.IDLE)
        var starts = 0
        var sends = 0
        compose.setContent {
            BeYourEyeTheme {
                VoiceHoldButton(
                    enabled = true,
                    state = state,
                    modifier = Modifier.testTag("voice-hold-under-test"),
                    onTapToType = {},
                    onPress = {
                        starts++
                        state = AssistantVoiceState.RECORDING
                        true
                    },
                    onRelease = {
                        sends++
                        state = AssistantVoiceState.IDLE
                    },
                    onCancel = { state = AssistantVoiceState.IDLE },
                )
            }
        }

        val node = compose.onNodeWithTag("voice-hold-under-test")
        val startAction = node.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        compose.runOnIdle { check(startAction.action()) }
        compose.waitForIdle()
        assertEquals(1, starts)

        val stopAction = node.fetchSemanticsNode().config[SemanticsActions.CustomActions].first()
        compose.runOnIdle { check(stopAction.action()) }
        compose.waitForIdle()
        assertEquals(1, sends)
    }
}
