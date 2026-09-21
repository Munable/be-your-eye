package app.beyoureyes.monitor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.core.data.cloud.CloudAccountState
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalOnlyCloudBootInstrumentedTest {
    @Test
    fun missingSupabaseAndFirebaseConfigurationKeepsApplicationLocalAndOperational() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assumeFalse(
            "This fixture verifies a build with no cloud configuration",
            CloudBootstrap.cloudConfigured(context),
        )

        CloudBootstrap.initialize(context)

        assertFalse(CloudBootstrap.cloudConfigured(context))
        assertTrue(
            CloudBootstrap.accountController(context).state.value is CloudAccountState.Disabled,
        )
        assertTrue(FirebaseApp.getApps(context).isEmpty())
    }
}
