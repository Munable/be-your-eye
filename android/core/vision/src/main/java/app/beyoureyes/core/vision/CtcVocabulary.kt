package app.beyoureyes.core.vision

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Immutable token order loaded from a hash-verified model-package sidecar. */
internal data class CtcVocabulary(
    val tokens: List<String>,
    val blankIndex: Int,
)

/**
 * Strict finite parser for `ctc_vocabulary_v1`.
 *
 * Artifact bytes are size/SHA-256 checked by [ModelPackageRuntimeFactory] before this parser runs.
 * This layer then rejects unknown/duplicate fields, malformed Unicode, duplicate/illegal tokens,
 * blank/index drift and logits-class-count drift before an adapter can be created.
 */
internal object CtcVocabularyCodec {
    fun read(
        file: File,
        decoding: CtcDecodingSpec,
        expectedClassCount: Int,
    ): CtcVocabulary {
        require(file.isFile && file.length() in 1..MAX_VOCABULARY_BYTES) {
            "CTC vocabulary sidecar size is outside the supported range"
        }
        require(expectedClassCount in MIN_CLASS_COUNT..MAX_CLASS_COUNT) {
            "CTC class count is outside the supported range"
        }
        require(decoding.indexSemantics == CtcIndexSemantics.ZERO_BASED_TOKEN_ORDER_V1)
        require(decoding.collapseSemantics == CtcCollapseSemantics.CTC_GREEDY_ARGMAX_V1)

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val fields = linkedSetOf<String>()
        var schemaVersion: String? = null
        var family: String? = null
        var tokens: List<String>? = null
        FileInputStream(file).use { input ->
            InputStreamReader(input, decoder).use { text ->
                JsonReader(text).use { reader ->
                    reader.strictness = Strictness.STRICT
                    require(reader.peek() == JsonToken.BEGIN_OBJECT) {
                        "CTC vocabulary root must be an object"
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        require(reader.peek() == JsonToken.NAME) {
                            "CTC vocabulary object contains an invalid member"
                        }
                        val name = reader.nextName()
                        require(name in REQUIRED_FIELDS) { "unknown CTC vocabulary field: $name" }
                        require(fields.add(name)) { "duplicate CTC vocabulary field: $name" }
                        when (name) {
                            "schema_version" -> schemaVersion = reader.requireString(name)
                            "family" -> family = reader.requireString(name)
                            "tokens" -> tokens = reader.readTokens(expectedClassCount)
                        }
                    }
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT) {
                        "trailing CTC vocabulary content is forbidden"
                    }
                }
            }
        }
        require(fields == REQUIRED_FIELDS) { "CTC vocabulary fields are incomplete" }
        require(schemaVersion == SCHEMA_VERSION) { "unsupported CTC vocabulary schema_version" }
        require(family == FAMILY) { "unsupported CTC vocabulary family" }
        val resolvedTokens = checkNotNull(tokens)
        require(resolvedTokens.size == expectedClassCount) {
            "CTC vocabulary size does not match logits class count"
        }
        require(decoding.blankIndex in resolvedTokens.indices) {
            "CTC blank index is outside the vocabulary"
        }
        require(resolvedTokens[decoding.blankIndex].isEmpty()) {
            "CTC blank token must be the empty string"
        }
        require(resolvedTokens.withIndex().all { (index, token) ->
            index == decoding.blankIndex || token.isNotEmpty()
        }) { "only the declared CTC blank token may be empty" }
        require(resolvedTokens.toSet().size == resolvedTokens.size) {
            "CTC vocabulary tokens must be unique"
        }
        resolvedTokens.forEachIndexed { index, token -> validateToken(index, token) }
        return CtcVocabulary(resolvedTokens.toList(), decoding.blankIndex)
    }

    private fun JsonReader.requireString(field: String): String {
        require(peek() == JsonToken.STRING) { "$field must be a string" }
        return nextString()
    }

    private fun JsonReader.readTokens(expectedClassCount: Int): List<String> {
        require(peek() == JsonToken.BEGIN_ARRAY) { "tokens must be an array" }
        beginArray()
        val result = ArrayList<String>(expectedClassCount)
        while (hasNext()) {
            require(result.size < MAX_CLASS_COUNT) { "CTC vocabulary is too large" }
            require(peek() == JsonToken.STRING) { "every CTC token must be a string" }
            result += nextString()
        }
        endArray()
        return result
    }

    private fun validateToken(index: Int, token: String) {
        require(token.codePointCount(0, token.length) <= MAX_TOKEN_CODE_POINTS) {
            "CTC token $index is too long"
        }
        var offset = 0
        while (offset < token.length) {
            val current = token[offset]
            require(!Character.isLowSurrogate(current)) {
                "CTC token $index contains an unpaired surrogate"
            }
            if (Character.isHighSurrogate(current)) {
                require(offset + 1 < token.length && Character.isLowSurrogate(token[offset + 1])) {
                    "CTC token $index contains an unpaired surrogate"
                }
            }
            val codePoint = Character.codePointAt(token, offset)
            require(!Character.isISOControl(codePoint) && !codePoint.isUnicodeNoncharacter()) {
                "CTC token $index contains a forbidden code point"
            }
            offset += Character.charCount(codePoint)
        }
    }

    private fun Int.isUnicodeNoncharacter(): Boolean =
        this in 0xFDD0..0xFDEF || (this and 0xFFFF) in 0xFFFE..0xFFFF

    private val REQUIRED_FIELDS = setOf("schema_version", "family", "tokens")
    private const val SCHEMA_VERSION = "1.0"
    private const val FAMILY = "ctc_vocabulary_v1"
    private const val MIN_CLASS_COUNT = 2
    private const val MAX_CLASS_COUNT = 65_536
    private const val MAX_TOKEN_CODE_POINTS = 64
    private const val MAX_VOCABULARY_BYTES = 4L * 1024 * 1024
}
