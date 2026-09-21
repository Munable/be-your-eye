package app.beyoureyes.core.vision

import org.junit.Assert.assertThrows
import org.junit.Test

class OnnxArtifactSessionContractTest {
    @Test
    fun staticAndDynamicDimensionsResolveAgainstConcreteManifestShape() {
        validateOnnxDeclaredShape(
            tensorKind = "input",
            tensorName = "image",
            onnxShape = longArrayOf(-1L, 3L, 48L, -1L),
            manifestShape = listOf(1, 3, 48, 320),
        )
        validateOnnxDeclaredShape(
            tensorKind = "output",
            tensorName = "logits",
            onnxShape = longArrayOf(1L, 40L, 128L),
            manifestShape = listOf(1, 40, 128),
        )
    }

    @Test
    fun staticDimensionMismatchIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validateOnnxDeclaredShape(
                tensorKind = "input",
                tensorName = "image",
                onnxShape = longArrayOf(-1L, 3L, 64L, -1L),
                manifestShape = listOf(1, 3, 48, 320),
            )
        }
    }

    @Test
    fun rankMismatchAndNonConcreteManifestShapeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validateOnnxDeclaredShape(
                tensorKind = "input",
                tensorName = "image",
                onnxShape = longArrayOf(-1L, 3L, -1L),
                manifestShape = listOf(1, 3, 48, 320),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateOnnxDeclaredShape(
                tensorKind = "input",
                tensorName = "image",
                onnxShape = longArrayOf(-1L, 3L, -1L, -1L),
                manifestShape = listOf(1, 3, 48, -1),
            )
        }
    }

    @Test
    fun actualOutputMustExactlyMatchConcreteManifestShape() {
        validateOnnxActualOutputShape(
            tensorName = "logits",
            actualShape = longArrayOf(1L, 40L, 128L),
            manifestShape = listOf(1, 40, 128),
        )
        assertThrows(IllegalArgumentException::class.java) {
            validateOnnxActualOutputShape(
                tensorName = "logits",
                actualShape = longArrayOf(1L, 39L, 128L),
                manifestShape = listOf(1, 40, 128),
            )
        }
    }
}
