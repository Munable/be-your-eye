package app.beyoureyes.monitor

import app.beyoureyes.core.data.cloud.CloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudNetworkActivationPolicyTest {
    @Test
    fun androidUsableMemoryRoundsToItsPhysicalGiBTierWithoutPromotingSmallerDevices() {
        assertEquals(8_192, reportedPhysicalMemoryTierMb(8_129_336L * 1_024L))
        assertEquals(8_192, reportedPhysicalMemoryTierMb(8L * 1_073_741_824L))
        assertEquals(6_144, reportedPhysicalMemoryTierMb(6L * 1_073_741_824L - 1L))
        assertEquals(4_096, reportedPhysicalMemoryTierMb(4L * 1_073_741_824L))
        assertEquals(0, reportedPhysicalMemoryTierMb(0))
    }

    @Test
    fun signedOutLaunchDoesNotScheduleCloudOrRegisterForPush() {
        assertEquals(
            CloudNetworkActivationDecision(
                scheduleAccountSync = false,
                registerForPush = false,
            ),
            decideCloudNetworkActivation(
                accountState = CloudAccountState.SignedOut,
                productAccessGranted = false,
                notificationsEnabled = true,
                firebaseConfigured = true,
                gmsAvailable = true,
            ),
        )
    }

    @Test
    fun foregroundSyncOnlyRunsForAnExistingSignedInSession() {
        assertEquals(
            false,
            shouldEnqueueForegroundSync(
                CloudAccountState.Disabled("not configured"),
                productAccessGranted = true,
            ),
        )
        assertEquals(
            false,
            shouldEnqueueForegroundSync(CloudAccountState.SignedOut, productAccessGranted = true),
        )
        assertEquals(
            true,
            shouldEnqueueForegroundSync(
                CloudAccountState.SignedIn(
                    accountId = "f5158dd2-c5d0-46b3-9af9-d7e2e7263864",
                    email = "person@example.com",
                ),
                productAccessGranted = true,
            ),
        )
        assertEquals(
            false,
            shouldEnqueueForegroundSync(
                CloudAccountState.SignedIn(
                    accountId = "f5158dd2-c5d0-46b3-9af9-d7e2e7263864",
                    email = "person@example.com",
                ),
                productAccessGranted = false,
            ),
        )
    }

    @Test
    fun signedInAccountSyncsButPushStillRequiresPerDeviceOptIn() {
        val state = CloudAccountState.SignedIn(
            accountId = "f5158dd2-c5d0-46b3-9af9-d7e2e7263864",
            email = "person@example.com",
        )
        assertEquals(
            CloudNetworkActivationDecision(
                scheduleAccountSync = true,
                registerForPush = false,
            ),
            decideCloudNetworkActivation(
                accountState = state,
                productAccessGranted = true,
                notificationsEnabled = false,
                firebaseConfigured = true,
                gmsAvailable = true,
            ),
        )
        assertEquals(
            CloudNetworkActivationDecision(
                scheduleAccountSync = true,
                registerForPush = true,
            ),
            decideCloudNetworkActivation(
                accountState = state,
                productAccessGranted = true,
                notificationsEnabled = true,
                firebaseConfigured = true,
                gmsAvailable = true,
            ),
        )
    }

    @Test
    fun missingFirebaseOrGmsNeverRegistersForPush() {
        val state = CloudAccountState.SignedIn(
            accountId = "f5158dd2-c5d0-46b3-9af9-d7e2e7263864",
            email = null,
        )
        for ((firebaseConfigured, gmsAvailable) in listOf(false to true, true to false)) {
            assertEquals(
                CloudNetworkActivationDecision(
                    scheduleAccountSync = true,
                    registerForPush = false,
                ),
                decideCloudNetworkActivation(
                    accountState = state,
                    productAccessGranted = true,
                    notificationsEnabled = true,
                    firebaseConfigured = firebaseConfigured,
                    gmsAvailable = gmsAvailable,
                ),
            )
        }
    }

    @Test
    fun signedInAccountWithoutEntitlementHasNoSyncPushOrRealtimeActivation() {
        val state = CloudAccountState.SignedIn(
            accountId = "f5158dd2-c5d0-46b3-9af9-d7e2e7263864",
            email = null,
        )
        assertEquals(
            CloudNetworkActivationDecision(false, false),
            decideCloudNetworkActivation(
                accountState = state,
                productAccessGranted = false,
                notificationsEnabled = true,
                firebaseConfigured = true,
                gmsAvailable = true,
            ),
        )
    }
}
