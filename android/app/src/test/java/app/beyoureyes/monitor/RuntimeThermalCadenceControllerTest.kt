package app.beyoureyes.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeThermalCadenceControllerTest {
    @Test
    fun `setup slows at severe pauses at critical and resumes only at a safe level`() {
        val severe = nextSetupThermalMode(RuntimeThermalMode.NORMAL, RuntimeThermalLevel.SEVERE)
        val critical = nextSetupThermalMode(severe, RuntimeThermalLevel.CRITICAL)

        assertEquals(RuntimeThermalMode.LIMITED, severe)
        assertEquals(RuntimeThermalMode.PAUSED, critical)
        assertEquals(
            RuntimeThermalMode.PAUSED,
            nextSetupThermalMode(critical, RuntimeThermalLevel.SEVERE),
        )
        assertEquals(
            RuntimeThermalMode.PAUSED,
            nextSetupThermalMode(critical, RuntimeThermalLevel.UNKNOWN),
        )
        assertEquals(
            RuntimeThermalMode.NORMAL,
            nextSetupThermalMode(critical, RuntimeThermalLevel.MODERATE),
        )
    }

    @Test
    fun `setup conservative cadence doubles the signed default within its maximum`() {
        fun interval(default: Long, maximum: Long) = setupThermalSamplingIntervalMillis(
            mode = RuntimeThermalMode.LIMITED,
            fastestIntervalMillis = default,
            signedDefaultIntervalMillis = default,
            signedMaximumIntervalMillis = maximum,
            adaptationAllowed = true,
        )

        assertEquals(200L, interval(default = 100, maximum = 2_000))
        assertEquals(1_000L, interval(default = 500, maximum = 2_000))
        assertEquals(500L, interval(default = 500, maximum = 500))
    }

    @Test
    fun `normal adaptive cadence bounds sustained processing duty cycle`() {
        val controller = controller()

        val initial = controller.update(0, RuntimeThermalLevel.NONE)
        controller.recordProcessingDuration(230)
        val measured = controller.update(1, RuntimeThermalLevel.LIGHT)

        assertEquals(33L, initial.intervalMillis)
        assertEquals(1_840L, measured.intervalMillis)
        assertEquals(RuntimeThermalMode.NORMAL, measured.mode)
        assertTrue(measured.intervalChanged)
    }

    @Test
    fun `normal workload baseline is fixed so inference jitter cannot reset rule evidence`() {
        val controller = controller()
        controller.update(0, RuntimeThermalLevel.NONE)
        controller.recordProcessingDuration(200)

        val firstAdaptation = controller.update(1, RuntimeThermalLevel.NONE)
        controller.recordProcessingDuration(140)
        val fasterFrame = controller.update(2, RuntimeThermalLevel.LIGHT)
        controller.recordProcessingDuration(280)
        val slowerFrame = controller.update(3, RuntimeThermalLevel.MODERATE)

        assertEquals(1_600L, firstAdaptation.intervalMillis)
        assertTrue(firstAdaptation.intervalChanged)
        assertEquals(1_600L, fasterFrame.intervalMillis)
        assertFalse(fasterFrame.intervalChanged)
        assertEquals(1_600L, slowerFrame.intervalMillis)
        assertFalse(slowerFrame.intervalChanged)
    }

    @Test
    fun `severe uses conservative measured cadence and critical pauses`() {
        val controller = controller()
        controller.recordProcessingDuration(230)

        val severe = controller.update(0, RuntimeThermalLevel.SEVERE)
        val critical = controller.update(1, RuntimeThermalLevel.CRITICAL)

        assertEquals(2_760L, severe.intervalMillis)
        assertEquals(RuntimeThermalMode.LIMITED, severe.mode)
        assertTrue(severe.intervalChanged)
        assertEquals(2_760L, critical.intervalMillis)
        assertEquals(RuntimeThermalMode.PAUSED, critical.mode)
        assertFalse(critical.intervalChanged)
        assertTrue(critical.modeChanged)
    }

    @Test
    fun `throttled session needs thirty continuous normal seconds to recover`() {
        val controller = controller()
        controller.recordProcessingDuration(230)
        controller.update(0, RuntimeThermalLevel.SEVERE)

        val resumed = controller.update(1_000, RuntimeThermalLevel.NONE)
        val almost = controller.update(30_999, RuntimeThermalLevel.MODERATE)
        val recovered = controller.update(31_000, RuntimeThermalLevel.LIGHT)

        assertEquals(RuntimeThermalMode.LIMITED, resumed.mode)
        assertEquals(2_760L, resumed.intervalMillis)
        assertEquals(RuntimeThermalMode.LIMITED, almost.mode)
        assertEquals(RuntimeThermalMode.NORMAL, recovered.mode)
        assertEquals(1_840L, recovered.intervalMillis)
        assertTrue(recovered.intervalChanged)
    }

    @Test
    fun `unknown and clock rollback freeze the current state`() {
        val controller = controller()
        controller.recordProcessingDuration(230)
        controller.update(100, RuntimeThermalLevel.SEVERE)

        assertEquals(
            RuntimeThermalMode.LIMITED,
            controller.update(200, RuntimeThermalLevel.UNKNOWN).mode,
        )
        val rollback = controller.update(50, RuntimeThermalLevel.NONE)
        assertEquals(2_760L, rollback.intervalMillis)
        assertFalse(rollback.intervalChanged)
    }

    @Test
    fun `a device already severe learns work duration after its first inference`() {
        val controller = controller()

        assertEquals(66L, controller.update(0, RuntimeThermalLevel.SEVERE).intervalMillis)
        controller.recordProcessingDuration(230)
        val measured = controller.update(250, RuntimeThermalLevel.SEVERE)

        assertEquals(2_760L, measured.intervalMillis)
        assertTrue(measured.intervalChanged)
    }

    @Test
    fun `non adaptive manifest still pauses at critical without changing cadence`() {
        val controller = controller(adaptationAllowed = false)

        val severe = controller.update(0, RuntimeThermalLevel.SEVERE)
        val critical = controller.update(1, RuntimeThermalLevel.CRITICAL)

        assertEquals(33L, severe.intervalMillis)
        assertEquals(RuntimeThermalMode.LIMITED, severe.mode)
        assertEquals(33L, critical.intervalMillis)
        assertEquals(RuntimeThermalMode.PAUSED, critical.mode)
    }

    @Test
    fun `setup uses signed default then adapts to measured workload`() {
        assertEquals(
            100L,
            setupThermalSamplingIntervalMillis(
                mode = RuntimeThermalMode.NORMAL,
                fastestIntervalMillis = 100,
                signedDefaultIntervalMillis = 100,
                signedMaximumIntervalMillis = 2_000,
                adaptationAllowed = true,
            ),
        )
        assertEquals(
            1_400L,
            setupThermalSamplingIntervalMillis(
                mode = RuntimeThermalMode.NORMAL,
                fastestIntervalMillis = 100,
                signedDefaultIntervalMillis = 100,
                signedMaximumIntervalMillis = 2_000,
                adaptationAllowed = true,
                processingDurationMillis = 200,
            ),
        )
        assertEquals(
            1_400L,
            setupThermalSamplingIntervalMillis(
                mode = RuntimeThermalMode.NORMAL,
                fastestIntervalMillis = 100,
                signedDefaultIntervalMillis = 100,
                signedMaximumIntervalMillis = 2_000,
                adaptationAllowed = true,
                processingDurationMillis = 250,
            ),
        )
    }

    private fun controller(adaptationAllowed: Boolean = true) =
        RuntimeThermalCadenceController(
            signedDefaultIntervalMillis = 33,
            signedMinimumIntervalMillis = 33,
            signedMaximumIntervalMillis = 3_000,
            adaptationAllowed = adaptationAllowed,
        )
}
