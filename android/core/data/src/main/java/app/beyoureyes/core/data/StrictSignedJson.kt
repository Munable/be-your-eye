package app.beyoureyes.core.data

import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.erdtman.jcs.JsonCanonicalizer
import java.math.BigDecimal
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

internal const val MAX_SIGNED_CATALOG_BYTES: Int = 2 * 1024 * 1024
internal const val MAX_SIGNED_MANIFEST_BYTES: Int = 2 * 1024 * 1024
internal const val DEFAULT_CATALOG_MAX_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1_000
internal const val MAX_METADATA_CLOCK_SKEW_MILLIS: Long = 5L * 60 * 1_000
internal val STRICT_SHA256 = Regex("^[0-9a-f]{64}$")
private val SAFE_KEY_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val SAFE_IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$")
private val NON_NEGATIVE_INTEGER = Regex("^(?:0|[1-9][0-9]*)$")

class MetadataVerificationException(
    val code: String,
    val jsonPath: String,
    message: String,
) : IllegalArgumentException("$code at $jsonPath: $message")

data class PinnedEd25519PublicKey(
    val keyId: String,
    val rawPublicKeyBase64: String,
) {
    internal val rawPublicKey: ByteArray

    init {
        require(SAFE_KEY_ID.matches(keyId)) { "invalid Ed25519 key id" }
        rawPublicKey = decodeCanonicalBase64(rawPublicKeyBase64, "$keyId.public_key")
        require(rawPublicKey.size == Ed25519Verify.PUBLIC_KEY_LEN) {
            "Ed25519 public keys must contain 32 raw bytes"
        }
    }
}

/** Exactly two rotation slots: the currently trusted key and an optional next key. */
class PinnedEd25519KeyRegistry(
    val current: PinnedEd25519PublicKey?,
    val next: PinnedEd25519PublicKey?,
) {
    private val byId: Map<String, PinnedEd25519PublicKey>

    init {
        val keys = listOfNotNull(current, next)
        require(keys.map(PinnedEd25519PublicKey::keyId).distinct().size == keys.size) {
            "current and next Ed25519 key ids must differ"
        }
        byId = keys.associateBy(PinnedEd25519PublicKey::keyId)
    }

    internal fun requireKey(keyId: String): PinnedEd25519PublicKey = byId[keyId]
        ?: throw MetadataVerificationException(
            code = "unknown_signing_key",
            jsonPath = "$.signature.signing_key_id",
            message = "the signing key is not in the embedded current/next registry",
        )
}

/** Public release trust roots. Private signing keys never enter the Android repository. */
object EmbeddedModelDeliveryPublicKeys {
    val catalog: PinnedEd25519KeyRegistry = PinnedEd25519KeyRegistry(
        current = PinnedEd25519PublicKey(
            keyId = "catalog-key-2026-a",
            rawPublicKeyBase64 = "UtA1YMoUNVD4Oopro6sEp61YDtMQXu5rIqGZZi6BlDQ=",
        ),
        next = PinnedEd25519PublicKey(
            keyId = "catalog-key-2026-b",
            rawPublicKeyBase64 = "5IJ4t5JTAIde8oIbso2R6OB5Y3qGrvbqGiKjXRXuim8=",
        ),
    )
    val manifest: PinnedEd25519KeyRegistry = PinnedEd25519KeyRegistry(
        current = PinnedEd25519PublicKey(
            keyId = "manifest-key-2026-a",
            rawPublicKeyBase64 = "G1WvR2G5iRbzIdr6UOqh8tZ7cvJ/AaTvDyRQfZ8Yg0w=",
        ),
        next = PinnedEd25519PublicKey(
            keyId = "manifest-key-2026-b",
            rawPublicKeyBase64 = "HZ/m6+/9kdbW/Xlvw8FIPukKz8EY+xmKl8p7vkVwLfY=",
        ),
    )
}

internal data class ParsedSignedJson(
    val root: JsonObject,
    val rawBytes: ByteArray,
    val rawSha256: String,
    val canonicalSignedPayload: ByteArray,
    val signature: ParsedDocumentSignature,
)

