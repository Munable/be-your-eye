package app.beyoureyes.monitor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.beyoureyes.monitor.feature.subscription.EntitlementLease
import app.beyoureyes.monitor.feature.subscription.SecureEntitlementLeaseStore
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureEntitlementLeaseStoreInstrumentedTest {
    @Test
    fun softRefreshBoundaryKeepsEncryptedLeaseUntilHardExpiry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SecureEntitlementLeaseStore(context)
        val now = Instant.parse("2026-08-31T00:00:00Z")
        val lease = EntitlementLease(
            accountId = ACCOUNT_ID,
            providerState = "SUBSCRIPTION_STATE_ACTIVE",
            expiresAt = now.plusSeconds(600),
            refreshAfter = now.minusSeconds(60),
        )

        store.clear()
        try {
            store.save(lease)

            val loaded = store.load(ACCOUNT_ID, now)
            assertNotNull(loaded)
            assertEquals(lease, loaded)
            assertNull(store.load(ACCOUNT_ID, lease.expiresAt))
        } finally {
            store.clear()
        }
    }

    private companion object {
        const val ACCOUNT_ID = "018f3f72-6e5c-7b4e-9a8f-1234567890ab"
    }
}
