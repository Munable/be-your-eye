package app.beyoureyes.core.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeuralReferenceRuntimeInstrumentedTest {
    @Test
    fun productionRegistryExposesOnlyLocalizedReferencePreprocessing() {
        val provider = ReferenceImageProvider { null }
        val cache = object : ReferenceEmbeddingCache {
            override fun load(key: ReferenceEmbeddingCacheKey, expectedDimension: Int) = null
            override fun store(key: ReferenceEmbeddingCacheKey, normalizedEmbedding: FloatArray) = Unit
            override fun invalidateReferences(referenceSha256: Set<String>) = Unit
        }

        val registry = ManifestRuntimeComponents.registry(
            referenceImageProvider = provider,
            referenceEmbeddingCache = cache,
        )

        assertNotNull(registry.preprocessor(NeuralReferenceRuntimeComponents.PREPROCESS_ID))
        assertNull(registry.preprocessor("rgb_direct_resize_0_1_v1"))
    }
}
