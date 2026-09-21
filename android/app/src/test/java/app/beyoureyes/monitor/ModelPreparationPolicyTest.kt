package app.beyoureyes.monitor

import app.beyoureyes.core.data.CatalogHumanReview
import app.beyoureyes.core.data.CatalogModelCard
import app.beyoureyes.core.data.CatalogOperationalCapability
import app.beyoureyes.core.data.CatalogRuntimeRecipe
import app.beyoureyes.core.domain.Observation
import app.beyoureyes.core.domain.UnavailableReason
import app.beyoureyes.core.vision.ClassMapTarget
import app.beyoureyes.core.vision.PipelineResult
import app.beyoureyes.core.vision.PipelineTimings
import app.beyoureyes.core.vision.RecipeFamily
import app.beyoureyes.core.vision.RuntimeActivationError
import app.beyoureyes.core.vision.TargetMode
import app.beyoureyes.core.vision.TargetProfile
import app.beyoureyes.monitor.feature.subscription.ProductAccessDecision
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelPreparationPolicyTest {
    @Test
    fun `product access rejection carries a precise account action`() {
        val signIn = requireNotNull(
            productAccessPreparationRejection(
                ProductAccessDecision.SIGN_IN_REQUIRED,
            ) { "sign in" },
        )
        val subscribe = requireNotNull(
            productAccessPreparationRejection(
                ProductAccessDecision.PRO_REQUIRED,
            ) { "subscribe" },
        )
        val verify = requireNotNull(
            productAccessPreparationRejection(
                ProductAccessDecision.VERIFICATION_REQUIRED,
            ) { "verify" },
        )

        assertEquals(ModelPreparationFailure.SIGN_IN_REQUIRED, signIn.failure)
        assertEquals(ModelPreparationFailure.SUBSCRIPTION_REQUIRED, subscribe.failure)
        assertEquals(ModelPreparationFailure.SUBSCRIPTION_VERIFICATION_REQUIRED, verify.failure)
        assertTrue(listOf(signIn, subscribe, verify).all { it.canOpenAccount })
        assertNull(productAccessPreparationRejection(ProductAccessDecision.GRANTED) { "" })
    }

    @Test
    fun `signed fallback skips an ineligible first package and selects the second`() {
        val recipe = recipe("object_detection_general_v1", "first_v1", "second_v1")
        assertEquals(
            "second_v1",
            ModelPreparationCandidatePolicy.firstEligible(
                capabilityRecipeIds = listOf(recipe.recipeId),
                runtimeRecipes = listOf(recipe),
                isEligible = { it.packageId == "second_v1" },
            )?.packageId,
        )
    }

    @Test
    fun `signed recipe order selects the first eligible package`() {
        val recipe = recipe("object_detection_general_v1", "primary_v1", "routed_v1")
        assertEquals(
            "primary_v1",
            ModelPreparationCandidatePolicy.firstEligible(
                capabilityRecipeIds = listOf(recipe.recipeId),
                runtimeRecipes = listOf(recipe),
                isEligible = { true },
            )?.packageId,
        )
        assertNull(
            ModelPreparationCandidatePolicy.firstEligible(
                capabilityRecipeIds = listOf(recipe.recipeId),
                runtimeRecipes = listOf(recipe),
                isEligible = { false },
            ),
        )
    }

    @Test
    fun `runtime recipe return order cannot change signed catalog priority`() {
        val lower = recipe("lower_recipe_v1", "lower_package_v1")
        val higher = recipe("higher_recipe_v1", "priority_b_v1", "priority_a_v1")
        val signedRecipePriority = listOf(higher.recipeId, lower.recipeId)
        val forward = ModelPreparationCandidatePolicy.firstEligible(
            signedRecipePriority,
            listOf(lower, higher),
        ) { true }
        val reversed = ModelPreparationCandidatePolicy.firstEligible(
            signedRecipePriority,
            listOf(higher, lower),
        ) { true }

        assertEquals("priority_b_v1", forward?.packageId)
        assertEquals(forward, reversed)
    }

    @Test
    fun `exact profile package intent and signed target coverage select the compatible package`() {
        val recipe = recipe("object_detection_general_v1", "alternate_v1", "coco_v1")
        val profile = operationalProfile(
            packageIds = setOf("alternate_v1", "coco_v1"),
            intentPatterns = setOf("object.common.*"),
        )
        val apple = TargetProfile.ObjectClass("apple", "苹果", "apple")
        val signedTargets = mapOf(
            "alternate_v1" to listOf(ClassMapTarget(4, "tomato", "番茄", "tomato")),
            "coco_v1" to listOf(ClassMapTarget(52, "apple", "苹果", "apple")),
        )

        val selected = ModelPreparationCandidatePolicy.firstEligible(
            capabilityRecipeIds = listOf(recipe.recipeId),
            runtimeRecipes = listOf(recipe),
        ) { candidate ->
            matchingOperationalModelProfile(
                operationalCapabilities = listOf(profile),
                modelProfileKey = COMMON_OBJECT_MODEL_PROFILE_KEY,
                capabilityId = "visual_target",
                recipeId = candidate.recipe.recipeId,
                packageId = candidate.packageId,
                intentKey = "object.common.apple",
            ) != null && exactSignedObjectTargetMatch(signedTargets[candidate.packageId], apple)
        }

        assertEquals("coco_v1", selected?.packageId)
    }

    @Test
    fun `manual object target resolves one signed profile and rejects ambiguous coverage`() {
        val commonRecipe = recipe("object_detection_general_v1", "coco_v1")
        val specialistRecipe = recipe("object_detection_specialist_v1", "alternate_v1")
        val common = operationalProfile(
            packageIds = setOf("coco_v1"),
            intentPatterns = setOf("object.common.*"),
            targetIds = setOf("apple", "shared_target"),
        )
        val specialist = operationalProfile(
            capabilityKey = "specialist_object_profile",
            recipeId = specialistRecipe.recipeId,
            packageIds = setOf("alternate_v1"),
            intentPatterns = setOf("object.specialist.*"),
            targetIds = setOf("specialist_target", "shared_target"),
        )
        val routes = listOf(common, specialist)
        val recipes = listOf(commonRecipe, specialistRecipe)
        val recipeIds = recipes.map(CatalogRuntimeRecipe::recipeId)
        val activePackages = setOf("coco_v1", "alternate_v1")

        assertEquals(
            ManualObjectModelRouteResolution.Resolved(
                ManualObjectModelRoute(
                    modelProfileKey = COMMON_OBJECT_MODEL_PROFILE_KEY,
                    recipeId = commonRecipe.recipeId,
                    intentKey = "object.common.apple",
                    candidatePackageIds = setOf("coco_v1"),
                ),
            ),
            resolveUniqueManualObjectModelRoute(
                routes,
                recipes,
                recipeIds,
                activePackages,
                targetId = "apple",
            ),
        )
        assertEquals(
            ManualObjectModelRouteResolution.Resolved(
                ManualObjectModelRoute(
                    modelProfileKey = "specialist_object_profile",
                    recipeId = specialistRecipe.recipeId,
                    intentKey = "object.specialist.specialist_target",
                    candidatePackageIds = setOf("alternate_v1"),
                ),
            ),
            resolveUniqueManualObjectModelRoute(
                routes,
                recipes,
                recipeIds,
                activePackages,
                targetId = "specialist_target",
            ),
        )
        assertEquals(
            ManualObjectModelRouteResolution.Ambiguous,
            resolveUniqueManualObjectModelRoute(
                routes,
                recipes,
                recipeIds,
                activePackages,
                targetId = "shared_target",
            ),
        )
        assertEquals(
            ManualObjectModelRouteResolution.Resolved(
                ManualObjectModelRoute(
                    modelProfileKey = "specialist_object_profile",
                    recipeId = specialistRecipe.recipeId,
                    intentKey = "object.specialist.shared_target",
                    candidatePackageIds = setOf("alternate_v1"),
                ),
            ),
            resolveUniqueManualObjectModelRoute(
                routes,
                recipes,
                recipeIds,
                activePackages,
                targetId = "shared_target",
                requiredPackageId = "alternate_v1",
            ),
        )
    }

    @Test
    fun `manual package selection keeps multiple eligible candidates ambiguous`() {
        val recipe = recipe("object_detection_general_v1", "first_v1", "second_v1")

        assertNull(
            ModelPreparationCandidatePolicy.allEligible(
                capabilityRecipeIds = listOf(recipe.recipeId),
                runtimeRecipes = listOf(recipe),
                isEligible = { true },
            ).singleOrNull(),
        )
    }

    @Test
    fun `object target labels must exactly match the signed Manifest row`() {
        val signed = listOf(ClassMapTarget(52, "apple", "苹果", "apple"))

        assertEquals(
            true,
            exactSignedObjectTargetMatch(
                signed,
                TargetProfile.ObjectClass("apple", "苹果", "apple"),
            ),
        )
        assertEquals(
            false,
            exactSignedObjectTargetMatch(
                signed,
                TargetProfile.ObjectClass("apple", "红苹果", "apple"),
            ),
        )
        assertEquals(
            false,
            exactSignedObjectTargetMatch(
                signed,
                TargetProfile.ObjectClass("banana", "香蕉", "banana"),
            ),
        )
    }

    @Test
    fun `internal wildcard and unlisted package never authorize model routing`() {
        val profile = operationalProfile(
            packageIds = setOf("coco_v1"),
            intentPatterns = setOf("object.common.*"),
        )

        assertNull(
            matchingOperationalModelProfile(
                listOf(profile),
                COMMON_OBJECT_MODEL_PROFILE_KEY,
                "visual_target",
                "object_detection_general_v1",
                "alternate_v1",
                "object.common.apple",
            ),
        )
        assertNull(
            matchingOperationalModelProfile(
                listOf(profile),
                COMMON_OBJECT_MODEL_PROFILE_KEY,
                "visual_target",
                "object_detection_general_v1",
                "coco_v1",
                "object.common.*",
            ),
        )
    }

    @Test
    fun `physical memory maps to explicit marketed RAM classes`() {
        assertEquals(4_096, MarketedRamClass.fromPhysicalBytes(gib(3)))
        assertEquals(6_144, MarketedRamClass.fromPhysicalBytes(gib(5)))
        assertEquals(8_192, MarketedRamClass.fromPhysicalBytes(gib(7)))
        assertEquals(12_288, MarketedRamClass.fromPhysicalBytes(gib(10)))
        assertEquals(16_384, MarketedRamClass.fromPhysicalBytes(gib(14)))
    }

    @Test
    fun `representative nominal eight GB is accepted while six GB stays below eight GB class`() {
        assertEquals(8_192, MarketedRamClass.fromPhysicalBytes(mib(7_300)))
        assertEquals(6_144, MarketedRamClass.fromPhysicalBytes(mib(5_600)))
    }

    @Test
    fun `bucket boundaries do not round a six GB class into eight GB`() {
        assertEquals(6_144, MarketedRamClass.fromPhysicalBytes(gib(7) - 1))
        assertEquals(8_192, MarketedRamClass.fromPhysicalBytes(gib(7)))
        assertEquals(8_192, MarketedRamClass.fromPhysicalBytes(gib(10) - 1))
        assertEquals(12_288, MarketedRamClass.fromPhysicalBytes(gib(10)))
    }

    @Test
    fun `activation failure gives one concise actionable message`() {
        assertEquals(
            R.string.model_self_test_failed,
            modelSelfTestFailureResource(
                setOf(RuntimeActivationError.COMPONENT_CREATION_FAILED),
            ),
        )
    }

    @Test
    fun `content neutral smoke can never activate a reading package`() {
        val candidate = pipelineResult(
            Observation.Reading(
                text = "12.3",
                valueDecimal = "12.3",
                stable = false,
                sourceSequence = 1,
                confidence = 0.8f,
            ),
            adapterCompleted = true,
        )

        assertEquals(
            false,
            minimumArtifactSmokeTestPassed(RecipeFamily.READING_PIPELINE_V1, candidate),
        )
    }

    @Test
    fun `reference smoke accepts a completed unavailable result without judging user photos`() {
        val unavailable = Observation.Unavailable(
            reason = UnavailableReason.LOW_QUALITY,
            diagnosticCode = "similarity_ambiguous",
            sourceSequence = 1,
        )

        assertEquals(
            true,
            minimumArtifactSmokeTestPassed(
                RecipeFamily.SIMILARITY_MATCH_V1,
                pipelineResult(unavailable, adapterCompleted = true),
            ),
        )
        assertEquals(
            false,
            minimumArtifactSmokeTestPassed(
                RecipeFamily.SIMILARITY_MATCH_V1,
                pipelineResult(unavailable, adapterCompleted = false),
            ),
        )
        assertEquals(
            false,
            minimumArtifactSmokeTestPassed(
                RecipeFamily.SIMILARITY_MATCH_V1,
                pipelineResult(
                    Observation.Unavailable(
                        reason = UnavailableReason.INFERENCE_ERROR,
                        diagnosticCode = "backend_failed",
                        sourceSequence = 1,
                    ),
                    adapterCompleted = true,
                ),
            ),
        )
    }

    private fun pipelineResult(
        observation: Observation,
        adapterCompleted: Boolean,
    ) = PipelineResult(
        sourceSequence = observation.sourceSequence,
        monotonicTimeMillis = 0,
        capturedAtEpochMillis = null,
        observation = observation,
        timings = PipelineTimings(0, 0, 0, 0, 0),
        adapterCompleted = adapterCompleted,
    )

    private fun recipe(
        recipeId: String,
        vararg packageIds: String,
    ) = CatalogRuntimeRecipe(
        recipeId = recipeId,
        capabilityId = "visual_target",
        runtimeFamily = RecipeFamily.OBJECT_DETECTION_V1,
        promptModes = setOf(TargetMode.OBJECT_CLASS),
        candidatePackageIds = packageIds.toList(),
    )

    private fun operationalProfile(
        packageIds: Set<String>,
        intentPatterns: Set<String>,
        capabilityKey: String = COMMON_OBJECT_MODEL_PROFILE_KEY,
        recipeId: String = "object_detection_general_v1",
        targetIds: Set<String> = setOf("apple", "shared_target"),
    ) = CatalogOperationalCapability(
        capabilityKey = capabilityKey,
        capabilityId = "visual_target",
        recipeId = recipeId,
        modelCard = CatalogModelCard(
            providerId = "tensorflow",
            providerName = "TensorFlow",
            modelName = "EfficientDet-Lite2",
            modelHomeUrl = "https://www.tensorflow.org/lite/examples/object_detection/overview",
            modelKind = "general",
        ),
        intentPatterns = intentPatterns,
        targetIds = targetIds,
        professionalDomain = "general",
        displayName = "常见物体检测",
        status = "internal-evaluation",
        applicableScenarios = setOf("fixed camera"),
        inapplicableScenarios = setOf("unsupported targets"),
        inputRequirements = setOf("stable view"),
        deviceProfileIds = setOf(LAUNCH_DEVICE_PROFILE_ID),
        packageIds = packageIds,
        humanReview = CatalogHumanReview(
            reviewedAt = "2026-08-21T00:00:00Z",
            licenseConclusion = "approved-for-internal-evaluation",
            evidenceRefs = setOf("model-tools/v3/reviews/example.md"),
        ),
    )

    private fun mib(value: Long): Long = value * 1_024L * 1_024L
    private fun gib(value: Long): Long = value * 1_024L * 1_024L * 1_024L
}
