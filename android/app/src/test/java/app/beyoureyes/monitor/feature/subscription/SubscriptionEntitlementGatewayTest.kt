package app.beyoureyes.monitor.feature.subscription

import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionEntitlementGatewayTest {
    @Test
    fun `status decodes exact active entitlement`() = runTest {
        val gateway = gateway(
            200,
            """{"product_id":"be_your_eye_pro","active":true,"state":"SUBSCRIPTION_STATE_ACTIVE","expires_at":"2026-09-23T00:00:00Z","refresh_after":"2026-08-24T00:00:00Z"}""",
        )
        val result = gateway.status() as SubscriptionEntitlementResult.Completed
        assertTrue(result.entitlement.active)
        assertEquals("SUBSCRIPTION_STATE_ACTIVE", result.entitlement.state)
    }

    @Test
    fun `verify sends product and token without persisting it`() = runTest {
        var body = ""
        val gateway = SupabaseSubscriptionEntitlementGateway(
            endpointUrl = "https://project.supabase.co/functions/v1/play-entitlement",
            publishableKey = "publishable",
            tokenProvider = SubscriptionTokenProvider { "session" },
            transport = SubscriptionHttpTransport { request ->
                body = request.body.decodeToString()
                SubscriptionHttpResponse(
                    200,
                    ByteArrayInputStream(
                        """{"product_id":"be_your_eye_pro","active":false,"state":"SUBSCRIPTION_STATE_PENDING","expires_at":null,"refresh_after":null}""".encodeToByteArray(),
                    ),
                )
            },
        )
        val token = "abcdefghijklmnopqrstuvwxyz0123456789"
        val result = gateway.verifyPurchase(token) as SubscriptionEntitlementResult.Completed
        assertFalse(result.entitlement.active)
        assertTrue(body.contains(token))
        assertTrue(body.contains(PRO_SUBSCRIPTION_PRODUCT_ID))
    }

    @Test
    fun `unknown response field and non boolean active fail closed`() = runTest {
        for (response in listOf(
            """{"product_id":"be_your_eye_pro","active":true,"state":"none","expires_at":null,"refresh_after":null,"extra":1}""",
            """{"product_id":"be_your_eye_pro","active":"true","state":"none","expires_at":null,"refresh_after":null}""",
        )) {
            assertEquals(SubscriptionEntitlementResult.Unavailable, gateway(200, response).status())
        }
    }

    @Test
    fun `missing session and server errors are explicit`() = runTest {
        val signedOut = SupabaseSubscriptionEntitlementGateway(
            "https://project.supabase.co/functions/v1/play-entitlement",
            "publishable",
            SubscriptionTokenProvider { null },
            SubscriptionHttpTransport { error("not called") },
        )
        assertEquals(SubscriptionEntitlementResult.SignInRequired, signedOut.status())
        assertEquals(SubscriptionEntitlementResult.Unavailable, gateway(503, "{}").status())
    }

    private fun gateway(status: Int, body: String) = SupabaseSubscriptionEntitlementGateway(
        endpointUrl = "https://project.supabase.co/functions/v1/play-entitlement",
        publishableKey = "publishable",
        tokenProvider = SubscriptionTokenProvider { "session" },
        transport = SubscriptionHttpTransport {
            SubscriptionHttpResponse(status, ByteArrayInputStream(body.encodeToByteArray()))
        },
    )
}