internal data class ParsedDocumentSignature(
    val signingKeyId: String,
    val value: ByteArray,
)

internal object StrictSignedJson {
    fun parseAndVerify(
        bytes: ByteArray,
        maximumBytes: Int,
        registry: PinnedEd25519KeyRegistry,
    ): ParsedSignedJson {
        val root = parseDocument(bytes, maximumBytes)
        val signatureObject = root.requireObject("signature", "$")
        signatureObject.requireExactKeys(
            setOf("canonicalization", "algorithm", "signing_key_id", "value"),
            "$.signature",
        )
        if (signatureObject.requireString("canonicalization", "$.signature") != "RFC8785") {
            reject("unsupported_canonicalization", "$.signature.canonicalization", "RFC8785 is required")
        }
        if (signatureObject.requireString("algorithm", "$.signature") != "Ed25519") {
            reject("unsupported_signature_algorithm", "$.signature.algorithm", "Ed25519 is required")
        }
        val keyId = signatureObject.requireString("signing_key_id", "$.signature")
        if (!SAFE_KEY_ID.matches(keyId)) reject("invalid_key_id", "$.signature.signing_key_id", "invalid key id")
        val signatureBytes = decodeCanonicalBase64(
            signatureObject.requireString("value", "$.signature"),
            "$.signature.value",
        )
        if (signatureBytes.size != Ed25519Verify.SIGNATURE_LEN) {
            reject("invalid_signature_encoding", "$.signature.value", "Ed25519 signatures contain 64 bytes")
        }
        val payload = root.deepCopy().also { it.remove("signature") }
        val canonicalPayload = canonicalize(payload)
        val publicKey = registry.requireKey(keyId)
        try {
            Ed25519Verify(publicKey.rawPublicKey).verify(signatureBytes, canonicalPayload)
        } catch (_: Exception) {
            reject("invalid_signature", "$.signature.value", "signature verification failed")
        }
        return ParsedSignedJson(
            root = root,
            rawBytes = bytes.copyOf(),
            rawSha256 = sha256Hex(bytes),
            canonicalSignedPayload = canonicalPayload,
            signature = ParsedDocumentSignature(keyId, signatureBytes.copyOf()),
        )
    }

    fun parseUnverified(bytes: ByteArray, maximumBytes: Int): JsonObject =
        parseDocument(bytes, maximumBytes)

    private fun parseDocument(bytes: ByteArray, maximumBytes: Int): JsonObject {
        if (bytes.isEmpty()) reject("empty_document", "$", "document is empty")
        if (bytes.size > maximumBytes) reject("document_too_large", "$", "document exceeds $maximumBytes bytes")
        val utf8 = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            reject("invalid_utf8", "$", "document is not strict UTF-8")
        }
        // JCS parsing rejects duplicate object keys and malformed JSON before Gson builds a tree.
        try {
            JsonCanonicalizer(bytes).encodedUTF8
        } catch (_: Throwable) {
            reject("invalid_json", "$", "malformed JSON or duplicate object key")
        }
        val element = try {
            JsonParser.parseString(utf8)
        } catch (_: Throwable) {
            reject("invalid_json", "$", "malformed JSON")
        }
        if (!element.isJsonObject) reject("invalid_root", "$", "signed document must be an object")
        rejectUnpairedSurrogates(element, "$", depth = 0)
        return element.asJsonObject
    }

    fun canonicalize(element: JsonElement): ByteArray = try {
        JsonCanonicalizer(element.toString().toByteArray(StandardCharsets.UTF_8)).encodedUTF8
    } catch (_: Throwable) {
        reject("canonicalization_failed", "$", "document is outside the RFC8785 input domain")
    }
}

internal fun JsonObject.requireExactKeys(expected: Set<String>, path: String) {
    val actual = keySet()
    if (actual != expected) {
        reject(
            "unexpected_or_missing_field",
            path,
            "expected ${expected.sorted()} but found ${actual.sorted()}",
        )
    }
}

