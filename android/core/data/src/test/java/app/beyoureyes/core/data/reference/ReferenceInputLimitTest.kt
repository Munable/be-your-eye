package app.beyoureyes.core.data.reference

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReferenceInputLimitTest {
    @Test
    fun runtimeDecodeUsesPowerOfTwoSamplingWithA1024PixelBound() {
        assertEquals(1, referenceRuntimeInSampleSize(1_024, 768))
        assertEquals(2, referenceRuntimeInSampleSize(1_025, 768))
        assertEquals(2, referenceRuntimeInSampleSize(2_048, 1_536))
        assertEquals(4, referenceRuntimeInSampleSize(3_072, 4_096))
    }

    @Test
    fun exactLimitIsCopiedWithoutChangingBytes() {
        val source = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()

        val count = readReferenceBytesWithLimit(
            input = ByteArrayInputStream(source),
            maximumBytes = source.size.toLong(),
        ) { bytes, length -> output.write(bytes, 0, length) }

        assertEquals(source.size.toLong(), count)
        assertArrayEquals(source, output.toByteArray())
    }

    @Test
    fun oversizedAndEmptyPickerStreamsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            readReferenceBytesWithLimit(
                input = ByteArrayInputStream(ByteArray(5)),
                maximumBytes = 4,
            ) { _, _ -> }
        }
        assertThrows(IllegalArgumentException::class.java) {
            readReferenceBytesWithLimit(
                input = ByteArrayInputStream(ByteArray(0)),
                maximumBytes = 4,
            ) { _, _ -> }
        }
    }
}
