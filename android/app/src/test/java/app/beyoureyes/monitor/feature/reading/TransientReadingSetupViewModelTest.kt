package app.beyoureyes.monitor.feature.reading

import app.beyoureyes.core.data.ModelPackageIdentity
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.data.PersistedMonitor
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.core.domain.Monitor
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.core.domain.ReadingComparison
import app.beyoureyes.core.domain.ReadingTargetConfig
import app.beyoureyes.monitor.SamplingConfigResolution
import app.beyoureyes.monitor.TransientReadingPreparationResult
import app.beyoureyes.monitor.feature.monitoring.MonitorCameraState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransientReadingSetupViewModelTest {
    private val taskId = UuidV7.generate(1_757_000_000_000)
    private val pointer = ModelPackagePointer(
        identity = ModelPackageIdentity(
            packageId = "numeric_reader_ppocrv6_medium_v1",
            packageVersion = "0.1.0-internal.1",
        ),
        canonicalManifestSha256 = "a".repeat(64),
    )

    private fun sampling(taskId: String, revision: Long = 1L) = ResolvedSamplingConfig(
        taskId = taskId,
        taskRevision = revision,
        catalogVersion = "2026.08.31.1",
        capabilityId = "structured_reading",
        modelProfileKey = "numeric_reader_ppocrv6_medium_v1",
        recipeId = "reading_pipeline_v1",
        intentKey = "structured_reading",
        packagePointer = pointer,
        artifactIdentitySha256 = "b".repeat(64),
        deviceFingerprintSha256 = "c".repeat(64),
        intervalMillis = 1_000,
        manifestMinimumIntervalMillis = 500,
        manifestMaximumIntervalMillis = 10_000,
        adaptiveEnabled = true,
    )

    private inner class FakePersistence : ReadingTaskPersistence {
        var pendingPersisted: PersistedMonitor? = null
        var persistedRule: MonitorRule.ReadingThreshold? = null
        var stored: PersistedMonitor? = null
        var insertCount = 0
        var revisionCount = 0
        val notificationWrites = mutableListOf<Boolean>()

        override suspend fun persist(
            taskId: String,
            rule: MonitorRule.ReadingThreshold,
            targetConfig: ReadingTargetConfig,
            resolvedSamplingConfig: ResolvedSamplingConfig,
            nowEpochMillis: Long,
        ): PersistedMonitor {
            insertCount++
            check(stored == null) { "reserved monitor identity already exists" }
            persistedRule = rule
            return PersistedMonitor(
                monitor = Monitor(
                    id = taskId,
                    revision = 1,
                    name = "读数",
                    target = MonitorTarget.NumericReading(targetConfig),
                    rule = rule,
                    createdAtEpochMillis = nowEpochMillis,
                ),
                runtimePackagePointer = pointer,
            ).also { stored = it }
        }

        override suspend fun persistPending(
            taskId: String,
            targetConfig: ReadingTargetConfig,
            resolvedSamplingConfig: ResolvedSamplingConfig,
            nowEpochMillis: Long,
        ): PersistedMonitor {
            insertCount++
            check(stored == null) { "reserved monitor identity already exists" }
            val pending = PersistedMonitor(
                monitor = Monitor(
                    id = taskId,
                    revision = 1,
                    name = "读数",
                    target = MonitorTarget.NumericReading(targetConfig),
                    rule = MonitorRule.ReadingThreshold.Single(
                        comparison = ReadingComparison.GT,
                        thresholdDecimal = "0",
                        configured = false,
                    ),
                    createdAtEpochMillis = nowEpochMillis,
                ),
                runtimePackagePointer = pointer,
            )
            pendingPersisted = pending
            stored = pending
            return pending
        }

        override suspend fun revise(
            taskId: String,
            expectedRevision: Long,
            rule: MonitorRule.ReadingThreshold,
            targetConfig: ReadingTargetConfig,
        ): PersistedMonitor? {
            val current = stored ?: return null
            if (current.monitor.id != taskId || current.monitor.revision != expectedRevision) return null
            revisionCount++
            return current.copy(
                monitor = current.monitor.copy(
                    revision = expectedRevision + 1,
                    rule = rule,
                    target = MonitorTarget.NumericReading(targetConfig),
                ),
            ).also { stored = it }
        }

        override suspend fun setNotificationsEnabled(taskId: String, enabled: Boolean) {
            notificationWrites += enabled
        }
    }

    private fun readyViewModel(persistence: ReadingTaskPersistence): TransientReadingSetupViewModel =
        TransientReadingSetupViewModel(
            preparation = ReadingPreparation { id, _ ->
                TransientReadingPreparationResult.Ready(
                    SamplingConfigResolution.Ready(
                        config = sampling(id),
                        fieldValidationSpec = null,
                        readingPreviewSpec = null,
                        modelDisplayName = "test-package",
                    ),
                )
            },
            persistence = persistence,
            monitorName = "读数",
            taskId = taskId,
            nowEpochMillis = { 1_757_000_000_000 },
        )

    @Test
    fun `pending baseline persist stores an unconfigured reading and reports it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val persistence = FakePersistence()
        try {
            val viewModel = readyViewModel(persistence)
            advanceUntilIdle()

            val result = viewModel.persistPendingBaseline(ReadingTargetConfig())
            advanceUntilIdle()

            val ready = result as? MonitorCameraState.Ready
            assertNotNull(ready)
            val rule = ready!!.persisted.monitor.rule as MonitorRule.ReadingThreshold.Single
            assertFalse(rule.configured)
            assertNull(
                (ready.persisted.monitor.target as MonitorTarget.NumericReading).confirmedFormat,
            )
            assertFalse(ready.savingCondition)
            assertNotNull(persistence.pendingPersisted)
            assertTrue(viewModel.hasPersisted)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `retrying a pending start reuses the saved task and persists notification changes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val persistence = FakePersistence()
        try {
            val viewModel = readyViewModel(persistence)
            advanceUntilIdle()
            val first = viewModel.persistPendingBaseline(ReadingTargetConfig())
            assertNotNull(first)

            // The camera handoff was rejected; the user changes notifications and retries.
            viewModel.setNotificationsEnabled(true)
            advanceUntilIdle()
            val retried = viewModel.persistPendingBaseline(ReadingTargetConfig())

            assertNotNull(retried)
            assertEquals(first!!.persisted, retried!!.persisted)
            assertEquals(1, persistence.insertCount)
            assertEquals(listOf(false, true), persistence.notificationWrites)
            assertTrue(retried.notificationsEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `confirming after a pending start failure updates the saved task and runtime revision`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val persistence = FakePersistence()
        try {
            val viewModel = readyViewModel(persistence)
            advanceUntilIdle()
            assertNotNull(viewModel.persistPendingBaseline(ReadingTargetConfig()))
            val rule = MonitorRule.ReadingThreshold.Single(
                comparison = ReadingComparison.GT,
                thresholdDecimal = "10",
                configured = true,
            )
            val targetConfig = ReadingTargetConfig(
                confirmedFormat = app.beyoureyes.core.domain.ConfirmedReadingFormat(
                    kind = app.beyoureyes.core.domain.ReadingFormatKind.DECIMAL,
                ),
            )

            val confirmed = viewModel.configureReadingAndPersist(rule, targetConfig)

            assertNotNull(confirmed)
            assertEquals(1, persistence.insertCount)
            assertEquals(1, persistence.revisionCount)
            assertEquals(taskId, confirmed!!.persisted.monitor.id)
            assertEquals(2L, confirmed.persisted.monitor.revision)
            assertEquals(2L, confirmed.runtime.config.taskRevision)
            assertEquals(pointer, confirmed.runtime.config.packagePointer)
            assertEquals(rule, confirmed.persisted.monitor.rule)
            assertEquals(targetConfig, (confirmed.persisted.monitor.target as MonitorTarget.NumericReading).config)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `retrying model preparation preserves a task already saved as pending`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val persistence = FakePersistence()
        try {
            val viewModel = readyViewModel(persistence)
            advanceUntilIdle()
            val saved = viewModel.persistPendingBaseline(ReadingTargetConfig())

            viewModel.retry()
            advanceUntilIdle()

            assertEquals(saved, viewModel.state.value)
            assertTrue(viewModel.hasPersisted)
            assertNotNull(viewModel.persistPendingBaseline(ReadingTargetConfig()))
            assertEquals(1, persistence.insertCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `pending baseline persist is rejected after a configured persist`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val persistence = FakePersistence()
        try {
            val viewModel = readyViewModel(persistence)
            advanceUntilIdle()
            val configuredRule = MonitorRule.ReadingThreshold.Single(
                comparison = ReadingComparison.GT,
                thresholdDecimal = "10",
                configured = true,
            )
            val targetConfig = ReadingTargetConfig(
                confirmedFormat = app.beyoureyes.core.domain.ConfirmedReadingFormat(
                    kind = app.beyoureyes.core.domain.ReadingFormatKind.DECIMAL,
                ),
            )
            val persisted = viewModel.configureReadingAndPersist(configuredRule, targetConfig)
            advanceUntilIdle()
            assertNotNull(persisted)
            assertTrue(viewModel.hasPersisted)

            val pending = viewModel.persistPendingBaseline(ReadingTargetConfig())

            assertNull(pending)
            assertNull(persistence.pendingPersisted)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `pending baseline persist rejects a target carrying a confirmed format`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val persistence = FakePersistence()
        try {
            val viewModel = readyViewModel(persistence)
            advanceUntilIdle()

            val pending = viewModel.persistPendingBaseline(
                ReadingTargetConfig(
                    confirmedFormat = app.beyoureyes.core.domain.ConfirmedReadingFormat(
                        kind = app.beyoureyes.core.domain.ReadingFormatKind.DECIMAL,
                    ),
                ),
            )

            assertNull(pending)
            assertNull(persistence.pendingPersisted)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
