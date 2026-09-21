package app.beyoureyes.monitor

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.monitor.feature.assistant.AssistantTags
import app.beyoureyes.monitor.feature.home.HomeTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantAccessUiInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun grantedFunctionalEntitlementOpensAssistantComposer() {
        compose.onNodeWithTag(HomeTags.ASSISTANT).performClick()
        compose.onNodeWithTag(AssistantTags.INPUT).assertIsEnabled()
        compose.onNodeWithTag(AssistantTags.VOICE).assertIsEnabled()
    }
}
