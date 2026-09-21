package app.beyoureyes.monitor.feature.assistant

import androidx.lifecycle.SavedStateHandle
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.monitor.feature.objectdetection.ObjectDetectionCreationViewModel
import app.beyoureyes.monitor.feature.objectdetection.ObjectDetectionModelBinding
import app.beyoureyes.monitor.feature.objectdetection.ObjectTargetCatalogProvider
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
class AssistantProposalSavedStateTest {
    @Test
    fun allThreeProposalKindsRoundTripThroughProcessRestorableState() {
        val catalog = catalog()
        val proposals = listOf(
            MonitorConfigurationProposal.ReferenceImages(
                title = "门口包裹摘要",
                catalogBinding = catalog.binding,
                modelProfileKey = REFERENCE_PROFILE,
                packageId = REFERENCE_PACKAGE,
                intentKey = "visual.reference.object",
                rule = AssistantPresenceRule(AssistantPresenceCondition.REMAINS, 10),
            ),
            MonitorConfigurationProposal.VisualDescription(
                title = "苹果摘要",
                catalogBinding = catalog.binding,
                modelProfileKey = VISUAL_PROFILE,
                packageId = VISUAL_PACKAGE,
                intentKey = "object.common.apple",
                targetId = "apple",
                displayText = "苹果",
                rule = AssistantPresenceRule(AssistantPresenceCondition.DISAPPEARS, 30),
            ),
            MonitorConfigurationProposal.StructuredReading(
                title = "范围摘要",
                catalogBinding = catalog.binding,
                modelProfileKey = READING_PROFILE,
                packageId = READING_PACKAGE,
                intentKey = "reading.numeric.outside",
                rule = AssistantReadingRule.Outside("12.5", "20", 5),
            ),
        )

        proposals.forEach { proposal ->
            val encoded = AssistantProposalSavedState.encode(proposal)
            val decoded = AssistantProposalSavedState.decode(encoded, proposal.kind, catalog)

            assertNotNull(decoded)
            assertEquals(proposal.kind, decoded?.kind)
            assertEquals(proposal.catalogBinding, decoded?.catalogBinding)
            assertEquals(proposal.modelProfileKey, decoded?.modelProfileKey)
            assertEquals(proposal.packageId, decoded?.packageId)
            assertEquals(proposal.intentKey, decoded?.intentKey)
            assertEquals(proposal.title, decoded?.title)
            when (proposal) {
                is MonitorConfigurationProposal.ReferenceImages ->
                    assertEquals(proposal.rule, (decoded as MonitorConfigurationProposal.ReferenceImages).rule)
                is MonitorConfigurationProposal.VisualDescription -> {
                    decoded as MonitorConfigurationProposal.VisualDescription
                    assertEquals(proposal.targetId, decoded.targetId)
                    assertEquals(proposal.displayText, decoded.displayText)
                    assertEquals(proposal.rule, decoded.rule)
                }
                is MonitorConfigurationProposal.StructuredReading ->
                    assertEquals(proposal.rule, (decoded as MonitorConfigurationProposal.StructuredReading).rule)
            }
        }
    }

    @Test
    fun navigationTargetHandleCarriesProposalAcrossARecreatedOwner() {
        val catalog = catalog()
        val proposal = MonitorConfigurationProposal.VisualDescription(
            title = "苹果摘要",
            catalogBinding = catalog.binding,
            modelProfileKey = VISUAL_PROFILE,
            packageId = VISUAL_PACKAGE,
            intentKey = "object.common.apple",
            targetId = "apple",
            displayText = "apple",
            rule = AssistantPresenceRule(AssistantPresenceCondition.APPEARS, 3),
        )
        val original = SavedStateHandle()
        AssistantProposalSavedState.write(original, proposal)
        val recreated = SavedStateHandle(
            original.keys().associateWith { key -> original.get<Any>(key) },
        )

        val decoded = AssistantProposalSavedState.decode(
            recreated,
            AssistantProposalKind.VISUAL_DESCRIPTION,
            catalog,
        ) as? MonitorConfigurationProposal.VisualDescription

        assertNotNull(decoded)
        assertEquals("apple", decoded?.targetId)
        assertEquals(proposal.rule, decoded?.rule)
        assertTrue(AssistantProposalSavedState.containsAny(recreated))
    }

