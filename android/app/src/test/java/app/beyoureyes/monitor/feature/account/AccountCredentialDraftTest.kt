package app.beyoureyes.monitor.feature.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCredentialDraftTest {
    @Test
    fun `email validation mirrors the cloud service contract`() {
        listOf(
            "name@example.com",
            " user+monitor@example.co.uk ",
            "a@b.co",
        ).forEach { email ->
            assertTrue(email, AccountCredentialDraft(email, "password").emailValid)
        }

        listOf(
            "",
            "name",
            "name@example",
            "name example@example.com",
            "name@@example.com",
            "name@example.",
            "a".repeat(310) + "@example.com",
        ).forEach { email ->
            assertFalse(email, AccountCredentialDraft(email, "password").emailValid)
        }
    }

    @Test
    fun `password validation keeps the existing eight to two hundred fifty six contract`() {
        assertFalse(AccountCredentialDraft("name@example.com", "a".repeat(7)).passwordValid)
        assertTrue(AccountCredentialDraft("name@example.com", "a".repeat(8)).passwordValid)
        assertTrue(AccountCredentialDraft("name@example.com", "a".repeat(256)).passwordValid)
        assertFalse(AccountCredentialDraft("name@example.com", "a".repeat(257)).passwordValid)
    }

    @Test
    fun `clearing a credential draft removes both fields and disables submission`() {
        val cleared = AccountCredentialDraft("name@example.com", "password").cleared()

        assertTrue(cleared.email.isEmpty())
        assertTrue(cleared.password.isEmpty())
        assertFalse(cleared.canSubmit)
    }
}
