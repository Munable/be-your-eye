package app.beyoureyes.monitor

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.beyoureyes.core.data.MonitorDatabaseFactory
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.core.data.cloud.CloudAccountState
import app.beyoureyes.core.data.cloud.CloudClientBundle
import app.beyoureyes.core.data.cloud.CloudConfiguration
import app.beyoureyes.core.data.cloud.CloudDeviceWrite
import app.beyoureyes.core.data.cloud.CloudEventWrite
import app.beyoureyes.core.data.cloud.CloudTaskWrite
import app.beyoureyes.core.data.cloud.SupabaseCloudClientFactory
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit local acceptance for the hosted physical source -> Supabase -> Edge Function -> FCM
 * -> API 36 receiver path. Both rows use device identities created by the product account graph;
 * the test sends one Event and disables cursor sync before delivery so only FCM may cache it.
 */
@RunWith(AndroidJUnit4::class)
class HostedPushAcceptanceInstrumentedTest {
    @Test
    fun physicalSourceEventArrivesOnceOnApi36ReceiverThroughFcm() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Run explicitly with -e runHostedPushAcceptance true",
            arguments.getString("runHostedPushAcceptance") == "true",
        )
        val email = requireNotNull(arguments.getString("pushEmail")).trim()
        val password = requireNotNull(arguments.getString("pushPassword"))
        val completionMarker = requireNotNull(arguments.getString("pushCompletionMarker"))
        require(completionMarker.matches(Regex("push-complete-[a-z0-9-]{8,80}")))
        when (requireNotNull(arguments.getString("pushRole"))) {
            "source" -> runSource(email, password, completionMarker)
            "receiver" -> runReceiver(email, password, completionMarker)
            else -> error("pushRole must be source or receiver")
        }
    }

    private suspend fun runSource(
        email: String,
        password: String,
        completionMarker: String,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controller = CloudBootstrap.accountController(context)
        if (controller.state.value is CloudAccountState.SignedIn) controller.signOut()
        var activity: MainActivity? = null
        var deleted = false
        try {
            val signedIn = controller.signIn(email, password)
            assertTrue(signedIn is CloudAccountState.SignedIn)
            CloudBootstrap.setNotificationsEnabled(context, false)
            CloudBootstrap.coordinator(context).sync()
            val sourceDeviceId = controller.currentDeviceId()
            assertTrue(controller.peerDevices().any { it.deviceId == sourceDeviceId })

            val foregroundActivity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            activity = foregroundActivity
            instrumentation.runOnMainSync {
                foregroundActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            File(context.cacheDir, SOURCE_READY_FILE).writeText("ready")

            withTimeout(SOURCE_WAIT_MILLIS) {
                while (
                    controller.peerDevices().none {
                        it.deviceId != sourceDeviceId && it.displayName == readyMarker(completionMarker)
                    }
                ) {
                    delay(250)
                }
            }

            val client = acceptanceClient(context, email, password)
            val base = System.currentTimeMillis()
            val taskId = UuidV7.generate(base)
            val eventId = UuidV7.generate(base + 1)
            client.dataPlane.upsertTasks(
                listOf(
                    CloudTaskWrite(
                        taskId = taskId,
                        revision = 1,
                        catalogVersion = CURRENT_CATALOG_VERSION,
                        capabilityId = "visual_target",
                        title = "hosted-push-acceptance",
                        monitoringDeviceId = sourceDeviceId,
                        config = taskConfig(),
                    ),
                ),
            )
            val uploaded = client.dataPlane.uploadEvents(
                listOf(
                    CloudEventWrite(
                        eventId = eventId,
                        taskId = taskId,
                        taskRevision = 1,
                        episodeId = UuidV7.generate(base + 2),
                        sourceSequence = 1,
                        occurredAt = Instant.ofEpochMilli(base + 1_000).toString(),
                        payload = buildJsonObject {
                            put("type", "object_episode")
                            put("target_id", TARGET_ID)
                            put("condition", "appeared")
                            put("duration_ms", 1_000)
                            put("count", 1)
                        },
                    ),
                ),
            )
            assertEquals(listOf(eventId), uploaded.acceptedIds)
            assertTrue(uploaded.duplicateIds.isEmpty())

            withTimeout(SOURCE_WAIT_MILLIS) {
                while (controller.peerDevices().none { it.displayName == completionMarker }) {
                    delay(250)
                }
            }
            controller.deleteAccount()
            deleted = true
            assertTrue(controller.state.value is CloudAccountState.SignedOut)
        } finally {
            File(context.cacheDir, SOURCE_READY_FILE).delete()
            instrumentation.runOnMainSync { activity?.finish() }
            if (!deleted && controller.state.value is CloudAccountState.SignedIn) {
                runCatching { controller.deleteAccount() }
            }
            if (controller.state.value is CloudAccountState.SignedIn) {
                runCatching { controller.signOut() }
            }
        }
    }

    private suspend fun runReceiver(
        email: String,
        password: String,
        completionMarker: String,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
        )

        val firebase = OptionalFirebaseConfiguration(
            apiKey = BuildConfig.FIREBASE_API_KEY,
            applicationId = BuildConfig.FIREBASE_APPLICATION_ID,
            projectId = BuildConfig.FIREBASE_PROJECT_ID,
            gcmSenderId = BuildConfig.FIREBASE_GCM_SENDER_ID,
        )
        assertTrue(firebase.enabled)
        assertTrue(firebase.initialize(context))
        assertEquals(BuildConfig.FIREBASE_APPLICATION_ID, FirebaseApp.getInstance().options.applicationId)

        val controller = CloudBootstrap.accountController(context)
        if (controller.state.value is CloudAccountState.SignedIn) controller.signOut()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        var activity: MainActivity? = null
        try {
            withTimeout(RECEIVER_SIGN_IN_MILLIS) {
                while (true) {
                    val state = runCatching { controller.signIn(email, password) }.getOrNull()
                    if (state is CloudAccountState.SignedIn) break
                    delay(500)
                }
            }
            val foregroundActivity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            activity = foregroundActivity
            instrumentation.runOnMainSync {
                foregroundActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            CloudBootstrap.coordinator(context).unregisterCurrentPushToken(clearLocalToken = true)
            CloudBootstrap.setNotificationsEnabled(context, true)
            FirebaseMessaging.getInstance().apply {
                setAutoInitEnabled(true)
                Tasks.await(register(), FCM_REGISTRATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            val installationId = Tasks.await(
                FirebaseInstallations.getInstance().id,
                FCM_REGISTRATION_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
            val registrationStartedAt = SystemClock.elapsedRealtime()
            while (
                CloudBootstrap.registeredPushInstallationId(context) != installationId &&
                SystemClock.elapsedRealtime() - registrationStartedAt < FCM_REGISTRATION_TIMEOUT_MS
            ) {
                delay(250)
            }
            assertEquals(installationId, CloudBootstrap.registeredPushInstallationId(context))
            CloudBootstrap.coordinator(context).sync()

            val receiverDeviceId = controller.currentDeviceId()
            val client = acceptanceClient(context, email, password)
            client.dataPlane.upsertDevice(
                receiverDevice(receiverDeviceId, readyMarker(completionMarker)),
            )
            assertTrue(
                client.dataPlane.peerDevices().single { it.deviceId == receiverDeviceId }
                    .notificationsEnabled,
            )

            // From here onward only FCM may insert the source Event into local Room.
            CloudWorkScheduler.cancelAccountSync(context)
            val dao = MonitorDatabaseFactory.open(context).monitoringDao()
            val deliveryStartedAt = SystemClock.elapsedRealtime()
            val delivered = withTimeout(FCM_DELIVERY_TIMEOUT_MS) {
                while (true) {
                    val found = dao.latestEvents(10).singleOrNull { event ->
                        event.payloadJson.contains(TARGET_ID) &&
                            event.notificationDeliveredAtEpochMillis != null
                    }
                    if (found != null) return@withTimeout found
                    delay(250)
                }
                error("unreachable")
            }
            val notification = context.getSystemService(NotificationManager::class.java)
                .activeNotifications.singleOrNull { it.tag == "cloud-event-${delivered.eventId}" }
            assertNotNull(notification)
            delay(NO_DUPLICATE_WINDOW_MS)
            assertEquals(
                1,
                dao.latestEvents(10).count { it.payloadJson.contains(TARGET_ID) },
            )
            assertEquals(
                1,
                context.getSystemService(NotificationManager::class.java)
                    .activeNotifications.count { it.tag == "cloud-event-${delivered.eventId}" },
            )
            assertTrue(
                SystemClock.elapsedRealtime() - deliveryStartedAt < DELIVERY_SLO_MS,
            )

            client.dataPlane.upsertDevice(receiverDevice(receiverDeviceId, completionMarker))
            delay(1_500)
            controller.signOut()
            assertTrue(controller.state.value is CloudAccountState.SignedOut)
        } finally {
            if (controller.state.value is CloudAccountState.SignedIn) {
                runCatching { controller.signOut() }
            }
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }

    private suspend fun acceptanceClient(
        context: Context,
        email: String,
        password: String,
    ): CloudClientBundle {
        val configuration = CloudConfiguration.from(
            url = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        )
        assertTrue(configuration is CloudConfiguration.Enabled)
        return SupabaseCloudClientFactory.create(context, configuration).also { client ->
            client.account.awaitInitialization()
            if (client.account.state.value !is CloudAccountState.SignedIn) {
                assertTrue(client.account.signIn(email, password) is CloudAccountState.SignedIn)
            }
        }
    }

    private fun receiverDevice(deviceId: String, displayName: String) = CloudDeviceWrite(
        deviceId = deviceId,
        displayName = displayName,
        androidApi = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a",
        memoryMb = 8_192,
        gmsAvailable = true,
        notificationsEnabled = true,
        appVersion = BuildConfig.VERSION_NAME,
    )

    private fun readyMarker(completionMarker: String): String =
        "push-ready-${completionMarker.removePrefix("push-complete-")}"

    private fun taskConfig() = buildJsonObject {
        put("target_definition", buildJsonObject { put("mode", "reference_images") })
        put("roi", buildJsonObject {
            put("left", 0)
            put("top", 0)
            put("right", 1)
            put("bottom", 1)
        })
        put("sampling_policy", buildJsonObject { put("mode", "package_default") })
        put("route_binding", buildJsonObject {
            put("model_profile_key", "reference_object_matching")
            put("recipe_id", "neural_reference_target_v1")
            put("intent_key", "visual.reference.user_target")
        })
        put("package_binding", buildJsonObject {
            put("package_id", REFERENCE_PACKAGE_ID)
            put("package_version", REFERENCE_PACKAGE_VERSION)
            put("manifest_sha256", REFERENCE_MANIFEST_SHA256)
            put("artifact_identity_sha256", "a".repeat(64))
        })
        put("rule", buildJsonObject {
            put("type", "presence_duration")
            put("condition", "appears")
            put("duration_ms", 1_000)
            put("min_positive_count", 2)
            put("max_positive_gap_ms", 1_000)
            put("rearm_absence_ms", 1_000)
        })
    }

    private companion object {
        const val SOURCE_READY_FILE = "hosted-push-source-ready"
        const val TARGET_ID = "hosted-push-acceptance"
        const val CURRENT_CATALOG_VERSION = "2026.08.31.1"
        const val REFERENCE_PACKAGE_ID = "similarity_mediapipe_mobilenet_v3_large_v1"
        const val REFERENCE_PACKAGE_VERSION = "0.1.0-internal.15"
        const val REFERENCE_MANIFEST_SHA256 =
            "2319c397c685d3d9a9d9d57c6d4703a49f516d3e9f2e258548d23a5c87d91306"
        const val SOURCE_WAIT_MILLIS = 300_000L
        const val RECEIVER_SIGN_IN_MILLIS = 60_000L
        const val FCM_REGISTRATION_TIMEOUT_MS = 60_000L
        const val FCM_DELIVERY_TIMEOUT_MS = 120_000L
        const val NO_DUPLICATE_WINDOW_MS = 5_000L
        const val DELIVERY_SLO_MS = 5L * 60L * 1_000L
    }
}
