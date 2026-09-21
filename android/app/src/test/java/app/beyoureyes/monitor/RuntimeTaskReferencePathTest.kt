package app.beyoureyes.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTaskReferencePathTest {
    @Test
    fun `monitor-owned generation reference files are accepted`() {
        assertTrue(
            isMonitorReferencePath(
                TASK_ID,
                "reference-images/$TASK_ID/$GENERATION/reference-3.jpg",
            ),
        )
    }

    @Test
    fun `old extension cross-task traversal absolute malformed and nested paths are rejected`() {
        assertFalse(
            isMonitorReferencePath(
                TASK_ID,
                "reference-images/$TASK_ID/$GENERATION/reference-3.img",
            ),
        )
        assertFalse(
            isMonitorReferencePath(
                TASK_ID,
                "reference-images/$TASK_ID/reference-1.jpg",
            ),
        )
        assertFalse(
            isMonitorReferencePath(
                TASK_ID,
                "reference-images/$OTHER_TASK_ID/$GENERATION/reference-1.jpg",
            ),
        )
        assertFalse(
            isMonitorReferencePath(
                TASK_ID,
                "reference-images/$TASK_ID/../$GENERATION/reference-1.jpg",
            ),
        )
        assertFalse(isMonitorReferencePath(TASK_ID, "/tmp/reference-1.jpg"))
        assertFalse(
            isMonitorReferencePath(
                TASK_ID,
                "reference-images/$TASK_ID/generation-not-a-uuid/reference-1.jpg",
            ),
        )
        assertFalse(
            isMonitorReferencePath(
                TASK_ID,
                "reference-images/$TASK_ID/$GENERATION/nested/reference-1.jpg",
            ),
        )
    }

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000001"
        const val OTHER_TASK_ID = "01900000-0000-7000-8000-000000000002"
        const val GENERATION = "generation-123e4567-e89b-42d3-a456-426614174000"
    }
}
