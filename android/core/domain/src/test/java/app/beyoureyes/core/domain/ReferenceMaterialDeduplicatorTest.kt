package app.beyoureyes.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceMaterialDeduplicatorTest {
    @Test
    fun `only identical canonical image bytes are duplicates`() {
        val accepted = material(sha = "a".repeat(64))

        assertTrue(
            ReferenceMaterialDeduplicator.isDuplicate(
                material(sha = accepted.exactSha256, differenceHash = 7L),
                listOf(accepted),
            ),
        )
        assertFalse(
            ReferenceMaterialDeduplicator.isDuplicate(
                material(sha = "b".repeat(64)),
                listOf(accepted),
            ),
        )
    }

    private fun material(
        sha: String,
        differenceHash: Long = 0L,
    ) = ReferenceMaterial(
        sourceUri = "file:///reference-$sha.jpg",
        exactSha256 = sha,
        differenceHash = differenceHash,
        meanRed = 100,
        meanGreen = 100,
        meanBlue = 100,
        width = 100,
        height = 100,
    )
}
