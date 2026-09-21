package app.beyoureyes.monitor.feature.subscription

import app.beyoureyes.core.vision.BuildChannel
import org.junit.Assert.*
import org.junit.Test

class LocalUseAccessTest {
    @Test fun `Community local use survives every unavailable cloud state without granting cloud access`() {
        val states = listOf(ProductAccessState.Initializing, ProductAccessState.SignedOut) +
            ProductLockReason.entries.map { ProductAccessState.Locked(it) }
        states.forEach { cloud ->
            assertEquals(ProductAccessDecision.GRANTED, localUseAccessDecision(BuildChannel.COMMUNITY, cloud))
            assertFalse(cloud.isGranted())
            assertNotEquals(ProductAccessDecision.GRANTED, productAccessDecision(cloud))
            assertNotEquals(ProductAccessDecision.GRANTED, localUseAccessDecision(BuildChannel.COMMERCIAL, cloud))
            assertNotEquals(ProductAccessDecision.GRANTED, localUseAccessDecision(BuildChannel.INTERNAL_EVALUATION, cloud))
        }
    }
}