    @Test
    fun missingOrUnexpectedFieldFailsClosed() {
        val catalog = catalog()
        val proposal = MonitorConfigurationProposal.ReferenceImages(
            title = "参考摘要",
            catalogBinding = catalog.binding,
            modelProfileKey = REFERENCE_PROFILE,
            packageId = REFERENCE_PACKAGE,
            intentKey = "visual.reference.object",
            rule = AssistantPresenceRule(AssistantPresenceCondition.APPEARS, 1),
        )
        val encoded = AssistantProposalSavedState.encode(proposal)
        val withoutPackage = encoded.filterKeys { !it.endsWith(".package") }
        val withUnknownField = encoded + ("assistant_route_proposal.unknown" to "value")

        assertNull(
            AssistantProposalSavedState.decode(
                withoutPackage,
                AssistantProposalKind.REFERENCE_IMAGES,
                catalog,
            ),
        )
        assertNull(
            AssistantProposalSavedState.decode(
                withUnknownField,
                AssistantProposalKind.REFERENCE_IMAGES,
                catalog,
            ),
        )
    }

    @Test
    fun wrongRouteOrChangedCatalogFailsClosed() {
        val catalog = catalog()
        val proposal = MonitorConfigurationProposal.VisualDescription(
            title = "苹果摘要",
            catalogBinding = catalog.binding,
            modelProfileKey = VISUAL_PROFILE,
            packageId = VISUAL_PACKAGE,
            intentKey = "object.common.apple",
            targetId = "apple",
            displayText = "苹果",
            rule = AssistantPresenceRule(AssistantPresenceCondition.APPEARS, 1),
        )
        val encoded = AssistantProposalSavedState.encode(proposal)
        val changedCatalog = catalog.copy(
            binding = catalog.binding.copy(catalogVersion = "2026.08.22.1"),
        )

        assertNull(
            AssistantProposalSavedState.decode(
                encoded,
                AssistantProposalKind.REFERENCE_IMAGES,
                catalog,
            ),
        )
        assertNull(
            AssistantProposalSavedState.decode(
                encoded,
                AssistantProposalKind.VISUAL_DESCRIPTION,
                changedCatalog,
            ),
        )
    }

    @Test
    fun objectCameraChildColdRecoveryRebuildsParentCreationStateBeforeUsingIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
        val catalog = catalog()
        val proposal = MonitorConfigurationProposal.VisualDescription(
            title = "苹果摘要",
            catalogBinding = catalog.binding,
            modelProfileKey = VISUAL_PROFILE,
            packageId = VISUAL_PACKAGE,
            intentKey = "object.common.apple",
            targetId = "apple",
            displayText = "苹果",
            rule = AssistantPresenceRule(AssistantPresenceCondition.REMAINS, 10),
        )
        val savedBeforeProcessDeath = SavedStateHandle().also {
            AssistantProposalSavedState.write(it, proposal)
        }
        val childRestoredParentHandle = savedBeforeProcessDeath.recreated()
        val restored = AssistantProposalSavedState.decode(
            childRestoredParentHandle,
            AssistantProposalKind.VISUAL_DESCRIPTION,
            catalog,
        ) as MonitorConfigurationProposal.VisualDescription

        val creationViewModel = ObjectDetectionCreationViewModel(
            catalogProvider = ObjectTargetCatalogProvider { catalog.objectClassDefinitions() },
            initialTargetId = restored.targetId,
            initialModelBinding = ObjectDetectionModelBinding(
                restored.modelProfileKey,
                restored.packageId,
                restored.intentKey,
            ),
            initialRule = restored.rule.toMonitorRule(),
        )
        advanceUntilIdle()

