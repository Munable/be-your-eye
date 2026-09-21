package app.beyoureyes.core.data.cloud

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreCloudLocalStateStoreInstrumentedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File
    private lateinit var store: DataStoreCloudLocalStateStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = context.preferencesDataStoreFile("cloud-local-state-test-${System.nanoTime()}")
        store = DataStoreCloudLocalStateStore(
            PreferenceDataStoreFactory.create(scope = scope) { file },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun localTaskOwnershipIsExclusiveAcrossAccountsAndReleasableOnDeletion() = runBlocking {
        assertEquals(
            setOf(TASK_A),
            store.claimUnownedLocalTaskIds(ACCOUNT_A, setOf(TASK_A)),
        )
        assertEquals(
            emptySet<String>(),
            store.claimUnownedLocalTaskIds(ACCOUNT_B, setOf(TASK_A)),
        )
        assertEquals(setOf(TASK_A), store.localTaskIdsOwnedBy(ACCOUNT_A))
        assertEquals(emptySet<String>(), store.localTaskIdsOwnedBy(ACCOUNT_B))

        assertEquals(
            setOf(TASK_B),
            store.claimUnownedLocalTaskIds(ACCOUNT_B, setOf(TASK_B)),
        )
        store.releaseLocalTaskOwnership(ACCOUNT_A)
        assertEquals(emptySet<String>(), store.localTaskIdsOwnedBy(ACCOUNT_A))
        assertEquals(setOf(TASK_B), store.localTaskIdsOwnedBy(ACCOUNT_B))
    }

    @Test
    fun deviceIdentityIsStablePerAccountAndRemovedWithDeletedAccountState() = runBlocking {
        val accountADevice = store.deviceId(ACCOUNT_A)
        val accountBDevice = store.deviceId(ACCOUNT_B)

        assertEquals(accountADevice, store.deviceId(ACCOUNT_A))
        assertEquals(accountBDevice, store.deviceId(ACCOUNT_B))
        assertNotEquals(accountADevice, accountBDevice)

        store.clearAccountState(ACCOUNT_A)

        assertNotEquals(accountADevice, store.deviceId(ACCOUNT_A))
        assertEquals(accountBDevice, store.deviceId(ACCOUNT_B))
    }

    private companion object {
        const val ACCOUNT_A = "018f0870-7b8a-7abc-8abc-3123456789ab"
        const val ACCOUNT_B = "018f0870-7b8a-7abc-8abc-7123456789ab"
        const val TASK_A = "018f0870-7b8a-7abc-8abc-5123456789ab"
        const val TASK_B = "018f0870-7b8a-7abc-8abc-6123456789ab"
    }
}
