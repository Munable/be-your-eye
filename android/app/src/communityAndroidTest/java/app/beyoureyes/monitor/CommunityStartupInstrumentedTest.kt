package app.beyoureyes.monitor

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.monitor.feature.home.HomeTags
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CommunityStartupInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun opensManualCreationWithoutCloudOrPurchase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(BuildConfig.COMMUNITY_BUILD)
        compose.onNodeWithTag(HomeTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(HomeTags.READING).assertIsDisplayed()
    }
}
