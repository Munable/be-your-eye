package app.beyoureyes.monitor

import com.google.android.gms.common.ConnectionResult
import org.junit.Assert.assertEquals
import org.junit.Test

class GooglePlayAvailabilityTest {
    @Test
    fun onlySuccessfulGooglePlayServicesStatusIsUsableForPush() {
        assertEquals(true, isUsableGooglePlayServicesStatus(ConnectionResult.SUCCESS))
        listOf(
            ConnectionResult.SERVICE_MISSING,
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED,
            ConnectionResult.SERVICE_DISABLED,
            ConnectionResult.SERVICE_INVALID,
        ).forEach { status ->
            assertEquals(false, isUsableGooglePlayServicesStatus(status))
        }
    }

}
