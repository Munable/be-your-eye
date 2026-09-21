package app.beyoureyes.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReferenceEventSnapshotStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reconcile deletes orphan JPEG and retains its matched event JPEG`() {
        val filesDir = temporaryFolder.newFolder("files")
        val taskDirectory = filesDir.resolve("reference-event-snapshots-v1/$TASK_ID")
        assertTrue(taskDirectory.mkdirs())
        val matched = taskDirectory.resolve("$MATCHED_EVENT_ID.jpg").apply {
            writeBytes(byteArrayOf(1))
        }
        val orphan = taskDirectory.resolve("$ORPHAN_EVENT_ID.jpg").apply {
            writeBytes(byteArrayOf(2))
        }

        val result = ReferenceEventSnapshotStore.openAppPrivate(filesDir).reconcile(
            createdBeforeOrAtEpochMillis = System.currentTimeMillis() + 1_000,
            maximumFiles = 10,
        ) { taskId, eventId ->
            taskId == TASK_ID && eventId == MATCHED_EVENT_ID
        }

        assertEquals(2, result.inspectedFiles)
        assertEquals(1, result.deletedFiles)
        assertFalse(result.reachedLimit)
        assertTrue(matched.isFile)
        assertFalse(orphan.exists())
    }

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000000"
        const val MATCHED_EVENT_ID = "01900000-0000-7000-8000-000000000001"
        const val ORPHAN_EVENT_ID = "01900000-0000-7000-8000-000000000002"
    }
}
