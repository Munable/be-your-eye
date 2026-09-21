package app.beyoureyes.monitor

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.room.InvalidationTracker
import androidx.datastore.preferences.preferencesDataStore
import app.beyoureyes.core.data.MonitorDatabaseFactory
import app.beyoureyes.core.data.RemoteEventSnapshotCache
import app.beyoureyes.core.data.monitorPreferences
import app.beyoureyes.core.data.cloud.CloudAccountController
import app.beyoureyes.core.data.cloud.CloudAccountRepository
import app.beyoureyes.core.data.cloud.CloudAccountState
import app.beyoureyes.core.data.cloud.CloudConfiguration
import app.beyoureyes.core.data.cloud.CloudDataPlane
import app.beyoureyes.core.data.cloud.CloudDeviceDescriptorProvider
import app.beyoureyes.core.data.cloud.CloudDeviceWrite
import app.beyoureyes.core.data.cloud.CloudEventNotificationSink
import app.beyoureyes.core.data.cloud.CloudLocalStateStore
import app.beyoureyes.core.data.cloud.CloudNotificationPolicy
import app.beyoureyes.core.data.cloud.CloudSyncCoordinator
import app.beyoureyes.core.data.cloud.DataStoreCloudLocalStateStore
import app.beyoureyes.core.data.cloud.RoomCloudBridge
import app.beyoureyes.core.data.cloud.RemoteSnapshotTransferController
import app.beyoureyes.core.data.cloud.SupabaseCloudClientFactory
import app.beyoureyes.core.data.cloud.displayText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.cloudLocalStateDataStore by preferencesDataStore(
    name = "optional_cloud_state_v1",
)

/** Process-wide optional cloud graph. Constructing a local-only graph performs no network I/O. */
internal object CloudBootstrap {
    private data class Runtime(
        val configuration: CloudConfiguration,
        val firebaseConfiguration: OptionalFirebaseConfiguration,
        val accountRepository: CloudAccountRepository,
        val dataPlane: CloudDataPlane,
        val stateStore: CloudLocalStateStore,
        val coordinator: CloudSyncCoordinator,
        val accountController: CloudAccountController,
        val snapshotTransfers: RemoteSnapshotTransferController,
        val outboxObserver: InvalidationTracker.Observer?,
    )

    @Volatile
    private var runtime: Runtime? = null

    @Volatile
    private var activationObserverStarted = false
    private val productAccessGranted = MutableStateFlow(false)

    private val activationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun initialize(context: Context) {
        val value = get(context)
        if (value.configuration !is CloudConfiguration.Enabled) return
        if (!startActivationObserverOnce()) return
        val appContext = context.applicationContext
        activationScope.launch {
            value.accountRepository.awaitInitialization()
            combine(value.accountRepository.state, productAccessGranted) { account, granted ->
                account to granted
            }.collectLatest { (state, granted) ->
                applyNetworkActivation(appContext, value, state, granted)
            }
        }
    }

    fun updateProductAccess(granted: Boolean) {
        productAccessGranted.value = granted
    }

    fun coordinator(context: Context): CloudSyncCoordinator = get(context).coordinator

    suspend fun claimLocalTask(context: Context, accountId: String, taskId: String) {
        if (!productAccessGranted.value) return
        get(context.applicationContext).coordinator.claimLocalTask(accountId, taskId)
    }

    suspend fun registeredPushInstallationId(context: Context): String? =
        get(context).stateStore.pushToken()

    fun accountController(context: Context): CloudAccountController = get(context).accountController

    fun snapshotTransfers(context: Context): RemoteSnapshotTransferController =
        get(context).snapshotTransfers


    fun cloudConfigured(context: Context): Boolean =
        get(context).configuration is CloudConfiguration.Enabled

    fun enqueueForegroundSyncIfSignedIn(context: Context) {
        val value = get(context.applicationContext)
        if (shouldEnqueueForegroundSync(
                value.accountRepository.state.value,
                productAccessGranted.value,
            )
        ) {
            CloudWorkScheduler.enqueueSync(context.applicationContext)
        }
    }

