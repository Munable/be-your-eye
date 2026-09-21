package app.beyoureyes.monitor.service.monitoring

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingBaselinePromptStoreTest {
    @Test
    fun `prompt is claimable exactly once per task`() {
        val dir = Files.createTempDirectory("baseline-prompt").toFile()
        val store = ReadingBaselinePromptStore(dir)

        assertTrue(store.claim("018f0000-0000-7000-8000-000000000001"))
        assertFalse(store.claim("018f0000-0000-7000-8000-000000000001"))
        assertFalse(store.claim("018f0000-0000-7000-8000-000000000001"))
        assertTrue(store.claim("018f0000-0000-7000-8000-000000000002"))

        dir.deleteRecursively()
    }

    @Test
    fun `claim survives process restart through the marker file`() {
        val dir = Files.createTempDirectory("baseline-prompt").toFile()

        assertTrue(ReadingBaselinePromptStore(dir).claim("018f0000-0000-7000-8000-000000000003"))
        assertFalse(ReadingBaselinePromptStore(dir).claim("018f0000-0000-7000-8000-000000000003"))

        dir.deleteRecursively()
    }
}
