package app.beyoureyes.monitor.feature.objectdetection

import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.ObjectClassDefinition
import app.beyoureyes.core.domain.PresenceRuleKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObjectDetectionCreationViewModelTest {
    @Test
    fun catalogLoadingAndVerificationFailureStayFailClosed() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val loading = CompletableDeferred<List<ObjectClassDefinition>>()
        try {
            val viewModel = ObjectDetectionCreationViewModel(
                ObjectTargetCatalogProvider { loading.await() },
            )
            assertTrue(viewModel.state.value.loading)
            assertFalse(viewModel.continueToCamera())

            loading.completeExceptionally(IllegalStateException("rejected signed metadata"))
            advanceUntilIdle()

            assertFalse(viewModel.state.value.loading)
            assertEquals(ObjectCreationError.CATALOG_UNAVAILABLE, viewModel.state.value.error)
            assertTrue(viewModel.state.value.catalogUnavailable)
            assertTrue(viewModel.state.value.suggestions.isEmpty())
            assertFalse(viewModel.continueToCamera())

            viewModel.onQueryChanged("apple")
            assertEquals(ObjectCreationError.CATALOG_UNAVAILABLE, viewModel.state.value.error)
            assertTrue(viewModel.state.value.catalogUnavailable)
            assertFalse(viewModel.continueToStart())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun catalogFailureCanBeRetriedWithoutLeavingTheCreationPage() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var loads = 0
            val viewModel = ObjectDetectionCreationViewModel(
                ObjectTargetCatalogProvider {
                    loads += 1
                    if (loads == 1) error("temporary catalog failure")
                    provider().load()
                },
            )
            advanceUntilIdle()
            assertTrue(viewModel.state.value.catalogUnavailable)

            viewModel.retryCatalogLoad()
            advanceUntilIdle()

            assertEquals(2, loads)
            assertFalse(viewModel.state.value.loading)
            assertFalse(viewModel.state.value.catalogUnavailable)
            assertEquals(null, viewModel.state.value.error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun partialTextFiltersSignedTargetsButRequiresAnExplicitSelection() = catalogTest { viewModel ->
        viewModel.onQueryChanged("app")

        assertEquals(listOf("apple"), viewModel.state.value.suggestions.map { it.targetId })
        assertEquals(null, viewModel.state.value.error)
        assertEquals(null, viewModel.state.value.selected)
        assertFalse(viewModel.continueToCamera())

        viewModel.select(viewModel.state.value.suggestions.single())
        assertEquals("apple", viewModel.target()?.targetId)
        assertTrue(viewModel.continueToCamera())
    }

    @Test
    fun emptyQueryOffersSignedExamplesWithoutSelectingOrStartingOne() = catalogTest { viewModel ->
        assertEquals(listOf("apple", "cat"), viewModel.state.value.suggestions.map { it.targetId })
        assertFalse(viewModel.continueToStart())
        viewModel.select(viewModel.state.value.suggestions.first())
        assertEquals("apple", viewModel.target()?.targetId)
        viewModel.onQueryChanged("apple")
        viewModel.onQueryChanged("")
        assertEquals(listOf("apple", "cat"), viewModel.state.value.suggestions.map { it.targetId })
        assertEquals(null, viewModel.state.value.selected)
        assertFalse(viewModel.continueToStart())
    }

    @Test
    fun initialExamplesStayBoundedAndCannotSelectAnUnsignedTarget() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val definitions = (1..6).map {
                ObjectClassDefinition("target_$it", "目标$it", "target $it", emptySet())
            }
            val viewModel = ObjectDetectionCreationViewModel(ObjectTargetCatalogProvider { definitions })
            advanceUntilIdle()
            assertEquals(definitions.take(3), viewModel.state.value.suggestions)
            viewModel.select(ObjectClassDefinition("unknown", "未知", "unknown", emptySet()))
            assertEquals(null, viewModel.state.value.selected)
            assertFalse(viewModel.continueToStart())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun exactChineseAndNaturalLanguageQueriesSelectOneSignedCategory() = catalogTest { viewModel ->
        viewModel.onQueryChanged("当画面中出现苹果的时候通知我")
        assertEquals("apple", viewModel.state.value.selected?.targetId)
        assertEquals(listOf("apple"), viewModel.state.value.suggestions.map { it.targetId })

        viewModel.onQueryChanged("  CAT  ")
        assertEquals("cat", viewModel.state.value.selected?.targetId)
        assertEquals(listOf("cat"), viewModel.state.value.suggestions.map { it.targetId })

        viewModel.onQueryChanged("猫")
        assertEquals("cat", viewModel.target()?.targetId)
        assertTrue(viewModel.continueToCamera())
        assertFalse(viewModel.continueToCamera())
        viewModel.resetCameraRequest()
        assertTrue(viewModel.continueToCamera())
    }

    @Test
    fun missingDescriptionStaysOnCreationPageWithoutNearbySuggestions() = catalogTest { viewModel ->
        viewModel.onQueryChanged("蒸汽泄漏")

        assertEquals(ObjectCreationError.NO_MATCH, viewModel.state.value.error)
        assertTrue(viewModel.state.value.selected == null)
        assertTrue(viewModel.state.value.suggestions.isEmpty())
        assertFalse(viewModel.continueToCamera())
    }

    @Test
    fun assistantPrefillSelectsExactTargetAndRetainsItsCatalogBindingUntilEdited() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val binding = ObjectDetectionModelBinding(
            modelProfileKey = "common_objects_tensorflow_efficientdet_lite2",
            packageId = "efficientdet_lite2_object_v1",
            intentKey = "object.common.apple",
        )
        try {
            val viewModel = ObjectDetectionCreationViewModel(
                catalogProvider = provider(),
                initialTargetId = "apple",
                initialModelBinding = binding,
                initialRule = MonitorRule.TargetPresence(PresenceRuleKind.REMAINS, 5),
                initialNotificationsEnabled = true,
            )
            advanceUntilIdle()

            assertEquals("apple", viewModel.target()?.targetId)
            assertEquals(binding, viewModel.modelBinding())
            assertEquals(
                MonitorRule.TargetPresence(PresenceRuleKind.REMAINS, 5),
                viewModel.presenceRule(),
            )
            assertTrue(viewModel.notificationsEnabled())

            viewModel.onQueryChanged("猫")
            assertEquals("cat", viewModel.target()?.targetId)
            assertEquals(null, viewModel.modelBinding())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun manualRuleChoiceIsCarriedIntoCameraSetupWithoutChangingTheSelectedTarget() =
        catalogTest { viewModel ->
            viewModel.onQueryChanged("苹果")
            val chosen = MonitorRule.TargetPresence(PresenceRuleKind.APPEARS, 3)

            viewModel.setPresenceRule(chosen)
            viewModel.setNotificationsEnabled(true)

            assertEquals("apple", viewModel.target()?.targetId)
            assertEquals(chosen, viewModel.rule.value)
            assertEquals(chosen, viewModel.presenceRule())
            assertTrue(viewModel.notificationsEnabled())
            assertTrue(viewModel.continueToCamera())
            viewModel.setPresenceRule(MonitorRule.TargetPresence(PresenceRuleKind.APPEARS, 10))
            assertEquals(chosen, viewModel.presenceRule())
            viewModel.resetCameraRequest()
            assertTrue(viewModel.notificationsEnabled())
        }

    private fun catalogTest(block: (ObjectDetectionCreationViewModel) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = ObjectDetectionCreationViewModel(provider())
            advanceUntilIdle()
            assertFalse(viewModel.state.value.loading)
            block(viewModel)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun provider() = ObjectTargetCatalogProvider {
        listOf(
            ObjectClassDefinition("apple", "苹果", "apple", emptySet()),
            ObjectClassDefinition("cat", "猫", "cat", setOf("小猫")),
        )
    }
}
