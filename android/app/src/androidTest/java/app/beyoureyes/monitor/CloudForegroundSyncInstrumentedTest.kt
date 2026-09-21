package app.beyoureyes.monitor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import app.beyoureyes.core.data.cloud.CloudAccountState
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudForegroundSyncInstrumentedTest {
    @Test
    fun foregroundEntryWithoutSignedInSessionDoesNotAddSyncWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeFalse(
            "This assertion requires a local build with no signed-in test account",
            CloudBootstrap.accountController(context).state.value is CloudAccountState.SignedIn,
        )
        val workManager = WorkManager.getInstance(context)
        val before = workManager
            .getWorkInfosForUniqueWork(CloudWorkScheduler.UNIQUE_SYNC)
            .get(10, TimeUnit.SECONDS)
            .map { it.id }
            .toSet()

        CloudBootstrap.enqueueForegroundSyncIfSignedIn(context)

        val after = workManager
            .getWorkInfosForUniqueWork(CloudWorkScheduler.UNIQUE_SYNC)
            .get(10, TimeUnit.SECONDS)
            .map { it.id }
            .toSet()
        assertEquals(before, after)
    }
}
