package app.beyoureyes.monitor

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseInstallationIdModeInstrumentedTest {
    @Test
    fun manifestUsesExplicitInstallationIdRegistrationWithManualActivation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val applicationInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )

        assertTrue(
            applicationInfo.metaData.getBoolean(
                "firebase_messaging_installation_id_enabled",
                false,
            ),
        )
        assertFalse(
            applicationInfo.metaData.getBoolean(
                "firebase_messaging_auto_init_enabled",
                true,
            ),
        )
    }
}