internal fun JsonObject.requireElement(name: String, path: String): JsonElement = get(name)
    ?: reject("missing_field", "$path.$name", "required field is missing")

internal fun JsonObject.requireObject(name: String, path: String): JsonObject {
    val value = requireElement(name, path)
    if (!value.isJsonObject) reject("invalid_type", "$path.$name", "object required")
    return value.asJsonObject
}

internal fun JsonObject.requireArray(name: String, path: String): JsonArray {
    val value = requireElement(name, path)
    if (!value.isJsonArray) reject("invalid_type", "$path.$name", "array required")
    return value.asJsonArray
}

internal fun JsonObject.requireString(
    name: String,
    path: String,
    maximumLength: Int = MAX_METADATA_STRING_CHARACTERS,
): String {
    require(maximumLength > 0)
    val value = requireElement(name, path)
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        reject("invalid_type", "$path.$name", "string required")
    }
    val result = value.asString
    if (result.isEmpty()) reject("empty_string", "$path.$name", "non-empty string required")
    if (result.length > maximumLength) {
        reject(
            "string_too_long",
            "$path.$name",
            "string exceeds $maximumLength characters",
        )
    }
    return result
}

internal fun JsonObject.optionalString(name: String, path: String): String? {
    val value = requireElement(name, path)
    if (value is JsonNull || value.isJsonNull) return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        reject("invalid_type", "$path.$name", "string or null required")
    }
    val result = value.asString
    if (result.isEmpty()) reject("empty_string", "$path.$name", "non-empty string or null required")
    if (result.length > 4_096) reject("string_too_long", "$path.$name", "string exceeds 4096 characters")
    return result
}

private const val MAX_METADATA_STRING_CHARACTERS = 4_096

internal fun JsonObject.requireBoolean(name: String, path: String): Boolean {
    val value = requireElement(name, path)
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
        reject("invalid_type", "$path.$name", "boolean required")
    }
    return value.asBoolean
}

internal fun JsonObject.requireLong(name: String, path: String): Long {
    val value = requireElement(name, path)
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
        reject("invalid_type", "$path.$name", "integer required")
    }
    val raw = value.asJsonPrimitive.toString()
    if (!NON_NEGATIVE_INTEGER.matches(raw)) reject("invalid_integer", "$path.$name", "non-negative integer required")
    return raw.toLongOrNull() ?: reject("integer_out_of_range", "$path.$name", "integer is outside Long range")
}

internal fun JsonObject.optionalLong(name: String, path: String): Long? {
    val value = requireElement(name, path)
    if (value.isJsonNull) return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
        reject("invalid_type", "$path.$name", "integer or null required")
    }
    val raw = value.asJsonPrimitive.toString()
    if (!NON_NEGATIVE_INTEGER.matches(raw)) reject("invalid_integer", "$path.$name", "non-negative integer or null required")
    return raw.toLongOrNull() ?: reject("integer_out_of_range", "$path.$name", "integer is outside Long range")
}

internal fun JsonObject.requireDouble(name: String, path: String): Double {
    val value = requireElement(name, path)
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
        reject("invalid_type", "$path.$name", "number required")
    }
    val parsed = runCatching { BigDecimal(value.asJsonPrimitive.toString()).toDouble() }.getOrNull()
    if (parsed == null || !parsed.isFinite()) reject("invalid_number", "$path.$name", "finite number required")
    return parsed
}

internal fun JsonObject.optionalDouble(name: String, path: String): Double? {
    val value = requireElement(name, path)
    if (value.isJsonNull) return null
    return requireDouble(name, path)
}

internal fun JsonArray.requireNonEmpty(path: String): JsonArray {
    if (size() == 0) reject("empty_array", path, "at least one item is required")
    return this
}

internal fun JsonArray.stringSet(path: String, allowed: Set<String>? = null): Set<String> {
    val values = mapIndexed { index, element ->
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            reject("invalid_type", "$path[$index]", "string required")
        }
        val value = element.asString
        if (value.isEmpty()) reject("empty_string", "$path[$index]", "non-empty string required")
        if (allowed != null && value !in allowed) reject("unknown_enum", "$path[$index]", "unknown value $value")
        value
    }
    if (values.distinct().size != values.size) reject("duplicate_array_item", path, "array items must be unique")
    return values.toCollection(linkedSetOf())
}

