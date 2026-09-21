package app.beyoureyes.monitor.service.monitoring

import app.beyoureyes.core.domain.CountOperator
import app.beyoureyes.core.domain.ReadingOperator
import app.beyoureyes.core.domain.RuntimeMonitorRule
import app.beyoureyes.core.vision.RecipeFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringRuleFamilyCompatibilityTest {
    @Test
    fun `only the exact supported rule and runtime family pairs are compatible`() {
        val rules = mapOf(
            "presence_episode" to RuntimeMonitorRule.PresenceEpisode(),
            "presence" to RuntimeMonitorRule.Presence(durationMillis = 1_000),
            "absence" to RuntimeMonitorRule.Absence(durationMillis = 1_000),
            "object_count" to RuntimeMonitorRule.ObjectCount(
                operator = CountOperator.GTE,
                count = 1,
                durationMillis = 1_000,
                cooldownMillis = 0,
            ),
            "reading" to RuntimeMonitorRule.ReadingThreshold.Single(
                operator = ReadingOperator.GTE,
                thresholdDecimal = "1",
                durationMillis = 1_000,
            ),
            "state_transition" to RuntimeMonitorRule.StateTransition(
                fromState = "off",
                toState = "on",
                stableFrames = 2,
                cooldownMillis = 0,
            ),
        )
        val supported = setOf(
            "presence_episode" to RecipeFamily.SIMILARITY_MATCH_V1,
            "presence_episode" to RecipeFamily.OBJECT_DETECTION_V1,
            "presence" to RecipeFamily.SIMILARITY_MATCH_V1,
            "presence" to RecipeFamily.OBJECT_DETECTION_V1,
            "absence" to RecipeFamily.SIMILARITY_MATCH_V1,
            "absence" to RecipeFamily.OBJECT_DETECTION_V1,
            "reading" to RecipeFamily.READING_PIPELINE_V1,
        )

        rules.forEach { (name, rule) ->
            RecipeFamily.entries.forEach { family ->
                assertEquals(
                    "$name with ${family.wireValue}",
                    (name to family) in supported,
                    isRuleFamilyCompatible(rule, family),
                )
            }
        }
    }
}
