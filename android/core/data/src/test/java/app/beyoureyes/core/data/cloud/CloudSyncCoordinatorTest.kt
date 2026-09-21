package app.beyoureyes.core.data.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncCoordinatorTest {
    @Test
    fun localOnlyModeIsANoOpAndDoesNotTouchRoomOrNetwork() = runBlocking {
        val dataPlane = FakeDataPlane(configured = false)
        val local = FakeLocalBridge(errorOnUse = true)
        val coordinator = coordinator(dataPlane, local)

        assertEquals(CloudSyncResult(0, 0, 0), coordinator.sync())
        assertEquals(0, dataPlane.calls)
    }

    @Test
    fun remoteCacheClearWaitsForAnInFlightSync() = runBlocking {
        val registerStarted = CompletableDeferred<Unit>()
        val releaseRegister = CompletableDeferred<Unit>()
        val dataPlane = FakeDataPlane(
            registerStarted = registerStarted,
            releaseRegister = releaseRegister,
        )
        val local = FakeLocalBridge()
        val coordinator = coordinator(
            dataPlane = dataPlane,
            local = local,
            stateStore = FakeStateStore(token = "fcm-token-that-is-long-enough"),
        )

        val sync = async { coordinator.sync() }
        registerStarted.await()
        val clear = async { coordinator.clearRemoteCache() }
        yield()

        assertEquals(0, local.remoteCacheClearCount)
        releaseRegister.complete(Unit)
        sync.await()
        assertEquals(0, clear.await())
        assertEquals(1, local.remoteCacheClearCount)
    }

    @Test
    fun acceptedAndDuplicateOutboxIdsAreBothRemovedIdempotently() = runBlocking {
        val first = event("018f0870-7b8a-7abc-8abc-0123456789ab")
        val duplicate = event("018f0870-7b8a-7abc-8abc-1123456789ab")
        val local = FakeLocalBridge(
            ready = mutableListOf(first, duplicate),
            localTaskIds = setOf(TASK_ID),
        )
        val dataPlane = FakeDataPlane(
            batchResult = CloudEventBatchResult(
                acceptedIds = listOf(first.eventId),
                duplicateIds = listOf(duplicate.eventId),
            ),
        )

        val result = coordinator(dataPlane, local).sync()

        assertEquals(2, result.uploadedEvents)
        assertEquals(setOf(first.eventId, duplicate.eventId), local.markedUploaded)
        assertTrue(local.ready.isEmpty())
    }

    @Test
    fun orphanedOutboxEventIsNotAssignedToTheNextAccountAfterItsTaskIsGone() = runBlocking {
        val orphan = event(EVENT_ID)
        val local = FakeLocalBridge(ready = mutableListOf(orphan))
        val dataPlane = FakeDataPlane(
            batchResult = CloudEventBatchResult(
                acceptedIds = listOf(orphan.eventId),
                duplicateIds = emptyList(),
            ),
        )

        coordinator(dataPlane, local).sync()

        assertTrue(dataPlane.uploadedEvents.isEmpty())
        assertEquals(listOf(orphan), local.ready)
    }

    @Test
    fun sameRemoteEventIsCachedAndNotifiedOnlyOnceAcrossPushRetries() = runBlocking {
        val remote = row("018f0870-7b8a-7abc-8abc-2123456789ab")
        val dataPlane = FakeDataPlane(remoteEvent = remote)
        val local = FakeLocalBridge()
        var notificationCount = 0
        val coordinator = coordinator(dataPlane, local) { notificationCount++; true }
        val envelope = PushEnvelope(remote.eventId, "9")

        assertTrue(coordinator.processPush(envelope))
        assertFalse(coordinator.processPush(envelope))

        assertEquals(1, notificationCount)
        assertEquals(2, dataPlane.receipts.count { it.second == ReceiptType.FETCHED })
        assertEquals(1, dataPlane.receipts.count { it.second == ReceiptType.DISPLAYED })
    }

    @Test
    fun stableDeviceIdentityMapsPushTokenAndDeviceNotificationSwitch() = runBlocking {
        val dataPlane = FakeDataPlane()
        val state = FakeStateStore(token = "fcm-token-that-is-long-enough")
        val coordinator = coordinator(dataPlane, FakeLocalBridge(), stateStore = state)

        coordinator.sync()
        coordinator.sync()

        assertEquals(setOf(DEVICE_ID), dataPlane.devices.map { it.deviceId }.toSet())
        assertTrue(dataPlane.devices.all { it.notificationsEnabled })
        assertEquals(listOf(DEVICE_ID to state.token), dataPlane.tokens)
    }

    @Test
    fun peerRevocationRejectsTheCurrentDeviceAndUsesTheAccountScopedDataPlane() = runBlocking {
        val dataPlane = FakeDataPlane()
        val coordinator = coordinator(dataPlane, FakeLocalBridge())
        val peerId = "018f0870-7b8a-7abc-8abc-8123456789ab"

        assertTrue(coordinator.revokePeerDevice(peerId))
        assertEquals(listOf(peerId), dataPlane.revokedDevices)
        assertTrue(runCatching { coordinator.revokePeerDevice(DEVICE_ID) }.isFailure)
        assertEquals(listOf(peerId), dataPlane.revokedDevices)
    }

    @Test
    fun disabledDeviceSwitchCachesPeerEventWithoutDisplayingIt() = runBlocking {
        val remote = row("018f0870-7b8a-7abc-8abc-7123456789ab")
        val dataPlane = FakeDataPlane(remoteEvent = remote)
        var notificationCount = 0
        val coordinator = coordinator(
            dataPlane = dataPlane,
            local = FakeLocalBridge(),
            notificationsEnabled = false,
            sink = { notificationCount++; true },
        )

        assertTrue(coordinator.processPush(PushEnvelope(remote.eventId, "11")))

        assertEquals(0, notificationCount)
        assertEquals(1, dataPlane.receipts.count { it.second == ReceiptType.FETCHED })
        assertEquals(0, dataPlane.receipts.count { it.second == ReceiptType.DISPLAYED })
    }

    @Test
    fun notificationSinkFalseStaysPendingUntilALaterRetryActuallyDisplays() = runBlocking {
        val remote = row("018f0870-7b8a-7abc-8abc-6123456789ab")
        val dataPlane = FakeDataPlane(remoteEvent = remote)
        val local = FakeLocalBridge()
        var publishCalls = 0
        val coordinator = coordinator(dataPlane, local) {
            publishCalls++
            publishCalls > 1
        }
        val envelope = PushEnvelope(remote.eventId, "10")

        assertTrue(coordinator.processPush(envelope))
        assertTrue(local.isRemoteNotificationPending(ACCOUNT_ID, remote.eventId))
        assertTrue(coordinator.processPush(envelope))

        assertEquals(2, publishCalls)
        assertFalse(local.isRemoteNotificationPending(ACCOUNT_ID, remote.eventId))
        assertEquals(1, dataPlane.receipts.count { it.second == ReceiptType.DISPLAYED })
    }

    @Test
    fun pendingRemoteNotificationRetriesAfterCursorAdvancedWithoutAnotherPush() = runBlocking {
        val remote = row("018f0870-7b8a-7abc-8abc-5123456789ab")
        val dataPlane = FakeDataPlane(remoteEvent = remote)
        val local = FakeLocalBridge()
        var publishCalls = 0
        val coordinator = coordinator(dataPlane, local) {
            publishCalls++
            publishCalls > 1
        }

        assertTrue(coordinator.processPush(PushEnvelope(remote.eventId, "13")))
        assertTrue(local.isRemoteNotificationPending(ACCOUNT_ID, remote.eventId))

        coordinator.sync()

        assertEquals(2, publishCalls)
        assertFalse(local.isRemoteNotificationPending(ACCOUNT_ID, remote.eventId))
        assertEquals(1, dataPlane.receipts.count { it.second == ReceiptType.FETCHED })
        assertEquals(1, dataPlane.receipts.count { it.second == ReceiptType.DISPLAYED })
    }

    @Test
    fun disablingPeerNotificationsExplicitlyHandlesPreviouslyPendingRows() = runBlocking {
        val remote = row("018f0870-7b8a-7abc-8abc-4123456789ab")
        val dataPlane = FakeDataPlane(remoteEvent = remote)
        val local = FakeLocalBridge()
        coordinator(dataPlane, local, sink = { false })
            .processPush(PushEnvelope(remote.eventId, "14"))
        assertTrue(local.isRemoteNotificationPending(ACCOUNT_ID, remote.eventId))
        var publishCalls = 0

        coordinator(
            dataPlane = dataPlane,
            local = local,
            notificationsEnabled = false,
            sink = { publishCalls++; true },
        ).sync()

        assertEquals(0, publishCalls)
        assertFalse(local.isRemoteNotificationPending(ACCOUNT_ID, remote.eventId))
        assertEquals(0, dataPlane.receipts.count { it.second == ReceiptType.DISPLAYED })
    }

    @Test
    fun offlineTokenCleanupDoesNotBlockLocalSignOut() = runBlocking {
        val dataPlane = FakeDataPlane(throwOnUnregister = true)
        val account = FakeAccountRepository()
        val local = FakeLocalBridge()
        val stateStore = FakeStateStore()
        val controller = CloudAccountController(
            accountRepository = account,
            dataPlane = dataPlane,
            syncCoordinator = coordinator(dataPlane, local, stateStore = stateStore),
        )

        controller.signOut()

        assertEquals(CloudAccountState.SignedOut, account.state.value)
        assertEquals(1, local.remoteCacheClearCount)
        assertEquals(ACCOUNT_ID, stateStore.resetAccountId)
        assertEquals(null, stateStore.clearedAccountId)
    }

    @Test
    fun accountActionsMoveRoomOwnershipFenceOffTheCallerThread() = runBlocking {
        val dataPlane = FakeDataPlane(throwOnUnregister = true)
        val account = FakeAccountRepository()
        val local = FakeLocalBridge()
        val callerThread = Thread.currentThread()
        val controller = CloudAccountController(
            accountRepository = account,
            dataPlane = dataPlane,
            syncCoordinator = coordinator(dataPlane, local, stateStore = FakeStateStore()),
        )

        controller.signOut()

        assertNotSame(callerThread, local.localTaskIdsThread)
        assertEquals(CloudAccountState.SignedOut, account.state.value)
    }

    @Test
    fun pushTokenRegistrationAndRemovalAreSerializedAcrossAuthTransitions() = runBlocking {
        val registerStarted = CompletableDeferred<Unit>()
        val releaseRegister = CompletableDeferred<Unit>()
        val dataPlane = FakeDataPlane(
            registerStarted = registerStarted,
            releaseRegister = releaseRegister,
        )
        val coordinator = coordinator(dataPlane, FakeLocalBridge(), stateStore = FakeStateStore())

        val register = launch {
            coordinator.registerLatestPushToken("fcm-token-that-is-long-enough")
        }
        registerStarted.await()
        val unregister = launch { coordinator.unregisterCurrentPushToken() }
        yield()

        assertEquals(0, dataPlane.unregisterCalls)
        releaseRegister.complete(Unit)
        register.join()
        unregister.join()

        assertEquals(1, dataPlane.unregisterCalls)
        assertEquals(
            listOf(DEVICE_ID to "fcm-token-that-is-long-enough"),
            dataPlane.tokens,
        )
    }

    @Test
    fun directAccountSwitchClearsDownloadedCacheAndDefersNewAccountSync() = runBlocking {
        val dataPlane = FakeDataPlane()
        val local = FakeLocalBridge(localTaskIds = setOf(TASK_ID))
        val stateStore = FakeStateStore(token = "fcm-token-that-is-long-enough")
        val account = FakeAccountRepository(signInAccountId = OTHER_ACCOUNT_ID)
        val controller = CloudAccountController(
            accountRepository = account,
            dataPlane = dataPlane,
            syncCoordinator = coordinator(dataPlane, local, stateStore = stateStore),
        )

        val result = controller.signIn("other@example.com", "correct horse battery staple")

        assertEquals(
            CloudAccountState.SignedIn(OTHER_ACCOUNT_ID, "other@example.com"),
            result,
        )
        assertEquals(1, local.remoteCacheClearCount)
        assertEquals(OTHER_ACCOUNT_ID, stateStore.resetAccountId)
        assertEquals(null, stateStore.clearedAccountId)
        assertEquals(ACCOUNT_ID, stateStore.localTaskOwners[TASK_ID])
        assertEquals(1, dataPlane.unregisterCalls)
        assertTrue(dataPlane.devices.isEmpty())
        assertTrue(dataPlane.upsertedTasks.isEmpty())
    }

    @Test
    fun successfulSignInDoesNotFailWhenTheEntitlementGatedDataPlaneRejectsSync() = runBlocking {
        val dataPlane = FakeDataPlane(throwOnUpsertDevice = true)
        val account = FakeAccountRepository(signInAccountId = OTHER_ACCOUNT_ID)
        val local = FakeLocalBridge()
        val stateStore = FakeStateStore()
        val controller = CloudAccountController(
            accountRepository = account,
            dataPlane = dataPlane,
            syncCoordinator = coordinator(dataPlane, local, stateStore = stateStore),
        )

        val result = controller.signIn("other@example.com", "correct horse battery staple")

        assertEquals(
            CloudAccountState.SignedIn(OTHER_ACCOUNT_ID, "other@example.com"),
            result,
        )
        assertEquals(1, local.remoteCacheClearCount)
        assertEquals(OTHER_ACCOUNT_ID, stateStore.resetAccountId)
        assertTrue(dataPlane.devices.isEmpty())
    }

    @Test
    fun failedDirectAccountSwitchRestoresPreviousPushToken() = runBlocking {
        val dataPlane = FakeDataPlane()
        val local = FakeLocalBridge()
        val stateStore = FakeStateStore(token = "fcm-token-that-is-long-enough")
        val account = FakeAccountRepository(throwOnSignIn = true)
        val controller = CloudAccountController(
            accountRepository = account,
            dataPlane = dataPlane,
            syncCoordinator = coordinator(dataPlane, local, stateStore = stateStore),
        )

        val failure = runCatching {
            controller.signIn("other@example.com", "bad password")
        }

        assertTrue(failure.isFailure)
        assertEquals(
            CloudAccountState.SignedIn(ACCOUNT_ID, "test@example.com"),
            account.state.value,
        )
        assertEquals(1, dataPlane.unregisterCalls)
        assertEquals(
            listOf(DEVICE_ID to "fcm-token-that-is-long-enough"),
            dataPlane.tokens,
        )
    }

    @Test
    fun accountTransitionsAreSerializedBeforeTheNextSessionCanUnregisterItsToken() = runBlocking {
        val signInStarted = CompletableDeferred<Unit>()
        val releaseSignIn = CompletableDeferred<Unit>()
        val dataPlane = FakeDataPlane()
        val stateStore = FakeStateStore(token = "fcm-token-that-is-long-enough")
        val account = FakeAccountRepository(
            signInAccountId = OTHER_ACCOUNT_ID,
            signInStarted = signInStarted,
            releaseSignIn = releaseSignIn,
        )
        val controller = CloudAccountController(
            accountRepository = account,
            dataPlane = dataPlane,
            syncCoordinator = coordinator(dataPlane, FakeLocalBridge(), stateStore = stateStore),
        )

        val first = launch {
            controller.signIn("first@example.com", "correct horse battery staple")
        }
        signInStarted.await()
        val sync = async { controller.syncNow() }
        val second = async {
            controller.signIn("second@example.com", "correct horse battery staple")
        }
        yield()

        assertFalse(sync.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(1, dataPlane.unregisterCalls)

        releaseSignIn.complete(Unit)
        first.join()
        sync.await()
        second.await()

        assertEquals(2, dataPlane.unregisterCalls)
        assertEquals(
            CloudAccountState.SignedIn(OTHER_ACCOUNT_ID, "second@example.com"),
            account.state.value,
        )
    }

    @Test
    fun peerReadsAndRevocationsWaitForAnInFlightAccountTransition() = runBlocking {
        val signInStarted = CompletableDeferred<Unit>()
        val releaseSignIn = CompletableDeferred<Unit>()
        val dataPlane = FakeDataPlane()
        val account = FakeAccountRepository(
            signInAccountId = OTHER_ACCOUNT_ID,
            signInStarted = signInStarted,
            releaseSignIn = releaseSignIn,
        )
        val controller = CloudAccountController(
            accountRepository = account,
            dataPlane = dataPlane,
            syncCoordinator = coordinator(dataPlane, FakeLocalBridge()),
        )
        val first = launch {
            controller.signIn("first@example.com", "correct horse battery staple")
        }
        signInStarted.await()
        val peers = async { controller.peerDevices() }
        val revoke = async {
            controller.revokePeerDevice("018f0870-7b8a-7abc-8abc-8123456789ab")
        }
        yield()

        assertFalse(peers.isCompleted)
        assertFalse(revoke.isCompleted)
        assertEquals(0, dataPlane.peerDevicesCalls)
        assertTrue(dataPlane.revokedDevices.isEmpty())

        releaseSignIn.complete(Unit)
        first.join()
        peers.await()
        assertTrue(revoke.await())

        assertEquals(1, dataPlane.peerDevicesCalls)
        assertEquals(listOf("018f0870-7b8a-7abc-8abc-8123456789ab"), dataPlane.revokedDevices)
    }

    @Test
    fun accountOwnedLocalRowsNeverUploadToAnotherAccount() = runBlocking {
        val dataPlane = FakeDataPlane()
        val stateStore = FakeStateStore().apply {
            localTaskOwners[TASK_ID] = OTHER_ACCOUNT_ID
        }
        val local = FakeLocalBridge(
            ready = mutableListOf(event(EVENT_ID)),
            localTaskIds = setOf(TASK_ID),
            taskWrites = listOf(taskWrite(TASK_ID)),
        )

        coordinator(dataPlane, local, stateStore = stateStore).sync()

        assertTrue(dataPlane.upsertedTasks.isEmpty())
        assertTrue(dataPlane.uploadedEvents.isEmpty())
        assertTrue(local.markedUploaded.isEmpty())
    }

    @Test
    fun anUnownedLocalTaskIsClaimedByTheCurrentAccountBeforeSync() = runBlocking {
        val dataPlane = FakeDataPlane(
            batchResult = CloudEventBatchResult(
                acceptedIds = listOf(EVENT_ID),
                duplicateIds = emptyList(),
            ),
        )
        val stateStore = FakeStateStore()
        val local = FakeLocalBridge(
            ready = mutableListOf(event(EVENT_ID)),
            localTaskIds = setOf(TASK_ID),
            taskWrites = listOf(taskWrite(TASK_ID)),
        )

        coordinator(dataPlane, local, stateStore = stateStore).sync()

        assertEquals(ACCOUNT_ID, stateStore.localTaskOwners[TASK_ID])
        assertEquals(listOf(TASK_ID), dataPlane.upsertedTasks.single().map { it.taskId })
        assertEquals(listOf(EVENT_ID), dataPlane.uploadedEvents.single().map { it.eventId })
    }

    @Test
    fun newlyCreatedTaskCanBeClaimedBeforeAnySyncRuns() = runBlocking {
        val stateStore = FakeStateStore()
        val dataPlane = FakeDataPlane()
        coordinator(dataPlane, FakeLocalBridge(), stateStore = stateStore)
            .claimLocalTask(ACCOUNT_ID, TASK_ID)

        assertEquals(ACCOUNT_ID, stateStore.localTaskOwners[TASK_ID])
    }

    @Test
    fun staleTaskClaimIsIgnoredAfterTheSupabaseSessionMovesToAnotherAccount() = runBlocking {
        val stateStore = FakeStateStore()
        val dataPlane = FakeDataPlane(currentAccountId = "account-b")

        coordinator(dataPlane, FakeLocalBridge(), stateStore = stateStore)
            .claimLocalTask(ACCOUNT_ID, TASK_ID)

        assertFalse(stateStore.localTaskOwners.containsKey(TASK_ID))
    }

    @Test
    fun syncPrunesOwnerForTaskDeletedFromThisDevice() = runBlocking {
        val stateStore = FakeStateStore().apply { localTaskOwners[TASK_ID] = ACCOUNT_ID }
        val dataPlane = FakeDataPlane()

        coordinator(dataPlane, FakeLocalBridge(), stateStore = stateStore).sync()

        assertFalse(stateStore.localTaskOwners.containsKey(TASK_ID))
    }

    @Test
    fun deletingALocallySyncedTaskDeletesTheCloudCopy() = runBlocking {
        val dataPlane = FakeDataPlane()
        val state = FakeStateStore().apply { syncedTasks += TASK_ID }

        coordinator(dataPlane, FakeLocalBridge(), stateStore = state).sync()

        assertEquals(listOf(TASK_ID), dataPlane.deletedTasks)
        assertTrue(state.syncedTasks.isEmpty())
    }

    @Test
    fun offlineDeletionAfterSignOutReconcilesWhenTheSameAccountReturns() = runBlocking {
        val dataPlane = FakeDataPlane()
        val stateStore = FakeStateStore().apply { syncedTasks += TASK_ID }
        val account = FakeAccountRepository()
        CloudAccountController(
            accountRepository = account,
            dataPlane = dataPlane,
            syncCoordinator = coordinator(
                dataPlane,
                FakeLocalBridge(localTaskIds = setOf(TASK_ID)),
                stateStore,
            ),
        ).signOut()

        assertEquals(setOf(TASK_ID), stateStore.syncedTasks)
        val returningCoordinator = coordinator(dataPlane, FakeLocalBridge(), stateStore)
        CloudAccountController(
            accountRepository = account,
            dataPlane = dataPlane,
            syncCoordinator = returningCoordinator,
        ).signIn("test@example.com", "correct horse battery staple")
        returningCoordinator.sync()

        assertEquals(listOf(TASK_ID), dataPlane.deletedTasks)
        assertTrue(stateStore.syncedTasks.isEmpty())
    }

    @Test
    fun peerTaskTombstoneClearsOnlyTheCachedRemoteHistory() = runBlocking {
        val dataPlane = FakeDataPlane(
            changes = listOf(
                CloudSyncChange(
                    sequence = 1,
                    resourceType = "task",
                    operation = "delete",
                    resourceId = TASK_ID,
                ),
            ),
        )
        val local = FakeLocalBridge()

        coordinator(dataPlane, local).sync()

        assertEquals(listOf(ACCOUNT_ID to TASK_ID), local.removedRemoteTasks)
    }

    @Test
    fun peerEventTombstoneClearsOnlyTheCachedRemoteEvent() = runBlocking {
        val dataPlane = FakeDataPlane(
            changes = listOf(
                CloudSyncChange(
                    sequence = 1,
                    resourceType = "event",
                    operation = "delete",
                    resourceId = EVENT_ID,
                ),
            ),
        )
        val local = FakeLocalBridge()

        coordinator(dataPlane, local).sync()

        assertEquals(listOf(ACCOUNT_ID to EVENT_ID), local.removedRemoteEvents)
    }

    @Test
    fun taskUpsertCachesOnlyItsReadOnlyPeerSummary() = runBlocking {
        val dataPlane = FakeDataPlane(
            changes = listOf(
                CloudSyncChange(
                    sequence = 1,
                    resourceType = "task",
                    operation = "upsert",
                    resourceId = TASK_ID,
                    value = buildJsonObject {
                        put("schema_version", "4.0")
                        put("task_id", TASK_ID)
                        put("revision", 2)
                        put("capability_id", "visual_target")
                        put("title", "门口的人")
                        put("target_definition", buildJsonObject { put("mode", "object_detection") })
                        put("monitoring_device_id", DEVICE_ID)
                        put("catalog_version", "catalog-v3")
                    },
                ),
            ),
        )
        val local = FakeLocalBridge()

        coordinator(dataPlane, local).sync()

        assertEquals(
            listOf(
                CloudTaskSummary(
                    "4.0",
                    TASK_ID,
                    2,
                    "visual_target",
                    "门口的人",
                    buildJsonObject { put("mode", "object_detection") },
                    DEVICE_ID,
                ),
            ),
            local.remoteTaskSummaries,
        )
    }

    @Test
    fun receiptFailureAfterCacheStillDeliversTheNotificationOnRetry() = runBlocking {
        val remote = row("018f0870-7b8a-7abc-8abc-8123456789ab")
        val dataPlane = FakeDataPlane(remoteEvent = remote, failFirstFetchedReceipt = true)
        val local = FakeLocalBridge()
        var notificationCount = 0
        val coordinator = coordinator(dataPlane, local) { notificationCount++; true }
        val envelope = PushEnvelope(remote.eventId, "12")

        runCatching { coordinator.processPush(envelope) }
        assertEquals(0, notificationCount)
        assertTrue(coordinator.processPush(envelope))

        assertEquals(1, notificationCount)
        assertEquals(1, dataPlane.receipts.count { it.second == ReceiptType.FETCHED })
        assertEquals(1, dataPlane.receipts.count { it.second == ReceiptType.DISPLAYED })
    }

    @Test
    fun displayedReceiptFailureNeverDisplaysTheOsNotificationTwice() = runBlocking {
        val remote = row("018f0870-7b8a-7abc-8abc-3123456789ac")
        val dataPlane = FakeDataPlane(remoteEvent = remote, failFirstDisplayedReceipt = true)
        val local = FakeLocalBridge()
        var notificationCount = 0
        val coordinator = coordinator(dataPlane, local) { notificationCount++; true }
        val envelope = PushEnvelope(remote.eventId, "15")

        runCatching { coordinator.processPush(envelope) }
        assertFalse(local.isRemoteNotificationPending(ACCOUNT_ID, remote.eventId))
        assertFalse(coordinator.processPush(envelope))

        assertEquals(1, notificationCount)
        assertEquals(0, dataPlane.receipts.count { it.second == ReceiptType.DISPLAYED })
    }

    @Test
    fun accountDeletionClearsCloudSessionAndMappingWithoutTouchingLocalState() = runBlocking {
        val dataPlane = FakeDataPlane()
        val stateStore = FakeStateStore(token = "fcm-token-that-is-long-enough")
        val local = FakeLocalBridge()
        val account = FakeAccountRepository(deletionAvailable = true)
        val controller = CloudAccountController(
            accountRepository = account,
            dataPlane = dataPlane,
            syncCoordinator = coordinator(dataPlane, local, stateStore = stateStore),
        )

        controller.deleteAccount()

        assertTrue(account.deleted)
        assertEquals(CloudAccountState.SignedOut, account.state.value)
        assertEquals("fcm-token-that-is-long-enough", stateStore.token)
        assertEquals(ACCOUNT_ID, stateStore.clearedAccountId)
        assertEquals(1, local.remoteCacheClearCount)
    }

    @Test
    fun accountDeletionReleasesLocalOwnerEvenIfAccountStateCleanupFails() = runBlocking {
        val dataPlane = FakeDataPlane()
        val stateStore = FakeStateStore().apply {
            localTaskOwners[TASK_ID] = ACCOUNT_ID
            throwOnClearAccountState = true
        }
        val account = FakeAccountRepository(deletionAvailable = true)
        val controller = CloudAccountController(
            accountRepository = account,
            dataPlane = dataPlane,
            syncCoordinator = coordinator(dataPlane, FakeLocalBridge(), stateStore = stateStore),
        )

        val failure = runCatching { controller.deleteAccount() }

        assertTrue(failure.isFailure)
        assertTrue(account.deleted)
        assertFalse(stateStore.localTaskOwners.containsKey(TASK_ID))
    }

    @Test
    fun failedAccountDeletionKeepsSessionTokenAndAccountMappings() = runBlocking {
        val dataPlane = FakeDataPlane()
        val stateStore = FakeStateStore(token = "fcm-token-that-is-long-enough")
        val account = FakeAccountRepository(deletionAvailable = true, throwOnDelete = true)
        val local = FakeLocalBridge()
        val controller = CloudAccountController(
            accountRepository = account,
            dataPlane = dataPlane,
            syncCoordinator = coordinator(dataPlane, local, stateStore = stateStore),
        )

        val failure = runCatching { controller.deleteAccount() }

        assertTrue(failure.isFailure)
        assertFalse(account.deleted)
        assertEquals(
            CloudAccountState.SignedIn(ACCOUNT_ID, "test@example.com"),
            account.state.value,
        )
        assertEquals("fcm-token-that-is-long-enough", stateStore.token)
        assertEquals(null, stateStore.clearedAccountId)
        assertEquals(0, local.remoteCacheClearCount)
    }

    private fun coordinator(
        dataPlane: FakeDataPlane,
        local: FakeLocalBridge,
        stateStore: FakeStateStore = FakeStateStore(),
        notificationsEnabled: Boolean = true,
        sink: suspend (CloudEventRow) -> Boolean = { true },
    ) = CloudSyncCoordinator(
        dataPlane = dataPlane,
        local = local,
        stateStore = stateStore,
        deviceProvider = CloudDeviceDescriptorProvider { deviceId, notifications ->
            CloudDeviceWrite(
                deviceId = deviceId,
                displayName = "Test phone",
                androidApi = 36,
                abi = "arm64-v8a",
                memoryMb = 8192,
                gmsAvailable = true,
                notificationsEnabled = notifications,
                appVersion = "0.1.0",
            )
        },
        notificationPolicy = CloudNotificationPolicy { notificationsEnabled },
        notificationSink = CloudEventNotificationSink(sink),
        nowEpochMillis = { 1_700_000_000_000L },
    )

    private class FakeStateStore(
        var token: String? = null,
    ) : CloudLocalStateStore {
        private var cursor = 0L
        private val fingerprints = mutableMapOf<String, String>()
        val syncedTasks = mutableSetOf<String>()
        val localTaskOwners = mutableMapOf<String, String>()
        var clearedAccountId: String? = null
        var resetAccountId: String? = null
        var throwOnClearAccountState = false
        override suspend fun deviceId(accountId: String) =
            if (accountId == OTHER_ACCOUNT_ID) OTHER_DEVICE_ID else DEVICE_ID
        override suspend fun cursor(accountId: String) = cursor
        override suspend fun setCursor(accountId: String, cursor: Long) { this.cursor = cursor }
        override suspend fun pushToken() = token
        override suspend fun setPushToken(token: String?) { this.token = token }
        override suspend fun syncedTaskIds(accountId: String) = syncedTasks.toSet()
        override suspend fun setSyncedTaskIds(accountId: String, taskIds: Set<String>) {
            syncedTasks.apply { clear(); addAll(taskIds) }
        }
        override suspend fun claimUnownedLocalTaskIds(
            accountId: String,
            taskIds: Set<String>,
        ): Set<String> = taskIds.filterTo(linkedSetOf()) { taskId ->
            val owner = localTaskOwners[taskId]
            if (owner == null) {
                localTaskOwners[taskId] = accountId
                true
            } else {
                owner == accountId
            }
        }
        override suspend fun localTaskIdsOwnedBy(accountId: String): Set<String> =
            localTaskOwners.filterValues { it == accountId }.keys
        override suspend fun releaseLocalTaskOwnership(accountId: String) {
            localTaskOwners.entries.removeIf { it.value == accountId }
        }
        override suspend fun releaseLocalTaskOwnership(accountId: String, taskIds: Set<String>) {
            taskIds.forEach { taskId ->
                if (localTaskOwners[taskId] == accountId) localTaskOwners.remove(taskId)
            }
        }
        override suspend fun resetAccountSessionState(accountId: String) {
            resetAccountId = accountId
            cursor = 0
            fingerprints.keys.removeAll { it.startsWith("$accountId:") }
        }
        override suspend fun clearAccountState(accountId: String) {
            if (throwOnClearAccountState) throw IllegalStateException("state cleanup failure")
            clearedAccountId = accountId
            resetAccountSessionState(accountId)
            syncedTasks.clear()
        }
        override suspend fun syncFingerprint(accountId: String, resourceKey: String) =
            fingerprints["$accountId:$resourceKey"]
        override suspend fun setSyncFingerprint(
            accountId: String,
            resourceKey: String,
            value: String?,
        ) {
            val key = "$accountId:$resourceKey"
            if (value == null) fingerprints.remove(key) else fingerprints[key] = value
        }
    }

    private class FakeLocalBridge(
        val ready: MutableList<CloudEventWrite> = mutableListOf(),
        private val localTaskIds: Set<String> = emptySet(),
        private val taskWrites: List<CloudTaskWrite> = emptyList(),
        private val errorOnUse: Boolean = false,
    ) : CloudLocalBridge {
        val markedUploaded = mutableSetOf<String>()
        val removedRemoteEvents = mutableListOf<Pair<String, String>>()
        val removedRemoteTasks = mutableListOf<Pair<String, String>>()
        val remoteTaskSummaries = mutableListOf<CloudTaskSummary>()
        private val cached = mutableSetOf<String>()
        private val remoteRows = mutableMapOf<String, CloudEventRow>()
        private val pendingRemoteNotifications = mutableSetOf<String>()
        var remoteCacheClearCount = 0
        var localTaskIdsThread: Thread? = null
        override suspend fun localTaskIds(): Set<String> {
            localTaskIdsThread = Thread.currentThread()
            return localTaskIds
        }
        override suspend fun taskWrites(deviceId: String): List<CloudTaskWrite> {
            check(!errorOnUse)
            return taskWrites
        }
        override suspend fun readyEvents(nowEpochMillis: Long, limit: Int): List<CloudEventWrite> {
            check(!errorOnUse)
            return ready.take(limit)
        }
        override suspend fun markEventsUploaded(eventIds: Set<String>) {
            markedUploaded += eventIds
            ready.removeAll { it.eventId in eventIds }
        }
        override suspend fun cacheRemoteTaskSummary(
            accountId: String,
            summary: CloudTaskSummary,
        ): Boolean =
            remoteTaskSummaries.add(summary)
        override suspend fun cacheRemoteEvent(accountId: String, event: CloudEventRow): Boolean =
            cached.add("$accountId:${event.eventId}").also { inserted ->
                if (inserted) {
                    remoteRows["$accountId:${event.eventId}"] = event
                    pendingRemoteNotifications += "$accountId:${event.eventId}"
                }
            }
        override suspend fun isRemoteNotificationPending(
            accountId: String,
            eventId: String,
        ): Boolean = "$accountId:$eventId" in pendingRemoteNotifications
        override suspend fun pendingRemoteNotifications(
            accountId: String,
            limit: Int,
        ): List<CloudEventRow> = pendingRemoteNotifications.asSequence()
            .filter { it.startsWith("$accountId:") }
            .sorted()
            .take(limit)
            .mapNotNull(remoteRows::get)
            .toList()
        override suspend fun markRemoteNotificationHandled(
            accountId: String,
            eventId: String,
            handledAtEpochMillis: Long,
        ): Boolean = pendingRemoteNotifications.remove("$accountId:$eventId")
        override suspend fun removeCachedRemoteEvent(accountId: String, eventId: String): Boolean {
            removedRemoteEvents += accountId to eventId
            cached.remove("$accountId:$eventId")
            remoteRows.remove("$accountId:$eventId")
            pendingRemoteNotifications.remove("$accountId:$eventId")
            return true
        }
        override suspend fun removeCachedRemoteTask(accountId: String, taskId: String): Boolean {
            removedRemoteTasks += accountId to taskId
            return true
        }
        override suspend fun clearRemoteCache(): Int {
            check(!errorOnUse)
            val removed = cached.size
            cached.clear()
            remoteRows.clear()
            pendingRemoteNotifications.clear()
            remoteCacheClearCount++
            return removed
        }
    }

    private class FakeDataPlane(
        override val configured: Boolean = true,
        private val currentAccountId: String? = ACCOUNT_ID,
        private val batchResult: CloudEventBatchResult = CloudEventBatchResult(emptyList(), emptyList()),
        private val remoteEvent: CloudEventRow? = null,
        private val throwOnUnregister: Boolean = false,
        private val throwOnUpsertDevice: Boolean = false,
        private val changes: List<CloudSyncChange> = emptyList(),
        private val failFirstFetchedReceipt: Boolean = false,
        private val failFirstDisplayedReceipt: Boolean = false,
        private val registerStarted: CompletableDeferred<Unit>? = null,
        private val releaseRegister: CompletableDeferred<Unit>? = null,
    ) : CloudDataPlane {
        var calls = 0
        val devices = mutableListOf<CloudDeviceWrite>()
        val tokens = mutableListOf<Pair<String, String?>>()
        val revokedDevices = mutableListOf<String>()
        var peerDevicesCalls = 0
        val receipts = mutableListOf<Pair<String, ReceiptType>>()
        val deletedTasks = mutableListOf<String>()
        val upsertedTasks = mutableListOf<List<CloudTaskWrite>>()
        val uploadedEvents = mutableListOf<List<CloudEventWrite>>()
        var unregisterCalls = 0
        private var fetchedReceiptFailures = 0
        private var displayedReceiptFailures = 0
        override suspend fun currentAccountId(): String? { calls++; return currentAccountId }
        override suspend fun upsertDevice(device: CloudDeviceWrite) {
            calls++
            if (throwOnUpsertDevice) error("entitlement required")
            devices += device
        }
        override suspend fun peerDevices(): List<CloudDeviceRow> {
            calls++
            peerDevicesCalls++
            return emptyList()
        }
        override suspend fun revokeDevice(deviceId: String): Boolean {
            calls++
            revokedDevices += deviceId
            return true
        }
        override suspend fun upsertTasks(tasks: List<CloudTaskWrite>) {
            calls++
            upsertedTasks += tasks
        }
        override suspend fun deleteTasks(taskIds: List<String>) {
            calls++
            deletedTasks += taskIds
        }
        override suspend fun uploadEvents(events: List<CloudEventWrite>): CloudEventBatchResult {
            calls++
            uploadedEvents += events
            return batchResult
        }
        override suspend fun changesAfter(cursor: Long, limit: Int): List<CloudSyncChange> {
            calls++
            return changes.filter { it.sequence > cursor }.take(limit)
        }
        override suspend fun event(eventId: String): CloudEventRow? { calls++; return remoteEvent }
        override suspend fun addReceipt(
            eventId: String,
            deviceId: String,
            type: ReceiptType,
            receivedAt: String,
        ) {
            calls++
            if (type == ReceiptType.FETCHED && failFirstFetchedReceipt && fetchedReceiptFailures++ == 0) {
                error("offline")
            }
            if (type == ReceiptType.DISPLAYED &&
                failFirstDisplayedReceipt && displayedReceiptFailures++ == 0
            ) {
                error("offline")
            }
            receipts += eventId to type
        }
        override suspend fun registerPushToken(deviceId: String, token: String) {
            calls++
            tokens += deviceId to token
            registerStarted?.complete(Unit)
            releaseRegister?.await()
        }
        override suspend fun unregisterPushToken(deviceId: String) {
            calls++
            unregisterCalls++
            if (throwOnUnregister) error("offline")
        }
    }

    private class FakeAccountRepository(
        private val deletionAvailable: Boolean = false,
        private val throwOnDelete: Boolean = false,
        private val signInAccountId: String = ACCOUNT_ID,
        private val throwOnSignIn: Boolean = false,
        private val signInStarted: CompletableDeferred<Unit>? = null,
        private val releaseSignIn: CompletableDeferred<Unit>? = null,
    ) : CloudAccountRepository {
        private val mutableState = MutableStateFlow<CloudAccountState>(
            CloudAccountState.SignedIn(ACCOUNT_ID, "test@example.com"),
        )
        override val state: StateFlow<CloudAccountState> = mutableState
        var deleted = false
        override val accountDeletionAvailable: Boolean = deletionAvailable
        override suspend fun signUp(email: String, password: String): CloudAccountState = state.value
        override suspend fun signIn(email: String, password: String): CloudAccountState {
            if (throwOnSignIn) error("invalid credentials")
            signInStarted?.complete(Unit)
            releaseSignIn?.await()
            return CloudAccountState.SignedIn(signInAccountId, email)
                .also { mutableState.value = it }
        }
        override suspend fun signOut() {
            mutableState.value = CloudAccountState.SignedOut
        }
        override suspend fun deleteAccount() {
            check(deletionAvailable)
            if (throwOnDelete) error("offline")
            deleted = true
            mutableState.value = CloudAccountState.SignedOut
        }
    }

    companion object {
        const val ACCOUNT_ID = "018f0870-7b8a-7abc-8abc-3123456789ab"
        const val OTHER_ACCOUNT_ID = "018f0870-7b8a-7abc-8abc-7123456789ab"
        const val DEVICE_ID = "018f0870-7b8a-7abc-8abc-4123456789ab"
        const val OTHER_DEVICE_ID = "018f0870-7b8a-7abc-8abc-8123456789ab"
        const val TASK_ID = "018f0870-7b8a-7abc-8abc-5123456789ab"
        const val EPISODE_ID = "018f0870-7b8a-7abc-8abc-6123456789ab"
        const val EVENT_ID = "018f0870-7b8a-7abc-8abc-9123456789ab"

        fun event(eventId: String) = CloudEventWrite(
            eventId = eventId,
            taskId = TASK_ID,
            taskRevision = 1,
            episodeId = EPISODE_ID,
            sourceSequence = 1,
            occurredAt = "2026-08-03T00:00:00Z",
            payload = payload(),
        )

        fun taskWrite(taskId: String) = CloudTaskWrite(
            taskId = taskId,
            revision = 1,
            catalogVersion = "2026.08.24.1",
            capabilityId = "visual_target",
            title = "test target",
            monitoringDeviceId = DEVICE_ID,
            config = buildJsonObject { put("target_definition", buildJsonObject {}) },
        )

        fun row(eventId: String) = CloudEventRow(
            eventId = eventId,
            taskId = TASK_ID,
            taskRevision = 1,
            episodeId = EPISODE_ID,
            sourceSequence = 1,
            occurredAt = "2026-08-03T00:00:00Z",
            monitoringDeviceId = DEVICE_ID,
            payload = payload(),
        )

        fun payload(): JsonObject = buildJsonObject {
            put("type", "object_episode")
            put("target_id", "person")
            put("condition", "appeared")
            put("duration_ms", 3_000)
            put("count", 1)
        }
    }
}
