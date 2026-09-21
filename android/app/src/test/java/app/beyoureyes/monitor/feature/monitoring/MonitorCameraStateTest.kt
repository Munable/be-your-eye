package app.beyoureyes.monitor.feature.monitoring

import app.beyoureyes.core.data.ModelPackageIdentity
import app.beyoureyes.core.data.ModelPackagePointer
import app.beyoureyes.core.data.PersistedMonitor
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.core.domain.Monitor
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.ConfirmedReadingFormat
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.core.domain.NormalizedRect
import app.beyoureyes.core.domain.ReadingFormatKind
import app.beyoureyes.core.domain.ReadingTargetConfig
import app.beyoureyes.monitor.SamplingConfigResolution
import app.beyoureyes.monitor.ModelPreparationProgress
import app.beyoureyes.monitor.ReadingPreviewSpec
import app.beyoureyes.monitor.SetupFieldValidationStatus
import app.beyoureyes.monitor.SimilarityFieldValidationIdentity
import app.beyoureyes.monitor.SimilarityFieldValidationSignal
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.UiText
import app.beyoureyes.monitor.emptyFieldValidationSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorCameraStateTest {
    @Test
    fun `preparation stages stay inside a natural camera opening transition`() {
        assertEquals(
            UiText.Resource(R.string.model_progress_checking),
            modelPreparationMessage(ModelPreparationProgress.Checking),
        )
        assertEquals(
            UiText.Resource(R.string.model_progress_downloading),
            modelPreparationMessage(ModelPreparationProgress.Downloading(1, 2, "Reader")),
        )
        assertEquals(
            UiText.Resource(R.string.model_progress_verifying),
            modelPreparationMessage(ModelPreparationProgress.Verifying),
        )
        assertEquals(
            UiText.Resource(R.string.model_progress_activating),
            modelPreparationMessage(ModelPreparationProgress.Activating),
        )
    }

    @Test
    fun `missing monitor is an explicit terminal camera error`() {
        val state = missingMonitorCameraError()

        assertEquals(UiText.Resource(R.string.monitor_missing_return_home), state.message)
        assertFalse(state.retryable)
        assertFalse(state.canReplaceReferenceImages)
    }

    @Test
    fun `saved reading revision carries the exact verified runtime into handoff`() {
        val fixture = readingRuntimeFixture()

        val carried = carryRuntimeAfterReadingRuleSave(
            previous = fixture.previous,
            updated = fixture.updated,
            runtime = fixture.runtime,
        )

        requireNotNull(carried)
        assertEquals(fixture.updated.monitor.revision, carried.config.taskRevision)
        assertEquals(fixture.runtime.config.packagePointer, carried.config.packagePointer)
        assertEquals(fixture.runtime.modelDisplayName, carried.modelDisplayName)
    }

    @Test
    fun `saved reading revision rebinds the live preview spec`() {
        val fixture = readingRuntimeFixture()
        val runtime = fixture.runtime.copy(
            readingPreviewSpec = ReadingPreviewSpec(
                taskId = fixture.previous.monitor.id,
                taskRevision = fixture.previous.monitor.revision,
                packagePointer = fixture.runtime.config.packagePointer,
            ) { error("camera runtime is outside this unit test") },
        )

        val carried = requireNotNull(
            carryRuntimeAfterReadingRuleSave(
                previous = fixture.previous,
                updated = fixture.updated,
                runtime = runtime,
            ),
        )

        assertEquals(fixture.updated.monitor.revision, carried.config.taskRevision)
        assertEquals(
            fixture.updated.monitor.revision,
            carried.readingPreviewSpec?.taskRevision,
        )
        assertEquals(
            fixture.runtime.config.packagePointer,
            carried.readingPreviewSpec?.packagePointer,
        )
    }

    @Test
    fun `saved reading revision rejects a stale live preview spec`() {
        val fixture = readingRuntimeFixture()
        val staleRuntime = fixture.runtime.copy(
            readingPreviewSpec = ReadingPreviewSpec(
                taskId = fixture.previous.monitor.id,
                taskRevision = fixture.previous.monitor.revision + 1,
                packagePointer = fixture.runtime.config.packagePointer,
            ) { error("camera runtime is outside this unit test") },
        )

        assertNull(
            carryRuntimeAfterReadingRuleSave(
                previous = fixture.previous,
                updated = fixture.updated,
                runtime = staleRuntime,
            ),
        )
    }

    @Test
    fun `reading handoff rejects a non exact saved revision`() {
        val fixture = readingRuntimeFixture()
        val skippedRevision = fixture.updated.copy(
            monitor = fixture.updated.monitor.copy(revision = 3),
        )

        assertNull(
            carryRuntimeAfterReadingRuleSave(
                previous = fixture.previous,
                updated = skippedRevision,
                runtime = fixture.runtime,
            ),
        )
    }

    @Test
    fun `throwing persistence dependency cannot produce a handoff runtime`() = runBlocking {
        val fixture = readingRuntimeFixture()
        val ready = MonitorCameraState.Ready(
            persisted = fixture.previous,
            runtime = fixture.runtime,
            savingCondition = true,
        )

        val result = persistReadingRuleForHandoff(
            ready = ready,
            rule = fixture.updated.monitor.rule as MonitorRule.ReadingThreshold,
            targetConfig = (fixture.updated.monitor.target as MonitorTarget.NumericReading).config,
        ) { _, _, _, _ ->
            throw IllegalStateException("sqlite write failed")
        }

        assertNull(result)
        val restored = readingRuleSaveFailureState(ready)
        assertFalse(restored.savingCondition)
        assertEquals(ready.persisted, restored.persisted)
        assertEquals(ready.runtime, restored.runtime)
        assertEquals(
            UiText.Resource(R.string.error_condition_save_failed),
            restored.conditionError,
        )
    }

    @Test(expected = CancellationException::class)
    fun `cancelled persistence remains cooperative`(): Unit = runBlocking {
        val fixture = readingRuntimeFixture()
        persistReadingRuleForHandoff(
            ready = MonitorCameraState.Ready(fixture.previous, fixture.runtime),
            rule = fixture.updated.monitor.rule as MonitorRule.ReadingThreshold,
            targetConfig = (fixture.updated.monitor.target as MonitorTarget.NumericReading).config,
        ) { _, _, _, _ ->
            throw CancellationException("screen closed")
        }
        Unit
    }

    @Test
    fun `cancelled screen waiter does not cancel viewmodel owned persistence`() = runBlocking {
        val fixture = readingRuntimeFixture()
        val expected = MonitorCameraState.Ready(fixture.updated, fixture.runtime.copy(
            config = fixture.runtime.config.copy(taskRevision = fixture.updated.monitor.revision),
        ))
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = ReadingRulePersistenceOwner(ownerScope)
        val started = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var persistenceCalls = 0
        try {
            val persistence = checkNotNull(owner.startOrNull {
                persistenceCalls++
                started.complete(Unit)
                releaseWrite.await()
                expected
            })
            withTimeout(5_000) { started.await() }

            val firstScreenWaiter = launch { persistence.await() }
            yield()
            firstScreenWaiter.cancelAndJoin()
            assertTrue(persistence.isActive)

            val recreatedScreenWaiter = async(start = CoroutineStart.UNDISPATCHED) {
                checkNotNull(owner.current()).await()
            }
            releaseWrite.complete(Unit)

            assertEquals(expected, withTimeout(5_000) { recreatedScreenWaiter.await() })
            assertEquals(1, persistenceCalls)
            assertNull(owner.current())
        } finally {
            releaseWrite.complete(Unit)
            ownerScope.cancel()
        }
    }

    @Test
    fun `reference preview distinguishes immediate live signal from stable confirmation`() {
        val identity = fieldIdentity()
        val present = emptyFieldValidationSummary().copy(
            processedFrames = 1,
            presentFrames = 1,
            latestSignal = SimilarityFieldValidationSignal.PRESENT,
        )
        val absent = emptyFieldValidationSummary().copy(
            processedFrames = 1,
            absentFrames = 1,
            latestSignal = SimilarityFieldValidationSignal.ABSENT,
        )
        val unavailable = emptyFieldValidationSummary().copy(
            processedFrames = 1,
            unavailableFrames = 1,
            unavailableReasons = mapOf(
                app.beyoureyes.core.domain.UnavailableReason.LOW_QUALITY to 1,
            ),
            latestSignal = SimilarityFieldValidationSignal.UNAVAILABLE,
        )

        assertEquals(
            ReferenceFieldFeedback.PRESENT_CANDIDATE,
            referenceFieldFeedback(SetupFieldValidationStatus.Checking(identity, present), identity),
        )
        assertEquals(
            ReferenceFieldFeedback.PRESENT_CONFIRMED,
            referenceFieldFeedback(SetupFieldValidationStatus.Passed(identity, present, 7L), identity),
        )
        assertEquals(
            ReferenceFieldFeedback.ABSENT,
            referenceFieldFeedback(SetupFieldValidationStatus.Checking(identity, absent), identity),
        )
        assertEquals(
            ReferenceFieldFeedback.ABSENT,
            referenceFieldFeedback(
                SetupFieldValidationStatus.NotFound(identity, emptyFieldValidationSummary()),
                identity,
            ),
        )
        assertEquals(
            ReferenceFieldFeedback.UNAVAILABLE,
            referenceFieldFeedback(SetupFieldValidationStatus.Checking(identity, unavailable), identity),
        )
        assertEquals(
            ReferenceFieldFeedback.INVALIDATED,
            referenceFieldFeedback(
                SetupFieldValidationStatus.Passed(identity, present, 7L),
                identity.copy(taskRevision = identity.taskRevision + 1),
            ),
        )
    }

    @Test
    fun `reference field feedback stays informational without any start gating`() {
        val identity = fieldIdentity()
        val present = emptyFieldValidationSummary().copy(
            processedFrames = 1,
            presentFrames = 1,
            latestSignal = SimilarityFieldValidationSignal.PRESENT,
        )

        // 测试识别只是反馈：任何识别状态都不产生"必须先命中才能开始"的约束。
        assertEquals(
            ReferenceFieldFeedback.ABSENT,
            referenceFieldFeedback(
                SetupFieldValidationStatus.NotFound(identity, emptyFieldValidationSummary()),
                identity,
            ),
        )
        assertEquals(
            ReferenceFieldFeedback.PRESENT_CONFIRMED,
            referenceFieldFeedback(
                SetupFieldValidationStatus.Passed(identity, present, 7L),
                identity,
            ),
        )
    }

    private fun readingRuntimeFixture(): ReadingRuntimeFixture {
        val taskId = UuidV7.generate(1_700_000_000_000L)
        val pointer = ModelPackagePointer(
            identity = ModelPackageIdentity("numeric_reader_fixture", "1.0.0"),
            canonicalManifestSha256 = "a".repeat(64),
        )
        val previous = PersistedMonitor(
            monitor = Monitor(
                id = taskId,
                revision = 1,
                name = "数字监控 1",
                target = MonitorTarget.NumericReading(
                    ReadingTargetConfig(
                        confirmedFormat = ConfirmedReadingFormat(ReadingFormatKind.DECIMAL),
                    ),
                ),
                rule = MonitorRule.ReadingThreshold.Single(
                    thresholdDecimal = "100",
                    configured = true,
                ),
                createdAtEpochMillis = 1_700_000_000_000L,
            ),
            runtimePackagePointer = pointer,
        )
        val updated = previous.copy(
            monitor = previous.monitor.copy(
                revision = 2,
                target = MonitorTarget.NumericReading(
                    ReadingTargetConfig(
                        confirmedFormat = ConfirmedReadingFormat(ReadingFormatKind.DECIMAL),
                    ),
                ),
                rule = MonitorRule.ReadingThreshold.Single(
                    thresholdDecimal = "332",
                    configured = true,
                ),
            ),
        )
        val runtime = SamplingConfigResolution.Ready(
            config = ResolvedSamplingConfig(
                taskId = taskId,
                taskRevision = 1,
                catalogVersion = "2026.08.24.1",
                capabilityId = "structured_reading",
                modelProfileKey = "numeric_display_reading",
                recipeId = "reading_pipeline_general_v1",
                intentKey = "reading.numeric.display",
                packagePointer = pointer,
                artifactIdentitySha256 = "b".repeat(64),
                deviceFingerprintSha256 = "c".repeat(64),
                intervalMillis = 500,
                manifestMinimumIntervalMillis = 100,
                manifestMaximumIntervalMillis = 1_000,
                adaptiveEnabled = false,
            ),
            fieldValidationSpec = null,
            readingPreviewSpec = null,
            modelDisplayName = "PaddleOCR-VL",
        )
        return ReadingRuntimeFixture(previous, updated, runtime)
    }

    private fun fieldIdentity() = SimilarityFieldValidationIdentity(
        taskId = UuidV7.generate(1_700_000_000_000L),
        taskRevision = 1,
        packagePointer = ModelPackagePointer(
            identity = ModelPackageIdentity("similarity_fixture", "1.0.0"),
            canonicalManifestSha256 = "d".repeat(64),
        ),
        roi = NormalizedRect(0f, 0f, 1f, 1f),
        targetId = "field_target",
    )
}

private data class ReadingRuntimeFixture(
    val previous: PersistedMonitor,
    val updated: PersistedMonitor,
    val runtime: SamplingConfigResolution.Ready,
)