        assertEquals("apple", creationViewModel.target()?.targetId)
        assertEquals(VISUAL_PACKAGE, creationViewModel.modelBinding()?.packageId)
        assertEquals(
            MonitorRule.TargetPresence(PresenceRuleKind.REMAINS, 10),
            creationViewModel.presenceRule(),
        )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun referenceCameraChildColdRecoveryKeepsProposalBesideExistingDraftSession() {
        val catalog = catalog()
        val proposal = MonitorConfigurationProposal.ReferenceImages(
            title = "门口摘要",
            catalogBinding = catalog.binding,
            modelProfileKey = REFERENCE_PROFILE,
            packageId = REFERENCE_PACKAGE,
            intentKey = "visual.reference.object",
            rule = AssistantPresenceRule(AssistantPresenceCondition.DISAPPEARS, 30),
        )
        val savedBeforeProcessDeath = SavedStateHandle(
            mapOf("reference_creation_session_id" to "draft-session-1"),
        ).also { AssistantProposalSavedState.write(it, proposal) }
        val childRestoredParentHandle = savedBeforeProcessDeath.recreated()

        val restored = AssistantProposalSavedState.decode(
            childRestoredParentHandle,
            AssistantProposalKind.REFERENCE_IMAGES,
            catalog,
        ) as MonitorConfigurationProposal.ReferenceImages

        assertEquals("draft-session-1", childRestoredParentHandle["reference_creation_session_id"])
        assertEquals(REFERENCE_PROFILE, restored.modelProfileKey)
        assertEquals(REFERENCE_PACKAGE, restored.packageId)
        assertEquals("visual.reference.object", restored.intentKey)
        assertEquals(
            MonitorRule.TargetPresence(PresenceRuleKind.DISAPPEARS, 30),
            restored.rule.toMonitorRule(),
        )
    }

    private fun catalog(): AssistantCatalogSnapshot {
        val binding = AssistantCatalogBinding(
            catalogId = "be-your-eye-internal",
            catalogVersion = "2026.08.24.1",
            catalogSignedPayloadSha256 = "a".repeat(64),
        )
        return AssistantCatalogSnapshot(
            binding = binding,
            modelProfiles = listOf(
                AssistantModelProfile(
                    modelProfileKey = REFERENCE_PROFILE,
                    packageId = REFERENCE_PACKAGE,
                    kind = AssistantProposalKind.REFERENCE_IMAGES,
                    intentPatterns = listOf("visual.reference.*"),
                    applicableScenarios = listOf("固定机位参考目标"),
                    inapplicableScenarios = listOf("没有参考图片"),
                    inputRequirements = listOf("3 至 20 张参考图片"),
                    targets = emptyList(),
                    displayName = "Reference Embedder",
                ),
                AssistantModelProfile(
                    modelProfileKey = VISUAL_PROFILE,
                    packageId = VISUAL_PACKAGE,
                    kind = AssistantProposalKind.VISUAL_DESCRIPTION,
                    intentPatterns = listOf("object.common.*"),
                    applicableScenarios = listOf("固定机位常见物体"),
                    inapplicableScenarios = listOf("清单外目标"),
                    inputRequirements = listOf("目标清晰可见"),
                    targets = listOf(
                        AssistantTargetDescriptor(
                            targetId = "apple",
                            labelZhCn = "苹果",
                            labelEn = "apple",
                            aliases = listOf("苹果目标"),
                        ),
                    ),
                    displayName = "EfficientDet-Lite2",
                ),
                AssistantModelProfile(
                    modelProfileKey = READING_PROFILE,
                    packageId = READING_PACKAGE,
                    kind = AssistantProposalKind.STRUCTURED_READING,
                    intentPatterns = listOf("reading.numeric.*"),
                    applicableScenarios = listOf("固定机位数字显示"),
                    inapplicableScenarios = listOf("通用文字识别"),
                    inputRequirements = listOf("清晰单行数字"),
                    targets = emptyList(),
                    displayName = "PP-OCRv6",
                ),
            ),
            currentExactObjectTargetIds = setOf("apple"),
        )
    }

    private fun SavedStateHandle.recreated() = SavedStateHandle(
        keys().associateWith { key -> get<Any>(key) },
    )

    private companion object {
        const val REFERENCE_PROFILE = "reference_object_matching"
        const val REFERENCE_PACKAGE = "similarity_mediapipe_mobilenet_v3_large_v1"
        const val VISUAL_PROFILE = "common_objects_tensorflow_efficientdet_lite2"
        const val VISUAL_PACKAGE = "efficientdet_lite2_object_v1"
        const val READING_PROFILE = "numeric_display_reading"
        const val READING_PACKAGE = "numeric_reader_ppocrv6_medium_v1"
    }
}
