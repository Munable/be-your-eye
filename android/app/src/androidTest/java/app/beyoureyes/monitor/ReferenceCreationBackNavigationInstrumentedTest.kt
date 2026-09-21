package app.beyoureyes.monitor

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.monitor.feature.reference.referenceCreationBackNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceCreationBackNavigationInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun savingConsumesSystemBackAndHidesTopBack() {
        var saving by mutableStateOf(false)
        var backCount = 0
        var topBack: (() -> Unit)? = null
        compose.setContent {
            topBack = referenceCreationBackNavigation(saving) { backCount++ }
        }

        compose.runOnIdle { assertNotNull(topBack) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, backCount) }

        compose.runOnIdle { saving = true }
        compose.runOnIdle { assertNull(topBack) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, backCount) }
    }
}
