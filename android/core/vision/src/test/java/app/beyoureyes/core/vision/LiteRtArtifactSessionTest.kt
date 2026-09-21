package app.beyoureyes.core.vision

import org.junit.Assert.assertThrows
import org.junit.Test

class LiteRtArtifactSessionTest {
    @Test
    fun `signed runtime batch may bind a dynamic model dimension`() {
        requireRuntimeShapeFitsSignature(
            shapeSignature = listOf(-1, 224, 224, 3),
            runtimeShape = listOf(4, 224, 224, 3),
        )
    }

    @Test
    fun `fixed dimension drift fails closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireRuntimeShapeFitsSignature(
                shapeSignature = listOf(-1, 192, 192, 3),
                runtimeShape = listOf(4, 224, 224, 3),
            )
        }
    }

    @Test
    fun `runtime rank and nonpositive dimensions fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireRuntimeShapeFitsSignature(
                shapeSignature = listOf(-1, 224, 224, 3),
                runtimeShape = listOf(4, 224, 224),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireRuntimeShapeFitsSignature(
                shapeSignature = listOf(-1, 224, 224, 3),
                runtimeShape = listOf(0, 224, 224, 3),
            )
        }
    }
}