    fun notificationsEnabled(context: Context): Flow<Boolean> =
        monitorPreferences(context.applicationContext).eventNotificationsEnabled

    suspend fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        monitorPreferences(appContext).setEventNotificationsEnabled(enabled)
        val value = get(appContext)
        if (value.configuration is CloudConfiguration.Enabled) {
            applyNetworkActivation(
                appContext,
                value,
                value.accountRepository.state.value,
                productAccessGranted.value,
            )
        }
    }

    private fun get(context: Context): Runtime = runtime ?: synchronized(this) {
        runtime ?: create(context.applicationContext).also { runtime = it }
    }

    private fun create(context: Context): Runtime {
        val configuration = CloudConfiguration.from(
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            BuildConfig.AUTH_REDIRECT_URL,
        )
        val firebase = OptionalFirebaseConfiguration(
            apiKey = BuildConfig.FIREBASE_API_KEY,
            applicationId = BuildConfig.FIREBASE_APPLICATION_ID,
            projectId = BuildConfig.FIREBASE_PROJECT_ID,
            gcmSenderId = BuildConfig.FIREBASE_GCM_SENDER_ID,
        )
        val bundle = SupabaseCloudClientFactory.create(context, configuration)
        val stateStore = DataStoreCloudLocalStateStore(context.cloudLocalStateDataStore)
        val notificationPreferences = monitorPreferences(context)
        val database = MonitorDatabaseFactory.open(context)
        val coordinator = CloudSyncCoordinator(
            dataPlane = bundle.dataPlane,
            local = RoomCloudBridge(
                database = database,
                remoteSnapshotCache = RemoteEventSnapshotCache.openAppPrivate(context.filesDir),
            ),
            stateStore = stateStore,
            deviceProvider = CloudDeviceDescriptorProvider { deviceId, notificationsEnabled ->
                buildDevice(context, deviceId, notificationsEnabled, firebase.enabled)
            },
            notificationPolicy = CloudNotificationPolicy {
                notificationPreferences.eventNotificationsEnabled.first()
            },
            notificationSink = CloudEventNotificationSink { event ->
                publishCloudEventNotification(
                    context,
                    event.eventId,
                    event.displayText(context.resources.configuration.locales[0].toLanguageTag()),
                )
            },
        )
        val snapshotTransfers = RemoteSnapshotTransferController(
            accountState = bundle.account.state,
            accessGranted = productAccessGranted,
            dataPlane = bundle.dataPlane,
            relay = bundle.snapshotRelay,
            stateStore = stateStore,
            database = database,
            filesDir = context.filesDir,
        )
        val outboxObserver = if (configuration is CloudConfiguration.Enabled) {
            object : InvalidationTracker.Observer("product_tasks", "event_outbox") {
                override fun onInvalidated(tables: Set<String>) {
                    if (bundle.account.state.value is CloudAccountState.SignedIn &&
                        productAccessGranted.value
                    ) {
                        CloudWorkScheduler.enqueueSync(context)
                    }
                }
            }.also(database.invalidationTracker::addObserver)
        } else {
            null
        }
        return Runtime(
            configuration = configuration,
            firebaseConfiguration = firebase,
            accountRepository = bundle.account,
            dataPlane = bundle.dataPlane,
            stateStore = stateStore,
            coordinator = coordinator,
            accountController = CloudAccountController(
                bundle.account,
                bundle.dataPlane,
                coordinator,
                snapshotTransfers,
            ),
            snapshotTransfers = snapshotTransfers,
            outboxObserver = outboxObserver,
        )
    }

    private fun startActivationObserverOnce(): Boolean = synchronized(this) {
        if (activationObserverStarted) {
            false
        } else {
            activationObserverStarted = true
            true
        }
    }

    private suspend fun applyNetworkActivation(
        context: Context,
        value: Runtime,
        accountState: CloudAccountState,
        accessGranted: Boolean,
    ) {
        val notificationsEnabled = monitorPreferences(context)
            .eventNotificationsEnabled
            .first()
        val decision = decideCloudNetworkActivation(
            accountState = accountState,
            productAccessGranted = accessGranted,
            notificationsEnabled = notificationsEnabled,
            firebaseConfigured = value.firebaseConfiguration.enabled,
            gmsAvailable = hasUsableGooglePlayServices(context),
        )
        if (decision.scheduleAccountSync) {
            CloudWorkScheduler.ensurePeriodicSync(context)
            CloudWorkScheduler.enqueueSync(context)
        } else {
            CloudWorkScheduler.cancelAccountSync(context)
        }

        withContext(Dispatchers.IO) {
            value.firebaseConfiguration.setPushEnabled(context, decision.registerForPush)
        }
    }

    private fun buildDevice(
        context: Context,
        deviceId: String,
        notificationsEnabled: Boolean,
        firebaseEnabled: Boolean,
    ): CloudDeviceWrite {
        val memoryInfo = ActivityManager.MemoryInfo().also { info ->
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        }
        val displayName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100)
        return CloudDeviceWrite(
            deviceId = deviceId,
            displayName = displayName.ifBlank { "Android device" },
            androidApi = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_64_BIT_ABIS.firstOrNull()
                ?: Build.SUPPORTED_ABIS.firstOrNull()
                ?: "unknown",
            memoryMb = reportedPhysicalMemoryTierMb(memoryInfo.totalMem),
            gmsAvailable = firebaseEnabled && hasUsableGooglePlayServices(context),
            notificationsEnabled = notificationsEnabled,
            appVersion = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
        )
    }

    private fun publishCloudEventNotification(
        context: Context,
        eventId: String,
        text: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        NotificationChannels.ensureCreated(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NotificationChannels.REMOTE_EVENT_CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE
        ) return false
        manager.notify(
            "cloud-event-$eventId",
            NotificationChannels.EVENT_NOTIFICATION_ID,
            NotificationChannels.remoteConfirmedEvent(context, eventId, text),
        )
        return true
    }
}

