package app.beyoureyes.core.data.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The only account action surface used by Android UI. Local monitoring never depends on it. */
class CloudAccountController(
    private val accountRepository: CloudAccountRepository,
    private val dataPlane: CloudDataPlane,
    private val syncCoordinator: CloudSyncCoordinator,
    private val snapshotTransfers: RemoteSnapshotTransferController? = null,
) {
    private val authTransitionMutex = Mutex()

    val state: StateFlow<CloudAccountState> = accountRepository.state
    val accountDeletionAvailable: Boolean = accountRepository.accountDeletionAvailable
    val passwordRecoveryAvailable: Boolean = accountRepository.passwordRecoveryAvailable
    val passwordRecoveryPending: StateFlow<Boolean> = accountRepository.passwordRecoveryPending

    suspend fun requestPasswordReset(email: String) = withContext(Dispatchers.IO) {
        authTransitionMutex.withLock { accountRepository.requestPasswordReset(email) }
    }

    fun acceptPasswordRecoveryCallback(callbackUrl: String): Boolean =
        accountRepository.acceptPasswordRecoveryCallback(callbackUrl)

    suspend fun completePasswordRecovery(newPassword: String): CloudAccountState =
        withContext(Dispatchers.IO) {
            authTransitionMutex.withLock {
                val state = accountRepository.completePasswordRecovery(newPassword)
                if (state is CloudAccountState.SignedIn) {
                    prepareSignedInSession(state.accountId)
                }
                state
            }
        }

    fun cancelPasswordRecovery() = accountRepository.cancelPasswordRecovery()

    suspend fun signUp(email: String, password: String): CloudAccountState =
        withContext(Dispatchers.IO) {
            authTransitionMutex.withLock { signUpLocked(email, password) }
        }

    private suspend fun signUpLocked(email: String, password: String): CloudAccountState {
        val previousAccountId = prepareAuthTransition()
        val state = attemptAuth(previousAccountId) {
            accountRepository.signUp(email, password)
        }
        if (state !is CloudAccountState.SignedIn) {
            restorePushTokenAfterFailedAuth(previousAccountId)
        }
        if (state is CloudAccountState.SignedIn) {
            prepareSignedInSession(state.accountId)
        }
        return state
    }

    suspend fun signIn(email: String, password: String): CloudAccountState =
        withContext(Dispatchers.IO) {
            authTransitionMutex.withLock { signInLocked(email, password) }
        }

    private suspend fun signInLocked(email: String, password: String): CloudAccountState {
        val previousAccountId = prepareAuthTransition()
        val state = attemptAuth(previousAccountId) {
            accountRepository.signIn(email, password)
        }
        if (state !is CloudAccountState.SignedIn) {
            restorePushTokenAfterFailedAuth(previousAccountId)
        }
        if (state is CloudAccountState.SignedIn) {
            // A direct A -> B sign-in is permitted by the auth SDK. Remove downloaded rows before
            // returning control; account-scoped display filtering protects the intermediate state.
            prepareSignedInSession(state.accountId)
        }
        return state
    }

    private suspend fun prepareSignedInSession(accountId: String) {
        syncCoordinator.clearRemoteCache()
        syncCoordinator.resetAccountSessionState(accountId)
        // Authentication must finish before subscription access exists. The root access observer
        // schedules the first sync only after the entitlement is Granted; attempting it here makes
        // a successful sign-in look like a network failure when the cloud data plane correctly
        // rejects an unenrolled account.
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        authTransitionMutex.withLock {
            val accountId = (state.value as? CloudAccountState.SignedIn)?.accountId
            if (accountId != null) {
                // This has no network dependency. Keep local rows usable while fencing their optional
                // cloud projection from the next account on the same phone.
                claimLocalDataBeforeAuthTransition(accountId)
            }
            try {
                withTimeoutOrNull(AUTH_TRANSITION_NETWORK_TIMEOUT_MILLIS) {
                    try {
                        syncCoordinator.unregisterCurrentPushToken()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // Local sign-out must remain available while offline. A stale server token
                        // carries only opaque event/cursor IDs, and authenticated fetches stop after
                        // this call.
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            snapshotTransfers?.clearCurrentAccountCache()
            accountRepository.signOut()
            syncCoordinator.clearRemoteCache()
            if (accountId != null) syncCoordinator.resetAccountSessionState(accountId)
        }
    }

    suspend fun syncNow(): CloudSyncResult = withContext(Dispatchers.IO) {
        authTransitionMutex.withLock {
            syncCoordinator.sync().also { snapshotTransfers?.reconcileCache() }
        }
    }

    suspend fun deleteAccount() = withContext(Dispatchers.IO) {
        authTransitionMutex.withLock {
            if (!accountDeletionAvailable) {
                throw CloudUnavailableException("account deletion service is not configured")
            }
            val accountId = (state.value as? CloudAccountState.SignedIn)?.accountId
            try {
                syncCoordinator.unregisterCurrentPushToken(clearLocalToken = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Account deletion itself cascades the server token. Keep the device's local FCM
                // registration token so a failed deletion does not silently disable notifications,
                // and so a later account can register the same device token without waiting for a
                // Firebase token rotation.
            }
            snapshotTransfers?.clearCurrentAccountCache()
            accountRepository.deleteAccount()
            try {
                syncCoordinator.clearRemoteCache()
            } finally {
                if (accountId != null) {
                    try {
                        syncCoordinator.clearAccountState(accountId)
                    } finally {
                        // Account deletion has already succeeded remotely. Always release the
                        // device-local owner even if clearing the old cursor/fingerprint state fails.
                        syncCoordinator.releaseLocalDataOwnership(accountId)
                    }
                }
            }
        }
    }

    suspend fun peerDevices(): List<CloudDeviceRow> = withContext(Dispatchers.IO) {
        authTransitionMutex.withLock {
            if (state.value is CloudAccountState.SignedIn) dataPlane.peerDevices() else emptyList()
        }
    }

    suspend fun currentDeviceId(): String = withContext(Dispatchers.IO) {
        syncCoordinator.currentDeviceId()
    }

    suspend fun revokePeerDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        authTransitionMutex.withLock {
            check(state.value is CloudAccountState.SignedIn) { "Supabase account session is required" }
            syncCoordinator.revokePeerDevice(deviceId)
        }
    }

    private suspend fun claimLocalDataBeforeAuthTransition(accountId: String) {
        // Fail closed: an account transition cannot proceed while local ownership is unknown.
        // Otherwise the next account could claim and upload the previous account's rows.
        syncCoordinator.claimLocalData(accountId)
    }

    private suspend fun prepareAuthTransition(): String? {
        val previousAccountId = (state.value as? CloudAccountState.SignedIn)?.accountId
            ?: return null
        claimLocalDataBeforeAuthTransition(previousAccountId)
        unregisterPushTokenBeforeAuthTransition()
        return previousAccountId
    }

    private suspend fun <T> attemptAuth(
        previousAccountId: String?,
        action: suspend () -> T,
    ): T = try {
        action()
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            restorePushTokenAfterFailedAuth(previousAccountId)
        }
        throw cancelled
    } catch (error: Throwable) {
        restorePushTokenAfterFailedAuth(previousAccountId)
        throw error
    }

    private suspend fun restorePushTokenAfterFailedAuth(previousAccountId: String?) {
        if (previousAccountId == null ||
            (state.value as? CloudAccountState.SignedIn)?.accountId != previousAccountId
        ) {
            return
        }
        try {
            syncCoordinator.restoreCurrentPushToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Preserve the original authentication result; the next foreground sync retries the
            // idempotent registration while the previous session is still active.
        }
    }

    private suspend fun unregisterPushTokenBeforeAuthTransition() {
        try {
            // Best effort while offline: the local account switch remains available, while an
            // online transition removes this device token from the previous account before the
            // new account can register it.
            syncCoordinator.unregisterCurrentPushToken(clearLocalToken = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The next account's sync will register the token for its own device row.
        }
    }

    private companion object {
        const val AUTH_TRANSITION_NETWORK_TIMEOUT_MILLIS = 3_000L
    }
}
