package app.beyoureyes.core.vision

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CtcVocabularyCodecTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val repoRoot = File(checkNotNull(System.getProperty("beYourEyes.repoRoot")))

    @Test
    fun `numeric and third party large vocabularies use the same finite sidecar family`() {
        val numeric = CtcVocabularyCodec.read(
            repoRoot.resolve("test-vectors/valid/ctc-vocabulary-numeric-13.json"),
            decoding(blankIndex = 12),
            expectedClassCount = 13,
        )
        val large = CtcVocabularyCodec.read(
            repoRoot.resolve("test-vectors/valid/ctc-vocabulary-third-party-18710.json"),
            decoding(blankIndex = 0),
            expectedClassCount = 18_710,
        )

        assertEquals(listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "-", ".", ""), numeric.tokens)
        assertEquals(0, large.blankIndex)
        assertEquals(18_710, large.tokens.size)
        assertEquals("", large.tokens.first())
        assertEquals(" ", large.tokens.last())

        val trailingSpace = temporaryFolder.newFile("space-vocabulary.json").apply {
            writeText(
                """{"schema_version":"1.0","family":"ctc_vocabulary_v1","tokens":["","0"," "]}""",
            )
        }
        val withSpace = CtcVocabularyCodec.read(
            trailingSpace,
            decoding(blankIndex = 0),
            expectedClassCount = 3,
        )
        assertEquals(" ", withSpace.tokens.last())
    }

    @Test
    fun `unknown malformed duplicate and illegal vocabulary content fails closed`() {
        val rejected = listOf(
            "unknown field" to
                """{"schema_version":"1.0","family":"ctc_vocabulary_v1","tokens":["0",""],"vendor":"x"}""",
            "duplicate field" to
                """{"schema_version":"1.0","family":"ctc_vocabulary_v1","tokens":["0",""],"tokens":["1",""]}""",
            "unknown schema" to
                """{"schema_version":"2.0","family":"ctc_vocabulary_v1","tokens":["0",""]}""",
            "unknown family" to
                """{"schema_version":"1.0","family":"vendor_vocab","tokens":["0",""]}""",
            "duplicate token" to
                """{"schema_version":"1.0","family":"ctc_vocabulary_v1","tokens":["0","0",""]}""",
            "wrong blank token" to
                """{"schema_version":"1.0","family":"ctc_vocabulary_v1","tokens":["0","1"]}""",
            "extra empty token" to
                """{"schema_version":"1.0","family":"ctc_vocabulary_v1","tokens":["","1",""]}""",
            "control token" to
                """{"schema_version":"1.0","family":"ctc_vocabulary_v1","tokens":["\u0001",""]}""",
            "noncharacter token" to
                """{"schema_version":"1.0","family":"ctc_vocabulary_v1","tokens":["\uFDD0",""]}""",
            "unpaired surrogate token" to
                """{"schema_version":"1.0","family":"ctc_vocabulary_v1","tokens":["\uD800",""]}""",
        )
        rejected.forEachIndexed { index, (name, document) ->
            val file = temporaryFolder.newFile("invalid-$index.json").apply {
                writeBytes(document.toByteArray(Charsets.UTF_8))
            }
            assertTrue(name, runCatching {
                CtcVocabularyCodec.read(file, decoding(blankIndex = 1), expectedClassCount = 2)
            }.isFailure)
        }
    }

    @Test
    fun `dictionary count and declared blank index must match logits classes`() {
        val file = temporaryFolder.newFile("vocabulary.json").apply {
            writeText(
                """{"schema_version":"1.0","family":"ctc_vocabulary_v1","tokens":["0","1",""]}""",
            )
        }

        assertTrue(runCatching {
            CtcVocabularyCodec.read(file, decoding(blankIndex = 2), expectedClassCount = 4)
        }.isFailure)
        assertTrue(runCatching {
            CtcVocabularyCodec.read(file, decoding(blankIndex = 3), expectedClassCount = 3)
        }.isFailure)
    }

    private fun decoding(blankIndex: Int) = CtcDecodingSpec(
        vocabularyArtifactRole = "vocabulary",
        blankIndex = blankIndex,
        indexSemantics = CtcIndexSemantics.ZERO_BASED_TOKEN_ORDER_V1,
        collapseSemantics = CtcCollapseSemantics.CTC_GREEDY_ARGMAX_V1,
        scoreSemantics = CtcScoreSemantics.UNNORMALIZED_LOGITS_SOFTMAX_V1,
    )
}