internal fun isUsableGooglePlayServicesStatus(status: Int): Boolean =
    status == 0

/**
 * Android exposes usable physical bytes after a small platform reservation, so an 8 GiB device
 * commonly reports roughly 7.7 GiB. Device and Manifest contracts use hardware tiers; round only
 * that fractional reservation up to the next whole GiB. A genuine 4/6 GiB device remains below
 * the 8 GiB product boundary instead of being coerced to a supported tier.
 */
internal fun reportedPhysicalMemoryTierMb(totalBytes: Long): Int {
    require(totalBytes >= 0)
    val completeGiB = totalBytes / BYTES_PER_GIB
    val tierGiB = completeGiB + if (totalBytes % BYTES_PER_GIB == 0L) 0 else 1
    return if (tierGiB > Int.MAX_VALUE / MIB_PER_GIB) {
        Int.MAX_VALUE
    } else {
        (tierGiB * MIB_PER_GIB).toInt()
    }
}

private const val BYTES_PER_GIB = 1_073_741_824L
private const val MIB_PER_GIB = 1_024L

internal data class CloudNetworkActivationDecision(
    val scheduleAccountSync: Boolean,
    val registerForPush: Boolean,
)

internal fun decideCloudNetworkActivation(
    accountState: CloudAccountState,
    productAccessGranted: Boolean,
    notificationsEnabled: Boolean,
    firebaseConfigured: Boolean,
    gmsAvailable: Boolean,
): CloudNetworkActivationDecision {
    val signedIn = accountState is CloudAccountState.SignedIn
    return CloudNetworkActivationDecision(
        scheduleAccountSync = signedIn && productAccessGranted,
        registerForPush = signedIn && productAccessGranted && notificationsEnabled &&
            firebaseConfigured && gmsAvailable,
    )
}

internal fun shouldEnqueueForegroundSync(
    accountState: CloudAccountState,
    productAccessGranted: Boolean,
): Boolean = accountState is CloudAccountState.SignedIn && productAccessGranted
