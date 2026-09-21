package app.beyoureyes.core.data.reference

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReferenceDeletionJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `staged references can be restored after database deletion fails`() {
        val filesDir = temporaryFolder.newFolder("files-restore")
        val source = referenceDirectory(filesDir).apply { mkdirs() }
        val expected = byteArrayOf(1, 2, 3, 4)
        source.resolve("reference-1.img").writeBytes(expected)
        val journal = ReferenceDeletionJournal(filesDir)

        val entry = checkNotNull(journal.stage(TASK_ID))
        assertFalse(source.exists())
        assertTrue(journal.entries().single().taskId == TASK_ID)

        journal.restore(entry)

        assertArrayEquals(expected, source.resolve("reference-1.img").readBytes())
        assertTrue(journal.entries().isEmpty())
    }

    @Test
    fun `staged references are discarded only after database task is gone`() {
        val filesDir = temporaryFolder.newFolder("files-discard")
        val source = referenceDirectory(filesDir).apply { mkdirs() }
        source.resolve("reference-1.img").writeText("private")
        val journal = ReferenceDeletionJournal(filesDir)

        val entry = checkNotNull(journal.stage(TASK_ID))
        journal.discard(entry)

        assertFalse(source.exists())
        assertTrue(journal.entries().isEmpty())
    }

    @Test
    fun `discard does not follow a symbolic link outside app journal`() {
        val filesDir = temporaryFolder.newFolder("files-link")
        val outside = temporaryFolder.newFile("outside").apply { writeText("keep") }
        val source = referenceDirectory(filesDir).apply { mkdirs() }
        source.resolve("reference-1.img").writeText("private")
        val journal = ReferenceDeletionJournal(filesDir)
        val entry = checkNotNull(journal.stage(TASK_ID))
        Files.createSymbolicLink(entry.staged.toPath().resolve("outside-link"), outside.toPath())

        journal.discard(entry)

        assertTrue(outside.exists())
        assertTrue(outside.readText() == "keep")
    }

    @Test
    fun `invalid task identity and duplicate staging fail closed`() {
        val filesDir = temporaryFolder.newFolder("files-invalid")
        val journal = ReferenceDeletionJournal(filesDir)
        assertThrows(IllegalArgumentException::class.java) { journal.stage("../escape") }

        referenceDirectory(filesDir).apply { mkdirs() }
        val staged = checkNotNull(journal.stage(TASK_ID))
        val recreated = referenceDirectory(filesDir).apply { mkdirs() }
        assertThrows(IllegalStateException::class.java) { journal.stage(TASK_ID) }
        assertTrue(recreated.delete())
        journal.restore(staged)
    }

    private fun referenceDirectory(filesDir: java.io.File) =
        filesDir.resolve("reference-images/$TASK_ID")

    private companion object {
        const val TASK_ID = "018f1e44-9d7a-7f31-8c0a-1234567890ab"
    }
}