internal fun requireIdentifier(value: String, path: String): String {
    if (!SAFE_IDENTIFIER.matches(value)) reject("invalid_identifier", path, "invalid identifier")
    return value
}

internal fun requireSha256(value: String, path: String): String {
    if (!STRICT_SHA256.matches(value)) reject("invalid_sha256", path, "lowercase SHA-256 required")
    return value
}

internal fun requireSemver(value: String, path: String): String {
    if (!Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$").matches(value)) {
        reject("invalid_semver", path, "semantic version required")
    }
    return value
}

internal fun requireIsoInstant(value: String, path: String): Long = try {
    Instant.parse(value).toEpochMilli()
} catch (_: Exception) {
    reject("invalid_timestamp", path, "RFC3339 UTC instant required")
}

internal fun requireAbsoluteSourceUrl(value: String, path: String): String {
    val uri = parseUri(value, path)
    if (!uri.isAbsolute || uri.scheme !in setOf("https", "http") || uri.host.isNullOrEmpty()) {
        reject("invalid_source_url", path, "absolute HTTP(S) source URL required")
    }
    if (uri.userInfo != null || uri.fragment != null) reject("invalid_source_url", path, "credentials/fragments are forbidden")
    return value
}

internal fun requireFixedHttpsUrl(value: String, path: String): String {
    val uri = parseUri(value, path)
    if (!uri.isAbsolute || uri.scheme != "https" || uri.host.isNullOrEmpty()) {
        reject("insecure_or_relative_url", path, "fixed HTTPS URL required")
    }
    if (uri.userInfo != null || uri.fragment != null || uri.query != null) {
        reject("mutable_or_credentialed_url", path, "credentials, query, and fragment are forbidden")
    }
    if (uri.normalize().toASCIIString() != value) reject("noncanonical_url", path, "canonical fixed HTTPS URL required")
    return value
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun decodeCanonicalBase64(value: String, path: String): ByteArray {
    if (value.isEmpty() || value.length % 4 != 0 ||
        !Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$").matches(value)
    ) {
        reject("invalid_base64", path, "canonical padded base64 required")
    }
    val decoded = try {
        Base64.getDecoder().decode(value)
    } catch (_: Exception) {
        reject("invalid_base64", path, "canonical padded base64 required")
    }
    if (Base64.getEncoder().encodeToString(decoded) != value) {
        reject("noncanonical_base64", path, "canonical padded base64 required")
    }
    return decoded
}

internal fun reject(code: String, path: String, message: String): Nothing =
    throw MetadataVerificationException(code, path, message)

private fun parseUri(value: String, path: String): URI = try {
    URI(value)
} catch (_: Exception) {
    reject("invalid_url", path, "URL is malformed")
}

private fun rejectUnpairedSurrogates(element: JsonElement, path: String, depth: Int) {
    if (depth > 64) reject("json_too_deep", path, "maximum nesting depth is 64")
    when {
        element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
            requireWellFormedUnicode(key, "$path.<key>")
            rejectUnpairedSurrogates(value, "$path.$key", depth + 1)
        }
        element.isJsonArray -> element.asJsonArray.forEachIndexed { index, value ->
            rejectUnpairedSurrogates(value, "$path[$index]", depth + 1)
        }
        element.isJsonPrimitive && element.asJsonPrimitive.isString ->
            requireWellFormedUnicode(element.asString, path)
    }
}

private fun requireWellFormedUnicode(value: String, path: String) {
    var index = 0
    while (index < value.length) {
        val current = value[index]
        when {
            Character.isHighSurrogate(current) -> {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    reject("invalid_unicode", path, "unpaired high surrogate")
                }
                index += 2
            }
            Character.isLowSurrogate(current) -> reject("invalid_unicode", path, "unpaired low surrogate")
            else -> index += 1
        }
    }
}
