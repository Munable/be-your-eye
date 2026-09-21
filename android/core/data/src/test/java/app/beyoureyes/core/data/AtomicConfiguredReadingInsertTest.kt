package app.beyoureyes.core.data

import org.junit.Assert.assertThrows
import org.junit.Test

class AtomicConfiguredReadingInsertTest {
    @Test
    fun `accepts one matching initial reading and sampling generation`() {
        requireAtomicConfiguredReadingInsert(task(), sampling())
    }

    @Test
    fun `accepts one matching initial reference and sampling generation`() {
        requireAtomicConfiguredReferenceInsert(
            task(targetMode = "reference_images", pointer = REFERENCE_POINTER),
            sampling(pointer = REFERENCE_POINTER),
        )
    }

    @Test
    fun `rejects a different task identity before Room is called`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireAtomicConfiguredReadingInsert(
                task(),
                sampling(taskId = "01900000-0000-7000-8000-000000000002"),
            )
        }
    }

    @Test
    fun `rejects a different immutable runtime pointer before Room is called`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireAtomicConfiguredReadingInsert(
                task(),
                sampling(
                    pointer = ModelPackagePointer(
                        ModelPackageIdentity("other_reader", "1.0.0"),
                        "d".repeat(64),
                    ),
                ),
            )
        }
    }

    @Test
    fun `rejects a noninitial revision before Room is called`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireAtomicConfiguredReadingInsert(
                task().copy(revision = 2),
                sampling().copy(taskRevision = 2),
            )
        }
    }

    @Test
    fun `reference entry point rejects a reading row`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireAtomicConfiguredReferenceInsert(task(), sampling())
        }
    }

    private fun task(
        targetMode: String = "structured_reading",
        pointer: ModelPackagePointer = POINTER,
    ) = StoredLocalTask(
        taskId = TASK_ID,
        revision = 1,
        title = "监控 1",
        targetMode = targetMode,
        targetJson = "configured-target",
        ruleJson = "configured-rule",
        materialRefsJson = if (targetMode == "reference_images") "[1,2,3]" else "[]",
        createdAtEpochMillis = 1,
        runtimePackagePointer = pointer,
    )

    private fun sampling(
        taskId: String = TASK_ID,
        pointer: ModelPackagePointer = POINTER,
    ) = ResolvedSamplingConfig(
        taskId = taskId,
        taskRevision = 1,
        catalogVersion = "2026.08.24.1",
        capabilityId = if (pointer == REFERENCE_POINTER) "visual_target" else "structured_reading",
        modelProfileKey = if (pointer == REFERENCE_POINTER) {
            "reference_object_matching"
        } else {
            "numeric_display_reading"
        },
        recipeId = if (pointer == REFERENCE_POINTER) {
            "reference_match_general_v1"
        } else {
            "reading_pipeline_general_v1"
        },
        intentKey = if (pointer == REFERENCE_POINTER) {
            "visual.reference.user_target"
        } else {
            "reading.numeric.display"
        },
        packagePointer = pointer,
        artifactIdentitySha256 = "b".repeat(64),
        deviceFingerprintSha256 = "c".repeat(64),
        intervalMillis = 750,
        manifestMinimumIntervalMillis = 250,
        manifestMaximumIntervalMillis = 2_000,
        adaptiveEnabled = false,
    )

    private companion object {
        const val TASK_ID = "01900000-0000-7000-8000-000000000001"
        val POINTER = ModelPackagePointer(
            ModelPackageIdentity("reader", "1.0.0"),
            "a".repeat(64),
        )
        val REFERENCE_POINTER = ModelPackagePointer(
            ModelPackageIdentity("reference_matcher", "2.1.0"),
            "e".repeat(64),
        )
    }
}
