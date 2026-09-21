package app.beyoureyes.monitor.service.monitoring

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoHitReminderStoreTest {
    @Test
    fun `reminder is claimable exactly once per task`() {
        val dir = Files.createTempDirectory("no-hit-reminder").toFile()
        val store = NoHitReminderStore(dir)

        assertTrue(store.claim("018f0000-0000-7000-8000-000000000001"))
        assertFalse(store.claim("018f0000-0000-7000-8000-000000000001"))
        assertFalse(store.claim("018f0000-0000-7000-8000-000000000001"))
        assertTrue(store.claim("018f0000-0000-7000-8000-000000000002"))

        dir.deleteRecursively()
    }

    @Test
    fun `claim survives process restart through the marker file`() {
        val dir = Files.createTempDirectory("no-hit-reminder").toFile()

        assertTrue(NoHitReminderStore(dir).claim("018f0000-0000-7000-8000-000000000003"))
        assertFalse(NoHitReminderStore(dir).claim("018f0000-0000-7000-8000-000000000003"))

        dir.deleteRecursively()
    }
}

class NoHitReminderPolicyTest {
    @Test
    fun `reminder is due only after the configured age`() {
        val created = 1_000_000L

        assertFalse(
            NoHitReminderPolicy.reminderDue(
                createdAtEpochMillis = created,
                nowEpochMillis = created + NoHitReminderPolicy.REMINDER_AGE_MILLIS - 1,
            ),
        )
        assertTrue(
            NoHitReminderPolicy.reminderDue(
                createdAtEpochMillis = created,
                nowEpochMillis = created + NoHitReminderPolicy.REMINDER_AGE_MILLIS,
            ),
        )
        assertTrue(
            NoHitReminderPolicy.reminderDue(
                createdAtEpochMillis = created,
                nowEpochMillis = created + NoHitReminderPolicy.REMINDER_AGE_MILLIS * 7,
            ),
        )
    }

    @Test
    fun `checks run immediately once and then respect the interval`() {
        val start = 5_000L

        assertTrue(NoHitReminderPolicy.checkDue(null, start))
        assertFalse(
            NoHitReminderPolicy.checkDue(
                start,
                start + NoHitReminderPolicy.CHECK_INTERVAL_MILLIS - 1,
            ),
        )
        assertTrue(
            NoHitReminderPolicy.checkDue(
                start,
                start + NoHitReminderPolicy.CHECK_INTERVAL_MILLIS,
            ),
        )
    }
}
